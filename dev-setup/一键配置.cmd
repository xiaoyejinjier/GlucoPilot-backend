@echo off
chcp 65001 >nul
setlocal
REM 拉取后 / 换机：按 defaults.env 强制写出本机配置（默认本地存储，不启 Docker/MinIO）
cd /d "%~dp0.."
if errorlevel 1 (
  echo [错误] 无法进入 backend 根目录: %~dp0..
  goto :end
)
if not exist "%~dp0setup-backend-dev.ps1" (
  echo [错误] 找不到脚本: %~dp0setup-backend-dev.ps1
  goto :end
)
echo 正在写出本机配置（STORAGE_PROVIDER=local）...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-backend-dev.ps1" -Force
if errorlevel 1 (
  echo.
  echo [失败] 配置未写完，请查看上方报错。
) else (
  echo.
  echo [成功] 可启动后端，IDE profiles: dev,local
)
:end
echo.
pause
endlocal
