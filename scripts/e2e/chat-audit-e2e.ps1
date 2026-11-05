#requires -Version 5.1
<#
  后台会话审计的端到端验证：4.30 列表 / 4.31 消息预览 / 4.31 单条全文 / 审计留痕 / 越权与参数边界。

  刻意**不调用模型**：审计页读的是已有的 chat_session / chat_message，脚本自己往库里塞一个
  带前后两条消息的会话（标题带 【E2E审计】 前缀），验完逻辑删除掉，因此不需要 E2E_DEEPSEEK_KEY。

  重点验三件容易做错的事：
    1) 列表与预览都不返回正文（预览 ≤200 字、不含换行）；
    2) 只有「读全文」会写 ops_audit_log（module=ai, action=ai_chat_audit_view），且 detail_json 只有 sessionId；
    3) 用会话 A 的入口读会话 B 的消息必须 404，且**不能留下审计**。

  用法：
    $env:E2E_BASE_URL   = 'http://127.0.0.1:8091'
    $env:E2E_MYSQL_CNF  = '<repo>\target\e2e\my.cnf'
    $env:E2E_MYSQL_EXE  = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/e2e/chat-audit-e2e.ps1
#>
param([string]$Base)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\_common.ps1"
if (-not $PSBoundParameters.ContainsKey('Base')) { $Base = Get-E2eBaseUrl }

$admin = "$Base/admin/v1"
$outDir = Get-E2eOutDir
$marker = '【E2E审计】'

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

# 返回 @{ Code=http状态; Json=解析后的对象; Raw=原文 }
function Call([string]$Method, [string]$Url, [string[]]$Headers = @()) {
    $args = @('-s', '-X', $Method, $Url, '-w', "`n%{http_code}")
    foreach ($h in $Headers) { $args += @('-H', $h) }
    $raw = (& curl.exe @args) -join "`n"
    $split = $raw.LastIndexOf("`n")
    $status = 0
    $payload = $raw
    if ($split -gt 0) {
        [void][int]::TryParse($raw.Substring($split + 1).Trim(), [ref]$status)
        $payload = $raw.Substring(0, $split)
    }
    $json = $null
    try { $json = $payload | ConvertFrom-Json } catch { }
    return [pscustomobject]@{ Code = $status; Json = $json; Raw = $payload }
}

function Sql([string]$query) {
    $out = Invoke-E2eSql $query
    if ($null -eq $out) { return '' }
    return ($out -join "`n").Trim()
}

function AuditCount([long]$msgId) {
    $n = Sql "SELECT COUNT(*) FROM sugar_health.ops_audit_log WHERE module_code='ai' AND action_code='ai_chat_audit_view' AND target_id=$msgId;"
    if (-not $n) { return 0 }
    return [long]$n
}

Write-Host "== 后台会话审计 E2E（$Base）==" -ForegroundColor Cyan

# ---------- 0. 准备：登录 + 造数据 ----------
$loginFile = Save-Json 'audit-admin-login.json' @{ username = 'admin'; password = (Get-E2eAdminPassword) }
$lr = & curl.exe -s -X POST "$admin/auth/sessions" -H "Content-Type: application/json" --data-binary "@$loginFile"
$login = $lr | ConvertFrom-Json
if ($login.code -ne 0) { throw "后台登录失败：$lr" }
$ah = @("-H", "Authorization: Bearer $($login.data.accessToken)", "-H", "Content-Type: application/json")

$userId = Sql "SELECT id FROM sugar_health.user_account WHERE is_deleted=0 ORDER BY id LIMIT 1;"
if (-not $userId) { throw '库里没有可用用户，无法造会话' }

# 两个会话：A 用于正常读取，B 用于越权用例（用 A 的入口读 B 的消息）
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$titleA = "$marker$stamp-甲"
$titleB = "$marker$stamp-乙"
# 先把上一轮可能残留的 E2E 审计会话清掉（逻辑删除即可，幂等）
[void](Sql "UPDATE sugar_health.chat_session SET is_deleted=1, deleted_at=NOW(3), deleted_by=$userId WHERE title LIKE '$marker%' AND is_deleted=0;")

function New-Session([string]$title, [string]$prefix, [int]$status) {
    [void](Sql @"
INSERT INTO sugar_health.chat_session (user_id, title, session_status, session_type, model_code, system_prompt_ver, msg_count, last_msg_at, created_by, gmt_create, updated_by, gmt_modified, is_deleted)
VALUES ($userId, '$title', $status, 3, 'deepseek-v4-pro', 'chat@v2', 2, NOW(3), $userId, NOW(3), $userId, NOW(3), 0);
"@)
    $sid = Sql "SELECT id FROM sugar_health.chat_session WHERE title='$title' AND is_deleted=0 ORDER BY id DESC LIMIT 1;"
    if (-not $sid) { throw "造会话失败：$title" }
    # 用户消息：正文刻意超过 200 字并含换行，用来验「预览被截断且折叠空白」
    $longText = ("${prefix}这是一段用来验证预览截断的用户消息。" + ('水' * 260)) -replace '水', 'x'
    [void](Sql @"
INSERT INTO sugar_health.chat_message (session_id, user_id, seq_no, msg_role, msg_type, content_text, client_msg_id, created_by, gmt_create, is_deleted)
VALUES ($sid, $userId, 1, 1, 1, '$longText', 'e2e-audit-$stamp-$prefix-u', $userId, NOW(3), 0);
"@)
    [void](Sql @"
INSERT INTO sugar_health.chat_message (session_id, user_id, seq_no, msg_role, msg_type, content_text, model_code, prompt_tokens, completion_tokens, latency_ms, finish_reason, client_msg_id, created_by, gmt_create, is_deleted)
VALUES ($sid, $userId, 2, 2, 1, '${prefix}助手回复正文，用于验证全文接口能读到完整内容。', 'deepseek-v4-pro', 800, 120, 950, 1, 'e2e-audit-$stamp-$prefix-a', $userId, NOW(3), 0);
"@)
    return [long]$sid
}

$sessionA = New-Session $titleA 'A' 1
$sessionB = New-Session $titleB 'B' 2
$userMsgA = [long](Sql "SELECT id FROM sugar_health.chat_message WHERE session_id=$sessionA AND seq_no=1 AND is_deleted=0;")
$assistantMsgA = [long](Sql "SELECT id FROM sugar_health.chat_message WHERE session_id=$sessionA AND seq_no=2 AND is_deleted=0;")
$userMsgB = [long](Sql "SELECT id FROM sugar_health.chat_message WHERE session_id=$sessionB AND seq_no=1 AND is_deleted=0;")
Write-Host "  造数据：会话 A=$sessionA / B=$sessionB，消息 A.user=$userMsgA A.assistant=$assistantMsgA B.user=$userMsgB" -ForegroundColor DarkGray

try {
    # ---------- 1. 未授权 ----------
    $r = Call 'GET' "$admin/ai/chat-sessions"
    Check '1. 无 token 访问列表 → 401' ($r.Code -eq 401) "HTTP $($r.Code)"

    # ---------- 2. 列表 ----------
    $r = Call 'GET' "$admin/ai/chat-sessions?pageNum=1&pageSize=5" $ah
    Check '2. 列表 200' ($r.Code -eq 200) "HTTP $($r.Code) $($r.Raw)"
    Check '3. 列表返回 records/total' ($null -ne $r.Json.data.records -and $null -ne $r.Json.data.total) '缺少 data.records 或 data.total'
    $row = @($r.Json.data.records | Where-Object { $_.id -eq $sessionA })[0]
    Check '4. 列表能查到刚造的会话' ($null -ne $row) "未找到 sessionId=$sessionA"
    if ($row) {
        Check '5. 列表含昵称/消息数/轮次/摘要字段' (
            ($null -ne $row.PSObject.Properties['userNickname']) -and
            ($null -ne $row.PSObject.Properties['msgCount']) -and
            ($row.msgCount -eq 2) -and
            ($null -ne $row.PSObject.Properties['contextRounds']) -and
            ($null -ne $row.PSObject.Properties['hasSummary'])
        ) "msgCount=$($row.msgCount)"
        Check '6. 列表不含任何正文（无 contentText/preview）' (
            ($null -eq $row.PSObject.Properties['contentText']) -and ($null -eq $row.PSObject.Properties['preview'])
        ) '列表里出现了正文字段'
    }

    # ---------- 3. 关键词过滤 ----------
    $r = Call 'GET' "$admin/ai/chat-sessions?keyword=$stamp" $ah
    $hits = @($r.Json.data.records | Where-Object { $_.id -eq $sessionA -or $_.id -eq $sessionB })
    Check '7. 关键词能按标题命中 E2E 会话' ($r.Code -eq 200 -and $hits.Count -eq 2) "HTTP $($r.Code) 命中 $($hits.Count)"
    $long = 'x' * 51
    $r = Call 'GET' "$admin/ai/chat-sessions?keyword=$long" $ah
    Check '8. 关键词 >50 字 → 400/40000' ($r.Code -eq 400 -and $r.Json.code -eq 40000) "HTTP $($r.Code) code=$($r.Json.code)"

    # ---------- 4. 消息预览 ----------
    $r = Call 'GET' "$admin/ai/chat-sessions/$sessionA/messages?pageNum=1&pageSize=20" $ah
    Check '9. 消息列表 200' ($r.Code -eq 200) "HTTP $($r.Code) $($r.Raw)"
    $msgs = @($r.Json.data.records)
    Check '10. 消息列表含 2 条' ($msgs.Count -eq 2) "实际 $($msgs.Count)"
    $previews = @($msgs | ForEach-Object { $_.preview })
    $maxLen = ($previews | ForEach-Object { $_.Length } | Measure-Object -Maximum).Maximum
    # 截断时末尾额外补一个省略号，所以字符串长度最多 201（正文 200 字 + '…'）
    Check '11. 预览 ≤200 字（+省略号）' ($maxLen -le 201) "最长 $maxLen"
    Check '12. 预览已折叠换行' (@($previews | Where-Object { $_ -match "`n" -or $_ -match "`r" }).Count -eq 0) '预览里还有换行'
    Check '13. 预览不含正文字段' ($null -eq $msgs[0].PSObject.Properties['contentText']) '预览里出现了 contentText'
    Check '14. 预览含 role/type/finishReason 元数据' (
        ($null -ne $msgs[0].PSObject.Properties['msgRole']) -and
        ($null -ne $msgs[0].PSObject.Properties['msgType']) -and
        ($null -ne $msgs[0].PSObject.Properties['finishReason'])
    ) '元数据字段缺失'

    # 往前翻：beforeSeq=2 只应返回 seqNo<2 的那条
    $r2 = Call 'GET' "$admin/ai/chat-sessions/$sessionA/messages?beforeSeq=2" $ah
    $older = @($r2.Json.data.records)
    Check '15. beforeSeq 往前翻只返回更早的消息' ($r2.Code -eq 200 -and $older.Count -eq 1 -and $older[0].seqNo -eq 1) "HTTP $($r2.Code) 条数 $($older.Count)"

    # ---------- 5. 全文与审计留痕 ----------
    $before = AuditCount $assistantMsgA
    $r = Call 'GET' "$admin/ai/chat-sessions/$sessionA/messages/$assistantMsgA" $ah
    Check '16. 全文 200' ($r.Code -eq 200) "HTTP $($r.Code) $($r.Raw)"
    Check '17. 全文返回完整正文' ($r.Json.data.contentText -like '*助手回复正文*') "contentText=$($r.Json.data.contentText)"
    $after = AuditCount $assistantMsgA
    Check '18. 读全文写入 1 条审计' (($after - $before) -eq 1) "新增 $($after - $before) 条"
    $detail = Sql "SELECT detail_json FROM sugar_health.ops_audit_log WHERE module_code='ai' AND action_code='ai_chat_audit_view' AND target_id=$assistantMsgA ORDER BY id DESC LIMIT 1;"
    Check '19. 审计 detail 只有 sessionId、不含正文' ($detail -eq "{`"sessionId`": $sessionA}" -or $detail -eq "{`"sessionId`":$sessionA}") "detail=$detail"
    $tgt = Sql "SELECT CONCAT(target_type,'/',target_id) FROM sugar_health.ops_audit_log WHERE module_code='ai' AND action_code='ai_chat_audit_view' AND target_id=$assistantMsgA ORDER BY id DESC LIMIT 1;"
    Check '20. 审计目标指向 chat_message' ($tgt -eq "chat_message/$assistantMsgA") "target=$tgt"

    # 再读一次：每次读取都应独立留痕（这是审计的目的，不是 bug）
    $r = Call 'GET' "$admin/ai/chat-sessions/$sessionA/messages/$assistantMsgA" $ah
    $again = AuditCount $assistantMsgA
    Check '21. 重复读全文再写 1 条审计' (($again - $after) -eq 1 -and $r.Code -eq 200) "新增 $($again - $after) 条"

    # ---------- 6. 越权与不存在 ----------
    $beforeB = AuditCount $userMsgB
    $r = Call 'GET' "$admin/ai/chat-sessions/$sessionA/messages/$userMsgB" $ah
    $afterB = AuditCount $userMsgB
    Check '22. 用会话 A 的入口读 B 的消息 → 404' ($r.Code -eq 404 -and $r.Json.code -eq 40400) "HTTP $($r.Code) code=$($r.Json.code)"
    Check '23. 越权失败不写审计' (($afterB - $beforeB) -eq 0) "新增 $($afterB - $beforeB) 条"

    $r = Call 'GET' "$admin/ai/chat-sessions/99999999/messages" $ah
    Check '24. 会话不存在 → 404' ($r.Code -eq 404 -and $r.Json.code -eq 40400) "HTTP $($r.Code) code=$($r.Json.code)"

    # ---------- 7. 权限点回归（V53） ----------
    $r = Call 'GET' "$admin/ai/usage" $ah
    Check '25. ai:usage:view 拆分后超管仍可读用量' ($r.Code -eq 200) "HTTP $($r.Code)"
    $r = Call 'GET' "$admin/ai/chat-sessions/$sessionA/context" $ah
    Check '26. 分片审计接口改用 ai:chat:audit 后仍可读' ($r.Code -eq 200) "HTTP $($r.Code)"
}
finally {
    [void](Sql "UPDATE sugar_health.chat_session SET is_deleted=1, deleted_at=NOW(3), deleted_by=$userId WHERE title LIKE '$marker%' AND is_deleted=0;")
    [void](Sql "UPDATE sugar_health.chat_message m JOIN sugar_health.chat_session s ON s.id=m.session_id SET m.is_deleted=1, m.deleted_at=NOW(3), m.deleted_by=$userId WHERE s.title LIKE '$marker%';")
}

Write-Host ""
Write-Host "== 结果：PASS=$script:pass FAIL=$script:fail ==" -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
if ($script:fail -gt 0) { exit 1 }
