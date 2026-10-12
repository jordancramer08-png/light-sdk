#===========================================================================
#  Watch - Smoke Test (PC side)
#  Picks one real file of each kind from the source folders (an MP4 with AAC,
#  an MKV, an AVI, one with AC3 sound, a 720p one, a 1080p one and a Marvel
#  TV episode), cuts a 20-second clip of each (on a copy in %TEMP%), makes
#  it Watch-safe the normal way and sends it to the phone:
#      Movies/Smoke Test/Smoke 1 - MP4 with AAC sound/movie.mp4 ...
#      TV/Smoke Test TV/Season 01/S01E01.mp4
#  Then prints what was sent and a short checklist to try on the phone
#  (also saved as phone_day\smoke-test.txt).
#
#  Nothing in the source folders is changed. Test mode (no phone):
#  WATCH_DRY_RUN=<folder> writes the layout there instead.
#===========================================================================
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}
$OutputEncoding = [Text.Encoding]::UTF8

. (Join-Path $PSScriptRoot 'WatchCommon.ps1')     # settings, scan, check, fix, send

$ClipSeconds = 20
$SmokeCollection = 'Smoke Test'
$SmokeShow = 'Smoke Test TV'

#---------------------------------------------------------------------------
# region  PICKING REAL EXAMPLES
#---------------------------------------------------------------------------

# What the smoke rules need to know about one file (from its ffprobe answer), or $null.
function New-SmokeRecord($lf) {
    if (-not (Test-ProbeCached $lf.File)) { return $null }
    $p = (Get-SourceProbe $lf.File.FullName).Probe
    if (-not $p) { return $null }
    $st = Split-Streams $p
    if ($st.Videos.Count -eq 0) { return $null }
    $d = 0.0; $fd = Get-P (Get-P $p 'format') 'duration'; if ($fd) { $d = [double]$fd }
    return [pscustomobject]@{ Path = $lf.File.FullName; Ext = $lf.File.Extension.ToLowerInvariant(); Kind = $lf.Kind
        Root = [string]$lf.Item.Root; AudioCodecs = @($st.Audio | ForEach-Object { [string](Get-P $_ 'codec_name') })
        Height = [int](Get-P $st.Videos[0] 'height'); Duration = $d; Lf = $lf }
}

# Files worth reading first: the kinds still missing that the name already gives away.
function Get-ProbeOrder($files) {
    $first = @()
    $first += @($files | Where-Object { $_.File.Extension -ieq '.avi' } | Select-Object -First 3)
    $first += @($files | Where-Object { $_.File.Extension -ieq '.mkv' -and $_.Kind -eq 'Movie' } | Select-Object -First 3)
    $first += @($files | Where-Object { $_.Kind -eq 'Episode' -and [string]$_.Item.Root -match 'Marvel' } | Select-Object -First 3)
    $first += @($files | Where-Object { $_.File.Extension -ieq '.mp4' -and $_.Kind -eq 'Movie' } | Select-Object -First 6)
    # then a spread through the whole library
    $rest = @($files | Where-Object { $first -notcontains $_ })
    $spread = @()
    $step = [math]::Max(1, [math]::Floor($rest.Count / 200))
    for ($i = 0; $i -lt $rest.Count; $i += $step) { $spread += $rest[$i] }
    return @($first + $spread)
}

# One example per kind. Uses saved ffprobe answers first; reads more files only while a kind is missing.
function Find-SmokeExamples($files) {
    $order = @(Get-ProbeOrder $files)
    $pos = 0
    while ($true) {
        $records = @($files | ForEach-Object { New-SmokeRecord $_ } | Where-Object { $_ })
        $picks = Select-SmokeExamples $records
        $missing = @($picks | Where-Object { -not $_.Record }).Count
        if ($missing -eq 0 -or $pos -ge $order.Count) { return ,$picks }
        $batch = @()
        while ($batch.Count -lt 24 -and $pos -lt $order.Count) {
            if (-not (Test-ProbeCached $order[$pos].File)) { $batch += $order[$pos].File }
            $pos++
        }
        if ($batch.Count -gt 0) { Write-Note "  Looking at $($batch.Count) more file(s) to find: $(@($picks | Where-Object { -not $_.Record } | ForEach-Object { $_.Name }) -join ', ')"; Invoke-ProbeMany $batch }
    }
}
#endregion

#---------------------------------------------------------------------------
# region  CLIPS AND SENDING
#---------------------------------------------------------------------------

# Cuts $ClipSeconds from the original into $dir (same file type, nothing re-encoded). Returns the FileInfo.
function New-Clip($rec, [string]$dir, [int]$n) {
    Assert-SafeTarget $dir
    if (-not (Test-Path -LiteralPath $dir)) { [void](New-Item -ItemType Directory -Path $dir -Force) }
    $out = Join-Path $dir ("clip$n" + $rec.Ext)
    $start = Get-ClipStart $rec.Duration
    $tries = @(@('-map', '0'), @('-map', '0:v:0', '-map', '0:a?'))
    foreach ($maps in $tries) {
        $a = @('-hide_banner', '-loglevel', 'error', '-y', '-ss', "$start", '-i', $rec.Path, '-t', "$ClipSeconds") + $maps + @('-c', 'copy', '-avoid_negative_ts', 'make_zero')
        if ($rec.Ext -eq '.mp4') { $a += @('-movflags', '+faststart') }
        $a += $out
        $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        try { & $script:Ffmpeg @a 2>&1 | Out-Null; $ok = ($LASTEXITCODE -eq 0) } finally { $ErrorActionPreference = $old }
        if ($ok -and (Test-Path -LiteralPath $out) -and (Get-Item -LiteralPath $out).Length -gt 0) { return (Get-Item -LiteralPath $out) }
    }
    throw "could not cut a clip from $($rec.Path)"
}

# A menu item for one clip, shaped like the ones Watch-Phone-Sync sends.
function New-SmokeItem($pick, $clip, [int]$n) {
    $rec = $pick.Record
    # Only the TV example goes as an episode; any other clip goes as a movie (even if cut from an episode)
    if ($pick.Name -eq 'Marvel TV episode') {
        $ep = [pscustomobject]@{ Show = $SmokeShow; Season = 1; Episode = 1; File = $clip
            Title = "$($pick.Name) ($([IO.Path]::GetFileNameWithoutExtension($rec.Path)))" }
        return [pscustomobject]@{ Kind = 'Season'; Label = "$SmokeShow - $($pick.Name)"; Title = $SmokeShow; Year = $null
            ShowName = $SmokeShow; Season = 1; Episodes = @($ep); Id = "$SmokeShow/Season 01"; PhoneDir = "TV/$SmokeShow/Season 01"
            Files = @($clip); Bytes = [double]$clip.Length; Source = $rec.Path; Root = $rec.Root; DupKey = "smoke|$n"
            OnPhone = $false; Selected = $true; Name = $pick.Name }
    }
    $title = Get-SafeName "Smoke $n - $($pick.Name)"
    return [pscustomobject]@{ Kind = 'Movie'; Label = $title; Title = $title; Year = $null; Collection = $SmokeCollection
        Number = $n; Id = "$SmokeCollection/$title"; PhoneDir = "Movies/$SmokeCollection/$title"; Files = @($clip)
        Bytes = [double]$clip.Length; Source = $rec.Path; Root = $rec.Root; DupKey = "smoke|$n"
        OnPhone = $false; Selected = $true; Name = $pick.Name }
}

function Get-Checklist($sent) {
    $lines = @()
    $lines += 'Watch smoke test - try each clip on the phone'
    $lines += '============================================='
    $lines += "Sent $(Get-Date -Format 'yyyy-MM-dd HH:mm'). Movies are in Collections > $SmokeCollection; the episode is in TV > $SmokeShow."
    $lines += ''
    foreach ($s in $sent) { $lines += ("  {0,-26} from {1}" -f $s.Name, $s.Source) }
    $lines += ''
    $lines += 'For EACH clip:'
    $lines += '  [ ] Plays: picture and sound start within a few seconds'
    $lines += '  [ ] Seeks: drag the bar to the middle; it carries on from there. Skip 15 s back and forward'
    $lines += '  [ ] Speed: 1.5x, then 0.5x - the voices keep their normal pitch; then back to 1.0x'
    $lines += '  [ ] Resumes: press Back at about 10 s, open it again - it starts about 5 s earlier'
    $lines += ''
    $lines += 'Once:'
    $lines += '  [ ] At the end of Smoke 1, "Next up" offers Smoke 2 with a 10-second countdown'
    $lines += '  [ ] Rotate button turns the clip without restarting it'
    $lines += '  [ ] A clip whose original had subtitles shows them in the subtitle picker'
    $lines += ''
    $lines += 'A clip that fails: run Watch-Why-Wont-It-Play.cmd and pick the original title.'
    $lines += 'When done: Watch-Phone-Sync.cmd > 2 Remove > "Smoke" removes the clips.'
    return ,$lines
}
#endregion

#---------------------------------------------------------------------------
# region  MAIN
#---------------------------------------------------------------------------
function Main {
    Write-Title 'Watch - Smoke Test'
    if (-not (Initialize-Ffmpeg 'Watch-Smoke-Test.cmd')) { return }
    [void](New-Item -ItemType Directory -Path $WorkDir -Force)
    if (-not (Initialize-Target)) {
        Write-Host ''
        Write-Host '  What this does when the phone is plugged in: picks one real file of each kind'
        Write-Host '  (MP4 with AAC, MKV, AVI, AC3 sound, 720p, 1080p, a Marvel TV episode), sends a'
        Write-Host '  20-second clip of each to Watch, and prints a checklist to try on the phone.'
        Write-Host '  Stopped: nothing was changed.'
        return
    }
    Write-Step "Reading $($SourceRoots -join ', ') (read only)"
    $files = @(Get-LibraryFiles @(Get-Library))
    Write-Step 'Picking one real example of each kind'
    $picks = Find-SmokeExamples $files
    foreach ($p in $picks) {
        if ($p.Record) { Write-Host ("  {0,-22} {1}" -f $p.Name, $p.Record.Path) }
        else { Write-Warn2 ("  {0,-22} none found in your folders - skipped" -f $p.Name) }
    }
    $picks = @($picks | Where-Object { $_.Record })
    if ($picks.Count -eq 0) { Write-Warn2 '  Nothing to send.'; return }

    $info = Get-TargetInfo
    $old = @($info.Dirs.Keys | Where-Object { $_ -like "Movies/$SmokeCollection/*" -or $_ -like "TV/$SmokeShow/*" })
    if ($old.Count -gt 0) {
        Write-Warn2 "  An earlier smoke test ($($old.Count) clip folder(s)) is on the phone."
        if (-not (Read-YesNo '  Replace it with the new clips?')) { Write-Host '  Nothing was changed.'; return }
        Remove-Target "Movies/$SmokeCollection"
        Remove-Target "TV/$SmokeShow"
        $info = Get-TargetInfo
    }
    if (-not (Read-YesNo "  Send $($picks.Count) clip(s) of $ClipSeconds seconds?")) { Write-Host '  Nothing was changed.'; return }

    $script:Quality = 'Original'
    $script:TmdbOff = $true            # clips go with their file names, no TMDB lookups
    $clipDir = Join-Path $WorkDir 'clips'
    $sent = @()
    $n = 0
    foreach ($p in $picks) {
        $n++
        Write-Step "$($p.Name): cutting $ClipSeconds s from $([IO.Path]::GetFileName($p.Record.Path))"
        try {
            $clip = New-Clip $p.Record $clipDir $n
            $item = New-SmokeItem $p $clip $n
            if ((Send-Items @($item) $info) -eq 1) { $sent += $item }
        } catch {
            Write-Bad "  $($_.Exception.Message)"
            Write-Bad '  This clip was not sent; the rest carry on.'
        }
    }
    if ($sent.Count -gt 0) { Write-LastSync }
    if (-not $DryRoot) { [void](Invoke-Phone ("rm -rf " + (Q $PhoneTmp))) }

    $lines = Get-Checklist $sent
    Write-Title "Sent $($sent.Count) of $($picks.Count) clips"
    foreach ($l in $lines) { Write-Host $l }
    $dir = Join-Path $RepoRoot 'phone_day'
    if (-not (Test-Path -LiteralPath $dir)) { [void](New-Item -ItemType Directory -Path $dir -Force) }
    $txt = Join-Path $dir 'smoke-test.txt'
    [IO.File]::WriteAllText($txt, (($lines -join "`r`n") + "`r`n"), (New-Object Text.UTF8Encoding($false)))
    Write-Host ''
    Write-Good "  Checklist saved: $txt"
}

$logFile = Start-WatchLog 'smoke'
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
