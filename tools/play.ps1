# Plays a story file in this console.
# Usage, from the project root:  .\tools\play.ps1 tool\src\main\assets\zork1.dat
# Add -Build after changing engine code. Add -Seed 42 to make random events repeat.
# Add -Commands file.txt to play the commands in a file, one per line, instead of typing.
param(
    [Parameter(Mandatory = $true)][string]$StoryFile,
    [switch]$Build,
    [long]$Seed = -1,
    [string]$Commands
)
$root = Split-Path $PSScriptRoot -Parent
$main = Join-Path $root 'tool\build\tmp\kotlin-classes\debug'
$test = Join-Path $root 'tool\build\tmp\kotlin-classes\debugUnitTest'
$player = Join-Path $test 'com\thelightphone\adventure\cli\PlayKt.class'
if ($Build -or -not (Test-Path $player)) {
    & (Join-Path $root 'gradlew.bat') -p $root :tool:compileDebugUnitTestKotlin --console=plain -q
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
$stdlib = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.jetbrains.kotlin\kotlin-stdlib\2.3.20" -Recurse -Filter 'kotlin-stdlib-2.3.20.jar' |
    Select-Object -First 1
if (-not $stdlib) { Write-Host 'kotlin-stdlib 2.3.20 was not found. Run a Gradle build first.'; exit 1 }
$arguments = @('-cp', "$main;$test;$($stdlib.FullName)", 'com.thelightphone.adventure.cli.PlayKt', $StoryFile)
if ($Seed -ge 0) { $arguments += @('--seed', $Seed) }
$encoding = [Console]::OutputEncoding
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
try {
    if ($Commands) { Get-Content $Commands | & java @arguments } else { & java @arguments }
} finally {
    [Console]::OutputEncoding = $encoding
}
exit $LASTEXITCODE
