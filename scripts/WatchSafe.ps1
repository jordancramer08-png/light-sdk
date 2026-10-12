#===========================================================================
#  Watch-safe rules (CLAUDE.md section 8). Dot-sourced by Watch-Phone-Sync.ps1
#  and by scripts\tests\Test-WatchSafe.ps1. Pure rules: they look at ffprobe's
#  answer (as an object) and never run ffmpeg or touch the phone.
#
#  Watch-safe = MP4 with the index (moov) before the data (mdat), exactly one
#  picture track: H.264 8-bit 4:2:0 at most 1920x1080, every sound track AAC
#  with at most 6 channels (stereo or 5.1), nothing else inside (no cover
#  picture, no subtitles, no data/chapter tracks). Subtitles go next to the
#  video as .srt files.
#===========================================================================

$SafeMaxWidth    = 1920
$SafeMaxHeight   = 1080
$SafeMaxChannels = 6           # the phone's AAC decoder plays 5.1 (max-channel-count 6)
$SafeMaxLevel    = 51          # H.264 level 5.1 (phone decoder: High@5)
$SafeProfiles    = @('Baseline', 'Constrained Baseline', 'Main', 'High')
$SafeAudioProfiles = @('LC', 'HE-AAC', 'HE-AACv2')
$TextSubCodecs   = @('subrip', 'ass', 'ssa', 'mov_text', 'webvtt', 'text')
$CoverCodecList  = @('mjpeg', 'png', 'bmp', 'gif')
$DurationSlackSec = 1.0

function Get-P($o, [string]$name) {
    if ($null -eq $o) { return $null }
    $p = $o.PSObject.Properties[$name]
    if ($p) { return $p.Value }
    return $null
}

function Test-CoverStream($s) {
    $disp = Get-P $s 'disposition'
    return ((Get-P $disp 'attached_pic') -eq 1) -or ($CoverCodecList -contains (Get-P $s 'codec_name'))
}

# Order of the top-level MP4 boxes, e.g. @('ftyp','moov','mdat'). Empty when not an MP4.
function Get-Mp4BoxOrder([string]$path) {
    $order = @()
    $fs = [IO.File]::OpenRead($path)
    try {
        $buf = New-Object byte[] 16
        $pos = [long]0
        while ($pos + 8 -le $fs.Length -and $order.Count -lt 50) {
            [void]$fs.Seek($pos, 'Begin')
            if ($fs.Read($buf, 0, 16) -lt 8) { break }
            $size = [long](([uint32]$buf[0] -shl 24) -bor ([uint32]$buf[1] -shl 16) -bor ([uint32]$buf[2] -shl 8) -bor [uint32]$buf[3])
            $type = [Text.Encoding]::ASCII.GetString($buf, 4, 4)
            if ($type -notmatch '^[ -~]{4}$') { break }
            if ($size -eq 1) {
                $size = [long]0
                for ($i = 8; $i -lt 16; $i++) { $size = ($size -shl 8) -bor [long]$buf[$i] }
            } elseif ($size -eq 0) { $size = $fs.Length - $pos }
            if ($size -lt 8) { break }
            $order += $type
            $pos += $size
        }
    } finally { $fs.Dispose() }
    return ,$order
}

# $true when the MP4 index comes before the picture data (plays at once, seeks fast).
function Test-IndexAtFront([string[]]$boxes) {
    $m = [array]::IndexOf($boxes, 'moov'); $d = [array]::IndexOf($boxes, 'mdat')
    return ($m -ge 0 -and $d -ge 0 -and $m -lt $d)
}

# Is this picture track already fine for the phone? Returns a reason, or $null when it is.
function Get-VideoProblem($v) {
    $codec = [string](Get-P $v 'codec_name')
    if ($codec -ne 'h264') { return "$codec picture (Watch-safe is H.264)" }
    $pix = [string](Get-P $v 'pix_fmt')
    if ($pix -notin @('yuv420p', 'yuvj420p')) { return "H.264 $pix picture (the phone needs 8-bit 4:2:0)" }
    $prof = [string](Get-P $v 'profile')
    if ($prof -and $SafeProfiles -notcontains $prof) { return "H.264 $prof profile (the phone plays Baseline/Main/High)" }
    $lvl = Get-P $v 'level'
    if ($lvl -and [int]$lvl -gt $SafeMaxLevel) { return "H.264 level $([int]$lvl / 10) (the phone goes up to 5.1)" }
    $w = [int](Get-P $v 'width'); $h = [int](Get-P $v 'height')
    if ($w -gt $SafeMaxWidth -or $h -gt $SafeMaxHeight) { return "${w}x$h picture (bigger than 1080p)" }
    return $null
}

# Is this sound track already fine? Returns a reason, or $null.
function Get-AudioProblem($a) {
    $codec = [string](Get-P $a 'codec_name')
    if ($codec -ne 'aac') { return "$($codec.ToUpper()) sound (Watch-safe is AAC)" }
    $prof = [string](Get-P $a 'profile')
    if ($prof -and $SafeAudioProfiles -notcontains $prof) { return "AAC $prof sound (the phone's hardware plays LC/HE)" }
    $ch = [int](Get-P $a 'channels')
    if ($ch -gt $SafeMaxChannels) { return "$ch-channel sound (the phone plays up to 5.1)" }
    return $null
}

# Channels a converted sound track gets: stereo stays stereo, 5.1 stays 5.1, more is mixed to stereo.
function Get-SafeChannels([int]$channels) {
    if ($channels -ge 1 -and $channels -le $SafeMaxChannels) { if ($channels -eq 6) { return 6 } ; return [math]::Min($channels, 2) }
    return 2
}

# Splits ffprobe's streams. Returns @{ Videos; Covers; Audio; TextSubs; PictureSubs; Other }
function Split-Streams($probe) {
    $out = @{ Videos = @(); Covers = @(); Audio = @(); TextSubs = @(); PictureSubs = @(); Other = @() }
    foreach ($s in @(Get-P $probe 'streams')) {
        if ($null -eq $s) { continue }
        switch ([string](Get-P $s 'codec_type')) {
            'video'    { if (Test-CoverStream $s) { $out.Covers += $s } else { $out.Videos += $s } }
            'audio'    { $out.Audio += $s }
            'subtitle' { if ($TextSubCodecs -contains (Get-P $s 'codec_name')) { $out.TextSubs += $s } else { $out.PictureSubs += $s } }
            default    { $out.Other += $s }
        }
    }
    return $out
}

# Every reason a file is not Watch-safe (empty list = safe, copy untouched).
# $boxes = Get-Mp4BoxOrder of the file; $ext = its extension.
function Get-SafeProblems($probe, [string[]]$boxes, [string]$ext) {
    $list = @()
    $fmt = [string](Get-P (Get-P $probe 'format') 'format_name')
    if ($ext -ne '.mp4' -or $fmt -notmatch 'mp4') { $list += "$($ext.TrimStart('.').ToUpper()) file (Watch-safe is MP4)" }
    elseif (-not (Test-IndexAtFront $boxes)) { $list += 'MP4 index is at the end (slow to open and seek)' }
    $st = Split-Streams $probe
    if ($st.Videos.Count -eq 0) { $list += 'no picture track' }
    elseif ($st.Videos.Count -gt 1) { $list += "$($st.Videos.Count) picture tracks (Watch-safe has one)" }
    if ($st.Videos.Count -ge 1) { $vp = Get-VideoProblem $st.Videos[0]; if ($vp) { $list += $vp } }
    foreach ($a in $st.Audio) { $ap = Get-AudioProblem $a; if ($ap) { $list += $ap } }
    if ($st.Covers.Count -gt 0) { $list += 'cover picture stored as a video track' }
    if ($st.TextSubs.Count + $st.PictureSubs.Count -gt 0) { $list += 'subtitles inside the file (Watch-safe uses .srt next to it)' }
    if ($st.Other.Count -gt 0) { $list += 'extra data/chapter tracks inside the file' }
    return @($list | Select-Object -Unique)
}

# Final check of the file that is about to be sent. $origDuration = seconds of the original.
# Returns the reason it must not be sent, or $null when it is fine.
function Get-SendRefusal($probe, [string[]]$boxes, [string]$ext, [double]$origDuration) {
    if (-not $probe) { return "ffprobe can't read the finished file" }
    $st = Split-Streams $probe
    $videoCount = $st.Videos.Count + $st.Covers.Count
    if ($videoCount -ne 1) { return "it has $videoCount picture tracks; it must have exactly one" }
    $problems = @(Get-SafeProblems $probe $boxes $ext)
    if ($problems.Count -gt 0) { return "it is still not Watch-safe: $($problems -join '; ')" }
    $d = Get-P (Get-P $probe 'format') 'duration'
    if (-not $d) { return 'its length could not be read' }
    $diff = [math]::Abs([double]$d - $origDuration)
    if ($origDuration -gt 0 -and $diff -gt $DurationSlackSec) {
        return ("it is {0:N1} s long but the original is {1:N1} s (more than 1 s apart)" -f [double]$d, $origDuration)
    }
    return $null
}

# Language part of a sidecar name: "eng" -> "en", unknown -> "und".
function Get-SubLanguage($s) {
    $lang = [string](Get-P (Get-P $s 'tags') 'language')
    $map = @{ eng = 'en'; spa = 'es'; fre = 'fr'; fra = 'fr'; ger = 'de'; deu = 'de'; ita = 'it'; por = 'pt'; jpn = 'ja'; kor = 'ko'; chi = 'zh'; zho = 'zh'; rus = 'ru'; dut = 'nl'; nld = 'nl' }
    if (-not $lang) { return 'und' }
    $lang = $lang.ToLowerInvariant()
    if ($map.ContainsKey($lang)) { return $map[$lang] }
    return $lang
}

# Sidecar names for the text subtitle tracks: movie.en.srt, movie.en.2.srt, movie.und.srt ...
function Get-SubFileNames([string]$baseName, $textSubs) {
    $names = @(); $seen = @{}
    foreach ($s in @($textSubs)) {
        $lang = Get-SubLanguage $s
        if ($seen.ContainsKey($lang)) { $seen[$lang]++; $names += "$baseName.$lang.$($seen[$lang]).srt" }
        else { $seen[$lang] = 1; $names += "$baseName.$lang.srt" }
    }
    return ,$names
}
