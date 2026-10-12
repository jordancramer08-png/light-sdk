#===========================================================================
#  Watch - Library Report (PC side, read only)
#  Looks at every video in the source folders (the same list as
#  Watch-Phone-Sync: D:\Movies, D:\Marvel Entertainment, D:\TV, or
#  WATCH_SOURCES=<folder>;<folder>) and marks each one against the
#  Watch-safe rules (WatchSafe.ps1, phone_day\decoders.txt):
#     OK                    - goes to the phone untouched
#     Needs a quick fix     - copied into an MP4 / sound converted (minutes)
#     Needs a full convert  - picture re-encoded (hours)
#     Can't play            - damaged, or no picture
#  with the reason, the size after converting and the time it takes.
#
#  Saves phone_day\library-report.csv and phone_day\library-report.html and
#  opens the page. Nothing in the source folders is changed.
#
#  Fast to run again: ffprobe answers are kept in private\probe-cache.tsv, so
#  only new or changed files are read. Stop any time with Ctrl+C; the next
#  run carries on where it stopped.
#
#  Switches (for testing):  -NoOpen          don't open the page
#                           WATCH_REPORT_DIR  save the report somewhere else
#===========================================================================
param([switch]$NoOpen)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}
$OutputEncoding = [Text.Encoding]::UTF8

. (Join-Path $PSScriptRoot 'WatchCommon.ps1')     # settings, scan, check rules, probe cache

$ReportDir = Join-Path $RepoRoot 'phone_day'
if ($env:WATCH_REPORT_DIR) { $ReportDir = $env:WATCH_REPORT_DIR }
$script:Finished = $false

#---------------------------------------------------------------------------
# region  CHECKING  (ffprobe, with progress)
#---------------------------------------------------------------------------

# Reads every file not checked before, showing a count and the time left.
function Invoke-CheckAll($files) {
    $todo = @($files | Where-Object { -not (Test-ProbeCached $_) })
    $known = $files.Count - $todo.Count
    if ($known -gt 0) { Write-Good ("  {0:N0} of {1:N0} files were checked before and are unchanged (saved answers)." -f $known, $files.Count) }
    if ($todo.Count -eq 0) { return }
    Write-Host ("  Checking {0:N0} new or changed file(s) with ffprobe, {1} at a time. Ctrl+C stops; run again to carry on." -f $todo.Count, $ProbeWorkers)
    $script:PDone = 0; $script:PTotal = $todo.Count
    $script:PWatch = [Diagnostics.Stopwatch]::StartNew(); $script:PLastLine = 0.0
    $tick = {
        param($fi)
        $script:PDone++
        $el = $script:PWatch.Elapsed.TotalSeconds
        $left = 0.0; if ($script:PDone -gt 0) { $left = ($script:PTotal - $script:PDone) * $el / $script:PDone }
        $status = '{0:N0} of {1:N0} - about {2} left' -f $script:PDone, $script:PTotal, (Format-Duration $left)
        Write-Progress -Activity 'Checking files (read only)' -Status $status -PercentComplete ([int](100 * $script:PDone / $script:PTotal))
        if ($el - $script:PLastLine -ge 10 -or $script:PDone -eq $script:PTotal) {
            $script:PLastLine = $el
            Write-Note "  $status"
        }
    }
    Invoke-ProbeMany $todo $tick
    Write-Progress -Activity 'Checking files (read only)' -Completed
}

# One report row per file.
function New-ReportRow($lf, [double]$speed) {
    $f = $lf.File
    $sp = Get-SourceProbe $f.FullName
    $v = Get-FileVerdict $sp.Probe $sp.Boxes $f.Extension.ToLowerInvariant() $f.Length $speed
    $root = [IO.Path]::GetFullPath($lf.Item.Root).TrimEnd('\')
    $group = $null; if ($lf.Kind -eq 'Movie') { $group = $lf.Item.Collection }
    if ($lf.Kind -eq 'Episode') { $group = $lf.Item.Title }
    if (-not $group) { $group = '' }
    $note = ''; if ($lf.Item.Label -match '\(also elsewhere\)') { $note = 'same title is in another folder too' }
    return [pscustomobject][ordered]@{
        Status = $v.Status; Title = ($lf.Label -replace '\s+\(also elsewhere\)$', ''); Source = $root; Kind = $lf.Kind
        Group = $group; File = $f.FullName.Substring($root.Length).TrimStart('\'); Type = $f.Extension.TrimStart('.').ToUpperInvariant()
        Picture = $v.Picture; Sound = $v.Sound; Minutes = [math]::Round($v.Duration / 60, 1)
        Bytes = [double]$f.Length; EstBytes = [math]::Round($v.EstBytes); Est720Bytes = [math]::Round($v.Est720Bytes)
        WorkSeconds = [math]::Round($v.WorkSeconds); Reason = (@($v.Reasons) -join '; '); Fix = $v.Fix; Note = $note
    }
}
#endregion

#---------------------------------------------------------------------------
# region  SAVING  (CSV and the web page)
#---------------------------------------------------------------------------
function Get-GB([double]$b) { return [math]::Round($b / 1GB, 2) }

function Save-Csv($rows, [string]$path) {
    $rows | ForEach-Object {
        [pscustomobject][ordered]@{
            'Status' = $_.Status; 'Title' = $_.Title; 'Source folder' = $_.Source; 'Kind' = $_.Kind
            'Collection or show' = $_.Group; 'File' = $_.File; 'Type' = $_.Type; 'Picture' = $_.Picture
            'Sound' = $_.Sound; 'Minutes' = $_.Minutes; 'Size now (GB)' = (Get-GB $_.Bytes)
            'After converting, Original (GB)' = (Get-GB $_.EstBytes); 'After converting, 720p (GB)' = (Get-GB $_.Est720Bytes)
            'Convert time (min)' = [math]::Round($_.WorkSeconds / 60); 'Reason' = $_.Reason; 'Fix' = $_.Fix; 'Note' = $_.Note
        }
    } | Export-Csv -LiteralPath $path -NoTypeInformation -Encoding UTF8
}

function ConvertTo-Html2([string]$s) { return [Net.WebUtility]::HtmlEncode($s) }

function Get-TotalsHtml($rows, $roots) {
    $sb = New-Object Text.StringBuilder
    $lines = @()
    foreach ($r in $roots) { $lines += ,@($r, (Get-ReportTotals @($rows | Where-Object { $_.Source -eq $r }))) }
    $lines += ,@('Everything', (Get-ReportTotals $rows))
    foreach ($l in $lines) {
        $t = $l[1]
        $cls = ''; if ($l[0] -eq 'Everything') { $cls = ' class="all"' }
        [void]$sb.Append("<tr$cls><th scope=""row"">$(ConvertTo-Html2 $l[0])</th><td>$('{0:N0}' -f $t.Files)</td>")
        [void]$sb.Append("<td>$('{0:N0}' -f $t.Ok)</td><td>$('{0:N0}' -f $t.Quick)</td><td>$('{0:N0}' -f $t.Full)</td><td>$('{0:N0}' -f $t.Bad)</td>")
        [void]$sb.Append("<td>$(Format-Size $t.Bytes)</td><td>$(Format-Size $t.EstBytes)</td><td>$(Format-Size $t.Est720Bytes)</td><td>$(Format-Duration $t.WorkSeconds)</td></tr>")
    }
    return $sb.ToString()
}

function Save-Html($rows, $roots, [string]$path, [double]$speed) {
    $data = @($rows | ForEach-Object {
        [ordered]@{ s = $_.Status; t = $_.Title; src = $_.Source; k = $_.Kind; g = $_.Group; f = $_.File; ty = $_.Type
            p = $_.Picture; a = $_.Sound; m = $_.Minutes; b = $_.Bytes; e = $_.EstBytes; e7 = $_.Est720Bytes
            w = $_.WorkSeconds; r = $_.Reason; x = $_.Fix; n = $_.Note }
    })
    $json = ConvertTo-Json -InputObject $data -Compress -Depth 3
    $json = $json.Replace('</', '<\/')
    $when = Get-Date -Format 'd MMMM yyyy, HH:mm'
    $speedLine = 'Convert times assume this PC converts 1 hour of 1080p film in about {0} (measured; it gets more exact as you convert).' -f (Format-Duration ($speed * 3600))
    $totals = Get-TotalsHtml $rows $roots
    $template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'report-template.html'))
    $html = $template.Replace('{{WHEN}}', (ConvertTo-Html2 $when)).Replace('{{SPEED}}', (ConvertTo-Html2 $speedLine))
    $html = $html.Replace('{{TOTALS}}', $totals).Replace('{{DATA}}', $json)
    [IO.File]::WriteAllText($path, $html, (New-Object Text.UTF8Encoding($false)))
}
#endregion

#---------------------------------------------------------------------------
# region  MAIN
#---------------------------------------------------------------------------
function Write-Summary($rows, $roots) {
    Write-Title 'Totals'
    $fmt = '  {0,-26} {1,6} {2,6} {3,6} {4,6} {5,6} {6,10} {7,10} {8,14}'
    Write-Host ($fmt -f '', 'files', 'OK', 'quick', 'full', "can't", 'size now', 'after', 'convert time') -ForegroundColor DarkGray
    $list = @(); foreach ($r in $roots) { $list += ,@((Split-Path -Leaf $r), @($rows | Where-Object { $_.Source -eq $r })) }
    $list += ,@('Everything', $rows)
    foreach ($l in $list) {
        $t = Get-ReportTotals $l[1]
        Write-Host ($fmt -f $l[0], $t.Files, $t.Ok, $t.Quick, $t.Full, $t.Bad, (Format-Size $t.Bytes), (Format-Size $t.EstBytes), (Format-Duration $t.WorkSeconds))
    }
}

function Main {
    Write-Title 'Watch - Library Report (read only: nothing is changed)'
    if (-not (Initialize-Ffmpeg 'Watch-Library-Report.cmd')) { return }
    Write-Step "Reading $($SourceRoots -join ', ')"
    $items = @(Get-Library)
    $files = @(Get-LibraryFiles $items)
    if ($files.Count -eq 0) { Write-Warn2 '  No video files found.'; $script:Finished = $true; return }
    $bytes = 0.0; foreach ($lf in $files) { $bytes += $lf.File.Length }
    Write-Host ("  {0:N0} video files, {1} in total." -f $files.Count, (Format-Size $bytes))

    Write-Step 'Checking every file against the Watch-safe rules'
    Invoke-CheckAll @($files | ForEach-Object { $_.File })

    Write-Step 'Working out sizes and times'
    $sample = $null
    foreach ($lf in $files) {
        $p = (Get-SourceProbe $lf.File.FullName).Probe
        $v = $null; if ($p) { $v = @((Split-Streams $p).Videos) | Select-Object -First 1 }
        if ($v -and [int](Get-P $v 'height') -ge 1000 -and [int](Get-P $v 'height') -le 1090) { $sample = $lf.File.FullName; break }
    }
    $speed = Get-EncodeSpeedOrMeasure $sample
    $rows = @()
    $rows = @(foreach ($lf in $files) { New-ReportRow $lf $speed })
    $roots = @($rows | ForEach-Object { $_.Source } | Select-Object -Unique)

    if (-not (Test-Path -LiteralPath $ReportDir)) { [void](New-Item -ItemType Directory -Path $ReportDir -Force) }
    Assert-SafeTarget $ReportDir
    $csv = Join-Path $ReportDir 'library-report.csv'
    $html = Join-Path $ReportDir 'library-report.html'
    Save-Csv $rows $csv
    Save-Html $rows $roots $html $speed
    $script:Finished = $true
    Write-Summary $rows $roots
    Write-Host ''
    Write-Good "  Saved: $html"
    Write-Good "         $csv"
    Write-Note '  Sizes and times are estimates. "after" = size at Original quality once converted.'
    if (-not $NoOpen) { Start-Process $html }
}

$logFile = Start-WatchLog 'report'
try {
    Main
} catch {
    Write-Bad "  Something went wrong: $($_.Exception.Message)"
    Write-Note $_.ScriptStackTrace
    exit 1
} finally {
    if (-not $script:Finished) {
        Write-Host ''
        Write-Warn2 '  Stopped before the report was finished. What was checked so far is saved;'
        Write-Warn2 '  double-click Watch-Library-Report.cmd again to carry on from there.'
    }
    try { Stop-Transcript | Out-Null } catch {}
    Write-Note "  Log: $logFile"
}
#endregion
