# scripts/e2e 的共享前置：路径与凭据的**唯一**来源。
# 这里刻意不含任何密钥：所有凭据要么来自环境变量，要么来自仓库之外的本地文件。
#
# 需要的环境变量（按需）：
#   E2E_APP_LOG        后端日志路径（默认 <repo>/target/e2e-app.log）
#   E2E_MYSQL_CNF      已存在的 mysql --defaults-extra-file（推荐：放在仓库之外，如 %USERPROFILE%\.glucopilot\my.cnf）
#   E2E_MYSQL_HOST/PORT/USER/PASSWORD  未提供 E2E_MYSQL_CNF 时按这些值在临时目录生成一份
#   E2E_ADMIN_PASSWORD 后台 admin 密码（默认 admin123，属开发种子口令）
#   E2E_DEEPSEEK_KEY   真实 DeepSeek 密钥（cipher-e2e.ps1 需要，用来验证"加密落库/解密可用"）
#
# 用法：脚本内 `."$PSScriptRoot\_common.ps1"`，然后调用下面的函数。
#
# 刻意不开 Set-StrictMode：这些脚本大量按宽松风格读 JSON（例：`if ($null -ne $o.delta)`），
# 严格模式下访问不存在的属性会直接抛 PropertyNotFoundStrict，把脚本本身变成坑。

function Get-E2eRepoRoot {
    # scripts/e2e/xxx.ps1 → 上两级即后端仓根
    return (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}

function Get-E2eOutDir {
    $dir = Join-Path (Get-E2eRepoRoot) 'target\e2e'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    return $dir
}

function Get-E2eLogPath {
    if ($env:E2E_APP_LOG) { return $env:E2E_APP_LOG }
    return (Join-Path (Get-E2eRepoRoot) 'target\e2e-app.log')
}

function Get-E2eBaseUrl {
    if ($env:E2E_BASE_URL) { return $env:E2E_BASE_URL }
    return 'http://127.0.0.1:8080'
}

function Get-E2eAdminPassword {
    if ($env:E2E_ADMIN_PASSWORD) { return $env:E2E_ADMIN_PASSWORD }
    return 'admin123'
}

function Get-E2eUserCode {
    # mock 微信登录用的 code；配了 wechat.mock.fixed-openid 时它不影响落到哪个用户
    if ($env:E2E_USER_CODE) { return $env:E2E_USER_CODE }
    return 'mock-glucose-demo'
}

function Assert-E2eDeepSeekKey {
    if (-not $env:E2E_DEEPSEEK_KEY) {
        throw "缺少环境变量 E2E_DEEPSEEK_KEY（真实 DeepSeek 密钥）。本脚本要验证「后台保存密钥 → 加密落库 → 解密可用」这条链路，必须用真凭据；刻意不提供默认值，避免把密钥写进仓库。"
    }
    return $env:E2E_DEEPSEEK_KEY
}

<#
  解析 mysql 客户端参数用的 defaults-extra-file。
  优先级：E2E_MYSQL_CNF（本地已存在的文件）→ 由 E2E_MYSQL_* 生成到临时目录。
  刻意不把连接信息写进仓库：这些脚本会进版本库，密钥/口令只应留在本机。
#>
function Resolve-MysqlCnf {
    if ($env:E2E_MYSQL_CNF) {
        if (-not (Test-Path $env:E2E_MYSQL_CNF)) { throw "E2E_MYSQL_CNF 指向的文件不存在：$env:E2E_MYSQL_CNF" }
        return $env:E2E_MYSQL_CNF
    }
    if (-not $env:E2E_MYSQL_PASSWORD) {
        throw @"
缺少数据库连接信息。二选一：
  1) 设 E2E_MYSQL_CNF 指向本机已有的 defaults-extra-file（推荐，放在仓库之外），例如：
       `$env:E2E_MYSQL_CNF = "`$env:USERPROFILE\.glucopilot\my.cnf"
  2) 或设 E2E_MYSQL_PASSWORD（可加 E2E_MYSQL_HOST/PORT/USER），脚本会在临时目录生成一份
"@
    }
    $host_ = if ($env:E2E_MYSQL_HOST) { $env:E2E_MYSQL_HOST } else { '127.0.0.1' }
    $port = if ($env:E2E_MYSQL_PORT) { $env:E2E_MYSQL_PORT } else { '3306' }
    $user = if ($env:E2E_MYSQL_USER) { $env:E2E_MYSQL_USER } else { 'root' }
    $path = Join-Path ([System.IO.Path]::GetTempPath()) 'glucopilot-e2e-my.cnf'
    $content = "[client]`nhost=$host_`nport=$port`nuser=$user`npassword=$($env:E2E_MYSQL_PASSWORD)`ndefault-character-set=utf8mb4`n"
    [System.IO.File]::WriteAllText($path, $content, (New-Object System.Text.UTF8Encoding($false)))
    return $path
}

function Get-MysqlExe {
    if ($env:E2E_MYSQL_EXE) { return $env:E2E_MYSQL_EXE }
    return 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
}

<# 执行一条 SQL 并返回输出（-N -B 便于脚本解析）。#>
function Invoke-E2eSql([string]$Query) {
    $exe = Get-MysqlExe
    if (-not (Test-Path $exe)) { throw "找不到 mysql 客户端：$exe（可用 E2E_MYSQL_EXE 指定）" }
    return ((& $exe "--defaults-extra-file=$(Resolve-MysqlCnf)" -N -B -e $Query 2>&1) -join "`n")
}
