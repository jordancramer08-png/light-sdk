#===========================================================================
#  Watch - Why Won't It Play? (PC side)
#  Pick a title from any source folder (type words to search). It checks:
#    1. the original file: can it be read, is it Watch-safe, is its length
#       right (picture and sound only - subtitles may run longer), are the
#       first and last 30 seconds readable without errors;
#    2. the copy on the phone: is it there, complete, with its json, and
#       can Watch read its files.
#  Then it says in plain words what is wrong and, with your yes, fixes it
#  (on a copy in %TEMP%) and sends it again.
#
#  Nothing in the source folders is changed. With no phone it only checks
#  the original. Test mode: WATCH_DRY_RUN=<folder> (the dry-run folder is
#  checked and sent to instead of the phone).
#===========================================================================
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}
$OutputEncoding = [Text.Encoding]::UTF8

. (Join-Path $PSScriptRoot 'WatchCommon.ps1')     # settings, scan, check, fix, send

#---------------------------------------------------------------------------
# region  CHECKS  (each returns findings: @{ Text; Bad; Fixable })
#---------------------------------------------------------------------------
function New-Finding([string]$text, [bool]$bad, [bool]$fixable) { return [pscustomobject]@{ Text = $text; Bad = $bad; Fixable = $fixable } }

# Counts read errors in a stretch of the file (picture and sound decoded, nothing written).
function Get-ReadErrors([string]$path, [string[]]$where) {
    $a = @('-hide_banner', '-v', 'error') + $where + @('-i', $path, '-t', '30', '-map', '0:v:0', '-map', '0:a?', '-f', 'null', 'NUL')
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $out = @(& $script:Ffmpeg @a 2>&1 | ForEach-Object { "$_" } | Where-Object { $_.Trim() }) } finally { $ErrorActionPreference = $old }
    return $out.Count
}

function Test-Original($lf) {
    $f = New-Object IO.FileInfo $lf.File.FullName
    $list = @()
    if (-not $f.Exists) { return @(New-Finding "The file is gone from the PC: $($f.FullName)" $true $false) }
    if ($f.Length -eq 0) { return @(New-Finding 'The file is empty (0 bytes). Get a fresh copy.' $true $false) }
    $sp = Get-SourceProbe $f.FullName -Fresh
    if (-not $sp.Probe) { return @(New-Finding (Get-PlainExplanation "ffprobe can't read it") $true $false) }
    $r = Test-Media $f.FullName 'Original'
    if ($r.CantFix) { return @(New-Finding (Get-PlainExplanation $r.CantFix) $true $false) }
    $h = '?'; if ($r.Height) { $h = "$($r.Height)p" }
    Write-Note ("    {0} {1}, sound {2}, {3}, {4}" -f $h, (Get-CodecLabel $r.VideoCodec), (Get-SoundLabel $r.Audio), (Format-Duration $r.Duration), (Format-Size $f.Length))
    if ($r.Problems.Count -eq 0) { $list += New-Finding 'The file is Watch-safe: the phone can play it as it is.' $false $false }
    foreach ($p in $r.Problems) { $list += New-Finding (Get-PlainExplanation $p) $true $true }
    foreach ($n in $r.Notes) { $list += New-Finding "Note: $n." $false $false }

    # Length: measured by picture and sound only (Session 10b), so subtitles running long don't count
    $play = Get-PlayLength $f.FullName $r.Duration
    if ($r.Duration - $play -gt 1) {
        $list += New-Finding ("The file says it is {0:N1} s long, but its picture and sound end at {1:N1} s (subtitle or extra tracks run on). That is fine: the check before sending measures picture and sound only." -f $r.Duration, $play) $false $false
    }
    $startErr = Get-ReadErrors $f.FullName @()
    $endErr = Get-ReadErrors $f.FullName @('-sseof', '-30')
    if ($startErr + $endErr -gt 0) {
        $list += New-Finding ("Reading the first and last 30 seconds gave {0} error(s): the file is probably damaged in places, and the phone may freeze or skip there. Converting can smooth small damage, but a fresh copy of the file is the real fix." -f ($startErr + $endErr)) $true $true
    } else {
        $list += New-Finding 'The first and last 30 seconds read without errors.' $false $false
    }
    return $list
}

# The phone (or dry-run folder) listing of one title folder: @{ name = bytes }, or $null when missing.
function Get-TargetListing([string]$rel) {
    $map = @{}
    if ($DryRoot) {
        $p = Join-Path $DryRoot $rel
        if (-not (Test-Path -LiteralPath $p)) { return $null }
        foreach ($f in (Get-ChildItem -LiteralPath $p -File)) { $map[$f.Name] = [double]$f.Length }
        return $map
    }
    $r = Invoke-Phone ("ls -l " + (Q "$PhoneRoot/$rel") + " 2>/dev/null || echo MISSING")
    if ($r.Out -contains 'MISSING') { return $null }
    foreach ($l in $r.Out) {
        if ($l -match '^-\S*\s+\d+\s+\S+\s+\S+\s+(\d+)\s+\S+\s+\S+\s+(.+)$') { $map[$Matches[2].Trim()] = [double]$Matches[1] }
    }
    return $map
}

function Read-TargetText([string]$rel) {
    if ($DryRoot) {
        $p = Join-Path $DryRoot $rel
        if (-not (Test-Path -LiteralPath $p)) { return $null }
        return [IO.File]::ReadAllText($p)
    }
    $r = Invoke-Phone ("cat " + (Q "$PhoneRoot/$rel") + " 2>/dev/null")
    if ($r.Code -ne 0) { return $null }
    return ($r.Out -join "`n")
}

# Checks one video + json pair in a title folder.
function Test-TargetVideo($listing, [string]$rel, [string]$base, [string]$what) {
    $list = @()
    $video = "$base.mp4"
    $parts = @($listing.Keys | Where-Object { $_ -like "$base*.part" })
    if ($parts.Count -gt 0) { $list += New-Finding "$what was cut off part-way while sending (a .part file is left on the phone). Sending it again fixes it." $true $true }
    if (-not $listing.ContainsKey($video)) {
        $other = @($listing.Keys | Where-Object { $_ -like "$base.*" -and $_ -match '\.(mkv|avi|m4v|mov|webm)$' })
        if ($other.Count -gt 0) { $list += New-Finding "$what is on the phone as $($other[0]), from before Watch-safe sending. Sending it again replaces it with a Watch-safe MP4." $true $true }
        else { $list += New-Finding "$what has no $video on the phone. Sending it again fixes it." $true $true }
        return $list
    }
    $jsonText = Read-TargetText "$rel/$base.json"
    if (-not $jsonText) { $list += New-Finding "$what has no $base.json, so Watch shows it with the folder name and no details. Sending it again fixes it." $true $true; return $list }
    try { $j = $jsonText | ConvertFrom-Json } catch { $j = $null }
    if (-not $j) { $list += New-Finding "$base.json on the phone is broken. Sending it again fixes it." $true $true; return $list }
    $want = Get-P $j 'bytes'
    if ($want -and [double]$want -ne $listing[$video]) {
        $list += New-Finding ("{0} on the phone is {1}, but {2} was sent: it is incomplete or changed. Sending it again fixes it." -f $video, (Format-Size $listing[$video]), (Format-Size ([double]$want))) $true $true
    } elseif ($listing[$video] -lt 1MB) {
        $list += New-Finding "$video on the phone is almost empty ($(Format-Size $listing[$video])). Sending it again fixes it." $true $true
    } else {
        $vc = Get-P $j 'videoCodec'
        if ($vc -and $vc -ne 'h264') { $list += New-Finding "The copy on the phone was sent as $(Get-CodecLabel $vc), before the Watch-safe rules. Sending it again makes it H.264." $true $true }
        else { $list += New-Finding "$what on the phone is complete ($(Format-Size $listing[$video]))." $false $false }
    }
    return $list
}

function Test-PhoneCopy($item) {
    $list = @()
    if (-not $DryRoot) {
        $pm = Invoke-Phone "pm path $AppPackage"
        if (-not (($pm.Out -join ' ') -match 'package:')) { return @(New-Finding 'Watch is not installed on the phone.' $true $false) }
        $ap = Invoke-Phone "appops get $AppPackage MANAGE_EXTERNAL_STORAGE"
        if (-not (($ap.Out -join ' ') -match 'allow')) {
            $list += New-Finding 'Watch is not allowed to read its files on the phone, so it can''t open any video. Fix: Watch-Phone-Sync.cmd > 5 (grant file access).' $true $false
        }
    }
    $listing = Get-TargetListing $item.PhoneDir
    if ($null -eq $listing) { $list += New-Finding 'It is not on the phone. Sending it fixes that.' $true $true; return $list }
    if ($item.Kind -eq 'Movie') {
        $list += Test-TargetVideo $listing $item.PhoneDir 'movie' 'The movie'
    } else {
        foreach ($ep in $item.Episodes) {
            $code = 'S{0:D2}E{1:D2}' -f $ep.Season, $ep.Episode
            $list += @(Test-TargetVideo $listing $item.PhoneDir $code $code | Where-Object { $_.Bad })
        }
        if (@($list | Where-Object { $_.Bad }).Count -eq 0) { $list += New-Finding "All $($item.Episodes.Count) episodes on the phone are complete." $false $false }
    }
    return $list
}
#endregion

#---------------------------------------------------------------------------
# region  MAIN
#---------------------------------------------------------------------------
function Write-Findings($list) {
    foreach ($x in $list) {
        if ($x.Bad) { Write-Warn2 "    - $($x.Text)" } else { Write-Good "    - $($x.Text)" }
    }
}

function Invoke-Why($item, [bool]$haveTarget, $info) {
    Write-Title $item.Label
    $all = @()
    Write-Step 'The original on this PC (read only)'
    foreach ($lf in @(Get-LibraryFiles @($item))) {
        if ($item.Kind -eq 'Season') { Write-Host "  $($lf.Base)  $($lf.File.Name)" }
        $f = @(Test-Original $lf)
        if ($item.Kind -eq 'Season') { $f = @($f | Where-Object { $_.Bad -or $_.Text -match '^Note' }) }
        Write-Findings $f
        $all += $f
    }
    $blocked = @($all | Where-Object { $_.Bad -and -not $_.Fixable })
    if ($haveTarget) {
        Write-Step 'The copy on the phone'
        $p = @(Test-PhoneCopy $item)
        Write-Findings $p
        $all += $p
    }
    $bad = @($all | Where-Object { $_.Bad })
    $fixable = @($bad | Where-Object { $_.Fixable })
    Write-Host ''
    if ($bad.Count -eq 0) {
        Write-Good '  Nothing wrong found. If it still will not play: restart the phone, open Watch, and wait for "Updating library..." to finish.'
        return
    }
    if ($blocked.Count -gt 0 -or $fixable.Count -eq 0) { Write-Warn2 '  What is wrong with the original cannot be fixed by converting or sending again (see above).'; return }
    if (-not $haveTarget) { Write-Warn2 '  Plug in the phone and run this again to fix it and send it.'; return }
    Write-Host '  Fixing means: make it Watch-safe on a copy in %TEMP% (the original stays as it is),'
    Write-Host '  check it with ffprobe, and send it again, replacing the copy on the phone.'
    if (-not (Read-YesNo '  Fix it and send it again?')) { Write-Host '  Nothing was changed.'; return }
    $script:Quality = Read-Quality
    $item.OnPhone = $info.Dirs.ContainsKey($item.PhoneDir)
    $used = $info.Used
    if ($item.OnPhone) {   # the old copy is removed first, so it doesn't count against the cap
        $old = @(Get-TargetItems | Where-Object { $_.PhoneDir -eq $item.PhoneDir }) | Select-Object -First 1
        if ($old) { $used = [math]::Max(0, $used - $old.Bytes) }
    }
    Initialize-Tmdb
    $sent = Send-Items @($item) @{ Used = $used; Free = $info.Free; Dirs = $info.Dirs }
    if ($sent -gt 0) {
        Write-LastSync
        Write-Good '  Sent again. Open Watch on the phone; it shows "Updating library..." and then the title.'
    }
}

function Main {
    Write-Title "Watch - Why won't it play?"
    if (-not (Initialize-Ffmpeg 'Watch-Why-Wont-It-Play.cmd')) { return }
    [void](New-Item -ItemType Directory -Path $WorkDir -Force)
    $haveTarget = Initialize-Target
    if (-not $haveTarget) { Write-Note '  No phone: only the original on this PC is checked; nothing is sent.' }
    Write-Step "Reading $($SourceRoots -join ', ') (read only)"
    $items = @(Get-Library)
    if ($items.Count -eq 0) { Write-Warn2 '  No titles found.'; return }
    $info = @{ Used = 0.0; Free = $null; Dirs = @{} }
    if ($haveTarget) {
        $info = Get-TargetInfo
        foreach ($it in $items) { $it.OnPhone = $info.Dirs.ContainsKey($it.PhoneDir) }
    }
    Write-Note '  Type words to search (e.g. "iron man 2008"), then the number of the title.'
    $picked = @(Select-Titles $items $info "Pick the title that won't play" $false)
    if ($picked.Count -eq 0) { Write-Host '  Nothing picked. Nothing was changed.'; return }
    foreach ($item in $picked) {
        try { Invoke-Why $item $haveTarget $info }
        catch { Write-Bad "  $($_.Exception.Message)" }
    }
    if ($haveTarget -and -not $DryRoot) { [void](Invoke-Phone ("rm -rf " + (Q $PhoneTmp))) }
}

$logFile = Start-WatchLog 'why'
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
