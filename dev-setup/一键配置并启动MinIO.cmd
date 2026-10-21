@echo off
REM 拉取后可选：写出配置 + 启动本机 MinIO 并自动建桶（需 Docker Desktop）
cd /d "%~dp0.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-backend-dev.ps1" -WithMinio -Force
echo.
pause
