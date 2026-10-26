@echo off
setlocal
title Watch - Renew TMDB Key
rem TMDB (movie posters and details for Watch-Phone-Sync) can cancel a key.
rem This tests your key and lets you paste a new one. Add /check to only test it.
set "PSModulePath="
set "CHECK="
if /i "%~1"=="/check" set "CHECK=-Check"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0kit\run-logged.ps1" "%~dp0scripts\Renew-TMDB-Key.ps1" %CHECK%
echo.
pause
