# Read-only check: never prints process arguments, credentials or application data.
[CmdletBinding()]
param([ValidateRange(1, 65535)][int]$Port = 8080)

$ErrorActionPreference = 'Stop'
$expectedRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\')
Write-Output "Expected backend: $expectedRoot"
try {
    $listener = Get-NetTCPConnection -State Listen -ErrorAction Stop |
        Where-Object LocalPort -eq $Port | Select-Object -First 1
} catch {
    Write-Output 'FAIL: Cannot inspect local TCP listeners in this shell. Run this script directly in a local PowerShell terminal.'
    exit 1
}
if (-not $listener) {
    Write-Output "FAIL: No service is listening on port $Port."
    exit 1
}

$runtimeProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
Write-Output "Listener: PID $($listener.OwningProcess), process $($runtimeProcess.Name)"
$commandLine = $runtimeProcess.CommandLine
if (-not $commandLine) {
    Write-Output 'FAIL: Cannot inspect the listener process; source directory cannot be verified.'
    exit 1
}
$classRoots = @([regex]::Matches($commandLine, '[A-Za-z]:\\[^;"\r\n]*?\\target\\classes') |
    ForEach-Object Value | Select-Object -Unique)
$jarMatch = [regex]::Match($commandLine, '(?i)(?:^|\s)-jar\s+(?:"([^"]+)"|(\S+))')
$origins = $classRoots
if ($jarMatch.Success) {
    $jarPath = if ($jarMatch.Groups[1].Success) { $jarMatch.Groups[1].Value } else { $jarMatch.Groups[2].Value }
    if ([IO.Path]::IsPathRooted($jarPath)) { $origins += $jarPath }
}
$sourceMatches = $origins.Count -gt 0
foreach ($origin in $origins) {
    Write-Output "Loaded from: $origin"
    if (-not $origin.StartsWith($expectedRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        $sourceMatches = $false
    }
}
if (-not $sourceMatches) {
    Write-Output 'FAIL: The running backend is from another checkout, or its origin cannot be verified.'
}

$routesMatch = $true
try {
    $spec = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/v3/api-docs" -TimeoutSec 10
    $required = @(
        @{ Path = '/api/v1/speech-transcriptions'; Method = 'post' },
        @{ Path = '/api/v1/community/following'; Method = 'get' },
        @{ Path = '/api/v1/community/collections'; Method = 'get' },
        @{ Path = '/api/v1/news/{id}/comments'; Method = 'post' },
        @{ Path = '/api/v1/community/messages/{peerUserId}'; Method = 'get' },
        @{ Path = '/api/v1/community/messages/{peerUserId}'; Method = 'post' },
        @{ Path = '/api/v1/chat-sessions'; Method = 'get' },
        @{ Path = '/api/v1/chat-sessions/{sessionId}/messages'; Method = 'get' },
        @{ Path = '/api/v1/chat-sessions/current'; Method = 'post' }
    )
    foreach ($route in $required) {
        $pathProperty = $spec.paths.PSObject.Properties[$route.Path]
        if (-not $pathProperty -or -not $pathProperty.Value.PSObject.Properties[$route.Method]) {
            $routesMatch = $false
            Write-Output "FAIL: Missing $($route.Method.ToUpperInvariant()) $($route.Path)"
        }
    }
} catch {
    $routesMatch = $false
    Write-Output 'FAIL: Cannot verify routes through local OpenAPI. Check that the backend has finished starting and API docs are enabled.'
}

if (-not $sourceMatches -or -not $routesMatch) {
    Write-Output 'Open this backend/pom.xml in the IDE, stop the old run, rebuild and run com.ruoyi.RuoYiApplication from this checkout (dev,local). Then repeat this check.'
    Write-Output 'No process was stopped and no configuration or database was changed.'
    exit 1
}
Write-Output 'PASS: Running directory and chat/speech/community routes match this checkout. This does not verify database state, login or upstream AI credentials.'
