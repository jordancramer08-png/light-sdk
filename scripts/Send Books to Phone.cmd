@echo off
rem Copies .epub books to the Reader app on the Light Phone.
rem   Double-click:        sends every .epub in Documents\reader-books
rem   Drag and drop:       drop .epub files or a folder onto this file to send just those
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0send-books.ps1" %*
echo.
pause
