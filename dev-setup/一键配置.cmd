@echo off
REM 拉取后 / 换机：按 defaults.env 强制写出本机配置（默认本地存储，不启 Docker/MinIO）
cd /d "%~dp0.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-backend-dev.ps1" -Force
echo.
pause
