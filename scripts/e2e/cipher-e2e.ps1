#requires -Version 5.1
<#
  验证后台配置的 API Key 加密落库链路（P1）：
  A. 加密落库：PUT 真实 key → api_key_cipher 为密文（非明文）
  B. 解密可用：/test 用库里解出来的 key 打真实上游 → ok
  C. 确实在用库里的 key：换成无效 key → /test 报 AUTH（而不是偷偷回落到 yaml）
  D. 运行时链路：无效 key 生效期间，C 端对话请求应当失败（证明走的是 DB 配置）
  E. 清空回落：clearApiKey=true → cipher 为 NULL，apiKeySource=yaml
  F. 回落可用：C 端对话重新成功（yaml key）
  注意：运行时快照 60s TTL，PUT 之后需要等待刷新。
#>
param(
    [string]$Base = "http://127.0.0.1:8080",
    [string]$RealKey = "",
    [string]$BadKey = "sk-00000000000000000000000000000000",
    [int]$TtlWaitSeconds = 65,
    [string]$UserCode = "mock-glucose-demo"
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\_common.ps1"
if (-not $PSBoundParameters.ContainsKey('Base')) { $Base = Get-E2eBaseUrl }
# 真实密钥不写进仓库：优先用参数，未传则读 E2E_DEEPSEEK_KEY（必须放在 dot-source 之后，函数才可用）
if (-not $RealKey) { $RealKey = Assert-E2eDeepSeekKey }
$outDir = "$PSScriptRoot\..\..\target\e2e"
$admin = "$Base/admin/v1"
$api = "$Base/api/v1"
$mysqlExe = Get-MysqlExe
$myCnf = Resolve-MysqlCnf
$log = (Get-E2eLogPath)
$script:pass = 0
$script:fail = 0

function Save-Json([string]$Path, $Object) {
    [System.IO.File]::WriteAllText($Path, ($Object | ConvertTo-Json -Compress -Depth 8), (New-Object System.Text.UTF8Encoding($false)))
}
function Sql([string]$Query) {
    return (& $mysqlExe "--defaults-extra-file=$myCnf" -N -B -e $Query 2>&1) -join "`n"
}
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
    return [pscustomobject]@{ Streamed = $sb.ToString(); Done = $done; Error = $err; Raw = $raw }
}

# ---------- 后台登录 ----------
Save-Json "$outDir\cipher-admin-login.json" @{ username = "admin"; password = (Get-E2eAdminPassword) }
$lr = & curl.exe -s -X POST "$admin/auth/sessions" -H "Content-Type: application/json" --data-binary "@$outDir\cipher-admin-login.json"
$login = $lr | ConvertFrom-Json
if ($login.code -ne 0) { throw "后台登录失败: $lr" }
$at = $login.data.accessToken
$ah = @("-H", "Authorization: Bearer $at", "-H", "Content-Type: application/json")
Write-Host "0) 后台登录 OK" -ForegroundColor Cyan

function Get-Rows() {
    $r = (& curl.exe -s "$admin/ai/models" @ah) | ConvertFrom-Json
    if ($r.code -ne 0) { throw "列表失败: $($r | ConvertTo-Json -Compress)" }
    return $r.data.records
}
function Find-Row($rows, [string]$code) {
    return @($rows | Where-Object { $_.modelCode -eq $code })[0]
}
function Put-Row($row, $extra) {
    $body = [ordered]@{
        modelName     = $row.modelName
        providerCode  = $row.providerCode
        apiBaseUrl    = $row.apiBaseUrl
        modelEndpoint = $row.modelEndpoint
        temperature   = $row.temperature
        topP          = $row.topP
        maxTokens     = $row.maxTokens
        timeoutMs     = $row.timeoutMs
        isEnabled     = $row.isEnabled
        isDefault     = $row.isDefault
        configVersion = $row.configVersion
    }
    foreach ($k in $extra.Keys) { $body[$k] = $extra[$k] }
    Save-Json "$outDir\cipher-put.json" $body
    return (& curl.exe -s -X PUT "$admin/ai/models/$($row.id)" @ah --data-binary "@$outDir\cipher-put.json") | ConvertFrom-Json
}
function Do-Test([string]$id) {
    return (& curl.exe -s -X POST "$admin/ai/models/$id/test" @ah) | ConvertFrom-Json
}

# 目标行 = 当前真正生效的文本模型。
# 写死 model_code 会在"停用旧的、启用新的"之后测错对象（D 步还会因为运行时用的是别人而误判），
# 所以这里从接口的 effectiveRole 取——它回答的正是"现在真正在用的是谁"。
$rows = Get-Rows
$row = @($rows | Where-Object { $_.effectiveRole -eq 'text-default' })[0]
if (-not $row) { throw "列表里没有 effectiveRole=text-default 的行：请先确认后台有启用并生效的文本模型" }
$code = $row.modelCode
Write-Host ("   目标行 id={0} code={1} endpoint={2} 当前 keySource={3} mask={4} version={5}" -f $row.id, $row.modelCode, $row.modelEndpoint, $row.apiKeySource, $row.apiKeyMasked, $row.configVersion) -ForegroundColor Gray

# 记录原始状态，最后还原
$origCipher = Sql "SELECT COALESCE(api_key_cipher,'<NULL>') FROM sugar_health.ai_model_config WHERE id=$($row.id);"
$origVersion = $row.configVersion
$origEnabled = $row.isEnabled

Write-Host "`nA) 加密落库" -ForegroundColor Cyan
$resp = Put-Row $row @{ apiKey = $RealKey }
Check "PUT 保存真实 key 成功" ($resp.code -eq 0) "code=$($resp.code) msg=$($resp.message)"
$dbRow = Sql "SELECT api_key_cipher, cipher_algo, key_version, config_version FROM sugar_health.ai_model_config WHERE id=$($row.id);"
Write-Host "   DB: $dbRow" -ForegroundColor Gray
$parts = $dbRow -split "`t"
$cipher = $parts[0]
Check "cipher 非空" ([bool]$cipher)
Check "cipher 不是明文（不含 sk-）" (-not ($cipher -like "sk-*"))
Check "cipher 长度 > 40（iv+ct+tag 的 base64）" ($cipher.Length -gt 40) "len=$($cipher.Length)"
Check "cipher 可 base64 解码" ($(try { [void][Convert]::FromBase64String($cipher); $true } catch { $false }))
Check "cipher_algo=AES-GCM" ($parts[1] -eq "AES-GCM") "algo=$($parts[1])"
Check "key_version=1" ($parts[2] -eq "1") "kv=$($parts[2])"

Write-Host "`nB) 解密可用（/test 用库内解出的 key 打真实上游）" -ForegroundColor Cyan
$t1 = Do-Test $row.id
Check "test ok" ($t1.data.ok -eq $true) "category=$($t1.data.category) echo=$($t1.data.modelEcho) elapsed=$($t1.data.elapsedMs)ms msg=$($t1.data.message)"
$row2 = Find-Row (Get-Rows) $code
Check "GET apiKeySource=db" ($row2.apiKeySource -eq "db") "source=$($row2.apiKeySource)"
Check "GET 只回掩码" ($row2.apiKeyMasked -like "sk-*****") "mask=$($row2.apiKeyMasked)"
Check "掩码不同于明文" ($row2.apiKeyMasked -ne $RealKey) "mask=$($row2.apiKeyMasked)"
$resp2 = Put-Row $row2 @{ apiKey = "" }
Check "apiKey 留空 = 不修改（密文仍在）" ($resp2.code -eq 0) "code=$($resp2.code)"
$cipherAfter = (Sql "SELECT COALESCE(api_key_cipher,'<NULL>') FROM sugar_health.ai_model_config WHERE id=$($row.id);").Trim()
Check "留空后密文未变" ($cipherAfter -eq $cipher) "same=$($cipherAfter -eq $cipher)"

Write-Host "`nC) 确认用的是库里的 key（换无效 key → 必须 AUTH，而不是回落 yaml）" -ForegroundColor Cyan
$row3 = Find-Row (Get-Rows) $code
$resp3 = Put-Row $row3 @{ apiKey = $BadKey }
Check "PUT 无效 key 成功" ($resp3.code -eq 0) "code=$($resp3.code)"
$t2 = Do-Test $row3.id
Check "test 失败且分类为 AUTH/RATE_LIMIT" (($t2.data.ok -eq $false) -and ($t2.data.category -in @("AUTH", "MODEL_NOT_FOUND", "RATE_LIMIT"))) "ok=$($t2.data.ok) category=$($t2.data.category) msg=$($t2.data.message)"
Write-Host ("   advice: " + $t2.data.advice) -ForegroundColor Gray

Write-Host "`nD) 运行时链路：等 ${TtlWaitSeconds}s 让快照刷新，再用无效 key 发一轮对话" -ForegroundColor Cyan
Start-Sleep -Seconds $TtlWaitSeconds
Save-Json "$outDir\cipher-login.json" @{ code = $UserCode }
$ul = (& curl.exe -s -X POST "$api/auth/wechat/sessions" -H "Content-Type: application/json" --data-binary "@$outDir\cipher-login.json") | ConvertFrom-Json
if ($ul.code -ne 0) { throw "C 端登录失败" }
$uh = @("-H", "Authorization: Bearer $($ul.data.accessToken)", "-H", "Content-Type: application/json")
Save-Json "$outDir\cipher-sess.json" @{ sessionType = 3 }
$ss = (& curl.exe -s -X POST "$api/chat-sessions/current" @uh --data-binary "@$outDir\cipher-sess.json") | ConvertFrom-Json
$sid = $ss.data.id
Save-Json "$outDir\cipher-msg.json" @{ clientMsgId = [guid]::NewGuid().ToString(); msgType = 1; contentText = "我今天的血糖怎么样？" }
$mg = (& curl.exe -s -X POST "$api/chat-sessions/$sid/messages" @uh --data-binary "@$outDir\cipher-msg.json") | ConvertFrom-Json
Save-Json "$outDir\cipher-comp.json" @{ clientMsgId = [guid]::NewGuid().ToString(); replyToMsgId = $mg.data.id; stream = $true }
& curl.exe -sN -X POST "$api/chat-sessions/$sid/completions" @uh --data-binary "@$outDir\cipher-comp.json" -o "$outDir\cipher-badkey.sse"
$r1 = Read-Sse "$outDir\cipher-badkey.sse"
$logTail1 = ((Get-Content -LiteralPath $log -Encoding UTF8 -ReadCount 0) | Select-Object -Last 80) -join "`n"
$authErr = ($logTail1 -match "Authentication Fails|invalid_api_key|401|AUTH")
Check "无效 key 下对话失败（说明运行时用的是 DB key）" ([bool]$r1.Error -or (-not $r1.Done -and $r1.Streamed.Length -eq 0)) "err=$(if($r1.Error){$r1.Error.message}else{'<none>；日志含鉴权失败=' + $authErr})"

Write-Host "`nE) 清空密钥 → 回落 yaml" -ForegroundColor Cyan
$row4 = Find-Row (Get-Rows) $code
$resp4 = Put-Row $row4 @{ clearApiKey = $true }
Check "PUT clearApiKey 成功" ($resp4.code -eq 0) "code=$($resp4.code) msg=$($resp4.message)"
$cipherCleared = (Sql "SELECT COALESCE(api_key_cipher,'<NULL>') FROM sugar_health.ai_model_config WHERE id=$($row.id);").Trim()
Check "密文已清空" ($cipherCleared -eq "<NULL>") "now=$cipherCleared"
$row5 = Find-Row (Get-Rows) $code
Check "GET apiKeySource=yaml" ($row5.apiKeySource -eq "yaml") "source=$($row5.apiKeySource)"

Write-Host "`nF) 回落可用：再等 ${TtlWaitSeconds}s，对话应当重新成功" -ForegroundColor Cyan
Start-Sleep -Seconds $TtlWaitSeconds
Save-Json "$outDir\cipher-msg2.json" @{ clientMsgId = [guid]::NewGuid().ToString(); msgType = 1; contentText = "再说一次，我今天血糖怎么样？" }
$mg2 = (& curl.exe -s -X POST "$api/chat-sessions/$sid/messages" @uh --data-binary "@$outDir\cipher-msg2.json") | ConvertFrom-Json
Save-Json "$outDir\cipher-comp2.json" @{ clientMsgId = [guid]::NewGuid().ToString(); replyToMsgId = $mg2.data.id; stream = $true }
& curl.exe -sN -X POST "$api/chat-sessions/$sid/completions" @uh --data-binary "@$outDir\cipher-comp2.json" -o "$outDir\cipher-yamlkey.sse"
$r2 = Read-Sse "$outDir\cipher-yamlkey.sse"
Check "回落 yaml key 后对话成功" ([bool]$r2.Done -or $r2.Streamed.Length -gt 0) "len=$($r2.Streamed.Length) err=$(if($r2.Error){$r2.Error.message}else{'<none>'})"
[System.IO.File]::WriteAllText("$outDir\cipher-yamlkey.answer.txt", $(if ($r2.Done) { [string]$r2.Done.contentText } else { $r2.Streamed }), (New-Object System.Text.UTF8Encoding($false)))

Write-Host "`n---- 收尾 ----" -ForegroundColor Cyan
$finalCipher = (Sql "SELECT COALESCE(api_key_cipher,'<NULL>') FROM sugar_health.ai_model_config WHERE id=$($row.id);").Trim()
$finalRow = Find-Row (Get-Rows) $code
Write-Host ("最终状态：id={0} cipher={1} apiKeySource={2} version={3}（原 version={4}，原始 cipher 长度={5}）" -f $row.id, $finalCipher, $finalRow.apiKeySource, $finalRow.configVersion, $origVersion, $origCipher.Length) -ForegroundColor Gray
Write-Host ("`n结果：PASS={0} FAIL={1}" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { "Green" } else { "Red" })
