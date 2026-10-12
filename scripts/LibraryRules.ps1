#===========================================================================
#  Rules for the library report, the PC staging folder, the smoke test and
#  "why won't it play" (Session 11b). Pure: they look at ffprobe answers and
#  plain values, never run ffmpeg, never touch files or the phone.
#  Needs WatchSafe.ps1 dot-sourced first. Tests: scripts\tests\Test-LibraryTools.ps1
#===========================================================================

# Picture bitrate a CRF 20 H.264 re-encode usually lands at, for 1920x1080 (kbit/s).
# Smaller pictures scale with their pixel count. It is an estimate, shown as one.
$CrfKbps1080 = 5000
$MinVideoKbps = 600
# Converted sound (the same numbers Invoke-Fix uses)
$NewAudioKbpsStereo = 160
$NewAudioKbps51     = 384
# 720p small (Watch-Phone-Sync's '720p' quality)
$SmallVideoKbps = 1850; $SmallAudioKbps = 128; $SmallHeight = 720
# Speeds for the time estimate
$CopyBytesPerSec  = 60MB      # remux = read + write on the same disk
$AudioEncodeSpeed = 150       # media seconds converted per second (AAC is quick)
$DefaultSecPerSec1080 = 2.5   # until measured: seconds of work per second of 1080p film

# Status names (also the report's colour key)
$StatusOk    = 'OK'
$StatusQuick = 'Needs a quick fix'
$StatusFull  = 'Needs a full convert'
$StatusBad   = "Can't play"

#--- probe cache lines ------------------------------------------------------
# One line per file in private\probe-cache.tsv:  key <TAB> boxes <TAB> ffprobe json (one line)
# The key changes when the file's path, size or time changes, so a changed file is checked again.
function Get-ProbeKey([string]$fullPath, [long]$bytes, [long]$ticks) {
    return "$($fullPath.ToLowerInvariant())|$bytes|$ticks"
}

function ConvertTo-ProbeLine([string]$key, [string[]]$boxes, [string]$json) {
    $flat = ($json -replace '[\r\n\t]+', ' ').Trim()
    return "$key`t$(@($boxes) -join ',')`t$flat"
}

# Returns @{ Key; Boxes; Json } or $null for a broken line (e.g. cut off by Ctrl+C).
function ConvertFrom-ProbeLine([string]$line) {
    $parts = $line.Split("`t")
    if ($parts.Count -ne 3 -or -not $parts[0]) { return $null }
    $json = $parts[2]
    if ($json -and -not $json.EndsWith('}')) { return $null }
    $boxes = @(); if ($parts[1]) { $boxes = @($parts[1].Split(',')) }
    return @{ Key = $parts[0]; Boxes = $boxes; Json = $json }
}

#--- labels -----------------------------------------------------------------
function Get-ChannelLabel([int]$ch) {
    switch ($ch) { 1 { return 'mono' } 2 { return 'stereo' } 6 { return '5.1' } 8 { return '7.1' } }
    return "$ch ch"
}

function Get-CodecLabel([string]$codec) {
    $map = @{ h264 = 'H.264'; hevc = 'HEVC'; mpeg4 = 'MPEG-4'; msmpeg4v3 = 'DivX 3'; mpeg2video = 'MPEG-2'; vc1 = 'VC-1'
        vp9 = 'VP9'; av1 = 'AV1'; aac = 'AAC'; ac3 = 'AC3'; eac3 = 'E-AC3'; dts = 'DTS'; truehd = 'TrueHD'; mp3 = 'MP3'
        opus = 'Opus'; vorbis = 'Vorbis'; flac = 'FLAC'; wmav2 = 'WMA'; wmv3 = 'WMV' }
    if (-not $codec) { return '?' }
    if ($map.ContainsKey($codec)) { return $map[$codec] }
    return $codec.ToUpperInvariant()
}

function Get-PictureLabel($v) {
    if (-not $v) { return 'none' }
    $h = [int](Get-P $v 'height')
    $label = "${h}p $(Get-CodecLabel ([string](Get-P $v 'codec_name')))"
    if ([string](Get-P $v 'pix_fmt') -match '10|12') { $label += ' 10-bit' }
    return $label
}

function Get-SoundLabel($audio) {
    $parts = @()
    foreach ($a in @($audio)) { if ($a) { $parts += "$(Get-CodecLabel ([string](Get-P $a 'codec_name'))) $(Get-ChannelLabel ([int](Get-P $a 'channels')))" } }
    if ($parts.Count -eq 0) { return 'none' }
    return ($parts -join ', ')
}

# 7385 -> "2 h 3 min"; 95 -> "2 min"; 0 -> "-"
function Format-Duration([double]$sec) {
    if ($sec -le 0) { return '-' }
    if ($sec -lt 60) { return '1 min' }
    $m = [int][math]::Round($sec / 60)
    if ($m -lt 60) { return "$m min" }
    $h = [math]::Floor($m / 60); $m = $m % 60
    if ($m -eq 0) { return "$h h" }
    return "$h h $m min"
}

#--- size and time estimates ------------------------------------------------
# Bitrate of one sound track in kbit/s: ffprobe's number, MKV's BPS tag, or a typical value.
function Get-AudioKbps($a) {
    $br = Get-P $a 'bit_rate'
    if ($br -and [double]$br -gt 0) { return [double]$br / 1000 }
    $tags = Get-P $a 'tags'
    foreach ($n in @('BPS', 'BPS-eng')) { $t = Get-P $tags $n; if ($t -and [double]$t -gt 0) { return [double]$t / 1000 } }
    $ch = [int](Get-P $a 'channels')
    switch ([string](Get-P $a 'codec_name')) {
        'ac3'    { if ($ch -ge 6) { return 448 }; return 192 }
        'eac3'   { return 640 }
        'dts'    { return 1509 }
        'truehd' { return 3000 }
        'flac'   { return 900 }
    }
    if ($ch -ge 6) { return 384 }
    return 160
}

function Get-NewAudioKbps($a) {
    if ((Get-SafeChannels ([int](Get-P $a 'channels'))) -eq 6) { return $NewAudioKbps51 }
    return $NewAudioKbpsStereo
}

# Picture size after fitting inside a box (never made bigger). Returns @(width, height).
function Get-FitSize([int]$w, [int]$h, [int]$maxW, [int]$maxH) {
    if ($w -le 0 -or $h -le 0) { return @($maxW, $maxH) }
    $f = [math]::Min(1.0, [math]::Min($maxW / $w, $maxH / $h))
    return @([int]([math]::Round($w * $f / 2) * 2), [int]([math]::Round($h * $f / 2) * 2))
}

# Seconds of work to make one file Watch-safe. $work = none / remux / audio / video.
# $secPerSec1080 = measured encode speed (seconds of work per second of 1080p film).
function Get-WorkSeconds([string]$work, [double]$duration, [double]$bytes, [int]$outW, [int]$outH, [double]$secPerSec1080) {
    switch ($work) {
        'none'  { return 0.0 }
        'remux' { return $bytes / $CopyBytesPerSec }
        'audio' { return $bytes / $CopyBytesPerSec + $duration / $AudioEncodeSpeed }
        'video' {
            $px = [math]::Max(0.15, ($outW * $outH) / (1920.0 * 1080.0))
            return $duration * $secPerSec1080 * $px + $bytes / $CopyBytesPerSec
        }
    }
    return 0.0
}

# The report's answer for one file. $probe = ffprobe json object (or $null), $boxes = MP4 box
# order, $ext = '.mkv', $bytes = file size, $secPerSec1080 = encode speed.
function Get-FileVerdict($probe, [string[]]$boxes, [string]$ext, [double]$bytes, [double]$secPerSec1080) {
    $r = [pscustomobject]@{ Status = $StatusBad; Reasons = @(); Work = 'none'; Fix = ''
        Duration = 0.0; Width = 0; Height = 0; Picture = '?'; Sound = '?'
        EstBytes = $bytes; Est720Bytes = $bytes; WorkSeconds = 0.0 }
    if (-not $probe) { $r.Reasons = @("ffprobe can't read it (damaged or not a video)"); $r.Fix = "can't be fixed here"; return $r }
    $d = Get-P (Get-P $probe 'format') 'duration'
    if ($d) { $r.Duration = [double]$d }
    $st = Split-Streams $probe
    $r.Sound = Get-SoundLabel $st.Audio
    if ($st.Videos.Count -eq 0) { $r.Picture = 'none'; $r.Reasons = @('no picture track'); $r.Fix = "can't be fixed here"; return $r }
    $v = $st.Videos[0]
    $r.Width = [int](Get-P $v 'width'); $r.Height = [int](Get-P $v 'height'); $r.Picture = Get-PictureLabel $v
    $r.Reasons = @(Get-SafeProblems $probe $boxes $ext)
    $badAudio = @($st.Audio | Where-Object { Get-AudioProblem $_ })

    if (Get-VideoProblem $v) { $r.Work = 'video' }
    elseif ($badAudio.Count -gt 0) { $r.Work = 'audio' }
    elseif ($r.Reasons.Count -gt 0) { $r.Work = 'remux' }
    switch ($r.Work) {
        'none'  { $r.Status = $StatusOk;    $r.Fix = 'none - sent untouched' }
        'remux' { $r.Status = $StatusQuick; $r.Fix = 'copy into a Watch-safe MP4 (no quality loss)' }
        'audio' { $r.Status = $StatusQuick; $r.Fix = 'convert the sound to AAC, picture copied' }
        'video' { $r.Status = $StatusFull;  $r.Fix = 're-encode the picture to H.264 (1080p at most)' }
    }

    # Size at Original quality
    $sec = $r.Duration
    $audioK = 0.0; $oldBadK = 0.0; $newBadK = 0.0
    foreach ($a in $st.Audio) {
        if (Get-AudioProblem $a) { $oldBadK += Get-AudioKbps $a; $newBadK += Get-NewAudioKbps $a; $audioK += Get-NewAudioKbps $a }
        else { $audioK += Get-AudioKbps $a }
    }
    $fit = Get-FitSize $r.Width $r.Height $SafeMaxWidth $SafeMaxHeight
    if ($r.Work -eq 'video' -and $sec -gt 0) {
        $vk = [math]::Max($MinVideoKbps, $CrfKbps1080 * ($fit[0] * $fit[1]) / (1920.0 * 1080.0))
        $r.EstBytes = ($vk + $audioK) * 1000 / 8 * $sec
    } elseif ($r.Work -eq 'audio' -and $sec -gt 0) {
        $r.EstBytes = [math]::Max($bytes * 0.1, $bytes - ($oldBadK - $newBadK) * 1000 / 8 * $sec)
    }
    # Size at 720p small: shrunk only when bigger than the target (same rule as Test-Media)
    $r.Est720Bytes = $r.EstBytes
    if ($sec -gt 0) {
        $target = ($SmallVideoKbps + $SmallAudioKbps) * 1000 / 8 * $sec
        if ($r.Height -gt $SmallHeight * 1.05 -or $r.EstBytes -gt $target * 1.2) { $r.Est720Bytes = $target }
    }
    $r.WorkSeconds = Get-WorkSeconds $r.Work $sec $bytes $fit[0] $fit[1] $secPerSec1080
    return $r
}

# Adds up report rows: @{ Files; Ok; Quick; Full; Bad; Bytes; EstBytes; Est720Bytes; WorkSeconds }
function Get-ReportTotals($rows) {
    $t = [ordered]@{ Files = 0; Ok = 0; Quick = 0; Full = 0; Bad = 0; Bytes = 0.0; EstBytes = 0.0; Est720Bytes = 0.0; WorkSeconds = 0.0 }
    if ($null -eq $rows) { return $t }
    foreach ($r in $rows) {
        if ($null -eq $r) { continue }
        $t.Files++
        switch ($r.Status) { $StatusOk { $t.Ok++ } $StatusQuick { $t.Quick++ } $StatusFull { $t.Full++ } default { $t.Bad++ } }
        $t.Bytes += [double]$r.Bytes; $t.EstBytes += [double]$r.EstBytes; $t.Est720Bytes += [double]$r.Est720Bytes
        $t.WorkSeconds += [double]$r.WorkSeconds
    }
    return $t
}

#--- staging folder ---------------------------------------------------------
# A staged file is used only when its source is unchanged (same size and time), it was made
# at the same quality, and the staged video is still all there.
function Test-StagedCurrent($manifest, [long]$srcBytes, [long]$srcTicks, [string]$quality, $stagedVideoBytes) {
    if (-not $manifest) { return $false }
    if ([long](Get-P $manifest 'sourceBytes') -ne $srcBytes) { return $false }
    if ([long](Get-P $manifest 'sourceTicks') -ne $srcTicks) { return $false }
    if ([string](Get-P $manifest 'quality') -ne $quality) { return $false }
    if ($null -eq $stagedVideoBytes) { return $false }
    return ([long](Get-P $manifest 'videoBytes') -eq [long]$stagedVideoBytes)
}

# Room check before staging: the estimate plus 10% and 5 GB to spare must fit.
function Test-FitsOnDisk([double]$needBytes, [double]$freeBytes) {
    return ($needBytes * 1.1 + 5GB -le $freeBytes)
}

#--- smoke test -------------------------------------------------------------
# The kinds of file the smoke test sends, in order.
$SmokeKinds = @(
    @{ Name = 'MP4 with AAC sound'; Test = { param($x) $x.Ext -eq '.mp4' -and $x.Kind -eq 'Movie' -and $x.AudioCodecs.Count -gt 0 -and @($x.AudioCodecs | Where-Object { $_ -ne 'aac' }).Count -eq 0 } }
    @{ Name = 'MKV file';           Test = { param($x) $x.Ext -eq '.mkv' -and $x.Kind -eq 'Movie' } }
    @{ Name = 'Former AVI';         Test = { param($x) $x.Ext -eq '.avi' } }
    @{ Name = 'Former AC3 sound';   Test = { param($x) $x.AudioCodecs -contains 'ac3' } }
    @{ Name = '720p picture';       Test = { param($x) $x.Height -ge 690 -and $x.Height -le 760 } }
    @{ Name = '1080p picture';      Test = { param($x) $x.Height -ge 1000 -and $x.Height -le 1090 } }
    @{ Name = 'Marvel TV episode';  Test = { param($x) $x.Kind -eq 'Episode' -and $x.Root -match 'Marvel' } }
)

# Picks one real file per kind, each file used once. $records: @{ Path; Ext; Kind (Movie/Episode);
# Root; AudioCodecs; Height; Duration }. Files shorter than a minute are skipped.
# Returns one @{ Name; Record } per kind ($null Record = none found).
function Select-SmokeExamples($records) {
    $used = @{}
    $chosen = @{}
    $pool = @($records | Where-Object { $_ -and $_.Duration -ge 60 } | Sort-Object Path)
    # The rarest kinds choose first, so a common one (1080p) doesn't take the only Marvel episode
    $byRarity = @($SmokeKinds | Sort-Object { $k = $_; @($pool | Where-Object { & $k.Test $_ }).Count }, { [array]::IndexOf($SmokeKinds, $_) })
    foreach ($k in $byRarity) {
        $pick = $null
        foreach ($x in $pool) {
            if ($used.ContainsKey($x.Path)) { continue }
            if (& $k.Test $x) { $pick = $x; break }
        }
        if ($pick) { $used[$pick.Path] = $true }
        $chosen[$k.Name] = $pick
    }
    $out = @()
    foreach ($k in $SmokeKinds) { $out += @{ Name = $k.Name; Record = $chosen[$k.Name] } }
    return ,$out
}

# Where the 20-second clip starts: 5 minutes in, or a quarter of the way into a short file.
function Get-ClipStart([double]$duration) {
    if ($duration -le 0) { return 0 }
    return [math]::Floor([math]::Min(300, $duration * 0.25))
}

#--- why won't it play ------------------------------------------------------
# One problem (as Get-SafeProblems words it) in plain words: what it means and what the fix does.
function Get-PlainExplanation([string]$reason) {
    switch -Regex ($reason) {
        '^(\w+) file \(Watch-safe is MP4\)' {
            return "The file is in the $($Matches[1]) format. Watch takes MP4 files only. Fix: the picture and sound are copied into an MP4 (quick, nothing is lost)." }
        'index is at the end' {
            return "The MP4's table of contents is at the end of the file, so the phone must read the whole file before it can start or jump. Fix: move it to the front (quick, nothing is lost)." }
        'picture \(Watch-safe is H\.264\)' {
            return "The picture is stored as $(Get-CodecLabel ($reason -split ' ')[0]). Watch-safe pictures are H.264. Fix: re-encode the picture (slow)." }
        '8-bit' {
            return 'The picture uses 10-bit (or 4:2:2/4:4:4) colour. The phone''s video chip only plays 8-bit 4:2:0. Fix: re-encode the picture (slow).' }
        'profile' {
            return 'The picture uses an H.264 profile the phone''s video chip does not play. Fix: re-encode the picture (slow).' }
        'level' {
            return 'The picture is coded at a higher H.264 level than the phone plays (5.1 at most). Fix: re-encode the picture (slow).' }
        'bigger than 1080p' {
            return 'The picture is bigger than 1080p, more than the phone can decode. Fix: re-encode it at 1080p (slow).' }
        '(\S+) sound \(Watch-safe is AAC\)' {
            return "The sound is $($Matches[1]). The phone has no $($Matches[1]) decoder, so it plays with no sound or not at all. Fix: convert the sound to AAC (quick; 5.1 stays 5.1)." }
        'AAC .* sound \(' {
            return 'The sound is an unusual kind of AAC the phone may not play. Fix: convert it to plain AAC (quick).' }
        'channel sound' {
            return 'The sound has more than 6 channels (e.g. 7.1); the phone plays up to 5.1. Fix: mix it down to stereo (quick).' }
        'cover picture' {
            return 'A cover picture is stored inside as a second video track, which can confuse the player. Fix: save it as the poster and take it out.' }
        'subtitles inside' {
            return 'Subtitles are stored inside the file. Fix: save text subtitles as .srt files next to the video (picture subtitles cannot be kept).' }
        'data/chapter' {
            return 'The file carries extra data or chapter tracks. Fix: leave them out.' }
        'picture tracks' {
            return 'The file has more than one picture track. Fix: keep only the first.' }
        'no picture track' {
            return 'There is no picture in this file at all. This cannot be fixed here; it may be a sound-only file.' }
        "can't read" {
            return 'ffprobe cannot read this file. It is probably damaged or only partly downloaded. This cannot be fixed here; get a fresh copy of the file.' }
    }
    return $reason
}
