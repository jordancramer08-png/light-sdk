@echo off
rem Removes books (.epub), comics (.cbz) and comic note files (.txt) from the Reader app on the Light Phone.
rem   Double-click:   opens a searchable list of what's on the phone (Type, Folder, Name);
rem                   pick what to remove, click OK, then answer Y
rem   Saved places, reading lists and reading status are kept, so anything sent again opens where you stopped.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0remove-books.ps1" %*
echo.
pause
