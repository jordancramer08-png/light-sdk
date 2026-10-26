@echo off
rem Shared starter for the hand-off kit. The double-click files at the top of
rem the app folder call this. Not meant to be double-clicked itself.
rem   _run.cmd ACTION [/preview]
setlocal
cd /d "%~dp0.."
where powershell >nul 2>nul
if errorlevel 1 (
    echo MISSING: Windows PowerShell was not found. It is part of Windows; ask for help.
    goto end
)
rem Started from a PowerShell 7 window, its module path confuses Windows PowerShell.
set "PSModulePath="
set "PREVIEW="
if /i "%~2"=="/preview" set "PREVIEW=-Preview"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0kit.ps1" -Action %1 %PREVIEW%
if errorlevel 1 (
    echo.
    echo It stopped early. Read the red lines above. The same text is in the logs folder.
)
:end
echo.
if /i not "%~2"=="/nopause" if /i not "%~3"=="/nopause" pause
