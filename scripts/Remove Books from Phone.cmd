@echo off
rem Removes .epub books from the Reader app on the Light Phone.
rem   Double-click:   opens a searchable list of the books on the phone;
rem                   pick the ones to remove, click OK, then answer Y
rem   Saved places and reading lists are kept, so a book sent again opens where you stopped.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0remove-books.ps1" %*
echo.
pause
