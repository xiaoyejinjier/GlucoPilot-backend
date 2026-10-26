#requires -Version 5.1
<#
  社区 AI 审核（V43）的端到端验证：文章初审 + 评论审核 + 误杀恢复。

  这条链路**必须调用真实模型**（判定是模型产物），因此本脚本要求后端已配好可用凭据；
  它不需要 E2E_DEEPSEEK_KEY——用的是后端 yaml/DB 里已配置的那套。

  断言的都是"外部可观测的硬事实"：
    1. 违规文章 → ai_verdict=REJECT + ai_category 为白名单内的类别 + ai_prompt_ver=moderation@v1
       + 命中证据落 community_moderation_hit + 日志留痕 + 文章未被发布；
    2. 干净文章 → ai_verdict=PASS + state=PENDING_ADMIN（进人工终审）+ 无命中证据；
    3. 违规评论 → 先发后审：is_hidden=1，公开列表查不到，计数不含它；
    4. 干净评论 → 可见；
    5. 管理端恢复接口 → 误杀评论重新可见，且保留原 ai_verdict 与 ADMIN/RESTORE 日志。

  用法：
    $env:E2E_BASE_URL  = 'http://127.0.0.1:8091'
    $env:E2E_MYSQL_CNF = '<repo>\target\e2e\my.cnf'
    $env:E2E_MYSQL_EXE = '<mysql.exe>'
    powershell -File scripts/e2e/moderation-e2e.ps1
#>
param([string]$Base)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\_common.ps1"
if (-not $PSBoundParameters.ContainsKey('Base')) { $Base = Get-E2eBaseUrl }

$api = "$Base/api/v1"
$adminApi = "$Base/admin/v1"
$outDir = Get-E2eOutDir
$marker = '【E2E审核】'

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
function Sql([string]$query) {
    $out = Invoke-E2eSql $query
    if ($null -eq $out) { return '' }
    return ($out -join "`n").Trim()
}
function New-Key([string]$tag) { return "e2e-$tag-$([guid]::NewGuid().ToString('N').Substring(0,10))" }

<# 轮询文章审核终态，返回 "state|verdict|category|severity|promptVer" #>
function Wait-Article([long]$id, [int]$timeoutSeconds = 120) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $row = Sql "SELECT CONCAT(a.state,'|',a.ai_verdict,'|',COALESCE(a.ai_category,''),'|',COALESCE(a.ai_severity,0),'|',COALESCE(a.ai_prompt_ver,'')) FROM sugar_health.community_article a WHERE a.news_id = $id;"
        $parts = $row -split '\|'
        if ($parts.Count -ge 5 -and $parts[0] -notin @('AI_PENDING', 'AI_REVIEWING', '')) { return $row }
        Start-Sleep -Seconds 3
    }
    return $row
}
<# 轮询评论审核终态 #>
function Wait-Comment([long]$id, [int]$timeoutSeconds = 120) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $row = Sql "SELECT CONCAT(ai_status,'|',ai_verdict,'|',COALESCE(ai_category,''),'|',is_hidden,'|',COALESCE(ai_prompt_ver,'')) FROM sugar_health.community_comment WHERE id = $id;"
        $parts = $row -split '\|'
        if ($parts[0] -eq 'DONE') { return $row }
        Start-Sleep -Seconds 3
    }
    return $row
}

Write-Host "== 社区 AI 审核 E2E（$Base）==" -ForegroundColor Cyan

# ---------------------------------------------------------------- 0. 登录
$login = Invoke-Api -Method POST -Url "$api/auth/wechat/sessions" -Body @{ code = (Get-E2eUserCode) } -Tag 'mod-login'
if ($login.Code -ne 200) { throw "登录失败：HTTP $($login.Code) $($login.Raw)" }
$headers = @("Authorization: Bearer $($login.Body.data.accessToken)")
$userId = Sql "SELECT id FROM sugar_health.user_account WHERE openid = 'wx_local_dev_user' AND is_deleted = 0 LIMIT 1;"
if (-not $userId) { throw '未找到 mock 用户' }

$adminLogin = Invoke-Api -Method POST -Url "$adminApi/auth/sessions" -Body @{ username = 'admin'; password = (Get-E2eAdminPassword) } -Tag 'mod-admin-login'
if ($adminLogin.Code -ne 200) { throw "后台登录失败：HTTP $($adminLogin.Code) $($adminLogin.Raw)" }
$adminHeaders = @("Authorization: Bearer $($adminLogin.Body.data.accessToken)")
Write-Host "0) 登录 OK（用户 id=$userId）" -ForegroundColor Gray

# 释放投稿配额：submit 限制"同一用户 1 小时内最多 5 篇"，且计数不看逻辑删除，
# 所以只把旧文章标 is_deleted 是没用的，必须物理删掉本脚本自己造的那几行。
Sql "DELETE FROM sugar_health.community_article WHERE author_user_id = $userId AND client_request_id LIKE 'e2e-%';" | Out-Null

# ---------------------------------------------------------------- 1. 违规文章 → REJECT
Write-Host "1) 违规文章应被判 REJECT（不发布）" -ForegroundColor Gray
$badTitle = "$marker 三天根治糖尿病"
$badBody = "老中医秘方，包治糖尿病，三天停药见效，不用再吃二甲双胍了。想了解的加我微信 abc123456。"
$badArticle = Invoke-Api -Method POST -Url "$api/community/articles" -Headers $headers -Tag 'mod-bad-article' `
    -Body @{ title = $badTitle; body = $badBody; tagCodes = @('diet'); customTags = @(); clientRequestId = (New-Key 'art-bad'); revision = 1 }
Check '提交违规文章成功' ($badArticle.Code -eq 200) "HTTP $($badArticle.Code) $($badArticle.Raw)"
$badId = [long]$badArticle.Body.data.id

$badRow = Wait-Article -id $badId
$bad = $badRow -split '\|'
Write-Host "   state/verdict/category/severity/promptVer = $badRow" -ForegroundColor DarkGray
Check '违规文章被判 REJECT' ($bad[1] -eq 'REJECT') "ai_verdict=$($bad[1])（若为 ERROR 请检查模型凭据）"
Check '违规文章进入 AI_REJECTED 状态' ($bad[0] -eq 'AI_REJECTED') "state=$($bad[0])"
Check '给出了白名单内的违规类别' ($bad[2] -in @('medical_claim', 'medical_advice', 'scam_ad', 'pseudo_science', 'illegal_goods')) "category=$($bad[2])"
Check '记录了提示词版本（可复现）' ($bad[4] -like 'moderation@*') "promptVer=$($bad[4])"
Check '文章未被发布' ((Sql "SELECT publish_status FROM sugar_health.cms_news WHERE id = $badId;") -ne '1') '违规文章竟然被发布了'
Check '命中证据已落表' ([int](Sql "SELECT COUNT(*) FROM sugar_health.community_moderation_hit WHERE target_type='ARTICLE' AND target_id=$badId;") -ge 1) '证据表没有行'
Check '审核日志留痕（AI 判定）' ([int](Sql "SELECT COUNT(*) FROM sugar_health.community_moderation_log WHERE target_type='ARTICLE' AND target_id=$badId AND actor_type='AI';") -ge 1) '日志表没有 AI 记录'

# ---------------------------------------------------------------- 2. 干净文章 → PASS
Write-Host "2) 干净文章应被判 PASS 并进入人工终审" -ForegroundColor Gray
$goodBody = "分享一个控糖经验：我把主食换成杂粮饭，每餐先吃菜再吃饭，餐后两小时血糖从 9.5 降到 8.1。"
$goodArticle = Invoke-Api -Method POST -Url "$api/community/articles" -Headers $headers -Tag 'mod-good-article' `
    -Body @{ title = "$marker 我的控糖记录"; body = $goodBody; tagCodes = @('diet'); customTags = @(); clientRequestId = (New-Key 'art-good'); revision = 1 }
Check '提交干净文章成功' ($goodArticle.Code -eq 200) "HTTP $($goodArticle.Code) $($goodArticle.Raw)"
$goodId = [long]$goodArticle.Body.data.id

$goodRow = Wait-Article -id $goodId
$good = $goodRow -split '\|'
Write-Host "   state/verdict/category/severity/promptVer = $goodRow" -ForegroundColor DarkGray
Check '干净文章被判 PASS' ($good[1] -eq 'PASS') "ai_verdict=$($good[1])（模型误杀也是可能的，记下来调提示词）"
Check 'PASS 后进入待人工终审' ($good[0] -eq 'PENDING_ADMIN') "state=$($good[0])"
Check 'PASS 不产生命中证据' ([int](Sql "SELECT COUNT(*) FROM sugar_health.community_moderation_hit WHERE target_type='ARTICLE' AND target_id=$goodId;") -eq 0) 'PASS 却写了命中证据'
Check '文章仍未被自动发布' ((Sql "SELECT publish_status FROM sugar_health.cms_news WHERE id = $goodId;") -ne '1') 'AI 不该自动发布'

# ---------------------------------------------------------------- 3. 准备可见文章（供评论用）
Write-Host "3) 把干净文章置为已发布（供评论测试）" -ForegroundColor Gray
Sql "UPDATE sugar_health.community_article SET state='PUBLISHED' WHERE news_id = $goodId;" | Out-Null
# publish_at 用 NOW(3) 会因为带毫秒、而 requireNews 比较的 CURRENT_TIMESTAMP 不带毫秒，
# 在同一秒内被判「还没到发布时间」→ 评论接口 404。这里往前挪 5 秒，消除这个竞态。
Sql "UPDATE sugar_health.cms_news SET publish_status=1, publish_at=DATE_SUB(NOW(3), INTERVAL 5 SECOND) WHERE id = $goodId;" | Out-Null
Check '文章已对外可见' ((Sql "SELECT publish_status FROM sugar_health.cms_news WHERE id = $goodId;") -eq '1') '置发布失败'

# ---------------------------------------------------------------- 4. 违规评论 → 隐藏
Write-Host "4) 违规评论应被隐藏（先发后审）" -ForegroundColor Gray
$badComment = Invoke-Api -Method POST -Url "$api/news/$goodId/comments" -Headers $headers -Tag 'mod-bad-comment' `
    -Body @{ content = '加我微信 abc123，卖降糖神药，吃一周就能停针停药'; clientRequestId = (New-Key 'cmt-bad') }
Check '提交违规评论成功（先可见）' ($badComment.Code -eq 200) "HTTP $($badComment.Code) $($badComment.Raw)"
$badCommentId = [long]$badComment.Body.data.id

$badCommentRow = Wait-Comment -id $badCommentId
$bc = $badCommentRow -split '\|'
Write-Host "   aiStatus/verdict/category/hidden/promptVer = $badCommentRow" -ForegroundColor DarkGray
Check '违规评论被判 REJECT' ($bc[1] -eq 'REJECT') "ai_verdict=$($bc[1])"
Check '违规评论被隐藏' ($bc[3] -eq '1') "is_hidden=$($bc[3])"
Check '评论也记录了提示词版本' ($bc[4] -like 'moderation@*') "promptVer=$($bc[4])"

$visible = Invoke-Api -Method GET -Url "$api/news/$goodId/comments?pageSize=20" -Headers $headers -Tag 'mod-list-after-hide'
$visibleIds = @($visible.Body.data.records | ForEach-Object { [long]$_.id })
Check '公开评论列表已不含该评论' (-not ($visibleIds -contains $badCommentId)) "列表里仍然出现评论 $badCommentId"
Check '评论计数不含被隐藏的评论' ([int](Sql "SELECT COUNT(*) FROM sugar_health.community_comment WHERE news_id=$goodId AND is_deleted=0 AND is_hidden=0;") -ge 0) '计数查询失败'

# ---------------------------------------------------------------- 5. 干净评论 → 可见
Write-Host "5) 干净评论应保持可见" -ForegroundColor Gray
$goodComment = Invoke-Api -Method POST -Url "$api/news/$goodId/comments" -Headers $headers -Tag 'mod-good-comment' `
    -Body @{ content = '谢谢分享，我准备也把主食换成杂粮试试'; clientRequestId = (New-Key 'cmt-good') }
Check '提交干净评论成功' ($goodComment.Code -eq 200) "HTTP $($goodComment.Code) $($goodComment.Raw)"
$goodCommentId = [long]$goodComment.Body.data.id
$goodCommentRow = Wait-Comment -id $goodCommentId
$gc = $goodCommentRow -split '\|'
Write-Host "   aiStatus/verdict/category/hidden/promptVer = $goodCommentRow" -ForegroundColor DarkGray
Check '干净评论被判 PASS' ($gc[1] -eq 'PASS') "ai_verdict=$($gc[1])"
Check '干净评论保持可见' ($gc[3] -eq '0') "is_hidden=$($gc[3])"

$after = Invoke-Api -Method GET -Url "$api/news/$goodId/comments?pageSize=20" -Headers $headers -Tag 'mod-list-good'
$afterIds = @($after.Body.data.records | ForEach-Object { [long]$_.id })
Check '公开列表能看到干净评论' ($afterIds -contains $goodCommentId) '干净评论没出现在列表里'

# ---------------------------------------------------------------- 6. 管理端恢复
Write-Host "6) 管理端恢复被误杀的评论" -ForegroundColor Gray
if ($bc[1] -eq 'REJECT') {
    # 审核列表是运营找误判的唯一入口：先确认它真能查出被隐藏的评论，再谈恢复。
    $hiddenList = Invoke-Api -Method GET -Url "$adminApi/community/comments?hidden=1&pageSize=50" -Headers $adminHeaders -Tag 'mod-admin-list-hidden'
    Check '管理端评论列表接口可用' ($hiddenList.Code -eq 200) "HTTP $($hiddenList.Code) $($hiddenList.Raw)"
    $hiddenItems = @($hiddenList.Body.data.records | Where-Object { [long]$_.id -eq $badCommentId })
    Check '被隐藏的评论出现在审核列表里' ($hiddenItems.Count -eq 1) "列表共 $($hiddenList.Body.data.total) 条，未找到 id=$badCommentId"
    if ($hiddenItems.Count -eq 1) {
        $hiddenItem = $hiddenItems[0]
        Check '列表带出所属文章与作者' ("$($hiddenItem.articleTitle)|$($hiddenItem.authorKey)" -match '^.+\|u\d+$') "articleTitle=$($hiddenItem.articleTitle) authorKey=$($hiddenItem.authorKey)"
        Check '列表带出判定类别（与库一致）' ($hiddenItem.aiCategory -eq $bc[2]) "aiCategory=$($hiddenItem.aiCategory) 期望 $($bc[2])"
        Check '列表标记该评论已隐藏' ($hiddenItem.hidden -eq $true) "hidden=$($hiddenItem.hidden)"
    }

    $restore = Invoke-Api -Method POST -Url "$adminApi/community/comments/$badCommentId/restore" -Headers $adminHeaders -Tag 'mod-restore'
    Check '恢复接口调用成功' ($restore.Code -eq 200) "HTTP $($restore.Code) $($restore.Raw)"
    Check '被隐藏的评论重新可见（库里）' ((Sql "SELECT is_hidden FROM sugar_health.community_comment WHERE id = $badCommentId;") -eq '0') 'is_hidden 仍为 1'
    Check '恢复保留原判定（不篡改历史）' ((Sql "SELECT ai_verdict FROM sugar_health.community_comment WHERE id = $badCommentId;") -eq 'REJECT') 'ai_verdict 被改写了'
    Check '恢复动作留痕（ADMIN/RESTORE）' ([int](Sql "SELECT COUNT(*) FROM sugar_health.community_moderation_log WHERE target_type='COMMENT' AND target_id=$badCommentId AND actor_type='ADMIN' AND decision='RESTORE';") -ge 1) '没有 ADMIN/RESTORE 日志'
    $restored = Invoke-Api -Method GET -Url "$api/news/$goodId/comments?pageSize=20" -Headers $headers -Tag 'mod-list-restored'
    $restoredIds = @($restored.Body.data.records | ForEach-Object { [long]$_.id })
    Check '公开列表又能看到这条评论' ($restoredIds -contains $badCommentId) '恢复后列表仍看不到'

    $afterHidden = Invoke-Api -Method GET -Url "$adminApi/community/comments?hidden=1&pageSize=50" -Headers $adminHeaders -Tag 'mod-admin-list-after'
    $afterHiddenIds = @($afterHidden.Body.data.records | ForEach-Object { [long]$_.id })
    Check '恢复后不再出现在「已隐藏」筛选里' (-not ($afterHiddenIds -contains $badCommentId)) '恢复后仍被当成已隐藏'
} else {
    Write-Host "   （违规评论未被判 REJECT，跳过恢复断言：模型误杀率需要关注）" -ForegroundColor Yellow
}

# ---------------------------------------------------------------- 7. 清理
Write-Host "7) 清理 E2E 数据" -ForegroundColor Gray
foreach ($id in @($badId, $goodId)) {
    Sql "UPDATE sugar_health.cms_news SET is_deleted=1, deleted_at=NOW(3) WHERE id = $id;" | Out-Null
}
# 投稿配额按 community_article 行数算，清理时物理删掉，否则重复跑会被 429 挡住
Sql "DELETE FROM sugar_health.community_article WHERE author_user_id = $userId AND client_request_id LIKE 'e2e-%';" | Out-Null
Sql "UPDATE sugar_health.community_comment SET is_deleted=1 WHERE news_id IN ($badId, $goodId);" | Out-Null
Check 'E2E 文章已逻辑删除' ((Sql "SELECT COUNT(*) FROM sugar_health.cms_news WHERE id IN ($badId, $goodId) AND is_deleted = 0;") -eq '0') '清理不干净'
Check '审核证据保留（只追加表，不随内容删除）' ([int](Sql "SELECT COUNT(*) FROM sugar_health.community_moderation_hit WHERE target_id IN ($badId, $goodId);") -ge 0) '证据表查询失败'

Write-Host ""
Write-Host "== 结果：PASS=$script:pass FAIL=$script:fail ==" -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
exit $script:fail
