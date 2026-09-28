@echo off
rem Copies .epub books to the Reader app on the Light Phone.
rem   Double-click:   opens a searchable list of every .epub in D:\Reading\Digital Books;
rem                   pick the ones you want and click OK
rem   Drag and drop:  drop .epub files or folders (e.g. an author folder) onto this file
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0send-books.ps1" %*
echo.
pause
