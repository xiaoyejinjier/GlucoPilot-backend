#requires -Version 5.1
<#
  会话生命周期闭环的端到端验证：5.3 列表 / 5.4 删除 / 2.23 新建（forceNew）/ 历史分页 / 置顶 /
  状态对写入的影响 / B3 工具元数据表可用性。

  刻意**不调用模型**（不需要 E2E_DEEPSEEK_KEY）：本轮验证的是会话与消息的"账"，
  而 /messages（2.24）本身不触发模型调用、/completions（2.25）才触发——因此整条链路都能在
  不花钱、不依赖外网的前提下验完。assistant 消息由脚本直接落库（模拟"模型答完了"），
  这样历史分页与列表预览才有真实数据可验。

  用法：
    $env:E2E_BASE_URL  = 'http://127.0.0.1:8091'
    $env:E2E_MYSQL_CNF = '<repo>\target\e2e\my.cnf'
    pwsh -File scripts/e2e/session-e2e.ps1
#>
param([string]$Base)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\_common.ps1"
if (-not $PSBoundParameters.ContainsKey('Base')) { $Base = Get-E2eBaseUrl }

$api = "$Base/api/v1"
$outDir = Get-E2eOutDir

# E2E 专用标题前缀：便于识别与清理（脚本会把自己造的会话逻辑删掉）
$marker = '【E2E会话】'

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

Write-Host "== 会话生命周期 E2E（$Base）==" -ForegroundColor Cyan

# ---------------------------------------------------------------- 0. 登录
$login = Invoke-Api -Method POST -Url "$api/auth/wechat/sessions" `
    -Body @{ code = (Get-E2eUserCode) } -Tag 'login'
if ($login.Code -ne 200) { throw "登录失败：HTTP $($login.Code) $($login.Raw)" }
$appToken = $login.Body.data.accessToken
$headers = @("Authorization: Bearer $appToken")
$userId = Get-SqlScalar "SELECT id FROM sugar_health.user_account WHERE openid = 'wx_local_dev_user' AND is_deleted = 0 LIMIT 1;"
if (-not $userId) { throw '未能在库里定位 mock 用户（openid = wx_local_dev_user）' }
Write-Host "0) 登录 OK，用户 id=$userId" -ForegroundColor Gray

# 清理上一次运行可能留下的 E2E 会话（逻辑删除即可，消息一并删）
Invoke-E2eSql "UPDATE sugar_health.chat_message m JOIN sugar_health.chat_session s ON m.session_id = s.id SET m.is_deleted = 1, m.deleted_at = NOW(3) WHERE s.user_id = $userId AND s.title LIKE '$marker%' AND m.is_deleted = 0;" | Out-Null
Invoke-E2eSql "UPDATE sugar_health.chat_session SET is_deleted = 1, deleted_at = NOW(3), session_status = 2 WHERE user_id = $userId AND title LIKE '$marker%' AND is_deleted = 0;" | Out-Null

# ---------------------------------------------------------------- 1. 新建会话（forceNew）
Write-Host "1) 新建会话：旧会话必须被结束" -ForegroundColor Gray

# 先确保有一条进行中会话（不带 forceNew，取或建）
$current = Invoke-Api -Method POST -Url "$api/chat-sessions/current" -Headers $headers -Body @{ sessionType = 3 } -Tag 'current-1'
Check '2.23 取或建当前会话成功' ($current.Code -eq 200 -and $current.Body.data.id) "HTTP $($current.Code) $($current.Body.message)"
$oldSessionId = $current.Body.data.id

$forced = Invoke-Api -Method POST -Url "$api/chat-sessions/current" -Headers $headers `
    -Body @{ sessionType = 3; forceNew = $true } -Tag 'current-new'
Check '2.23 forceNew=true 新建会话成功' ($forced.Code -eq 200 -and $forced.Body.data.id) "HTTP $($forced.Code) $($forced.Body.message)"
$newSessionId = $forced.Body.data.id
Check '新建返回的是另一条会话' ("$newSessionId" -ne "$oldSessionId") "old=$oldSessionId new=$newSessionId"
Check '新会话状态是进行中(1)' ($forced.Body.data.sessionStatus -eq 1) "status=$($forced.Body.data.sessionStatus)"

$oldStatus = Get-SqlScalar "SELECT session_status FROM sugar_health.chat_session WHERE id = $oldSessionId;"
Check '旧会话被置为已结束(2)' ($oldStatus -eq '2') "old session_status=$oldStatus"
$activeCount = Get-SqlScalar "SELECT COUNT(*) FROM sugar_health.chat_session WHERE user_id = $userId AND session_status = 1 AND is_deleted = 0;"
Check '该用户仍然只有一条进行中会话（uk_user_active）' ($activeCount -eq '1') "active=$activeCount"

# 给新会话起个可识别的标题（走 2.24 的首条消息会自动改名，这里直接改库更稳）
Invoke-E2eSql "UPDATE sugar_health.chat_session SET title = '$marker 新建的会话' WHERE id = $newSessionId;" | Out-Null
Invoke-E2eSql "UPDATE sugar_health.chat_session SET title = '$marker 旧的已结束会话' WHERE id = $oldSessionId;" | Out-Null

# ---------------------------------------------------------------- 2. 历史读取与翻页（2.24 不调模型）
Write-Host "2) 历史消息：写入 → 读取 → 翻页" -ForegroundColor Gray
$emptyHistory = Invoke-Api -Method GET -Url "$api/chat-sessions/$newSessionId/messages?pageSize=20" -Headers $headers -Tag 'history-empty'
Check '新会话历史为空且 hasMore=false' ($emptyHistory.Code -eq 200 -and @($emptyHistory.Body.data.records).Count -eq 0 -and $emptyHistory.Body.data.hasMore -eq $false) `
    "HTTP $($emptyHistory.Code) count=$(@($emptyHistory.Body.data.records).Count) hasMore=$($emptyHistory.Body.data.hasMore)"

for ($i = 1; $i -le 3; $i++) {
    $sent = Invoke-Api -Method POST -Url "$api/chat-sessions/$newSessionId/messages" -Headers $headers `
        -Body @{ clientMsgId = "e2e-sess-$i-$([guid]::NewGuid().ToString('N').Substring(0,8))"; msgType = 1; contentText = "$marker 第 $i 条用户消息" } -Tag "msg-$i"
    if ($sent.Code -ne 200) { throw "第 $i 条消息写入失败：HTTP $($sent.Code) $($sent.Body.message)" }
    # 模拟"模型答完了"：直接落一条助手消息（不调模型），让列表预览与历史有真实内容
    Invoke-E2eSql "INSERT INTO sugar_health.chat_message (session_id, user_id, seq_no, msg_role, msg_type, content_text, reply_to_msg_id, model_code, finish_reason, created_by, updated_by) SELECT $newSessionId, $userId, COALESCE(MAX(seq_no),0)+1, 2, 1, '$marker 第 $i 条助手回复', $($sent.Body.data.id), 'e2e-model', 1, $userId, $userId FROM sugar_health.chat_message WHERE session_id = $newSessionId;" | Out-Null
    Invoke-E2eSql "UPDATE sugar_health.chat_session SET msg_count = (SELECT COUNT(*) FROM sugar_health.chat_message WHERE session_id = $newSessionId AND is_deleted = 0), last_msg_at = NOW(3) WHERE id = $newSessionId;" | Out-Null
}

$history = Invoke-Api -Method GET -Url "$api/chat-sessions/$newSessionId/messages?pageSize=4" -Headers $headers -Tag 'history-1'
$records = @($history.Body.data.records)
Check '历史返回条数受 pageSize 限制' ($history.Code -eq 200 -and $records.Count -eq 4) "count=$($records.Count)"
Check '历史按时间正序返回（第一条 seqNo 最小）' (@($records)[0].seqNo -lt @($records)[-1].seqNo) `
    "first=$(@($records)[0].seqNo) last=$(@($records)[-1].seqNo)"
Check '历史带 hasMore 与 nextBeforeSeq' ($history.Body.data.hasMore -eq $true -and $null -ne $history.Body.data.nextBeforeSeq) `
    "hasMore=$($history.Body.data.hasMore) nextBeforeSeq=$($history.Body.data.nextBeforeSeq)"
Check '用户消息与助手消息都返回且能区分角色' (@($records | Where-Object { $_.msgRole -eq 1 }).Count -ge 1 -and @($records | Where-Object { $_.msgRole -eq 2 }).Count -ge 1) `
    '缺少用户或助手消息'
Check '助手回复带回 modelCode' (@($records | Where-Object { $_.msgRole -eq 2 })[0].modelCode -eq 'e2e-model') `
    "modelCode=$(@($records | Where-Object { $_.msgRole -eq 2 })[0].modelCode)"

$beforeSeq = $history.Body.data.nextBeforeSeq
$older = Invoke-Api -Method GET -Url "$api/chat-sessions/$newSessionId/messages?pageSize=4&beforeSeq=$beforeSeq" -Headers $headers -Tag 'history-2'
$olderRecords = @($older.Body.data.records)
Check '按 beforeSeq 能翻到更早的消息' ($older.Code -eq 200 -and $olderRecords.Count -eq 2 -and $older.Body.data.hasMore -eq $false) `
    "count=$($olderRecords.Count) hasMore=$($older.Body.data.hasMore)"
Check '翻页结果不与第一页重叠' (@($olderRecords | Where-Object { $_.seqNo -ge $beforeSeq }).Count -eq 0) `
    "beforeSeq=$beforeSeq 但翻页里出现了 seqNo >= beforeSeq 的记录"

# ---------------------------------------------------------------- 3. 5.3 列表
Write-Host "3) 5.3 会话列表" -ForegroundColor Gray
$list = Invoke-Api -Method GET -Url "$api/chat-sessions?pageSize=50" -Headers $headers -Tag 'session-list'
$rows = @($list.Body.data.records)
Check '列表返回本人会话与总数' ($list.Code -eq 200 -and $rows.Count -ge 2 -and [long]$list.Body.data.total -ge 2) `
    "HTTP $($list.Code) records=$($rows.Count) total=$($list.Body.data.total)"
Check '列表带出归档阈值（前端据此解释归档）' ($list.Body.data.archiveIdleDays -eq 30) "archiveIdleDays=$($list.Body.data.archiveIdleDays)"
$newRow = $rows | Where-Object { "$($_.id)" -eq "$newSessionId" } | Select-Object -First 1
Check '新会话出现在列表里且状态为进行中' ($null -ne $newRow -and $newRow.sessionStatus -eq 1) `
    "sessionStatus=$($newRow.sessionStatus)"
Check '列表带回最后一条消息预览（助手回复）' ($newRow.lastMessagePreview -like '*助手回复*') `
    "preview=$($newRow.lastMessagePreview)"
Check '列表带回消息条数' ([int]$newRow.msgCount -eq 6) "msgCount=$($newRow.msgCount)"

$ended = Invoke-Api -Method GET -Url "$api/chat-sessions?sessionStatus=2&pageSize=50" -Headers $headers -Tag 'session-list-ended'
$endedRows = @($ended.Body.data.records)
Check '按 sessionStatus=2 过滤只返回已结束会话' ($ended.Code -eq 200 -and @($endedRows | Where-Object { $_.sessionStatus -ne 2 }).Count -eq 0 -and $endedRows.Count -ge 1) `
    "count=$($endedRows.Count)"

# ---------------------------------------------------------------- 4. 已结束会话不能继续写
Write-Host "4) 状态约束：已结束会话不能再写" -ForegroundColor Gray
$rejectMsg = Invoke-Api -Method POST -Url "$api/chat-sessions/$oldSessionId/messages" -Headers $headers `
    -Body @{ clientMsgId = "e2e-ended-$([guid]::NewGuid().ToString('N').Substring(0,8))"; msgType = 1; contentText = '这条不该写进去' } -Tag 'msg-ended'
Check '2.24 对已结束会话返回业务错误' ($rejectMsg.Code -eq 400 -and $rejectMsg.Body.code -eq 40001) `
    "HTTP $($rejectMsg.Code) code=$($rejectMsg.Body.code) msg=$($rejectMsg.Body.message)"
Check '错误文案引导用户新建对话' ($rejectMsg.Body.message -like '*新建对话*') "msg=$($rejectMsg.Body.message)"
$leaked = Get-SqlScalar "SELECT COUNT(*) FROM sugar_health.chat_message WHERE session_id = $oldSessionId AND content_text = '这条不该写进去';"
Check '被拒的消息没有落库' ($leaked -eq '0') "rows=$leaked"

$rejectCompletion = Invoke-Api -Method POST -Url "$api/chat-sessions/$oldSessionId/completions" -Headers $headers `
    -Body @{ clientMsgId = [guid]::NewGuid().ToString(); replyToMsgId = $null; stream = $true } -Tag 'completion-ended'
Check '2.25 对已结束会话同样拒绝（且在流开始前返回标准 JSON 错误）' ($rejectCompletion.Code -eq 400) `
    "HTTP $($rejectCompletion.Code)"

# ---------------------------------------------------------------- 5. 置顶
Write-Host "5) 置顶 / 取消置顶" -ForegroundColor Gray
$pin = Invoke-Api -Method PATCH -Url "$api/chat-sessions/$oldSessionId/pinned" -Headers $headers -Body @{ isPinned = $true } -Tag 'pin'
Check '置顶接口成功' ($pin.Code -eq 200) "HTTP $($pin.Code) $($pin.Body.message)"
$pinnedFlag = Get-SqlScalar "SELECT is_pinned FROM sugar_health.chat_session WHERE id = $oldSessionId;"
Check '库里 is_pinned=1' ($pinnedFlag -eq '1') "is_pinned=$pinnedFlag"
$listPinned = Invoke-Api -Method GET -Url "$api/chat-sessions?pageSize=50" -Headers $headers -Tag 'session-list-pinned'
$firstRow = @($listPinned.Body.data.records)[0]
Check '置顶会话排在列表第一位' ("$($firstRow.id)" -eq "$oldSessionId") "first=$($firstRow.id) expect=$oldSessionId"
$unpin = Invoke-Api -Method PATCH -Url "$api/chat-sessions/$oldSessionId/pinned" -Headers $headers -Body @{ isPinned = $false } -Tag 'unpin'
Check '取消置顶成功' ($unpin.Code -eq 200 -and (Get-SqlScalar "SELECT is_pinned FROM sugar_health.chat_session WHERE id = $oldSessionId;") -eq '0') `
    "HTTP $($unpin.Code)"

# ---------------------------------------------------------------- 6. 5.4 删除
Write-Host "6) 5.4 删除会话（会话与消息一并逻辑删除，重复删除幂等）" -ForegroundColor Gray
$deleted = Invoke-Api -Method DELETE -Url "$api/chat-sessions/$oldSessionId" -Headers $headers -Tag 'delete-session'
Check '删除会话成功' ($deleted.Code -eq 200 -and $null -eq $deleted.Body.data) "HTTP $($deleted.Code) $($deleted.Body.message)"
$deletedFlag = Get-SqlScalar "SELECT CONCAT(is_deleted, '/', IF(deleted_at IS NULL,'null','set')) FROM sugar_health.chat_session WHERE id = $oldSessionId;"
Check '会话是逻辑删除且写了 deleted_at' ($deletedFlag -eq '1/set') "is_deleted/deleted_at=$deletedFlag"
$deletedMsgs = Get-SqlScalar "SELECT COUNT(*) FROM sugar_health.chat_message WHERE session_id = $oldSessionId AND is_deleted = 0;"
Check '该会话的消息也一并逻辑删除' ($deletedMsgs -eq '0') "remaining=$deletedMsgs"

$again = Invoke-Api -Method DELETE -Url "$api/chat-sessions/$oldSessionId" -Headers $headers -Tag 'delete-again'
Check '重复删除返回成功（幂等）' ($again.Code -eq 200) "HTTP $($again.Code) msg=$($again.Body.message)"

$listAfterDelete = Invoke-Api -Method GET -Url "$api/chat-sessions?pageSize=50" -Headers $headers -Tag 'session-list-deleted'
Check '已删除会话不再出现在列表里' (@(@($listAfterDelete.Body.data.records) | Where-Object { "$($_.id)" -eq "$oldSessionId" }).Count -eq 0) `
    '删除后仍出现在列表里'
$historyDeleted = Invoke-Api -Method GET -Url "$api/chat-sessions/$oldSessionId/messages" -Headers $headers -Tag 'history-deleted'
Check '已删除会话读历史返回未找到' ($historyDeleted.Code -eq 404) "HTTP $($historyDeleted.Code)"

# 别人的会话：拿一个不存在的 id 与一个别人的 id 都应该 404（不泄露存在性）
$foreign = Get-SqlScalar "SELECT id FROM sugar_health.chat_session WHERE user_id <> $userId AND is_deleted = 0 LIMIT 1;"
if ($foreign) {
    $deleteForeign = Invoke-Api -Method DELETE -Url "$api/chat-sessions/$foreign" -Headers $headers -Tag 'delete-foreign'
    Check '删除别人的会话返回 404 且未删除' ($deleteForeign.Code -eq 404 -and (Get-SqlScalar "SELECT is_deleted FROM sugar_health.chat_session WHERE id = $foreign;") -eq '0') `
        "HTTP $($deleteForeign.Code)"
}

# ---------------------------------------------------------------- 7. 清理
Write-Host "7) 清理 E2E 会话" -ForegroundColor Gray
Invoke-E2eSql "UPDATE sugar_health.chat_message m JOIN sugar_health.chat_session s ON m.session_id = s.id SET m.is_deleted = 1, m.deleted_at = NOW(3) WHERE s.user_id = $userId AND s.title LIKE '$marker%' AND m.is_deleted = 0;" | Out-Null
Invoke-E2eSql "UPDATE sugar_health.chat_session SET is_deleted = 1, deleted_at = NOW(3), session_status = 2 WHERE user_id = $userId AND title LIKE '$marker%' AND is_deleted = 0;" | Out-Null
$left = Get-SqlScalar "SELECT COUNT(*) FROM sugar_health.chat_session WHERE user_id = $userId AND title LIKE '$marker%' AND is_deleted = 0;"
Check 'E2E 会话已清理' ($left -eq '0') "left=$left"

Write-Host ""
Write-Host "== 结果：PASS=$script:pass FAIL=$script:fail ==" -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
exit $script:fail
