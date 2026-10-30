#requires -Version 5.1
<#
  验证 C2/C3 的路由语义：DB 优先、yaml 只兜底；场景绑定的能力校验；运行时诊断接口。

  守的四个点：
    1. GET /admin/v1/ai/runtime/status 能如实报出「每个场景实际用哪个模型、来源是哪条链」；
    2. 视觉场景绑文本模型 → 保存时 400（不再只是 WARN 后静默回落）；
    3. 文本场景绑纯视觉模型 → 同样 400；
    4. 合法绑定生效后，status 的 source 从 default 变成 binding，清空后又变回 default。

  不构造真实模型行（需要真密钥），只借现场已有的 deepseek-v4-pro / qwen3-vl-plus。
#>
param([string]$Base = "http://127.0.0.1:8080")

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\_common.ps1"
if (-not $PSBoundParameters.ContainsKey('Base')) { $Base = Get-E2eBaseUrl }
$outDir = "$PSScriptRoot\..\..\target\e2e"
$admin = "$Base/admin/v1"
$script:pass = 0
$script:fail = 0

function Save-Json([string]$Path, $Object) {
    [System.IO.File]::WriteAllText($Path, ($Object | ConvertTo-Json -Compress -Depth 8), (New-Object System.Text.UTF8Encoding($false)))
}
function Check([string]$Name, [bool]$Ok, [string]$Detail = "") {
    if ($Ok) { $script:pass++ ; Write-Host ("  [PASS] {0} {1}" -f $Name, $Detail) -ForegroundColor Green }
    else { $script:fail++ ; Write-Host ("  [FAIL] {0} {1}" -f $Name, $Detail) -ForegroundColor Red }
}

Save-Json "$outDir\routing-admin-login.json" @{ username = "admin"; password = (Get-E2eAdminPassword) }
$lr = & curl.exe -s -X POST "$admin/auth/sessions" -H "Content-Type: application/json" --data-binary "@$outDir\routing-admin-login.json"
$login = $lr | ConvertFrom-Json
if ($login.code -ne 0) { throw "后台登录失败: $lr" }
$ah = @("-H", "Authorization: Bearer $($login.data.accessToken)", "-H", "Content-Type: application/json")

function Get-Status() { return (& curl.exe -s "$admin/ai/runtime/status" @ah) | ConvertFrom-Json }
function Get-Scenes() { return (& curl.exe -s "$admin/ai-scenes" @ah) | ConvertFrom-Json }
function Find-Scene($list, [string]$code) {
    return @($list.data.records | Where-Object { $_.sceneCode -eq $code })[0]
}
function Scene-Status($status, [string]$code) {
    return @($status.data.scenes | Where-Object { $_.sceneCode -eq $code })[0]
}
function Put-Scene([string]$sceneCode, $Body, [string]$Tag) {
    Save-Json "$outDir\routing-$Tag.json" $Body
    $raw = & curl.exe -s -X PUT "$admin/ai-scenes/$sceneCode" @ah --data-binary "@$outDir\routing-$Tag.json"
    return $raw | ConvertFrom-Json
}

Write-Host ""
Write-Host "A) 运行时诊断接口：7 个场景各有归属" -ForegroundColor Cyan
$st = Get-Status
Check "状态接口返回成功" ($st.code -eq 0) "code=$($st.code)"
Check "覆盖全部 7 个场景" (@($st.data.scenes).Count -eq 7) "count=$(@($st.data.scenes).Count)"
$chat = Scene-Status $st "CHAT"
Check "CHAT 走后台默认（不是 yaml 兜底）" ($chat.source -eq "default") "source=$($chat.source) model=$($chat.modelCode)"
Check "文本默认来自 DB" ($st.data.textDefault.source -eq "db") "source=$($st.data.textDefault.source) model=$($st.data.textDefault.modelCode)"
$image = Scene-Status $st "CHAT_IMAGE"
Check "视觉场景没有绑定时报 bindingUsable=null" ($null -eq $image.bindingUsable) "bindingUsable=$($image.bindingUsable)"
Check "视觉场景用的是视觉模型" ($image.requiresVision -and ($image.modelCode -eq $st.data.visionDefault.modelCode)) "model=$($image.modelCode)"

Write-Host ""
Write-Host "B) 场景列表要把能力带给前端（下拉据此过滤）" -ForegroundColor Cyan
$scenes = Get-Scenes
Check "场景列表返回成功" ($scenes.code -eq 0) "code=$($scenes.code)"
$firstModel = @($scenes.data.models)[0]
Check "模型选项带 capability" ($null -ne $firstModel.capability -and $firstModel.capability.Length -gt 0) "capability=$($firstModel.capability)"
Check "模型选项带 capabilityText" ($null -ne $firstModel.capabilityText) "text=$($firstModel.capabilityText)"
$imageRow = Find-Scene $scenes "CHAT_IMAGE"
Check "视觉场景 modelCodeApplies=true（批次2 起视觉也认绑定）" ($imageRow.modelCodeApplies -eq $true) "applies=$($imageRow.modelCodeApplies)"

Write-Host ""
Write-Host "C) 视觉场景绑文本模型 → 400，且不落库" -ForegroundColor Cyan
$textCode = $st.data.textDefault.modelCode
$bad = Put-Scene "chat-image" @{ modelCode = $textCode; status = 1; configVersion = $imageRow.configVersion } "bad-image"
Check "保存被拒绝（非 0）" ($bad.code -ne 0) "code=$($bad.code) msg=$($bad.message)"
Check "错误信息点明只能绑能力含 vision" ($bad.message -match "vision") "msg=$($bad.message)"
$stAfter = Get-Status
$imageAfter = Scene-Status $stAfter "CHAT_IMAGE"
Check "被拒绝后绑定仍未生效" ($null -eq $imageAfter.boundModelCode) "bound=$($imageAfter.boundModelCode)"

Write-Host ""
Write-Host "D) 文本场景绑纯视觉模型 → 400" -ForegroundColor Cyan
$chatRow = Find-Scene $scenes "CHAT"
$visionCode = $st.data.visionDefault.modelCode
$badText = Put-Scene "CHAT" @{ modelCode = $visionCode; status = 1; configVersion = $chatRow.configVersion } "bad-text"
Check "保存被拒绝（非 0）" ($badText.code -ne 0) "code=$($badText.code) msg=$($badText.message)"
Check "错误信息点明只能绑能力含 text" ($badText.message -match "text") "msg=$($badText.message)"

Write-Host ""
Write-Host "E) 合法绑定生效 → source 变 binding；清空 → 回到 default" -ForegroundColor Cyan
$ok = Put-Scene "chat-image" @{ modelCode = $visionCode; status = 1; configVersion = $imageRow.configVersion } "ok-image"
Check "视觉模型绑定成功" ($ok.code -eq 0) "code=$($ok.code) msg=$($ok.message)"
$stBound = Get-Status
$imageBound = Scene-Status $stBound "CHAT_IMAGE"
Check "来源变成 binding" ($imageBound.source -eq "binding") "source=$($imageBound.source)"
Check "实际模型就是绑定的那个" ($imageBound.modelCode -eq $visionCode) "model=$($imageBound.modelCode)"
Check "bindingUsable=true" ($imageBound.bindingUsable -eq $true) "bindingUsable=$($imageBound.bindingUsable)"
$imageRow2 = Find-Scene (Get-Scenes) "CHAT_IMAGE"
$cleared = Put-Scene "chat-image" @{ clear = $true; status = 1; configVersion = $imageRow2.configVersion } "clear-image"
Check "清空绑定成功" ($cleared.code -eq 0) "code=$($cleared.code) msg=$($cleared.message)"
$stBack = Get-Status
$imageBack = Scene-Status $stBack "CHAT_IMAGE"
Check "清空后来源回到 default" ($imageBack.source -eq "default") "source=$($imageBack.source)"
Check "清空后没有残留绑定编码" ($null -eq $imageBack.boundModelCode) "bound=$($imageBack.boundModelCode)"

Write-Host ""
Write-Host "---- 结果 ----" -ForegroundColor Cyan
Write-Host ("PASS={0} FAIL={1}" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { "Green" } else { "Red" })
