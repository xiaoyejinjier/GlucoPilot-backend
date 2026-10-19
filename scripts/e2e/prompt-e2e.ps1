#requires -Version 5.1
<#
  提示词管理 + 场景绑定 的端到端验证（契约 4.11~4.13 与本轮新增的 /admin/v1/ai-scenes）。

  验证的核心命题（也是运营最关心的那条）：
    「后台改提示词 → 运行中的服务立刻按新版提示词工作 → 旧版本还在、可以一键回滚」
  刻意不依赖任何外部模型调用（不需要 E2E_DEEPSEEK_KEY）：
    * 「立刻生效」的证据有两处硬证据：
        1) 应用日志里 AiPromptTemplateService 的「AI 提示词库表版本已加载：CHAT=chat@v900」——
           这行只在运行中的单例真正重新读了库之后才会打印；
        2) 后台接口返回的 scenes[].activeVersion / activeSource 就取自那个单例
           （DynamicPromptAdvisor 每请求读的也是它）；
        3) **最强的一条**：新建会话后 chat_session.system_prompt_ver 落的就是 chat@v900
           （下一条消息用的就是新提示词，端到端、不靠推断）。
    * 为了让「下一条消息」真的产生一条新会话，脚本会先把该 mock 用户的进行中会话归档
      （见 Archive-ActiveSessions：只用开发库里的 mock 用户，且这是"新会话"唯一的产生方式）。

  用法：
    $env:E2E_BASE_URL = 'http://127.0.0.1:8091'
    $env:E2E_MYSQL_CNF = '<repo>\target\e2e\my.cnf'
    pwsh -File scripts/e2e/prompt-e2e.ps1
#>
param([string]$Base)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\_common.ps1"
if (-not $PSBoundParameters.ContainsKey('Base')) { $Base = Get-E2eBaseUrl }

$adminApi = "$Base/admin/v1"
$appApi = "$Base/api/v1"
$outDir = Get-E2eOutDir
$log = Get-E2eLogPath

# E2E 专用的版本号：取一个真实运营不会用的高位版本，便于识别与清理
$testVersion = 900
$testScene = 'CHAT'
$marker = '【E2E-提示词标记】'

$script:pass = 0
$script:fail = 0

function Ok([string]$name) {
    $script:pass++
    Write-Host "  [PASS] $name" -ForegroundColor Green
}
function Ng([string]$name, [string]$detail) {
    $script:fail++
    Write-Host "  [FAIL] $name — $detail" -ForegroundColor Red
}
function Check([string]$name, [bool]$condition, [string]$detail) {
    if ($condition) { Ok $name } else { Ng $name $detail }
}

function Save-Json([string]$name, $Object) {
    $path = Join-Path $outDir $name
    [System.IO.File]::WriteAllText($path, ($Object | ConvertTo-Json -Compress -Depth 8), (New-Object System.Text.UTF8Encoding($false)))
    return $path
}

<# curl.exe 调用：返回 @{ Code = HTTP 状态码; Body = 解析后的 JSON; Raw = 原始响应 }。#>
function Invoke-Api {
    param(
        [string]$Method,
        [string]$Url,
        [string[]]$Headers = @(),
        $Body = $null,
        [string]$Tag = 'call'
    )
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

<# 归档该 mock 用户的进行中会话，让「下一条消息」必然落到一条新会话（新会话才会重新落提示词版本）。#>
function Archive-ActiveSessions([long]$userId) {
    Invoke-E2eSql "UPDATE sugar_health.chat_session SET session_status = 2 WHERE user_id = $userId AND session_status = 1 AND is_deleted = 0;" | Out-Null
}

Write-Host "== 提示词管理 / 场景绑定 E2E（$Base）==" -ForegroundColor Cyan

# ---------------------------------------------------------------- 0. 登录
$adminLogin = Invoke-Api -Method POST -Url "$adminApi/auth/sessions" `
    -Body @{ username = 'admin'; password = (Get-E2eAdminPassword) } -Tag 'admin-login'
if ($adminLogin.Code -ne 200) { throw "后台登录失败：HTTP $($adminLogin.Code) $($adminLogin.Raw)" }
$adminToken = $adminLogin.Body.data.accessToken
$adminHeaders = @("Authorization: Bearer $adminToken")
Write-Host "0) 后台登录 OK" -ForegroundColor Gray

$appLogin = Invoke-Api -Method POST -Url "$appApi/auth/wechat/sessions" `
    -Body @{ code = (Get-E2eUserCode) } -Tag 'app-login'
if ($appLogin.Code -ne 200) { throw "小程序登录失败：HTTP $($appLogin.Code) $($appLogin.Raw)" }
$appToken = $appLogin.Body.data.accessToken
$appHeaders = @("Authorization: Bearer $appToken")
$userId = Get-SqlScalar "SELECT id FROM sugar_health.user_account WHERE openid = 'wx_local_dev_user' AND is_deleted = 0 LIMIT 1;"
if (-not $userId) {
    $userId = Get-SqlScalar "SELECT id FROM sugar_health.user_account WHERE is_deleted = 0 ORDER BY id DESC LIMIT 1;"
}
Write-Host "   模拟用户 id=$userId" -ForegroundColor Gray

# ---------------------------------------------------------------- 1. 列表
Write-Host "1) 查询类接口（4.11 与场景列表）" -ForegroundColor Gray
$scenes = Invoke-Api -Method GET -Url "$adminApi/ai-scenes" -Headers $adminHeaders -Tag 'ai-scenes'
Check '场景列表返回全部场景（③ 期起为 6 个：含 SUMMARY 摘要与 MEMORY 长期记忆）' ($scenes.Code -eq 200 -and @($scenes.Body.data.records).Count -eq 6) `
    "HTTP $($scenes.Code)，records=$(@($scenes.Body.data.records).Count)"
$chatScene = @($scenes.Body.data.records) | Where-Object { $_.sceneCode -eq 'CHAT' } | Select-Object -First 1
Check 'CHAT 场景给出"实际生效模型 + 来源"' ($null -ne $chatScene -and $null -ne $chatScene.effectiveModelCode -and $null -ne $chatScene.effectiveModelSource) `
    "effectiveModelCode=$($chatScene.effectiveModelCode) source=$($chatScene.effectiveModelSource)"
Check 'CHAT 场景的 model_code 标记为生效' ($chatScene.modelCodeApplies -eq $true) "modelCodeApplies=$($chatScene.modelCodeApplies)"
$mealScene = @($scenes.Body.data.records) | Where-Object { $_.sceneCode -eq 'MEAL_ANALYSIS' } | Select-Object -First 1
Check '视觉场景的 model_code 标记为不生效（如实告知）' ($mealScene.modelCodeApplies -eq $false) "modelCodeApplies=$($mealScene.modelCodeApplies)"
Check '场景列表带回可选模型清单' (@($scenes.Body.data.models).Count -ge 1) "models=$(@($scenes.Body.data.models).Count)"

$list = Invoke-Api -Method GET -Url "$adminApi/ai-prompt-templates?sceneCode=$testScene" -Headers $adminHeaders -Tag 'prompt-list'
Check '4.11 提示词列表可查（按场景）' ($list.Code -eq 200 -and @($list.Body.data.records).Count -ge 1) `
    "HTTP $($list.Code)，records=$(@($list.Body.data.records).Count)"
Check '列表带回场景元信息（含当前生效来源）' (@($list.Body.data.scenes).Count -eq 6) "scenes=$(@($list.Body.data.scenes).Count)"
$beforeActive = @($list.Body.data.records) | Where-Object { $_.active -eq $true } | Select-Object -First 1
$beforeActiveId = $beforeActive.id
# 基准标签形如 chat@v1（落 chat_session.system_prompt_ver 的那个值），回滚后必须回到它
$beforeActiveLabel = (@($list.Body.data.scenes) | Where-Object { $_.sceneCode -eq $testScene } | Select-Object -First 1).activeVersion
Write-Host "   当前生效版本：$($beforeActive.version)（id=$beforeActiveId，标签 $beforeActiveLabel）" -ForegroundColor Gray

# 清理上一次运行留下的 E2E 版本（唯一键 uk_scene_ver_active 不允许同场景同版本重复）
Invoke-E2eSql "DELETE FROM sugar_health.ai_prompt_template WHERE scene_code = '$testScene' AND version_no IN ($testVersion, $($testVersion + 1), $($testVersion + 2));" | Out-Null

# ---------------------------------------------------------------- 2. 阻断式校验
Write-Host "2) 阻断式校验（坏提示词不允许入库）" -ForegroundColor Gray
$short = Invoke-Api -Method POST -Url "$adminApi/ai-prompt-templates" -Headers $adminHeaders `
    -Body @{ sceneCode = $testScene; promptText = '太短了'; status = 0 } -Tag 'bad-short'
Check '过短正文被拒（400）' ($short.Code -eq 400 -and $short.Body.code -eq 40000) "HTTP $($short.Code) code=$($short.Body.code)"

$noSafety = Invoke-Api -Method POST -Url "$adminApi/ai-prompt-templates" -Headers $adminHeaders `
    -Body @{ sceneCode = $testScene; promptText = '你是控糖助手，用简洁中文回答用户关于血糖的问题，只做科普解释与生活方式建议，保持语气平实友好，不要追问隐私信息。'; status = 0 } -Tag 'bad-safety'
Check '缺就医指引被拒（400）' ($noSafety.Code -eq 400) "HTTP $($noSafety.Code) msg=$($noSafety.Body.message)"

$brace = Invoke-Api -Method POST -Url "$adminApi/ai-prompt-templates" -Headers $adminHeaders `
    -Body @{ sceneCode = $testScene; promptText = '你是控糖助手，用简洁中文回答用户关于血糖的问题，不做诊断，涉及治疗请引导用户及时就医。输出示例：{"level":"ok"}'; status = 0 } -Tag 'bad-brace'
Check '未声明花括号被拒（400）' ($brace.Code -eq 400) "HTTP $($brace.Code) msg=$($brace.Body.message)"

# ---------------------------------------------------------------- 3. 新增草稿 + 原地编辑 + 乐观锁
Write-Host "3) 4.12 新增（默认草稿）与编辑" -ForegroundColor Gray
$body = "$marker 你是控糖助手，用简洁中文回答用户关于血糖的问题，不做诊断、不给用药建议，涉及治疗与调整用药时请引导用户及时就医。"
$created = Invoke-Api -Method POST -Url "$adminApi/ai-prompt-templates" -Headers $adminHeaders `
    -Body @{ sceneCode = $testScene; templateName = 'E2E 临时版本'; version = "v$testVersion"; promptText = $body; variables = @(); status = 0; changeNote = 'prompt-e2e 自动创建' } -Tag 'create-draft'
Check '新增版本成功' ($created.Code -eq 200 -and $created.Body.data.id -gt 0) "HTTP $($created.Code) $($created.Body.message)"
Check '新增默认是草稿（status=0）' ($created.Body.data.status -eq 0) "status=$($created.Body.data.status)"
Check '新增不会污染线上版本（未启用）' ($created.Body.data.appended -eq $false) "appended=$($created.Body.data.appended)"
$testId = $created.Body.data.id

$dup = Invoke-Api -Method POST -Url "$adminApi/ai-prompt-templates" -Headers $adminHeaders `
    -Body @{ sceneCode = $testScene; version = "v$testVersion"; promptText = $body; status = 0 } -Tag 'dup-version'
Check '同场景同版本号重复 → 409' ($dup.Code -eq 409) "HTTP $($dup.Code) msg=$($dup.Body.message)"

$listAfterDraft = Invoke-Api -Method GET -Url "$adminApi/ai-prompt-templates?sceneCode=$testScene" -Headers $adminHeaders -Tag 'prompt-list-2'
$draftRow = @($listAfterDraft.Body.data.records) | Where-Object { $_.id -eq $testId } | Select-Object -First 1
$edited = Invoke-Api -Method PATCH -Url "$adminApi/ai-prompt-templates/$testId" -Headers $adminHeaders `
    -Body @{ sceneCode = $testScene; promptText = "$body（已编辑）"; variables = @(); status = 0; configVersion = $draftRow.configVersion; changeNote = '草稿原地更新' } -Tag 'edit-draft'
Check '草稿可原地编辑（版本号不变）' ($edited.Code -eq 200 -and $edited.Body.data.appended -eq $false -and $edited.Body.data.version -eq "v$testVersion") `
    "HTTP $($edited.Code) appended=$($edited.Body.data.appended) version=$($edited.Body.data.version)"

$stale = Invoke-Api -Method PATCH -Url "$adminApi/ai-prompt-templates/$testId" -Headers $adminHeaders `
    -Body @{ sceneCode = $testScene; promptText = $body; status = 0; configVersion = $draftRow.configVersion } -Tag 'stale-edit'
Check '乐观锁过期 → 409（不会覆盖他人改动）' ($stale.Code -eq 409) "HTTP $($stale.Code) msg=$($stale.Body.message)"

# ---------------------------------------------------------------- 4. 启用 → 立即生效
Write-Host "4) 4.13 启用 → 运行中的服务立即生效" -ForegroundColor Gray
$logLengthBefore = 0
if (Test-Path $log) { $logLengthBefore = (Get-Content -LiteralPath $log -Encoding UTF8 -ReadCount 0).Count }

$activated = Invoke-Api -Method PATCH -Url "$adminApi/ai-prompt-templates/$testId/activate" -Headers $adminHeaders -Tag 'activate'
Check '启用接口返回成功且 data 为 null' ($activated.Code -eq 200 -and $null -eq $activated.Body.data) `
    "HTTP $($activated.Code) data=$($activated.Body.data)"

$listAfter = Invoke-Api -Method GET -Url "$adminApi/ai-prompt-templates?sceneCode=$testScene" -Headers $adminHeaders -Tag 'prompt-list-3'
$activeNow = @($listAfter.Body.data.records) | Where-Object { $_.active -eq $true }
Check '同场景只有一版生效（唯一启用）' (@($activeNow).Count -eq 1) "active=$(@($activeNow).Count)"
Check '生效版本就是刚启用的那一版' (@($activeNow)[0].id -eq $testId) "activeId=$(@($activeNow)[0].id) expect=$testId"
$chatSceneNow = @($listAfter.Body.data.scenes) | Where-Object { $_.sceneCode -eq $testScene } | Select-Object -First 1
Check '运行态已切到新版本（activeVersion=chat@v900）' ($chatSceneNow.activeVersion -eq "chat@v$testVersion") "activeVersion=$($chatSceneNow.activeVersion)"
Check '运行态来源是库表（db#id）' ($chatSceneNow.activeSource -eq "db#$testId") "activeSource=$($chatSceneNow.activeSource)"

$lines = @()
if (Test-Path $log) { $lines = Get-Content -LiteralPath $log -Encoding UTF8 -ReadCount 0 }
$reloadLines = @($lines[$logLengthBefore..($lines.Count - 1)] | Where-Object { $_ -match 'AI 提示词库表版本已加载' })
Check '应用日志出现"库表版本已加载"（运行中的单例真的重读了库）' (@($reloadLines).Count -ge 1) '未在本次启用之后看到重载日志'
if (@($reloadLines).Count -ge 1) { Write-Host "   $($reloadLines[-1].Trim())" -ForegroundColor DarkGray }

$enabledCount = Get-SqlScalar "SELECT COUNT(*) FROM sugar_health.ai_prompt_template WHERE scene_code = '$testScene' AND is_enabled = 1 AND is_deleted = 0;"
Check '数据库层面同场景只有一行启用' ($enabledCount -eq '1') "enabled=$enabledCount"

# ---------------------------------------------------------------- 5. 下一条消息真的用新提示词
Write-Host "5) 端到端：下一条消息（新会话）落的提示词版本" -ForegroundColor Gray
if ($userId) {
    Archive-ActiveSessions ([long]$userId)
    $sess = Invoke-Api -Method POST -Url "$appApi/chat-sessions/current" -Headers $appHeaders -Body @{ sessionType = 3 } -Tag 'session-1'
    if ($sess.Code -eq 200) {
        $ver = Get-SqlScalar "SELECT system_prompt_ver FROM sugar_health.chat_session WHERE id = $($sess.Body.data.id);"
        Check "新会话记录 chat@v$testVersion（= 下一条消息用的提示词）" ($ver -eq "chat@v$testVersion") "system_prompt_ver=$ver"
    } else {
        Ng '新建会话（用于验证下一条消息）' "HTTP $($sess.Code) $($sess.Body.message)"
    }
} else {
    Ng '定位 mock 用户' '未能在库里找到 mock 用户，跳过"下一条消息"验证'
}

# ---------------------------------------------------------------- 5.5 编辑"正在生效"的版本（回归）
Write-Host "5.5) 编辑正在生效的版本（界面行为回归：回传自身版本号）" -ForegroundColor Gray
# 背景：编辑器打开生效版本时，表单里的版本号就是被编辑那一版的号，前端会原样回传。
# 后端若把它当成"新版本要叫 v900"，就会撞唯一键报 409，用户看到的现象是"保存后什么都没发生"。
# 这里就用**界面完全一样的请求体**（带 version=v900）走一遍，钉住"同号 = 自动分配下一个"。
$listBeforeEdit = Invoke-Api -Method GET -Url "$adminApi/ai-prompt-templates?sceneCode=$testScene" -Headers $adminHeaders -Tag 'prompt-list-4b'
$activeRow = @($listBeforeEdit.Body.data.records) | Where-Object { $_.active -eq $true } | Select-Object -First 1
$editedActive = Invoke-Api -Method PATCH -Url "$adminApi/ai-prompt-templates/$($activeRow.id)" -Headers $adminHeaders -Body @{
    sceneCode    = $testScene
    templateName = $activeRow.templateName
    version      = $activeRow.version
    promptText   = "$marker 编辑生效版本后的正文：你是控糖助手，用简洁中文回答用户关于血糖的问题，不做诊断、不给用药建议，涉及治疗与调整用药时请引导用户及时就医。"
    variables    = @()
    status       = 1
    changeNote   = 'prompt-e2e：编辑生效版本'
    configVersion = $activeRow.configVersion
} -Tag 'edit-active'
Check '编辑生效版本：回传自身版本号不再 409，且返回追加结果' `
    ($editedActive.Code -eq 200 -and $editedActive.Body.data.appended -eq $true) `
    "HTTP $($editedActive.Code) msg=$($editedActive.Body.message) appended=$($editedActive.Body.data.appended)"
Check '追加出来的版本号是下一个（v901）' ($editedActive.Body.data.version -eq "v$($testVersion + 1)") `
    "version=$($editedActive.Body.data.version)"
Check '追加的新版本直接生效（列表里只有它 active）' `
    (@(@(@(Invoke-Api -Method GET -Url "$adminApi/ai-prompt-templates?sceneCode=$testScene" -Headers $adminHeaders -Tag 'prompt-list-4c').Body.data.records) | Where-Object { $_.active -eq $true }).Count -eq 1) `
    '启用版本不唯一'
$chatAfterEdit = @(@(Invoke-Api -Method GET -Url "$adminApi/ai-prompt-templates?sceneCode=$testScene" -Headers $adminHeaders -Tag 'prompt-list-4d').Body.data.scenes) |
    Where-Object { $_.sceneCode -eq $testScene } | Select-Object -First 1
Check '运行态切到编辑后的新版本' ($chatAfterEdit.activeVersion -eq "chat@v$($testVersion + 1)") `
    "activeVersion=$($chatAfterEdit.activeVersion)"
$oldStillThere = Get-SqlScalar "SELECT CONCAT(COUNT(*), '/', COALESCE(MAX(is_enabled), -1)) FROM sugar_health.ai_prompt_template WHERE scene_code = '$testScene' AND version_no = $testVersion AND is_deleted = 0;"
Check '被编辑的那一版仍在且已停用（可回滚）' ($oldStillThere -eq '1/0') "count/enabled=$oldStillThere"

# ---------------------------------------------------------------- 6. 回滚
Write-Host "6) 回滚到原版本" -ForegroundColor Gray
$rollback = Invoke-Api -Method PATCH -Url "$adminApi/ai-prompt-templates/$beforeActiveId/activate" -Headers $adminHeaders -Tag 'rollback'
Check '回滚（启用原版本）成功' ($rollback.Code -eq 200) "HTTP $($rollback.Code) msg=$($rollback.Body.message)"
$listAfterRollback = Invoke-Api -Method GET -Url "$adminApi/ai-prompt-templates?sceneCode=$testScene" -Headers $adminHeaders -Tag 'prompt-list-4'
$chatAfterRollback = @($listAfterRollback.Body.data.scenes) | Where-Object { $_.sceneCode -eq $testScene } | Select-Object -First 1
Check '运行态回到原版本的标签' ($chatAfterRollback.activeVersion -eq $beforeActiveLabel) `
    "activeVersion=$($chatAfterRollback.activeVersion) expect=$beforeActiveLabel"
Check '回滚后原版本仍在（历史版本没被覆盖）' (@(@($listAfterRollback.Body.data.records) | Where-Object { $_.id -eq $beforeActiveId }).Count -eq 1) '原版本行不见了'
if ($userId) {
    Archive-ActiveSessions ([long]$userId)
    $sess2 = Invoke-Api -Method POST -Url "$appApi/chat-sessions/current" -Headers $appHeaders -Body @{ sessionType = 3 } -Tag 'session-2'
    if ($sess2.Code -eq 200) {
        $ver2 = Get-SqlScalar "SELECT system_prompt_ver FROM sugar_health.chat_session WHERE id = $($sess2.Body.data.id);"
        Check '回滚后新会话回到原版本标签' ($ver2 -eq $beforeActiveLabel) "system_prompt_ver=$ver2 expect=$beforeActiveLabel"
    } else {
        Ng '回滚后新建会话' "HTTP $($sess2.Code) $($sess2.Body.message)"
    }
}

# ---------------------------------------------------------------- 6.5 删除草稿（本轮新增的小功能）
Write-Host "6.5) 删除草稿版本" -ForegroundColor Gray
# ① 生效版本必须删不掉（删了会让该场景静默回落到 classpath 提示词）
$deleteActive = Invoke-Api -Method DELETE -Url "$adminApi/ai-prompt-templates/$beforeActiveId" -Headers $adminHeaders -Tag 'delete-active'
Check '生效版本不允许删除（400）' ($deleteActive.Code -eq 400) "HTTP $($deleteActive.Code) msg=$($deleteActive.Body.message)"
$activeStillEnabled = Get-SqlScalar "SELECT is_enabled FROM sugar_health.ai_prompt_template WHERE id = $beforeActiveId AND is_deleted = 0;"
Check '被拒后生效版本仍然完好' ($activeStillEnabled -eq '1') "is_enabled=$activeStillEnabled"

# ② 新建一个一次性草稿 → 删除 → 列表里消失、库里是逻辑删除
$throwaway = Invoke-Api -Method POST -Url "$adminApi/ai-prompt-templates" -Headers $adminHeaders `
    -Body @{ sceneCode = $testScene; version = "v$($testVersion + 2)"; promptText = $body; variables = @(); status = 0; changeNote = 'prompt-e2e 待删除' } -Tag 'create-throwaway'
Check '一次性草稿创建成功' ($throwaway.Code -eq 200) "HTTP $($throwaway.Code) msg=$($throwaway.Body.message)"
$throwawayId = $throwaway.Body.data.id

$duplicate = Invoke-Api -Method DELETE -Url "$adminApi/ai-prompt-templates/$throwawayId`?configVersion=999" -Headers $adminHeaders -Tag 'delete-stale'
Check '删除时乐观锁过期 → 409' ($duplicate.Code -eq 409) "HTTP $($duplicate.Code) msg=$($duplicate.Body.message)"

$deleted = Invoke-Api -Method DELETE -Url "$adminApi/ai-prompt-templates/$throwawayId" -Headers $adminHeaders -Tag 'delete-draft'
Check '删除草稿成功（data 为 null）' ($deleted.Code -eq 200 -and $null -eq $deleted.Body.data) `
    "HTTP $($deleted.Code) msg=$($deleted.Body.message)"

$softDeleted = Get-SqlScalar "SELECT CONCAT(is_deleted, '/', IF(deleted_at IS NULL, 'null', 'set')) FROM sugar_health.ai_prompt_template WHERE id = $throwawayId;"
Check '库里是逻辑删除（is_deleted=1 且 deleted_at 已写）' ($softDeleted -eq '1/set') "is_deleted/deleted_at=$softDeleted"
$listAfterDelete = Invoke-Api -Method GET -Url "$adminApi/ai-prompt-templates?sceneCode=$testScene" -Headers $adminHeaders -Tag 'prompt-list-4e'
Check '列表里不再出现该草稿' (@(@($listAfterDelete.Body.data.records) | Where-Object { $_.id -eq $throwawayId }).Count -eq 0) '删除后仍出现在列表里'
$recreate = Invoke-Api -Method POST -Url "$adminApi/ai-prompt-templates" -Headers $adminHeaders `
    -Body @{ sceneCode = $testScene; version = "v$($testVersion + 2)"; promptText = $body; variables = @(); status = 0 } -Tag 'recreate-same-version'
Check '删掉之后可以用同一个版本号重建（唯一键含 is_deleted）' ($recreate.Code -eq 200) `
    "HTTP $($recreate.Code) msg=$($recreate.Body.message)"
$recreateId = $recreate.Body.data.id

# ---------------------------------------------------------------- 7. 场景绑定
Write-Host "7) 场景绑定（场景 → 模型 / 场景参数）" -ForegroundColor Gray
$sceneBefore = Invoke-Api -Method GET -Url "$adminApi/ai-scenes" -Headers $adminHeaders -Tag 'ai-scenes-2'
$chatBefore = @($sceneBefore.Body.data.records) | Where-Object { $_.sceneCode -eq $testScene } | Select-Object -First 1
$savedTemp = Invoke-Api -Method PUT -Url "$adminApi/ai-scenes/$testScene" -Headers $adminHeaders `
    -Body @{ temperature = 0.9; maxTokens = $null; thinking = $null; status = 1; remark = 'prompt-e2e 临时改温度'; clear = $false; configVersion = $chatBefore.configVersion } -Tag 'scene-save'
Check '保存场景参数成功' ($savedTemp.Code -eq 200 -and $savedTemp.Body.data.temperature -eq 0.9) `
    "HTTP $($savedTemp.Code) temperature=$($savedTemp.Body.data.temperature) msg=$($savedTemp.Body.message)"

$conflictScene = Invoke-Api -Method PUT -Url "$adminApi/ai-scenes/$testScene" -Headers $adminHeaders `
    -Body @{ temperature = 0.5; status = 1; clear = $false; configVersion = $chatBefore.configVersion } -Tag 'scene-conflict'
Check '场景配置乐观锁过期 → 409' ($conflictScene.Code -eq 409) "HTTP $($conflictScene.Code) msg=$($conflictScene.Body.message)"

$restored = Invoke-Api -Method PUT -Url "$adminApi/ai-scenes/$testScene" -Headers $adminHeaders `
    -Body @{ clear = $true; status = $chatBefore.status; remark = $chatBefore.remark; configVersion = $savedTemp.Body.data.configVersion } -Tag 'scene-clear'
Check '清空覆盖（改回继承）成功' ($restored.Code -eq 200 -and $null -eq $restored.Body.data.temperature -and $null -eq $restored.Body.data.modelCode) `
    "HTTP $($restored.Code) temperature=$($restored.Body.data.temperature) modelCode=$($restored.Body.data.modelCode)"

# ---------------------------------------------------------------- 8. 清理
Write-Host "8) 清理 E2E 产生的数据" -ForegroundColor Gray
# 5.5 步会由后端自动追加出 v901；6.5 步会留下 v902（一条已逻辑删除 + 一条重建的草稿）与 v900/v901
Invoke-E2eSql "DELETE FROM sugar_health.ai_prompt_template WHERE scene_code = '$testScene' AND version_no IN ($testVersion, $($testVersion + 1), $($testVersion + 2));" | Out-Null
$left = Get-SqlScalar "SELECT COUNT(*) FROM sugar_health.ai_prompt_template WHERE scene_code = '$testScene' AND version_no IN ($testVersion, $($testVersion + 1), $($testVersion + 2));"
Check 'E2E 提示词版本已清理' ($left -eq '0') "left=$left"

Write-Host ""
Write-Host "== 结果：PASS=$script:pass FAIL=$script:fail ==" -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
exit $script:fail
