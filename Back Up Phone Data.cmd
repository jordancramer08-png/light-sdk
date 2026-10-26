@echo off
rem Copies this app's data from the Phone to Documents\App Backups. Read-only on the Phone.
rem Add /preview to only check and show what would happen.
call "%~dp0kit\_run.cmd" backup %*
