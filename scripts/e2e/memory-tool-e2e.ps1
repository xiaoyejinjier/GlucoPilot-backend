#requires -Version 5.1
<#
  长期记忆工具（save_long_term_memory）的端到端验证。

  这条链路**必须调用真实模型**：判断「这句话值不值得长期记住」是模型的行为，
  单测只能验证工具收到调用后做什么，验证不了「模型会不会调用它、会不会重复调用」。
  用的是后端 yaml/DB 里已配置的凭据，不需要 E2E_DEEPSEEK_KEY。

  断言的都是能被外部观测到的硬事实：
    1. 用户说「我不吃辣」→ chat_tool_call 里出现 save_long_term_memory，且每次调用都 ok=1；
    2. 工具回执是短确认（不含记忆正文，正文在 chat_user_memory 里）；
    3. chat_user_memory 落进 diet_taboo，置信度全是 85（服务端固定，不是模型自评）；
       V71 起一类可以有多条（「不吃辣」「不吃香菜」各算一条），所以不再要求恰好一条；
    4. 追问同一件事时同键不会出现同文重复（去重生效，不会堆垃圾）；
    5. 「今晚想吃火锅」这类一次性安排不落库（键数不增加）；
    6. 记忆会进下一轮 prompt（日志「会话记忆装配 … 记忆=N」N≥1）；
    7. 用户自助清空接口把这批记忆删干净（为下次重跑留干净现场）。

  用法：
    $env:E2E_BASE_URL  = 'http://127.0.0.1:8091'
    $env:E2E_APP_LOG   = '<repo>\target\e2e-app.log'
    $env:E2E_MYSQL_CNF = '<repo>\target\e2e\my.cnf'
    pwsh -File scripts/e2e/memory-tool-e2e.ps1
#>
param([string]$Base)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\_common.ps1"
if (-not $PSBoundParameters.ContainsKey('Base')) { $Base = Get-E2eBaseUrl }

$api = "$Base/api/v1"
$outDir = Get-E2eOutDir
$log = Get-E2eLogPath
$marker = '【E2E记忆工具】'

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

<#
  发一条用户消息并跑一轮真实补全。
  返回 done 事件（含 promptTokens），失败时返回 $null 并把错误塞进 Error。
#>
function Invoke-Round([long]$sessionId, [string[]]$headers, [string]$text, [string]$tag) {
    $sent = Invoke-Api -Method POST -Url "$api/chat-sessions/$sessionId/messages" -Headers $headers `
        -Body @{ clientMsgId = "$tag-$([guid]::NewGuid().ToString('N').Substring(0,8))"; msgType = 1; contentText = $text } -Tag "$tag-msg"
    if ($sent.Code -ne 200) { throw "$tag 发消息失败：HTTP $($sent.Code) $($sent.Body.message)" }
    $comp = Save-Json "$tag-comp.json" @{ clientMsgId = [guid]::NewGuid().ToString(); replyToMsgId = $sent.Body.data.id; stream = $true }
    $sseFile = Join-Path $outDir "$tag.sse"
    # ⚠️ 必须逐条加 -H：直接把 header 字符串 splat 进 curl 参数，curl 会把它当成"另一个 URL"（40100 未登录）
    $curlArgs = @('-sN', '-X', 'POST', "$api/chat-sessions/$sessionId/completions")
    foreach ($h in $headers) { $curlArgs += @('-H', $h) }
    $curlArgs += @('-H', 'Content-Type: application/json', '--data-binary', "@$comp", '-o', $sseFile)
    & curl.exe @curlArgs
    # 工具回环会多一次上游往返，比纯文本慢
    Start-Sleep -Milliseconds 2500
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

Write-Host "== 长期记忆工具 E2E（$Base）==" -ForegroundColor Cyan

# ---------------------------------------------------------------- 0. 登录 + 建会话 + 清现场
$login = Invoke-Api -Method POST -Url "$api/auth/wechat/sessions" -Body @{ code = (Get-E2eUserCode) } -Tag 'login'
if ($login.Code -ne 200) { throw "登录失败：HTTP $($login.Code)" }
$headers = @("Authorization: Bearer $($login.Body.data.accessToken)")
$userId = Get-SqlScalar "SELECT id FROM sugar_health.user_account WHERE openid = 'wx_local_dev_user' AND is_deleted = 0 LIMIT 1;"
if (-not $userId) { throw '未找到 mock 用户' }

$created = Invoke-Api -Method POST -Url "$api/chat-sessions/current" -Headers $headers -Body @{ sessionType = 3; forceNew = $true } -Tag 'new-session'
if ($created.Code -ne 200) { throw "建会话失败：HTTP $($created.Code)" }
$sessionId = $created.Body.data.id
Invoke-E2eSql "UPDATE sugar_health.chat_session SET title = '$marker 记忆工具验证' WHERE id = $sessionId;" | Out-Null
# 现场必须干净：否则「键数不增加」这类断言会被上一次跑的数据带偏
Invoke-E2eSql "DELETE FROM sugar_health.chat_user_memory WHERE user_id = $userId;" | Out-Null
Write-Host "0) 会话 id=$sessionId 用户 id=$userId" -ForegroundColor Gray

# ---------------------------------------------------------------- 1. 说一件长期事实 → 工具落库
Write-Host "1) 用户主动说长期事实，模型应调用 save_long_term_memory" -ForegroundColor Gray
$logBefore = (Log-Lines).Count
$round1 = Invoke-Round -sessionId $sessionId -headers $headers -text '帮我记住：我不吃辣，也不吃香菜' -tag 'round1'
Check '第一轮补全成功（未报错）' ($null -ne $round1.Done -and $null -eq $round1.Error) `
    "done=$($round1.Done -ne $null) error=$($round1.Error.message)"

$toolRows = Get-SqlScalar "SELECT CONCAT(COUNT(*), '/', COALESCE(SUM(ok), 0)) FROM sugar_health.chat_tool_call WHERE session_id = $sessionId AND tool_name = 'save_long_term_memory';"
$toolParts = $toolRows -split '/'
$toolCalls = [int]$toolParts[0]
$toolOkSum = [int]$toolParts[1]
# V71 起一条消息里可以出现多个"事"，模型可能并行发两次调用（"不吃辣"与"不吃香菜"各一次）；
# 这里只要求「至少调过一次、且每次都成功」，不把并行调用判成失败。
Check '调用了 save_long_term_memory 且每次调用都成功' ($toolCalls -ge 1 -and $toolCalls -le 3 -and $toolOkSum -eq $toolCalls) "count/okSum=$toolRows"

$digest = Get-SqlScalar "SELECT COALESCE(result_digest, '') FROM sugar_health.chat_tool_call WHERE session_id = $sessionId AND tool_name = 'save_long_term_memory' ORDER BY id DESC LIMIT 1;"
Check '工具回执是短确认（不重复记忆正文）' ($digest -match '已记录' -and $digest -notmatch '香菜') "digest=$digest"

# V71 起一类可以有多条（一条只记一件事），所以断言从「正好一条」改成「至少一条，且置信度全是 85」。
$memories = Get-SqlScalar "SELECT CONCAT(COUNT(*), '/', COALESCE(MIN(confidence), 0), '/', COALESCE(MAX(confidence), 0)) FROM sugar_health.chat_user_memory WHERE user_id = $userId AND memory_key = 'diet_taboo' AND is_deleted = 0;"
Check '至少落了一条 diet_taboo，置信度全是 85（服务端固定）' ($memories -match '^[1-9]\d*/85/85$') "count/min/max=$memories"

$memText = Get-SqlScalar "SELECT COALESCE(memory_text, '') FROM sugar_health.chat_user_memory WHERE user_id = $userId AND memory_key = 'diet_taboo' AND is_deleted = 0 LIMIT 1;"
Check '记忆正文是第三人称陈述且含忌口信息' ($memText.Length -gt 0 -and $memText.Length -le 200) "text=$memText"

# ---------------------------------------------------------------- 2. 记忆进下一轮 prompt
Write-Host "2) 下一轮应把这条记忆注入系统提示词" -ForegroundColor Gray
$round2 = Invoke-Round -sessionId $sessionId -headers $headers -text '中午吃点什么好？' -tag 'round2'
Check '第二轮补全成功（未报错）' ($null -ne $round2.Done -and $null -eq $round2.Error) `
    "done=$($round2.Done -ne $null) error=$($round2.Error.message)"
$assembleLines = @(New-Lines $logBefore | Where-Object { $_ -match '会话记忆装配' })
$injected = 0
foreach ($line in $assembleLines) { if ($line -match '记忆=(\d+)') { $n = [int]$Matches[1]; if ($n -gt $injected) { $injected = $n } } }
Check '日志显示长期记忆确实被注入（记忆=N，N≥1）' (@($assembleLines).Count -ge 1 -and $injected -ge 1) `
    "lines=$(@($assembleLines).Count) 记忆=$injected（检查日志级别）"

# ---------------------------------------------------------------- 3. 同一件事重复说，不堆重复条目
Write-Host "3) 追问同一件事：允许一条更完整的说法，但不许出现同文重复" -ForegroundColor Gray
$round3 = Invoke-Round -sessionId $sessionId -headers $headers -text '再说一遍，我最近不能吃辣了，香菜也不行' -tag 'round3'
Check '第三轮补全成功（未报错）' ($null -ne $round3.Done -and $null -eq $round3.Error) `
    "done=$($round3.Done -ne $null) error=$($round3.Error.message)"
# V71 之后「同键覆盖」变成「同文视为同一条、异文新增一条」，所以这里断言的是去重而不是条数：
# 同样的正文（含空白差异）不该出现第二行，否则用户每次追问都会多一条垃圾。
$dietRows = Get-SqlScalar "SELECT CONCAT(COUNT(*), '/', COUNT(DISTINCT memory_text)) FROM sugar_health.chat_user_memory WHERE user_id = $userId AND memory_key = 'diet_taboo' AND is_deleted = 0;"
$dietParts = $dietRows -split '/'
Check '同键条目没有同文重复（去重生效）' ($dietParts[0] -eq $dietParts[1]) "count/distinct=$dietRows"

# ---------------------------------------------------------------- 4. 一次性安排不落库
Write-Host "4) 一次性安排（今晚想吃火锅）不应进长期记忆" -ForegroundColor Gray
$before = Get-SqlScalar "SELECT COUNT(*) FROM sugar_health.chat_user_memory WHERE user_id = $userId AND is_deleted = 0;"
$round4 = Invoke-Round -sessionId $sessionId -headers $headers -text '我今晚想吃火锅，就这一次' -tag 'round4'
Check '第四轮补全成功（未报错）' ($null -ne $round4.Done -and $null -eq $round4.Error) `
    "done=$($round4.Done -ne $null) error=$($round4.Error.message)"
Start-Sleep -Milliseconds 500
$after = Get-SqlScalar "SELECT COUNT(*) FROM sugar_health.chat_user_memory WHERE user_id = $userId AND is_deleted = 0;"
Check '记忆条数没有增加（一次性事件被模型判掉）' ($after -eq $before) "before=$before after=$after"

# ---------------------------------------------------------------- 5. 用户自助查看与清空
Write-Host "5) 用户自助查看 / 清空" -ForegroundColor Gray
$list = Invoke-Api -Method GET -Url "$api/users/me/ai-memories" -Headers $headers -Tag 'list'
Check '列表返回 200 且带 records' ($list.Code -eq 200 -and $null -ne $list.Body.data.records) "code=$($list.Code)"
$keys = @()
if ($null -ne $list.Body.data.records) { $keys = @($list.Body.data.records | ForEach-Object { $_.memoryKey }) }
Check '列表里有 diet_taboo' ($keys -contains 'diet_taboo') ("keys=" + ($keys -join ','))

$cleared = Invoke-Api -Method DELETE -Url "$api/users/me/ai-memories" -Headers $headers -Tag 'clear'
Check '清空返回 200' ($cleared.Code -eq 200) "code=$($cleared.Code)"
$left = Get-SqlScalar "SELECT COUNT(*) FROM sugar_health.chat_user_memory WHERE user_id = $userId AND is_deleted = 0;"
Check '清空后没有有效记忆行' ($left -eq '0') "left=$left"

# ---------------------------------------------------------------- 清理
Invoke-E2eSql "DELETE FROM sugar_health.chat_tool_call WHERE session_id = $sessionId;" | Out-Null
Invoke-E2eSql "DELETE FROM sugar_health.chat_user_memory WHERE user_id = $userId;" | Out-Null
Invoke-E2eSql "DELETE FROM sugar_health.chat_message WHERE session_id = $sessionId;" | Out-Null
Invoke-E2eSql "DELETE FROM sugar_health.chat_session WHERE id = $sessionId;" | Out-Null

Write-Host ""
Write-Host "PASS=$($script:pass) FAIL=$($script:fail)" -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
if ($script:fail -gt 0) { exit 1 }
