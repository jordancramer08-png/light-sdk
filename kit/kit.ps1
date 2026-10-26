<#
  Hand-off kit engine (Session 2, Oct 2026). Shared by the double-click files
  at the top of each app folder. Settings for this app are in kit\app.psd1.

    -Action install   install the newest APK in releases\ with adb install -r
    -Action backup    copy the app's data from the device to Documents\App Backups
    -Action restore   put a backup back (backs up first, asks for YES)
    -Action rebuild   build from source, save the APK in releases\ (never installs)
    -Preview          check the PC side and show what would happen; touches no device
#>
param(
    [ValidateSet('install','backup','restore','rebuild')][string]$Action = 'backup',
    [switch]$Preview
)
# 'Continue': adb and gradle write normal messages to stderr; every result is checked by hand.
$ErrorActionPreference = 'Continue'
$KitDir  = $PSScriptRoot
$Root    = Split-Path $KitDir -Parent
# Read app.psd1 as data only (no commands allowed in it).
$CfgBlock = [scriptblock]::Create((Get-Content -LiteralPath (Join-Path $KitDir 'app.psd1') -Raw))
$CfgBlock.CheckRestrictedLanguage([string[]]@(), [string[]]@(), $false)
$Cfg     = & $CfgBlock
$Pkg     = $Cfg.Package
$TabletSerial = 'R9TY304232V'
$Stamp   = Get-Date -Format 'yyyy-MM-dd HHmm-ss'
$LogDir  = Join-Path $Root 'logs'
[void](New-Item -ItemType Directory -Path $LogDir -Force)
$Log     = Join-Path $LogDir ("kit-$Action-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
$BackupRoot = Join-Path ([Environment]::GetFolderPath('MyDocuments')) ("App Backups\" + $Cfg.AppName)

function Log([string]$t)  { Add-Content -LiteralPath $Log -Value $t -Encoding UTF8 }
function Say([string]$t, [string]$c = 'Gray') { Write-Host $t -ForegroundColor $c; Log $t }
function Good([string]$t) { Say $t 'Green' }
function Bad([string]$t)  { Say $t 'Red' }
function Step([string]$t) { Write-Host ''; Say "=== $t ===" 'Cyan' }
function Stop-Kit([string]$why) {
    if ($why) { Bad $why }
    Say "Nothing more was done. The log is here: $Log"
    exit 1
}
function Ask-Yes([string]$what) {
    Write-Host ''
    $a = Read-Host "  Type YES (capitals) and press Enter to $what, or just press Enter to stop"
    Log "Answer: $a"
    return ($a -ceq 'YES')
}

#--- tools on this PC ---------------------------------------------------------
function Find-Adb {
    $c = Get-Command adb.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($c) { return $c.Source }
    foreach ($p in @("$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe", 'C:\Android\sdk\platform-tools\adb.exe')) {
        if (Test-Path -LiteralPath $p) { return $p }
    }
    Stop-Kit 'MISSING: adb (the Android phone tool) is not on this PC. It normally lives in C:\Users\<you>\AppData\Local\Android\Sdk\platform-tools. Install "Android SDK Platform-Tools" and try again.'
}
function Find-Sdk {
    $lp = Join-Path $Root 'local.properties'
    if (Test-Path -LiteralPath $lp) {
        $line = Get-Content -LiteralPath $lp | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) { $d = ($line -replace '^sdk\.dir=', '') -replace '\\:', ':' -replace '\\\\', '\'; if (Test-Path -LiteralPath $d) { return $d } }
    }
    foreach ($d in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk", 'C:\Android\sdk')) {
        if ($d -and (Test-Path -LiteralPath (Join-Path $d 'build-tools'))) { return $d }
    }
    return $null
}
function Find-BuildTool([string]$name) {
    $sdk = Find-Sdk
    if (-not $sdk) { return $null }
    $f = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
         Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending |
         ForEach-Object { Get-ChildItem -LiteralPath $_.FullName -Filter $name -ErrorAction SilentlyContinue } |
         Select-Object -First 1
    if ($f) { return $f.FullName } else { return $null }
}
function Get-ApkInfo([string]$apk) {
    $aapt = Find-BuildTool 'aapt2.exe'
    if (-not $aapt) { return $null }
    $line = (& $aapt dump badging $apk 2>$null | Select-String '^package:' | Select-Object -First 1)
    if (-not $line) { return $null }
    $s = $line.ToString()
    [pscustomobject]@{
        Package = if ($s -match "name='([^']+)'") { $Matches[1] } else { '' }
        VersionName = if ($s -match "versionName='([^']*)'") { $Matches[1] } else { '' }
        VersionCode = if ($s -match "versionCode='([^']*)'") { $Matches[1] } else { '' }
    }
}
function Get-ApkKey([string]$apk) {
    $signer = Find-BuildTool 'apksigner.bat'
    if (-not $signer) { return $null }
    $out = & cmd /c "`"$signer`" verify --print-certs `"$apk`" 2>&1"
    $m = $out | Select-String 'certificate SHA-256 digest:\s*([0-9a-f]+)' | Select-Object -First 1
    if ($m) { return $m.Matches[0].Groups[1].Value } else { return $null }
}
function Get-ReleaseApk {
    $dir = Join-Path $Root 'releases'
    Get-ChildItem -LiteralPath $dir -Filter '*.apk' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
}

#--- the device -----------------------------------------------------------------
function Adb { & $script:AdbExe @args 2>&1 | ForEach-Object { "$_" } }
function Test-Device([switch]$NeedApp) {
    Say 'Looking for a connected device...'
    [void](Adb start-server)
    $lines = Adb devices | Select-Object -Skip 1 | Where-Object { $_.Trim() }
    Log ($lines -join "`n")
    $ready = @($lines | Where-Object { $_ -match "`tdevice$" })
    if ($lines | Where-Object { $_ -match "`tunauthorized$" }) {
        Stop-Kit 'A device is plugged in but has not allowed this PC yet. On the device, tap "Allow" on the USB debugging question, then run this again.'
    }
    if ($ready.Count -eq 0) { Stop-Kit "No device is connected. Plug in your $($Cfg.Device) with its USB cable, unlock it, and run this again." }
    if ($ready.Count -gt 1) { Stop-Kit "$($ready.Count) devices are connected. Unplug everything except your $($Cfg.Device) and run this again." }
    $serial = ($ready[0] -split "`t")[0]
    $model  = ((Adb shell getprop ro.product.model) -join '').Trim()
    $maker  = ((Adb shell getprop ro.product.manufacturer) -join '').Trim()
    Say "Connected: $maker $model (serial $serial)"
    if ($Cfg.Device -eq 'Tablet') {
        if ($serial -ne $TabletSerial) { Stop-Kit "That is not your tablet (its serial is $TabletSerial). Unplug it and plug in the tablet." }
    } else {
        if ($serial -eq $TabletSerial) { Stop-Kit 'That is your TABLET. This app is for the Light Phone. Unplug the tablet and plug in the Light Phone.' }
        if ("$maker $model" -notmatch '(?i)light|lp3') {
            Say "This device does not call itself a Light Phone (it says: $maker $model)." 'Yellow'
            Say 'If you are sure it IS your Light Phone 3, type LIGHTPHONE and press Enter. Otherwise just press Enter.' 'Yellow'
            $a = Read-Host '  '
            Log "Model answer: $a"
            if ($a -cne 'LIGHTPHONE') { Stop-Kit 'Stopped because the device is not the Light Phone.' }
        }
    }
    if ($NeedApp) {
        $p = Adb shell pm list packages $Pkg
        if (-not ($p | Where-Object { $_.Trim() -eq "package:$Pkg" })) { Stop-Kit "$($Cfg.AppName) is not installed on this device, so there is nothing to back up or restore." }
    }
    Good 'Device check passed.'
}
function Get-InstalledKey {
    $p = (Adb shell pm path $Pkg | Where-Object { $_ -match '^package:' } | Select-Object -First 1)
    if (-not $p) { return $null }
    $tmp = Join-Path $env:TEMP "kit-installed-$Pkg.apk"
    [void](Adb pull ($p -replace '^package:', '').Trim() $tmp)
    if (-not (Test-Path -LiteralPath $tmp)) { return $null }
    $k = Get-ApkKey $tmp
    Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    return $k
}
function Grant-Permissions {
    foreach ($op in @($Cfg.AppOps)) {
        if (-not $op) { continue }
        [void](Adb shell appops set $Pkg $op allow)
        $chk = (Adb shell appops get $Pkg $op) -join ' '
        Log "appops $op -> $chk"
        if ($chk -match 'allow') { Good "  Permission $op is switched on." }
        else { Bad "  The device did not accept permission $op ($chk). Open the app; it may ask for it." }
    }
}

#--- backup ----------------------------------------------------------------------
function Invoke-Backup([string]$label = '') {
    $name = $Stamp + $(if ($label) { " $label" } else { '' })
    $out = Join-Path $BackupRoot $name
    [void](New-Item -ItemType Directory -Path $out -Force)
    $count = 0
    # Close the app so a database is not caught half-written. This deletes nothing.
    [void](Adb shell am force-stop $Pkg)

    # 1) the app's private storage (works because these are debug builds)
    $probe = (Adb shell run-as $Pkg id) -join ' '
    if ($probe -match 'not debuggable|unknown package|run-as:') {
        Bad "  The device will not let this PC read the app's private data ($probe)."
    } else {
        $files = Adb shell run-as $Pkg find . -type f | ForEach-Object { $_.Trim() } | Where-Object { $_ -like './*' }
        foreach ($f in $files) {
            $rel = $f.Substring(2)
            if (@($Cfg.SkipPrivate) | Where-Object { $_ -and ($rel -eq $_ -or $rel.StartsWith("$_/")) }) { continue }
            $local = Join-Path (Join-Path $out 'private') ($rel -replace '/', '\')
            [void](New-Item -ItemType Directory -Path (Split-Path $local -Parent) -Force)
            # cmd does the redirect so binary files are copied byte for byte
            & cmd /c "`"$script:AdbExe`" exec-out run-as $Pkg cat '$rel' > `"$local`" 2>nul"
            Log "private: $rel ($((Get-Item -LiteralPath $local).Length) bytes)"
            $count++
        }
        Say "  App-private files copied: $count"
    }
    # 2) folders in shared storage (/sdcard)
    foreach ($dir in @($Cfg.SharedDirs)) {
        if (-not $dir) { continue }
        $exists = (Adb shell "ls -d '$dir' 2>/dev/null") -join ''
        if (-not $exists.Trim()) { Say "  $dir is not on the device (nothing to copy there)."; continue }
        $dest = Join-Path (Join-Path $out 'shared') (($dir.TrimStart('/')) -replace '/', '\')
        [void](New-Item -ItemType Directory -Path $dest -Force)
        $r = Adb pull "$dir/." $dest
        Log ($r -join "`n")
        Say "  Copied $dir"
    }
    $ver = (Adb shell dumpsys package $Pkg | Select-String 'versionName=' | Select-Object -First 1)
    @("App: $($Cfg.AppName) ($Pkg)", "Made: $(Get-Date -Format 'dddd d MMMM yyyy, HH:mm')",
      "App version on device: $("$ver".Trim())", "Private files: $count",
      "Shared folders: $(@($Cfg.SharedDirs) -join ', ')") |
        Set-Content -LiteralPath (Join-Path $out 'ABOUT THIS BACKUP.txt') -Encoding UTF8
    Good "  Backup saved in: $out"
    return $out
}

#--- restore ---------------------------------------------------------------------
function Invoke-Restore {
    $all = @(Get-ChildItem -LiteralPath $BackupRoot -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending)
    if ($all.Count -eq 0) { Stop-Kit "There are no backups in $BackupRoot yet." }
    Step 'Choose a backup'
    $show = [Math]::Min($all.Count, 15)
    for ($i = 0; $i -lt $show; $i++) { Say ("  {0,2}. {1}" -f ($i + 1), $all[$i].Name) }
    $n = Read-Host '  Type the number of the backup to put back (just Enter = 1, the newest)'
    if (-not $n) { $n = '1' }
    if (-not ($n -match '^\d+$') -or [int]$n -lt 1 -or [int]$n -gt $show) { Stop-Kit "There is no backup number $n." }
    $pick = $all[[int]$n - 1]
    Say "  Chosen: $($pick.FullName)"
    if ($Preview) { Say 'PREVIEW: stopping here. With a device connected it would back up first, ask for YES, then copy this backup back.'; return }

    Test-Device -NeedApp
    Say ''
    Say "This REPLACES $($Cfg.AppName)'s data on the device with the backup from $($pick.Name)." 'Yellow'
    Say 'First, a fresh backup of what is on the device now is made, so this can be undone.' 'Yellow'
    if (-not (Ask-Yes 'continue')) { Stop-Kit 'Stopped. Nothing was changed.' }
    Step 'Fresh backup first'
    [void](Invoke-Backup 'before restore')
    Step 'Putting the backup back'
    [void](Adb shell am force-stop $Pkg)
    $n = 0
    $priv = Join-Path $pick.FullName 'private'
    if (Test-Path -LiteralPath $priv) {
        $files = Get-ChildItem -LiteralPath $priv -Recurse -File
        $names = $files | ForEach-Object { $_.FullName.Substring($priv.Length + 1) -replace '\\', '/' }
        foreach ($f in $files) {
            $rel = $f.FullName.Substring($priv.Length + 1) -replace '\\', '/'
            $tmp = "/data/local/tmp/kit-restore-$n"
            # Same way the existing tools do it: adb push (byte-exact), then copy in as the app.
            $r = Adb push $f.FullName $tmp
            Log ($r -join ' ')
            $dir = if ($rel.Contains('/')) { $rel.Substring(0, $rel.LastIndexOf('/')) } else { '.' }
            # Single quotes inside the argument: Windows PowerShell 5.1 mangles inner double quotes.
            $r = @(Adb shell run-as $Pkg mkdir -p "'$dir'") + @(Adb shell run-as $Pkg cp $tmp "'$rel'")
            Log "restore $rel $($r -join ' ')"
            [void](Adb shell rm -f $tmp)
            # A database put back without its journal must not meet the device's newer journal.
            if ($rel -match '\.db$') {
                foreach ($j in @('-wal', '-shm', '-journal')) {
                    if ($names -notcontains "$rel$j") { [void](Adb shell run-as $Pkg rm -f "'$rel$j'") }
                }
            }
            $n++
        }
        Say "  App-private files put back: $n"
    }
    $shared = Join-Path $pick.FullName 'shared'
    if (Test-Path -LiteralPath $shared) {
        foreach ($f in Get-ChildItem -LiteralPath $shared -Recurse -File) {
            $rel = '/' + ($f.FullName.Substring($shared.Length + 1) -replace '\\', '/')
            $r = Adb push $f.FullName $rel
            Log "shared $rel $($r -join ' ')"
        }
        Say '  Shared-storage files put back.'
    }
    Good "Done. Open $($Cfg.AppName) on the device and check your data."
}

#--- install ---------------------------------------------------------------------
function Invoke-Install {
    $apk = Get-ReleaseApk
    if (-not $apk) { Stop-Kit "There is no APK in $Root\releases. Run Rebuild App.cmd first." }
    $info = Get-ApkInfo $apk.FullName
    Step "Install $($Cfg.AppName) on the $($Cfg.Device)"
    Say "  APK:      $($apk.FullName)"
    if ($info) { Say "  Version:  $($info.VersionName) (code $($info.VersionCode)), package $($info.Package)" }
    Say '  How:      adb install -r  (an UPDATE: your data on the device is kept; nothing is uninstalled)'
    if (@($Cfg.AppOps) -join '') { Say "  Then:     switches on $(@($Cfg.AppOps) -join ', ') for the app, like the existing tools do" }
    if ($Preview) { Say 'PREVIEW: stopping here. Nothing was sent to any device.'; return }
    if ($info -and $info.Package -ne $Pkg) { Stop-Kit "That APK is for $($info.Package), not $Pkg. Stopped." }

    Test-Device
    if (-not (Ask-Yes 'install it')) { Stop-Kit 'Stopped. Nothing was installed.' }
    $r = Adb install -r $apk.FullName
    Log ($r -join "`n")
    if (($r -join ' ') -notmatch 'Success') {
        if (($r -join ' ') -match 'UPDATE_INCOMPATIBLE|signatures do not match') {
            Stop-Kit 'The device refused it: this APK is signed with a DIFFERENT KEY than the app already on the device. Do NOT uninstall (that erases your data). Back up first and keep this log.'
        }
        if (($r -join ' ') -match 'VERSION_DOWNGRADE') {
            Stop-Kit 'The device already has a NEWER version of this app than this APK. Nothing was changed.'
        }
        Stop-Kit ('The install did not work. What adb said: ' + ($r -join ' '))
    }
    Good '  Installed.'
    Grant-Permissions
    if ($Cfg.Device -eq 'Phone') {
        Say ''
        Say 'The Light Phone launcher only shows a new or updated app after a restart.' 'Yellow'
        $a = Read-Host '  Restart the phone now? Type Y and press Enter, or just Enter to do it yourself later'
        if ($a -eq 'Y' -or $a -eq 'y') { [void](Adb reboot); Good '  The phone is restarting.' }
        else { Say '  Remember: restart the phone (hold the power button, then Restart).' 'Yellow' }
    }
}

#--- rebuild ---------------------------------------------------------------------
function Invoke-Rebuild {
    Step "Rebuild $($Cfg.AppName) from source"
    $java = if ($env:JAVA_HOME -and (Test-Path -LiteralPath "$env:JAVA_HOME\bin\java.exe")) { "$env:JAVA_HOME\bin\java.exe" } else { (Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source }
    if (-not $java) { Stop-Kit 'MISSING: Java 17. Install "Eclipse Temurin 17" from adoptium.net, then try again.' }
    Say "  Java:    $java"
    $sdk = Find-Sdk
    if (-not $sdk) { Stop-Kit 'MISSING: the Android SDK. It is normally in C:\Users\<you>\AppData\Local\Android\Sdk.' }
    Say "  SDK:     $sdk"
    if (-not (Test-Path -LiteralPath (Join-Path $Root 'gradlew.bat'))) { Stop-Kit "MISSING: gradlew.bat in $Root." }
    Say "  Builds:  gradlew.bat $($Cfg.BuildTask)  (needs internet the first time)"
    Say "  Saves:   releases\$($Cfg.AppName)-<version>-$(Get-Date -Format 'yyyy-MM-dd').apk"
    Say '  It never installs anything.'
    if ($Preview) { Say 'PREVIEW: stopping here. Nothing was built.'; return }

    Push-Location $Root
    try {
        & cmd /c "gradlew.bat $($Cfg.BuildTask) 2>&1" | ForEach-Object { Write-Host $_; Log $_ }
        $ok = ($LASTEXITCODE -eq 0)
    } finally { Pop-Location }
    if (-not $ok) { Stop-Kit 'The build FAILED. Scroll up to the first line that says "error" or "FAILED". Nothing was saved.' }
    $built = Join-Path $Root $Cfg.BuildOutput
    if (-not (Test-Path -LiteralPath $built)) { Stop-Kit "The build said OK but there is no APK at $built." }
    $info = Get-ApkInfo $built
    $ver = if ($info) { $info.VersionName } else { 'unknown' }
    $dest = Join-Path $Root ("releases\$($Cfg.AppName)-$ver-$(Get-Date -Format 'yyyy-MM-dd').apk")
    [void](New-Item -ItemType Directory -Path (Split-Path $dest -Parent) -Force)
    Copy-Item -LiteralPath $built -Destination $dest -Force
    $newKey = Get-ApkKey $dest
    @("$($Cfg.AppName) $ver (versionCode $(if ($info) { $info.VersionCode }))",
      "Built: $(Get-Date -Format 'yyyy-MM-dd HH:mm') on this PC by Rebuild App.cmd",
      "Package: $Pkg", "Signed with: $($Cfg.KeyName)", "Key fingerprint (SHA-256): $newKey") |
        Set-Content -LiteralPath ($dest -replace '\.apk$', '.txt') -Encoding UTF8
    Good "  Saved: $dest"

    # Compare the key with the app on the device (or the last saved APK if no device)
    Step 'Signing key check'
    $oldKey = $null; $where = ''
    try {
        $script:AdbExe = Find-Adb
        $ready = @(Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "`tdevice$" })
        if ($ready.Count -eq 1) { $oldKey = Get-InstalledKey; if ($oldKey) { $where = 'the app on your device' } }
    } catch { }
    if (-not $oldKey) {
        $prev = Get-ChildItem -LiteralPath (Join-Path $Root 'releases') -Filter '*.apk' | Where-Object { $_.FullName -ne $dest } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($prev) { $oldKey = Get-ApkKey $prev.FullName; $where = "the last saved APK ($($prev.Name))" }
    }
    if (-not $newKey -or -not $oldKey) { Say '  Could not compare keys (no device and no older APK, or apksigner missing).' 'Yellow' }
    elseif ($newKey -eq $oldKey) { Good "  Same key as $where. Installing it will keep your data." }
    else {
        Bad "  WARNING: the new APK is signed with a DIFFERENT KEY than $where."
        Bad '  The device will refuse it as an update. Do NOT uninstall the app to get round this (that erases your data).'
    }
}

#--- main ------------------------------------------------------------------------
Say "$($Cfg.AppName) - $Action$(if ($Preview) { ' (PREVIEW: nothing on any device is touched)' })   $(Get-Date)"
if ($Action -ne 'rebuild') { $script:AdbExe = Find-Adb; Say "  adb: $script:AdbExe" }
switch ($Action) {
    'install' { Invoke-Install }
    'backup'  {
        Step "Back up $($Cfg.AppName) data from the $($Cfg.Device)"
        Say "  Copies: the app's private storage$(if (@($Cfg.SharedDirs) -join '') { ' and ' + (@($Cfg.SharedDirs) -join ', ') })"
        Say "  To:     $BackupRoot\<date and time>"
        Say '  It only reads from the device.'
        if ($Preview) { Say 'PREVIEW: stopping here.'; break }
        Test-Device -NeedApp
        [void](Invoke-Backup)
    }
    'restore' { Invoke-Restore }
    'rebuild' { Invoke-Rebuild }
}
Say "Log: $Log"
