#requires -Version 5.1
<# 窗口生效验证：向一个历史超过 20 条的会话发一轮真实请求，然后看日志里注入了几条。 #>
param([string]$Base = "http://127.0.0.1:8080", [long]$SessionId = 55, [string]$Code = "mock-glucose-demo")

$ErrorActionPreference = "Stop"
$api = "$Base/api/v1"
. "$PSScriptRoot\_common.ps1"
if (-not $PSBoundParameters.ContainsKey('Base')) { $Base = Get-E2eBaseUrl }
$outDir = "$PSScriptRoot\..\..\target\e2e"
function Save-Json([string]$Path, $Object) {
    [System.IO.File]::WriteAllText($Path, ($Object | ConvertTo-Json -Compress -Depth 6), (New-Object System.Text.UTF8Encoding($false)))
}

Save-Json "$outDir\login.json" @{ code = $Code }
$login = (& curl.exe -s -X POST "$api/auth/wechat/sessions" -H "Content-Type: application/json" --data-binary "@$outDir\login.json") | ConvertFrom-Json
if ($login.code -ne 0) { throw "登录失败" }
$auth = @("-H", "Authorization: Bearer $($login.data.accessToken)", "-H", "Content-Type: application/json")

Save-Json "$outDir\w.msg.json" @{ clientMsgId = [guid]::NewGuid().ToString(); msgType = 1; contentText = "上面那些记录我都看到了，总结一下就行" }
$msg = (& curl.exe -s -X POST "$api/chat-sessions/$SessionId/messages" @auth --data-binary "@$outDir\w.msg.json") | ConvertFrom-Json
if ($msg.code -ne 0) { throw "发消息失败: $($msg | ConvertTo-Json -Compress)" }
Write-Host "已落用户消息 id=$($msg.data.id)"

Save-Json "$outDir\w.comp.json" @{ clientMsgId = [guid]::NewGuid().ToString(); replyToMsgId = $msg.data.id; stream = $true }
& curl.exe -sN -X POST "$api/chat-sessions/$SessionId/completions" @auth --data-binary "@$outDir\w.comp.json" -o "$outDir\w.sse"
Start-Sleep -Milliseconds 800
Write-Host "流已结束，产物大小: $((Get-Item "$outDir\w.sse").Length) 字节"
