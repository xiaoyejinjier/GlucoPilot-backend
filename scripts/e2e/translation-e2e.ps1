#requires -Version 5.1
<#
  中英翻译（V54 收束到 ai.business.translation）的端到端验证。

  这条链路**会调用真实模型**（翻译是模型产物），因此要求后端已配好可用凭据；
  它不需要 E2E_DEEPSEEK_KEY——用的是后端 yaml/DB 里已配置的那套。
  一次运行消耗 1~2 次模型调用（只有 ARTICLE_PREVIEW 会真的请求模型，其余都是缓存或本地直判）。

  断言的都是"外部可观测的硬事实"：
    1. 中文文章首次请求 → PENDING 或 READY，并给出 sourceHash（缓存键 = 提示词版本 + 源文本）；
    2. 轮询到 READY：标题不再含汉字，且 content_localization.prompt_ver = translation@v1
       （换提示词必须让旧缓存失效，这一列是事后排障的唯一线索）；
    3. 同样的请求再来一次 → 直接 READY、sourceHash 不变（命中缓存，不再花钱）；
    4. 纯英文昵称直判 READY，source 与 translated 一致（不请求模型）；
    5. 不可读的文章 → UNAVAILABLE（不是 500，也不泄露文章是否存在）。

  用法：
    $env:E2E_BASE_URL  = 'http://127.0.0.1:8091'
    $env:E2E_MYSQL_CNF = '<repo>\target\e2e\my.cnf'
    $env:E2E_MYSQL_EXE = '<mysql.exe>'
    powershell -File scripts/e2e/translation-e2e.ps1
#>
param([string]$Base)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\_common.ps1"
if (-not $PSBoundParameters.ContainsKey('Base')) { $Base = Get-E2eBaseUrl }

$api = "$Base/api/v1"
$outDir = Get-E2eOutDir
$marker = '【E2E翻译】'

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
function Has-Han([string]$text) { return [bool]([regex]::IsMatch($text, '[\u4e00-\u9fff]')) }

<# 轮询到终态：返回最后一条 Result（PENDING 期间重复请求不会额外花钱，缓存未就绪时 claim 拿不到租约）。#>
function Wait-Localized($Items, [int]$timeoutSeconds = 180) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    $last = $null
    while ((Get-Date) -lt $deadline) {
        $call = Invoke-Api -Method POST -Url "$api/localizations/en" -Headers $headers -Body @{ items = $Items } -Tag 'tr-poll'
        if ($call.Code -ne 200) { return $call }
        $row = @($call.Body.data)[0]
        $last = [pscustomobject]@{ Code = $call.Code; Row = $row; Raw = $call.Raw }
        if ($row.status -ne 'PENDING') { return $last }
        Start-Sleep -Seconds 3
    }
    return $last
}

Write-Host "== 中英翻译 E2E（$Base）==" -ForegroundColor Cyan

# ---------------------------------------------------------------- 0. 登录 + 清场
$login = Invoke-Api -Method POST -Url "$api/auth/wechat/sessions" -Body @{ code = (Get-E2eUserCode) } -Tag 'tr-login'
if ($login.Code -ne 200) { throw "登录失败：HTTP $($login.Code) $($login.Raw)" }
$headers = @("Authorization: Bearer $($login.Body.data.accessToken)")
$userId = Sql "SELECT id FROM sugar_health.user_account WHERE openid = 'wx_local_dev_user' AND is_deleted = 0 LIMIT 1;"
if (-not $userId) { throw '未找到 mock 用户' }
Write-Host "0) 登录 OK（用户 id=$userId）" -ForegroundColor Gray

# 清掉本脚本上次留下的投稿（submit 配额"同一用户 1 小时内最多 5 篇"且不看逻辑删除）
Sql "DELETE FROM sugar_health.community_article WHERE author_user_id = $userId AND client_request_id LIKE 'e2e-%';" | Out-Null

# ---------------------------------------------------------------- 1. 造一篇可读的中文文章
Write-Host "1) 准备一篇已发布的中文文章" -ForegroundColor Gray
$body = "我把主食换成了杂粮饭，每餐先吃菜再吃饭，餐后两小时血糖从 9.5 降到 8.1，坚持了三个月，腰围也小了一圈。"
$article = Invoke-Api -Method POST -Url "$api/community/articles" -Headers $headers -Tag 'tr-article' `
    -Body @{ title = "$marker 控糖三个月记录"; body = $body; tagCodes = @('diet'); customTags = @(); clientRequestId = (New-Key 'art-tr'); revision = 1 }
if ($article.Code -ne 200) { throw "提交文章失败：HTTP $($article.Code) $($article.Raw)" }
$newsId = [long]$article.Body.data.id
# 直接置为已发布：翻译只关心"读者能不能读到"，不关心它是不是 AI 放行的
Sql "UPDATE sugar_health.community_article SET state='PUBLISHED' WHERE news_id = $newsId;" | Out-Null
Sql "UPDATE sugar_health.cms_news SET publish_status=1, publish_at=DATE_SUB(NOW(3), INTERVAL 5 SECOND) WHERE id = $newsId;" | Out-Null
Check '文章已对外可见' ((Sql "SELECT publish_status FROM sugar_health.cms_news WHERE id = $newsId;") -eq '1') '置发布失败'

# ---------------------------------------------------------------- 2. ARTICLE_PREVIEW → READY
Write-Host "2) 中文文章摘要首次请求 → 翻译为英文" -ForegroundColor Gray
$items = @(@{ kind = 'ARTICLE_PREVIEW'; id = "$newsId" })
$first = Invoke-Api -Method POST -Url "$api/localizations/en" -Headers $headers -Body @{ items = $items } -Tag 'tr-preview-1'
Check '翻译接口可用' ($first.Code -eq 200) "HTTP $($first.Code) $($first.Raw)"
$firstRow = @($first.Body.data)[0]
Check '首次请求返回 PENDING 或 READY' ($firstRow.status -in @('PENDING', 'READY')) "status=$($firstRow.status)"
Check '返回了缓存键 sourceHash' (-not [string]::IsNullOrWhiteSpace($firstRow.sourceHash)) 'sourceHash 为空，缓存与失效都无从谈起'

$ready = Wait-Localized $items
if ($ready.Code -ne 200) { throw "轮询翻译失败：HTTP $($ready.Code) $($ready.Raw)" }
$row = $ready.Row
Write-Host "   status=$($row.status) title=$($row.translated.title)" -ForegroundColor DarkGray
Check '最终状态为 READY' ($row.status -eq 'READY') "status=$($row.status)（FAILED 请检查模型凭据与提示词）"
Check '标题已译为英文（不含汉字）' (-not (Has-Han $row.translated.title)) "title=$($row.translated.title)"
Check '摘要已译为英文（不含汉字）' (-not (Has-Han $row.translated.summary)) "summary=$($row.translated.summary)"
Check '缓存表记录了提示词版本' ((Sql "SELECT COALESCE(prompt_ver,'') FROM sugar_health.content_localization WHERE kind='ARTICLE_PREVIEW' AND resource_id=$newsId AND source_hash='$($row.sourceHash)' LIMIT 1;") -eq 'translation@v1') 'prompt_ver 不是 translation@v1（换提示词后旧缓存不会失效）'

# ---------------------------------------------------------------- 3. 重复请求命中缓存
Write-Host "3) 同样的请求应直接命中缓存" -ForegroundColor Gray
$again = Invoke-Api -Method POST -Url "$api/localizations/en" -Headers $headers -Body @{ items = $items } -Tag 'tr-preview-2'
Check '第二次请求直接 READY' (@($again.Body.data)[0].status -eq 'READY') "status=$(@($again.Body.data)[0].status)"
Check '缓存键未变（提示词版本 + 源文本一致）' (@($again.Body.data)[0].sourceHash -eq $row.sourceHash) 'sourceHash 变了，说明缓存键掺了不稳定因素'

# ---------------------------------------------------------------- 4. 纯英文昵称直判，不请求模型
Write-Host "4) 纯英文昵称直判 READY（不请求模型）" -ForegroundColor Gray
$before = Sql "SELECT COALESCE(nickname,'') FROM sugar_health.user_profile WHERE user_id=$userId AND is_deleted=0 LIMIT 1;"
Sql "UPDATE sugar_health.user_profile SET nickname='E2E Tester' WHERE user_id=$userId AND is_deleted=0;" | Out-Null
$nameItems = @(@{ kind = 'PROFILE_NAME'; id = "u$userId" })
$name = Invoke-Api -Method POST -Url "$api/localizations/en" -Headers $headers -Body @{ items = $nameItems } -Tag 'tr-name'
$nameRow = @($name.Body.data)[0]
Check '纯英文昵称返回 READY' ($nameRow.status -eq 'READY') "status=$($nameRow.status)"
Check '源文本与译文一致（没有多余的模型往返）' ($nameRow.translated.nickname -eq 'E2E Tester') "nickname=$($nameRow.translated.nickname)"

# 生成昵称（用户_xxx）也应本地直判，只在第一次见到时替换前缀
Sql "UPDATE sugar_health.user_profile SET nickname='用户_e2e123' WHERE user_id=$userId AND is_deleted=0;" | Out-Null
$gen = Invoke-Api -Method POST -Url "$api/localizations/en" -Headers $headers -Body @{ items = $nameItems } -Tag 'tr-name-gen'
$genRow = @($gen.Body.data)[0]
Check '生成昵称本地替换为 User_ 前缀' ($genRow.translated.nickname -eq 'User_e2e123') "nickname=$($genRow.translated.nickname)"
if ($before) { Sql "UPDATE sugar_health.user_profile SET nickname='$before' WHERE user_id=$userId AND is_deleted=0;" | Out-Null }

# ---------------------------------------------------------------- 5. 不可读的文章
Write-Host "5) 不可读的文章应返回 UNAVAILABLE" -ForegroundColor Gray
$missing = Invoke-Api -Method POST -Url "$api/localizations/en" -Headers $headers -Tag 'tr-missing' `
    -Body @{ items = @(@{ kind = 'ARTICLE_PREVIEW'; id = '999999999' }) }
Check '不存在的文章返回 200 + UNAVAILABLE' ($missing.Code -eq 200 -and (@($missing.Body.data)[0].status -eq 'UNAVAILABLE')) "HTTP $($missing.Code) status=$(@($missing.Body.data)[0].status)"

# ---------------------------------------------------------------- 清理
Sql "DELETE FROM sugar_health.content_localization WHERE kind IN ('ARTICLE_PREVIEW','ARTICLE_BODY') AND resource_id = $newsId;" | Out-Null
Sql "DELETE FROM sugar_health.community_article WHERE author_user_id = $userId AND client_request_id LIKE 'e2e-%';" | Out-Null

Write-Host ""
if ($script:fail -eq 0) {
    Write-Host "PASS=$($script:pass) FAIL=0" -ForegroundColor Green
    exit 0
}
Write-Host "PASS=$($script:pass) FAIL=$($script:fail)" -ForegroundColor Red
exit 1
