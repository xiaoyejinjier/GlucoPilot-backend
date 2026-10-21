@echo off
REM After git pull / new machine: rewrite local config from defaults.env (STORAGE_PROVIDER=local, no Docker).
cd /d "%~dp0.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-backend-dev.ps1" -Force
echo.
pause
