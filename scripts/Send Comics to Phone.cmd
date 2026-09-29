@echo off
rem Copies comics (.cbz, .cbr) to the Reader app on the Light Phone, in the same folders as in D:\Comics.
rem   Double-click:   opens a searchable list of every comic in D:\Comics;
rem                   pick the ones you want and click OK
rem                   (tip: filter on a folder name, then Ctrl+A picks that whole folder)
rem   Drag and drop:  drop comics or folders (subfolders included) onto this file
rem   Note files (.txt) in the same folders go too. .cbr files are repacked as .cbz, and
rem   pages can be shrunk to phone size - on copies only; nothing in D:\Comics is changed.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0send-comics.ps1" %*
echo.
pause
