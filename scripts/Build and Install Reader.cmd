@echo off
setlocal
title Build and Install Reader
rem Builds the Reader tool and installs it on the Light Phone over USB.
rem Double-click it, or run it from PowerShell:  & ".\scripts\Build and Install Reader.cmd"

cd /d "%~dp0.."

rem --- Hand-off kit: keep a log in logs\ ---
if not exist "logs" mkdir "logs"
for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "STAMP=%%i"
set "LOG=%CD%\logs\build-and-install-%STAMP%.log"

rem --- Java: use the one bundled with Android Studio if none is set up ---
if not defined JAVA_HOME if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"

rem --- Android SDK: Android Studio's default location ---
if not exist "local.properties" if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"

rem --- adb: on PATH, or in the Android SDK ---
set "ADB=adb"
where adb >nul 2>nul || set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"

rem --- Hand-off kit: say plainly if a program is missing ---
if not "%ADB%"=="adb" if not exist "%ADB%" (
    echo MISSING: adb, the Android phone tool. Install "Android SDK Platform-Tools".
    goto :end
)
set "JAVA_OK="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_OK=1"
where java >nul 2>nul && set "JAVA_OK=1"
if not defined JAVA_OK (
    echo MISSING: Java 17. Install "Eclipse Temurin 17" from adoptium.net.
    goto :end
)

echo.
echo === 1 of 3: Building the app (first build can take several minutes) ===
rem Shows the build on screen and keeps a copy in the log.
powershell -NoProfile -Command "& cmd /c 'gradlew.bat :tool:assembleDebug 2>&1' | Tee-Object -FilePath $env:LOG -Append; exit $LASTEXITCODE"
if errorlevel 1 goto :fail

echo.
echo === 2 of 3: Installing on the phone (phone plugged in, USB debugging on) ===
"%ADB%" install -r "tool\build\outputs\apk\debug\tool-debug.apk" > "%LOG%.part" 2>&1
set "RC=%ERRORLEVEL%"
type "%LOG%.part"
type "%LOG%.part" >> "%LOG%"
del "%LOG%.part"
if not "%RC%"=="0" goto :installfail

echo.
echo === 3 of 3: Reboot ===
echo The LightOS launcher only shows a new or updated tool after a reboot.
choice /C YN /M "Reboot the phone now"
if errorlevel 2 goto :done
"%ADB%" reboot
goto :done

:installfail
echo.
echo Install failed. Check that the phone is plugged in, unlocked, and shows up in:
echo     "%ADB%" devices
echo If the message mentions INSTALL_FAILED_UPDATE_INCOMPATIBLE, see Troubleshooting
echo in the walkthrough.
goto :end

:fail
echo.
echo Build failed. Scroll up for the first error. Everything is also in the log:
echo     %LOG%
goto :end

:done
echo.
echo Done.

:end
echo.
if defined LOG echo Log: %LOG%
pause
