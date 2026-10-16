#requires -Version 5.1
<#
  图片轮次端到端验证（对应「发送图片报错」的修复确认）。
  完整走一遍小程序的三步上传 + 图片轮次：
    1) 申请上传凭证 → 2) 直传 MinIO → 3) 登记 media_file
    4) 发图片消息（msgType=2）→ 5) SSE 流式补全（阶段1 视觉识别 + 阶段2 文本回答）
  判定：视觉调用成功（日志有「视觉分析完成」）、回答非空、media_caption 被回填。
#>
param([string]$Base = "http://127.0.0.1:8080", [string]$Code = "mock-glucose-demo")

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\_common.ps1"
if (-not $PSBoundParameters.ContainsKey('Base')) { $Base = Get-E2eBaseUrl }
# ⚠️ $api 必须在 $Base 定稿之后才算：否则 E2E_BASE_URL 会被忽略、请求打到默认的 8080（实测踩过）
$api = "$Base/api/v1"
$outDir = "$PSScriptRoot\..\..\target\e2e"
$log = (Get-E2eLogPath)
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Save-Json([string]$Path, $Object) {
    [System.IO.File]::WriteAllText($Path, ($Object | ConvertTo-Json -Compress -Depth 6), (New-Object System.Text.UTF8Encoding($false)))
}
function Read-Sse([string]$Path) {
    $raw = [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
    $sb = [System.Text.StringBuilder]::new(); $done = $null
    foreach ($line in ($raw -split "\r?\n")) {
        if (-not $line.StartsWith("data:")) { continue }
        $p = $line.Substring(5).Trim(); if (-not $p) { continue }
        try { $o = $p | ConvertFrom-Json } catch { continue }
        if ($null -ne $o.delta) { [void]$sb.Append($o.delta) } elseif ($null -ne $o.assistantMsgId) { $done = $o }
    }
    if ($done) { return [string]$done.contentText }
    return $sb.ToString()
}

# ---------- 造一张可识别的测试图 ----------
Add-Type -AssemblyName System.Drawing
$imgPath = "$outDir\vision-test.jpg"
$bmp = New-Object System.Drawing.Bitmap 480, 360
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.Clear([System.Drawing.Color]::White)
$g.FillEllipse([System.Drawing.Brushes]::Crimson, 50, 50, 170, 170)
$g.FillRectangle([System.Drawing.Brushes]::SeaGreen, 270, 80, 150, 120)
$g.DrawString("TEST 123", (New-Object System.Drawing.Font("Arial", 28)), [System.Drawing.Brushes]::Black, 110, 270)
$g.Dispose()
$bmp.Save($imgPath, [System.Drawing.Imaging.ImageFormat]::Jpeg)
$bmp.Dispose()
$size = (Get-Item $imgPath).Length
Write-Host "1) 测试图已生成: $imgPath ($([math]::Round($size/1024))KB)"

# ---------- 登录 ----------
Save-Json "$outDir\login.json" @{ code = $Code }
$login = (& curl.exe -s -X POST "$api/auth/wechat/sessions" -H "Content-Type: application/json" --data-binary "@$outDir\login.json") | ConvertFrom-Json
if ($login.code -ne 0) { throw "登录失败" }
$auth = @("-H", "Authorization: Bearer $($login.data.accessToken)", "-H", "Content-Type: application/json")

# ---------- 上传三步 ----------
Save-Json "$outDir\cred.json" @{ bizType = 4; mimeType = "image/jpeg"; sizeBytes = $size }
$cred = (& curl.exe -s -X POST "$api/media-files/upload-credentials" @auth --data-binary "@$outDir\cred.json") | ConvertFrom-Json
if ($cred.code -ne 0) { throw "申请上传凭证失败: $($cred | ConvertTo-Json -Compress)" }
Write-Host "2) 凭证 OK: storageKey=$($cred.data.storageKey)"

& curl.exe -s -o NUL -w "%{http_code}" -X PUT --upload-file "$imgPath" "$($cred.data.uploadUrl)" | ForEach-Object { Write-Host "3) 直传 MinIO HTTP $($_)" }

Save-Json "$outDir\reg.json" @{ bizType = 4; storageKey = $cred.data.storageKey; mimeType = "image/jpeg"; sizeBytes = $size }
$reg = (& curl.exe -s -X POST "$api/media-files" @auth --data-binary "@$outDir\reg.json") | ConvertFrom-Json
if ($reg.code -ne 0) { throw "登记失败: $($reg | ConvertTo-Json -Compress)" }
$mediaId = $reg.data.id
Write-Host "4) 登记 OK: mediaId=$mediaId fileUrl=$($reg.data.fileUrl)"

# ---------- 图片轮次 ----------
Save-Json "$outDir\cur.json" @{ sessionType = 3 }
$sess = (& curl.exe -s -X POST "$api/chat-sessions/current" @auth --data-binary "@$outDir\cur.json") | ConvertFrom-Json
Save-Json "$outDir\img.msg.json" @{ clientMsgId = [guid]::NewGuid().ToString(); msgType = 2; mediaId = $mediaId; contentText = "这是我今天的早餐，碳水高吗？" }
$msg = (& curl.exe -s -X POST "$api/chat-sessions/$($sess.data.id)/messages" @auth --data-binary "@$outDir\img.msg.json") | ConvertFrom-Json
if ($msg.code -ne 0) { throw "发图片消息失败: $($msg | ConvertTo-Json -Compress)" }
Write-Host "5) 图片消息已落库: msgId=$($msg.data.id)（sessionId=$($sess.data.id)）"

$before = (Get-Content -LiteralPath $log -Encoding UTF8 -ReadCount 0).Count
Save-Json "$outDir\img.comp.json" @{ clientMsgId = [guid]::NewGuid().ToString(); replyToMsgId = $msg.data.id; stream = $true }
& curl.exe -sN -X POST "$api/chat-sessions/$($sess.data.id)/completions" @auth --data-binary "@$outDir\img.comp.json" -o "$outDir\img.sse"
Start-Sleep -Milliseconds 1000

$answer = Read-Sse "$outDir\img.sse"
[System.IO.File]::WriteAllText("$outDir\img.answer.txt", $answer, (New-Object System.Text.UTF8Encoding($false)))
$lines = Get-Content -LiteralPath $log -Encoding UTF8 -ReadCount 0
$new = @($lines[$before..($lines.Count - 1)])

Write-Host "`n=== 视觉与工具日志 ==="
$new | Where-Object { $_ -match "视觉分析|视觉 provider|工具 \S+ 执行|视觉分析调用失败" } | ForEach-Object { $_.Substring([Math]::Max(0, $_.IndexOf("]")+2)) }
Write-Host "`n=== 回答（$($answer.Length) 字）==="
Write-Host $answer.Substring(0, [Math]::Min(260, $answer.Length))
