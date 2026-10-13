#===========================================================================
#  Tests for Session 11b: library report, pre-convert (staging), smoke test,
#  why-won't-it-play (scripts\LibraryRules.ps1 and the four scripts).
#  Run:  powershell -ExecutionPolicy Bypass -File scripts\tests\Test-LibraryTools.ps1
#  Rule tests need only PowerShell. With ffmpeg installed it also makes small
#  synthetic videos in %TEMP% and runs every script on them end to end, with
#  WATCH_DRY_RUN instead of a phone. -RulesOnly skips that part.
#===========================================================================
param([switch]$RulesOnly)
$ErrorActionPreference = 'Stop'
$Scripts = Split-Path -Parent $PSScriptRoot
. (Join-Path $Scripts 'WatchSafe.ps1')
. (Join-Path $Scripts 'LibraryRules.ps1')

$script:Pass = 0; $script:Fail = 0
function Check([string]$name, [bool]$ok) {
    if ($ok) { $script:Pass++; Write-Host "  ok   $name" -ForegroundColor Green }
    else { $script:Fail++; Write-Host "  FAIL $name" -ForegroundColor Red }
}

# --- fake ffprobe answers (same shapes as Test-WatchSafe.ps1) ------------
function V($codec = 'h264', $w = 1920, $h = 1080, $pix = 'yuv420p', $profile = 'High', $level = 40, $idx = 0) {
    [pscustomobject]@{ index = $idx; codec_type = 'video'; codec_name = $codec; width = $w; height = $h
        pix_fmt = $pix; profile = $profile; level = $level; disposition = [pscustomobject]@{ attached_pic = 0 } }
}
function A($codec = 'aac', $ch = 2, $profile = 'LC', $idx = 1, $br = $null) {
    $o = [pscustomobject]@{ index = $idx; codec_type = 'audio'; codec_name = $codec; channels = $ch; profile = $profile }
    if ($br) { $o | Add-Member -NotePropertyName bit_rate -NotePropertyValue "$br" }
    return $o
}
function Probe($streams, $fmt = 'mov,mp4,m4a,3gp,3g2,mj2', $dur = '6000.000') {
    [pscustomobject]@{ streams = @($streams); format = [pscustomobject]@{ format_name = $fmt; duration = $dur } }
}
$Front = @('ftyp', 'moov', 'mdat'); $Back = @('ftyp', 'mdat', 'moov')
$GB = [double]1GB

Write-Host 'Report: status of each file'
$ok = Get-FileVerdict (Probe @((V), (A))) $Front '.mp4' (3 * $GB) 2.0
Check 'Watch-safe MP4 = OK'                 ($ok.Status -eq $StatusOk -and $ok.Work -eq 'none' -and $ok.WorkSeconds -eq 0)
Check 'OK keeps its size'                   ($ok.EstBytes -eq 3 * $GB)
$mkv = Get-FileVerdict (Probe @((V), (A)) 'matroska,webm') @() '.mkv' (3 * $GB) 2.0
Check 'MKV with H.264 + AAC = quick fix (remux)' ($mkv.Status -eq $StatusQuick -and $mkv.Work -eq 'remux')
$idx = Get-FileVerdict (Probe @((V), (A))) $Back '.mp4' (3 * $GB) 2.0
Check 'index at the end = quick fix'        ($idx.Status -eq $StatusQuick -and ($idx.Reasons -match 'index').Count -eq 1)
$ac3 = Get-FileVerdict (Probe @((V), (A 'ac3' 6 '' 1 640000)) 'matroska,webm') @() '.mkv' (4 * $GB) 2.0
Check 'AC3 sound = quick fix (sound only)'  ($ac3.Status -eq $StatusQuick -and $ac3.Work -eq 'audio')
Check 'AC3 640k -> AAC 384k makes it smaller' ($ac3.EstBytes -lt 4 * $GB -and $ac3.EstBytes -gt 3.5 * $GB)
$avi = Get-FileVerdict (Probe @((V 'mpeg4' 720 400 'yuv420p' 'Advanced Simple Profile' 5), (A 'mp3' 2 '')) 'avi') @() '.avi' (0.7 * $GB) 2.0
Check 'AVI MPEG-4 = full convert'           ($avi.Status -eq $StatusFull -and $avi.Work -eq 'video')
$hevc = Get-FileVerdict (Probe @((V 'hevc' 3840 2160 'yuv420p10le' 'Main 10'), (A 'eac3' 6 '')) 'matroska,webm') @() '.mkv' (20 * $GB) 2.0
Check '4K 10-bit HEVC = full convert'       ($hevc.Status -eq $StatusFull)
Check '4K estimate is a 1080p-sized file'   ([math]::Abs($hevc.EstBytes - (5000 + 384) * 1000 / 8 * 6000) -lt 1MB)
Check '720p estimate is smaller'            ($hevc.Est720Bytes -lt $hevc.EstBytes)
Check 'picture label says 10-bit'           ($hevc.Picture -eq '2160p HEVC 10-bit')
Check 'sound label'                         ($hevc.Sound -eq 'E-AC3 5.1')
$bad = Get-FileVerdict $null @() '.mp4' 100 2.0
Check 'unreadable = Can''t play'             ($bad.Status -eq $StatusBad -and $bad.Fix -match "can't")
$nopic = Get-FileVerdict (Probe @((A))) $Front '.mp4' 100 2.0
Check 'no picture = Can''t play'             ($nopic.Status -eq $StatusBad -and $nopic.Picture -eq 'none')

Write-Host 'Report: time estimates'
Check 'none takes no time'                  ((Get-WorkSeconds 'none' 6000 $GB 1920 1080 2) -eq 0)
Check 'remux = copy time only'              ([math]::Abs((Get-WorkSeconds 'remux' 6000 (6 * $GB) 1920 1080 2) - 6 * $GB / $CopyBytesPerSec) -lt 0.01)
Check 'sound convert is minutes, not hours' ((Get-WorkSeconds 'audio' 6000 (4 * $GB) 1920 1080 2) -lt 300)
$t1080 = Get-WorkSeconds 'video' 6000 0 1920 1080 2
$t720 = Get-WorkSeconds 'video' 6000 0 1280 720 2
Check '1080p full convert = film length x speed' ($t1080 -eq 12000)
Check '720p takes less than 1080p'          ($t720 -lt $t1080 -and $t720 -gt 0)
Check 'full convert time is in the verdict' ($avi.WorkSeconds -gt 0)
Check 'Format-Duration 2 h 3 min'           ((Format-Duration 7385) -eq '2 h 3 min')
Check 'Format-Duration minutes'             ((Format-Duration 95) -eq '2 min')
Check 'Format-Duration nothing'             ((Format-Duration 0) -eq '-')
Check 'fit 4K into 1080p'                   (((Get-FitSize 3840 2160 1920 1080) -join 'x') -eq '1920x1080')
Check 'fit wide 2048x858 into 1080p'        (((Get-FitSize 2048 858 1920 1080) -join 'x') -eq '1920x804')
Check 'small picture is not made bigger'    (((Get-FitSize 640 360 1920 1080) -join 'x') -eq '640x360')

Write-Host 'Report: totals'
$rows = @(
    [pscustomobject]@{ Status = $StatusOk; Bytes = 10.0; EstBytes = 10.0; Est720Bytes = 5.0; WorkSeconds = 0 }
    [pscustomobject]@{ Status = $StatusQuick; Bytes = 20.0; EstBytes = 18.0; Est720Bytes = 9.0; WorkSeconds = 60 }
    [pscustomobject]@{ Status = $StatusFull; Bytes = 30.0; EstBytes = 12.0; Est720Bytes = 6.0; WorkSeconds = 3600 }
    [pscustomobject]@{ Status = $StatusBad; Bytes = 1.0; EstBytes = 1.0; Est720Bytes = 1.0; WorkSeconds = 0 })
$t = Get-ReportTotals $rows
Check 'counts per status'  ($t.Files -eq 4 -and $t.Ok -eq 1 -and $t.Quick -eq 1 -and $t.Full -eq 1 -and $t.Bad -eq 1)
Check 'sizes and time add up' ($t.Bytes -eq 61 -and $t.EstBytes -eq 41 -and $t.Est720Bytes -eq 21 -and $t.WorkSeconds -eq 3660)
Check 'empty list = zeros'    ((Get-ReportTotals @()).Files -eq 0)

Write-Host 'Probe cache lines (resume after Ctrl+C)'
$json = "{`n  ""streams"": [],`n  ""format"": { ""duration"": ""1.0"" }`n}"
$line = ConvertTo-ProbeLine 'd:\movies\a.mp4|10|99' @('ftyp', 'moov', 'mdat') $json
$back = ConvertFrom-ProbeLine $line
Check 'line is one line'            (-not $line.Contains("`n"))
Check 'key and boxes come back'     ($back.Key -eq 'd:\movies\a.mp4|10|99' -and ($back.Boxes -join ',') -eq 'ftyp,moov,mdat')
Check 'json still parses'           ((($back.Json | ConvertFrom-Json).format.duration) -eq '1.0')
Check 'cut-off line is ignored'     ($null -eq (ConvertFrom-ProbeLine $line.Substring(0, $line.Length - 5)))
Check 'unreadable file is saved too' ((ConvertFrom-ProbeLine (ConvertTo-ProbeLine 'k|1|2' @() '')).Json -eq '')
Check 'changed size = new key'      ((Get-ProbeKey 'D:\A.mp4' 10 5) -ne (Get-ProbeKey 'D:\A.mp4' 11 5))
Check 'changed time = new key'      ((Get-ProbeKey 'D:\A.mp4' 10 5) -ne (Get-ProbeKey 'D:\A.mp4' 10 6))
Check 'key ignores letter case'     ((Get-ProbeKey 'D:\A.mp4' 10 5) -eq (Get-ProbeKey 'd:\a.MP4' 10 5))

Write-Host 'Staging: is the pre-converted copy still good?'
$m = [pscustomobject]@{ sourceBytes = 100; sourceTicks = 555; quality = 'Original'; videoBytes = 80 }
Check 'same source, quality, size = use it'  (Test-StagedCurrent $m 100 555 'Original' 80)
Check 'source changed size = redo'           (-not (Test-StagedCurrent $m 101 555 'Original' 80))
Check 'source changed time = redo'           (-not (Test-StagedCurrent $m 100 556 'Original' 80))
Check 'other quality = redo'                 (-not (Test-StagedCurrent $m 100 555 '720p' 80))
Check 'staged video cut short = redo'        (-not (Test-StagedCurrent $m 100 555 'Original' 79))
Check 'staged video missing = redo'          (-not (Test-StagedCurrent $m 100 555 'Original' $null))
Check 'no manifest = redo'                   (-not (Test-StagedCurrent $null 100 555 'Original' 80))
Check 'fits: 100 GB needed, 200 GB free'     (Test-FitsOnDisk (100 * $GB) (200 * $GB))
Check 'refused: 100 GB needed, 105 GB free'  (-not (Test-FitsOnDisk (100 * $GB) (105 * $GB)))
Check 'refused: 1 GB needed, 5 GB free (5 GB spare)' (-not (Test-FitsOnDisk $GB (5 * $GB)))

Write-Host 'Smoke test: picking real examples'
function Rec($path, $ext, $kind = 'Movie', $root = 'D:\Movies', $audio = @('aac'), $h = 1080, $d = 6000) {
    [pscustomobject]@{ Path = $path; Ext = $ext; Kind = $kind; Root = $root; AudioCodecs = $audio; Height = $h; Duration = $d }
}
$recs = @(
    (Rec 'D:\Movies\a.mp4' '.mp4' 'Movie' 'D:\Movies' @('aac') 1080)
    (Rec 'D:\Movies\b.mkv' '.mkv' 'Movie' 'D:\Movies' @('ac3') 1080)
    (Rec 'D:\Movies\c.avi' '.avi' 'Movie' 'D:\Movies' @('mp3') 400)
    (Rec 'D:\Movies\d.mkv' '.mkv' 'Movie' 'D:\Movies' @('ac3') 720)
    (Rec 'D:\Movies\e.mp4' '.mp4' 'Movie' 'D:\Movies' @('aac') 720)
    (Rec 'D:\Movies\f.mp4' '.mp4' 'Movie' 'D:\Movies' @('aac') 1080)
    (Rec 'D:\Movies\short.mp4' '.mp4' 'Movie' 'D:\Movies' @('aac') 1080 30)
    (Rec 'D:\Movies\TV\x S01E01.mp4' '.mp4' 'Episode' 'D:\TV' @('aac') 720)
    (Rec 'D:\Marvel Entertainment\Loki S01E01.mkv' '.mkv' 'Episode' 'D:\Marvel Entertainment' @('eac3') 1080))
$picks = Select-SmokeExamples $recs
$by = @{}; foreach ($p in $picks) { $by[$p.Name] = $p.Record }
Check 'seven kinds, in order'           ($picks.Count -eq 7 -and $picks[0].Name -eq 'MP4 with AAC sound' -and $picks[6].Name -eq 'Marvel TV episode')
Check 'MP4 with AAC = a.mp4'            ($by['MP4 with AAC sound'].Path -eq 'D:\Movies\a.mp4')
Check 'MKV = b.mkv'                     ($by['MKV file'].Path -eq 'D:\Movies\b.mkv')
Check 'AVI = c.avi'                     ($by['Former AVI'].Path -eq 'D:\Movies\c.avi')
Check 'AC3 = d.mkv (b.mkv already used)' ($by['Former AC3 sound'].Path -eq 'D:\Movies\d.mkv')
Check '720p = e.mp4 (d.mkv already used)' ($by['720p picture'].Path -eq 'D:\Movies\e.mp4')
Check '1080p = f.mp4 (short one skipped)' ($by['1080p picture'].Path -eq 'D:\Movies\f.mp4')
Check 'TV episode comes from Marvel'    ($by['Marvel TV episode'].Path -like 'D:\Marvel*')
Check 'every file used once'            (@($picks | ForEach-Object { $_.Record.Path } | Select-Object -Unique).Count -eq 7)
$none = Select-SmokeExamples @((Rec 'D:\Movies\a.mp4' '.mp4'))
Check 'missing kinds come back empty'   ($none[0].Record -and -not $none[2].Record)
Check 'clip starts 5 min in'            ((Get-ClipStart 6000) -eq 300)
Check 'short file: a quarter in'        ((Get-ClipStart 100) -eq 25)

Write-Host "Why won't it play: plain words"
Check 'MKV explained'      ((Get-PlainExplanation 'MKV file (Watch-safe is MP4)') -match 'MKV format.*MP4 files only')
Check 'AC3 explained'      ((Get-PlainExplanation 'AC3 sound (Watch-safe is AAC)') -match 'no AC3 decoder')
Check 'HEVC explained'     ((Get-PlainExplanation 'hevc picture (Watch-safe is H.264)') -match 'HEVC')
Check '10-bit explained'   ((Get-PlainExplanation 'H.264 yuv420p10le picture (the phone needs 8-bit 4:2:0)') -match '10-bit')
Check '7.1 explained'      ((Get-PlainExplanation '8-channel sound (the phone plays up to 5.1)') -match 'stereo')
Check 'index explained'    ((Get-PlainExplanation 'MP4 index is at the end (slow to open and seek)') -match 'table of contents')
Check 'damaged explained'  ((Get-PlainExplanation "ffprobe can't read it") -match 'damaged')
Check 'every rule reason has plain words' (@((Get-SafeProblems (Probe @((V 'hevc' 3840 2160 'yuv420p10le' 'Main 10'), (A 'dts' 8 'DTS'))) $Back '.mkv') |
    Where-Object { (Get-PlainExplanation $_) -eq $_ }).Count -eq 0)

#--- Session 12: encoder choice and fallback ---------------------------------
Check 'encoder: blank = fast'              ((Get-EncoderChoice '') -eq 'fast')
Check 'encoder: MEDIUM (any case) = medium' ((Get-EncoderChoice ' MEDIUM ') -eq 'medium')
Check 'encoder: unknown word = fast'       ((Get-EncoderChoice 'turbo') -eq 'fast')
Check 'plan: fast = veryfast then medium'  ((@(Get-EncoderPlan 'fast') -join ',') -eq 'veryfast,medium')
Check 'plan: medium = medium only'         ((@(Get-EncoderPlan 'medium') -join ',') -eq 'medium')
Check 'fallback: made + safe = done'       ((Get-FallbackStep $true '' 0 2) -eq 'done')
Check 'fallback: ffmpeg failed = next'     ((Get-FallbackStep $false 'code 1' 0 2) -eq 'next')
Check 'fallback: not Watch-safe = next'    ((Get-FallbackStep $true 'hevc picture' 0 2) -eq 'next')
Check 'fallback: medium failed too = fail' ((Get-FallbackStep $false 'code 1' 1 2) -eq 'fail')
Check 'fallback: medium only, failed = fail' ((Get-FallbackStep $true 'bad' 0 1) -eq 'fail')
Check 'speed file: old (no preset) = medium' ((Get-SpeedPreset ([pscustomobject]@{ secPerSec1080 = 2.96 })) -eq 'medium')
Check 'speed file: preset read'            ((Get-SpeedPreset ([pscustomobject]@{ secPerSec1080 = 1; preset = 'veryfast' })) -eq 'veryfast')

#===========================================================================
#  End to end, on synthetic files (needs ffmpeg)
#===========================================================================
function Get-Md5([string]$path) {
    $md5 = [Security.Cryptography.MD5]::Create(); $fs = [IO.File]::OpenRead($path)
    try { return [BitConverter]::ToString($md5.ComputeHash($fs)) } finally { $fs.Dispose() }
}
function Find-Tool($name) {
    $c = Get-Command $name -ErrorAction SilentlyContinue; if ($c) { return $c.Source }
    $l = Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Links\$name.exe"; if (Test-Path $l) { return $l }
    return $null
}
$ffmpeg = Find-Tool 'ffmpeg'
if ($RulesOnly -or -not $ffmpeg) {
    if (-not $RulesOnly) { Write-Host '  (ffmpeg not found: end-to-end tests skipped)' -ForegroundColor Yellow }
} else {
    $base = Join-Path ([IO.Path]::GetTempPath()) "wtest-11b-$PID"
    $src = Join-Path $base 'src'; $dry = Join-Path $base 'dry'; $stageDir = Join-Path $base 'staging'; $rep = Join-Path $base 'report'
    $old = @{}
    foreach ($n in 'WATCH_SOURCES', 'WATCH_DRY_RUN', 'WATCH_STAGING', 'WATCH_REPORT_DIR', 'WATCH_PROBE_CACHE', 'WATCH_ENCODE_SPEED', 'WATCH_BACKUP_DIR', 'WATCH_ENCODER', 'WATCH_TEST_BREAK_FAST') { $old[$n] = [Environment]::GetEnvironmentVariable($n) }
    try {
        Write-Host 'Making synthetic videos (about a minute)...'
        $movies = Join-Path $src 'Movies'; $marvel = Join-Path $src 'Marvel Entertainment'
        function Make([string]$path, [string[]]$codec, [string]$size = '1280x720') {
            [void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path))
            $a = @('-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', "testsrc2=size=${size}:rate=5", '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000', '-t', '65') + $codec + @($path)
            & $ffmpeg @a
            if ($LASTEXITCODE -ne 0) { throw "ffmpeg could not make $path" }
        }
        $x264 = @('-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '35', '-pix_fmt', 'yuv420p')
        Make (Join-Path $movies 'Alpha Films\01 - Alpha (2001)\Alpha (2001).mp4') ($x264 + @('-c:a', 'aac', '-b:a', '96k', '-movflags', '+faststart'))
        Make (Join-Path $movies 'Beta (2002).mkv') ($x264 + @('-c:a', 'ac3', '-ac', '6', '-b:a', '384k')) '1920x1080'
        Make (Join-Path $movies 'Gamma (2003).avi') (@('-c:v', 'mpeg4', '-q:v', '10', '-c:a', 'libmp3lame', '-b:a', '96k')) '640x360'
        Make (Join-Path $movies 'Delta (2004).mp4') ($x264 + @('-c:a', 'aac', '-b:a', '96k'))
        Make (Join-Path $movies 'Epsilon (2005).mkv') ($x264 + @('-c:a', 'ac3', '-b:a', '192k')) '854x480'
        Make (Join-Path $movies 'Zeta (2006).mp4') ($x264 + @('-c:a', 'aac', '-b:a', '96k', '-movflags', '+faststart')) '1920x1080'
        Make (Join-Path $marvel 'Phase Four\Show (2021) - Season 1\Show - S01E01 - Pilot.mp4') ($x264 + @('-c:a', 'aac', '-b:a', '96k', '-movflags', '+faststart')) '854x480'
        [IO.File]::WriteAllBytes((Join-Path $movies 'Broken (2007).mp4'), [byte[]](1..200))
        # Delta must have its index at the end (ffmpeg's default)
        Check 'fixture: Delta index at the end' (-not (Test-IndexAtFront (Get-Mp4BoxOrder (Join-Path $movies 'Delta (2004).mp4'))))
        function Get-Snapshot { @(Get-ChildItem -LiteralPath $src -Recurse -File | Sort-Object FullName | ForEach-Object { "$($_.FullName)|$($_.Length)|$($_.LastWriteTimeUtc.Ticks)|$(Get-Md5 $_.FullName)" }) -join "`n" }
        $before = Get-Snapshot

        $env:WATCH_SOURCES = "$movies;$marvel"
        $env:WATCH_REPORT_DIR = $rep
        $env:WATCH_PROBE_CACHE = Join-Path $base 'probe-cache.tsv'
        $env:WATCH_ENCODE_SPEED = '2.0'
        $env:WATCH_STAGING = $stageDir
        $env:WATCH_DRY_RUN = $dry
        $env:WATCH_BACKUP_DIR = Join-Path $base 'backups'
        function Run([string]$script, [string]$typed, [string[]]$more = @()) {
            $ErrorActionPreference = 'Continue'
            $out = ($typed | powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Scripts $script) @more 2>&1 | Out-String)
            return $out
        }

        Write-Host 'Library report'
        $o1 = Run 'Watch-Library-Report.ps1' '' @('-NoOpen')
        $csvPath = Join-Path $rep 'library-report.csv'
        if (-not (Test-Path $csvPath)) { Write-Host $o1; throw 'report failed' }
        Check 'report: CSV and page saved'   ((Test-Path $csvPath) -and (Test-Path (Join-Path $rep 'library-report.html')))
        $csv = @(Import-Csv $csvPath)
        $st = @{}; foreach ($r in $csv) { $st[[IO.Path]::GetFileName($r.File)] = $r.Status }
        Check 'report: 8 files'                ($csv.Count -eq 8)
        Check 'report: Alpha OK'               ($st['Alpha (2001).mp4'] -eq 'OK')
        Check 'report: Zeta OK'                ($st['Zeta (2006).mp4'] -eq 'OK')
        Check 'report: Beta MKV+AC3 quick fix' ($st['Beta (2002).mkv'] -eq 'Needs a quick fix')
        Check 'report: Delta index quick fix'  ($st['Delta (2004).mp4'] -eq 'Needs a quick fix')
        Check 'report: Gamma AVI full convert' ($st['Gamma (2003).avi'] -eq 'Needs a full convert')
        Check 'report: Broken can''t play'     ($st['Broken (2007).mp4'] -eq "Can't play")
        Check 'report: episode listed as Episode' (@($csv | Where-Object { $_.Kind -eq 'Episode' }).Count -eq 1)
        Check 'report: progress count shown'   ($o1 -match '8 of 8')
        Check 'report: totals per folder + all' ($o1 -match 'Movies' -and $o1 -match 'Marvel Entertainment' -and $o1 -match 'Everything')
        $page = [IO.File]::ReadAllText((Join-Path $rep 'library-report.html'))
        Check 'page: filter box, sortable columns, data' ($page -match 'id="q"' -and $page -match 'aria-sort' -and $page -match 'Gamma' -and $page -notmatch '\{\{DATA\}\}')
        $o2 = Run 'Watch-Library-Report.ps1' '' @('-NoOpen')
        Check 'report again: nothing re-read'  ($o2 -match '8 of 8 files were checked before' -and $o2 -notmatch 'Checking \d+ new')
        # Simulate Ctrl+C half way: keep only the first 4 saved answers, the last one cut off
        $lines = @(Get-Content -LiteralPath $env:WATCH_PROBE_CACHE -Encoding UTF8)
        $keep = @($lines | Select-Object -First 4) + @($lines[4].Substring(0, 40))
        [IO.File]::WriteAllText($env:WATCH_PROBE_CACHE, (($keep -join "`n") + "`n"), (New-Object Text.UTF8Encoding($false)))
        $o3 = Run 'Watch-Library-Report.ps1' '' @('-NoOpen')
        Check 'resume: carries on with the 4 left' ($o3 -match '4 of 8 files were checked before' -and $o3 -match 'Checking 4 new')
        Check 'resume: full report again'        (@(Import-Csv $csvPath).Count -eq 8)

        Write-Host 'Pre-convert into the staging folder'
        # 6 = pre-convert, A = all, D = done, 1 = Original, y = create folder, y = start, Q = quit
        $s1 = Run 'Watch-Phone-Sync.ps1' "6`nA`nD`n1`ny`ny`nQ`n"
        $mv = Join-Path $stageDir 'Movies'
        Check 'stage: staging folder made (after asking)' (Test-Path $stageDir)
        Check 'stage: total time estimate shown'  ($s1 -match 'file\(s\) to convert at Original: about .* of converting')
        Check 'stage: Beta staged (MKV+AC3)'     (Test-Path (Join-Path $mv '_Singles\Beta (2002)\movie.stage.json'))
        Check 'stage: Gamma staged (AVI)'        (Test-Path (Join-Path $mv '_Singles\Gamma (2003)\movie.mp4'))
        Check 'stage: Delta staged (index)'      (Test-Path (Join-Path $mv '_Singles\Delta (2004)\movie.mp4'))
        Check 'stage: Alpha not staged (already safe)' (-not (Test-Path (Join-Path $mv 'Alpha Films')))
        Check 'stage: Broken refused with a reason' ($s1 -match "Can't use Broken")
        Check 'stage: no .part left'             (@(Get-ChildItem -LiteralPath $stageDir -Recurse -Filter '*.part*').Count -eq 0)
        Check 'stage: keep-awake mentioned'      ($s1 -match 'will not go to sleep' -and $s1 -match 'can sleep again')
        $s2 = Run 'Watch-Phone-Sync.ps1' "6`nA`nD`n1`nQ`n"
        Check 'stage again: all skipped, nothing to convert' ($s2 -match 'already pre-converted and unchanged' -and $s2 -match 'Nothing to convert')
        # A stop half way: a leftover .part folder and a missing manifest -> redone cleanly
        $gd = Join-Path $mv '_Singles\Gamma (2003)'
        Remove-Item -LiteralPath (Join-Path $gd 'movie.stage.json')
        [void](New-Item -ItemType Directory -Path (Join-Path $gd 'movie.part')); Set-Content (Join-Path $gd 'movie.part\movie.mp4') 'half'
        $s3 = Run 'Watch-Phone-Sync.ps1' "6`nGamma`nA`nD`n1`ny`nQ`n"
        Check 'stage after a stop: redone, .part gone' ((Test-Path (Join-Path $gd 'movie.stage.json')) -and -not (Test-Path (Join-Path $gd 'movie.part')))
        # Not enough room: pretend to need far more than the disk has
        $env:WATCH_ENCODE_SPEED = '2.0'
        $bigCheck = Test-FitsOnDisk (10000 * $GB) ((New-Object IO.DriveInfo ([IO.Path]::GetPathRoot($stageDir))).AvailableFreeSpace)
        Check 'stage: 10 TB would be refused on this disk' (-not $bigCheck)

        Write-Host 'Send uses the staged copies'
        $s4 = Run 'Watch-Phone-Sync.ps1' "1`nBeta`nA`nD`n1`ny`nQ`n"
        $sentBeta = Join-Path $dry 'Watch\Movies\_Singles\Beta (2002)\movie.mp4'
        Check 'send: used the pre-converted copy' ($s4 -match 'Using the copy pre-converted')
        Check 'send: same file as staged'         ((Test-Path $sentBeta) -and (Get-Md5 $sentBeta) -eq (Get-Md5 (Join-Path $mv '_Singles\Beta (2002)\movie.mp4')))
        Check 'send: subtitles/json there'        (Test-Path (Join-Path $dry 'Watch\Movies\_Singles\Beta (2002)\movie.json'))
        Check 'send: cap still shown'             ($s4 -match 'of the 50.0 GB cap')

        Write-Host 'Smoke test'
        $k1 = Run 'Watch-Smoke-Test.ps1' "y`n"
        $k1 | Set-Content (Join-Path (Split-Path -Parent $Scripts) 'logs/test11b-smoke.log')
        $sm = Join-Path $dry 'Watch\Movies\Smoke Test'
        $smokeDirs = @(); if (Test-Path $sm) { $smokeDirs = @(Get-ChildItem -LiteralPath $sm -Directory | ForEach-Object { $_.Name }) }
        Check 'smoke: 6 movie clips sent'        ($smokeDirs.Count -eq 6)
        Check 'smoke: the Marvel episode sent'   (Test-Path (Join-Path $dry 'Watch\TV\Smoke Test TV\Season 01\S01E01.mp4'))
        Check 'smoke: an MP4 with AAC picked'    ($k1 -match 'MP4 with AAC sound\s+\S.*\.mp4')
        Check 'smoke: AVI = Gamma'               ($k1 -match 'Former AVI\s+\S.*Gamma')
        Check 'smoke: AC3 = Epsilon (Beta used)' ($k1 -match 'Former AC3 sound\s+\S.*Epsilon')
        Check 'smoke: 1080p = Zeta'              ($k1 -match '1080p picture\s+\S.*Zeta')
        $clip = Join-Path $sm 'Smoke 3 - Former AVI\movie.json'
        $cj = $null; if (Test-Path $clip) { $cj = Get-Content -Raw $clip | ConvertFrom-Json }
        Check 'smoke: clip is ~20 s, H.264'      ($cj -and $cj.videoCodec -eq 'h264' -and $cj.runtimeMin -le 1)
        Check 'smoke: in story order for next-up' ($cj -and $cj.collection -eq 'Smoke Test' -and $cj.collectionNumber -eq 3)
        Check 'smoke: checklist printed'         ($k1 -match 'For EACH clip' -and $k1 -match 'Resumes')
        $k2 = Run 'Watch-Smoke-Test.ps1' "y`ny`n"
        Check 'smoke again: asks before replacing' ($k2 -match 'earlier smoke test')

        Write-Host "Why won't it play"
        $w1 = Run 'Watch-Why-Wont-It-Play.ps1' "gamma`n1`nD`ny`n1`n"
        $gm = Join-Path $dry 'Watch\Movies\_Singles\Gamma (2003)\movie.mp4'
        Check 'why: says AVI in plain words'     ($w1 -match 'AVI format')
        Check 'why: says MP3 in plain words'     ($w1 -match 'no MP3 decoder')
        Check 'why: not on the phone yet'        ($w1 -match 'not on the phone')
        Check 'why: fixed and sent'              ((Test-Path $gm) -and $w1 -match 'Sent again')
        # Cut the copy on the "phone" short -> found and fixed again
        $fs = [IO.File]::Open($gm, 'Open', 'Write'); $fs.SetLength(1000); $fs.Close()
        $w2 = Run 'Watch-Why-Wont-It-Play.ps1' "gamma`n1`nD`ny`n1`n"
        Check 'why: finds the cut-off copy'      ($w2 -match 'incomplete or changed')
        Check 'why: replaced it'                 ((Get-Item $gm).Length -gt 1000)
        $w3 = Run 'Watch-Why-Wont-It-Play.ps1' "alpha`n1`nD`n"
        Check 'why: safe title not on phone -> offers to send' ($w3 -match 'Watch-safe' -and $w3 -match 'not on the phone')
        $w4 = Run 'Watch-Why-Wont-It-Play.ps1' "broken`n1`nD`n"
        Check 'why: damaged file explained, no fix offered' ($w4 -match 'damaged' -and $w4 -match 'cannot be fixed')

        # Session 11c: every menu that uses Select-Titles, picking ONE item by number
        $NoProp = 'cannot be found on this object|PropertyNotFound'
        Write-Host 'Pick one item in each menu (Session 11c)'
        $p1 = Run 'Watch-Phone-Sync.ps1' "1`nzeta`n1`nD`n1`ny`nQ`n"
        Check 'send one: no missing-property error' ($p1 -notmatch $NoProp)
        Check 'send one: Zeta sent'               (Test-Path (Join-Path $dry 'Watch\Movies\_Singles\Zeta (2006)\movie.mp4'))
        $p2 = Run 'Watch-Phone-Sync.ps1' "6`ndelta`n1`nD`n1`nQ`n"
        Check 'pre-convert one: no missing-property error' ($p2 -notmatch $NoProp)
        Check 'pre-convert one: Delta already staged' ($p2 -match 'already pre-converted')
        # pick 1, then 2 (a second pick runs the duplicate check), un-pick all, then pick Beta only
        $p3 = Run 'Watch-Phone-Sync.ps1' "2`n1`n2`nN`nbeta`n1`nD`ny`nQ`n"
        Check 'remove one: no missing-property error' ($p3 -notmatch $NoProp)
        Check 'remove one: Beta gone from the phone' (-not (Test-Path (Join-Path $dry 'Watch\Movies\_Singles\Beta (2002)')))
        Check 'remove one: Zeta still there'      (Test-Path (Join-Path $dry 'Watch\Movies\_Singles\Zeta (2006)\movie.mp4'))
        $p4 = Run 'Watch-Phone-Sync.ps1' "2`n1`nD`nn`nQ`n"
        Check 'remove one, then No: nothing changed' ($p4 -notmatch $NoProp -and $p4 -match 'Nothing was changed' -and (Test-Path (Join-Path $dry 'Watch\Movies\_Singles\Zeta (2006)\movie.mp4')))
        $p5 = Run 'Watch-Phone-Sync.ps1' "3`nQ`n"
        Check 'backup: runs, no error'            ($p5 -notmatch $NoProp -and $p5 -match 'Backing up')
        $p6 = Run 'Watch-Phone-Sync.ps1' "4`n`nQ`n"
        Check 'restore (cancel): runs, no error'  ($p6 -notmatch $NoProp -and $p6 -match 'Nothing was changed')
        $p7 = Run 'Watch-Why-Wont-It-Play.ps1' "1`n2`nN`nzeta`n1`nD`n"
        Check 'why one: no missing-property error' ($p7 -notmatch $NoProp -and $p7 -match 'Zeta')

        Write-Host 'Fast encoder (Session 12): default, forced medium, and the fallback'
        # x264 writes its settings into the file: veryfast = subme=2, medium = subme=7
        function Get-X264Subme([string]$path) {
            $m = [regex]::Match([Text.Encoding]::ASCII.GetString([IO.File]::ReadAllBytes($path)), 'subme=(\d+)')
            if ($m.Success) { return [int]$m.Groups[1].Value }; return -1
        }
        function Send-Gamma([string]$tag) {
            $env:WATCH_DRY_RUN = Join-Path $base "dry-$tag"; $env:WATCH_STAGING = Join-Path $base "stage-$tag"
            $o = Run 'Watch-Phone-Sync.ps1' "1`ngamma`n1`nD`n1`ny`nQ`n"
            $v = Join-Path $env:WATCH_DRY_RUN 'Watch\Movies\_Singles\Gamma (2003)\movie.mp4'
            $safe = $false; $sub = -1
            if (Test-Path $v) {
                $fp = (& (Find-Tool 'ffprobe') -v error -print_format json -show_streams -show_format -i $v) -join "`n" | ConvertFrom-Json
                $safe = -not (Get-SendRefusal $fp (Get-Mp4BoxOrder $v) '.mp4' 65.0)
                $sub = Get-X264Subme $v
            }
            return @{ Out = $o; Safe = $safe; Subme = $sub }
        }
        $f1 = Send-Gamma 'fast'
        Check 'fast: AVI sent with veryfast (subme=2)'   ($f1.Safe -and $f1.Subme -eq 2)
        Check 'fast: no fallback needed'                 ($f1.Out -notmatch "fast converter didn't work")
        $env:WATCH_ENCODER = 'medium'
        $f2 = Send-Gamma 'medium'
        Check 'WATCH_ENCODER=medium: medium used (subme=7)' ($f2.Safe -and $f2.Subme -eq 7)
        $env:WATCH_ENCODER = $null
        $env:WATCH_TEST_BREAK_FAST = '1'
        $f3 = Send-Gamma 'fallback'
        Check 'fallback: says the fast converter failed' ($f3.Out -match "fast converter didn't work" -and $f3.Out -match 'slower, proven setting')
        Check 'fallback: sent anyway, with medium, Watch-safe' ($f3.Safe -and $f3.Subme -eq 7)
        $env:WATCH_ENCODER = 'medium'
        $f4 = Send-Gamma 'nofallback'
        Check 'forced medium ignores the fast-only break' ($f4.Safe -and $f4.Subme -eq 7 -and $f4.Out -notmatch "fast converter didn't work")
        $env:WATCH_ENCODER = $null; $env:WATCH_TEST_BREAK_FAST = $null
        $env:WATCH_DRY_RUN = $dry; $env:WATCH_STAGING = $stageDir

        Write-Host 'Subtitles in one pass'
        $subSrc = Join-Path $base 'subs.mkv'
        $srt = Join-Path $base 'a.srt'
        [IO.File]::WriteAllText($srt, "1`r`n00:00:01,000 --> 00:00:03,000`r`nHello`r`n")
        $sa = @('-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=320x240:rate=5', '-i', $srt, '-i', $srt, '-i', $srt, '-t', '10',
                '-map', '0', '-map', '1', '-map', '2', '-map', '3', '-c:v', 'libx264', '-preset', 'ultrafast', '-c:s', 'srt',
                '-metadata:s:s:0', 'language=eng', '-metadata:s:s:1', 'language=eng', '-metadata:s:s:2', 'language=fre', $subSrc)
        & $ffmpeg @sa
        $sb = Join-Path $base 'subs-out'; [void](New-Item -ItemType Directory -Force -Path $sb)
        $subScript = Join-Path $base 'subs.ps1'
        # Run Save-Subtitles in its own PowerShell (WatchCommon needs the script folder)
        Set-Content -LiteralPath $subScript -Value @"
. '$Scripts\WatchCommon.ps1'
`$script:Ffmpeg = '$ffmpeg'; `$script:Ffprobe = '$(Find-Tool 'ffprobe')'
`$r = Test-Media '$subSrc' 'Original'
`$made = Save-Subtitles `$r '$sb' 'movie'
(`$made | ForEach-Object { `$_.Name }) -join ','
"@
        $subNames = (& powershell -NoProfile -ExecutionPolicy Bypass -File $subScript 2>&1 | Out-String).Trim()
        Check 'subtitles: 3 tracks saved in one go' (@($subNames.Split(',') | Where-Object { $_ -match '^movie\..+\.srt$' }).Count -eq 3)
        Check 'subtitles: each file has the text' (@(Get-ChildItem $sb -Filter *.srt | Where-Object { (Get-Content -Raw $_.FullName) -match 'Hello' }).Count -eq 3)

        Check 'source folders unchanged (size, time and MD5 of every file)' ((Get-Snapshot) -eq $before)
    } finally {
        foreach ($n in $old.Keys) { [Environment]::SetEnvironmentVariable($n, $old[$n]) }
        Remove-Item -LiteralPath $base -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Write-Host ''
Write-Host "$($script:Pass) passed, $($script:Fail) failed"
if ($script:Fail -gt 0) { exit 1 }
