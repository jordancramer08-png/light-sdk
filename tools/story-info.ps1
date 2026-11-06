# Prints version, release, serial, object count, dictionary size and object 1's name.
# Usage, from the project root:  .\tools\story-info.ps1 story-files\curses.z5
# Add -Build after changing engine code.
param(
    [Parameter(Mandatory = $true)][string]$StoryFile,
    [switch]$Build
)
$root = Split-Path $PSScriptRoot -Parent
$main = Join-Path $root 'tool\build\tmp\kotlin-classes\debug'
$test = Join-Path $root 'tool\build\tmp\kotlin-classes\debugUnitTest'
if ($Build -or -not (Test-Path $test)) {
    & (Join-Path $root 'gradlew.bat') -p $root :tool:compileDebugUnitTestKotlin --console=plain -q
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
$stdlib = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.jetbrains.kotlin\kotlin-stdlib\2.3.20" -Recurse -Filter 'kotlin-stdlib-2.3.20.jar' |
    Select-Object -First 1
if (-not $stdlib) { Write-Host 'kotlin-stdlib 2.3.20 was not found. Run a Gradle build first.'; exit 1 }
& java -cp "$main;$test;$($stdlib.FullName)" com.thelightphone.adventure.cli.StoryInfoKt $StoryFile
exit $LASTEXITCODE
