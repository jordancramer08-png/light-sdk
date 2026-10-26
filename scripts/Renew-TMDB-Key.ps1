#===========================================================================
#  Watch - Renew TMDB key (PC side)
#  Watch-Phone-Sync adds posters and details from TMDB using the key in
#  private\tmdb-key.txt. If TMDB cancels that key, paste a new one here.
#  It tests the key with TMDB first and only saves a key that works.
#  The old key is kept as private\tmdb-key.old-<date>.txt. The key is never
#  shown or logged.
#    -Check   only test the key you have now; change nothing
#===========================================================================
param([switch]$Check)
$RepoRoot = Split-Path $PSScriptRoot -Parent
$KeyFile  = Join-Path $RepoRoot 'private\tmdb-key.txt'

function Test-Key([string]$k) {
    $wc = New-Object Net.WebClient
    $url = 'https://api.themoviedb.org/3/configuration'
    # Same rule as WatchCommon.ps1: a long key is a "read access token", a short one an "API key".
    if ($k.Length -gt 40) { $wc.Headers['Authorization'] = "Bearer $k" } else { $url += "?api_key=$k" }
    try { [void]$wc.DownloadString($url); return 'ok' }
    catch {
        if ("$_" -match '401') { return 'refused' }
        return 'offline'
    }
}

Write-Host ''
Write-Host '  WATCH - RENEW TMDB KEY' -ForegroundColor Cyan
if (Test-Path -LiteralPath $KeyFile) {
    $now = ([IO.File]::ReadAllText($KeyFile)).Trim()
    $r = Test-Key $now
    if ($r -eq 'ok') { Write-Host '  Your key now: WORKS.' -ForegroundColor Green }
    elseif ($r -eq 'refused') { Write-Host '  Your key now: REFUSED by TMDB. Paste a new one below.' -ForegroundColor Red }
    else { Write-Host '  Could not reach TMDB. Check the internet connection, then try again.' -ForegroundColor Yellow }
} else {
    Write-Host '  There is no key yet (private\tmdb-key.txt).' -ForegroundColor Yellow
}
if ($Check) { Write-Host '  Check only: nothing was changed.'; exit 0 }

Write-Host ''
Write-Host '  Paste the new key and press Enter (right-click pastes). It is not shown.'
Write-Host '  Or just press Enter to stop without changing anything.'
$sec = Read-Host '  New key' -AsSecureString
$new = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)).Trim()
if (-not $new) { Write-Host '  Stopped. Nothing was changed.'; exit 0 }

$r = Test-Key $new
if ($r -ne 'ok') {
    if ($r -eq 'refused') { Write-Host '  TMDB refused that key. Nothing was changed. Check you copied all of it.' -ForegroundColor Red }
    else { Write-Host '  Could not reach TMDB to test it. Nothing was changed. Try again when online.' -ForegroundColor Yellow }
    exit 1
}
[void](New-Item -ItemType Directory -Path (Split-Path $KeyFile -Parent) -Force)
if (Test-Path -LiteralPath $KeyFile) {
    Copy-Item -LiteralPath $KeyFile -Destination (Join-Path (Split-Path $KeyFile -Parent) ("tmdb-key.old-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.txt'))
}
[IO.File]::WriteAllText($KeyFile, $new)
Write-Host '  Saved. TMDB accepted the new key. Watch-Phone-Sync will use it next time.' -ForegroundColor Green
Write-Host '  Also copy private\tmdb-key.txt to Documents\HANDOFF\keys (and your USB stick).'
exit 0
