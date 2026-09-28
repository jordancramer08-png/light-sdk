# Copies .epub files into the Reader app's private book folder on the Light Phone.
# Called by "Send Books to Phone.cmd". Nothing on the PC is changed.

$ErrorActionPreference = 'Stop'
$Package   = 'com.thelightphone.reader'
$PhoneDir  = 'files/shared/books'
$TempDir   = '/data/local/tmp'
$LibraryRoot = 'D:\Reading\Digital Books'   # your whole EPUB library (subfolders included)

# --- find adb ---
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
if (-not (Test-Path $adb)) { Write-Host "Can't find adb. Install Android Studio first (see the walkthrough)."; exit 1 }

# --- which books? ---
# Drag-and-drop: the dropped files/folders. Double-click: a searchable picker over the whole library.
function Find-Epubs($paths) {
    foreach ($p in $paths) {
        if (Test-Path -LiteralPath $p -PathType Container) { Get-ChildItem -LiteralPath $p -Filter *.epub -File -Recurse }
        elseif ($p -like '*.epub' -and (Test-Path -LiteralPath $p)) { Get-Item -LiteralPath $p }
        else { Write-Host "Skipping (not an .epub or folder): $p" }
    }
}
if ($args.Count -gt 0) {
    $books = @(Find-Epubs $args)
} else {
    if (-not (Test-Path -LiteralPath $LibraryRoot)) { Write-Host "Library folder not found: $LibraryRoot"; exit 1 }
    Write-Host "Reading your library in $LibraryRoot ..."
    $all = @(Find-Epubs @($LibraryRoot))
    $rows = $all | Sort-Object FullName | ForEach-Object {
        [pscustomobject]@{
            Folder = $_.DirectoryName.Substring($LibraryRoot.Length).TrimStart('\')
            Book   = $_.BaseName
            SizeMB = [math]::Round($_.Length / 1MB, 1)
            Path   = $_.FullName
        }
    }
    Write-Host 'Pick books in the window: type in the filter box to search, Ctrl+click to pick several, then OK.'
    $picked = @($rows | Out-GridView -Title 'Pick books to send to the phone' -PassThru)
    $books = @($picked | ForEach-Object { Get-Item -LiteralPath $_.Path })
}
$books = @($books)
if ($books.Count -eq 0) { Write-Host 'No books picked.'; exit 1 }

# --- phone connected and app installed? ---
$devices = & $adb devices | Select-String "`tdevice$"
if (-not $devices) { Write-Host 'No phone found. Plug it in, unlock it, and allow USB debugging.'; exit 1 }
$installed = & $adb shell pm list packages $Package | Select-String -SimpleMatch "package:$Package"
if (-not $installed) { Write-Host 'The Reader app is not installed yet. Run "Build and Install Reader.cmd" first.'; exit 1 }

# Phone-side file name: plain ASCII (accents and curly quotes flattened) so nothing
# gets mangled between Windows, adb, and the phone. Titles on screen come from the
# book itself, not the file name.
function PhoneName([string]$name) {
    $n = $name.Replace([char]0x2019, "'").Replace([char]0x2018, "'").Replace([char]0x201C, '"').Replace([char]0x201D, '"')
    $n = $n.Replace([char]0x2013, '-').Replace([char]0x2014, '-')
    $n = $n.Normalize([Text.NormalizationForm]::FormD)
    $n = -join ($n.ToCharArray() | Where-Object { [Globalization.CharUnicodeInfo]::GetUnicodeCategory($_) -ne 'NonSpacingMark' })
    $n = $n -replace '[^\x20-\x7E]', '_'
    $n = $n -replace '"', ''
    return $n
}

# Single-quote a path for the phone's shell (handles spaces and apostrophes).
function Quote([string]$s) { "'" + ($s -replace "'", "'\''") + "'" }

& $adb shell "run-as $Package mkdir -p $PhoneDir" | Out-Null

$sent = 0
foreach ($b in $books) {
    Write-Host "Sending $($b.Name)"
    $tmp = "$TempDir/reader-upload.epub"
    & $adb push $b.FullName $tmp | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Host '   push failed'; continue }
    $dest = Quote "$PhoneDir/$(PhoneName $b.Name)"
    & $adb shell "run-as $Package cp $tmp $dest && rm $tmp"
    if ($LASTEXITCODE -ne 0) { Write-Host '   copy into the app failed'; continue }
    $sent++
}

Write-Host ''
Write-Host "Sent $sent of $($books.Count) book(s). Open Reader on the phone - new books are prepared the first time the library opens."
Write-Host ''
Write-Host 'Books on the phone now:'
& $adb shell "run-as $Package ls $PhoneDir" | Where-Object { $_ -like '*.epub' } | ForEach-Object { "   $_" }
