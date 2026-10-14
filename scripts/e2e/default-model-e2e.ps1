#requires -Version 5.1
<#
  验证 2026-10-13 反馈的修复：停用 flash、启用新建的 pro 之后，对话到底用谁。
  现场状态（用户库里真实存在的）：
    id=2 deepseek-v4-flash  is_enabled=0 is_default=1（停用却仍留着默认标记——正是误导人的那种状态）
    id=3 qwen3-vl-plus      is_enabled=1（视觉行，不该抢文本默认）
    id=4 deepseek-v4-pro    is_enabled=1 is_default=0（新启用的行）
  修复前：通道默认写成 resolveText(yamlText().modelCode()) → 解析回 yaml 的 flash。
  修复后：文本默认 = 默认标记 → 唯一启用 → 最近修改 → yaml，落库 model_code 与之一致。
#>
param([string]$Base = "http://127.0.0.1:8080", [string]$UserCode = "mock-glucose-demo")

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\_common.ps1"
if (-not $PSBoundParameters.ContainsKey('Base')) { $Base = Get-E2eBaseUrl }
$outDir = "$PSScriptRoot\..\..\target\e2e"
$admin = "$Base/admin/v1"
$api = "$Base/api/v1"
$mysqlExe = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
$myCnf = Resolve-MysqlCnf
$log = (Get-E2eLogPath)
$script:pass = 0
$script:fail = 0

function Save-Json([string]$Path, $Object) {
    [System.IO.File]::WriteAllText($Path, ($Object | ConvertTo-Json -Compress -Depth 8), (New-Object System.Text.UTF8Encoding($false)))
}
function Sql([string]$Query) { return ((& $mysqlExe "--defaults-extra-file=$myCnf" -N -B -e $Query 2>&1) -join "`n") }
function Check([string]$Name, [bool]$Ok, [string]$Detail = "") {
    if ($Ok) { $script:pass++ ; Write-Host ("  [PASS] {0} {1}" -f $Name, $Detail) -ForegroundColor Green }
    else { $script:fail++ ; Write-Host ("  [FAIL] {0} {1}" -f $Name, $Detail) -ForegroundColor Red }
}
function Read-Sse([string]$Path) {
    $raw = [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
    $sb = [System.Text.StringBuilder]::new(); $done = $null; $err = $null
    foreach ($line in ($raw -split "\r?\n")) {
        if (-not $line.StartsWith("data:")) { continue }
        $p = $line.Substring(5).Trim(); if (-not $p) { continue }
        try { $o = $p | ConvertFrom-Json } catch { continue }
        if ($null -ne $o.delta) { [void]$sb.Append($o.delta) }
        elseif ($null -ne $o.assistantMsgId) { $done = $o }
        elseif ($null -ne $o.code -and $o.code -ne 0) { $err = $o }
    }
    return [pscustomobject]@{ Streamed = $sb.ToString(); Done = $done; Error = $err }
}

Save-Json "$outDir\dml-admin-login.json" @{ username = "admin"; password = (Get-E2eAdminPassword) }
$lr = & curl.exe -s -X POST "$admin/auth/sessions" -H "Content-Type: application/json" --data-binary "@$outDir\dml-admin-login.json"
$login = $lr | ConvertFrom-Json
if ($login.code -ne 0) { throw "后台登录失败: $lr" }
$ah = @("-H", "Authorization: Bearer $($login.data.accessToken)", "-H", "Content-Type: application/json")

function Get-List() { return (& curl.exe -s "$admin/ai/models" @ah) | ConvertFrom-Json }
function Find-Row($list, [string]$code) { return @($list.data.records | Where-Object { $_.modelCode -eq $code })[0] }
function Set-Enabled([string]$id, [int]$enabled) {
    Save-Json "$outDir\dml-en.json" @{ enabled = $enabled }
    return (& curl.exe -s -X POST "$admin/ai/models/$id/enabled" @ah --data-binary "@$outDir\dml-en.json") | ConvertFrom-Json
}
function Set-Default([string]$id) { return (& curl.exe -s -X POST "$admin/ai/models/$id/default" @ah) | ConvertFrom-Json }

function Chat-Round([string]$tag) {
    Save-Json "$outDir\dml-login.json" @{ code = $UserCode }
    $ul = (& curl.exe -s -X POST "$api/auth/wechat/sessions" -H "Content-Type: application/json" --data-binary "@$outDir\dml-login.json") | ConvertFrom-Json
    if ($ul.code -ne 0) { throw "C 端登录失败" }
    $uh = @("-H", "Authorization: Bearer $($ul.data.accessToken)", "-H", "Content-Type: application/json")
    Save-Json "$outDir\dml-sess.json" @{ sessionType = 3 }
    $ss = (& curl.exe -s -X POST "$api/chat-sessions/current" @uh --data-binary "@$outDir\dml-sess.json") | ConvertFrom-Json
    $sid = $ss.data.id
    Save-Json "$outDir\dml-msg.json" @{ clientMsgId = [guid]::NewGuid().ToString(); msgType = 1; contentText = "我今天的血糖怎么样？" }
    $mg = (& curl.exe -s -X POST "$api/chat-sessions/$sid/messages" @uh --data-binary "@$outDir\dml-msg.json") | ConvertFrom-Json
    Save-Json "$outDir\dml-comp.json" @{ clientMsgId = [guid]::NewGuid().ToString(); replyToMsgId = $mg.data.id; stream = $true }
    & curl.exe -sN -X POST "$api/chat-sessions/$sid/completions" @uh --data-binary "@$outDir\dml-comp.json" -o "$outDir\dml-$tag.sse"
    $res = Read-Sse "$outDir\dml-$tag.sse"
    $assistantId = if ($res.Done) { $res.Done.assistantMsgId } else { $null }
    $recorded = if ($assistantId) { (Sql "SELECT model_code FROM sugar_health.chat_message WHERE id=$assistantId;").Trim() } else { "<无回答行>" }
    return [pscustomobject]@{ Ok = [bool]$res.Done; Len = $res.Streamed.Length; Recorded = $recorded
        SessionId = $sid; UserMsgId = $mg.data.id; AssistantMsgId = $assistantId }
}
function Log-Tail([int]$before) {
    $lines = Get-Content -LiteralPath $log -Encoding UTF8 -ReadCount 0
    if ($before -ge $lines.Count) { return "" }
    return (@($lines[$before..($lines.Count - 1)]) -join "`n")
}

Write-Host "=== 现场状态 ===" -ForegroundColor Cyan
$list = Get-List
foreach ($r in ($list.data.records | Sort-Object { [int]$_.id })) {
    Write-Host ("  id={0} {1,-20} provider={2} enabled={3} default={4} 生效角色={5}" -f `
        $r.id, $r.modelCode, $r.providerCode, $r.isEnabled, $r.isDefault, $(if ($r.effectiveRole) { $r.effectiveRole } else { '—' }))
}

Write-Host ""
Write-Host "A) 列表要如实说明『现在真正在用谁』" -ForegroundColor Cyan
Check "文本默认=deepseek-v4-pro" ($list.data.textDefaultCode -eq "deepseek-v4-pro") "code=$($list.data.textDefaultCode) source=$($list.data.textDefaultSource)"
Check "判据=唯一启用的文本模型" ($list.data.textDefaultReason -eq "唯一启用的文本模型") "reason=$($list.data.textDefaultReason)"
$flash = Find-Row $list "deepseek-v4-flash"
$pro = Find-Row $list "deepseek-v4-pro"
$vision = Find-Row $list "qwen3-vl-plus"
Check "pro 标记为生效中(文本)" ($pro.effectiveRole -eq "text-default") "role=$($pro.effectiveRole)"
Check "视觉行标记为视觉" ($vision.effectiveRole -eq "vision") "role=$($vision.effectiveRole)"
Check "停用的 flash 不再声称生效" ($null -eq $flash.effectiveRole) "role=$($flash.effectiveRole)"
Check "启动日志写明文本默认与判据" ((Get-Content -LiteralPath $log -Raw -Encoding UTF8) -match "文本默认=deepseek-v4-pro\[db\]（判据：唯一启用的文本模型）") "见 e2e-app.log"

Write-Host ""
Write-Host "B) 真实对话一轮：必须走 pro，且落库 model_code 与之一致" -ForegroundColor Cyan
$before = (Get-Content -LiteralPath $log -Encoding UTF8 -ReadCount 0).Count
$round1 = Chat-Round "pro"
$tail1 = Log-Tail $before
Check "对话成功" ($round1.Ok) "len=$($round1.Len) assistantMsgId=$($round1.AssistantMsgId)"
Check "运行时按 pro 构造模型" ($tail1 -match "构造模型：modelCode=deepseek-v4-pro") "见日志"
Check "回答落库 model_code=deepseek-v4-pro" ($round1.Recorded -eq "deepseek-v4-pro") "recorded=$($round1.Recorded)"
Check "落库不再是 flash（旧 bug 的观测点）" ($round1.Recorded -ne "deepseek-v4-flash") "recorded=$($round1.Recorded)"

Write-Host ""
Write-Host "C) 显式设为默认：判据变成默认标记，调用不变" -ForegroundColor Cyan
$sd = Set-Default $pro.id
Check "设为默认成功" ($sd.code -eq 0) "code=$($sd.code) msg=$($sd.message)"
$list2 = Get-List
Check "判据=默认标记" ($list2.data.textDefaultReason -eq "默认标记") "reason=$($list2.data.textDefaultReason)"
Check "文本默认仍是 pro" ($list2.data.textDefaultCode -eq "deepseek-v4-pro") "code=$($list2.data.textDefaultCode)"
$flash2 = Find-Row $list2 "deepseek-v4-flash"
Check "flash 的陈旧默认标记已被清掉" ($flash2.isDefault -eq 0) "flashDefault=$($flash2.isDefault)"
$round2 = Chat-Round "prodefault"
Check "默认标记路径下对话成功" ($round2.Ok) "len=$($round2.Len)"
Check "默认标记路径下落库仍是 pro" ($round2.Recorded -eq "deepseek-v4-pro") "recorded=$($round2.Recorded)"

Write-Host ""
Write-Host "D) 反向验证：把 pro 也停用 → 必须落回 yaml，而不是继续用 pro" -ForegroundColor Cyan
Set-Enabled $pro.id 0 | Out-Null
$list3 = Get-List
Check "无文本候选时回落 yaml" (($list3.data.textDefaultSource -eq "yaml") -and ($list3.data.textDefaultReason -eq "yaml 兜底")) "source=$($list3.data.textDefaultSource) reason=$($list3.data.textDefaultReason)"
Check "回落目标是 yaml 的 flash" ($list3.data.textDefaultCode -eq "deepseek-v4-flash") "code=$($list3.data.textDefaultCode)"
$pro3 = Find-Row $list3 "deepseek-v4-pro"
Check "停用 pro 时摘掉它的默认标记" ($pro3.isDefault -eq 0) "proDefault=$($pro3.isDefault)"

Write-Host ""
Write-Host "E) 恢复现场：重新启用 pro 并设为默认（用户想要的终态）" -ForegroundColor Cyan
Set-Enabled $pro.id 1 | Out-Null
Set-Default $pro.id | Out-Null
$list4 = Get-List
$pro4 = Find-Row $list4 "deepseek-v4-pro"
$flash4 = Find-Row $list4 "deepseek-v4-flash"
Check "pro 生效中且是默认" (($pro4.effectiveRole -eq "text-default") -and ($pro4.isDefault -eq 1)) "role=$($pro4.effectiveRole) default=$($pro4.isDefault)"
Check "flash 保持停用且无默认标记" (($flash4.isEnabled -eq 0) -and ($flash4.isDefault -eq 0)) "enabled=$($flash4.isEnabled) default=$($flash4.isDefault)"
Check "视觉行不动" ($vision.isEnabled -eq 1) "visionEnabled=$($vision.isEnabled)"

Write-Host ""
Write-Host "---- 结果 ----" -ForegroundColor Cyan
Write-Host ("PASS={0} FAIL={1}" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { "Green" } else { "Red" })
