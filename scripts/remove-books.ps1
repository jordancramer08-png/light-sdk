# Removes .epub files from the Reader app's private book folder on the Light Phone.
# Called by "Remove Books from Phone.cmd". Nothing on the PC is changed.
# Saved places and reading lists are kept, so a book sent again opens where you stopped.

$ErrorActionPreference = 'Stop'
$Package   = 'com.thelightphone.reader'
$PhoneDir  = 'files/shared/books'

# --- find adb ---
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
if (-not (Test-Path $adb)) { Write-Host "Can't find adb. Install Android Studio first (see the walkthrough)."; exit 1 }

# --- phone connected and app installed? ---
$devices = & $adb devices | Select-String "`tdevice$"
if (-not $devices) { Write-Host 'No phone found. Plug it in, unlock it, and allow USB debugging.'; exit 1 }
$installed = & $adb shell pm list packages $Package | Select-String -SimpleMatch "package:$Package"
if (-not $installed) { Write-Host 'The Reader app is not installed yet. Run "Build and Install Reader.cmd" first.'; exit 1 }

# The .epub file names in the app's book folder, A to Z. Subfolders (old converted
# text from the September app) are not books and are never listed or touched.
function PhoneBooks {
    @(& $adb shell "run-as $Package ls -1 $PhoneDir 2>/dev/null" |
        ForEach-Object { $_.TrimEnd("`r") } |
        Where-Object { $_ -like '*.epub' } |
        Sort-Object)
}

# Single-quote a path for the phone's shell (handles spaces and apostrophes).
function Quote([string]$s) { "'" + ($s -replace "'", "'\''") + "'" }

# --- which books? ---
$onPhone = PhoneBooks
if ($onPhone.Count -eq 0) { Write-Host 'There are no books on the phone.'; exit 0 }

$rows = $onPhone | ForEach-Object { [pscustomobject]@{ Book = $_ } }
Write-Host 'Pick books in the window: type in the filter box to search, Ctrl+click to pick several, then OK.'
$picked = @($rows | Out-GridView -Title 'Pick books to remove from the phone' -PassThru)
$names = @($picked | ForEach-Object { $_.Book })
if ($names.Count -eq 0) { Write-Host 'No books picked. Nothing was removed.'; exit 0 }

# --- confirm ---
Write-Host ''
Write-Host 'These books will be removed from the phone:'
$names | ForEach-Object { Write-Host "   $_" }
Write-Host ''
Write-Host 'Saved places and reading lists are kept. Send a book again later and it opens where you stopped.'
$answer = Read-Host "Remove these $($names.Count) books? (Y/N)"
if ($answer.Trim() -notmatch '^(y|yes)$') { Write-Host 'Nothing was removed.'; exit 0 }

# --- remove ---
$removed = 0
foreach ($n in $names) {
    Write-Host "Removing $n"
    $path = Quote "$PhoneDir/$n"
    & $adb shell "run-as $Package rm $path"
    if ($LASTEXITCODE -ne 0) { Write-Host '   remove failed'; continue }
    $removed++
}

Write-Host ''
Write-Host "Removed $removed of $($names.Count) book(s). Open Reader on the phone - the library updates the next time it opens."
Write-Host ''
$left = PhoneBooks
if ($left.Count -eq 0) {
    Write-Host 'No books left on the phone.'
} else {
    Write-Host "Books on the phone now ($($left.Count)):"
    $left | ForEach-Object { "   $_" }
}
