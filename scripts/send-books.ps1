# Copies .epub files into the Reader app's private book folder on the Light Phone.
# Called by "Send Books to Phone.cmd". Nothing on the PC is changed.

$ErrorActionPreference = 'Stop'
$Package   = 'com.thelightphone.reader'
$PhoneDir  = 'files/shared/books'
$TempDir   = '/data/local/tmp'
$DefaultSource = Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'reader-books'

# --- find adb ---
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
if (-not (Test-Path $adb)) { Write-Host "Can't find adb. Install Android Studio first (see the walkthrough)."; exit 1 }

# --- which books? ---
$sources = if ($args.Count -gt 0) { $args } else { @($DefaultSource) }
$books = foreach ($s in $sources) {
    if (Test-Path $s -PathType Container) { Get-ChildItem -LiteralPath $s -Filter *.epub -File }
    elseif ($s -like '*.epub' -and (Test-Path -LiteralPath $s)) { Get-Item -LiteralPath $s }
    else { Write-Host "Skipping (not an .epub or folder): $s" }
}
$books = @($books)
if ($books.Count -eq 0) { Write-Host "No .epub files found in: $($sources -join ', ')"; exit 1 }

# --- phone connected and app installed? ---
$devices = & $adb devices | Select-String "`tdevice$"
if (-not $devices) { Write-Host 'No phone found. Plug it in, unlock it, and allow USB debugging.'; exit 1 }
$installed = & $adb shell pm list packages $Package | Select-String -SimpleMatch "package:$Package"
if (-not $installed) { Write-Host 'The Reader app is not installed yet. Run "Build and Install Reader.cmd" first.'; exit 1 }

# Single-quote a path for the phone's shell (handles spaces and apostrophes).
function Quote([string]$s) { "'" + ($s -replace "'", "'\''") + "'" }

& $adb shell "run-as $Package mkdir -p $PhoneDir" | Out-Null

$sent = 0
foreach ($b in $books) {
    Write-Host "Sending $($b.Name)"
    $tmp = "$TempDir/reader-upload.epub"
    & $adb push $b.FullName $tmp | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Host '   push failed'; continue }
    $dest = Quote "$PhoneDir/$($b.Name)"
    & $adb shell "run-as $Package cp $tmp $dest && rm $tmp"
    if ($LASTEXITCODE -ne 0) { Write-Host '   copy into the app failed'; continue }
    $sent++
}

Write-Host ''
Write-Host "Sent $sent of $($books.Count) book(s). Open Reader on the phone - new books are prepared the first time the library opens."
Write-Host ''
Write-Host 'Books on the phone now:'
& $adb shell "run-as $Package ls $PhoneDir" | Where-Object { $_ -like '*.epub' } | ForEach-Object { "   $_" }
