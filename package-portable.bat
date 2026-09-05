@echo off
REM Double-click wrapper for package-portable.ps1
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0package-portable.ps1"
echo.
pause
