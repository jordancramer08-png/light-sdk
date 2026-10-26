<#
  Runs one of this app's existing PowerShell tools unchanged, after checking
  the programs it needs, and keeps a copy of everything it shows in logs\.
    run-logged.ps1 -Needs adb,java  "C:\...\tool.ps1"  [what was dropped on it]
#>
param(
    [string[]]$Needs = @(),
    [Parameter(Mandatory = $true, Position = 0)][string]$Script,
    [Parameter(ValueFromRemainingArguments = $true)]$Rest
)
$Root = Split-Path $PSScriptRoot -Parent
$LogDir = Join-Path $Root 'logs'
[void](New-Item -ItemType Directory -Path $LogDir -Force)
$Log = Join-Path $LogDir ([IO.Path]::GetFileNameWithoutExtension($Script) + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')

$help = @{
    adb    = 'adb (the Android phone tool). It normally lives in C:\Users\<you>\AppData\Local\Android\Sdk\platform-tools. Install "Android SDK Platform-Tools".'
    java   = 'Java 17. Install "Eclipse Temurin 17" from adoptium.net.'
    python = 'Python 3. Install it from python.org and tick "Add python.exe to PATH".'
    ffmpeg = 'ffmpeg. In a Command Prompt type:  winget install Gyan.FFmpeg'
}
$missing = @()
$Needs = @($Needs | ForEach-Object { $_ -split "," } | Where-Object { $_ })
foreach ($n in $Needs) {
    $found = Get-Command "$n.exe" -CommandType Application -ErrorAction SilentlyContinue
    if (-not $found -and $n -eq 'adb') { $found = Test-Path "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" }
    if (-not $found -and $n -eq 'java') { $found = $env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe") }
    if (-not $found) { $missing += $n }
}
if ($missing) {
    foreach ($m in $missing) { Write-Host "MISSING: $($help[$m])" -ForegroundColor Red }
    "Missing: $($missing -join ', ')" | Set-Content -LiteralPath $Log
    Write-Host "Nothing was done. Log: $Log"
    exit 1
}
Start-Transcript -LiteralPath $Log | Out-Null
try {
    # "-Name" words become switches for the tool; everything else (dropped files) stays as is.
    $named = @{}; $plain = @()
    foreach ($a in @($Rest)) { if ("$a" -match '^-(\w+)$') { $named[$Matches[1]] = $true } elseif ($null -ne $a) { $plain += $a } }
    & $Script @named @plain
    $code = $LASTEXITCODE
} finally {
    Stop-Transcript | Out-Null
    Write-Host "Log: $Log"
}
exit $code
