# Removes books (.epub), comics (.cbz) and comic note files (.txt) from the Reader app's
# private folders on the Light Phone.
# Called by "Remove Books from Phone.cmd". Nothing on the PC is changed.
# Saved places, reading lists and reading status are kept, so anything sent again opens
# where you stopped.

$ErrorActionPreference = 'Stop'
$Package   = 'com.thelightphone.reader'
$BooksDir  = 'files/shared/books'
$ComicsDir = 'files/shared/comics'

# --- find adb ---
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
if (-not (Test-Path $adb)) { Write-Host "Can't find adb. Install Android Studio first (see the walkthrough)."; exit 1 }

# --- phone connected and app installed? ---
$devices = & $adb devices | Select-String "`tdevice$"
if (-not $devices) { Write-Host 'No phone found. Plug it in, unlock it, and allow USB debugging.'; exit 1 }
$installed = & $adb shell pm list packages $Package | Select-String -SimpleMatch "package:$Package"
if (-not $installed) { Write-Host 'The Reader app is not installed yet. Run "Build and Install Reader.cmd" first.'; exit 1 }

# Everything that can be removed, as rows with Type, Folder, Name and the phone path.
# Books: the .epub files in the book folder (subfolders there are old converted text from
# the September app, never listed or touched). Comics and notes: every .cbz and .txt in
# the comics folder, at any depth.
function PhoneFiles {
    $books = @(& $adb shell "run-as $Package ls -1 $BooksDir 2>/dev/null" |
        ForEach-Object { $_.TrimEnd("`r") } |
        Where-Object { $_ -like '*.epub' } |
        ForEach-Object { [pscustomobject][ordered]@{ Type = 'Book'; Folder = ''; Name = $_; Path = "$BooksDir/$_" } })
    $comics = @(& $adb shell "run-as $Package find $ComicsDir -type f 2>/dev/null" |
        ForEach-Object { $_.TrimEnd("`r") } |
        Where-Object { $_ -like '*.cbz' -or $_ -like '*.txt' } |
        ForEach-Object {
            $relative = $_.Substring($ComicsDir.Length + 1)
            $slash = $relative.LastIndexOf('/')
            [pscustomobject][ordered]@{
                Type   = if ($_ -like '*.cbz') { 'Comic' } else { 'Note' }
                Folder = if ($slash -ge 0) { $relative.Substring(0, $slash) } else { '' }
                Name   = $relative.Substring($slash + 1)
                Path   = $_
            }
        })
    @($books | Sort-Object Name) + @($comics | Sort-Object Folder, Name)
}

# Single-quote a path for the phone's shell (handles spaces and apostrophes).
function Quote([string]$s) { "'" + ($s -replace "'", "'\''") + "'" }

# "3 books, 12 comics and 1 note"
function Describe($rows) {
    $parts = @()
    foreach ($type in 'Book', 'Comic', 'Note') {
        $n = @($rows | Where-Object { $_.Type -eq $type }).Count
        if ($n -eq 1) { $parts += "1 $($type.ToLower())" }
        elseif ($n -gt 1) { $parts += "$n $($type.ToLower())s" }
    }
    if ($parts.Count -le 1) { return $parts -join '' }
    return ($parts[0..($parts.Count - 2)] -join ', ') + ' and ' + $parts[-1]
}

# --- what to remove? ---
$onPhone = @(PhoneFiles)
if ($onPhone.Count -eq 0) { Write-Host 'There are no books or comics on the phone.'; exit 0 }

Write-Host 'Pick what to remove in the window: type in the filter box to search, Ctrl+click to pick several, then OK.'
Write-Host 'Tip: type a folder name in the filter box, then press Ctrl+A to pick everything shown.'
$byKey = @{}
$onPhone | ForEach-Object { $byKey["$($_.Type)|$($_.Folder)|$($_.Name)"] = $_ }
$chosen = @($onPhone | Select-Object Type, Folder, Name |
    Out-GridView -Title 'Pick books, comics and notes to remove from the phone' -PassThru)
$picked = @($chosen | ForEach-Object { $byKey["$($_.Type)|$($_.Folder)|$($_.Name)"] })
if ($picked.Count -eq 0) { Write-Host 'Nothing picked. Nothing was removed.'; exit 0 }

# --- confirm ---
Write-Host ''
Write-Host 'These will be removed from the phone:'
$picked | ForEach-Object { if ($_.Folder) { Write-Host "   $($_.Type): $($_.Folder)/$($_.Name)" } else { Write-Host "   $($_.Type): $($_.Name)" } }
Write-Host ''
Write-Host 'Saved places, reading lists and reading status are kept. Send something again later and it opens where you stopped.'
$answer = Read-Host "Remove these $(Describe $picked)? (Y/N)"
if ($answer.Trim() -notmatch '^(y|yes)$') { Write-Host 'Nothing was removed.'; exit 0 }

# --- remove ---
$removed = 0
$emptied = @{}
foreach ($p in $picked) {
    Write-Host "Removing $($p.Name)"
    & $adb shell "run-as $Package rm $(Quote $p.Path)"
    if ($LASTEXITCODE -ne 0) { Write-Host '   remove failed'; continue }
    $removed++
    # Remember every comics folder above it, so folders left empty can go too.
    $folder = $p.Path.Substring(0, $p.Path.LastIndexOf('/'))
    while ($p.Type -ne 'Book' -and $folder.Length -gt $ComicsDir.Length) {
        $emptied[$folder] = $true
        $folder = $folder.Substring(0, $folder.LastIndexOf('/'))
    }
}

# Deepest first; rmdir only removes a folder that is empty, so folders still holding comics stay.
foreach ($folder in ($emptied.Keys | Sort-Object { $_.Split('/').Count } -Descending)) {
    & $adb shell "run-as $Package rmdir $(Quote $folder) 2>/dev/null"
}

Write-Host ''
Write-Host "Removed $removed of $($picked.Count). Open Reader on the phone - the library updates the next time it opens."
Write-Host ''
$left = @(PhoneFiles)
$leftBooks = @($left | Where-Object { $_.Type -eq 'Book' })
$leftComics = @($left | Where-Object { $_.Type -eq 'Comic' })
if ($leftBooks.Count -eq 0) {
    Write-Host 'No books left on the phone.'
} else {
    Write-Host "Books on the phone now ($($leftBooks.Count)):"
    $leftBooks | ForEach-Object { "   $($_.Name)" }
}
Write-Host "Comics on the phone now: $($leftComics.Count)."
