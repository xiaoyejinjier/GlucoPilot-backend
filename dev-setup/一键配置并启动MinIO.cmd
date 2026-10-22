@echo off
chcp 65001 >nul
setlocal
REM 可选：写出配置 + 启动本机 MinIO 并自动建桶（需 Docker Desktop）
cd /d "%~dp0.."
if errorlevel 1 (
  echo [错误] 无法进入 backend 根目录: %~dp0..
  goto :end
)
if not exist "%~dp0setup-backend-dev.ps1" (
  echo [错误] 找不到脚本: %~dp0setup-backend-dev.ps1
  goto :end
)
echo 正在写出配置并启动 MinIO（需 Docker Desktop）...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-backend-dev.ps1" -WithMinio -Force
if errorlevel 1 (
  echo.
  echo [失败] 请确认 Docker Desktop 已启动，并查看上方报错。配置文件可能已写出。
) else (
  echo.
  echo [成功] MinIO 控制台: http://127.0.0.1:9001
)
:end
echo.
pause
endlocal
