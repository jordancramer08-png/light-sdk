@echo off
setlocal
title Build and Install Listen
rem Builds the Listen tool and installs it on the Light Phone over USB.
rem Double-click it, or run it from PowerShell:  & ".\scripts\Build and Install Listen.cmd"

cd /d "%~dp0.."

rem --- Java: use the one bundled with Android Studio if none is set up ---
if not defined JAVA_HOME if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"

rem --- Android SDK: Android Studio's default location ---
if not exist "local.properties" if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"

rem --- adb: on PATH, or in the Android SDK ---
set "ADB=adb"
where adb >nul 2>nul || set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"

echo.
echo === 1 of 3: Building the app (first build can take several minutes) ===
call gradlew.bat :tool:assembleDebug
if errorlevel 1 goto :fail

echo.
echo === 2 of 3: Installing on the phone (phone plugged in, USB debugging on) ===
"%ADB%" install -r "tool\build\outputs\apk\debug\tool-debug.apk"
if errorlevel 1 goto :installfail

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
echo Build failed. Scroll up for the first error, or paste it into Claude Code.
goto :end

:done
echo.
echo Done.

:end
echo.
pause
