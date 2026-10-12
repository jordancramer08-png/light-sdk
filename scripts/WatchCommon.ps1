#===========================================================================
#  Shared code for the Watch PC scripts (Watch-Phone-Sync, Watch-Library-Report,
#  Watch-Smoke-Test, Watch-Why-Wont-It-Play). Dot-source it:
#      . (Join-Path $PSScriptRoot 'WatchCommon.ps1')
#  Settings, the source-folder scan, the library check (ffprobe, cached in
#  private\probe-cache.tsv), fixing (ffmpeg), TMDB, json, delivery to the
#  phone or the dry-run folder, menus, sending and the PC staging folder.
#  Nothing here ever writes inside a source folder (Assert-SafeTarget).
#===========================================================================
#---------------------------------------------------------------------------
# region  SETTINGS
#---------------------------------------------------------------------------
# Source folders, scanned in this order. Missing ones are skipped. Add a folder here.
$SourceRoots = @('D:\Movies', 'D:\Marvel Entertainment', 'D:\TV')
# Folders that only sort things; never used as a collection or show name
$PlainFolders = @('films', 'movies', 'tv', 'series', 'series & specials', 'specials', 'duology', 'trilogy')
$PhoneRoot  = '/sdcard/Watch'
$PhoneTmp   = '/data/local/tmp/watch-xfer'
$AppPackage = 'com.jordan.watch'
$CapBytes   = 50GB
$AdbPath    = ''          # leave blank to find adb.exe automatically

if ($env:WATCH_SOURCES) { $SourceRoots = @($env:WATCH_SOURCES -split ';' | Where-Object { $_ }) }
if ($env:WATCH_ADB)         { $AdbPath = $env:WATCH_ADB }
$DryRoot = $null
if ($env:WATCH_DRY_RUN) { $DryRoot = Join-Path $env:WATCH_DRY_RUN 'Watch' }

$RepoRoot = Split-Path -Parent $PSScriptRoot
$LogDir   = Join-Path $RepoRoot 'logs'
$Stamp    = Get-Date -Format 'yyyyMMdd-HHmmss'
$TmdbKeyFile = Join-Path $RepoRoot 'private\tmdb-key.txt'
$TmdbCache   = Join-Path $RepoRoot 'private\tmdb-cache'
$ScanCache   = Join-Path $RepoRoot 'private\scan-cache'
$ProbeCacheFile = Join-Path $RepoRoot 'private\probe-cache.tsv'
$SpeedFile   = Join-Path $RepoRoot 'private\encode-speed.json'
if ($env:WATCH_PROBE_CACHE) { $ProbeCacheFile = $env:WATCH_PROBE_CACHE }
# PC staging folder for pre-converted titles (Watch-Phone-Sync option 6)
$StageRoot   = 'D:\Watch-Staging'
if ($env:WATCH_STAGING) { $StageRoot = $env:WATCH_STAGING }
$ProbeWorkers = 4          # ffprobe runs at once while checking many files
$BackupRoot  = Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'Watch-Backups'
if ($env:WATCH_BACKUP_DIR) { $BackupRoot = $env:WATCH_BACKUP_DIR }
$WorkDir  = Join-Path ([IO.Path]::GetTempPath()) "watch-$Stamp"

$VideoExts   = @('.mp4', '.m4v', '.mkv', '.webm', '.mov', '.avi', '.wmv', '.mpg', '.mpeg', '.ts', '.flv')
. (Join-Path $PSScriptRoot 'WatchSafe.ps1')     # the Watch-safe rules (CLAUDE.md section 8)
. (Join-Path $PSScriptRoot 'LibraryRules.ps1')  # report, staging, smoke test, why-won't-it-play rules
# Bitrates for the smaller qualities (picture + sound, kbit/s)
$Qualities = @{
    'Original' = $null
    '1080p'    = @{ Height = 1080; VideoK = 3850; AudioK = 160 }
    '720p'     = @{ Height = 720;  VideoK = 1850; AudioK = 128 }
}

$script:Adb = $null
$script:Serial = $null
$script:Ffmpeg = $null
$script:Ffprobe = $null
#endregion

#---------------------------------------------------------------------------
# region  SMALL HELPERS  (the same as Listen's scripts)
#---------------------------------------------------------------------------
function Write-Title($text) {
    Write-Host ''
    Write-Host ('=' * 70) -ForegroundColor DarkCyan
    Write-Host ("  " + $text) -ForegroundColor Cyan
    Write-Host ('=' * 70) -ForegroundColor DarkCyan
}
function Write-Step($text)  { Write-Host ''; Write-Host ">> $text" -ForegroundColor Cyan }
function Write-Note($text)  { Write-Host $text -ForegroundColor DarkGray }
function Write-Good($text)  { Write-Host $text -ForegroundColor Green }
function Write-Warn2($text) { Write-Host $text -ForegroundColor Yellow }
function Write-Bad($text)   { Write-Host $text -ForegroundColor Red }

function Read-YesNo($prompt) {
    $a = Read-Host "$prompt (Y/N)"
    return ($a -match '^\s*y')
}

# Single-quote a string for the phone's shell
function Q([string]$s) { return "'" + $s.Replace("'", "'\''") + "'" }

# 1234567890 -> "1.1 GB"
function Format-Size([double]$bytes) {
    if ($bytes -ge 1GB) { return ('{0:N1} GB' -f ($bytes / 1GB)) }
    if ($bytes -ge 1MB) { return ('{0:N0} MB' -f ($bytes / 1MB)) }
    return ('{0:N0} KB' -f ($bytes / 1KB))
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

function Write-Utf8([string]$path, [string]$text) {
    Assert-SafeTarget $path
    $dir = Split-Path -Parent $path
    if ($dir -and -not (Test-Path -LiteralPath $dir)) { [void](New-Item -ItemType Directory -Path $dir -Force) }
    [IO.File]::WriteAllText($path, $text, (New-Object Text.UTF8Encoding($false)))
}

# Every file this script writes goes through here: never inside the PC library.
function Assert-SafeTarget([string]$path) {
    $full = [IO.Path]::GetFullPath($path)
    foreach ($root in $SourceRoots) {
        $r = [IO.Path]::GetFullPath($root).TrimEnd('\') + '\'
        if ($full.StartsWith($r, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to write inside the PC library: $full"
        }
    }
}

# "Accents and case don't matter": Pokemon matches the accented spelling
function Fold([string]$s) {
    $d = $s.Normalize([Text.NormalizationForm]::FormD)
    $sb = New-Object Text.StringBuilder
    foreach ($ch in $d.ToCharArray()) {
        if ([Globalization.CharUnicodeInfo]::GetUnicodeCategory($ch) -ne 'NonSpacingMark') { [void]$sb.Append($ch) }
    }
    return $sb.ToString().ToLowerInvariant()
}
#endregion

#---------------------------------------------------------------------------
# region  TOOLS  (adb and ffmpeg)
#---------------------------------------------------------------------------
function Find-Adb {
    if ($AdbPath -and (Test-Path -LiteralPath $AdbPath)) { return $AdbPath }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $cands = New-Object System.Collections.Generic.List[string]
    foreach ($v in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) { if ($v) { $cands.Add((Join-Path $v 'platform-tools\adb.exe')) } }
    if ($env:LOCALAPPDATA) { $cands.Add((Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')) }
    if ($env:USERPROFILE) {
        $cands.Add((Join-Path $env:USERPROFILE 'platform-tools\adb.exe'))
        $cands.Add((Join-Path $env:USERPROFILE 'Downloads\platform-tools\adb.exe'))
    }
    $cands.Add('C:\platform-tools\adb.exe')
    $cands.Add('C:\Android\platform-tools\adb.exe')
    foreach ($c in $cands) { if (Test-Path -LiteralPath $c) { return $c } }
    return $null
}

function Find-Ffmpeg([string]$name) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $links = Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Links\$name.exe"
    if (Test-Path -LiteralPath $links) { return $links }
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

# Exactly one phone, allowed. Sets $script:Serial. Returns $true when ready.
function Connect-Phone {
    $script:Serial = $null
    if (-not $script:Adb) { return $false }
    $r = Invoke-Adb devices
    $ready = @(); $unauth = @()
    foreach ($line in $r.Out) {
        if ($line -match '^(\S+)\s+device$') { $ready += $Matches[1] }
        elseif ($line -match '^(\S+)\s+(unauthorized|offline)') { $unauth += $Matches[1] }
    }
    if ($ready.Count -gt 1) {
        Write-Bad "  $($ready.Count) phones/devices are connected. Unplug all but the Light Phone and run this again."
        return $false
    }
    if ($ready.Count -eq 0) {
        if ($unauth.Count -gt 0) {
            Write-Warn2 '  The phone is connected but has not allowed this PC yet.'
            Write-Warn2 '  Unlock the phone and tap "Allow" on the USB debugging prompt, then try again.'
        } else {
            Write-Warn2 '  No phone found. Plug the Light Phone in with a data USB cable (USB debugging on).'
        }
        return $false
    }
    $script:Serial = $ready[0]
    return $true
}
#endregion

#---------------------------------------------------------------------------
# region  LIBRARY SCAN  (the source folders, read only)
#---------------------------------------------------------------------------

# "Batman v Superman - Dawn of Justice (2016)" -> title "Batman v Superman: Dawn of Justice", year 2016
function Split-TitleYear([string]$name) {
    $n = ($name -replace '\s+', ' ').Trim()
    $year = $null
    if ($n -match '^(.*?)\s*\((\d{4})\)\s*$') { $n = $Matches[1].Trim(); $year = [int]$Matches[2] }
    $title = $n -replace ' - ', ': '
    return @{ Base = $n; Title = $title; Year = $year }
}

# Folder name for the phone: "Title (Year)", with characters Android dislikes removed
function Get-SafeName([string]$s) {
    $s = $s -replace '[\\/:*?"<>|]', ' '
    return ($s -replace '\s+', ' ').Trim().TrimEnd('.')
}

# "03 - Thor (2011)" -> number 3, rest "Thor (2011)"
function Split-Number([string]$name) {
    if ($name -match '^(\d{1,3})\s*-\s*(.+)$') { return @{ Number = [int]$Matches[1]; Rest = $Matches[2].Trim() } }
    return @{ Number = $null; Rest = $name.Trim() }
}

function Test-PlainFolder([string]$name) { return ($PlainFolders -contains $name.ToLowerInvariant()) }

# Collection from the folders above a movie (title folder already removed):
#   Phase One (2008-2012)              -> "Phase One (2008-2012)"
#   X-Men (Fox)\Films                  -> "X-Men (Fox)"        (plain folders are skipped)
#   Spider-Man (Sony)\Tobey            -> "Spider-Man (Sony) - Tobey"
# The first folder under a source (e.g. "Marvel Cinematic Universe") is only
# used when it is the only one, as in D:\Movies\Star Wars.
function Get-FolderCollection([string[]]$dirs) {
    $i = $dirs.Count - 1
    while ($i -ge 0 -and (Test-PlainFolder $dirs[$i])) { $i-- }
    if ($i -lt 0) { return $null }
    $name = (Split-Number $dirs[$i]).Rest
    $j = $i - 1
    while ($j -ge 0 -and (Test-PlainFolder $dirs[$j])) { $j-- }
    if ($j -ge 1) { $name = (Split-Number $dirs[$j]).Rest + ' - ' + $name }
    return $name
}

# Show folder for an episode, e.g.
#   ...\05 - Loki (2021) - Season 2\Loki - S02E01.mkv  -> "Loki (2021)"
#   ...\Iron Man (1994)\Season 1\Iron Man - S01E01.mkv  -> "Iron Man (1994)"
#   ...\Agents of S.H.I.E.L.D. Slingshot (2016)\...S01E01.mp4 -> that folder
# Falls back to the start of the file name ("Loki - S02E01 - ...").
function Get-ShowFolder([string[]]$dirs, [string]$fromFile) {
    if ($dirs.Count -gt 0) {
        $d = (Split-Number $dirs[-1]).Rest
        if ($d -match '^(.+?)\s+-\s+Season\s*\d+$') { return $Matches[1] }
        if ($d -match '^(Season|Series)\s*\d+$|^Specials$') {
            if ($dirs.Count -gt 1 -and -not (Test-PlainFolder $dirs[-2])) { return (Split-Number $dirs[-2]).Rest }
        } elseif (-not (Test-PlainFolder $d)) { return $d }
    }
    if ($fromFile) { return $fromFile }
    return 'Unknown show'
}

# Every video file under one source folder, fast (no PowerShell provider).
function Get-SourceFiles([string]$root) {
    try {
        $dir = New-Object IO.DirectoryInfo $root
        return @($dir.EnumerateFiles('*', [IO.SearchOption]::AllDirectories) |
            Where-Object { $VideoExts -contains $_.Extension.ToLowerInvariant() })
    } catch {
        return @(Get-ChildItem -LiteralPath $root -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { $VideoExts -contains $_.Extension.ToLowerInvariant() })
    }
}

function New-MovieItem($f, [string]$root, [string[]]$dirs) {
    $n = Split-Number ([IO.Path]::GetFileNameWithoutExtension($f.Name))
    $number = $n.Number
    # Per-title folder ("01 - Iron Man (2008)\Iron Man (2008).mp4"): its number counts, its name doesn't
    if ($dirs.Count -gt 0) {
        $last = Split-Number $dirs[-1]
        if ((Get-MatchKey $last.Rest) -eq (Get-MatchKey $n.Rest)) {
            if ($null -eq $number) { $number = $last.Number }
            $dirs = @($dirs | Select-Object -First ($dirs.Count - 1))
        }
    }
    $collection = Get-FolderCollection $dirs
    $ty = Split-TitleYear $n.Rest
    $folder = Get-SafeName $ty.Base
    if ($ty.Year) { $folder = "$folder ($($ty.Year))" }
    $group = '_Singles'
    if ($collection) { $group = Get-SafeName $collection }
    $label = $ty.Title
    if ($ty.Year) { $label = "$label ($($ty.Year))" }
    if ($collection) {
        $c = $collection
        if ($number) { $c = "$collection #$number" }
        $label = "$label  [$c]"
    }
    return [pscustomobject]@{
        Kind = 'Movie'; Label = $label; Search = (Fold "$label $($f.Name)")
        Title = $ty.Title; Year = $ty.Year; Collection = $collection; Number = $number
        Id = "$group/$folder"; PhoneDir = "Movies/$group/$folder"; Files = @($f); Bytes = [double]$f.Length
        Source = $f.FullName; Root = $root; DupKey = "m|$(Get-MatchKey $ty.Title)|$($ty.Year)"
        OnPhone = $false; Selected = $false
    }
}

# One item per season, grouping the episodes found in one source folder.
function New-SeasonItems($eps, [string]$root) {
    $items = New-Object System.Collections.Generic.List[object]
    foreach ($g in ($eps | Group-Object { "$($_.Show)|$($_.Season)" } | Sort-Object Name)) {
        $first = $g.Group[0]
        $list = @($g.Group | Sort-Object Episode)
        $bytes = 0.0; foreach ($e in $list) { $bytes += $e.File.Length }
        $ty = Split-TitleYear $first.Show
        $showName = Get-SafeName $first.Show
        $seasonDir = 'Season {0:D2}' -f $first.Season
        $label = "$($ty.Title) - Season $($first.Season) ($($list.Count) episodes)  [TV]"
        $items.Add([pscustomobject]@{
            Kind = 'Season'; Label = $label; Search = (Fold $label)
            Title = $ty.Title; Year = $ty.Year; ShowName = $showName; Season = $first.Season; Episodes = $list
            Id = "$showName/$seasonDir"; PhoneDir = "TV/$showName/$seasonDir"
            Files = @($list | ForEach-Object { $_.File }); Bytes = $bytes
            Source = $first.File.DirectoryName; Root = $root
            DupKey = "s|$(Get-MatchKey $ty.Title)|$($ty.Year)|$($first.Season)"
            OnPhone = $false; Selected = $false
        })
    }
    return $items
}

# Reads one source folder. Files with SxxExx in the name are TV episodes; the rest are movies.
function Read-Source([string]$root) {
    $rootFull = [IO.Path]::GetFullPath($root).TrimEnd('\')
    $movies = New-Object System.Collections.Generic.List[object]
    $eps = New-Object System.Collections.Generic.List[object]
    foreach ($f in (Get-SourceFiles $rootFull | Sort-Object FullName)) {
        $rel = $f.DirectoryName.Substring($rootFull.Length).Trim('\')
        $dirs = @(); if ($rel) { $dirs = @($rel -split '\\') }
        $stem = [IO.Path]::GetFileNameWithoutExtension($f.Name)
        if ($stem -match '^(.*?)[\s\-_.]*[Ss](\d{1,3})[Ee](\d{1,4})') {
            $fromFile = $Matches[1].Trim()
            $s = [int]$Matches[2]; $e = [int]$Matches[3]
            $epTitle = "Episode $e"
            if ($stem -match '[Ss]\d{1,3}[Ee]\d{1,4}\s*-\s*(.+)$') { $epTitle = ($Matches[1] -replace ' - ', ': ').Trim() }
            $eps.Add([pscustomobject]@{ Show = (Get-ShowFolder $dirs $fromFile); Season = $s; Episode = $e; Title = $epTitle; File = $f })
        } else {
            $movies.Add((New-MovieItem $f $rootFull $dirs))
        }
    }
    foreach ($it in (New-SeasonItems $eps $rootFull)) { $movies.Add($it) }
    return $movies.ToArray()
}

# Saved scan per source folder, reused while no folder in it has changed.
# Keyed by every folder's last-write time (adding/removing/renaming a file changes it).
function Get-SourceStamp([string]$root) {
    $sb = New-Object Text.StringBuilder
    $dir = New-Object IO.DirectoryInfo $root
    [void]$sb.Append($dir.LastWriteTimeUtc.Ticks)
    foreach ($d in $dir.EnumerateDirectories('*', [IO.SearchOption]::AllDirectories)) {
        [void]$sb.Append("|$($d.FullName)=$($d.LastWriteTimeUtc.Ticks)")
    }
    return (Get-Hash $sb.ToString())
}

function Get-Library {
    $all = New-Object System.Collections.Generic.List[object]
    if (-not (Test-Path -LiteralPath $ScanCache)) { [void](New-Item -ItemType Directory -Path $ScanCache -Force) }
    foreach ($root in $SourceRoots) {
        if (-not (Test-Path -LiteralPath $root)) { Write-Note "  $root - not found, skipped."; continue }
        $watch = [Diagnostics.Stopwatch]::StartNew()
        $stamp = Get-SourceStamp $root
        $cacheFile = Join-Path $ScanCache ((Get-Hash ([IO.Path]::GetFullPath($root).ToLowerInvariant())) + '.xml')
        $items = $null
        if (Test-Path -LiteralPath $cacheFile) {
            try {
                $saved = Import-Clixml -LiteralPath $cacheFile
                if ($saved.Stamp -eq $stamp) { $items = @(Restore-Items $saved.Items) }
            } catch { $items = $null }
        }
        $how = 'saved scan'
        if ($null -eq $items) {
            $how = 'scanned'
            $items = @(Read-Source $root)
            try { Export-Clixml -LiteralPath $cacheFile -InputObject @{ Stamp = $stamp; Items = @(Save-Items $items) } } catch {}
        }
        foreach ($it in $items) { $all.Add($it) }
        Write-Note ("  {0} - {1} titles/seasons ({2}, {3:N1} s)" -f $root, $items.Count, $how, $watch.Elapsed.TotalSeconds)
    }
    Find-Duplicates $all
    return $all
}

# The scan is saved as plain text values (file paths, not file objects), so it loads fast.
function Save-Items($items) {
    foreach ($it in $items) {
        $o = @{}
        foreach ($p in $it.PSObject.Properties) { $o[$p.Name] = $p.Value }
        $o.Files = @($it.Files | ForEach-Object { $_.FullName })
        if ($it.Kind -eq 'Season') {
            $o.Episodes = @($it.Episodes | ForEach-Object {
                @{ Show = $_.Show; Season = $_.Season; Episode = $_.Episode; Title = $_.Title; File = $_.File.FullName } })
        }
        $o
    }
}

function Restore-Items($saved) {
    foreach ($o in $saved) {
        $h = @{}
        foreach ($k in $o.Keys) { $h[$k] = $o[$k] }
        $h.Files = @($o.Files | ForEach-Object { New-Object IO.FileInfo $_ })
        if ($h.Kind -eq 'Season') {
            $h.Episodes = @($o.Episodes | ForEach-Object {
                [pscustomobject]@{ Show = $_.Show; Season = [int]$_.Season; Episode = [int]$_.Episode; Title = $_.Title
                    File = (New-Object IO.FileInfo $_.File) } })
        }
        $h.Selected = $false; $h.OnPhone = $false
        [pscustomobject]$h
    }
}

# The same title in two places: warn once here; the menu won't let both be picked.
function Find-Duplicates($items) {
    $groups = @($items | Group-Object DupKey | Where-Object { $_.Count -gt 1 })
    if ($groups.Count -eq 0) { return }
    Write-Warn2 "  $($groups.Count) title(s) are in more than one place. Only one copy of each can be sent:"
    foreach ($g in $groups) {
        Write-Warn2 "    $($g.Group[0].Label)"
        foreach ($it in $g.Group) { Write-Note "      $($it.Source)" }
        foreach ($it in $g.Group) { $it.Label = "$($it.Label)  (also elsewhere)" }
    }
}

function Ensure-TvFolder {
    $TvRoot = @($SourceRoots | Where-Object { (Split-Path -Leaf $_) -eq 'TV' }) | Select-Object -First 1
    if ($env:WATCH_SOURCES -or -not $TvRoot -or (Test-Path -LiteralPath $TvRoot)) { return }
    [void](New-Item -ItemType Directory -Path $TvRoot)
    $readme = @"
Watch - TV shows for the Light Phone
====================================

Put TV shows here, one folder per show, one folder per season:

  D:\TV\<Show>\Season 01\<Show> - S01E01 - <Episode title>.mp4

Example:
  D:\TV\The Office\Season 01\The Office - S01E01 - Pilot.mp4
  D:\TV\The Office\Season 01\The Office - S01E02 - Diversity Day.mp4

The S01E01 part is what matters: season number, then episode number.
Watch-Phone-Sync.cmd lists each season so you can send it to the phone.
It never changes anything in this folder.
"@
    [IO.File]::WriteAllText((Join-Path $TvRoot 'README.txt'), $readme.Replace("`n", "`r`n"), (New-Object Text.UTF8Encoding($false)))
    Write-Good "  Made $TvRoot with a README.txt explaining how to name TV files."
}
#endregion

#---------------------------------------------------------------------------
# region  PHONE OR DRY-RUN FOLDER  (what is there, how much room)
#---------------------------------------------------------------------------

# Returns @{ Used; Free; Dirs (hash of "Movies/x/y" and "TV/x/Season 01") }
function Get-TargetInfo {
    $info = @{ Used = 0.0; Free = $null; Dirs = @{} }
    if ($DryRoot) {
        if (Test-Path -LiteralPath $DryRoot) {
            foreach ($f in (Get-ChildItem -LiteralPath $DryRoot -Recurse -File)) { $info.Used += $f.Length }
            foreach ($top in @('Movies', 'TV')) {
                $p = Join-Path $DryRoot $top
                if (-not (Test-Path -LiteralPath $p)) { continue }
                foreach ($a in (Get-ChildItem -LiteralPath $p -Directory)) {
                    foreach ($b in (Get-ChildItem -LiteralPath $a.FullName -Directory)) { $info.Dirs["$top/$($a.Name)/$($b.Name)"] = $true }
                }
            }
        }
        $drive = (Get-Item -LiteralPath $env:WATCH_DRY_RUN).PSDrive
        if ($drive) { $info.Free = [double]$drive.Free }
        return $info
    }
    $du = Invoke-Phone "du -sk $PhoneRoot 2>/dev/null"
    foreach ($l in $du.Out) { if ($l -match '^(\d+)\s') { $info.Used = [double]$Matches[1] * 1KB } }
    $df = Invoke-Phone 'df -k /sdcard'
    foreach ($l in $df.Out) { if ($l -match '^\S+\s+\d+\s+\d+\s+(\d+)\s') { $info.Free = [double]$Matches[1] * 1KB } }
    $ls = Invoke-Phone "cd $PhoneRoot 2>/dev/null && find Movies TV -mindepth 2 -maxdepth 2 -type d 2>/dev/null"
    foreach ($l in $ls.Out) { if ($l.Trim()) { $info.Dirs[$l.Trim()] = $true } }
    return $info
}
#endregion

# Everything on the phone (or dry-run folder) as menu items, with sizes.
function Get-TargetItems {
    $sizes = @{}
    if ($DryRoot) {
        foreach ($top in @('Movies', 'TV')) {
            $p = Join-Path $DryRoot $top
            if (-not (Test-Path -LiteralPath $p)) { continue }
            foreach ($a in (Get-ChildItem -LiteralPath $p -Directory)) {
                foreach ($b in (Get-ChildItem -LiteralPath $a.FullName -Directory)) {
                    $t = 0.0; foreach ($f in (Get-ChildItem -LiteralPath $b.FullName -Recurse -File)) { $t += $f.Length }
                    $sizes["$top/$($a.Name)/$($b.Name)"] = $t
                }
            }
        }
    } else {
        $du = Invoke-Phone "cd $PhoneRoot 2>/dev/null && find Movies TV -mindepth 2 -maxdepth 2 -type d -exec du -sk {} + 2>/dev/null"
        foreach ($l in $du.Out) { if ($l -match '^(\d+)\s+(.+)$') { $sizes[$Matches[2].Trim()] = [double]$Matches[1] * 1KB } }
    }
    $items = New-Object System.Collections.Generic.List[object]
    foreach ($rel in ($sizes.Keys | Sort-Object)) {
        $parts = $rel.Split('/')
        if ($parts[0] -eq 'Movies') {
            $label = $parts[2]
            if ($parts[1] -ne '_Singles') { $label = "$label  [$($parts[1])]" }
        } else {
            $label = "$($parts[1]) - $($parts[2])  [TV]"
        }
        $items.Add([pscustomobject]@{ Label = $label; Search = (Fold $label); PhoneDir = $rel; Bytes = $sizes[$rel]
            OnPhone = $true; Selected = $false })
    }
    return $items
}

#---------------------------------------------------------------------------
# region  LIBRARY CHECK  (can the phone play it? what fixes it?)
#---------------------------------------------------------------------------
function Get-Probe([string]$path) {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        $out = & $script:Ffprobe -v error -print_format json -show_streams -show_format -i $path 2>$null
        if ($LASTEXITCODE -ne 0 -or -not $out) { return $null }
        return (($out -join "`n") | ConvertFrom-Json)
    } finally { $ErrorActionPreference = $old }
}

function Get-Prop($o, [string]$name) {
    if ($null -eq $o) { return $null }
    $p = $o.PSObject.Properties[$name]
    if ($p) { return $p.Value }
    return $null
}

# Looks at one file against the Watch-safe rules (WatchSafe.ps1). $quality = 'Original' / '1080p' / '720p'.
# Returns what's wrong (Problems), what will be done (Actions) and how (the plan fields).
function Test-Media([string]$path, [string]$quality) {
    $r = [pscustomobject]@{
        Path = $path; Ok = $true; CantFix = $null; Problems = @(); Actions = @(); Notes = @()
        Duration = 0.0; Width = $null; Height = $null; VideoCodec = $null; AudioCodec = $null
        MainVideo = $null; Cover = $null; Audio = @(); BadAudio = @(); Subs = @()
        EncodeVideo = $false; Shrink = $false; MaxHeight = $SafeMaxHeight; OutExt = '.mp4'
        NeedsWork = $false; Estimate = [double](Get-Item -LiteralPath $path).Length
    }
    $sp = Get-SourceProbe $path
    $p = $sp.Probe
    if (-not $p) { $r.Ok = $false; $r.CantFix = "ffprobe can't read this file (damaged?)"; return $r }
    $fmtDur = Get-Prop $p.format 'duration'
    if ($fmtDur) { $r.Duration = [double]$fmtDur }
    $ext = [IO.Path]::GetExtension($path).ToLowerInvariant()
    $st = Split-Streams $p
    if ($st.Videos.Count -eq 0) { $r.Ok = $false; $r.CantFix = 'no picture track found'; return $r }
    $r.MainVideo = $st.Videos[0]; $r.Audio = $st.Audio; $r.Subs = $st.TextSubs
    if ($st.Covers.Count -gt 0) { $r.Cover = $st.Covers[0] }
    foreach ($ps in $st.PictureSubs) { $r.Notes += "picture subtitles ($(Get-Prop $ps 'codec_name')) left out - the phone can't show them" }
    if ($st.Videos.Count -gt 1) { $r.Notes += 'only the first picture track is kept' }

    $v = $r.MainVideo
    $r.VideoCodec = Get-Prop $v 'codec_name'
    $r.Width = Get-Prop $v 'width'; $r.Height = Get-Prop $v 'height'
    if ($r.Audio.Count -gt 0) { $r.AudioCodec = Get-Prop $r.Audio[0] 'codec_name' } else { $r.Notes += 'no sound track' }

    $boxes = @($sp.Boxes)
    $r.Problems = @(Get-SafeProblems $p $boxes $ext)
    if (Get-VideoProblem $v) { $r.EncodeVideo = $true }
    for ($i = 0; $i -lt $r.Audio.Count; $i++) { if (Get-AudioProblem $r.Audio[$i]) { $r.BadAudio += $i } }

    # Quality: shrink only when the file is bigger than the chosen size
    $q = $Qualities[$quality]
    if ($q -and $r.Duration -gt 0) {
        $targetBytes = ($q.VideoK + $q.AudioK) * 1000 / 8 * $r.Duration
        $tooTall = $r.Height -and ($r.Height -gt $q.Height * 1.05)
        if ($tooTall -or $r.Estimate -gt $targetBytes * 1.2) {
            $r.Shrink = $true; $r.EncodeVideo = $true; $r.Estimate = $targetBytes; $r.MaxHeight = $q.Height
        }
    }

    if ($r.Shrink) { $r.Actions += "shrink to $quality (H.264)" }
    elseif ($r.EncodeVideo) { $r.Actions += 're-encode picture to 8-bit H.264 (1080p at most)' }
    if ($r.BadAudio.Count -gt 0) { $r.Actions += 'convert the sound to AAC (5.1 stays 5.1, more is mixed to stereo)' }
    if ($r.Subs.Count -gt 0) { $r.Actions += "save $($r.Subs.Count) subtitle track(s) as .srt next to the video" }
    if ($r.Cover) { $r.Actions += 'save the cover as poster.jpg and strip it from the video' }
    $r.NeedsWork = $r.Shrink -or ($r.Problems.Count -gt 0)
    if ($r.NeedsWork -and -not $r.EncodeVideo -and $r.BadAudio.Count -eq 0) { $r.Actions += 'copy into a Watch-safe MP4 (no quality loss)' }
    if (-not $r.NeedsWork) { $r.Actions += 'none - already Watch-safe, sent untouched' }
    return $r
}

function Write-CheckResult($r, [string]$name) {
    Write-Host ''
    Write-Host "  $name" -ForegroundColor White
    if ($r.CantFix) { Write-Bad "    Can't send: $($r.CantFix)"; return }
    $h = '?'; if ($r.Height) { $h = "$($r.Height)p" }
    Write-Note ("    {0} {1} / {2} / {3:N0} min / {4}" -f $h, $r.VideoCodec, $r.AudioCodec, ($r.Duration / 60), (Format-Size (Get-Item -LiteralPath $r.Path).Length))
    if ($r.Problems.Count -eq 0) { Write-Good '    OK - already Watch-safe.' }
    foreach ($p in $r.Problems) { Write-Warn2 "    Not Watch-safe: $p" }
    foreach ($a in $r.Actions)  { Write-Host "    Fix: $a" }
    foreach ($n in $r.Notes)    { Write-Note "    Note: $n" }
}

# Runs ffmpeg for one file. Writes a Watch-safe MP4 into $outDir (always a temp folder). Returns its path.
function Invoke-Fix($r, [string]$quality, [string]$outDir, [string]$baseName) {
    Assert-SafeTarget $outDir
    if (-not (Test-Path -LiteralPath $outDir)) { [void](New-Item -ItemType Directory -Path $outDir -Force) }
    $out = Join-Path $outDir ($baseName + '.mp4')
    $a = @('-hide_banner', '-loglevel', 'error', '-stats', '-y', '-fflags', '+genpts')
    $a += @('-i', $r.Path, '-map', "0:$(Get-Prop $r.MainVideo 'index')")
    foreach ($s in $r.Audio) { $a += @('-map', "0:$(Get-Prop $s 'index')") }
    $a += @('-sn', '-dn', '-map_chapters', '-1')

    if ($r.EncodeVideo) {
        $h = $r.MaxHeight; $w = [int]([math]::Round($h * 16 / 9 / 2) * 2)
        $a += @('-vf', "scale='min($w,iw)':'min($h,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2")
        $a += @('-c:v', 'libx264', '-preset', 'medium', '-pix_fmt', 'yuv420p', '-profile:v', 'high', '-level:v', '4.1')
        if ($r.Shrink) {
            $q = $Qualities[$quality]
            $a += @('-b:v', "$($q.VideoK)k", '-maxrate', "$([int]($q.VideoK * 1.5))k", '-bufsize', "$($q.VideoK * 2)k")
        } else {
            $a += @('-crf', '20')
        }
    } else {
        $a += @('-c:v', 'copy')
    }
    # Sound: copy Watch-safe AAC tracks, convert the rest (all of them when shrinking)
    for ($i = 0; $i -lt $r.Audio.Count; $i++) {
        if ($r.Shrink -or ($r.BadAudio -contains $i)) {
            $ch = Get-SafeChannels ([int](Get-Prop $r.Audio[$i] 'channels'))
            $k = 160; if ($r.Shrink) { $k = $Qualities[$quality].AudioK }
            if ($ch -eq 6) { $k = 384 }
            $a += @("-c:a:$i", 'aac', "-b:a:$i", "${k}k", "-ac:a:$i", "$ch")
        } else {
            $a += @("-c:a:$i", 'copy')
        }
    }
    $a += @('-movflags', '+faststart', '-max_muxing_queue_size', '4096', $out)

    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { & $script:Ffmpeg @a; $code = $LASTEXITCODE } finally { $ErrorActionPreference = $old }
    if ($code -ne 0 -or -not (Test-Path -LiteralPath $out)) { throw "ffmpeg could not make $($r.Path) Watch-safe (code $code)" }
    return $out
}

# Saves each text subtitle track as <baseName>.<lang>.srt in $outDir. Returns the files made.
function Save-Subtitles($r, [string]$outDir, [string]$baseName) {
    $made = @()
    $names = Get-SubFileNames $baseName $r.Subs
    for ($i = 0; $i -lt $r.Subs.Count; $i++) {
        $dest = Join-Path $outDir $names[$i]
        Assert-SafeTarget $dest
        $a = @('-hide_banner', '-loglevel', 'error', '-y', '-i', $r.Path, '-map', "0:$(Get-Prop $r.Subs[$i] 'index')", '-c:s', 'srt', $dest)
        $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        try { & $script:Ffmpeg @a 2>&1 | Out-Null; $ok = ($LASTEXITCODE -eq 0) } finally { $ErrorActionPreference = $old }
        if ($ok -and (Test-Path -LiteralPath $dest) -and (Get-Item -LiteralPath $dest).Length -gt 0) { $made += Get-Item -LiteralPath $dest }
        else { Write-Note "  Note: subtitle track $($i + 1) could not be saved as .srt; left out." }
    }
    return ,$made
}

# Last check before sending: ffprobe the finished file. Throws a plain reason when it must not go.
# On success, copies the real codec/size/length into $r (for the json).
# How long the original's picture and sound last (end of the last packet),
# not counting subtitle tracks. Reads only the last minute of the file.
function Get-PlayLength([string]$path, [double]$formatDur) {
    $inv = [Globalization.CultureInfo]::InvariantCulture
    $from = [math]::Max(0, [math]::Floor($formatDur - 60))
    $end = 0.0
    foreach ($sel in @('v:0', 'a')) {
        $out = & $script:Ffprobe -v error -read_intervals "$from%" -select_streams $sel `
            -show_entries packet=pts_time,duration_time -of csv=p=0 $path 2>$null
        foreach ($line in @($out)) {
            $parts = "$line".Split(',')
            $t = 0.0; $len = 0.0
            if (-not [double]::TryParse($parts[0], [Globalization.NumberStyles]::Float, $inv, [ref]$t)) { continue }
            if ($parts.Count -gt 1) { [void][double]::TryParse($parts[1], [Globalization.NumberStyles]::Float, $inv, [ref]$len) }
            if ($t + $len -gt $end) { $end = $t + $len }
        }
    }
    if ($end -gt 0) { return $end }
    return $formatDur
}

function Confirm-WatchSafe([string]$path, $r) {
    $p = Get-Probe $path
    $ext = [IO.Path]::GetExtension($path).ToLowerInvariant()
    $boxes = @(); if ($p -and $ext -eq '.mp4') { $boxes = Get-Mp4BoxOrder $path }
    $why = Get-SendRefusal $p $boxes $ext $r.Duration
    if ($why) {
        # Subtitle tracks can run a few seconds past the picture; they are left out, so measure without them
        $playLen = Get-PlayLength $r.Path $r.Duration
        if ($playLen -ne $r.Duration) { $why = Get-SendRefusal $p $boxes $ext $playLen }
    }
    if ($why) { throw "NOT SENT - $why." }
    $st = Split-Streams $p
    $v = $st.Videos[0]
    $r.VideoCodec = Get-Prop $v 'codec_name'; $r.Width = Get-Prop $v 'width'; $r.Height = Get-Prop $v 'height'
    $r.AudioCodec = $null; if ($st.Audio.Count -gt 0) { $r.AudioCodec = Get-Prop $st.Audio[0] 'codec_name' }
    $r.Duration = [double](Get-Prop $p.format 'duration')
    Write-Good '  Checked: Watch-safe, same length as the original.'
}

# Saves the cover picture to $posterPath. $false when it can't.
function Save-Cover($r, [string]$posterPath) {
    Assert-SafeTarget $posterPath
    $idx = Get-Prop $r.Cover 'index'
    $a = @('-hide_banner', '-loglevel', 'error', '-y', '-i', $r.Path, '-map', "0:$idx", '-frames:v', '1')
    if ((Get-Prop $r.Cover 'codec_name') -eq 'mjpeg') { $a += @('-c', 'copy') }
    $a += $posterPath
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { & $script:Ffmpeg @a 2>&1 | Out-Null; $ok = ($LASTEXITCODE -eq 0) } finally { $ErrorActionPreference = $old }
    return ($ok -and (Test-Path -LiteralPath $posterPath))
}
#endregion

#---------------------------------------------------------------------------
# region  TMDB  (details and posters; the key is never shown or logged)
#---------------------------------------------------------------------------
$script:TmdbKey = $null
$script:TmdbOff = $false

# Reads the key once. Without one, titles go with what the file name says.
function Initialize-Tmdb {
    $script:TmdbOff = $true
    if (-not (Test-Path -LiteralPath $TmdbKeyFile)) {
        Write-Warn2 '  No TMDB key (private\tmdb-key.txt): titles go without TMDB posters and details.'
        return
    }
    $k = ([IO.File]::ReadAllText($TmdbKeyFile)).Trim()
    if (-not $k) { Write-Warn2 '  private\tmdb-key.txt is empty: titles go without TMDB details.'; return }
    $script:TmdbKey = $k
    $script:TmdbOff = $false
    try { [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12 } catch {}
    if (-not (Test-Path -LiteralPath $TmdbCache)) { [void](New-Item -ItemType Directory -Path $TmdbCache -Force) }
    Write-Note '  TMDB key found (not shown). Lookups are cached in private\tmdb-cache.'
}

# Never let the key reach the screen or the log
function Hide-Key([string]$text) {
    if ($script:TmdbKey) { return $text.Replace($script:TmdbKey, '***') }
    return $text
}

function Get-Hash([string]$text) {
    $sha = [Security.Cryptography.SHA1]::Create()
    $bytes = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($text))
    return (($bytes | ForEach-Object { $_.ToString('x2') }) -join '')
}

function New-WebClient {
    $wc = New-Object Net.WebClient
    $wc.Encoding = [Text.Encoding]::UTF8
    $wc.Headers['User-Agent'] = 'Watch-Phone-Sync'
    return $wc
}

# Downloads one API answer. $null when TMDB can't be reached or has nothing.
function Invoke-TmdbWeb([string]$rel) {
    $url = "https://api.themoviedb.org/3/$rel"
    $wc = New-WebClient
    if ($script:TmdbKey.Length -gt 40) { $wc.Headers['Authorization'] = "Bearer $($script:TmdbKey)" }
    else {
        $sep = '?'; if ($url.Contains('?')) { $sep = '&' }
        $url += $sep + 'api_key=' + $script:TmdbKey
    }
    for ($try = 1; $try -le 3; $try++) {
        try { return $wc.DownloadString($url) }
        catch {
            $msg = Hide-Key ([string]$_.Exception.Message)
            if ($msg -match '\(429\)' -and $try -lt 3) { Start-Sleep -Seconds 2; continue }
            if ($msg -match '\(404\)') { return $null }
            if ($msg -match '\(401\)') {
                Write-Bad '  TMDB did not accept the key in private\tmdb-key.txt. Carrying on without TMDB.'
            } else {
                Write-Warn2 '  Could not reach TMDB (no internet?). Carrying on without it.'
                Write-Note "  ($msg)"
            }
            $script:TmdbOff = $true
            return $null
        }
    }
    return $null
}

# One TMDB answer as an object, from the cache when it's there.
function Get-Tmdb([string]$path, [hashtable]$query) {
    if ($script:TmdbOff) { return $null }
    $parts = @()
    foreach ($k in ($query.Keys | Sort-Object)) { $parts += ($k + '=' + [Uri]::EscapeDataString([string]$query[$k])) }
    $rel = $path
    if ($parts.Count -gt 0) { $rel += '?' + ($parts -join '&') }
    $cacheFile = Join-Path $TmdbCache ((Get-Hash $rel) + '.json')
    if (Test-Path -LiteralPath $cacheFile) {
        $text = [IO.File]::ReadAllText($cacheFile, [Text.Encoding]::UTF8)
    } else {
        $text = Invoke-TmdbWeb $rel
        if (-not $text) { return $null }
        [IO.File]::WriteAllText($cacheFile, $text, (New-Object Text.UTF8Encoding($false)))
    }
    try { return ($text | ConvertFrom-Json) } catch { return $null }
}

# Saves a TMDB picture (cached too). $size: w300 / w780 / w1280. $true when saved.
function Save-TmdbImage([string]$tmdbPath, [string]$size, [string]$dest) {
    if (-not $tmdbPath -or -not $script:TmdbKey) { return $false }
    $cached = Join-Path $TmdbCache ("img-$size-" + $tmdbPath.TrimStart('/'))
    if (-not (Test-Path -LiteralPath $cached)) {
        try { (New-WebClient).DownloadFile("https://image.tmdb.org/t/p/$size$tmdbPath", $cached) }
        catch {
            if (Test-Path -LiteralPath $cached) { Remove-Item -LiteralPath $cached -Force }
            Write-Warn2 '  Could not download a picture from TMDB.'
            return $false
        }
    }
    Assert-SafeTarget $dest
    Copy-Item -LiteralPath $cached -Destination $dest -Force
    return $true
}

# "Harry Potter and the Sorcerer's Stone" -> "harrypotterandthesorcerersstone"
function Get-MatchKey([string]$s) { return ((Fold $s) -replace '[^a-z0-9]', '') }

# Picks the search result that is this title: same year (give or take 1), same name first.
# With no year in the file name, only an exact name counts.
function Select-TmdbHit($res, [string]$title, $year, [string]$nameField, [string]$dateField) {
    $want = Get-MatchKey $title
    $first = $null
    foreach ($r in @(Get-Prop $res 'results')) {
        if ($null -eq $r) { continue }
        $y = $null
        if ([string](Get-Prop $r $dateField) -match '^(\d{4})') { $y = [int]$Matches[1] }
        if ($year -and -not $y) { continue }
        if ($year -and [math]::Abs($y - $year) -gt 1) { continue }
        foreach ($n in @((Get-Prop $r $nameField), (Get-Prop $r ('original_' + $nameField)))) {
            if ($n -and (Get-MatchKey $n) -eq $want) { return $r }
        }
        if ($year -and $null -eq $first) { $first = $r }
    }
    return $first
}

# The US rating ("PG-13", "TV-14") from TMDB's list of countries
function Get-UsRating($results, [string]$listField, [string]$valueField) {
    foreach ($c in @($results)) {
        if ($null -eq $c -or (Get-Prop $c 'iso_3166_1') -ne 'US') { continue }
        $entries = @($c); if ($listField) { $entries = @(Get-Prop $c $listField) }
        foreach ($e in $entries) {
            $v = [string](Get-Prop $e $valueField)
            if ($v) { return $v }
        }
    }
    return $null
}

function Get-Names($list, [int]$max) {
    $out = @()
    foreach ($p in @($list)) {
        if ($null -eq $p) { continue }
        $n = Get-Prop $p 'name'
        if ($n) { $out += [string]$n }
        if ($out.Count -ge $max) { break }
    }
    return ,$out
}

function Get-TmdbRating($d) {
    if (-not (Get-Prop $d 'vote_count')) { return $null }
    return [math]::Round([double](Get-Prop $d 'vote_average'), 1)
}

# The movie's TMDB details, or $null when there's no match.
function Get-MovieMeta($item) {
    if ($script:TmdbOff) { return $null }
    # No year (Marvel Studios Legends "Thor", "Apocalypse"): a search would match the wrong film
    if (-not $item.Year) { Write-Note '  TMDB: no year in the name, not looked up.'; return $null }
    $q = @{ query = $item.Title; include_adult = 'false' }
    if ($item.Year) { $q['year'] = $item.Year }
    $hit = Select-TmdbHit (Get-Tmdb 'search/movie' $q) $item.Title $item.Year 'title' 'release_date'
    if (-not $hit -and $item.Year) {
        $q.Remove('year')
        $hit = Select-TmdbHit (Get-Tmdb 'search/movie' $q) $item.Title $item.Year 'title' 'release_date'
    }
    $d = $null
    if ($hit) { $d = Get-Tmdb ('movie/' + (Get-Prop $hit 'id')) @{ append_to_response = 'credits,release_dates' } }
    if (-not $d) { Write-Note '  TMDB: no match.'; return $null }
    $credits = Get-Prop $d 'credits'
    $directors = @(@(Get-Prop $credits 'crew') | Where-Object { $_ -and (Get-Prop $_ 'job') -eq 'Director' } |
        ForEach-Object { [string](Get-Prop $_ 'name') } | Select-Object -Unique)
    $director = $null; if ($directors.Count -gt 0) { $director = $directors -join ', ' }
    $coll = Get-Prop $d 'belongs_to_collection'
    $collName = $null; if ($coll) { $collName = [string](Get-Prop $coll 'name') }
    Write-Good ('  TMDB: matched "{0}" ({1}).' -f (Get-Prop $d 'title'), (Get-Prop $d 'release_date'))
    return @{
        Overview = [string](Get-Prop $d 'overview'); Genres = (Get-Names (Get-Prop $d 'genres') 10)
        Runtime = Get-Prop $d 'runtime'; Rating = (Get-TmdbRating $d)
        ContentRating = (Get-UsRating (Get-Prop (Get-Prop $d 'release_dates') 'results') 'release_dates' 'certification')
        Director = $director; Cast = (Get-Names (Get-Prop $credits 'cast') 10); Collection = $collName
        Poster = [string](Get-Prop $d 'poster_path'); Backdrop = [string](Get-Prop $d 'backdrop_path')
    }
}

# The show's TMDB details (and its id for season lookups), or $null.
function Get-ShowMeta($item) {
    if ($script:TmdbOff) { return $null }
    $q = @{ query = $item.Title; include_adult = 'false' }
    if ($item.Year) { $q['first_air_date_year'] = $item.Year }
    $hit = Select-TmdbHit (Get-Tmdb 'search/tv' $q) $item.Title $item.Year 'name' 'first_air_date'
    $d = $null
    if ($hit) { $d = Get-Tmdb ('tv/' + (Get-Prop $hit 'id')) @{ append_to_response = 'content_ratings,credits' } }
    if (-not $d) { Write-Note '  TMDB: no match for the show.'; return $null }
    Write-Good ('  TMDB: matched the show "{0}".' -f (Get-Prop $d 'name'))
    return @{
        Id = Get-Prop $d 'id'
        Overview = [string](Get-Prop $d 'overview'); Genres = (Get-Names (Get-Prop $d 'genres') 10)
        Rating = (Get-TmdbRating $d)
        ContentRating = (Get-UsRating (Get-Prop (Get-Prop $d 'content_ratings') 'results') $null 'rating')
        Cast = (Get-Names (Get-Prop (Get-Prop $d 'credits') 'cast') 10)
        Poster = [string](Get-Prop $d 'poster_path'); Backdrop = [string](Get-Prop $d 'backdrop_path')
    }
}

# Episode number -> TMDB episode, for one season. Empty when there's nothing.
function Get-SeasonEpisodes($showMeta, [int]$season) {
    $map = @{}
    if (-not $showMeta) { return $map }
    $d = Get-Tmdb "tv/$($showMeta.Id)/season/$season" @{}
    foreach ($e in @(Get-Prop $d 'episodes')) {
        if ($null -ne $e) { $map[[int](Get-Prop $e 'episode_number')] = $e }
    }
    return $map
}
#endregion

#---------------------------------------------------------------------------
# region  JSON FILES  (CLAUDE.md section 5)
#---------------------------------------------------------------------------
function ConvertTo-Json2($o) { return ($o | ConvertTo-Json -Depth 5) }

# A value from TMDB's details, or $fallback when there is no match or no value
function Get-Meta($meta, [string]$name, $fallback) {
    if ($meta -and $null -ne $meta[$name] -and "$($meta[$name])" -ne '') { return $meta[$name] }
    return $fallback
}

# The folder's collection wins when it's numbered (story order);
# otherwise TMDB's ("Harry Potter Collection") beats a category folder like "Family".
function Get-Collection($item, $meta) {
    if ($item.Collection -and $item.Number) { return $item.Collection }
    return (Get-Meta $meta 'Collection' $item.Collection)
}

function New-MovieJson($item, $r, [string]$videoName, [bool]$hasPoster, [double]$bytes, $meta, [bool]$hasBackdrop) {
    $ac = $r.AudioCodec; $vc = $r.VideoCodec; $w = $r.Width; $h = $r.Height   # measured on the sent file
    $poster = $null; if ($hasPoster) { $poster = 'poster.jpg' }
    $backdrop = $null; if ($hasBackdrop) { $backdrop = 'backdrop.jpg' }
    $runtime = [int][math]::Round($r.Duration / 60)
    if ($runtime -le 0) { $runtime = Get-Meta $meta 'Runtime' $null }
    $o = [ordered]@{
        schema = 1; id = $item.Id; title = $item.Title; year = $item.Year
        collection = (Get-Collection $item $meta); collectionNumber = $item.Number
        overview = (Get-Meta $meta 'Overview' ''); genres = @(Get-Meta $meta 'Genres' @()); runtimeMin = $runtime
        rating = (Get-Meta $meta 'Rating' $null); contentRating = (Get-Meta $meta 'ContentRating' $null)
        director = (Get-Meta $meta 'Director' $null); cast = @(Get-Meta $meta 'Cast' @())
        poster = $poster; backdrop = $backdrop; file = $videoName
        width = $w; height = $h; videoCodec = $vc; audioCodec = $ac
        bytes = [long]$bytes; sentAt = (Get-Date -Format 'yyyy-MM-ddTHH:mm:ss')
        source = $item.Source
    }
    return ConvertTo-Json2 $o
}

function New-ShowJson($item, $meta, [bool]$hasPoster, [bool]$hasBackdrop) {
    $poster = $null; if ($hasPoster) { $poster = 'poster.jpg' }
    $backdrop = $null; if ($hasBackdrop) { $backdrop = 'backdrop.jpg' }
    $o = [ordered]@{ schema = 1; id = $item.ShowName; title = $item.Title; year = $item.Year
        overview = (Get-Meta $meta 'Overview' ''); genres = @(Get-Meta $meta 'Genres' @())
        contentRating = (Get-Meta $meta 'ContentRating' $null); rating = (Get-Meta $meta 'Rating' $null)
        cast = @(Get-Meta $meta 'Cast' @()); poster = $poster; backdrop = $backdrop }
    return ConvertTo-Json2 $o
}

# $tmdbEp: TMDB's episode, or $null. The file's own episode title wins over TMDB's.
function New-EpisodeJson($item, $ep, $r, [string]$videoName, $tmdbEp, [string]$still) {
    $code = 'S{0:D2}E{1:D2}' -f $ep.Season, $ep.Episode
    $title = $ep.Title
    $tmdbName = [string](Get-Prop $tmdbEp 'name')
    if ($tmdbName -and $title -eq "Episode $($ep.Episode)") { $title = $tmdbName }
    $runtime = [int][math]::Round($r.Duration / 60)
    if ($runtime -le 0) { $runtime = Get-Prop $tmdbEp 'runtime' }
    $airDate = [string](Get-Prop $tmdbEp 'air_date'); if (-not $airDate) { $airDate = $null }
    if (-not $still) { $still = $null }
    $o = [ordered]@{ schema = 1; id = "$($item.ShowName)/$code"; title = $title
        season = $ep.Season; episode = $ep.Episode; overview = [string](Get-Prop $tmdbEp 'overview')
        runtimeMin = $runtime; airDate = $airDate; still = $still; file = $videoName }
    return ConvertTo-Json2 $o
}
#endregion

#---------------------------------------------------------------------------
# region  DELIVERY  (phone via /data/local/tmp, or the dry-run folder)
#---------------------------------------------------------------------------

# Removes a title folder that's already there (only after Jordan said yes).
function Remove-Target([string]$rel) {
    if ($DryRoot) {
        $p = Join-Path $DryRoot $rel
        if (Test-Path -LiteralPath $p) { Remove-Item -LiteralPath $p -Recurse -Force }
        return
    }
    [void](Invoke-Phone ("rm -rf " + (Q "$PhoneRoot/$rel")))
}

# Copies one local file to <target>/<rel>/<name>: as name.part, renamed at the end.
function Send-File([string]$local, [string]$rel, [string]$name) {
    if ($DryRoot) {
        $dir = Join-Path $DryRoot $rel
        Assert-SafeTarget $dir
        if (-not (Test-Path -LiteralPath $dir)) { [void](New-Item -ItemType Directory -Path $dir -Force) }
        $part = Join-Path $dir "$name.part"
        Copy-Item -LiteralPath $local -Destination $part -Force
        Move-Item -LiteralPath $part -Destination (Join-Path $dir $name) -Force
        return
    }
    $dest = "$PhoneRoot/$rel"
    $tmp = "$PhoneTmp/xfer"
    [void](Invoke-Phone ("mkdir -p " + (Q $PhoneTmp) + " " + (Q $dest)))
    $r = Invoke-Adb push $local $tmp
    if ($r.Code -ne 0) { throw ("Couldn't copy $name to the phone: " + ($r.Out -join ' ')) }
    $cmd = "cp " + (Q $tmp) + " " + (Q "$dest/$name.part") + " && mv -f " + (Q "$dest/$name.part") + " " + (Q "$dest/$name") + " && rm -f " + (Q $tmp) + " && echo done"
    $m = Invoke-Phone $cmd
    if (-not ($m.Out -contains 'done')) { throw ("Couldn't save $name on the phone: " + ($m.Out -join ' ')) }
}

# Writes text as <rel>/<name> (atomic, like the app's .state files)
function Send-Text([string]$text, [string]$rel, [string]$name) {
    $local = Join-Path $WorkDir ('text-' + $name)
    Write-Utf8 $local $text
    Send-File $local $rel $name
    Remove-Item -LiteralPath $local -Force
}
#endregion

#---------------------------------------------------------------------------
# region  MENUS
#---------------------------------------------------------------------------
function Get-SelectedBytes($items) {
    $t = 0.0
    foreach ($i in $items) { if ($i.Selected) { $t += $i.Bytes } }
    return $t
}

function Write-Totals($items, $info) {
    $sel = Get-SelectedBytes $items
    $count = @($items | Where-Object { $_.Selected }).Count
    $total = $info.Used + $sel
    $color = 'Green'; if ($total -gt $CapBytes * 0.9) { $color = 'Yellow' }
    Write-Host ("  Picked: {0} ({1})   Already there: {2}   Total: {3} of {4} cap" -f `
        $count, (Format-Size $sel), (Format-Size $info.Used), (Format-Size $total), (Format-Size $CapBytes)) -ForegroundColor $color
    if ($null -ne $info.Free) { Write-Note ("  Free space where it goes: {0}" -f (Format-Size $info.Free)) }
}

# Searchable multi-select. Returns the picked items, or @() to stop.
# $useCap: refuse picks that would go over the 50 GB cap (sending only).
function Select-Titles($items, $info, [string]$heading = 'Pick what goes to the phone', [bool]$useCap = $true) {
    $filter = ''
    while ($true) {
        $shown = @($items)
        if ($filter) {
            $words = @((Fold $filter) -split '\s+' | Where-Object { $_ })
            $shown = @($items | Where-Object { $it = $_; @($words | Where-Object { $it.Search -notlike "*$_*" }).Count -eq 0 })
        }
        Write-Title $heading
        if ($filter) { Write-Note "  Search: '$filter'   (C clears it)" }
        if ($shown.Count -eq 0) { Write-Warn2 '  Nothing matches.' }
        for ($i = 0; $i -lt $shown.Count; $i++) {
            $it = $shown[$i]
            $mark = '[ ]'; $c = 'Gray'
            if ($it.Selected) { $mark = '[x]'; $c = 'Green' }
            $tag = ''; if ($it.OnPhone -and $useCap) { $tag = '  (on phone)'; if (-not $it.Selected) { $c = 'DarkGray' } }
            Write-Host ('{0,4} {1} ' -f ($i + 1), $mark) -NoNewline -ForegroundColor DarkCyan
            Write-Host ('{0,9}  {1}{2}' -f (Format-Size $it.Bytes), $it.Label, $tag) -ForegroundColor $c
        }
        Write-Host ''
        if ($useCap) { Write-Totals $items $info }
        else { Write-Host ("  Picked: {0} ({1})" -f @($items | Where-Object { $_.Selected }).Count, (Format-Size (Get-SelectedBytes $items))) -ForegroundColor Green }
        Write-Host ''
        Write-Note '  Numbers pick or un-pick: 3   1,4,7   10-15      Words: search (title, collection)'
        Write-Note '  A = pick all shown   N = un-pick all   S = show picked   C = clear search'
        Write-Note '  D = done, go on      Q = quit'
        $ans = (Read-Host '  Choose').Trim()
        if ($ans -eq '') { continue }
        if ($ans -ieq 'Q') { return @() }
        if ($ans -ieq 'D') {
            $picked = @($items | Where-Object { $_.Selected })
            if ($picked.Count -eq 0) { Write-Warn2 '  Nothing picked yet.'; continue }
            return $picked
        }
        if ($ans -ieq 'C') { $filter = ''; continue }
        if ($ans -ieq 'N') { foreach ($it in $items) { $it.Selected = $false }; continue }
        if ($ans -ieq 'S') {
            Write-Title 'Picked'
            foreach ($it in ($items | Where-Object { $_.Selected })) { Write-Host ('  {0,9}  {1}' -f (Format-Size $it.Bytes), $it.Label) }
            [void](Read-Host '  Press Enter to go back')
            continue
        }
        $targets = $null
        if ($ans -ieq 'A') { $targets = @($shown | Where-Object { -not $_.Selected }) }
        else {
            $nums = Parse-Numbers $ans $shown.Count
            if ($null -ne $nums) {
                if ($nums.Count -eq 0) { Write-Warn2 '  Those numbers are not on the list.'; continue }
                $targets = @($nums | ForEach-Object { $shown[$_ - 1] })
            }
        }
        if ($null -eq $targets) { $filter = $ans; continue }
        foreach ($it in $targets) {
            if ($it.Selected -and $ans -ine 'A') { $it.Selected = $false; continue }
            $twin = @($items | Where-Object { $_.Selected -and $_.DupKey -eq $it.DupKey -and $_ -ne $it }) | Select-Object -First 1
            if ($useCap -and $twin) {
                Write-Bad ("  Not picked: {0} - the same title is already picked from {1}." -f $it.Label, $twin.Source)
                continue
            }
            $after = $info.Used + (Get-SelectedBytes $items) + $it.Bytes
            if ($useCap -and $after -gt $CapBytes) {
                Write-Bad ("  Not picked: {0} - it would go over the {1} cap." -f $it.Label, (Format-Size $CapBytes))
                continue
            }
            $it.Selected = $true
        }
    }
}

function Read-Quality {
    Write-Title 'Quality'
    Write-Host '  1  Original      full quality (copied untouched if already Watch-safe, 1080p at most)'
    Write-Host '  2  1080p         about 4 Mbps, roughly 1.8 GB per hour'
    Write-Host '  3  720p small    about 2 Mbps, roughly 0.9 GB per hour'
    Write-Note '  Files already smaller than the choice are not re-encoded.'
    while ($true) {
        $a = (Read-Host '  Choose 1, 2 or 3 (Enter = 1)').Trim()
        if ($a -eq '' -or $a -eq '1') { return 'Original' }
        if ($a -eq '2') { return '1080p' }
        if ($a -eq '3') { return '720p' }
    }
}
#endregion

#---------------------------------------------------------------------------
# region  SEND
#---------------------------------------------------------------------------

# Every file of every picked item: check, fix if needed, send. Returns how many items went.
function Send-Items($picked, $info) {
    $sent = 0
    $usedNow = $info.Used
    $shows = @{}
    foreach ($item in $picked) {
        Write-Step "$($item.Label)"
        try {
            if ($item.OnPhone) { Write-Note '  Replacing the copy that is there.'; Remove-Target $item.PhoneDir }
            if ($item.Kind -eq 'Movie') { $bytes = Send-Movie $item $usedNow }
            else { $bytes = Send-Season $item $usedNow $shows }
            if ($bytes -gt 0) { $usedNow += $bytes; $sent++ ; Write-Good ("  Sent ({0})." -f (Format-Size $bytes)) }
        } catch {
            Write-Bad "  $($_.Exception.Message)"
            Write-Bad '  This title was not sent; the rest carry on.'
        }
    }
    return $sent
}

# Makes one file Watch-safe in $stage (untouched when it already is), checks it with ffprobe,
# and saves its subtitles as .srt. Returns @{ Path; Result; Poster; Subs }
function Prepare-File($file, [string]$stage, [string]$baseName, [string]$phoneDir) {
    $r = Test-Media $file.FullName $script:Quality
    if ($r.CantFix) { throw $r.CantFix }
    $staged = Get-StagedFile $file $phoneDir $baseName $script:Quality
    if ($staged) {
        Write-Note "  Using the copy pre-converted in $StageRoot (no converting now)."
        Confirm-WatchSafe $staged.Video $r
        return @{ Path = $staged.Video; Result = $r; Poster = $staged.Poster; Subs = $staged.Subs }
    }
    foreach ($p in $r.Problems) { Write-Warn2 "  Found: $p" }
    foreach ($n in $r.Notes) { Write-Note "  Note: $n" }
    $poster = $null
    if ($r.Cover) {
        $pp = Join-Path $stage 'poster.jpg'
        if (Save-Cover $r $pp) { $poster = $pp; Write-Note '  Saved the cover as poster.jpg.' }
    }
    $subs = @(); if ($r.Subs.Count -gt 0) { $subs = Save-Subtitles $r $stage $baseName }
    $out = $file.FullName
    if ($r.NeedsWork) {
        foreach ($a in $r.Actions) { Write-Host "  Fixing: $a" }
        if ($r.EncodeVideo) { Write-Note ('  Re-encoding takes a while (roughly as long as the film on a fast PC).') }
        $out = Invoke-Fix $r $script:Quality $stage $baseName
    } else { Write-Note '  Already Watch-safe: sent untouched.' }
    Confirm-WatchSafe $out $r
    return @{ Path = $out; Result = $r; Poster = $poster; Subs = $subs }
}

# Says whether the real size is much bigger than expected, and whether it still fits.
function Test-RealSize([double]$real, [double]$estimate, [double]$usedNow) {
    if ($estimate -gt 0 -and $real -gt $estimate * 1.15) {
        Write-Warn2 ("  Heads up: it came out at {0}, more than the {1} estimate." -f (Format-Size $real), (Format-Size $estimate))
    }
    if ($usedNow + $real -gt $CapBytes) {
        throw ("it would take Watch over the {0} cap ({1} used)" -f (Format-Size $CapBytes), (Format-Size $usedNow))
    }
}

# Poster and backdrop for a title. TMDB's poster when it matched; the embedded cover only when
# TMDB has none. Returns @{ Poster; Backdrop; Bytes } (paths in $stage, or $null).
function Get-Pictures($meta, [string]$cover, [string]$stage) {
    $out = @{ Poster = $null; Backdrop = $null; Bytes = 0.0 }
    $p = Join-Path $stage 'tmdb-poster.jpg'
    if ($meta -and (Save-TmdbImage $meta.Poster 'w780' $p)) { $out.Poster = $p }
    elseif ($cover) { $out.Poster = $cover; Write-Note '  Poster: the cover stored in the file.' }
    $b = Join-Path $stage 'tmdb-backdrop.jpg'
    if ($meta -and (Save-TmdbImage $meta.Backdrop 'w1280' $b)) { $out.Backdrop = $b }
    foreach ($f in @($out.Poster, $out.Backdrop)) { if ($f) { $out.Bytes += (Get-Item -LiteralPath $f).Length } }
    return $out
}

function Send-Movie($item, [double]$usedNow) {
    $stage = Join-Path $WorkDir 'stage'
    if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
    [void](New-Item -ItemType Directory -Path $stage)
    $prep = Prepare-File $item.Files[0] $stage 'movie' $item.PhoneDir
    $video = Get-Item -LiteralPath $prep.Path
    $videoName = 'movie.mp4'
    $meta = Get-MovieMeta $item
    $pics = Get-Pictures $meta $prep.Poster $stage
    $subBytes = 0.0; foreach ($sf in $prep.Subs) { $subBytes += $sf.Length }
    $total = [double]$video.Length + $pics.Bytes + $subBytes
    Test-RealSize $total ($prep.Result.Estimate + $pics.Bytes + $subBytes) $usedNow
    Write-Host "  Sending $videoName ($(Format-Size $video.Length))..."
    if ($pics.Poster) { Send-File $pics.Poster $item.PhoneDir 'poster.jpg' }
    if ($pics.Backdrop) { Send-File $pics.Backdrop $item.PhoneDir 'backdrop.jpg' }
    foreach ($sf in $prep.Subs) { Send-File $sf.FullName $item.PhoneDir $sf.Name }
    Send-File $video.FullName $item.PhoneDir $videoName
    $json = New-MovieJson $item $prep.Result $videoName ([bool]$pics.Poster) $video.Length $meta ([bool]$pics.Backdrop)
    Send-Text $json $item.PhoneDir 'movie.json'
    Remove-Item -LiteralPath $stage -Recurse -Force
    return $total
}

function Send-Season($item, [double]$usedNow, $shows) {
    $showDir = "TV/$($item.ShowName)"
    $total = 0.0
    if (-not $shows.ContainsKey($item.ShowName)) {
        $meta = Get-ShowMeta $item
        $stage = Join-Path $WorkDir 'show'
        if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
        [void](New-Item -ItemType Directory -Path $stage)
        $pics = Get-Pictures $meta $null $stage
        if ($pics.Poster) { Send-File $pics.Poster $showDir 'poster.jpg' }
        if ($pics.Backdrop) { Send-File $pics.Backdrop $showDir 'backdrop.jpg' }
        Send-Text (New-ShowJson $item $meta ([bool]$pics.Poster) ([bool]$pics.Backdrop)) $showDir 'show.json'
        Remove-Item -LiteralPath $stage -Recurse -Force
        $total += $pics.Bytes
        $shows[$item.ShowName] = $meta
    }
    $tmdbEps = Get-SeasonEpisodes $shows[$item.ShowName] $item.Season
    foreach ($ep in $item.Episodes) {
        $code = 'S{0:D2}E{1:D2}' -f $ep.Season, $ep.Episode
        Write-Host "  $code $($ep.Title)"
        $stage = Join-Path $WorkDir 'stage'
        if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
        [void](New-Item -ItemType Directory -Path $stage)
        $prep = Prepare-File $ep.File $stage $code $item.PhoneDir
        $video = Get-Item -LiteralPath $prep.Path
        $videoName = "$code.mp4"
        Test-RealSize ([double]$video.Length) $prep.Result.Estimate ($usedNow + $total)
        foreach ($sf in $prep.Subs) { Send-File $sf.FullName $item.PhoneDir $sf.Name; $total += $sf.Length }
        Send-File $video.FullName $item.PhoneDir $videoName
        $tmdbEp = $tmdbEps[$ep.Episode]
        $still = $null
        $stillLocal = Join-Path $stage "$code.jpg"
        if (Save-TmdbImage ([string](Get-Prop $tmdbEp 'still_path')) 'w300' $stillLocal) {
            Send-File $stillLocal $item.PhoneDir "$code.jpg"
            $still = "$code.jpg"
            $total += (Get-Item -LiteralPath $stillLocal).Length
        }
        Send-Text (New-EpisodeJson $item $ep $prep.Result $videoName $tmdbEp $still) $item.PhoneDir "$code.json"
        $total += $video.Length
        Remove-Item -LiteralPath $stage -Recurse -Force
    }
    return $total
}

# Re-estimates the picked items at the chosen quality (needs ffprobe on each file).
function Get-Estimate($picked, [string]$quality) {
    $total = 0.0
    $problems = 0
    $staged = 0
    Invoke-ProbeMany @($picked | ForEach-Object { $_.Files })
    foreach ($item in $picked) {
        $i = 0
        foreach ($f in $item.Files) {
            $base = 'movie'
            if ($item.Kind -eq 'Season') { $ep = $item.Episodes[$i]; $base = 'S{0:D2}E{1:D2}' -f $ep.Season, $ep.Episode }
            $i++
            $st = Get-StagedFile $f $item.PhoneDir $base $quality
            if ($st) { $total += $st.Bytes; $staged++; continue }
            $r = Test-Media $f.FullName $quality
            $total += $r.Estimate
            if ($r.CantFix -or $r.Problems.Count -gt 0) { $problems++ }
            if ($r.CantFix -or $r.Problems.Count -gt 0) { Write-CheckResult $r $f.Name }
        }
    }
    if ($staged -gt 0) { Write-Good "  $staged file(s) are already pre-converted in $StageRoot; those go as a plain copy." }
    return @{ Bytes = $total; Problems = $problems }
}
#endregion

#---------------------------------------------------------------------------
# region  PROBE CACHE  (private\probe-cache.tsv: each file is read by ffprobe once)
#---------------------------------------------------------------------------
$script:ProbeCache = $null

function Read-ProbeCache {
    if ($null -ne $script:ProbeCache) { return }
    $script:ProbeCache = @{}
    if (-not (Test-Path -LiteralPath $ProbeCacheFile)) { return }
    foreach ($line in [IO.File]::ReadLines($ProbeCacheFile)) {
        $e = ConvertFrom-ProbeLine $line
        if ($e) { $script:ProbeCache[$e.Key] = $e }
    }
}

function Get-FileProbeKey($fi) { return (Get-ProbeKey $fi.FullName $fi.Length $fi.LastWriteTimeUtc.Ticks) }

# Is this file's answer already saved?
function Test-ProbeCached($fi) { Read-ProbeCache; return $script:ProbeCache.ContainsKey((Get-FileProbeKey $fi)) }

# Saves one answer (one line, written at once, so Ctrl+C loses at most the file being read).
# This script's own work files (%TEMP%\watch-*: clips, converted copies) are kept in memory only.
function Save-ProbeEntry($fi, [string]$json) {
    $boxes = @()
    if ($json -and $fi.Extension -ieq '.mp4') { try { $boxes = Get-Mp4BoxOrder $fi.FullName } catch {} }
    $key = Get-FileProbeKey $fi
    $line = ConvertTo-ProbeLine $key $boxes $json
    $e = ConvertFrom-ProbeLine $line
    $script:ProbeCache[$key] = $e
    $tmp = Join-Path ([IO.Path]::GetFullPath([IO.Path]::GetTempPath())) 'watch-'
    if (-not $fi.FullName.StartsWith($tmp, [StringComparison]::OrdinalIgnoreCase)) {
        $dir = Split-Path -Parent $ProbeCacheFile
        if (-not (Test-Path -LiteralPath $dir)) { [void](New-Item -ItemType Directory -Path $dir -Force) }
        [IO.File]::AppendAllText($ProbeCacheFile, $line + "`n", (New-Object Text.UTF8Encoding($false)))
    }
    return $e
}

function Get-ProbeText([string]$path) {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        $out = & $script:Ffprobe -v error -print_format json -show_streams -show_format -i $path 2>$null
        if ($LASTEXITCODE -ne 0 -or -not $out) { return '' }
        return ($out -join ' ')
    } finally { $ErrorActionPreference = $old }
}

# ffprobe's answer for a file plus its MP4 box order: @{ Probe; Boxes }. From the cache when
# the file is unchanged; -Fresh reads it again.
function Get-SourceProbe([string]$path, [switch]$Fresh) {
    Read-ProbeCache
    $fi = New-Object IO.FileInfo $path
    $key = Get-FileProbeKey $fi
    $e = $null
    if (-not $Fresh -and $script:ProbeCache.ContainsKey($key)) { $e = $script:ProbeCache[$key] }
    if (-not $e) { $e = Save-ProbeEntry $fi (Get-ProbeText $path) }
    if (-not $e.ContainsKey('Parsed')) {
        $e.Parsed = $null
        if ($e.Json) { try { $e.Parsed = $e.Json | ConvertFrom-Json } catch {} }
    }
    return @{ Probe = $e.Parsed; Boxes = @($e.Boxes) }
}

function Start-ProbeProcess($fi) {
    $psi = New-Object Diagnostics.ProcessStartInfo
    $psi.FileName = $script:Ffprobe
    $psi.Arguments = '-v error -print_format json -show_streams -show_format -i "' + $fi.FullName + '"'
    $psi.UseShellExecute = $false; $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = [Text.Encoding]::UTF8
    $p = [Diagnostics.Process]::Start($psi)
    return @{ Fi = $fi; Proc = $p; Out = $p.StandardOutput.ReadToEndAsync(); Err = $p.StandardError.ReadToEndAsync() }
}

# Reads every file not in the cache yet, $ProbeWorkers at a time. $OnEach runs after each file
# (for progress). Safe to stop with Ctrl+C: what was read is saved, the rest is read next time.
function Invoke-ProbeMany($files, [scriptblock]$OnEach = $null) {
    Read-ProbeCache
    $todo = New-Object System.Collections.Generic.Queue[object]
    foreach ($f in @($files)) {
        if ($null -eq $f) { continue }
        $fi = New-Object IO.FileInfo ([string]$f.FullName)
        if (-not $script:ProbeCache.ContainsKey((Get-FileProbeKey $fi))) { $todo.Enqueue($fi) }
    }
    $running = New-Object System.Collections.Generic.List[object]
    try {
        while ($todo.Count -gt 0 -or $running.Count -gt 0) {
            while ($running.Count -lt $ProbeWorkers -and $todo.Count -gt 0) { $running.Add((Start-ProbeProcess $todo.Dequeue())) }
            $done = @($running | Where-Object { $_.Proc.HasExited -and $_.Out.IsCompleted })
            if ($done.Count -eq 0) { Start-Sleep -Milliseconds 30; continue }
            foreach ($j in $done) {
                [void]$running.Remove($j)
                $json = ''
                if ($j.Proc.ExitCode -eq 0) { $json = $j.Out.Result }
                $j.Proc.Dispose()
                [void](Save-ProbeEntry $j.Fi $json)
                if ($OnEach) { & $OnEach $j.Fi }
            }
        }
    } finally {
        foreach ($j in $running) { try { if (-not $j.Proc.HasExited) { $j.Proc.Kill() } } catch {} }
    }
}
#endregion

#---------------------------------------------------------------------------
# region  ENCODE SPEED  (how fast this PC re-encodes; for time estimates)
#---------------------------------------------------------------------------

# Seconds of work per second of 1080p film, or $null when not measured yet.
function Get-EncodeSpeed {
    if ($env:WATCH_ENCODE_SPEED) { return [double]$env:WATCH_ENCODE_SPEED }
    if (-not (Test-Path -LiteralPath $SpeedFile)) { return $null }
    try { return [double](([IO.File]::ReadAllText($SpeedFile) | ConvertFrom-Json).secPerSec1080) } catch { return $null }
}

function Save-EncodeSpeed([double]$value, [string]$from) {
    if ($env:WATCH_ENCODE_SPEED) { return }
    $o = [ordered]@{ secPerSec1080 = [math]::Round($value, 3); from = $from; at = (Get-Date -Format 'yyyy-MM-ddTHH:mm:ss') }
    $dir = Split-Path -Parent $SpeedFile
    if (-not (Test-Path -LiteralPath $dir)) { [void](New-Item -ItemType Directory -Path $dir -Force) }
    [IO.File]::WriteAllText($SpeedFile, ($o | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
}

# After a real re-encode: fold the real speed into the saved one.
function Update-EncodeSpeed([double]$elapsed, [double]$duration, [int]$w, [int]$h) {
    if ($duration -lt 30 -or $w -le 0) { return }
    $fit = Get-FitSize $w $h $SafeMaxWidth $SafeMaxHeight
    $px = [math]::Max(0.15, ($fit[0] * $fit[1]) / (1920.0 * 1080.0))
    $measured = $elapsed / $duration / $px
    $old = Get-EncodeSpeed
    if ($old) { $measured = ($old + $measured) / 2 }
    Save-EncodeSpeed $measured 'real conversions'
}

# The saved speed, or measures it once with 20 s of $sample (a real 1080p file if there is one).
function Get-EncodeSpeedOrMeasure([string]$sample) {
    $s = Get-EncodeSpeed
    if ($s) { return $s }
    Write-Note '  Measuring how fast this PC converts video (about a minute, only this once)...'
    $vf = "scale='min(1920,iw)':'min(1080,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2"
    $a = @('-hide_banner', '-loglevel', 'error', '-y')
    $w = 1920; $h = 1080
    if ($sample -and (Test-Path -LiteralPath $sample)) {
        $sp = Get-SourceProbe $sample
        $dur = [double](Get-P (Get-P $sp.Probe 'format') 'duration')
        $v = @((Split-Streams $sp.Probe).Videos) | Select-Object -First 1
        if ($v) { $fit = Get-FitSize ([int](Get-P $v 'width')) ([int](Get-P $v 'height')) 1920 1080; $w = $fit[0]; $h = $fit[1] }
        $a += @('-ss', "$(Get-ClipStart $dur)", '-t', '20', '-i', $sample, '-map', '0:v:0')
    } else {
        $a += @('-f', 'lavfi', '-i', 'testsrc2=size=1920x1080:rate=24', '-t', '20')
    }
    $a += @('-vf', $vf, '-c:v', 'libx264', '-preset', 'medium', '-pix_fmt', 'yuv420p', '-crf', '20', '-an', '-f', 'null', 'NUL')
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { & $script:Ffmpeg @a 2>&1 | Out-Null } finally { $ErrorActionPreference = $old }
    $px = [math]::Max(0.15, ($w * $h) / (1920.0 * 1080.0))
    $s = $watch.Elapsed.TotalSeconds / 20 / $px
    if ($s -le 0.01) { return $DefaultSecPerSec1080 }
    Save-EncodeSpeed $s 'measured with a 20 s sample'
    Write-Note ('  This PC converts 1 hour of 1080p film in about {0}.' -f (Format-Duration ($s * 3600)))
    return $s
}
#endregion

#---------------------------------------------------------------------------
# region  STAGING  (pre-converted copies on this PC, e.g. D:\Watch-Staging)
#   <StageRoot>\Movies\<Collection>\<Title (Year)>\movie.mp4 (+ .srt, poster)
#   <StageRoot>\TV\<Show>\Season 01\S01E01.mp4
#   Each finished file has <name>.stage.json, written last; without it the file
#   doesn't count. Work happens in <name>.part\ and is moved in at the end.
#---------------------------------------------------------------------------
function Get-StageDir([string]$phoneDir) { return (Join-Path $StageRoot ($phoneDir -replace '/', '\')) }

# The staged copy of one source file, if it is current. @{ Video; Subs; Poster; Bytes } or $null.
function Get-StagedFile($file, [string]$phoneDir, [string]$baseName, [string]$quality) {
    if (-not $phoneDir -or -not (Test-Path -LiteralPath $StageRoot)) { return $null }
    $dir = Get-StageDir $phoneDir
    $mf = Join-Path $dir "$baseName.stage.json"
    if (-not (Test-Path -LiteralPath $mf)) { return $null }
    try { $m = [IO.File]::ReadAllText($mf) | ConvertFrom-Json } catch { return $null }
    $fi = New-Object IO.FileInfo ([string]$file.FullName)
    $video = Join-Path $dir ([string]$m.video)
    $vb = $null; if (Test-Path -LiteralPath $video) { $vb = (Get-Item -LiteralPath $video).Length }
    if (-not (Test-StagedCurrent $m $fi.Length $fi.LastWriteTimeUtc.Ticks $quality $vb)) { return $null }
    $subs = @(); $bytes = [double]$vb
    foreach ($n in @($m.subs)) {
        if (-not $n) { continue }
        $p = Join-Path $dir $n
        if (Test-Path -LiteralPath $p) { $g = Get-Item -LiteralPath $p; $subs += $g; $bytes += $g.Length }
    }
    $poster = $null
    if ($m.poster) { $p = Join-Path $dir $m.poster; if (Test-Path -LiteralPath $p) { $poster = $p } }
    return @{ Video = $video; Subs = $subs; Poster = $poster; Bytes = $bytes }
}

# Converts one source file into the staging folder. Returns @{ What (staged / already / untouched); Bytes }.
function Invoke-StageFile($file, [string]$phoneDir, [string]$baseName, [string]$quality) {
    $have = Get-StagedFile $file $phoneDir $baseName $quality
    if ($have) { return @{ What = 'already'; Bytes = $have.Bytes } }
    $r = Test-Media $file.FullName $quality
    if ($r.CantFix) { throw $r.CantFix }
    if (-not $r.NeedsWork) { return @{ What = 'untouched'; Bytes = 0.0 } }
    $dir = Get-StageDir $phoneDir
    Assert-SafeTarget $dir
    $work = Join-Path $dir "$baseName.part"
    $mf = Join-Path $dir "$baseName.stage.json"
    if (Test-Path -LiteralPath $mf) { Remove-Item -LiteralPath $mf -Force }              # the old copy no longer counts
    if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force } # left over from a stop
    [void](New-Item -ItemType Directory -Path $work -Force)
    foreach ($p in $r.Problems) { Write-Note "    Found: $p" }
    foreach ($a in $r.Actions) { Write-Note "    Fixing: $a" }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $poster = $null
    if ($r.Cover -and (Save-Cover $r (Join-Path $work "$baseName.poster.jpg"))) { $poster = "$baseName.poster.jpg" }
    $subs = @(); if ($r.Subs.Count -gt 0) { $subs = @(Save-Subtitles $r $work $baseName) }
    $srcW = [int]$r.Width; $srcH = [int]$r.Height
    $out = Invoke-Fix $r $quality $work $baseName
    Confirm-WatchSafe $out $r
    foreach ($f in @(Get-ChildItem -LiteralPath $work -File)) {
        Move-Item -LiteralPath $f.FullName -Destination (Join-Path $dir $f.Name) -Force
    }
    Remove-Item -LiteralPath $work -Recurse -Force
    $vb = (Get-Item -LiteralPath (Join-Path $dir "$baseName.mp4")).Length
    $m = [ordered]@{ schema = 1; source = $file.FullName; sourceBytes = [long]$file.Length
        sourceTicks = [long]$file.LastWriteTimeUtc.Ticks; quality = $quality; video = "$baseName.mp4"
        videoBytes = [long]$vb; subs = @($subs | ForEach-Object { $_.Name }); poster = $poster
        stagedAt = (Get-Date -Format 'yyyy-MM-ddTHH:mm:ss') }
    Write-Utf8 "$mf.tmp" ($m | ConvertTo-Json)
    Move-Item -LiteralPath "$mf.tmp" -Destination $mf -Force
    if ($r.EncodeVideo -and -not $r.Shrink) { Update-EncodeSpeed $watch.Elapsed.TotalSeconds $r.Duration $srcW $srcH }
    $bytes = [double]$vb; foreach ($s in $subs) { $bytes += $s.Length }
    return @{ What = 'staged'; Bytes = $bytes }
}
#endregion

#---------------------------------------------------------------------------
# region  START-UP  (shared by every Watch script)
#---------------------------------------------------------------------------

# Keeps Windows awake while a long run is going. It changes no power setting: it only asks
# Windows not to sleep until Set-KeepAwake $false (or until this window closes).
function Set-KeepAwake([bool]$on) {
    if (-not ('WatchPower.Native' -as [type])) {
        Add-Type -Namespace WatchPower -Name Native -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint esFlags);'
    }
    $continuous = [uint32]2147483648; $system = [uint32]1
    if ($on) { [void][WatchPower.Native]::SetThreadExecutionState($continuous -bor $system) }
    else { [void][WatchPower.Native]::SetThreadExecutionState($continuous) }
}

# Finds ffmpeg + ffprobe. $false (with the install line) when missing.
function Initialize-Ffmpeg([string]$cmdName) {
    $script:Ffmpeg = Find-Ffmpeg 'ffmpeg'
    $script:Ffprobe = Find-Ffmpeg 'ffprobe'
    if ($script:Ffmpeg -and $script:Ffprobe) { return $true }
    Write-Bad '  ffmpeg is not installed. It is needed to check and fix videos.'
    Write-Host '  To install it, open PowerShell and type:  winget install Gyan.FFmpeg'
    Write-Host "  Then close this window and double-click $cmdName again."
    return $false
}

# The dry-run folder, or exactly one connected phone. $false (after saying why) when neither.
function Initialize-Target {
    if ($DryRoot) {
        Write-Warn2 "  TEST MODE: nothing goes to the phone. Everything happens in $DryRoot"
        if (-not (Test-Path -LiteralPath $env:WATCH_DRY_RUN)) { [void](New-Item -ItemType Directory -Path $env:WATCH_DRY_RUN -Force) }
        Assert-SafeTarget $DryRoot
        return $true
    }
    $script:Adb = Find-Adb
    if (-not $script:Adb) {
        Write-Bad '  adb.exe (Android platform-tools) was not found. Set $AdbPath near the top of WatchCommon.ps1.'
        return $false
    }
    Write-Note "  adb: $($script:Adb)"
    if (-not (Connect-Phone)) { return $false }
    Write-Good "  Phone connected ($($script:Serial))."
    return $true
}

# Tells the app something changed, so it rescans on next open.
function Write-LastSync {
    Send-Text (Get-Date -Format 'yyyy-MM-ddTHH:mm:ss') '.state' 'last-sync.txt'
}

# Every source file in the library as a flat list: @{ File; Item; Base (movie / S01E02); Label; Kind }
function Get-LibraryFiles($items) {
    foreach ($it in $items) {
        if ($it.Kind -eq 'Movie') {
            [pscustomobject]@{ File = $it.Files[0]; Item = $it; Base = 'movie'; Label = $it.Label; Kind = 'Movie' }
        } else {
            foreach ($ep in $it.Episodes) {
                $code = 'S{0:D2}E{1:D2}' -f $ep.Season, $ep.Episode
                [pscustomobject]@{ File = $ep.File; Item = $it; Base = $code; Label = "$($it.Title) $code - $($ep.Title)"; Kind = 'Episode' }
            }
        }
    }
}

# Starts the log in logs\<name>-<time>.log. Returns the log path.
function Start-WatchLog([string]$name) {
    if (-not (Test-Path -LiteralPath $LogDir)) { [void](New-Item -ItemType Directory -Path $LogDir) }
    $log = Join-Path $LogDir "$name-$Stamp.log"
    try { Start-Transcript -LiteralPath $log | Out-Null } catch {}
    return $log
}
#endregion
