@echo off
rem Installs the saved APK from releases\ on the Phone (keeps your data).
rem Add /preview to only check and show what would happen.
call "%~dp0kit\_run.cmd" install %*
