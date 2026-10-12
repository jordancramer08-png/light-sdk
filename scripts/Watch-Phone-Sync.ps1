#===========================================================================
#  Watch - Phone Sync (PC side)
#  Pick movies from D:\Movies and TV seasons from D:\TV, check that the
#  Light Phone III can play them, fix the ones it can't (on copies only),
#  and send them to /sdcard/Watch in the layout the Watch app reads
#  (CLAUDE.md section 4).
#
#  Nothing in D:\Movies or D:\TV is ever changed. All work happens in
#  %TEMP%\watch-<time>, which is deleted at the end.
#
#  Test mode (no phone): set WATCH_DRY_RUN=<folder> and the finished
#  layout is written to <folder>\Watch instead of the phone.
#
#  Other switches (for testing):
#    -Check <file> <file> ...   only run the library check on these files
#    WATCH_MOVIES_ROOT / WATCH_TV_ROOT   use other library folders
#===========================================================================
param(
    [switch]$Check,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$CheckFiles
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}
$OutputEncoding = [Text.Encoding]::UTF8

#---------------------------------------------------------------------------
# region  SETTINGS
#---------------------------------------------------------------------------
$MoviesRoot = 'D:\Movies'
$TvRoot     = 'D:\TV'
$PhoneRoot  = '/sdcard/Watch'
$PhoneTmp   = '/data/local/tmp/watch-xfer'
$AppPackage = 'com.jordan.watch'
$CapBytes   = 50GB
$AdbPath    = ''          # leave blank to find adb.exe automatically

if ($env:WATCH_MOVIES_ROOT) { $MoviesRoot = $env:WATCH_MOVIES_ROOT }
if ($env:WATCH_TV_ROOT)     { $TvRoot = $env:WATCH_TV_ROOT }
if ($env:WATCH_ADB)         { $AdbPath = $env:WATCH_ADB }
$DryRoot = $null
if ($env:WATCH_DRY_RUN) { $DryRoot = Join-Path $env:WATCH_DRY_RUN 'Watch' }

$RepoRoot = Split-Path -Parent $PSScriptRoot
$LogDir   = Join-Path $RepoRoot 'logs'
$Stamp    = Get-Date -Format 'yyyyMMdd-HHmmss'
$WorkDir  = Join-Path ([IO.Path]::GetTempPath()) "watch-$Stamp"

$VideoExts   = @('.mp4', '.m4v', '.mkv', '.webm', '.mov', '.avi', '.wmv', '.mpg', '.mpeg', '.ts', '.flv')
$GoodVideo   = @('h264', 'hevc', 'vp9')           # 8-bit only
$GoodAudio   = @('aac', 'mp3', 'opus', 'vorbis', 'flac')
$TextSubs    = @('subrip', 'ass', 'ssa', 'mov_text', 'webvtt', 'text')
$CoverCodecs = @('mjpeg', 'png', 'bmp', 'gif')
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
    foreach ($root in @($MoviesRoot, $TvRoot)) {
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
# region  LIBRARY SCAN  (D:\Movies and D:\TV, read only)
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

function Get-Movies {
    $items = New-Object System.Collections.Generic.List[object]
    if (-not (Test-Path -LiteralPath $MoviesRoot)) { return $items }
    $ids = @{}
    $files = Get-ChildItem -LiteralPath $MoviesRoot -Recurse -File |
        Where-Object { $VideoExts -contains $_.Extension.ToLowerInvariant() } | Sort-Object FullName
    foreach ($f in $files) {
        $collection = $null
        if ($f.Directory.FullName.TrimEnd('\') -ne ([IO.Path]::GetFullPath($MoviesRoot)).TrimEnd('\')) {
            $collection = $f.Directory.Name
        }
        $stem = [IO.Path]::GetFileNameWithoutExtension($f.Name)
        $number = $null
        if ($stem -match '^(\d{1,3})\s*-\s*(.+)$') { $number = [int]$Matches[1]; $stem = $Matches[2] }
        $ty = Split-TitleYear $stem
        $folder = Get-SafeName $ty.Base
        if ($ty.Year) { $folder = "$folder ($($ty.Year))" }
        $group = '_Singles'
        if ($collection) { $group = Get-SafeName $collection }
        $id = "$group/$folder"
        if ($ids.ContainsKey($id)) { $k = 2; while ($ids.ContainsKey("$id $k")) { $k++ }; $folder = "$folder $k"; $id = "$group/$folder" }
        $ids[$id] = $true
        $label = $ty.Title
        if ($ty.Year) { $label = "$label ($($ty.Year))" }
        if ($collection) {
            $c = $collection
            if ($number) { $c = "$collection #$number" }
            $label = "$label  [$c]"
        }
        $items.Add([pscustomobject]@{
            Kind = 'Movie'; Label = $label; Search = (Fold "$label $($f.Name)")
            Title = $ty.Title; Year = $ty.Year; Collection = $collection; Number = $number
            Id = $id; PhoneDir = "Movies/$id"; Files = @($f); Bytes = [double]$f.Length
            Source = $f.FullName.Substring($MoviesRoot.TrimEnd('\').Length + 1)
            OnPhone = $false; Selected = $false
        })
    }
    return $items
}

# One item per season: D:\TV\<Show>\Season 01\<Show> - S01E01 - <Title>.mp4
function Get-Seasons {
    $items = New-Object System.Collections.Generic.List[object]
    if (-not (Test-Path -LiteralPath $TvRoot)) { return $items }
    foreach ($show in (Get-ChildItem -LiteralPath $TvRoot -Directory | Sort-Object Name)) {
        $files = @(Get-ChildItem -LiteralPath $show.FullName -Recurse -File |
            Where-Object { $VideoExts -contains $_.Extension.ToLowerInvariant() })
        $eps = @()
        foreach ($f in $files) {
            $stem = [IO.Path]::GetFileNameWithoutExtension($f.Name)
            if ($stem -notmatch '[Ss](\d{1,3})[Ee](\d{1,4})') { continue }
            $s = [int]$Matches[1]; $e = [int]$Matches[2]
            $epTitle = "Episode $e"
            if ($stem -match '[Ss]\d{1,3}[Ee]\d{1,4}\s*-\s*(.+)$') { $epTitle = ($Matches[1] -replace ' - ', ': ').Trim() }
            $eps += [pscustomobject]@{ Season = $s; Episode = $e; Title = $epTitle; File = $f }
        }
        $showName = Get-SafeName $show.Name
        $ty = Split-TitleYear $show.Name
        foreach ($g in ($eps | Group-Object Season | Sort-Object { [int]$_.Name })) {
            $s = [int]$g.Name
            $list = @($g.Group | Sort-Object Episode)
            $bytes = 0.0; foreach ($e in $list) { $bytes += $e.File.Length }
            $seasonDir = 'Season {0:D2}' -f $s
            $label = "$($ty.Title) - Season $s ($($list.Count) episodes)  [TV]"
            $items.Add([pscustomobject]@{
                Kind = 'Season'; Label = $label; Search = (Fold $label)
                Title = $ty.Title; Year = $ty.Year; ShowName = $showName; Season = $s; Episodes = $list
                Id = "$showName/$seasonDir"; PhoneDir = "TV/$showName/$seasonDir"
                Files = @($list | ForEach-Object { $_.File }); Bytes = $bytes
                Source = "$($show.Name)\$seasonDir"
                OnPhone = $false; Selected = $false
            })
        }
    }
    return $items
}

function Ensure-TvFolder {
    if ($env:WATCH_TV_ROOT -or (Test-Path -LiteralPath $TvRoot)) { return }
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

# Looks at one file. $quality = 'Original' / '1080p' / '720p'.
# Returns what's wrong (Problems), what will be done (Actions) and how (the plan fields).
function Test-Media([string]$path, [string]$quality) {
    $r = [pscustomobject]@{
        Path = $path; Ok = $true; CantFix = $null; Problems = @(); Actions = @(); Notes = @()
        Duration = 0.0; Width = $null; Height = $null; VideoCodec = $null; AudioCodec = $null
        MainVideo = $null; Cover = $null; Audio = @(); BadAudio = @(); Subs = @()
        EncodeVideo = $false; Shrink = $false; Remux = $false; OutExt = ''
        NeedsWork = $false; Estimate = [double](Get-Item -LiteralPath $path).Length
    }
    $p = Get-Probe $path
    if (-not $p) { $r.Ok = $false; $r.CantFix = "ffprobe can't read this file (damaged?)"; return $r }
    $fmtDur = Get-Prop $p.format 'duration'
    if ($fmtDur) { $r.Duration = [double]$fmtDur }
    $ext = [IO.Path]::GetExtension($path).ToLowerInvariant()

    foreach ($s in @($p.streams)) {
        $type = Get-Prop $s 'codec_type'; $codec = Get-Prop $s 'codec_name'
        $disp = Get-Prop $s 'disposition'
        if ($type -eq 'video') {
            $isCover = ((Get-Prop $disp 'attached_pic') -eq 1) -or ($CoverCodecs -contains $codec)
            if ($isCover) { if ($null -eq $r.Cover) { $r.Cover = $s } }
            elseif ($null -eq $r.MainVideo) { $r.MainVideo = $s }
        } elseif ($type -eq 'audio') { $r.Audio += $s }
        elseif ($type -eq 'subtitle') {
            if ($TextSubs -contains $codec) { $r.Subs += $s }
            else { $r.Notes += "picture subtitles ($codec) left out - the phone can't show them" }
        }
    }
    if ($null -eq $r.MainVideo) { $r.Ok = $false; $r.CantFix = 'no picture track found'; return $r }

    $v = $r.MainVideo
    $r.VideoCodec = Get-Prop $v 'codec_name'
    $r.Width = Get-Prop $v 'width'; $r.Height = Get-Prop $v 'height'
    $pix = [string](Get-Prop $v 'pix_fmt')
    if ($r.Audio.Count -gt 0) { $r.AudioCodec = Get-Prop $r.Audio[0] 'codec_name' } else { $r.Notes += 'no sound track' }

    # Container
    if (@('.mp4', '.m4v', '.mkv', '.webm', '.mov') -notcontains $ext) {
        $r.Problems += "$($ext.TrimStart('.').ToUpper()) file (the phone can't open it)"
        $r.Remux = $true
    }
    # Picture
    $tenBit = $pix -match '10|12'
    if ($GoodVideo -notcontains $r.VideoCodec) {
        $r.Problems += "$($r.VideoCodec) picture (not playable on the phone)"; $r.EncodeVideo = $true
    } elseif ($tenBit) {
        $r.Problems += "10-bit $($r.VideoCodec.ToUpper()) picture (the phone can't decode 10-bit)"; $r.EncodeVideo = $true
    }
    # Sound
    for ($i = 0; $i -lt $r.Audio.Count; $i++) {
        $c = Get-Prop $r.Audio[$i] 'codec_name'
        if ($GoodAudio -notcontains $c) { $r.BadAudio += $i }
    }
    if ($r.BadAudio.Count -gt 0) {
        $names = @($r.BadAudio | ForEach-Object { (Get-Prop $r.Audio[$_] 'codec_name').ToUpper() }) | Select-Object -Unique
        $r.Problems += (($names -join '/') + ' sound (the phone has no decoder)')
    }
    # Cover picture stored as a video track
    if ($r.Cover) { $r.Problems += 'cover picture stored as a video track' }

    # Quality: shrink only when the file is bigger than the chosen size
    $q = $Qualities[$quality]
    if ($q -and $r.Duration -gt 0) {
        $targetBytes = ($q.VideoK + $q.AudioK) * 1000 / 8 * $r.Duration
        $tooTall = $r.Height -and ($r.Height -gt $q.Height * 1.05)
        if ($tooTall -or $r.Estimate -gt $targetBytes * 1.2) {
            $r.Shrink = $true; $r.EncodeVideo = $true; $r.Estimate = $targetBytes
        }
    }

    # Output: MKV stays MKV, everything else becomes MP4
    $r.OutExt = '.mp4'
    if (@('.mkv', '.webm') -contains $ext) { $r.OutExt = '.mkv' }
    if ($r.Remux -and -not $r.EncodeVideo) { $r.Actions += 'copy into an MP4 (no quality loss)' }
    if ($r.Shrink) { $r.Actions += "shrink to $quality (H.264)" }
    elseif ($r.EncodeVideo) { $r.Actions += 're-encode picture to 8-bit H.264' }
    if ($r.BadAudio.Count -gt 0) { $r.Actions += 'convert only the sound to AAC stereo' }
    if ($r.Cover) { $r.Actions += 'save the cover as poster.jpg and strip it from the video' }
    $r.NeedsWork = $r.Remux -or $r.EncodeVideo -or ($r.BadAudio.Count -gt 0) -or [bool]$r.Cover
    if (-not $r.NeedsWork) { $r.Actions += 'none - sent as it is' }
    return $r
}

function Write-CheckResult($r, [string]$name) {
    Write-Host ''
    Write-Host "  $name" -ForegroundColor White
    if ($r.CantFix) { Write-Bad "    Can't send: $($r.CantFix)"; return }
    $h = '?'; if ($r.Height) { $h = "$($r.Height)p" }
    Write-Note ("    {0} {1} / {2} / {3:N0} min / {4}" -f $h, $r.VideoCodec, $r.AudioCodec, ($r.Duration / 60), (Format-Size (Get-Item -LiteralPath $r.Path).Length))
    if ($r.Problems.Count -eq 0) { Write-Good '    OK - the phone can play it.' }
    foreach ($p in $r.Problems) { Write-Warn2 "    Problem: $p" }
    foreach ($a in $r.Actions)  { Write-Host "    Fix: $a" }
    foreach ($n in $r.Notes)    { Write-Note "    Note: $n" }
}

# Runs ffmpeg for one file. Writes into $outDir (always a temp folder). Returns the new video path.
function Invoke-Fix($r, [string]$quality, [string]$outDir, [string]$baseName) {
    Assert-SafeTarget $outDir
    if (-not (Test-Path -LiteralPath $outDir)) { [void](New-Item -ItemType Directory -Path $outDir -Force) }
    $out = Join-Path $outDir ($baseName + $r.OutExt)
    $vIndex = Get-Prop $r.MainVideo 'index'
    $a = @('-hide_banner', '-loglevel', 'error', '-stats', '-y')
    if ($r.Remux) { $a += @('-fflags', '+genpts') }
    $a += @('-i', $r.Path, '-map', "0:$vIndex", '-map', '0:a?')
    foreach ($s in $r.Subs) { $a += @('-map', "0:$(Get-Prop $s 'index')") }
    $a += @('-map_chapters', '0')

    if ($r.EncodeVideo) {
        $a += @('-c:v', 'libx264', '-preset', 'medium', '-pix_fmt', 'yuv420p', '-profile:v', 'high')
        $q = $Qualities[$quality]
        if ($r.Shrink) {
            if ($r.Height -gt $q.Height) { $a += @('-vf', "scale=-2:$($q.Height)") }
            $a += @('-b:v', "$($q.VideoK)k", '-maxrate', "$([int]($q.VideoK * 1.5))k", '-bufsize', "$($q.VideoK * 2)k")
        } else {
            $a += @('-crf', '20')
        }
    } else {
        $a += @('-c:v', 'copy')
    }
    # Sound: copy the good tracks, AAC for the rest (all of them when shrinking)
    $a += @('-c:a', 'copy')
    for ($i = 0; $i -lt $r.Audio.Count; $i++) {
        if ($r.Shrink -or ($r.BadAudio -contains $i)) {
            $k = 160; if ($r.Shrink) { $k = $Qualities[$quality].AudioK }
            $a += @("-c:a:$i", 'aac', "-b:a:$i", "${k}k", "-ac:a:$i", '2')
        }
    }
    if ($r.Subs.Count -gt 0) {
        if ($r.OutExt -eq '.mp4') { $a += @('-c:s', 'mov_text') } else { $a += @('-c:s', 'copy') }
    }
    if ($r.OutExt -eq '.mp4') { $a += @('-movflags', '+faststart') }
    $a += @('-max_muxing_queue_size', '4096', $out)

    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { & $script:Ffmpeg @a; $code = $LASTEXITCODE } finally { $ErrorActionPreference = $old }
    if ($code -ne 0 -or -not (Test-Path -LiteralPath $out)) { throw "ffmpeg could not fix $($r.Path) (code $code)" }
    return $out
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
# region  JSON FILES  (CLAUDE.md section 5)
#---------------------------------------------------------------------------
function ConvertTo-Json2($o) { return ($o | ConvertTo-Json -Depth 5) }

function New-MovieJson($item, $r, [string]$videoName, [bool]$hasPoster, [double]$bytes) {
    $ac = $r.AudioCodec; if ($r.BadAudio -contains 0 -or $r.Shrink) { $ac = 'aac' }
    $vc = $r.VideoCodec; if ($r.EncodeVideo) { $vc = 'h264' }
    $w = $r.Width; $h = $r.Height
    if ($r.Shrink -and $h -gt $Qualities[$script:Quality].Height) {
        $nh = $Qualities[$script:Quality].Height; $w = [int]([math]::Round($w * $nh / $h / 2) * 2); $h = $nh
    }
    $poster = $null; if ($hasPoster) { $poster = 'poster.jpg' }
    $o = [ordered]@{
        schema = 1; id = $item.Id; title = $item.Title; year = $item.Year
        collection = $item.Collection; collectionNumber = $item.Number
        overview = ''; genres = @(); runtimeMin = [int][math]::Round($r.Duration / 60)
        rating = $null; contentRating = $null; director = $null; cast = @()
        poster = $poster; backdrop = $null; file = $videoName
        width = $w; height = $h; videoCodec = $vc; audioCodec = $ac
        bytes = [long]$bytes; sentAt = (Get-Date -Format 'yyyy-MM-ddTHH:mm:ss')
        source = $item.Source
    }
    return ConvertTo-Json2 $o
}

function New-ShowJson($item) {
    $o = [ordered]@{ schema = 1; id = $item.ShowName; title = $item.Title; year = $item.Year
        overview = ''; genres = @(); contentRating = $null; rating = $null; cast = @(); poster = $null }
    return ConvertTo-Json2 $o
}

function New-EpisodeJson($item, $ep, $r, [string]$videoName) {
    $code = 'S{0:D2}E{1:D2}' -f $ep.Season, $ep.Episode
    $o = [ordered]@{ schema = 1; id = "$($item.ShowName)/$code"; title = $ep.Title
        season = $ep.Season; episode = $ep.Episode; overview = ''
        runtimeMin = [int][math]::Round($r.Duration / 60); airDate = $null; still = $null; file = $videoName }
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
function Select-Titles($items, $info) {
    $filter = ''
    while ($true) {
        $shown = @($items)
        if ($filter) {
            $words = @((Fold $filter) -split '\s+' | Where-Object { $_ })
            $shown = @($items | Where-Object { $it = $_; @($words | Where-Object { $it.Search -notlike "*$_*" }).Count -eq 0 })
        }
        Write-Title 'Pick what goes to the phone'
        if ($filter) { Write-Note "  Search: '$filter'   (C clears it)" }
        if ($shown.Count -eq 0) { Write-Warn2 '  Nothing matches.' }
        for ($i = 0; $i -lt $shown.Count; $i++) {
            $it = $shown[$i]
            $mark = '[ ]'; $c = 'Gray'
            if ($it.Selected) { $mark = '[x]'; $c = 'Green' }
            $tag = ''; if ($it.OnPhone) { $tag = '  (on phone)'; if (-not $it.Selected) { $c = 'DarkGray' } }
            Write-Host ('{0,4} {1} ' -f ($i + 1), $mark) -NoNewline -ForegroundColor DarkCyan
            Write-Host ('{0,9}  {1}{2}' -f (Format-Size $it.Bytes), $it.Label, $tag) -ForegroundColor $c
        }
        Write-Host ''
        Write-Totals $items $info
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
            $after = $info.Used + (Get-SelectedBytes $items) + $it.Bytes
            if ($after -gt $CapBytes) {
                Write-Bad ("  Not picked: {0} - it would go over the {1} cap." -f $it.Label, (Format-Size $CapBytes))
                continue
            }
            $it.Selected = $true
        }
    }
}

function Read-Quality {
    Write-Title 'Quality'
    Write-Host '  1  Original      the file as it is (only fixed if the phone needs it)'
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
            Write-Bad "  Failed: $($_.Exception.Message)"
            Write-Bad '  This title was skipped; the rest carry on.'
        }
    }
    return $sent
}

# Fixes one file if needed into $stage; returns @{ Path; Result; Poster }
function Prepare-File($file, [string]$stage, [string]$baseName) {
    $r = Test-Media $file.FullName $script:Quality
    if ($r.CantFix) { throw $r.CantFix }
    foreach ($p in $r.Problems) { Write-Warn2 "  Found: $p" }
    foreach ($n in $r.Notes) { Write-Note "  Note: $n" }
    $poster = $null
    if ($r.Cover) {
        $pp = Join-Path $stage 'poster.jpg'
        if (Save-Cover $r $pp) { $poster = $pp; Write-Note '  Saved the cover as poster.jpg.' }
    }
    if (-not $r.NeedsWork) { return @{ Path = $file.FullName; Result = $r; Poster = $poster } }
    foreach ($a in $r.Actions) { Write-Host "  Fixing: $a" }
    if ($r.EncodeVideo) { Write-Note ('  Re-encoding takes a while (roughly as long as the film on a fast PC).') }
    $out = Invoke-Fix $r $script:Quality $stage $baseName
    return @{ Path = $out; Result = $r; Poster = $poster }
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

function Send-Movie($item, [double]$usedNow) {
    $stage = Join-Path $WorkDir 'stage'
    if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
    [void](New-Item -ItemType Directory -Path $stage)
    $prep = Prepare-File $item.Files[0] $stage 'movie'
    $video = Get-Item -LiteralPath $prep.Path
    $videoName = 'movie' + $prep.Result.OutExt
    if ($prep.Path -eq $item.Files[0].FullName) { $videoName = 'movie' + $video.Extension.ToLowerInvariant() }
    $total = [double]$video.Length
    if ($prep.Poster) { $total += (Get-Item -LiteralPath $prep.Poster).Length }
    Test-RealSize $total $prep.Result.Estimate $usedNow
    Write-Host "  Sending $videoName ($(Format-Size $video.Length))..."
    if ($prep.Poster) { Send-File $prep.Poster $item.PhoneDir 'poster.jpg' }
    Send-File $video.FullName $item.PhoneDir $videoName
    $json = New-MovieJson $item $prep.Result $videoName ([bool]$prep.Poster) $video.Length
    Send-Text $json $item.PhoneDir 'movie.json'
    Remove-Item -LiteralPath $stage -Recurse -Force
    return $total
}

function Send-Season($item, [double]$usedNow, $shows) {
    $showDir = "TV/$($item.ShowName)"
    if (-not $shows.ContainsKey($item.ShowName)) {
        Send-Text (New-ShowJson $item) $showDir 'show.json'
        $shows[$item.ShowName] = $true
    }
    $total = 0.0
    foreach ($ep in $item.Episodes) {
        $code = 'S{0:D2}E{1:D2}' -f $ep.Season, $ep.Episode
        Write-Host "  $code $($ep.Title)"
        $stage = Join-Path $WorkDir 'stage'
        if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
        [void](New-Item -ItemType Directory -Path $stage)
        $prep = Prepare-File $ep.File $stage $code
        $video = Get-Item -LiteralPath $prep.Path
        $videoName = $code + $video.Extension.ToLowerInvariant()
        Test-RealSize ([double]$video.Length) $prep.Result.Estimate ($usedNow + $total)
        Send-File $video.FullName $item.PhoneDir $videoName
        Send-Text (New-EpisodeJson $item $ep $prep.Result $videoName) $item.PhoneDir "$code.json"
        $total += $video.Length
        Remove-Item -LiteralPath $stage -Recurse -Force
    }
    return $total
}

# Re-estimates the picked items at the chosen quality (needs ffprobe on each file).
function Get-Estimate($picked, [string]$quality) {
    $total = 0.0
    $problems = 0
    foreach ($item in $picked) {
        foreach ($f in $item.Files) {
            $r = Test-Media $f.FullName $quality
            $total += $r.Estimate
            if ($r.CantFix -or $r.Problems.Count -gt 0) { $problems++ }
            if ($r.CantFix -or $r.Problems.Count -gt 0) { Write-CheckResult $r $f.Name }
        }
    }
    return @{ Bytes = $total; Problems = $problems }
}
#endregion

#---------------------------------------------------------------------------
# region  MAIN
#---------------------------------------------------------------------------
function Show-WhatItDoes {
    Write-Host ''
    Write-Host '  What this does when the phone is plugged in:'
    Write-Host '    1. Lists every movie in D:\Movies and every TV season in D:\TV.'
    Write-Host '    2. You pick titles; it keeps a running total against the 50 GB cap.'
    Write-Host '    3. You pick a quality: Original, 1080p or 720p small.'
    Write-Host '    4. It checks each file and fixes what the phone cannot play,'
    Write-Host '       on copies in your TEMP folder. D:\Movies and D:\TV are never changed.'
    Write-Host '    5. It copies them to the phone into /sdcard/Watch.'
}

function Invoke-CheckOnly([string[]]$paths) {
    Write-Title 'Library check (nothing is changed or sent)'
    foreach ($p in $paths) {
        $r = Test-Media $p 'Original'
        Write-CheckResult $r ([IO.Path]::GetFileName($p))
    }
}

function Main {
    Write-Title 'Watch - Phone Sync'
    $script:Ffmpeg = Find-Ffmpeg 'ffmpeg'
    $script:Ffprobe = Find-Ffmpeg 'ffprobe'
    if (-not $script:Ffmpeg -or -not $script:Ffprobe) {
        Write-Bad '  ffmpeg is not installed. It is needed to check and fix videos.'
        Write-Host '  To install it, open PowerShell and type:  winget install Gyan.FFmpeg'
        Write-Host '  Then close this window and double-click Watch-Phone-Sync.cmd again.'
        return
    }
    if ($Check) { Invoke-CheckOnly @($CheckFiles); return }

    Ensure-TvFolder

    if ($DryRoot) {
        Write-Warn2 "  TEST MODE: nothing goes to the phone. The layout is written to $DryRoot"
        if (-not (Test-Path -LiteralPath $env:WATCH_DRY_RUN)) { [void](New-Item -ItemType Directory -Path $env:WATCH_DRY_RUN -Force) }
        Assert-SafeTarget $DryRoot
    } else {
        $script:Adb = Find-Adb
        if (-not $script:Adb) {
            Write-Bad '  adb.exe (Android platform-tools) was not found. Set $AdbPath near the top of Watch-Phone-Sync.ps1.'
            Show-WhatItDoes
            return
        }
        Write-Note "  adb: $($script:Adb)"
        if (-not (Connect-Phone)) { Show-WhatItDoes; Write-Host ''; Write-Host '  Stopped: nothing was changed.'; return }
        Write-Good "  Phone connected ($($script:Serial))."
    }

    Write-Step 'Reading D:\Movies and D:\TV (read only)'
    $items = @(Get-Movies) + @(Get-Seasons)
    Write-Host "  Found $(@($items | Where-Object Kind -eq 'Movie').Count) movies and $(@($items | Where-Object Kind -eq 'Season').Count) TV seasons."
    if ($items.Count -eq 0) { Write-Warn2 '  Nothing to send.'; return }

    Write-Step 'Looking at what is already there'
    $info = Get-TargetInfo
    foreach ($it in $items) { $it.OnPhone = $info.Dirs.ContainsKey($it.PhoneDir) }
    Write-Host ("  Watch uses {0}; {1} titles/seasons are already there." -f (Format-Size $info.Used), @($items | Where-Object OnPhone).Count)

    $picked = @(Select-Titles $items $info)
    if ($picked.Count -eq 0) { Write-Host '  Nothing picked. Stopped: nothing was changed.'; return }

    $script:Quality = Read-Quality
    Write-Step "Checking the $($picked.Count) picked title(s) at $($script:Quality)"
    $est = Get-Estimate $picked $script:Quality
    $after = $info.Used + $est.Bytes
    Write-Host ''
    Write-Host ("  Estimated size to send: {0}. Watch would then use {1} of the {2} cap." -f (Format-Size $est.Bytes), (Format-Size $after), (Format-Size $CapBytes))
    if ($est.Problems -eq 0) { Write-Good '  Library check: every picked file can go as it is.' }
    else { Write-Warn2 "  Library check: $($est.Problems) file(s) need fixing (listed above); it is done on copies." }
    if ($after -gt $CapBytes) { Write-Bad '  That is over the 50 GB cap. Pick less or a smaller quality, then run this again.'; return }
    if ($null -ne $info.Free -and $est.Bytes -gt $info.Free) { Write-Bad '  There is not enough free space for that.'; return }

    $replacing = @($picked | Where-Object OnPhone)
    if ($replacing.Count -gt 0) {
        Write-Warn2 "  $($replacing.Count) of these are already there and would be replaced."
        Write-Note '  Watched marks and positions are kept (they live in .state).'
        if (-not (Read-YesNo '  Replace them?')) { $picked = @($picked | Where-Object { -not $_.OnPhone }) }
        if ($picked.Count -eq 0) { Write-Host '  Nothing left to send.'; return }
    }
    if (-not (Read-YesNo "  Send $($picked.Count) title(s) now?")) { Write-Host '  Stopped: nothing was changed.'; return }

    [void](New-Item -ItemType Directory -Path $WorkDir -Force)
    $sent = Send-Items $picked $info
    Send-Text (Get-Date -Format 'yyyy-MM-ddTHH:mm:ss') '.state' 'last-sync.txt'
    if (-not $DryRoot) { [void](Invoke-Phone ("rm -rf " + (Q $PhoneTmp))) }
    Write-Host ''
    Write-Good "  Done: $sent of $($picked.Count) sent."
    if ($DryRoot) { Write-Host "  Look in $DryRoot to see the layout." }
    else { Write-Host '  Open Watch on the phone; it shows "Updating library..." and then the new titles.' }
}

if (-not (Test-Path -LiteralPath $LogDir)) { [void](New-Item -ItemType Directory -Path $LogDir) }
$logFile = Join-Path $LogDir "sync-$Stamp.log"
try { Start-Transcript -LiteralPath $logFile | Out-Null } catch {}
try {
    Main
} catch {
    Write-Bad "  Something went wrong: $($_.Exception.Message)"
    Write-Note $_.ScriptStackTrace
    exit 1
} finally {
    if (Test-Path -LiteralPath $WorkDir) { Remove-Item -LiteralPath $WorkDir -Recurse -Force -ErrorAction SilentlyContinue }
    try { Stop-Transcript | Out-Null } catch {}
    Write-Note "  Log: $logFile"
}
#endregion
