@echo off
rem Builds the app from source and saves the APK in releases\. Never installs.
rem Add /preview to only check and show what would happen.
call "%~dp0kit\_run.cmd" rebuild %*
