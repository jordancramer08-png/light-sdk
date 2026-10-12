@echo off
setlocal EnableExtensions
title Watch - Smoke Test
where powershell >nul 2>nul
if errorlevel 1 (
  echo Windows PowerShell was not found on this PC.
  pause
  exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Watch-Smoke-Test.ps1" %*
if errorlevel 1 (
  echo.
  echo The script ended with an error. The log is in the "logs" folder.
)
echo.
pause
endlocal
