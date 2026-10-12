#===========================================================================
#  Tests for the Watch-safe rules (scripts\WatchSafe.ps1).
#  Run:  powershell -ExecutionPolicy Bypass -File scripts\tests\Test-WatchSafe.ps1
#  Needs nothing but PowerShell (ffmpeg is used only by the last tests, if found).
#===========================================================================
$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'WatchSafe.ps1')

$script:Pass = 0; $script:Fail = 0
function Check([string]$name, [bool]$ok) {
    if ($ok) { $script:Pass++; Write-Host "  ok   $name" -ForegroundColor Green }
    else { $script:Fail++; Write-Host "  FAIL $name" -ForegroundColor Red }
}

# --- fake ffprobe answers --------------------------------------------------
function V($codec = 'h264', $w = 1920, $h = 1080, $pix = 'yuv420p', $profile = 'High', $level = 40, $idx = 0) {
    [pscustomobject]@{ index = $idx; codec_type = 'video'; codec_name = $codec; width = $w; height = $h
        pix_fmt = $pix; profile = $profile; level = $level; disposition = [pscustomobject]@{ attached_pic = 0 } }
}
function Cover($idx = 9) {
    [pscustomobject]@{ index = $idx; codec_type = 'video'; codec_name = 'mjpeg'; width = 600; height = 900
        disposition = [pscustomobject]@{ attached_pic = 1 } }
}
function A($codec = 'aac', $ch = 2, $profile = 'LC', $idx = 1) {
    [pscustomobject]@{ index = $idx; codec_type = 'audio'; codec_name = $codec; channels = $ch; profile = $profile }
}
function S($codec = 'subrip', $lang = 'eng', $idx = 2) {
    [pscustomobject]@{ index = $idx; codec_type = 'subtitle'; codec_name = $codec; tags = [pscustomobject]@{ language = $lang } }
}
function D($idx = 3) { [pscustomobject]@{ index = $idx; codec_type = 'data'; codec_name = 'bin_data' } }
function Probe($streams, $fmt = 'mov,mp4,m4a,3gp,3g2,mj2', $dur = '6000.000') {
    [pscustomobject]@{ streams = @($streams); format = [pscustomobject]@{ format_name = $fmt; duration = $dur } }
}
$Front = @('ftyp', 'moov', 'mdat')
$Back  = @('ftyp', 'mdat', 'moov')
function Problems($probe, $boxes = $Front, $ext = '.mp4') { return @(Get-SafeProblems $probe $boxes $ext) }

Write-Host 'Watch-safe: files that are already fine'
Check 'H.264 1080p + AAC stereo MP4, index at front = safe' ((Problems (Probe @((V), (A)))).Count -eq 0)
Check '5.1 AAC is safe (phone plays 6 channels)' ((Problems (Probe @((V), (A 'aac' 6)))).Count -eq 0)
Check '720p Main profile is safe' ((Problems (Probe @((V 'h264' 1280 720 'yuv420p' 'Main' 31), (A)))).Count -eq 0)
Check 'two AAC tracks are safe' ((Problems (Probe @((V), (A), (A 'aac' 6 'LC' 2)))).Count -eq 0)
Check 'no sound track is still safe' ((Problems (Probe @((V)))).Count -eq 0)
Check 'HE-AAC is safe' ((Problems (Probe @((V), (A 'aac' 2 'HE-AAC')))).Count -eq 0)

Write-Host 'Watch-safe: files that must be converted'
Check 'MKV container'          ((Problems (Probe @((V), (A)) 'matroska,webm') $Front '.mkv') -match 'MKV').Count -eq 1
Check 'AVI container'          ((Problems (Probe @((V), (A)) 'avi') @() '.avi') -match 'AVI').Count -eq 1
Check 'index at the end'       ((Problems (Probe @((V), (A))) $Back) -match 'index').Count -eq 1
Check 'HEVC picture'           ((Problems (Probe @((V 'hevc'), (A)))) -match 'hevc').Count -eq 1
Check '10-bit H.264'           ((Problems (Probe @((V 'h264' 1920 1080 'yuv420p10le' 'High 10'), (A)))) -match '8-bit').Count -eq 1
Check 'High 10 profile alone'  ((Problems (Probe @((V 'h264' 1920 1080 'yuv420p' 'High 10'), (A)))) -match 'profile').Count -eq 1
Check '4K picture'             ((Problems (Probe @((V 'h264' 3840 2160), (A)))) -match '1080p').Count -eq 1
Check 'wide 2048x858 picture'  ((Problems (Probe @((V 'h264' 2048 858), (A)))) -match '1080p').Count -eq 1
Check 'level 5.2'              ((Problems (Probe @((V 'h264' 1920 1080 'yuv420p' 'High' 52), (A)))) -match 'level').Count -eq 1
Check 'AC3 sound'              ((Problems (Probe @((V), (A 'ac3' 6)))) -match 'AC3').Count -eq 1
Check 'DTS sound'              ((Problems (Probe @((V), (A 'dts' 6 'DTS')))) -match 'DTS').Count -eq 1
Check 'TrueHD sound'           ((Problems (Probe @((V), (A 'truehd' 8 '')))) -match 'TRUEHD').Count -eq 1
Check 'MP3 sound (not AAC)'    ((Problems (Probe @((V), (A 'mp3' 2 '')))) -match 'MP3').Count -eq 1
Check '7.1 AAC'                ((Problems (Probe @((V), (A 'aac' 8)))) -match '8-channel').Count -eq 1
Check 'second track bad only'  ((Problems (Probe @((V), (A), (A 'eac3' 6 '' 2)))) -match 'EAC3').Count -eq 1
Check 'cover picture track'    ((Problems (Probe @((V), (A), (Cover)))) -match 'cover').Count -eq 1
Check 'subtitles inside'       ((Problems (Probe @((V), (A), (S)))) -match 'srt').Count -eq 1
Check 'picture subtitles'      ((Problems (Probe @((V), (A), (S 'hdmv_pgs_subtitle')))) -match 'subtitles').Count -eq 1
Check 'data/chapter track'     ((Problems (Probe @((V), (A), (D)))) -match 'data').Count -eq 1
Check 'two picture tracks'     ((Problems (Probe @((V), (V 'h264' 640 360 'yuv420p' 'High' 30 5), (A)))) -match 'picture tracks').Count -eq 1
Check 'no picture at all'      ((Problems (Probe @((A)))) -match 'no picture').Count -eq 1
Check 'two problems = two separate reasons' ((Problems (Probe @((V 'hevc'), (A 'ac3' 6)))).Count -eq 2)
$safe = Get-SafeProblems (Probe @((V), (A))) $Front '.mp4'
Check 'safe file gives no reasons at all (not an empty item)' (@($safe).Count -eq 0 -and -not ($safe -is [array] -and $safe.Count -eq 1))

Write-Host 'Sound channels after converting'
Check 'mono stays mono'      ((Get-SafeChannels 1) -eq 1)
Check 'stereo stays stereo'  ((Get-SafeChannels 2) -eq 2)
Check '5.1 stays 5.1'        ((Get-SafeChannels 6) -eq 6)
Check '7.1 -> stereo'        ((Get-SafeChannels 8) -eq 2)
Check '3.0 / 5.0 -> stereo'  ((Get-SafeChannels 3) -eq 2 -and (Get-SafeChannels 5) -eq 2)
Check 'unknown -> stereo'    ((Get-SafeChannels 0) -eq 2)

Write-Host 'Final check before sending'
$ok = Probe @((V), (A)) 'mov,mp4,m4a,3gp,3g2,mj2' '6000.400'
Check 'safe file, 0.4 s apart = sent'        ($null -eq (Get-SendRefusal $ok $Front '.mp4' 6000.0))
Check 'exactly 1.0 s apart = sent'           ($null -eq (Get-SendRefusal $ok $Front '.mp4' 5999.4))
Check '1.5 s shorter = refused'              ((Get-SendRefusal $ok $Front '.mp4' 6001.9) -match 'more than 1 s')
Check 'cover track left in = refused'        ((Get-SendRefusal (Probe @((V), (A), (Cover))) $Front '.mp4' 6000) -match '2 picture tracks')
Check 'extra video track = refused'          ((Get-SendRefusal (Probe @((V), (V 'h264' 640 360 'yuv420p' 'High' 30 5), (A))) $Front '.mp4' 6000) -match 'exactly one')
Check 'still HEVC = refused'                 ((Get-SendRefusal (Probe @((V 'hevc'), (A))) $Front '.mp4' 6000) -match 'not Watch-safe')
Check 'still AC3 = refused'                  ((Get-SendRefusal (Probe @((V), (A 'ac3' 6))) $Front '.mp4' 6000) -match 'AC3')
Check 'index at end = refused'               ((Get-SendRefusal $ok $Back '.mp4' 6000) -match 'index')
Check 'unreadable = refused'                 ((Get-SendRefusal $null $Front '.mp4' 6000) -match "can't read")
Check 'no length = refused'                  ((Get-SendRefusal (Probe @((V), (A)) 'mov,mp4,m4a,3gp,3g2,mj2' $null) $Front '.mp4' 6000) -match 'length')

Write-Host 'Subtitle file names'
$n = Get-SubFileNames 'movie' @((S 'subrip' 'eng'), (S 'subrip' 'spa'), (S 'subrip' 'eng'), (S 'subrip' $null))
Check 'eng -> movie.en.srt'         ($n[0] -eq 'movie.en.srt')
Check 'spa -> movie.es.srt'         ($n[1] -eq 'movie.es.srt')
Check 'second eng -> movie.en.2.srt' ($n[2] -eq 'movie.en.2.srt')
Check 'no language -> movie.und.srt' ($n[3] -eq 'movie.und.srt')
Check 'episode -> S01E02.en.srt'    ((Get-SubFileNames 'S01E02' @((S)))[0] -eq 'S01E02.en.srt')

Write-Host 'MP4 index position (real bytes)'
$tmp = Join-Path ([IO.Path]::GetTempPath()) "watch-test-$PID"
[void](New-Item -ItemType Directory -Path $tmp -Force)
try {
    function Box([string]$type, [int]$size) {
        $b = New-Object byte[] $size
        $b[0] = ($size -shr 24) -band 255; $b[1] = ($size -shr 16) -band 255; $b[2] = ($size -shr 8) -band 255; $b[3] = $size -band 255
        [Text.Encoding]::ASCII.GetBytes($type).CopyTo($b, 4)
        return ,$b
    }
    $f1 = Join-Path $tmp 'front.mp4'; [IO.File]::WriteAllBytes($f1, ((Box 'ftyp' 24) + (Box 'moov' 40) + (Box 'mdat' 100)))
    $f2 = Join-Path $tmp 'back.mp4';  [IO.File]::WriteAllBytes($f2, ((Box 'ftyp' 24) + (Box 'mdat' 100) + (Box 'moov' 40)))
    $f3 = Join-Path $tmp 'junk.mp4';  [IO.File]::WriteAllBytes($f3, [byte[]](1..64))
    Check 'moov before mdat = index at front' (Test-IndexAtFront (Get-Mp4BoxOrder $f1))
    Check 'moov after mdat = index at end'    (-not (Test-IndexAtFront (Get-Mp4BoxOrder $f2)))
    Check 'not an MP4 = no boxes, not safe'   (-not (Test-IndexAtFront (Get-Mp4BoxOrder $f3)))
} finally { Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue }

Write-Host ''
Write-Host "$($script:Pass) passed, $($script:Fail) failed"
if ($script:Fail -gt 0) { exit 1 }
