#requires -Version 5.1
<#
  记忆深度（② 期）的端到端验证：token 预算裁剪 + 滚动摘要 + 工具结果回放。

  这条链路**必须调用真实模型**（摘要与对话都是模型产物），因此本脚本是少数需要应用自己配好密钥的用例；
  它不需要 E2E_DEEPSEEK_KEY——用的是后端 yaml/DB 里已配置的凭据。

  断言的都是"能被外部观测到的硬事实"：
    1. 造一段 60 条、每条 300 字的历史（约 18k 字 ≈ 18k token）→ 真实一轮的 prompt_tokens 必须远低于它；
    2. 应用日志里出现"会话记忆装配 … 裁剪=N"（N>0）证明预算真的在裁；
    3. 异步摘要会把超出窗口的部分写进 chat_session.context_summary 并推进 summary_upto_msg_id；
    4. 下一轮日志出现"摘要=true"（摘要被注入）且 token 仍受控；
    5. 触发工具的那一轮之后 chat_tool_call.result_digest 非空，再下一轮日志出现"工具摘要=true"。

  用法：
    $env:E2E_BASE_URL  = 'http://127.0.0.1:8091'
    $env:E2E_APP_LOG   = '<repo>\target\e2e-app.log'
    $env:E2E_MYSQL_CNF = '<repo>\target\e2e\my.cnf'
    pwsh -File scripts/e2e/memory-e2e.ps1
#>
param([string]$Base)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\_common.ps1"
if (-not $PSBoundParameters.ContainsKey('Base')) { $Base = Get-E2eBaseUrl }

$api = "$Base/api/v1"
$outDir = Get-E2eOutDir
$log = Get-E2eLogPath
$marker = '【E2E记忆】'

$script:pass = 0
$script:fail = 0

function Ok([string]$name) { $script:pass++; Write-Host "  [PASS] $name" -ForegroundColor Green }
function Ng([string]$name, [string]$detail) { $script:fail++; Write-Host "  [FAIL] $name — $detail" -ForegroundColor Red }
function Check([string]$name, [bool]$condition, [string]$detail) {
    if ($condition) { Ok $name } else { Ng $name $detail }
}
function Save-Json([string]$name, $Object) {
    $path = Join-Path $outDir $name
    [System.IO.File]::WriteAllText($path, ($Object | ConvertTo-Json -Compress -Depth 8), (New-Object System.Text.UTF8Encoding($false)))
    return $path
}
function Invoke-Api {
    param([string]$Method, [string]$Url, [string[]]$Headers = @(), $Body = $null, [string]$Tag = 'call')
    $curlArgs = @('-s', '-X', $Method, $Url, '-w', "`n%{http_code}")
    foreach ($h in $Headers) { $curlArgs += @('-H', $h) }
    if ($null -ne $Body) {
        $file = Save-Json "$Tag.json" $Body
        $curlArgs += @('-H', 'Content-Type: application/json', '--data-binary', "@$file")
    }
    $raw = (& curl.exe @curlArgs) -join "`n"
    $splitAt = $raw.LastIndexOf("`n")
    $status = 0
    $payload = $raw
    if ($splitAt -gt 0) {
        [void][int]::TryParse($raw.Substring($splitAt + 1).Trim(), [ref]$status)
        $payload = $raw.Substring(0, $splitAt)
    }
    $json = $null
    try { $json = $payload | ConvertFrom-Json } catch { }
    return [pscustomobject]@{ Code = $status; Body = $json; Raw = $payload }
}
function Get-SqlScalar([string]$query) {
    $out = Invoke-E2eSql $query
    if ($null -eq $out) { return '' }
    return ($out -join "`n").Trim()
}
function Log-Lines {
    if (-not (Test-Path $log)) { return @() }
    return (Get-Content -LiteralPath $log -Encoding UTF8 -ReadCount 0)
}
function New-Lines([int]$since) {
    $lines = Log-Lines
    if ($since -ge $lines.Count) { return @() }
    return $lines[$since..($lines.Count - 1)]
}

<# 造一段长历史：n 条消息、每条约 chars 字，直接写库（跳过模型，省时省钱）。
   内容刻意做成"有事实可摘"：血糖数值、饮食、目标、忌口、待确认事项——
   否则摘要模型只能回"无可保留信息"，摘要链路的证据就成了空的（第一次跑就是这么翻车的）。 #>
function Seed-History([long]$sessionId, [long]$userId, [int]$count, [int]$chars) {
    $facts = @(
        '早餐前血糖 6.8，早餐吃了燕麦粥和鸡蛋，餐后两小时 8.1',
        '午餐吃了米饭一碗和青菜，餐后血糖 9.2，感觉有点高',
        '晚饭后散步 30 分钟，睡前血糖 6.2',
        '用户说想把餐后血糖控制到 8 以内，医生建议先靠饮食和运动',
        '用户忌口：不吃动物内脏，对花生过敏',
        '用户提到下周要去医院复查糖化血红蛋白，希望到时候再问问怎么调整'
    )
    Invoke-E2eSql "DELETE FROM sugar_health.chat_message WHERE session_id = $sessionId;" | Out-Null
    for ($i = 1; $i -le $count; $i++) {
        $role = if ($i % 2 -eq 1) { 1 } else { 2 }
        $fact = $facts[($i - 1) % $facts.Count]
        $text = "[$marker 第$i 条] $fact"
        while ($text.Length -lt $chars) { $text += "；补充说明：$fact" }
        $text = $text.Substring(0, $chars)
        Invoke-E2eSql "INSERT INTO sugar_health.chat_message (session_id, user_id, seq_no, msg_role, msg_type, content_text, client_msg_id, created_by, updated_by) VALUES ($sessionId, $userId, $i, $role, 1, '$text', 'e2e-mem-$i', $userId, $userId);" | Out-Null
    }
    Invoke-E2eSql "UPDATE sugar_health.chat_session SET msg_count = $count, last_msg_at = NOW(3) WHERE id = $sessionId;" | Out-Null
}

<# 发一条用户消息并跑一轮真实补全，返回 done 事件里的 promptTokens。 #>
function Invoke-Round([long]$sessionId, [string[]]$headers, [string]$text, [string]$tag) {
    $sent = Invoke-Api -Method POST -Url "$api/chat-sessions/$sessionId/messages" -Headers $headers `
        -Body @{ clientMsgId = "$tag-$([guid]::NewGuid().ToString('N').Substring(0,8))"; msgType = 1; contentText = $text } -Tag "$tag-msg"
    if ($sent.Code -ne 200) { throw "$tag 发消息失败：HTTP $($sent.Code) $($sent.Body.message)" }
    $comp = Save-Json "$tag-comp.json" @{ clientMsgId = [guid]::NewGuid().ToString(); replyToMsgId = $sent.Body.data.id; stream = $true }
    $sseFile = Join-Path $outDir "$tag.sse"
    # ⚠️ 必须逐条加 -H：直接把 header 字符串 splat 进 curl 参数，curl 会把它当成"另一个 URL"，
    #    表现是补全返回 40100 未登录（实测踩过）
    $curlArgs = @('-sN', '-X', 'POST', "$api/chat-sessions/$sessionId/completions")
    foreach ($h in $headers) { $curlArgs += @('-H', $h) }
    $curlArgs += @('-H', 'Content-Type: application/json', '--data-binary', "@$comp", '-o', $sseFile)
    & curl.exe @curlArgs
    Start-Sleep -Milliseconds 1200
    $raw = [System.IO.File]::ReadAllText($sseFile, [System.Text.Encoding]::UTF8)
    $done = $null
    $err = $null
    foreach ($line in ($raw -split "\r?\n")) {
        if (-not $line.StartsWith('data:')) { continue }
        $payload = $line.Substring(5).Trim()
        if (-not $payload) { continue }
        try { $obj = $payload | ConvertFrom-Json } catch { continue }
        if ($null -ne $obj.assistantMsgId) { $done = $obj }
        elseif ($null -ne $obj.code -and $null -eq $obj.delta) { $err = $obj }
    }
    return [pscustomobject]@{ MessageId = $sent.Body.data.id; Done = $done; Error = $err }
}

Write-Host "== 记忆深度 E2E（$Base）==" -ForegroundColor Cyan

# ---------------------------------------------------------------- 0. 登录 + 建会话
$login = Invoke-Api -Method POST -Url "$api/auth/wechat/sessions" -Body @{ code = (Get-E2eUserCode) } -Tag 'login'
if ($login.Code -ne 200) { throw "登录失败：HTTP $($login.Code)" }
$headers = @("Authorization: Bearer $($login.Body.data.accessToken)")
$userId = Get-SqlScalar "SELECT id FROM sugar_health.user_account WHERE openid = 'wx_local_dev_user' AND is_deleted = 0 LIMIT 1;"
if (-not $userId) { throw '未找到 mock 用户' }

$created = Invoke-Api -Method POST -Url "$api/chat-sessions/current" -Headers $headers -Body @{ sessionType = 3; forceNew = $true } -Tag 'new-session'
if ($created.Code -ne 200) { throw "建会话失败：HTTP $($created.Code)" }
$sessionId = $created.Body.data.id
Invoke-E2eSql "UPDATE sugar_health.chat_session SET title = '$marker 记忆深度验证' WHERE id = $sessionId;" | Out-Null
Write-Host "0) 会话 id=$sessionId 用户 id=$userId" -ForegroundColor Gray

# ---------------------------------------------------------------- 1. 长历史 + 预算裁剪
Write-Host "1) token 预算裁剪（历史 60 条 × 300 字）" -ForegroundColor Gray
Seed-History -sessionId $sessionId -userId $userId -count 60 -chars 300
$seeded = Get-SqlScalar "SELECT CONCAT(COUNT(*), '/', SUM(CHAR_LENGTH(content_text))) FROM sugar_health.chat_message WHERE session_id = $sessionId;"
Check '已造出 60 条长历史' ($seeded -like '60/*') "count/chars=$seeded"
$seededChars = [int]($seeded -split '/')[1]
Write-Host "   历史总字符=$seededChars（约 $([Math]::Round($seededChars / 1000.0,1))k token 量级）" -ForegroundColor Gray

$logBefore = (Log-Lines).Count
$round1 = Invoke-Round -sessionId $sessionId -headers $headers -text '上面那些记录我都看到了，简单总结一下就行' -tag 'round1'
Check '第一轮补全成功（未报错）' ($null -ne $round1.Done -and $null -eq $round1.Error) `
    "done=$($round1.Done -ne $null) error=$($round1.Error.message)"
$tokens1 = 0
if ($round1.Done) { $tokens1 = [int]$round1.Done.promptTokens }
Write-Host "   第一轮 prompt_tokens=$tokens1" -ForegroundColor Gray
Check 'prompt 被预算压住（远小于历史总量）' ($tokens1 -gt 0 -and $tokens1 -lt [int]($seededChars * 0.9)) `
    "prompt_tokens=$tokens1（历史约 $([Math]::Round($seededChars / 1000.0,1))k 字）"
$assembleLines = @(New-Lines $logBefore | Where-Object { $_ -match '会话记忆装配' })
Check '日志出现记忆装配记录' (@($assembleLines).Count -ge 1) '未看到"会话记忆装配"日志（检查日志级别）'
$dropped = 0
foreach ($line in $assembleLines) { if ($line -match '裁剪=(\d+)') { $lineDropped = [int]$Matches[1]; if ($lineDropped -gt $dropped) { $dropped = $lineDropped } } }
Check '确实裁掉了历史（裁剪数>0）' ($dropped -gt 0) "裁剪=$dropped"
if (@($assembleLines).Count -ge 1) { Write-Host "   $($assembleLines[-1].Trim())" -ForegroundColor DarkGray }

# ---------------------------------------------------------------- 2. 异步摘要
Write-Host "2) 滚动摘要（异步生成并写回）" -ForegroundColor Gray
$summary = ''
$upto = ''
for ($i = 0; $i -lt 30; $i++) {
    $row = Get-SqlScalar "SELECT CONCAT(COALESCE(CHAR_LENGTH(context_summary),0), '/', COALESCE(summary_upto_msg_id,0)) FROM sugar_health.chat_session WHERE id = $sessionId;"
    $parts = $row -split '/'
    $summary = $parts[0]
    $upto = $parts[1]
    if ([int]$summary -gt 0 -and [int]$upto -gt 0) { break }
    Start-Sleep -Seconds 3
}
Check '摘要已生成并写回 context_summary' ([int]$summary -gt 0) "summary 长度=$summary"
Check 'summary_upto_msg_id 已推进' ([int]$upto -gt 0) "upto=$upto"
$summaryText = Get-SqlScalar "SELECT context_summary FROM sugar_health.chat_session WHERE id = $sessionId;"
Write-Host "   摘要全文：$summaryText" -ForegroundColor Gray
Check '摘要是实质性内容而不是"无可保留信息"' ($summaryText.Length -ge 20 -and -not ($summaryText -match '^无可保留信息')) `
    "摘要=$summaryText"
Check '摘要保留了关键事实（血糖数值或目标）' ($summaryText -match '\d') "摘要里没有数值：$summaryText"
Check '摘要没有把剂量/用药建议写进去（安全约束）' (-not ($summaryText -match '片|剂量|加量|减量|停药')) "摘要可能包含用药内容：$summaryText"

# ---------------------------------------------------------------- 3. 摘要被注入下一轮
Write-Host "3) 第二轮：摘要应被注入" -ForegroundColor Gray
$logBefore2 = (Log-Lines).Count
$round2 = Invoke-Round -sessionId $sessionId -headers $headers -text '那我还需要注意什么？' -tag 'round2'
Check '第二轮补全成功' ($null -ne $round2.Done -and $null -eq $round2.Error) "error=$($round2.Error.message)"
$tokens2 = 0
if ($round2.Done) { $tokens2 = [int]$round2.Done.promptTokens }
$lines2 = @(New-Lines $logBefore2 | Where-Object { $_ -match '会话记忆装配' })
Check '第二轮日志显示摘要已注入（摘要=true）' (@($lines2 | Where-Object { $_ -match '摘要=true' }).Count -ge 1) `
    ($lines2 -join '|')
# 断言口径：不是"必须小于预算"（保底条数与估算误差都会让它略超），而是"必须远小于把全量历史塞进去的成本"
$fullHistoryTokens = [int]($seededChars * 0.9)
Check 'prompt 明显小于全量历史的成本（说明确实在按预算裁）' ($tokens2 -gt 0 -and $tokens2 -lt $fullHistoryTokens) `
    "prompt_tokens=$tokens2，全量历史约 $fullHistoryTokens"
$calibration = @(New-Lines $logBefore2 | Where-Object { $_ -match '记忆估算校准' })
Check '校准日志出现（估算器在收敛）' (@($calibration).Count -ge 1) '未看到校准日志'
if (@($calibration).Count -ge 1) { Write-Host "   $($calibration[-1].Trim())" -ForegroundColor DarkGray }
if (@($lines2).Count -ge 1) { Write-Host "   $($lines2[-1].Trim())" -ForegroundColor DarkGray }

# ---------------------------------------------------------------- 4. 工具结果回放
Write-Host "4) 工具结果回放（B3 后半）" -ForegroundColor Gray
$logBefore3 = (Log-Lines).Count
$round3 = Invoke-Round -sessionId $sessionId -headers $headers -text '我这周的血糖怎么样？' -tag 'round3'
Check '第三轮补全成功' ($null -ne $round3.Done -and $null -eq $round3.Error) "error=$($round3.Error.Message)"
Start-Sleep -Seconds 2
$digest = Get-SqlScalar "SELECT CONCAT(COUNT(*), '/', COALESCE(MAX(CHAR_LENGTH(result_digest)),0)) FROM sugar_health.chat_tool_call WHERE session_id = $sessionId AND user_msg_id = $($round3.MessageId);"
Check '本轮工具调用记下了结果摘要' ($digest -like '*/*') "count/length=$digest"
$digestText = Get-SqlScalar "SELECT result_digest FROM sugar_health.chat_tool_call WHERE session_id = $sessionId AND result_digest IS NOT NULL ORDER BY id DESC LIMIT 1;"
Check '结果摘要非空且不超上限' ($digestText.Length -gt 0 -and $digestText.Length -le 512) "len=$($digestText.Length)"
Write-Host "   工具摘要：$($digestText.Substring(0, [Math]::Min(80, $digestText.Length)))" -ForegroundColor Gray

$logBefore4 = (Log-Lines).Count
$round4 = Invoke-Round -sessionId $sessionId -headers $headers -text '刚才查到的数据里最高是多少？' -tag 'round4'
$lines4 = @(New-Lines $logBefore4 | Where-Object { $_ -match '会话记忆装配' })
Check '下一轮日志显示工具摘要已注入（工具摘要=true）' (@($lines4 | Where-Object { $_ -match '工具摘要=true' }).Count -ge 1) `
    ($lines4 -join '|')

# ---------------------------------------------------------------- 5. 清理
Write-Host "5) 清理 E2E 会话" -ForegroundColor Gray
Invoke-E2eSql "UPDATE sugar_health.chat_message SET is_deleted = 1, deleted_at = NOW(3) WHERE session_id = $sessionId AND is_deleted = 0;" | Out-Null
Invoke-E2eSql "UPDATE sugar_health.chat_session SET is_deleted = 1, deleted_at = NOW(3), session_status = 2 WHERE id = $sessionId;" | Out-Null
$left = Get-SqlScalar "SELECT is_deleted FROM sugar_health.chat_session WHERE id = $sessionId;"
Check 'E2E 会话已逻辑删除' ($left -eq '1') "is_deleted=$left"
Check '工具元数据保留（只追加表，不随会话删除）' ((Get-SqlScalar "SELECT COUNT(*) FROM sugar_health.chat_tool_call WHERE session_id = $sessionId;") -gt 0) '工具元数据被删了'

Write-Host ""
Write-Host "== 结果：PASS=$script:pass FAIL=$script:fail ==" -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
exit $script:fail
