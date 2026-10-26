@echo off
setlocal EnableExtensions
title Listen - Phone Sync (music + audiobooks)
set "ABS_SELF=%~f0"
where powershell >nul 2>nul
if errorlevel 1 (
  echo Windows PowerShell was not found on this PC.
  pause
  exit /b 1
)
rem Hand-off kit: keep a copy of everything shown in a log file.
set "LOGDIR=%USERPROFILE%\Documents\listen\logs"
if not exist "%LOGDIR%" mkdir "%LOGDIR%"
for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "STAMP=%%i"
set "KIT_LOG=%LOGDIR%\listen-phone-sync-%STAMP%.log"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Transcript -LiteralPath $env:KIT_LOG | Out-Null; try { $t=[IO.File]::ReadAllText($env:ABS_SELF); $m=[char]35+'BEGIN-POWERSHELL'+[char]35; $i=$t.IndexOf($m); if($i -lt 0){Write-Host 'Script body not found.' -ForegroundColor Red; exit 1}; Invoke-Expression $t.Substring($i) } finally { Stop-Transcript | Out-Null }"
if errorlevel 1 (
  echo.
  echo The script ended with an error.
)
echo.
echo Log: %KIT_LOG%
pause
endlocal
exit /b
#BEGIN-POWERSHELL#
#===========================================================================
#  Listen - Phone Sync (engine)
#  Adds and removes music and audiobooks on the Light Phone III for the
#  "Listen" app. Files are copied exactly as they are (never re-encoded), so
#  the phone plays the same quality you have on the PC.
#
#  Phone layout (the Listen app reads from here):
#    /sdcard/Listen/Music/<Artist>/<Album>/<song files>
#    /sdcard/Listen/Audiobooks/<same folders as the PC>/<audio files>
#                                             + book.json + cover.jpg
#    /sdcard/Listen/.state/   playlists, positions (written by the app)
#
#  Nothing on the PC is ever changed, except the editable titles CSV
#  (_Listen_Book_Titles.csv in the Audio Books folder) and backups.
#===========================================================================

$ErrorActionPreference = 'Stop'
Set-StrictMode -Off
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}
$OutputEncoding = [Text.Encoding]::UTF8

#---------------------------------------------------------------------------
# region  SETTINGS  (edit these if your folders move)
#---------------------------------------------------------------------------
$MusicRoot  = 'D:\Music'
$BooksRoot  = 'D:\Reading\Audio Books'
$PhoneRoot  = '/sdcard/Listen'
$AppPackage = 'com.thelightphone.listen'
$AdbPath    = ''          # leave blank to find adb.exe automatically
$FfmpegDir  = ''          # optional: folder holding ffmpeg.exe / ffprobe.exe

# Top-level Audio Books folders that are categories, with author folders inside
$CategoryFolders = @('Nonfiction', 'Theology and Christian')

# Test hook (ignored in normal use)
if ($env:LISTEN_MUSIC_ROOT) { $MusicRoot = $env:LISTEN_MUSIC_ROOT }
if ($env:LISTEN_BOOKS_ROOT) { $BooksRoot = $env:LISTEN_BOOKS_ROOT }
if ($env:LISTEN_ADB)        { $AdbPath   = $env:LISTEN_ADB }

$BackupRoot = Join-Path $MusicRoot '_Listen Backups'
$TitlesCsv  = Join-Path $BooksRoot '_Listen_Book_Titles.csv'
$PhoneMusic = "$PhoneRoot/Music"
$PhoneBooks = "$PhoneRoot/Audiobooks"
$PhoneState = "$PhoneRoot/.state"

$AudioExt = @('.mp3', '.m4a', '.m4b', '.aac', '.flac', '.ogg', '.oga', '.opus', '.wav')
$ImageExt = @('.jpg', '.jpeg', '.png')
$MusicCoverNames = @('cover.jpg', 'cover.png', 'folder.jpg', 'folder.png', 'front.jpg', 'front.png')

$script:Adb = $null
$script:Serial = $null
$script:Ffprobe = $null
$script:Ffmpeg = $null
$script:PhoneFiles = @{}     # remote path -> size
$script:TempDir = Join-Path ([IO.Path]::GetTempPath()) 'ListenSync'
#endregion

#---------------------------------------------------------------------------
# region  SMALL HELPERS
#---------------------------------------------------------------------------
function Write-Title($text) {
    Write-Host ''
    Write-Host ('=' * 70) -ForegroundColor DarkCyan
    Write-Host ("  " + $text) -ForegroundColor Cyan
    Write-Host ('=' * 70) -ForegroundColor DarkCyan
}
function Write-Note($text)  { Write-Host $text -ForegroundColor DarkGray }
function Write-Good($text)  { Write-Host $text -ForegroundColor Green }
function Write-Warn2($text) { Write-Host $text -ForegroundColor Yellow }
function Write-Bad($text)   { Write-Host $text -ForegroundColor Red }

function Format-Size([double]$bytes) {
    if ($bytes -ge 1GB) { return ('{0:N1} GB' -f ($bytes / 1GB)) }
    if ($bytes -ge 1MB) { return ('{0:N0} MB' -f ($bytes / 1MB)) }
    return ('{0:N0} KB' -f ($bytes / 1KB))
}

function Read-YesNo($prompt) {
    $a = Read-Host "$prompt (Y/N)"
    return ($a -match '^\s*y')
}

function Pause-Here { [void](Read-Host 'Press Enter to continue') }

# Single-quote a string for the phone's shell
function Q([string]$s) { return "'" + $s.Replace("'", "'\''") + "'" }

# Split a relative path on either slash
function Split-Rel([string]$rel) { return @($rel -split '[\\/]' | Where-Object { $_ -ne '' }) }

function Get-RelPath([string]$full, [string]$root) {
    $r = $root.TrimEnd('\', '/')
    if ($full.Length -le $r.Length) { return '' }
    return $full.Substring($r.Length).TrimStart('\', '/')
}

function To-Remote([string]$base, [string]$rel) {
    $parts = @(Split-Rel $rel)
    if ($parts.Count -eq 0) { return $base }
    return $base + '/' + ($parts -join '/')
}

function Get-NaturalKey([string]$s) {
    return [regex]::Replace($s.ToLowerInvariant(), '\d+', { param($m) $m.Value.PadLeft(8, '0') })
}

function Test-Skipped([string]$rel) {
    foreach ($p in (Split-Rel $rel)) {
        if ($p.StartsWith('_') -or $p.StartsWith('.')) { return $true }
    }
    return $false
}

# Parse "1,3,5-8" into a list of ints; returns $null if not a number list
function Parse-Numbers([string]$text, [int]$max) {
    if ($text -notmatch '^[\d\s,\-]+$') { return $null }
    $out = New-Object System.Collections.Generic.List[int]
    foreach ($chunk in ($text -split ',')) {
        $c = $chunk.Trim()
        if ($c -eq '') { continue }
        if ($c -match '^(\d+)\s*-\s*(\d+)$') {
            $a = [int]$Matches[1]; $b = [int]$Matches[2]
            if ($a -gt $b) { $t = $a; $a = $b; $b = $t }
            for ($i = $a; $i -le $b; $i++) { if ($i -ge 1 -and $i -le $max -and -not $out.Contains($i)) { $out.Add($i) } }
        } elseif ($c -match '^\d+$') {
            $i = [int]$c
            if ($i -ge 1 -and $i -le $max -and -not $out.Contains($i)) { $out.Add($i) }
        } else { return $null }
    }
    return ,$out
}
#endregion

#---------------------------------------------------------------------------
# region  ADB
#---------------------------------------------------------------------------
function Find-Adb {
    if ($AdbPath -and (Test-Path -LiteralPath $AdbPath)) { return $AdbPath }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $cands = New-Object System.Collections.Generic.List[string]
    foreach ($v in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) { if ($v) { $cands.Add((Join-Path $v 'platform-tools\adb.exe')) } }
    if ($env:LOCALAPPDATA) { $cands.Add((Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')) }
    if ($env:USERPROFILE) {
        # sdk.dir from any of the LP3 project folders
        foreach ($proj in @('Documents\reader', 'Documents\light-sdk', 'Documents\listen')) {
            $lp = Join-Path $env:USERPROFILE (Join-Path $proj 'local.properties')
            if (Test-Path -LiteralPath $lp) {
                $line = Get-Content -LiteralPath $lp | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
                if ($line) {
                    $dir = ($line -replace '^\s*sdk\.dir\s*=\s*', '') -replace '\\:', ':' -replace '\\\\', '\'
                    $cands.Add((Join-Path $dir 'platform-tools\adb.exe'))
                }
            }
        }
        $cands.Add((Join-Path $env:USERPROFILE 'platform-tools\adb.exe'))
        $cands.Add((Join-Path $env:USERPROFILE 'Downloads\platform-tools\adb.exe'))
    }
    $cands.Add('C:\platform-tools\adb.exe')
    $cands.Add('C:\Android\platform-tools\adb.exe')
    foreach ($c in $cands) { if ($c -and (Test-Path -LiteralPath $c)) { return $c } }
    return $null
}

# Run adb and return @{ Code; Out (string[]) } without throwing on stderr
function Invoke-Adb {
    $AdbArgs = @($args)
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $full = @()
        if ($script:Serial) { $full += @('-s', $script:Serial) }
        $full += $AdbArgs
        $out = & $script:Adb @full 2>&1 | ForEach-Object { "$_" }
        return @{ Code = $LASTEXITCODE; Out = @($out) }
    } finally { $ErrorActionPreference = $old }
}

function Invoke-Phone([string]$shellCmd) { return (Invoke-Adb shell $shellCmd) }

function Connect-Phone {
    $script:Serial = $null
    $r = Invoke-Adb devices
    $ready = @(); $unauth = @()
    foreach ($line in $r.Out) {
        if ($line -match '^(\S+)\s+device$') { $ready += $Matches[1] }
        elseif ($line -match '^(\S+)\s+(unauthorized|offline)') { $unauth += $Matches[1] }
    }
    if ($ready.Count -eq 0) {
        if ($unauth.Count -gt 0) {
            Write-Warn2 'The phone is connected but has not allowed this PC yet.'
            Write-Warn2 'Unlock the phone and tap "Allow" on the USB debugging prompt, then try again.'
        } else {
            Write-Warn2 'No phone found. Plug the Light Phone in with a data USB cable and make sure'
            Write-Warn2 'USB debugging is still on (same setup you used to install the Reader).'
        }
        return $false
    }
    $script:Serial = $ready[0]
    return $true
}

function Get-PhoneFree {
    $r = Invoke-Phone 'df -k /sdcard'
    $last = $r.Out | Where-Object { $_ -match '\d+\s+\d+\s+\d+' } | Select-Object -Last 1
    if ($last) {
        $cols = @($last -split '\s+' | Where-Object { $_ -ne '' })
        # Filesystem 1K-blocks Used Available Use% Mounted
        foreach ($i in 1..($cols.Count - 1)) {
            if ($cols[$i] -match '%$' -and $i -ge 3) { return [double]$cols[$i - 1] * 1KB }
        }
        if ($cols.Count -ge 4) { return [double]$cols[3] * 1KB }
    }
    return -1
}

function Test-AppInstalled {
    $r = Invoke-Phone "pm list packages $AppPackage"
    return [bool]($r.Out | Where-Object { $_.Trim() -eq "package:$AppPackage" })
}

function Grant-Storage([switch]$Quiet) {
    if (-not (Test-AppInstalled)) {
        if (-not $Quiet) { Write-Warn2 "The Listen app ($AppPackage) is not installed yet - nothing to grant." }
        return
    }
    $r = Invoke-Phone "appops set $AppPackage MANAGE_EXTERNAL_STORAGE allow"
    if ($r.Code -eq 0) { if (-not $Quiet) { Write-Good 'Listen now has permission to read /sdcard/Listen.' } }
    else { Write-Warn2 ('Could not grant storage access: ' + ($r.Out -join ' ')) }
}

# Reads every file under /sdcard/Listen with its size
function Refresh-PhoneFiles {
    $script:PhoneFiles = @{}
    $r = Invoke-Phone ("find " + (Q $PhoneRoot) + " -type f -exec stat -c '%s|%n' {} + 2>/dev/null")
    foreach ($line in $r.Out) {
        $i = $line.IndexOf('|')
        if ($i -gt 0 -and $line.Substring(0, $i) -match '^\d+$') {
            $script:PhoneFiles[$line.Substring($i + 1)] = [int64]$line.Substring(0, $i)
        }
    }
}

function Write-SyncStamp {
    $stamp = (Get-Date).ToString('yyyy-MM-ddTHH:mm:ss')
    [void](Invoke-Phone ("mkdir -p " + (Q $PhoneState) + " && echo $stamp > " + (Q "$PhoneState/last-sync.txt")))
}

function Remove-EmptyParents([string]$remoteDir, [string]$stopAt) {
    $d = $remoteDir
    while ($d.Length -gt $stopAt.Length -and $d.StartsWith($stopAt)) {
        $r = Invoke-Phone ("rmdir " + (Q $d) + " 2>/dev/null")
        if ($r.Code -ne 0) { break }
        $d = $d.Substring(0, $d.LastIndexOf('/'))
    }
}
#endregion

#---------------------------------------------------------------------------
# region  FFMPEG (optional - only for audiobook chapters and embedded covers)
#---------------------------------------------------------------------------
function Find-Ff([string]$name) {
    if ($FfmpegDir) {
        $p = Join-Path $FfmpegDir "$name.exe"
        if (Test-Path -LiteralPath $p) { return $p }
    }
    $c = Get-Command $name -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    foreach ($p in @("C:\ffmpeg\bin\$name.exe", "$MusicRoot\_MusicToolkit\$name.exe", "$env:USERPROFILE\Music\_MusicToolkit\$name.exe")) {
        if ($p -and (Test-Path -LiteralPath $p)) { return $p }
    }
    return $null
}

function Invoke-Native([string]$exe, [string[]]$argList) {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        $out = & $exe @argList 2>$null | ForEach-Object { "$_" }
        return @{ Code = $LASTEXITCODE; Out = @($out) }
    } finally { $ErrorActionPreference = $old }
}

function Get-M4bChapters([string]$file) {
    if (-not $script:Ffprobe) { return @() }
    $r = Invoke-Native $script:Ffprobe @('-v', 'quiet', '-print_format', 'json', '-show_chapters', $file)
    if ($r.Code -ne 0 -or $r.Out.Count -eq 0) { return @() }
    try { $j = ($r.Out -join "`n") | ConvertFrom-Json } catch { return @() }
    if (-not ($j.PSObject.Properties.Name -contains 'chapters')) { return @() }
    $list = @()
    $n = 0
    foreach ($c in @($j.chapters)) {
        $n++
        $t = "Chapter $n"
        if ($c.PSObject.Properties.Name -contains 'tags' -and $c.tags -and ($c.tags.PSObject.Properties.Name -contains 'title') -and $c.tags.title) { $t = [string]$c.tags.title }
        $list += [ordered]@{ title = $t; startMs = [int64]([double]$c.start_time * 1000) }
    }
    return $list
}
#endregion

#---------------------------------------------------------------------------
# region  MUSIC LIBRARY (PC side)
#---------------------------------------------------------------------------
# An "album" = any folder that directly holds audio files.
function Get-MusicAlbums {
    $albums = @{}
    if (-not (Test-Path -LiteralPath $MusicRoot)) { return @() }
    foreach ($f in (Get-ChildItem -LiteralPath $MusicRoot -Recurse -File -ErrorAction SilentlyContinue)) {
        $rel = Get-RelPath $f.FullName $MusicRoot
        if (Test-Skipped $rel) { continue }
        $ext = $f.Extension.ToLowerInvariant()
        $isAudio = $AudioExt -contains $ext
        $isCover = $MusicCoverNames -contains $f.Name.ToLowerInvariant()
        if (-not ($isAudio -or $isCover)) { continue }
        $dirRel = Get-RelPath $f.DirectoryName $MusicRoot
        if ($dirRel -eq '') { continue }   # loose files in the root are ignored
        if (-not $albums.ContainsKey($dirRel)) {
            $parts = @(Split-Rel $dirRel)
            $artist = $parts[0]
            $albumName = '(songs)'
            if ($parts.Count -gt 1) { $albumName = ($parts[1..($parts.Count - 1)] -join ' / ') }
            $albums[$dirRel] = [pscustomobject]@{
                Kind = 'music'; RelDir = $dirRel; Artist = $artist; Album = $albumName
                Label = "$artist - $albumName"; Files = New-Object System.Collections.ArrayList
                Songs = 0; Bytes = [int64]0; Status = ''
                RemoteDir = (To-Remote $PhoneMusic $dirRel)
            }
        }
        $a = $albums[$dirRel]
        [void]$a.Files.Add([pscustomobject]@{ Local = $f.FullName; Remote = (To-Remote $PhoneMusic $rel); Size = [int64]$f.Length })
        if ($isAudio) { $a.Songs++ }
        $a.Bytes += $f.Length
    }
    $list = @($albums.Values | Where-Object { $_.Songs -gt 0 } | Sort-Object @{ E = { Get-NaturalKey $_.Artist } }, @{ E = { Get-NaturalKey $_.Album } })
    return $list
}
#endregion

#---------------------------------------------------------------------------
# region  AUDIOBOOK LIBRARY (PC side)
#---------------------------------------------------------------------------
function Clean-Piece([string]$s, [string]$author) {
    $t = $s.Trim()
    if ($author) {
        foreach ($sep in @(' - ', '-', ' ')) {
            if ($t.StartsWith($author + $sep, [StringComparison]::OrdinalIgnoreCase)) { $t = $t.Substring($author.Length + $sep.Length); break }
        }
    }
    $t = $t -replace '\s*\[\d+\s*-\s*\d+\]\s*', ' '
    $t = $t -replace '\s*\((Unabridged)\)\s*', ' '
    $t = $t.Trim(' ', '-', '_')
    return $t
}

function Guess-BookInfo([string]$rel) {
    $seg = @(Split-Rel $rel)
    $category = ''
    if ($seg.Count -ge 3 -and ($CategoryFolders -contains $seg[0])) {
        $category = $seg[0]; $seg = @($seg[1..($seg.Count - 1)])
    }
    if ($seg.Count -eq 1) {
        # Book folder sitting directly in Audio Books - no author folder
        $t1 = Clean-Piece $seg[0] ''
        $n1 = ''
        if ($t1 -match '\((?:read|narrated) by ([^)]+)\)') { $n1 = $Matches[1].Trim(); $t1 = ($t1 -replace '\s*\((?:read|narrated) by [^)]+\)', '').Trim() }
        return [pscustomobject][ordered]@{ Folder = $rel; Author = ''; Title = $t1; Series = ''; Number = ''; Narrator = $n1; Year = ''; Category = '' }
    }
    $author = $seg[0]
    $rest = @()
    if ($seg.Count -gt 1) { $rest = @($seg[1..($seg.Count - 1)]) }
    $info = [ordered]@{ Folder = $rel; Author = $author; Title = ''; Series = ''; Number = ''; Narrator = ''; Year = ''; Category = $category }
    if ($rest.Count -eq 0) { $info.Title = $author; return [pscustomobject]$info }

    $leaf = Clean-Piece $rest[$rest.Count - 1] $author
    $parent = ''
    if ($rest.Count -ge 2) {
        $parent = Clean-Piece $rest[$rest.Count - 2] $author
        $parent = ($parent -replace '-#\d+-', ' ').Trim()
    }

    if ($leaf -match '\((?:read|narrated) by ([^)]+)\)') { $info.Narrator = $Matches[1].Trim(); $leaf = ($leaf -replace '\s*\((?:read|narrated) by [^)]+\)', '').Trim() }
    if ($leaf -match '^(\d{4})\s+-\s+(.+)$') { $info.Year = $Matches[1]; $leaf = $Matches[2] }
    if ($leaf -match '^(.+?)\s*\(([^,()]+),\s*Book\s+(\d+)\)$') { $leaf = $Matches[1]; $info.Series = $Matches[2].Trim(); $info.Number = [string][int]$Matches[3] }
    if ($leaf -match '^.*?#(\d+)-(.+)$') { $info.Number = [string][int]$Matches[1]; $leaf = $Matches[2].Trim() }
    if ($leaf -match '^(?:Book\s+)?(\d{1,2})\s+-\s+(.+)$') { $info.Number = [string][int]$Matches[1]; $leaf = $Matches[2] }
    if ($leaf -match '^(Volume|Vol\.?|Part|Book)\s*(\d+)$' -and $parent) {
        $info.Number = [string][int]$Matches[2]
        $leaf = "$parent, $leaf"
    }
    if (-not $info.Series -and $parent -and $info.Number) { $info.Series = $parent }
    $info.Title = $leaf.Trim(' ', '-')
    if (-not $info.Title) { $info.Title = $rest[$rest.Count - 1] }
    return [pscustomobject]$info
}

function Get-BookTitles([string[]]$folders) {
    $map = @{}
    if (Test-Path -LiteralPath $TitlesCsv) {
        foreach ($row in (Import-Csv -LiteralPath $TitlesCsv -Encoding UTF8)) { if ($row.Folder) { $map[$row.Folder] = $row } }
    }
    $added = 0
    foreach ($f in $folders) {
        if (-not $map.ContainsKey($f)) { $map[$f] = Guess-BookInfo $f; $added++ }
    }
    if ($added -gt 0) {
        try {
            $map.Values | Sort-Object @{ E = { Get-NaturalKey $_.Folder } } |
                Select-Object Folder, Author, Title, Series, Number, Narrator, Year, Category |
                Export-Csv -LiteralPath $TitlesCsv -NoTypeInformation -Encoding UTF8
        } catch {
            Write-Warn2 "Could not save $TitlesCsv (is it open in Excel?). Titles will still work this time."
        }
    }
    return $map
}

function Pick-Cover($dir, $files) {
    $imgs = @($files | Where-Object { $ImageExt -contains $_.Extension.ToLowerInvariant() })
    if ($imgs.Count -eq 0) { return $null }
    foreach ($n in @('cover', 'folder', 'front')) {
        $hit = $imgs | Where-Object { $_.BaseName -ieq $n } | Select-Object -First 1
        if ($hit) { return $hit }
    }
    $hit = $imgs | Where-Object { $_.BaseName -match '(?i)(cover|front|[-_ ]lg$|large)' } | Sort-Object Length -Descending | Select-Object -First 1
    if ($hit) { return $hit }
    return ($imgs | Sort-Object Length -Descending | Select-Object -First 1)
}

function Get-FileLabels($audioFiles) {
    $names = @($audioFiles | ForEach-Object { $_.BaseName })
    if ($names.Count -le 1) { return $names }
    $prefix = $names[0]
    foreach ($n in $names) {
        $k = 0
        $max = [Math]::Min($prefix.Length, $n.Length)
        while ($k -lt $max -and [char]::ToLowerInvariant($prefix[$k]) -eq [char]::ToLowerInvariant($n[$k])) { $k++ }
        $prefix = $prefix.Substring(0, $k)
    }
    # cut back to a separator so we never chop a word in half
    $cut = $prefix.LastIndexOfAny([char[]]@(' ', '-', '_', '.'))
    if ($cut -lt 0) { $cut = -1 }
    $keep = $cut + 1
    $out = @()
    foreach ($n in $names) {
        $l = $n.Substring([Math]::Min($keep, $n.Length)).Trim(' ', '-', '_', '.')
        if (-not $l -or $l -match '^\d+$') { $l = $n }
        $out += $l
    }
    return $out
}

# A "book" = any folder that directly holds audio files.
function Get-Books {
    if (-not (Test-Path -LiteralPath $BooksRoot)) { return @() }
    $byDir = @{}
    foreach ($f in (Get-ChildItem -LiteralPath $BooksRoot -Recurse -File -ErrorAction SilentlyContinue)) {
        $dirRel = Get-RelPath $f.DirectoryName $BooksRoot
        if ($dirRel -eq '' -or (Test-Skipped $dirRel)) { continue }
        if (-not $byDir.ContainsKey($dirRel)) { $byDir[$dirRel] = New-Object System.Collections.ArrayList }
        [void]$byDir[$dirRel].Add($f)
    }
    $bookDirs = @($byDir.Keys | Where-Object { @($byDir[$_] | Where-Object { $AudioExt -contains $_.Extension.ToLowerInvariant() }).Count -gt 0 })
    $titles = Get-BookTitles $bookDirs
    $list = @()
    foreach ($d in $bookDirs) {
        $all = @($byDir[$d])
        $audio = @($all | Where-Object { $AudioExt -contains $_.Extension.ToLowerInvariant() } | Sort-Object @{ E = { Get-NaturalKey $_.Name } })
        $cover = Pick-Cover $d $all
        $t = $titles[$d]
        $remoteDir = To-Remote $PhoneBooks $d
        $files = New-Object System.Collections.ArrayList
        $bytes = [int64]0
        foreach ($a in $audio) {
            [void]$files.Add([pscustomobject]@{ Local = $a.FullName; Remote = "$remoteDir/$($a.Name)"; Size = [int64]$a.Length })
            $bytes += $a.Length
        }
        if ($cover) {
            $ce = $cover.Extension.ToLowerInvariant(); if ($ce -eq '.jpeg') { $ce = '.jpg' }
            [void]$files.Add([pscustomobject]@{ Local = $cover.FullName; Remote = "$remoteDir/cover$ce"; Size = [int64]$cover.Length })
            $bytes += $cover.Length
        }
        $label = $t.Title
        if ($t.Author -and $t.Author -ne $t.Title) { $label = "$($t.Author) - $($t.Title)" }
        if ($t.Narrator) { $label += " (read by $($t.Narrator))" }
        $list += [pscustomobject]@{
            Kind = 'book'; RelDir = $d; Info = $t; Label = $label; Audio = $audio; Cover = $cover
            Files = $files; Bytes = $bytes; Songs = $audio.Count; Status = ''; RemoteDir = $remoteDir
        }
    }
    return @($list | Sort-Object @{ E = { Get-NaturalKey $_.Info.Author } }, @{ E = { Get-NaturalKey $_.Info.Series } }, @{ E = { Get-NaturalKey ('{0,4}' -f $_.Info.Number) } }, @{ E = { Get-NaturalKey $_.Info.Title } })
}

function New-BookJson($book) {
    $t = $book.Info
    $labels = @(Get-FileLabels $book.Audio)
    $fileList = @()
    for ($i = 0; $i -lt $book.Audio.Count; $i++) {
        $a = $book.Audio[$i]
        $entry = [ordered]@{ name = $a.Name; label = $labels[$i]; bytes = [int64]$a.Length; chapters = @() }
        if ($a.Extension -ieq '.m4b' -or $a.Extension -ieq '.m4a') { $entry.chapters = @(Get-M4bChapters $a.FullName) }
        $fileList += $entry
    }
    $cover = $null
    $coverFile = $book.Files | Where-Object { $_.Remote -match '/cover\.(jpg|png)$' } | Select-Object -First 1
    if ($coverFile) { $cover = $coverFile.Remote.Substring($coverFile.Remote.LastIndexOf('/') + 1) }
    $num = $null
    if ($t.Number -match '^\d+(\.\d+)?$') { $num = [double]$t.Number }
    $obj = [ordered]@{
        schema = 1
        id = ((Split-Rel $book.RelDir) -join '/')
        title = [string]$t.Title; author = [string]$t.Author
        series = [string]$t.Series; seriesNumber = $num
        narrator = [string]$t.Narrator; year = [string]$t.Year; category = [string]$t.Category
        cover = $cover
        files = $fileList
        sentAt = (Get-Date).ToString('yyyy-MM-ddTHH:mm:ss')
    }
    return (ConvertTo-Json -InputObject $obj -Depth 8)
}

# If a book has no cover image file, pull the embedded one out of the first audio file
function Add-EmbeddedCover($book) {
    if ($book.Cover -or -not $script:Ffmpeg -or $book.Audio.Count -eq 0) { return }
    $dest = Join-Path $script:TempDir ('cover_' + [guid]::NewGuid().ToString('N') + '.jpg')
    $r = Invoke-Native $script:Ffmpeg @('-v', 'error', '-y', '-i', $book.Audio[0].FullName, '-an', '-map', '0:v:0', '-frames:v', '1', '-vf', "scale='min(1000,iw)':-2", $dest)
    if ($r.Code -eq 0 -and (Test-Path -LiteralPath $dest)) {
        $len = (Get-Item -LiteralPath $dest).Length
        if ($len -gt 0) {
            [void]$book.Files.Add([pscustomobject]@{ Local = $dest; Remote = "$($book.RemoteDir)/cover.jpg"; Size = [int64]$len })
            $book.Bytes += $len
        }
    }
}
#endregion

#---------------------------------------------------------------------------
# region  STATUS + SELECTION
#---------------------------------------------------------------------------
function Update-Status($items) {
    foreach ($it in $items) {
        $have = 0; $total = 0
        foreach ($f in $it.Files) {
            if ($f.Remote -match '/cover\.(jpg|png)$' -and $it.Kind -eq 'book') { continue }
            $total++
            if ($script:PhoneFiles.ContainsKey($f.Remote) -and $script:PhoneFiles[$f.Remote] -eq $f.Size) { $have++ }
        }
        if ($total -gt 0 -and $have -eq $total) { $it.Status = 'ON PHONE' }
        elseif ($have -gt 0) { $it.Status = "PARTIAL $have/$total" }
        else { $it.Status = '' }
    }
}

# Phone-side units (for removing) built from what is actually on the phone
function Get-PhoneUnits([string]$base, [hashtable]$labels) {
    $units = @{}
    foreach ($p in $script:PhoneFiles.Keys) {
        if (-not $p.StartsWith($base + '/')) { continue }
        $name = $p.Substring($p.LastIndexOf('/') + 1)
        $dir = $p.Substring(0, $p.LastIndexOf('/'))
        if ($dir -eq $base) { continue }
        if (-not $units.ContainsKey($dir)) {
            $rel = $dir.Substring($base.Length + 1)
            $label = $rel.Replace('/', ' - ')
            if ($labels.ContainsKey($dir)) { $label = $labels[$dir] }
            $units[$dir] = [pscustomobject]@{ RemoteDir = $dir; Rel = $rel; Label = $label; Bytes = [int64]0; Count = 0; Artist = @(Split-Rel $rel)[0] }
        }
        $units[$dir].Bytes += $script:PhoneFiles[$p]
        if ($AudioExt -contains ([IO.Path]::GetExtension($name).ToLowerInvariant())) { $units[$dir].Count++ }
    }
    return @($units.Values | Sort-Object @{ E = { Get-NaturalKey $_.Label } })
}

# Shows a numbered list; returns the chosen items (maybe empty)
function Select-FromList($items, [scriptblock]$lineFn, [string]$heading) {
    $filter = ''
    while ($true) {
        $shown = @($items)
        if ($filter) { $shown = @($items | Where-Object { $_.Label -like "*$filter*" }) }
        Write-Title $heading
        if ($filter) { Write-Note "  Filter: '$filter'   (type C to clear)" }
        if ($shown.Count -eq 0) { Write-Warn2 '  Nothing matches.' }
        for ($i = 0; $i -lt $shown.Count; $i++) {
            $txt = & $lineFn $shown[$i]
            $num = '{0,4}  ' -f ($i + 1)
            Write-Host $num -NoNewline -ForegroundColor DarkCyan
            $c = 'Gray'
            if ($txt -match '\[ON PHONE\]') { $c = 'Green' } elseif ($txt -match '\[PARTIAL') { $c = 'Yellow' }
            Write-Host $txt -ForegroundColor $c
        }
        Write-Host ''
        Write-Note '  Numbers: 3   or 1,4,7   or 10-15      Words: filter the list (e.g. getty)'
        Write-Note '  A = all shown     C = clear filter     Enter = back'
        $ans = (Read-Host '  Choose').Trim()
        if ($ans -eq '') { return @() }
        if ($ans -ieq 'C') { $filter = ''; continue }
        if ($ans -ieq 'A') { return $shown }
        $nums = Parse-Numbers $ans $shown.Count
        if ($null -ne $nums) {
            if ($nums.Count -eq 0) { Write-Warn2 '  Those numbers are not on the list.'; continue }
            return @($nums | ForEach-Object { $shown[$_ - 1] })
        }
        $filter = $ans
    }
}
#endregion

#---------------------------------------------------------------------------
# region  SEND + REMOVE
#---------------------------------------------------------------------------
function Send-Items($items, [string]$what) {
    $items = @($items)
    if ($items.Count -eq 0) { return }
    $need = [int64]0; $fileCount = 0
    foreach ($it in $items) {
        foreach ($f in $it.Files) {
            if (-not ($script:PhoneFiles.ContainsKey($f.Remote) -and $script:PhoneFiles[$f.Remote] -eq $f.Size)) { $need += $f.Size; $fileCount++ }
        }
    }
    $free = Get-PhoneFree
    Write-Host ''
    Write-Host ("  {0} {1} selected - {2} to copy ({3} files)." -f $items.Count, $what, (Format-Size $need), $fileCount)
    if ($free -ge 0) {
        Write-Host ("  Phone free space: {0}" -f (Format-Size $free))
        if ($need -gt ($free - 1GB)) {
            Write-Bad '  Not enough room on the phone (keeping 1 GB spare). Remove something first or pick less.'
            Pause-Here; return
        }
    }
    if ($fileCount -eq 0 -and $what -ne 'audiobooks') { Write-Good '  Everything selected is already on the phone.'; Pause-Here; return }
    if (-not (Read-YesNo '  Copy to the phone now?')) { return }

    $done = 0; $failed = 0; $n = 0
    $sw = [Diagnostics.Stopwatch]::StartNew()
    foreach ($it in $items) {
        $n++
        Write-Host ("  [{0}/{1}] {2}" -f $n, $items.Count, $it.Label) -ForegroundColor Cyan
        if ($it.Kind -eq 'book') {
            Add-EmbeddedCover $it
            if (-not (Test-Path -LiteralPath $script:TempDir)) { [void](New-Item -ItemType Directory -Path $script:TempDir) }
            $jsonPath = Join-Path $script:TempDir 'book.json'
            [IO.File]::WriteAllText($jsonPath, (New-BookJson $it), (New-Object Text.UTF8Encoding($false)))
        }
        foreach ($f in $it.Files) {
            if ($script:PhoneFiles.ContainsKey($f.Remote) -and $script:PhoneFiles[$f.Remote] -eq $f.Size) { continue }
            $leaf = [IO.Path]::GetFileName($f.Local)
            Write-Progress -Activity "Copying $what" -Status $it.Label -CurrentOperation $leaf -PercentComplete ([Math]::Min(100, [int](100 * $done / [Math]::Max(1, $fileCount))))
            $r = Invoke-Adb push $f.Local $f.Remote
            if ($r.Code -eq 0) { $done++; $script:PhoneFiles[$f.Remote] = $f.Size }
            else { $failed++; Write-Bad ("     failed: $leaf  " + (($r.Out | Select-Object -Last 1) -join '')) }
        }
        if ($it.Kind -eq 'book') {
            $r = Invoke-Adb push (Join-Path $script:TempDir 'book.json') "$($it.RemoteDir)/book.json"
            if ($r.Code -ne 0) { $failed++; Write-Bad '     failed: book.json' }
        }
    }
    Write-Progress -Activity "Copying $what" -Completed
    Write-SyncStamp
    Grant-Storage -Quiet
    Write-Host ''
    if ($failed -eq 0) { Write-Good ("  Done - {0} files copied in {1:N0} min." -f $done, $sw.Elapsed.TotalMinutes) }
    else { Write-Warn2 ("  Finished with {0} failures ({1} copied). Run it again to retry just the missing files." -f $failed, $done) }
    Write-Note '  Open Listen on the phone - it picks up the changes by itself.'
    Pause-Here
}

function Remove-Units($units, [string]$base, [string]$what) {
    $units = @($units)
    if ($units.Count -eq 0) { return }
    $bytes = [int64]0; foreach ($u in $units) { $bytes += $u.Bytes }
    Write-Host ''
    foreach ($u in $units | Select-Object -First 15) { Write-Host "   - $($u.Label)" }
    if ($units.Count -gt 15) { Write-Host ("   ...and {0} more" -f ($units.Count - 15)) }
    Write-Host ("  Remove {0} {1} ({2}) from the phone? Your PC copies are not touched." -f $units.Count, $what, (Format-Size $bytes))
    if (-not (Read-YesNo '  Remove now?')) { return }
    $bad = 0
    foreach ($u in $units) {
        $r = Invoke-Phone ("rm -rf " + (Q $u.RemoteDir))
        if ($r.Code -ne 0) { $bad++; Write-Bad "   could not remove $($u.Label)" ; continue }
        Remove-EmptyParents ($u.RemoteDir.Substring(0, $u.RemoteDir.LastIndexOf('/'))) $base
        foreach ($k in @($script:PhoneFiles.Keys)) { if ($k.StartsWith($u.RemoteDir + '/')) { $script:PhoneFiles.Remove($k) } }
    }
    Write-SyncStamp
    if ($bad -eq 0) { Write-Good "  Removed. Playlists keep their entries - they come back if you send the music again." }
    Pause-Here
}
#endregion

#---------------------------------------------------------------------------
# region  MENUS
#---------------------------------------------------------------------------
function Get-ArtistGroups($albums) {
    $groups = @()
    foreach ($g in ($albums | Group-Object Artist)) {
        $items = @($g.Group)
        $songs = 0; $bytes = [int64]0; $on = 0; $part = 0
        foreach ($a in $items) { $songs += $a.Songs; $bytes += $a.Bytes; if ($a.Status -eq 'ON PHONE') { $on++ } elseif ($a.Status) { $part++ } }
        $st = ''
        if ($on -eq $items.Count) { $st = 'ON PHONE' } elseif ($on -gt 0 -or $part -gt 0) { $st = "PARTIAL $on/$($items.Count) albums" }
        $groups += [pscustomobject]@{ Label = $g.Name; Albums = $items; Songs = $songs; Bytes = $bytes; Status = $st }
    }
    return @($groups | Sort-Object @{ E = { Get-NaturalKey $_.Label } })
}

function Tag($status) { if ($status) { return "  [$status]" } return '' }

function Menu-AddMusic {
    while ($true) {
        $albums = Get-MusicAlbums
        Update-Status $albums
        Write-Title 'ADD MUSIC TO PHONE'
        Write-Host '   1  Choose by artist'
        Write-Host '   2  Choose by album'
        Write-Host '   3  Send everything that is not on the phone yet'
        Write-Host '   Enter = back'
        $c = (Read-Host '  Choose').Trim()
        if ($c -eq '') { return }
        if ($c -eq '1') {
            $groups = Get-ArtistGroups $albums
            $pick = Select-FromList $groups { param($g) "{0}   ({1} albums, {2} songs, {3}){4}" -f $g.Label, $g.Albums.Count, $g.Songs, (Format-Size $g.Bytes), (Tag $g.Status) } 'ADD MUSIC - ARTISTS'
            $sel = @(); foreach ($g in $pick) { $sel += $g.Albums }
            Send-Items $sel 'albums'
        } elseif ($c -eq '2') {
            $pick = Select-FromList $albums { param($a) "{0}   ({1} songs, {2}){3}" -f $a.Label, $a.Songs, (Format-Size $a.Bytes), (Tag $a.Status) } 'ADD MUSIC - ALBUMS'
            Send-Items $pick 'albums'
        } elseif ($c -eq '3') {
            Send-Items @($albums | Where-Object { $_.Status -ne 'ON PHONE' }) 'albums'
        }
    }
}

function Menu-RemoveMusic {
    while ($true) {
        $units = Get-PhoneUnits $PhoneMusic @{}
        Write-Title 'REMOVE MUSIC FROM PHONE'
        if ($units.Count -eq 0) { Write-Warn2 '  There is no music on the phone.'; Pause-Here; return }
        Write-Host '   1  Choose by artist'
        Write-Host '   2  Choose by album'
        Write-Host '   3  Remove ALL music from the phone'
        Write-Host '   Enter = back'
        $c = (Read-Host '  Choose').Trim()
        if ($c -eq '') { return }
        if ($c -eq '1') {
            $groups = @()
            foreach ($g in ($units | Group-Object Artist)) {
                $b = [int64]0; $n = 0; foreach ($u in $g.Group) { $b += $u.Bytes; $n += $u.Count }
                $groups += [pscustomobject]@{ Label = $g.Name; Units = @($g.Group); Bytes = $b; Count = $n }
            }
            $groups = @($groups | Sort-Object @{ E = { Get-NaturalKey $_.Label } })
            $pick = Select-FromList $groups { param($g) "{0}   ({1} albums, {2} songs, {3})" -f $g.Label, $g.Units.Count, $g.Count, (Format-Size $g.Bytes) } 'REMOVE MUSIC - ARTISTS ON PHONE'
            $sel = @(); foreach ($g in $pick) { $sel += $g.Units }
            Remove-Units $sel $PhoneMusic 'albums'
        } elseif ($c -eq '2') {
            $pick = Select-FromList $units { param($u) "{0}   ({1} songs, {2})" -f $u.Label, $u.Count, (Format-Size $u.Bytes) } 'REMOVE MUSIC - ALBUMS ON PHONE'
            Remove-Units $pick $PhoneMusic 'albums'
        } elseif ($c -eq '3') {
            Remove-Units $units $PhoneMusic 'albums'
        }
    }
}

function Menu-AddBooks {
    Write-Note '  Reading the Audio Books folder...'
    $books = Get-Books
    Update-Status $books
    $pick = Select-FromList $books { param($b) "{0}   ({1}){2}" -f $b.Label, (Format-Size $b.Bytes), (Tag $b.Status) } 'ADD AUDIOBOOKS TO PHONE'
    Send-Items $pick 'audiobooks'
}

function Get-BookLabelMap {
    $map = @{}
    if (Test-Path -LiteralPath $TitlesCsv) {
        foreach ($row in (Import-Csv -LiteralPath $TitlesCsv -Encoding UTF8)) {
            if (-not $row.Folder) { continue }
            $l = $row.Title; if ($row.Author -and $row.Author -ne $row.Title) { $l = "$($row.Author) - $($row.Title)" }
            if ($row.Narrator) { $l += " (read by $($row.Narrator))" }
            $map[(To-Remote $PhoneBooks $row.Folder)] = $l
        }
    }
    return $map
}

function Menu-RemoveBooks {
    $units = Get-PhoneUnits $PhoneBooks (Get-BookLabelMap)
    if ($units.Count -eq 0) { Write-Title 'REMOVE AUDIOBOOKS'; Write-Warn2 '  There are no audiobooks on the phone.'; Pause-Here; return }
    $pick = Select-FromList $units { param($u) "{0}   ({1})" -f $u.Label, (Format-Size $u.Bytes) } 'REMOVE AUDIOBOOKS FROM PHONE'
    Remove-Units $pick $PhoneBooks 'audiobooks'
    Write-Note '  (Listening positions for removed books are kept, so a re-sent book resumes where you were.)'
}

function Menu-EditTitles {
    Write-Title 'AUDIOBOOK TITLES'
    Write-Note '  Reading the Audio Books folder...'
    $books = Get-Books      # makes sure the CSV exists and has every book
    Write-Host "  The titles file is: $TitlesCsv"
    Write-Host '  Edit Title / Author / Series / Number / Narrator / Year, save, and close it.'
    Write-Host '  Leave the Folder column alone - that is how each row finds its book.'
    try { Start-Process -FilePath $TitlesCsv } catch { Write-Warn2 '  Could not open it automatically - open it yourself.' }
    [void](Read-Host '  Press Enter here when you have saved and closed the file')
    $books = Get-Books
    Update-Status $books
    $onPhone = @($books | Where-Object { $_.Status -eq 'ON PHONE' })
    if ($onPhone.Count -eq 0) { Write-Note '  No books on the phone to update. New titles apply when you send books.'; Pause-Here; return }
    if (-not (Read-YesNo ("  Update the titles of the {0} books already on the phone?" -f $onPhone.Count))) { return }
    if (-not (Test-Path -LiteralPath $script:TempDir)) { [void](New-Item -ItemType Directory -Path $script:TempDir) }
    $jsonPath = Join-Path $script:TempDir 'book.json'
    foreach ($b in $onPhone) {
        [IO.File]::WriteAllText($jsonPath, (New-BookJson $b), (New-Object Text.UTF8Encoding($false)))
        $r = Invoke-Adb push $jsonPath "$($b.RemoteDir)/book.json"
        if ($r.Code -eq 0) { Write-Host "   updated  $($b.Label)" } else { Write-Bad "   failed   $($b.Label)" }
    }
    Write-SyncStamp
    Pause-Here
}

function Menu-Show {
    Write-Title "WHAT'S ON THE PHONE"
    $m = Get-PhoneUnits $PhoneMusic @{}
    $b = Get-PhoneUnits $PhoneBooks (Get-BookLabelMap)
    $mb = [int64]0; $ms = 0; foreach ($u in $m) { $mb += $u.Bytes; $ms += $u.Count }
    $bb = [int64]0; foreach ($u in $b) { $bb += $u.Bytes }
    Write-Host ("  MUSIC: {0} albums, {1} songs, {2}" -f $m.Count, $ms, (Format-Size $mb)) -ForegroundColor Cyan
    foreach ($u in $m) { Write-Host ("     {0}  ({1} songs)" -f $u.Label, $u.Count) }
    Write-Host ''
    Write-Host ("  AUDIOBOOKS: {0} books, {1}" -f $b.Count, (Format-Size $bb)) -ForegroundColor Cyan
    foreach ($u in $b) { Write-Host ("     {0}  ({1})" -f $u.Label, (Format-Size $u.Bytes)) }
    $free = Get-PhoneFree
    if ($free -ge 0) { Write-Host ''; Write-Host ("  Free space on phone: {0}" -f (Format-Size $free)) }
    Pause-Here
}

function Menu-Backup {
    Write-Title 'BACK UP PLAYLISTS + POSITIONS'
    $r = Invoke-Phone ("ls " + (Q $PhoneState))
    if ($r.Code -ne 0) { Write-Warn2 '  Nothing to back up yet (the Listen app has not saved anything).'; Pause-Here; return }
    $dest = Join-Path $BackupRoot ((Get-Date).ToString('yyyy-MM-dd_HHmm'))
    [void](New-Item -ItemType Directory -Path $dest -Force)
    $r = Invoke-Adb pull "$PhoneState/." $dest
    if ($r.Code -eq 0) { Write-Good "  Saved to $dest" } else { Write-Bad ('  Backup failed: ' + ($r.Out -join ' ')) }
    Pause-Here
}

function Menu-Restore {
    Write-Title 'RESTORE PLAYLISTS + POSITIONS'
    if (-not (Test-Path -LiteralPath $BackupRoot)) { Write-Warn2 '  No backups found.'; Pause-Here; return }
    $dirs = @(Get-ChildItem -LiteralPath $BackupRoot -Directory | Sort-Object Name -Descending | ForEach-Object { [pscustomobject]@{ Label = $_.Name; Path = $_.FullName } })
    if ($dirs.Count -eq 0) { Write-Warn2 '  No backups found.'; Pause-Here; return }
    $pick = @(Select-FromList $dirs { param($d) $d.Label } 'PICK A BACKUP (newest first)')
    if ($pick.Count -ne 1) { if ($pick.Count -gt 1) { Write-Warn2 '  Pick just one.'; Pause-Here }; return }
    Write-Warn2 '  Close the Listen app on the phone first (swipe it away), or it may save over this.'
    if (-not (Read-YesNo "  Replace the phone's playlists and positions with $($pick[0].Label)?")) { return }
    [void](Invoke-Phone ("mkdir -p " + (Q $PhoneState)))
    $bad = 0
    foreach ($f in (Get-ChildItem -LiteralPath $pick[0].Path -File)) {
        $r = Invoke-Adb push $f.FullName "$PhoneState/$($f.Name)"
        if ($r.Code -ne 0) { $bad++ }
    }
    [void](Invoke-Phone "am force-stop $AppPackage")
    if ($bad -eq 0) { Write-Good '  Restored. Open Listen to see it.' } else { Write-Bad "  $bad files failed to restore." }
    Pause-Here
}

function Menu-Main {
    while ($true) {
        Refresh-PhoneFiles
        $free = Get-PhoneFree
        $mCount = @($script:PhoneFiles.Keys | Where-Object { $_.StartsWith("$PhoneMusic/") -and ($AudioExt -contains ([IO.Path]::GetExtension($_).ToLowerInvariant())) }).Count
        $bCount = @($script:PhoneFiles.Keys | Where-Object { $_.StartsWith("$PhoneBooks/") -and $_.EndsWith('/book.json') }).Count
        $app = 'not installed yet'; if (Test-AppInstalled) { $app = 'installed' }
        Write-Title 'LISTEN - PHONE SYNC'
        $freeTxt = 'unknown'; if ($free -ge 0) { $freeTxt = Format-Size $free }
        Write-Note ("  Phone {0}   free space {1}   Listen app {2}" -f $script:Serial, $freeTxt, $app)
        Write-Host ''
        Write-Host ("  MUSIC  ({0})      on phone: {1} songs" -f $MusicRoot, $mCount) -ForegroundColor Cyan
        Write-Host '   1  Add music to phone'
        Write-Host '   2  Remove music from phone'
        Write-Host ''
        Write-Host ("  AUDIOBOOKS  ({0})      on phone: {1} books" -f $BooksRoot, $bCount) -ForegroundColor Cyan
        Write-Host '   3  Add audiobooks to phone'
        Write-Host '   4  Remove audiobooks from phone'
        Write-Host '   5  Edit audiobook titles / series / narrators'
        Write-Host ''
        Write-Host '  OTHER' -ForegroundColor Cyan
        Write-Host "   6  Show what's on the phone"
        Write-Host '   7  Back up playlists + listening positions to PC'
        Write-Host '   8  Restore playlists + positions from a backup'
        Write-Host '   9  Give the Listen app access to its files (run once after installing)'
        Write-Host '   Q  Quit'
        $c = (Read-Host '  Choose').Trim()
        switch -Regex ($c) {
            '^1$' { Menu-AddMusic }
            '^2$' { Menu-RemoveMusic }
            '^3$' { Menu-AddBooks }
            '^4$' { Menu-RemoveBooks }
            '^5$' { Menu-EditTitles }
            '^6$' { Menu-Show }
            '^7$' { Menu-Backup }
            '^8$' { Menu-Restore }
            '^9$' { Grant-Storage; Pause-Here }
            '^[qQ]$' { return }
        }
        if (-not (Connect-Phone)) { Pause-Here; if (-not (Connect-Phone)) { return } }
    }
}
#endregion

#---------------------------------------------------------------------------
# region  START
#---------------------------------------------------------------------------
$script:Adb = Find-Adb
if (-not $script:Adb) {
    Write-Bad 'Could not find adb.exe (Android platform-tools).'
    Write-Host 'Put the full path to adb.exe in the $AdbPath setting near the top of this file,'
    Write-Host 'or add the platform-tools folder to your PATH.'
    Pause-Here; exit 1
}
$script:Ffprobe = Find-Ff 'ffprobe'
$script:Ffmpeg = Find-Ff 'ffmpeg'
if (-not (Test-Path -LiteralPath $script:TempDir)) { [void](New-Item -ItemType Directory -Path $script:TempDir) }

Write-Title 'LISTEN - PHONE SYNC'
Write-Note "  adb: $($script:Adb)"
if ($script:Ffprobe) { Write-Note "  ffprobe: $($script:Ffprobe)  (audiobook chapters on)" } else { Write-Note '  ffprobe not found - m4b chapter lists will be skipped (the app still plays them).' }
while (-not (Connect-Phone)) {
    if (-not (Read-YesNo '  Try again?')) { exit 0 }
}
[void](Invoke-Phone ("mkdir -p " + (Q $PhoneMusic) + " " + (Q $PhoneBooks) + " " + (Q $PhoneState)))
Menu-Main
try { Remove-Item -LiteralPath $script:TempDir -Recurse -Force -ErrorAction SilentlyContinue } catch {}
Write-Host 'Bye.'
#endregion
