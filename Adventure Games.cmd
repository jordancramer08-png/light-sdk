@echo off
setlocal EnableExtensions
title Adventure - Phone Games
set "ABS_SELF=%~f0"
where powershell >nul 2>nul
if errorlevel 1 (
  echo Windows PowerShell was not found on this PC.
  pause
  exit /b 1
)
set "LOGDIR=%~dp0logs"
if not exist "%LOGDIR%" mkdir "%LOGDIR%"
for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "STAMP=%%i"
set "GAMES_LOG=%LOGDIR%\adventure-games-%STAMP%.log"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Transcript -LiteralPath $env:GAMES_LOG | Out-Null; try { $t=[IO.File]::ReadAllText($env:ABS_SELF); $m=[char]35+'BEGIN-POWERSHELL'+[char]35; $i=$t.LastIndexOf($m); if($i -lt 0){Write-Host 'Script body not found.' -ForegroundColor Red; exit 1}; Invoke-Expression $t.Substring($i) } finally { Stop-Transcript | Out-Null }"
if errorlevel 1 (
  echo.
  echo The script ended with an error.
)
echo.
echo Log: %GAMES_LOG%
if not defined ADVENTURE_NO_PAUSE pause
endlocal
exit /b
#BEGIN-POWERSHELL#
#===========================================================================
#  Adventure - Phone Games
#  Adds and removes story files on the Light Phone III for the Adventure tool.
#
#  Phone layout (the Adventure shelf reads from here):
#    /sdcard/Adventure/Games/<story files>
#
#  Files on this laptop are only ever read. Nothing here deletes or changes
#  them. The only delete in this script is "rm" on the phone, for files you
#  picked, after you have seen a preview and said yes.
#
#  Test hooks (ignored in normal use):
#    ADVENTURE_ADB      path to a stand-in for adb
#    ADVENTURE_DRY_RUN  when set, shows what would happen and changes nothing
#===========================================================================

$ErrorActionPreference = 'Stop'
Set-StrictMode -Off
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}

$PhoneGames = '/sdcard/Adventure/Games'
$AppPackage = 'com.thelightphone.adventure'
$StoryExt   = @('.z3', '.z4', '.z5', '.z8', '.zblorb', '.zlb')
$AdbPath    = ''          # leave blank to find adb.exe automatically
$DryRun     = [bool]$env:ADVENTURE_DRY_RUN
if ($env:ADVENTURE_ADB) { $AdbPath = $env:ADVENTURE_ADB }

$script:Adb = $null
$script:Serial = $null

function Write-Title($text) {
    Write-Host ''
    Write-Host ('=' * 70) -ForegroundColor DarkCyan
    Write-Host ("  " + $text) -ForegroundColor Cyan
    Write-Host ('=' * 70) -ForegroundColor DarkCyan
}
function Write-Good($text)  { Write-Host $text -ForegroundColor Green }
function Write-Warn2($text) { Write-Host $text -ForegroundColor Yellow }
function Write-Bad($text)   { Write-Host $text -ForegroundColor Red }
function Read-YesNo($prompt) { return ((Read-Host "$prompt (Y/N)") -match '^\s*y') }
function Pause-Here { [void](Read-Host 'Press Enter to continue') }

# Single-quote a string for the phone's shell
function Q([string]$s) { return "'" + $s.Replace("'", "'\''") + "'" }

function Format-Size([double]$bytes) {
    if ($bytes -ge 1MB) { return ('{0:N1} MB' -f ($bytes / 1MB)) }
    return ('{0:N0} KB' -f ($bytes / 1KB))
}

# Parse "1,3,5-8" into numbers; $null if it is not a number list
function Parse-Numbers([string]$text) {
    $found = New-Object System.Collections.Generic.List[int]
    foreach ($part in ($text -split ',')) {
        $p = $part.Trim()
        if ($p -match '^\d+$') { $found.Add([int]$p) }
        elseif ($p -match '^(\d+)-(\d+)$') { foreach ($n in ([int]$Matches[1])..([int]$Matches[2])) { $found.Add($n) } }
        else { return $null }
    }
    return @($found | Select-Object -Unique)
}

function Find-Adb {
    if ($AdbPath) { if (Test-Path -LiteralPath $AdbPath) { return $AdbPath } else { return $null } }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $cands = @()
    foreach ($v in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) { if ($v) { $cands += (Join-Path $v 'platform-tools\adb.exe') } }
    if ($env:LOCALAPPDATA) { $cands += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe') }
    if ($env:USERPROFILE) { $cands += (Join-Path $env:USERPROFILE 'Downloads\platform-tools\adb.exe') }
    $cands += 'C:\platform-tools\adb.exe'
    foreach ($c in $cands) { if (Test-Path -LiteralPath $c) { return $c } }
    return $null
}

# Run adb and return @{ Code; Out } without throwing on stderr
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

function Connect-Phone {
    $script:Serial = $null
    $ready = @(); $waiting = @()
    foreach ($line in (Invoke-Adb devices).Out) {
        if ($line -match '^(\S+)\s+device$') { $ready += $Matches[1] }
        elseif ($line -match '^(\S+)\s+(unauthorized|offline)') { $waiting += $Matches[1] }
    }
    if ($ready.Count -gt 0) { $script:Serial = $ready[0]; return $true }
    if ($waiting.Count -gt 0) { Write-Warn2 'Unlock the phone and tap "Allow" on the USB debugging prompt, then try again.' }
    else { Write-Warn2 'No phone found. Plug it in with a data USB cable and make sure USB debugging is on.' }
    return $false
}

function Get-PhoneFree {
    $last = (Invoke-Phone 'df -k /sdcard').Out | Where-Object { $_ -match '\d+\s+\d+\s+\d+' } | Select-Object -Last 1
    if (-not $last) { return -1 }
    $cols = @($last -split '\s+' | Where-Object { $_ -ne '' })
    if ($cols.Count -ge 4) { return [double]$cols[3] * 1KB }
    return -1
}

# The games on the phone, as objects with Name, Size and Path
function Get-PhoneGames {
    $r = Invoke-Phone ("find " + (Q $PhoneGames) + " -maxdepth 1 -type f -exec stat -c '%s|%n' {} + 2>/dev/null")
    $games = @()
    foreach ($line in $r.Out) {
        if ($line -match '^(\d+)\|(.+)$') {
            $path = $Matches[2]
            $games += [pscustomobject]@{ Name = $path.Substring($path.LastIndexOf('/') + 1); Size = [double]$Matches[1]; Path = $path }
        }
    }
    return @($games | Sort-Object Name)
}

function Show-Games($games) {
    if ($games.Count -eq 0) { Write-Host '  No added games on the phone. (Zork I-III are inside the app.)'; return }
    $n = 0
    foreach ($g in $games) { $n++; Write-Host ('  {0,3}. {1}  ({2})' -f $n, $g.Name, (Format-Size $g.Size)) }
}

# Why a file cannot be played, or $null if it looks like a story file
function Get-Problem($file) {
    if ($file.Length -lt 64) { return 'too short to be a story file' }
    $stream = [IO.File]::OpenRead($file.FullName)
    try {
        $head = New-Object byte[] 12
        [void]$stream.Read($head, 0, 12)
    } finally { $stream.Dispose() }
    $text = [Text.Encoding]::ASCII.GetString($head)
    if ($text.StartsWith('FORM') -and $text.Substring(8) -eq 'IFRS') { return $null }
    if ($head[0] -in 3, 4, 5, 8) { return $null }
    return "Z-machine version $($head[0]) is not supported"
}

function Menu-List {
    Write-Title 'GAMES ON THE PHONE'
    Show-Games @(Get-PhoneGames)
}

function Read-Folder {
    Write-Host 'Type or paste the folder that holds the story files. Dragging it here works too.'
    $folder = (Read-Host 'Folder').Trim().Trim('"')
    if ($folder -and (Test-Path -LiteralPath $folder -PathType Container)) { return $folder }
    Write-Warn2 'That folder was not found.'
    return $null
}

function Show-AddPreview($add, $skip) {
    Write-Title 'PREVIEW: NOTHING HAS CHANGED YET'
    Write-Host 'Will copy to the phone:'
    if ($add.Count -eq 0) { Write-Host '  (nothing)' }
    foreach ($f in $add) { Write-Good ('  + {0}  ({1})' -f $f.Name, (Format-Size $f.Length)) }
    if ($skip.Count -gt 0) { Write-Host 'Will skip:' }
    foreach ($s in $skip) { Write-Warn2 ('  - {0}: {1}' -f $s.Name, $s.Why) }
}

function Menu-Add {
    Write-Title 'ADD GAMES'
    $folder = Read-Folder
    if (-not $folder) { return }
    $onPhone = @{}
    foreach ($g in (Get-PhoneGames)) { $onPhone[$g.Name.ToLowerInvariant()] = $g }
    $add = @(); $skip = @()
    foreach ($f in (Get-ChildItem -LiteralPath $folder -File | Where-Object { $StoryExt -contains $_.Extension.ToLowerInvariant() } | Sort-Object Name)) {
        $problem = Get-Problem $f
        $same = $onPhone[$f.Name.ToLowerInvariant()]
        if ($problem) { $skip += [pscustomobject]@{ Name = $f.Name; Why = $problem } }
        elseif ($same -and $same.Size -eq $f.Length) { $skip += [pscustomobject]@{ Name = $f.Name; Why = 'already on the phone' } }
        elseif ($same) { $skip += [pscustomobject]@{ Name = $f.Name; Why = 'a different file with this name is on the phone; rename one or remove it first' } }
        else { $add += $f }
    }
    Show-AddPreview $add $skip
    if ($add.Count -eq 0) { return }
    $bytes = ($add | Measure-Object Length -Sum).Sum
    $free = Get-PhoneFree
    Write-Host ('Total {0}. Free on the phone: {1}.' -f (Format-Size $bytes), $(if ($free -ge 0) { Format-Size $free } else { 'unknown' }))
    if ($free -ge 0 -and $bytes -gt $free) { Write-Bad 'Not enough room on the phone.'; return }
    if (-not (Read-YesNo 'Copy these to the phone?')) { Write-Host 'Nothing changed.'; return }
    if ($DryRun) { foreach ($f in $add) { Write-Warn2 "[dry run] would copy $($f.Name)" }; return }
    [void](Invoke-Phone ("mkdir -p " + (Q $PhoneGames)))
    foreach ($f in $add) {
        $r = Invoke-Adb push $f.FullName "$PhoneGames/$($f.Name)"
        if ($r.Code -eq 0) { Write-Good "Copied $($f.Name)" } else { Write-Bad "Could not copy $($f.Name): $($r.Out -join ' ')" }
    }
    Grant-Storage -Quiet
}

function Menu-Remove {
    Write-Title 'REMOVE GAMES FROM THE PHONE'
    $games = @(Get-PhoneGames)
    Show-Games $games
    if ($games.Count -eq 0) { return }
    $numbers = Parse-Numbers (Read-Host 'Numbers to remove, like 1,3 or 2-4 (blank to cancel)')
    if ($null -eq $numbers) { Write-Host 'Nothing changed.'; return }
    $picked = @($numbers | Where-Object { $_ -ge 1 -and $_ -le $games.Count } | ForEach-Object { $games[$_ - 1] })
    if ($picked.Count -eq 0) { Write-Host 'Nothing changed.'; return }
    Write-Title 'PREVIEW: NOTHING HAS CHANGED YET'
    Write-Host 'Will delete from the phone only:'
    foreach ($g in $picked) { Write-Warn2 ('  - {0}  ({1})' -f $g.Name, (Format-Size $g.Size)) }
    Write-Host 'Saves stay in the app, and come back if the same game is added again.'
    Write-Host 'To remove a game and its saves together, use Remove on the phone instead.'
    if (-not (Read-YesNo 'Delete these from the phone?')) { Write-Host 'Nothing changed.'; return }
    foreach ($g in $picked) { Remove-FromPhone $g }
}

function Remove-FromPhone($game) {
    if ($game.Path -ne "$PhoneGames/$($game.Name)") { Write-Bad "Skipped $($game.Name): not in the games folder."; return }
    if ($DryRun) { Write-Warn2 "[dry run] would delete $($game.Path) from the phone"; return }
    $r = Invoke-Phone ("rm -f -- " + (Q $game.Path))
    if ($r.Code -eq 0) { Write-Good "Removed $($game.Name)" } else { Write-Bad "Could not remove $($game.Name): $($r.Out -join ' ')" }
}

function Grant-Storage([switch]$Quiet) {
    $installed = (Invoke-Phone "pm list packages $AppPackage").Out | Where-Object { $_.Trim() -eq "package:$AppPackage" }
    if (-not $installed) {
        Write-Warn2 'Adventure is not installed yet, so storage access cannot be granted. Run option 4 after installing.'
        return
    }
    if ($DryRun) { Write-Warn2 '[dry run] would grant Adventure storage access'; return }
    $r = Invoke-Phone "appops set $AppPackage MANAGE_EXTERNAL_STORAGE allow"
    if ($r.Code -ne 0) { Write-Warn2 ('Could not grant storage access: ' + ($r.Out -join ' ')) }
    elseif (-not $Quiet) { Write-Good 'Adventure can now read the games folder.' }
}

function Menu-Main {
    while ($true) {
        Write-Title 'ADVENTURE - PHONE GAMES'
        if ($DryRun) { Write-Warn2 '  DRY RUN: nothing will be changed' }
        Write-Host '  1. List games on the phone'
        Write-Host '  2. Add games from a folder on this laptop'
        Write-Host '  3. Remove games from the phone'
        Write-Host '  4. Give Adventure storage access (needed once after installing)'
        Write-Host '  Q. Quit'
        switch -Regex ((Read-Host 'Choose').Trim()) {
            '^1$' { Menu-List }
            '^2$' { Menu-Add }
            '^3$' { Menu-Remove }
            '^4$' { Grant-Storage }
            '^[qQ]$' { return }
            default { Write-Warn2 'Please choose 1, 2, 3, 4 or Q.' }
        }
    }
}

$script:Adb = Find-Adb
if (-not $script:Adb) {
    Write-Bad 'Could not find adb.exe (Android platform-tools).'
    Write-Host 'Put its full path in the $AdbPath setting near the top of this file.'
    Pause-Here; exit 1
}
while (-not (Connect-Phone)) {
    if (-not (Read-YesNo 'Try again?')) { exit 0 }
}
Menu-Main
Write-Host 'Bye.'
