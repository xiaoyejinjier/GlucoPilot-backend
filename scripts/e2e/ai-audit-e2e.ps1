<#
  后台「AI 审计」六个新页签的端到端验证：
    4.32 工具调用 / 4.33 长期记忆 / 4.34 滚动摘要 / 4.35 多模态任务 /
    4.36 内容审核命中 / 4.37 翻译缓存。

  刻意**不调用模型**：审计页读的都是既有流水，脚本自己往六张表里塞带标记的夹具，
  验完物理删掉，因此不需要 E2E_DEEPSEEK_KEY。

  重点验四件容易做错的事：
    1) 关键词超长一律 400（不静默截断）；
    2) 记忆默认不含已删除行，includeDeleted=true 才带上；
    3) 审核命中只给位置与片段哈希，**不返回原文**；翻译只给 200 字预览；
    4) 这六个接口全部**只读**，不写 ops_audit_log（与「读会话正文必留痕」区分开）。

  用法：
    $env:E2E_BASE_URL   = 'http://127.0.0.1:8091'
    $env:E2E_MYSQL_CNF  = '<repo>\target\e2e\my.cnf'
    $env:E2E_MYSQL_EXE  = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/e2e/ai-audit-e2e.ps1
#>
param([string]$Base)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\_common.ps1"
if (-not $PSBoundParameters.ContainsKey('Base')) { $Base = Get-E2eBaseUrl }

$admin = "$Base/admin/v1"
$outDir = Get-E2eOutDir
$marker = '【E2E审计】'
$toolName = 'e2e_audit_tool'
$localizationId = 'e2e-audit'

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

function AuditTotal() {
    $n = Sql "SELECT COUNT(*) FROM sugar_health.ops_audit_log WHERE module_code='ai';"
    if (-not $n) { return 0 }
    return [long]$n
}

Write-Host "== 后台 AI 审计（六页签）E2E（$Base）==" -ForegroundColor Cyan

# ---------- 0. 登录 ----------
$loginFile = Save-Json 'ai-audit-admin-login.json' @{ username = 'admin'; password = (Get-E2eAdminPassword) }
$lr = & curl.exe -s -X POST "$admin/auth/sessions" -H "Content-Type: application/json" --data-binary "@$loginFile"
$login = $lr | ConvertFrom-Json
if ($login.code -ne 0) { throw "后台登录失败：$lr" }
$ah = @("-H", "Authorization: Bearer $($login.data.accessToken)", "-H", "Content-Type: application/json")

$userId = Sql "SELECT id FROM sugar_health.user_account WHERE is_deleted=0 ORDER BY id LIMIT 1;"
if (-not $userId) { throw '库里没有可用用户，无法造夹具' }
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$title = "$marker$stamp"

try {
    # ---------- 1. 造夹具 ----------
    # 会话（承载工具调用 + 滚动摘要）
    [void](Sql @"
INSERT INTO sugar_health.chat_session (user_id, title, session_status, session_type, msg_count, last_msg_at,
       context_summary, summary_upto_msg_id, created_by, gmt_create, updated_by, gmt_modified, is_deleted)
VALUES ($userId, '$title', 2, 1, 12, NOW(3),
       '${marker}这是用于审计验证的滚动摘要：用户三个月内空腹血糖从 8.1 降到 6.4，晨起偏高，晚餐后波动大。', 10,
       $userId, NOW(3), $userId, NOW(3), 0);
"@)
    $sessionId = [long](Sql "SELECT id FROM sugar_health.chat_session WHERE title='$title' AND is_deleted=0 ORDER BY id DESC LIMIT 1;")
    if (-not $sessionId) { throw "造会话失败：$title" }

    # 工具调用：一条成功、一条失败（失败带 errorType）
    [void](Sql @"
INSERT INTO sugar_health.chat_tool_call (session_id, user_id, tool_name, call_index, ok, duration_ms, error_type, result_digest, gmt_create)
VALUES
  ($sessionId, $userId, '$toolName', 1, 1, 37, NULL, '空腹均值 7.2，最高 9.8（3 条）', NOW(3)),
  ($sessionId, $userId, '$toolName', 2, 0, 812, 'java.util.concurrent.TimeoutException', NULL, NOW(3));
"@)

    # 长期记忆：一条生效、一条已逻辑删除（删除时 deleted_at/deleted_by 必须同时非空）
    [void](Sql @"
INSERT INTO sugar_health.chat_user_memory (user_id, memory_key, memory_text, source_session_id, confidence, created_by, gmt_create, updated_by, gmt_modified, is_deleted)
VALUES ($userId, 'e2e_audit_taboo', '${marker}不能吃芒果，吃完血糖飙到 11', $sessionId, 92, $userId, NOW(3), $userId, NOW(3), 0);
"@)
    [void](Sql @"
INSERT INTO sugar_health.chat_user_memory (user_id, memory_key, memory_text, source_session_id, confidence, created_by, gmt_create, updated_by, gmt_modified, deleted_by, deleted_at, is_deleted)
VALUES ($userId, 'e2e_audit_habit', '${marker}每天晚饭后散步 30 分钟', $sessionId, 70, $userId, NOW(3), $userId, NOW(3), $userId, NOW(3), 1);
"@)

    # 多模态：一条成功（结果 JSON 很长，验证只给 200 字预览）、一条失败
    $longJson = '{"items":[{"name":"米饭","carb":' + ('1' * 300) + '}]}'
    [void](Sql @"
INSERT INTO sugar_health.ocr_task (user_id, media_id, biz_type, task_status, ocr_result_json, finished_at, created_by, gmt_create, is_deleted)
VALUES ($userId, 999001, 2, 2, '$longJson', NOW(3), $userId, TIMESTAMPADD(MICROSECOND, -1500000, NOW(3)), 0);
"@)
    [void](Sql @"
INSERT INTO sugar_health.ocr_task (user_id, media_id, biz_type, task_status, error_msg, finished_at, created_by, gmt_create, is_deleted)
VALUES ($userId, 999002, 1, 3, '${marker}图片模糊，无法识别读数', NOW(3), $userId, TIMESTAMPADD(MICROSECOND, -900000, NOW(3)), 0);
"@)

    # 内容审核命中：文章 severity=3 / 评论 severity=1
    $snippetHash = 'a' * 64
    [void](Sql @"
INSERT INTO sugar_health.community_moderation_hit (target_type, target_id, revision, hit_source, category_code, severity,
       confidence, start_offset, end_offset, snippet_hash, snippet_len, reason, model_code, prompt_ver, created_at)
VALUES
  ('ARTICLE', 999001, 2, 'AI', 'medical_advice', 3, 88, 120, 148, '$snippetHash', 28,
   '${marker}建议停用二甲双胍，改喝苦瓜水即可降糖', 'deepseek-v4-pro', 'moderation@v1', NOW(3)),
  ('COMMENT', 999002, NULL, 'AI', 'off_topic', 1, 61, NULL, NULL, '$snippetHash', 0,
   '${marker}与控糖无关的广告', 'deepseek-v4-pro', 'moderation@v1', NOW(3));
"@)

    # 翻译缓存：一条 READY（长译文）、一条 FAILED。
    # 译文刻意用 REPEAT 造、且**不含双引号**：Invoke-E2eSql 走 `mysql -e <整条 SQL>`，
    # 带 `"` 的 JSON 会被 PowerShell 转义成 \" 从而在空格处把参数切断（踩过）。
    [void](Sql @"
INSERT INTO sugar_health.content_localization (kind, resource_id, source_hash, locale, status, translated_json, prompt_ver, updated_at)
VALUES
  ('ARTICLE_BODY', '$localizationId', '$snippetHash', 'en', 'READY', REPEAT('E2E-translated-body-', 30), 'translation@v1', NOW(3)),
  ('PROFILE_NAME', '$localizationId-b', '$snippetHash', 'en', 'FAILED', NULL, 'translation@v1', NOW(3));
"@)

    Write-Host "  造夹具：会话 $sessionId / 用户 $userId" -ForegroundColor DarkGray

    # ---------- 2. 鉴权 ----------
    $r = Call 'GET' "$admin/ai/audit/tool-calls"
    Check '1. 未带 token 访问审计接口被拒' ($r.Code -eq 401 -or $r.Code -eq 403) "HTTP $($r.Code)"

    # ---------- 3. 工具调用（4.32） ----------
    $r = Call 'GET' "$admin/ai/audit/tool-calls?sessionId=$sessionId" $ah
    $rows = @($r.Json.data.records)
    Check '2. 工具调用列表返回 2 条且按 id 倒序' (
        $r.Code -eq 200 -and $rows.Count -eq 2 -and $rows[0].id -gt $rows[1].id
    ) "HTTP $($r.Code) 条数 $($rows.Count)"
    Check '3. 成功行的摘要与耗时都在、失败行没有摘要且有 errorType' (
        ($rows | Where-Object { $_.ok }).resultDigest -like '空腹均值*' -and
        ($rows | Where-Object { -not $_.ok }).errorType -like '*TimeoutException'
    ) '字段与预期不符'

    $r = Call 'GET' "$admin/ai/audit/tool-calls?sessionId=$sessionId&ok=true" $ah
    Check '4. ok=true 只剩成功行' ($r.Code -eq 200 -and @($r.Json.data.records).Count -eq 1) "条数 $(@($r.Json.data.records).Count)"
    $r = Call 'GET' "$admin/ai/audit/tool-calls?sessionId=$sessionId&toolName=nope" $ah
    Check '5. 工具名不匹配返回空列表' ($r.Code -eq 200 -and @($r.Json.data.records).Count -eq 0) "条数 $(@($r.Json.data.records).Count)"

    # ---------- 4. 长期记忆（4.33） ----------
    $r = Call 'GET' "$admin/ai/audit/memories?userId=$userId&memoryKey=e2e_audit_taboo" $ah
    $rows = @($r.Json.data.records)
    Check '6. 记忆默认只返回生效行' ($r.Code -eq 200 -and $rows.Count -eq 1 -and -not $rows[0].deleted) "条数 $($rows.Count)"
    Check '7. 记忆内容给全文（不截断）' ($rows.Count -eq 1 -and $rows[0].memoryText -like '*芒果*') $rows[0].memoryText

    $r = Call 'GET' "$admin/ai/audit/memories?userId=$userId&memoryKey=e2e_audit_habit&includeDeleted=true" $ah
    $rows = @($r.Json.data.records)
    Check '8. includeDeleted=true 才带上已删除行' ($r.Code -eq 200 -and $rows.Count -eq 1 -and $rows[0].deleted) "条数 $($rows.Count)"

    $r = Call 'GET' "$admin/ai/audit/memories?userId=$userId&keyword=%E8%8A%92%E6%9E%9C" $ah
    Check '9. 记忆内容关键词能命中' ($r.Code -eq 200 -and @($r.Json.data.records).Count -ge 1) "条数 $(@($r.Json.data.records).Count)"
    $tooLong = '水' * 51
    $r = Call 'GET' "$admin/ai/audit/memories?keyword=$([uri]::EscapeDataString($tooLong))" $ah
    Check '10. 关键词超过 50 字返回 400（不静默截断）' ($r.Code -eq 400) "HTTP $($r.Code) $($r.Raw)"

    # ---------- 5. 滚动摘要（4.34） ----------
    $r = Call 'GET' "$admin/ai/audit/summaries?userId=$userId&keyword=$([uri]::EscapeDataString($marker))" $ah
    $rows = @($r.Json.data.records)
    Check '11. 摘要列表能查到夹具会话' ($r.Code -eq 200 -and $rows.Count -eq 1 -and $rows[0].sessionId -eq $sessionId) "条数 $($rows.Count)"
    Check '12. 摘要给全文且字数对得上' (
        $rows.Count -eq 1 -and $rows[0].summary -like '*空腹血糖从 8.1*' -and $rows[0].summaryChars -eq $rows[0].summary.Length
    ) "chars=$($rows[0].summaryChars)"

    # ---------- 6. 多模态（4.35） ----------
    $r = Call 'GET' "$admin/ai/audit/ocr-tasks?userId=$userId" $ah
    $rows = @($r.Json.data.records)
    $okRow = @($rows | Where-Object { $_.taskStatus -eq 2 -and $_.mediaId -eq 999001 })[0]
    $failRow = @($rows | Where-Object { $_.taskStatus -eq 3 -and $_.mediaId -eq 999002 })[0]
    Check '13. 多模态列表含成功与失败两条' ($r.Code -eq 200 -and $null -ne $okRow -and $null -ne $failRow) "条数 $($rows.Count)"
    Check '14. 结果只给 200 字预览、字数给全量' (
        $null -ne $okRow -and $okRow.resultChars -gt 300 -and $okRow.resultPreview.Length -le 201
    ) "chars=$($okRow.resultChars) preview=$($okRow.resultPreview.Length)"
    Check '15. 未结束任务耗时为空、已完成任务耗时为正' (
        $null -ne $okRow -and $null -ne $okRow.durationMs -and $okRow.durationMs -gt 0
    ) "durationMs=$($okRow.durationMs)"
    Check '16. 失败行带 errorMsg' ($null -ne $failRow -and $failRow.errorMsg -like '*模糊*') $failRow.errorMsg

    # ---------- 7. 内容审核命中（4.36） ----------
    $r = Call 'GET' "$admin/ai/audit/moderation-hits?severity=3" $ah
    $rows = @($r.Json.data.records | Where-Object { $_.reason -like "$marker*" })
    Check '17. 内容审核命中按严重度过滤' ($r.Code -eq 200 -and $rows.Count -eq 1 -and $rows[0].targetType -eq 'ARTICLE') "条数 $($rows.Count)"
    Check '18. 命中明细只给位置与片段哈希、不返回原文' (
        $rows.Count -eq 1 -and $rows[0].snippetHash.Length -eq 64 -and
        $rows[0].startOffset -eq 120 -and $rows[0].endOffset -eq 148 -and
        $null -eq $rows[0].PSObject.Properties['contentText']
    ) "hash=$($rows[0].snippetHash)"

    # ---------- 8. 翻译缓存（4.37） ----------
    $r = Call 'GET' "$admin/ai/audit/translations?kind=ARTICLE_BODY&status=READY" $ah
    $rows = @($r.Json.data.records | Where-Object { $_.resourceId -eq $localizationId })
    Check '19. 翻译列表按类型+状态过滤' ($r.Code -eq 200 -and $rows.Count -eq 1 -and $rows[0].promptVer -eq 'translation@v1') "条数 $($rows.Count)"
    Check '20. 译文只给 200 字预览、字数给全量' (
        $rows.Count -eq 1 -and $rows[0].translatedPreview.Length -le 201 -and $rows[0].translatedChars -gt 300
    ) "preview=$($rows[0].translatedPreview.Length) chars=$($rows[0].translatedChars)"

    # ---------- 9. 只读性：不写审计 ----------
    $before = AuditTotal
    [void](Call 'GET' "$admin/ai/audit/tool-calls?sessionId=$sessionId" $ah)
    [void](Call 'GET' "$admin/ai/audit/memories?userId=$userId&includeDeleted=true" $ah)
    [void](Call 'GET' "$admin/ai/audit/summaries?userId=$userId" $ah)
    [void](Call 'GET' "$admin/ai/audit/ocr-tasks?userId=$userId" $ah)
    [void](Call 'GET' "$admin/ai/audit/moderation-hits?severity=3" $ah)
    [void](Call 'GET' "$admin/ai/audit/translations?kind=ARTICLE_BODY" $ah)
    $after = AuditTotal
    Check '21. 六个审计读接口都不写 ops_audit_log' (($after - $before) -eq 0) "新增 $($after - $before) 条"
}
finally {
    # 夹具全部物理删：这些表都是流水，重复累积会让计数类断言在重跑时假失败
    [void](Sql "DELETE FROM sugar_health.chat_tool_call WHERE session_id IN (SELECT id FROM sugar_health.chat_session WHERE title LIKE '$marker%');")
    [void](Sql "DELETE FROM sugar_health.chat_user_memory WHERE memory_key LIKE 'e2e_audit_%' AND user_id=$userId;")
    [void](Sql "DELETE FROM sugar_health.ocr_task WHERE media_id IN (999001, 999002) AND user_id=$userId;")
    [void](Sql "DELETE FROM sugar_health.community_moderation_hit WHERE reason LIKE '$marker%';")
    [void](Sql "DELETE FROM sugar_health.content_localization WHERE resource_id LIKE 'e2e-audit%';")
    [void](Sql "DELETE FROM sugar_health.chat_session WHERE title LIKE '$marker%';")
}

Write-Host ""
Write-Host "== 结果：PASS=$script:pass FAIL=$script:fail ==" -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
if ($script:fail -gt 0) { exit 1 }
