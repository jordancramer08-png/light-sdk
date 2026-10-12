#===========================================================================
#  Watch - Phone Sync (PC side)
#  Pick movies and TV seasons from the source folders (D:\Movies,
#  D:\Marvel Entertainment, D:\TV - see $SourceRoots below), check that the
#  Light Phone III can play them, fix the ones it can't (on copies only),
#  and send them to /sdcard/Watch in the layout the Watch app reads
#  (CLAUDE.md section 4).
#
#  Files with SxxExx in the name are TV episodes (grouped by show and
#  season); everything else is a movie. Folder names become collections
#  ("Phase One (2008-2012)", "01 - Iron Man (2008)" = story order 1).
#  The scan is saved in private\scan-cache and reused until a folder changes.
#
#  Nothing in the source folders is ever changed. All work happens in
#  %TEMP%\watch-<time>, which is deleted at the end.
#
#  Test mode (no phone): set WATCH_DRY_RUN=<folder> and the finished
#  layout is written to <folder>\Watch instead of the phone.
#
#  Other switches (for testing):
#    -Check <file> <file> ...   only run the library check on these files
#    WATCH_SOURCES=<folder>;<folder>     use other source folders
#    WATCH_BACKUP_DIR                    keep .state backups somewhere else
#
#  Pre-convert (option 6): converts picked titles into a staging folder on
#  this PC (D:\Watch-Staging, or WATCH_STAGING=<folder>) so a later send is
#  just a copy. Send uses a staged copy whenever its source is unchanged.
#
#  The shared code (scan, check, fix, TMDB, send) is in WatchCommon.ps1.
#
#  TMDB: details and posters come from TMDB using the key in
#  private\tmdb-key.txt (never shown). Answers are cached in
#  private\tmdb-cache, so a title is looked up online only once.
#===========================================================================
param(
    [switch]$Check,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$CheckFiles
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}
$OutputEncoding = [Text.Encoding]::UTF8

. (Join-Path $PSScriptRoot 'WatchCommon.ps1')     # settings, scan, check, fix, TMDB, send

#---------------------------------------------------------------------------
# region  MAIN
#---------------------------------------------------------------------------
function Show-WhatItDoes {
    Write-Host ''
    Write-Host '  What this does when the phone is plugged in:'
    Write-Host "    1. Send: lists $($SourceRoots -join ', '),"
    Write-Host '       you pick titles (50 GB cap; one copy of a title found twice) and a'
    Write-Host '       quality; it fixes what the phone cannot play (on copies in TEMP),'
    Write-Host '       adds posters and details from TMDB, and copies them to /sdcard/Watch.'
    Write-Host '    2. Remove: deletes titles you pick from the phone. Watched marks and'
    Write-Host '       positions are kept.'
    Write-Host '    3. Backup / 4. Restore: copies Watch''s saved places (.state) to and'
    Write-Host '       from Documents\Watch-Backups on this PC.'
    Write-Host '    5. Grant: lets Watch read its files on the phone.'
    Write-Host "    6. Pre-convert: converts picked titles into $StageRoot on this PC"
    Write-Host '       (works without the phone), so a later send is just a copy.'
    Write-Host '  The source folders are never changed.'
}

function Invoke-CheckOnly([string[]]$paths) {
    Write-Title 'Library check (nothing is changed or sent)'
    foreach ($p in $paths) {
        $r = Test-Media $p 'Original'
        Write-CheckResult $r ([IO.Path]::GetFileName($p))
    }
}

function Read-MainChoice {
    Write-Title 'Watch - what do you want to do?'
    Write-Host '  1  Send movies and TV to the phone'
    Write-Host '  2  Remove titles from the phone (keeps watched marks and positions)'
    Write-Host '  3  Back up saved places (.state) to this PC'
    Write-Host '  4  Restore saved places from a backup'
    Write-Host '  5  Let Watch read its files (grant file access)'
    Write-Host '  6  Pre-convert titles on this PC now, send later (good overnight)'
    Write-Host '  Q  Quit'
    $a = Read-Host '  Choose'
    if ($null -eq $a) { return 'Q' }
    return $a.Trim().ToUpperInvariant()
}

#--- 1. Send ---------------------------------------------------------------
function Invoke-Send {
    if (-not (Initialize-Ffmpeg 'Watch-Phone-Sync.cmd')) { return }
    Ensure-TvFolder
    Write-Step "Reading $($SourceRoots -join ', ') (read only)"
    $items = @(Get-Library)
    Write-Host "  Found $(@($items | Where-Object Kind -eq 'Movie').Count) movies and $(@($items | Where-Object Kind -eq 'Season').Count) TV seasons."
    if ($items.Count -eq 0) { Write-Warn2 '  Nothing to send.'; return }

    Write-Step 'Looking at what is already there'
    $info = Get-TargetInfo
    foreach ($it in $items) { $it.OnPhone = $info.Dirs.ContainsKey($it.PhoneDir) }
    Write-Host ("  Watch uses {0}; {1} titles/seasons are already there." -f (Format-Size $info.Used), @($items | Where-Object OnPhone).Count)

    $picked = @(Select-Titles $items $info)
    if ($picked.Count -eq 0) { Write-Host '  Nothing picked. Nothing was changed.'; return }

    $script:Quality = Read-Quality
    Write-Step "Checking the $($picked.Count) picked title(s) at $($script:Quality)"
    $est = Get-Estimate $picked $script:Quality
    $after = $info.Used + $est.Bytes
    Write-Host ''
    Write-Host ("  Estimated size to send: {0}. Watch would then use {1} of the {2} cap." -f (Format-Size $est.Bytes), (Format-Size $after), (Format-Size $CapBytes))
    if ($est.Problems -eq 0) { Write-Good '  Library check: every picked file is already Watch-safe.' }
    else { Write-Warn2 "  Library check: $($est.Problems) file(s) will be made Watch-safe (listed above), on copies in %TEMP%." }
    if ($after -gt $CapBytes) { Write-Bad '  That is over the 50 GB cap. Pick less or a smaller quality, then try again.'; return }
    if ($null -ne $info.Free -and $est.Bytes -gt $info.Free) { Write-Bad '  There is not enough free space for that.'; return }

    $replacing = @($picked | Where-Object OnPhone)
    if ($replacing.Count -gt 0) {
        Write-Warn2 "  $($replacing.Count) of these are already there and would be replaced."
        Write-Note '  Watched marks and positions are kept (they live in .state).'
        if (-not (Read-YesNo '  Replace them?')) { $picked = @($picked | Where-Object { -not $_.OnPhone }) }
        if ($picked.Count -eq 0) { Write-Host '  Nothing left to send.'; return }
    }
    if (-not (Read-YesNo "  Send $($picked.Count) title(s) now?")) { Write-Host '  Nothing was changed.'; return }

    Write-Step 'TMDB (posters and details)'
    Initialize-Tmdb
    $sent = Send-Items $picked $info
    Write-LastSync
    if (-not $DryRoot) { [void](Invoke-Phone ("rm -rf " + (Q $PhoneTmp))) }
    Write-Host ''
    Write-Good "  Done: $sent of $($picked.Count) sent."
    if ($DryRoot) { Write-Host "  Look in $DryRoot to see the layout." }
    else { Write-Host '  Open Watch on the phone; it shows "Updating library..." and then the new titles.' }
}

#--- 6. Pre-convert into the staging folder --------------------------------

# What each picked file needs. @{ File; Base; Item; Label; Verdict; Staged }
function Get-StagePlan($picked, [string]$quality, [double]$speed) {
    $plan = @()
    foreach ($lf in @(Get-LibraryFiles $picked)) {
        $sp = Get-SourceProbe $lf.File.FullName
        $v = Get-FileVerdict $sp.Probe $sp.Boxes $lf.File.Extension.ToLowerInvariant() $lf.File.Length $speed
        $staged = [bool](Get-StagedFile $lf.File $lf.Item.PhoneDir $lf.Base $quality)
        $plan += [pscustomobject]@{ File = $lf.File; Base = $lf.Base; Item = $lf.Item; Label = $lf.Label; Verdict = $v; Staged = $staged }
    }
    return $plan
}

function Invoke-Stage {
    if (-not (Initialize-Ffmpeg 'Watch-Phone-Sync.cmd')) { return }
    Write-Title "Pre-convert titles into $StageRoot"
    Write-Note '  Converts on this PC now (one file at a time), so a later send is just a copy.'
    Write-Note '  The 50 GB phone cap applies when you send, not here. Source folders are never changed.'
    Write-Step "Reading $($SourceRoots -join ', ') (read only)"
    $items = @(Get-Library)
    if ($items.Count -eq 0) { Write-Warn2 '  Nothing found.'; return }
    $picked = @(Select-Titles $items @{ Used = 0.0; Free = $null } 'Pick what to pre-convert on this PC' $false)
    if ($picked.Count -eq 0) { Write-Host '  Nothing picked. Nothing was changed.'; return }
    $quality = Read-Quality
    $script:Quality = $quality

    Write-Step 'Checking the picked files'
    Invoke-ProbeMany @($picked | ForEach-Object { $_.Files })
    $sample = @(Get-LibraryFiles $picked) | Select-Object -First 1
    $samplePath = $null; if ($sample) { $samplePath = $sample.File.FullName }
    $speed = Get-EncodeSpeedOrMeasure $samplePath
    $plan = @(Get-StagePlan $picked $quality $speed)
    # A file needs work when it isn't Watch-safe, or (1080p / 720p) when it is bigger than that quality
    $bad  = @($plan | Where-Object { $_.Verdict.Status -eq $StatusBad })
    $done = @($plan | Where-Object { $_.Staged })
    $todo = @($plan | Where-Object { -not $_.Staged -and $_.Verdict.Status -ne $StatusBad -and (Test-Media $_.File.FullName $quality).NeedsWork })
    $safe = @($plan | Where-Object { -not $_.Staged -and $_.Verdict.Status -ne $StatusBad -and $todo -notcontains $_ })
    $need = 0.0; $secs = 0.0
    foreach ($p in $todo) {
        $est = $p.Verdict.EstBytes; $w = $p.Verdict.WorkSeconds
        $r = Test-Media $p.File.FullName $quality
        if ($r.Shrink) {   # 1080p / 720p: re-encoded to that size, even when already Watch-safe
            $est = $r.Estimate
            $fit = Get-FitSize $p.Verdict.Width $p.Verdict.Height ([int]($r.MaxHeight * 16 / 9)) $r.MaxHeight
            $w = Get-WorkSeconds 'video' $p.Verdict.Duration $p.File.Length $fit[0] $fit[1] $speed
        }
        $p | Add-Member -NotePropertyName WorkSeconds -NotePropertyValue $w -Force
        $need += $est; $secs += $w
    }
    Write-Host ''
    Write-Host ("  {0} file(s) to convert at {1}: about {2} on disk, about {3} of converting." -f $todo.Count, $quality, (Format-Size $need), (Format-Duration $secs))
    if ($done.Count -gt 0) { Write-Good "  $($done.Count) already pre-converted and unchanged: skipped." }
    if ($safe.Count -gt 0) { Write-Good "  $($safe.Count) already Watch-safe: nothing to do, they are copied straight from the source when sent." }
    foreach ($b in $bad) { Write-Bad "  Can't use $($b.File.Name): $($b.Verdict.Reasons -join '; ')" }
    if ($todo.Count -eq 0) { Write-Host '  Nothing to convert.'; return }

    $drive = [IO.Path]::GetPathRoot([IO.Path]::GetFullPath($StageRoot))
    $free = [double](New-Object IO.DriveInfo $drive).AvailableFreeSpace
    Write-Note ("  Free space on {0} {1}" -f $drive, (Format-Size $free))
    if (-not (Test-FitsOnDisk $need $free)) {
        Write-Bad ("  That will not fit: about {0} is needed (plus room to spare) and {1} is free on {2}." -f (Format-Size $need), (Format-Size $free), $drive)
        Write-Bad '  Pick fewer titles or a smaller quality. Nothing was changed.'
        return
    }
    if (-not (Test-Path -LiteralPath $StageRoot)) {
        if (-not (Read-YesNo "  The staging folder $StageRoot does not exist yet. Create it?")) { Write-Host '  Nothing was changed.'; return }
        Assert-SafeTarget $StageRoot
        [void](New-Item -ItemType Directory -Path $StageRoot -Force)
    }
    Write-Note '  Windows will not go to sleep while this runs (no power setting is changed).'
    Write-Note '  Stop any time with Ctrl+C; run it again and it carries on where it stopped.'
    if (-not (Read-YesNo "  Start converting $($todo.Count) file(s) now?")) { Write-Host '  Nothing was changed.'; return }

    $made = 0; $failed = 0; $doneEst = 0.0
    $watch = [Diagnostics.Stopwatch]::StartNew()
    Set-KeepAwake $true
    try {
        for ($i = 0; $i -lt $todo.Count; $i++) {
            $p = $todo[$i]
            # Time left: the estimate for what's left, scaled by how the real speed compares so far
            $left = $secs - $doneEst
            if ($doneEst -gt 60) { $left = $left * ($watch.Elapsed.TotalSeconds / $doneEst) }
            Write-Step ("{0} of {1}: {2}   (about {3} left in total)" -f ($i + 1), $todo.Count, $p.Label, (Format-Duration $left))
            try {
                $res = Invoke-StageFile $p.File $p.Item.PhoneDir $p.Base $quality
                if ($res.What -eq 'staged') { $made++; Write-Good ("  Pre-converted ({0})." -f (Format-Size $res.Bytes)) }
                else { Write-Note "  Nothing to do ($($res.What))." }
            } catch {
                $failed++
                Write-Bad "  $($_.Exception.Message)"
                Write-Bad '  This file was not pre-converted; the rest carry on.'
            }
            $doneEst += $p.WorkSeconds
        }
    } finally {
        Set-KeepAwake $false
    }
    Write-Host ''
    Write-Good ("  Done: {0} pre-converted, {1} failed, in {2}." -f $made, $failed, (Format-Duration $watch.Elapsed.TotalSeconds))
    Write-Host "  They are in $StageRoot. Send them with option 1; they go as a plain copy."
    Write-Note '  Windows can sleep again as usual.'
}

#--- 2. Remove -------------------------------------------------------------

# After a removal: drop an empty collection folder, or a show with no seasons left.
function Remove-EmptyParent([string]$rel) {
    $parent = $rel.Substring(0, $rel.LastIndexOf('/'))
    if ($DryRoot) {
        $p = Join-Path $DryRoot $parent
        if (-not (Test-Path -LiteralPath $p)) { return }
        $left = @(Get-ChildItem -LiteralPath $p -Directory)
        if ($rel.StartsWith('TV/')) { $empty = ($left.Count -eq 0) } else { $empty = (@(Get-ChildItem -LiteralPath $p -Force).Count -eq 0) }
        if ($empty) { Remove-Item -LiteralPath $p -Recurse -Force }
        return
    }
    $pp = Q "$PhoneRoot/$parent"
    if ($rel.StartsWith('TV/')) {
        [void](Invoke-Phone "if [ -z ""`$(find $pp -mindepth 1 -maxdepth 1 -type d)"" ]; then rm -rf $pp; fi")
    } else {
        [void](Invoke-Phone "rmdir $pp 2>/dev/null")
    }
}

function Invoke-Remove {
    Write-Step 'Looking at what is on the phone'
    $items = @(Get-TargetItems)
    if ($items.Count -eq 0) { Write-Host '  Nothing is on the phone yet.'; return }
    $info = @{ Used = 0.0; Free = $null }
    $picked = @(Select-Titles $items $info 'Pick what to REMOVE from the phone' $false)
    if ($picked.Count -eq 0) { Write-Host '  Nothing picked. Nothing was changed.'; return }
    Write-Title 'These will be deleted from the phone'
    $bytes = 0.0
    foreach ($it in $picked) { Write-Host ('  {0,9}  {1}' -f (Format-Size $it.Bytes), $it.Label); $bytes += $it.Bytes }
    Write-Host ''
    Write-Note '  Watched marks and positions stay, so a title sent again resumes where you stopped.'
    Write-Note '  The files on this PC are not touched.'
    if (-not (Read-YesNo ("  Delete these {0} ({1}) from the phone?" -f $picked.Count, (Format-Size $bytes)))) {
        Write-Host '  Nothing was changed.'; return
    }
    foreach ($it in $picked) {
        Remove-Target $it.PhoneDir
        Remove-EmptyParent $it.PhoneDir
        Write-Good "  Removed: $($it.Label)"
    }
    Write-LastSync
    Write-Good ("  Done: {0} freed." -f (Format-Size $bytes))
}

#--- 3. Backup / 4. Restore ------------------------------------------------

# Copies .state into Documents\Watch-Backups\state-<time>[-note]. Returns the folder, or $null.
function Invoke-Backup([string]$note = '') {
    $name = "state-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    if ($note) { $name = "$name-$note" }
    $dest = Join-Path $BackupRoot $name
    Assert-SafeTarget $dest
    [void](New-Item -ItemType Directory -Path $dest -Force)
    if ($DryRoot) {
        $src = Join-Path $DryRoot '.state'
        if (Test-Path -LiteralPath $src) {
            Get-ChildItem -LiteralPath $src -File | Where-Object { $_.Extension -notin @('.tmp', '.part') } |
                ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $dest }
        }
    } else {
        $ls = Invoke-Phone "ls $PhoneRoot/.state 2>/dev/null"
        foreach ($f in $ls.Out) {
            $f = $f.Trim()
            if (-not $f -or $f -match '\.(tmp|part)$') { continue }
            $r = Invoke-Adb pull "$PhoneRoot/.state/$f" (Join-Path $dest $f)
            if ($r.Code -ne 0) { Write-Bad "  Could not copy $f from the phone."; return $null }
        }
    }
    $files = @(Get-ChildItem -LiteralPath $dest -File)
    if ($files.Count -eq 0) {
        Remove-Item -LiteralPath $dest -Force
        Write-Warn2 '  Watch has no saved places on the phone yet; nothing to back up.'
        return $null
    }
    foreach ($f in $files) { Write-Note ('    {0,-16} {1}' -f $f.Name, (Format-Size $f.Length)) }
    Write-Good "  Backed up to $dest"
    return $dest
}

function Invoke-Restore {
    $backups = @()
    if (Test-Path -LiteralPath $BackupRoot) {
        $backups = @(Get-ChildItem -LiteralPath $BackupRoot -Directory -Filter 'state-*' | Sort-Object Name -Descending)
    }
    if ($backups.Count -eq 0) { Write-Warn2 "  No backups in $BackupRoot yet."; return }
    Write-Title 'Restore saved places from a backup'
    for ($i = 0; $i -lt $backups.Count; $i++) {
        $b = $backups[$i]
        $n = @(Get-ChildItem -LiteralPath $b.FullName -File).Count
        Write-Host ('{0,4}  {1}   ({2} files)' -f ($i + 1), $b.Name, $n)
    }
    $a = Read-Host '  Number to restore (Enter = cancel)'
    if ($null -eq $a -or $a.Trim() -notmatch '^\d+$') { Write-Host '  Nothing was changed.'; return }
    $k = [int]$a.Trim()
    if ($k -lt 1 -or $k -gt $backups.Count) { Write-Host '  Not on the list. Nothing was changed.'; return }
    $pick = $backups[$k - 1]
    Write-Warn2 "  This replaces Watch's watched marks, positions, watchlist and settings with $($pick.Name)."
    Write-Note '  What is on the phone now is backed up first, so this can be undone.'
    if (-not (Read-YesNo '  Restore it?')) { Write-Host '  Nothing was changed.'; return }

    Write-Step 'Backing up what is there now'
    [void](Invoke-Backup 'before-restore')
    if (-not $DryRoot) {
        Write-Note '  Closing Watch on the phone so it does not write over the restore.'
        [void](Invoke-Phone "am force-stop $AppPackage")
    }
    Write-Step "Restoring $($pick.Name)"
    foreach ($f in (Get-ChildItem -LiteralPath $pick.FullName -File)) {
        Send-File $f.FullName '.state' $f.Name
        Write-Note "    $($f.Name)"
    }
    Write-Good '  Restored. Open Watch on the phone.'
}

#--- 5. Grant file access --------------------------------------------------
function Invoke-Grant {
    $cmd = "appops set $AppPackage MANAGE_EXTERNAL_STORAGE allow"
    if ($DryRoot) { Write-Note "  TEST MODE: on the phone this runs:  adb shell $cmd"; return }
    $pm = Invoke-Phone "pm path $AppPackage"
    if (-not (($pm.Out -join ' ') -match 'package:')) {
        Write-Warn2 '  Watch is not installed on the phone yet. Install it first, then run this again.'
        return
    }
    [void](Invoke-Phone $cmd)
    $check = Invoke-Phone "appops get $AppPackage MANAGE_EXTERNAL_STORAGE"
    if (($check.Out -join ' ') -match 'allow') { Write-Good '  Done: Watch can read /sdcard/Watch. Open Watch on the phone.' }
    else { Write-Bad ('  The phone did not accept it: ' + ($check.Out -join ' ')) }
}

function Main {
    Write-Title 'Watch - Phone Sync'
    if ($Check) {
        $script:Ffmpeg = Find-Ffmpeg 'ffmpeg'; $script:Ffprobe = Find-Ffmpeg 'ffprobe'
        if (-not $script:Ffprobe) { Write-Bad '  ffmpeg is not installed (winget install Gyan.FFmpeg).'; return }
        Invoke-CheckOnly @($CheckFiles); return
    }

    [void](New-Item -ItemType Directory -Path $WorkDir -Force)
    if (-not (Initialize-Target)) {
        # Pre-converting needs no phone; everything else does.
        Show-WhatItDoes
        Write-Host ''
        if (Read-YesNo '  No phone: pre-convert titles on this PC instead (option 6)?') { Invoke-Stage }
        Write-Host ''; Write-Host '  Stopped: nothing on the phone was changed.'
        return
    }

    while ($true) {
        switch (Read-MainChoice) {
            '1' { Invoke-Send }
            '2' { Invoke-Remove }
            '3' { Write-Step 'Backing up saved places'; [void](Invoke-Backup) }
            '4' { Invoke-Restore }
            '5' { Write-Step 'Granting file access'; Invoke-Grant }
            '6' { Invoke-Stage }
            'Q' { return }
        }
    }
}

$logFile = Start-WatchLog 'sync'
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
