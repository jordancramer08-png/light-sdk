@echo off
setlocal EnableExtensions
title Listen - Podcasts
set "ABS_SELF=%~f0"
where powershell >nul 2>nul
if errorlevel 1 (
  echo Windows PowerShell was not found on this PC.
  pause
  exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "$t=[IO.File]::ReadAllText($env:ABS_SELF); $m=[char]35+'BEGIN-POWERSHELL'+[char]35; $i=$t.IndexOf($m); if($i -lt 0){Write-Host 'Script body not found.' -ForegroundColor Red; exit 1}; Invoke-Expression $t.Substring($i)"
if errorlevel 1 (
  echo.
  echo The script ended with an error.
  pause
)
endlocal
exit /b
#BEGIN-POWERSHELL#
#===========================================================================
#  Listen - Podcasts (PC side)
#  Find and follow podcasts on the laptop, and keep the follow list in step
#  with the Listen app on the Light Phone III.
#
#  The PC keeps its own copy of the follow list:
#    D:\Music\_Listen Podcasts\podcasts.json      (same format as the phone's)
#  On the phone, Listen keeps:
#    /sdcard/Listen/.state/podcasts.json          follow list
#    /sdcard/Listen/.state/podcast_episodes.json  positions, played marks, downloads
#    /sdcard/Listen/.state/settings.json          settings (incl. podcast speed)
#  A sync never writes the phone's podcasts.json. It drops the merged list in
#    /sdcard/Listen/.state/podcasts_from_pc.json
#  and Listen folds it in the next time it opens (or comes back to the front).
#
#  Merging (the same rule as the app): shows match by id or by any address
#  they've had; for each, the later of "followed" and "unfollowed" wins.
#  Nothing is ever deleted outright, so no show followed on either side is
#  lost, and an unfollow can't be undone by an older copy.
#===========================================================================

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}
$OutputEncoding = [Text.Encoding]::UTF8
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}
try { [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]'Tls13' } catch {}

#---------------------------------------------------------------------------
# region  SETTINGS  (edit these if your folders move)
#---------------------------------------------------------------------------
$MusicRoot  = 'D:\Music'
$PcRoot     = Join-Path $MusicRoot '_Listen Podcasts'
$BackupRoot = Join-Path $MusicRoot '_Listen Backups'
$PhoneState = '/sdcard/Listen/.state'
$AppPackage = 'com.thelightphone.listen'
$AdbPath    = ''          # leave blank to find adb.exe automatically
$UserAgent  = 'Listen/1.0 (Light Phone III podcast player; PC sync)'

# Test hook (ignored in normal use)
if ($env:LISTEN_PODCASTS_ROOT) { $PcRoot = $env:LISTEN_PODCASTS_ROOT; $BackupRoot = Join-Path $PcRoot '_Backups' }
if ($env:LISTEN_ADB) { $AdbPath = $env:LISTEN_ADB }

$PcList   = Join-Path $PcRoot 'podcasts.json'
$TempDir  = Join-Path ([IO.Path]::GetTempPath()) 'ListenPodcasts'
$script:Adb = $null
$script:Serial = $null
#endregion

#---------------------------------------------------------------------------
# region  SMALL HELPERS  (the same as Listen-Phone-Sync.cmd)
#---------------------------------------------------------------------------
function Write-Title($text) {
    Write-Host ''
    Write-Host ('=' * 70) -ForegroundColor DarkCyan
    Write-Host ("  " + $text) -ForegroundColor Cyan
    Write-Host ('=' * 70) -ForegroundColor DarkCyan
}
function Write-Note($text)  { Write-Host $text -ForegroundColor DarkGray }
function Write-Good($text)  { Write-Host $text -ForegroundColor Green }
function Write-Warn2($text) { Write-Host $text -ForegroundColor Yellow }
function Write-Bad($text)   { Write-Host $text -ForegroundColor Red }

function Read-YesNo($prompt) {
    $a = Read-Host "$prompt (Y/N)"
    return ($a -match '^\s*y')
}

function Pause-Here { [void](Read-Host 'Press Enter to continue') }

# Single-quote a string for the phone's shell
function Q([string]$s) { return "'" + $s.Replace("'", "'\''") + "'" }

# Parse "1,3,5-8" into a list of ints; returns $null if not a number list
function Parse-Numbers([string]$text, [int]$max) {
    if ($text -notmatch '^[\d\s,\-]+$') { return $null }
    $out = New-Object System.Collections.Generic.List[int]
    foreach ($chunk in ($text -split ',')) {
        $c = $chunk.Trim()
        if ($c -eq '') { continue }
        if ($c -match '^(\d+)\s*-\s*(\d+)$') {
            $a = [int]$Matches[1]; $b = [int]$Matches[2]
            if ($a -gt $b) { $t = $a; $a = $b; $b = $t }
            for ($i = $a; $i -le $b; $i++) { if ($i -ge 1 -and $i -le $max -and -not $out.Contains($i)) { $out.Add($i) } }
        } elseif ($c -match '^\d+$') {
            $i = [int]$c
            if ($i -ge 1 -and $i -le $max -and -not $out.Contains($i)) { $out.Add($i) }
        } else { return $null }
    }
    return ,$out
}

# A numbered list to pick from: numbers, ranges, A = all shown, a word filters, Enter = back.
function Select-FromList($items, [scriptblock]$lineFn, [string]$heading) {
    $filter = ''
    while ($true) {
        $shown = @($items)
        if ($filter) { $shown = @($items | Where-Object { $_.Label -like "*$filter*" }) }
        Write-Title $heading
        if ($filter) { Write-Note "  Filter: '$filter'   (type C to clear)" }
        if ($shown.Count -eq 0) { Write-Warn2 '  Nothing matches.' }
        for ($i = 0; $i -lt $shown.Count; $i++) {
            $txt = & $lineFn $shown[$i]
            Write-Host ('{0,4}  ' -f ($i + 1)) -NoNewline -ForegroundColor DarkCyan
            $c = 'Gray'
            if ($txt -match '\[FOLLOWING\]') { $c = 'Green' }
            Write-Host $txt -ForegroundColor $c
        }
        Write-Host ''
        Write-Note '  Numbers: 3   or 1,4,7   or 10-15      Words: filter the list'
        Write-Note '  A = all shown     C = clear filter     Enter = back'
        $ans = (Read-Host '  Choose').Trim()
        if ($ans -eq '') { return @() }
        if ($ans -ieq 'C') { $filter = ''; continue }
        if ($ans -ieq 'A') { return $shown }
        $nums = Parse-Numbers $ans $shown.Count
        if ($null -ne $nums) {
            if ($nums.Count -eq 0) { Write-Warn2 '  Those numbers are not on the list.'; continue }
            return @($nums | ForEach-Object { $shown[$_ - 1] })
        }
        $filter = $ans
    }
}

function Now-Ms { return [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }

function Write-Utf8([string]$path, [string]$text) {
    $dir = Split-Path -Parent $path
    if ($dir -and -not (Test-Path -LiteralPath $dir)) { [void](New-Item -ItemType Directory -Path $dir -Force) }
    [IO.File]::WriteAllText($path, $text, (New-Object Text.UTF8Encoding($false)))
}
#endregion

#---------------------------------------------------------------------------
# region  ADB  (the same route as Listen-Phone-Sync.cmd)
#---------------------------------------------------------------------------
function Find-Adb {
    if ($AdbPath -and (Test-Path -LiteralPath $AdbPath)) { return $AdbPath }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $cands = New-Object System.Collections.Generic.List[string]
    foreach ($v in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) { if ($v) { $cands.Add((Join-Path $v 'platform-tools\adb.exe')) } }
    if ($env:LOCALAPPDATA) { $cands.Add((Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')) }
    if ($env:USERPROFILE) {
        $cands.Add((Join-Path $env:USERPROFILE 'platform-tools\adb.exe'))
        $cands.Add((Join-Path $env:USERPROFILE 'Downloads\platform-tools\adb.exe'))
    }
    $cands.Add('C:\platform-tools\adb.exe')
    $cands.Add('C:\Android\platform-tools\adb.exe')
    foreach ($c in $cands) { if (Test-Path -LiteralPath $c) { return $c } }
    return $null
}

# Run adb and return @{ Code; Out (string[]) } without throwing on stderr
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

# Quietly: is a phone there and allowed? Sets $script:Serial.
function Connect-Phone([switch]$Quiet) {
    $script:Serial = $null
    if (-not $script:Adb) { return $false }
    $r = Invoke-Adb devices
    $ready = @(); $unauth = @()
    foreach ($line in $r.Out) {
        if ($line -match '^(\S+)\s+device$') { $ready += $Matches[1] }
        elseif ($line -match '^(\S+)\s+(unauthorized|offline)') { $unauth += $Matches[1] }
    }
    if ($ready.Count -eq 0) {
        if (-not $Quiet) {
            if ($unauth.Count -gt 0) {
                Write-Warn2 '  The phone is connected but has not allowed this PC yet.'
                Write-Warn2 '  Unlock the phone and tap "Allow" on the USB debugging prompt, then try again.'
            } else {
                Write-Warn2 '  No phone found. Plug the Light Phone in with a data USB cable (USB debugging on).'
            }
        }
        return $false
    }
    $script:Serial = $ready[0]
    return $true
}

# Reads a file from the phone's .state folder; $null when it isn't there.
function Read-PhoneFile([string]$name) {
    if (-not (Test-Path -LiteralPath $TempDir)) { [void](New-Item -ItemType Directory -Path $TempDir) }
    $local = Join-Path $TempDir ('phone-' + $name)
    if (Test-Path -LiteralPath $local) { Remove-Item -LiteralPath $local -Force }
    $exists = Invoke-Phone ("test -f " + (Q "$PhoneState/$name") + " && echo yes")
    if (-not ($exists.Out -contains 'yes')) { return $null }
    $r = Invoke-Adb pull "$PhoneState/$name" $local
    if ($r.Code -ne 0 -or -not (Test-Path -LiteralPath $local)) { throw ("Couldn't read $name from the phone: " + ($r.Out -join ' ')) }
    return [IO.File]::ReadAllText($local, [Text.Encoding]::UTF8)
}

# Writes a file into the phone's .state folder safely: to name.tmp, then renamed over name.
function Write-PhoneFile([string]$name, [string]$text) {
    if (-not (Test-Path -LiteralPath $TempDir)) { [void](New-Item -ItemType Directory -Path $TempDir) }
    $local = Join-Path $TempDir ('push-' + $name)
    Write-Utf8 $local $text
    [void](Invoke-Phone ("mkdir -p " + (Q $PhoneState)))
    $r = Invoke-Adb push $local "$PhoneState/$name.tmp"
    if ($r.Code -ne 0) { throw ("Couldn't copy $name to the phone: " + ($r.Out -join ' ')) }
    $m = Invoke-Phone ("mv -f " + (Q "$PhoneState/$name.tmp") + " " + (Q "$PhoneState/$name"))
    if ($m.Code -ne 0) { throw ("Couldn't save $name on the phone: " + ($m.Out -join ' ')) }
}
#endregion

#---------------------------------------------------------------------------
# region  FOLLOW LIST  (the same format and rules as the app)
#---------------------------------------------------------------------------

# The feed address in one canonical form, for matching (PodcastIds.normalizeFeedUrl in the app).
# Note: PowerShell compares text ignoring case unless told otherwise (-ceq, -ccontains); paths
# in addresses are case-sensitive in the app, so address comparisons here use the c- forms.
function Normalize-FeedUrl([string]$url) {
    $u = $url.Trim()
    $hash = $u.IndexOf('#'); if ($hash -ge 0) { $u = $u.Substring(0, $hash) }
    $schemeEnd = $u.IndexOf('://')
    if ($schemeEnd -lt 0) { return $u.TrimEnd('/') }
    $scheme = $u.Substring(0, $schemeEnd).ToLowerInvariant()
    if ($scheme -eq 'http' -or $scheme -eq 'feed') { $scheme = 'https' }
    $rest = $u.Substring($schemeEnd + 3)
    $hostEnd = $rest.IndexOfAny([char[]]@('/', '?'))
    if ($hostEnd -lt 0) { $hostEnd = $rest.Length }
    $hostPart = $rest.Substring(0, $hostEnd).ToLowerInvariant()
    if ($hostPart.EndsWith(':443')) { $hostPart = $hostPart.Substring(0, $hostPart.Length - 4) }
    elseif ($hostPart.EndsWith(':80')) { $hostPart = $hostPart.Substring(0, $hostPart.Length - 3) }
    $path = $rest.Substring($hostEnd).TrimEnd('/')
    return "${scheme}://$hostPart$path"
}

# First 12 hex characters of SHA-1 of the normalized address (the app's show id).
function Get-ShowId([string]$feedUrl) {
    $sha = [Security.Cryptography.SHA1]::Create()
    try {
        $bytes = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes((Normalize-FeedUrl $feedUrl)))
    } finally { $sha.Dispose() }
    return (($bytes | ForEach-Object { $_.ToString('x2') }) -join '').Substring(0, 12)
}

function Get-Prop($o, [string]$name, $default) {
    if ($null -eq $o) { return $default }
    $p = $o.PSObject.Properties[$name]
    if ($null -eq $p -or $null -eq $p.Value) { return $default }
    return $p.Value
}

# One show in the app's format (every field, so the app reads it as-is).
function New-Sub($showId, $feedUrl, $prev, $title, $author, $artUrl, $followedAt, $unfollowedAt, $sort, $updatedAt) {
    return [pscustomobject][ordered]@{
        showId           = [string]$showId
        feedUrl          = [string]$feedUrl
        previousFeedUrls = @($prev | Where-Object { $_ })
        title            = [string]$title
        author           = [string]$author
        artUrl           = $artUrl
        followedAt       = [long]$followedAt
        unfollowedAt     = $unfollowedAt
        sort             = [string]$sort
        updatedAt        = [long]$updatedAt
    }
}

# A show read from JSON, tidied into the app's format (missing fields get the app's defaults).
function ConvertTo-Sub($o) {
    $sort = [string](Get-Prop $o 'sort' 'NEWEST'); if ($sort -ne 'OLDEST') { $sort = 'NEWEST' }
    $unf = Get-Prop $o 'unfollowedAt' $null
    if ($null -ne $unf) { $unf = [long]$unf }
    return New-Sub (Get-Prop $o 'showId' '') (Get-Prop $o 'feedUrl' '') @(Get-Prop $o 'previousFeedUrls' @()) `
        (Get-Prop $o 'title' '') (Get-Prop $o 'author' '') (Get-Prop $o 'artUrl' $null) `
        (Get-Prop $o 'followedAt' 0) $unf $sort (Get-Prop $o 'updatedAt' 0)
}

function Test-Following($s) { return ($null -eq $s.unfollowedAt) -or ($s.followedAt -gt $s.unfollowedAt) }

function Get-KnownUrls($s) {
    return @(@($s.previousFeedUrls) + @($s.feedUrl) | Where-Object { $_ } | ForEach-Object { Normalize-FeedUrl $_ } | Select-Object -Unique)
}

# Reads a podcasts.json text into a list of shows; an empty list for nothing; $null when it isn't readable.
function Read-SubsText([string]$text) {
    if ([string]::IsNullOrWhiteSpace($text)) { return ,@() }
    try { $data = $text.TrimStart([char]0xFEFF) | ConvertFrom-Json } catch { return $null }
    $shows = @(Get-Prop $data 'shows' @())
    return ,@($shows | ForEach-Object { ConvertTo-Sub $_ })
}

function ConvertTo-SubsText($subs) {
    $data = [pscustomobject][ordered]@{ version = 1; shows = @($subs) }
    return ($data | ConvertTo-Json -Depth 6)
}

function Load-PcList {
    if (-not (Test-Path -LiteralPath $PcList)) { return ,@() }
    $list = Read-SubsText ([IO.File]::ReadAllText($PcList, [Text.Encoding]::UTF8))
    if ($null -eq $list) {
        $aside = $PcList + '.broken-' + (Get-Date).ToString('yyyyMMdd-HHmmss')
        Move-Item -LiteralPath $PcList -Destination $aside
        Write-Warn2 "  The PC's podcast list couldn't be read. It was set aside as $aside"
        return ,@()
    }
    return ,$list
}

function Save-PcList($subs) {
    $tmp = $PcList + '.tmp'
    Write-Utf8 $tmp (ConvertTo-SubsText $subs)
    Move-Item -LiteralPath $tmp -Destination $PcList -Force
}

# The app's mergeSubscriptions: nothing is dropped; later of followed/unfollowed wins.
function Merge-Subs($mine, $theirs) {
    $result = New-Object System.Collections.ArrayList
    foreach ($m in @($mine)) { [void]$result.Add($m) }
    foreach ($o in @($theirs)) {
        $oUrls = Get-KnownUrls $o
        $i = -1
        for ($k = 0; $k -lt $result.Count; $k++) {
            $a = $result[$k]
            if ($a.showId -eq $o.showId) { $i = $k; break }
            foreach ($u in (Get-KnownUrls $a)) { if ($oUrls -ccontains $u) { $i = $k; break } }
            if ($i -ge 0) { break }
        }
        if ($i -lt 0) { [void]$result.Add($o); continue }
        $a = $result[$i]
        $theirsNewer = $o.updatedAt -gt $a.updatedAt
        $newer = if ($theirsNewer) { $o } else { $a }
        $followedAt = [Math]::Max([long]$a.followedAt, [long]$o.followedAt)
        $unfollowedAt = $null
        foreach ($x in @($a.unfollowedAt, $o.unfollowedAt)) {
            if ($null -ne $x -and ($null -eq $unfollowedAt -or [long]$x -gt $unfollowedAt)) { $unfollowedAt = [long]$x }
        }
        if ($null -ne $unfollowedAt -and $unfollowedAt -lt $followedAt) { $unfollowedAt = $null }
        $newerNorm = Normalize-FeedUrl $newer.feedUrl
        $seen = New-Object System.Collections.Hashtable; $urls = @()   # case-sensitive, like the app
        foreach ($u in (@($a.previousFeedUrls) + @($o.previousFeedUrls) + @($a.feedUrl, $o.feedUrl))) {
            if (-not $u) { continue }
            $n = Normalize-FeedUrl $u
            if ($seen.ContainsKey($n) -or $n -ceq $newerNorm) { continue }
            $seen[$n] = $true; $urls += $u
        }
        $sort = if ($theirsNewer) { $o.sort } else { $a.sort }
        $result[$i] = New-Sub $a.showId $newer.feedUrl $urls $newer.title $newer.author $newer.artUrl `
            $followedAt $unfollowedAt $sort ([Math]::Max([long]$a.updatedAt, [long]$o.updatedAt))
    }
    return ,@($result)
}

function Find-Sub($subs, [string]$feedUrl) {
    $n = Normalize-FeedUrl $feedUrl
    foreach ($s in @($subs)) { if ((Get-KnownUrls $s) -ccontains $n) { return $s } }
    return $null
}

# Follows a show on the PC list (again, if it was unfollowed). Returns the updated list.
function Add-Follow($subs, [string]$feedUrl, [string]$title, [string]$author, $artUrl) {
    $now = Now-Ms
    $existing = Find-Sub $subs $feedUrl
    if ($null -eq $existing) {
        $new = New-Sub (Get-ShowId $feedUrl) $feedUrl @() $title $author $artUrl $now $null 'NEWEST' $now
        return ,@(@($subs) + $new)
    }
    if (Test-Following $existing) { return ,@($subs) }
    $again = New-Sub $existing.showId $existing.feedUrl $existing.previousFeedUrls `
        $(if ($title) { $title } else { $existing.title }) $(if ($author) { $author } else { $existing.author }) `
        $(if ($artUrl) { $artUrl } else { $existing.artUrl }) $now $null $existing.sort $now
    return ,@(@($subs) | ForEach-Object { if ($_.showId -eq $existing.showId) { $again } else { $_ } })
}

function Remove-Follow($subs, [string]$showId) {
    $now = Now-Ms
    return ,@(@($subs) | ForEach-Object {
        if ($_.showId -eq $showId -and (Test-Following $_)) {
            New-Sub $_.showId $_.feedUrl $_.previousFeedUrls $_.title $_.author $_.artUrl $_.followedAt $now $_.sort $now
        } else { $_ }
    })
}

function Show-Name($s) { if ($s.title) { return $s.title } return $s.feedUrl }
#endregion

#---------------------------------------------------------------------------
# region  THE WEB  (Apple search, feeds, OPML)
#---------------------------------------------------------------------------

# Apple's podcast directory (no key). Results that have no feed can't be followed and are left out.
function Search-Apple([string]$term) {
    $url = 'https://itunes.apple.com/search?media=podcast&entity=podcast&limit=25&term=' + [uri]::EscapeDataString($term)
    $r = Invoke-RestMethod -Uri $url -UserAgent $UserAgent -TimeoutSec 30
    $seen = @{}
    $out = @()
    foreach ($x in @($r.results)) {
        $feed = Get-Prop $x 'feedUrl' $null
        if (-not $feed -or $seen.ContainsKey($feed)) { continue }
        $seen[$feed] = $true
        $title = Get-Prop $x 'collectionName' (Get-Prop $x 'trackName' '')
        $out += [pscustomobject]@{
            Label = $title; Title = $title; Author = (Get-Prop $x 'artistName' ''); FeedUrl = $feed
            ArtUrl = (Get-Prop $x 'artworkUrl600' (Get-Prop $x 'artworkUrl100' $null)); Count = (Get-Prop $x 'trackCount' $null)
        }
    }
    return ,$out
}

function Decode-Text([string]$s) {
    if ($null -eq $s) { return '' }
    $s = $s -replace '^\s*<!\[CDATA\[', '' -replace '\]\]>\s*$', ''
    $s = $s -replace '<[^>]+>', ''
    return ([Net.WebUtility]::HtmlDecode([Net.WebUtility]::HtmlDecode($s))).Trim()
}

# Reads a feed's own title, author and art (and checks it really is a podcast feed).
# Plain http feeds are fine here (the phone can't fetch those, the PC can).
function Get-FeedInfo([string]$feedUrl) {
    $resp = Invoke-WebRequest -Uri $feedUrl -UseBasicParsing -UserAgent $UserAgent -TimeoutSec 60 -MaximumRedirection 10
    $text = [string]$resp.Content
    $item = $text.IndexOf('<item')
    $head = if ($item -gt 0) { $text.Substring(0, $item) } else { $text.Substring(0, [Math]::Min($text.Length, 200000)) }
    if ($head -notmatch '<rss|<channel|<rdf:RDF') { throw "That address isn't a podcast feed." }
    $title = ''; $author = ''; $art = $null
    $m = [regex]::Match($head, '<title>([\s\S]*?)</title>'); if ($m.Success) { $title = Decode-Text $m.Groups[1].Value }
    $m = [regex]::Match($head, '<itunes:author>([\s\S]*?)</itunes:author>'); if ($m.Success) { $author = Decode-Text $m.Groups[1].Value }
    $m = [regex]::Match($head, '<itunes:image[^>]*href\s*=\s*["'']([^"'']+)["'']'); if ($m.Success) { $art = $m.Groups[1].Value }
    if (-not $art) { $m = [regex]::Match($head, '<image>[\s\S]*?<url>([\s\S]*?)</url>'); if ($m.Success) { $art = Decode-Text $m.Groups[1].Value } }
    return [pscustomobject]@{ Title = $title; Author = $author; ArtUrl = $art }
}

# The shows in an OPML file: outlines that have a feed address. DTDs are ignored (no tricks).
function Read-Opml([string]$path) {
    $settings = New-Object Xml.XmlReaderSettings
    $settings.DtdProcessing = [Xml.DtdProcessing]::Ignore
    $settings.XmlResolver = $null
    $reader = [Xml.XmlReader]::Create($path, $settings)
    try {
        $doc = New-Object Xml.XmlDocument
        $doc.XmlResolver = $null
        $doc.Load($reader)
    } finally { $reader.Dispose() }
    $out = @()
    $seen = New-Object System.Collections.Hashtable
    foreach ($node in $doc.SelectNodes('//outline[@xmlUrl]')) {
        $url = $node.GetAttribute('xmlUrl').Trim()
        if (-not $url -or $seen.ContainsKey((Normalize-FeedUrl $url))) { continue }
        $seen[(Normalize-FeedUrl $url)] = $true
        $title = $node.GetAttribute('title'); if (-not $title) { $title = $node.GetAttribute('text') }
        if (-not $title) { $title = $url }
        $out += [pscustomobject]@{ Label = $title; Title = $title; FeedUrl = $url }
    }
    return ,$out
}
#endregion

#---------------------------------------------------------------------------
# region  PHONE LIST
#---------------------------------------------------------------------------

# The phone's list, with any earlier sync Listen hasn't picked up yet folded in.
function Read-PhoneSubs {
    $phone = Read-SubsText (Read-PhoneFile 'podcasts.json')
    if ($null -eq $phone) { throw "The phone's podcast list couldn't be read." }
    $inbox = Read-SubsText (Read-PhoneFile 'podcasts_from_pc.json')
    if ($null -ne $inbox -and @($inbox).Count -gt 0) { $phone = Merge-Subs $phone $inbox }
    return ,@($phone)
}

# When the phone is plugged in: brings shows followed on the phone into the PC list.
function Update-FromPhone([switch]$Quiet) {
    if (-not (Connect-Phone -Quiet)) { return }
    try {
        $pc = Load-PcList
        $before = @($pc | Where-Object { Test-Following $_ } | ForEach-Object { $_.showId })
        $merged = Merge-Subs $pc (Read-PhoneSubs)
        Save-PcList $merged
        $new = @($merged | Where-Object { (Test-Following $_) -and ($before -notcontains $_.showId) })
        if (-not $Quiet -and $new.Count -gt 0) {
            Write-Good ("  Read the phone's list: {0} show(s) followed on the phone added here." -f $new.Count)
        }
    } catch {
        Write-Warn2 ("  Couldn't read the phone's podcast list: " + $_.Exception.Message)
    }
}
#endregion

#---------------------------------------------------------------------------
# region  MENUS
#---------------------------------------------------------------------------
function Follow-Picked($picks) {
    $pc = Load-PcList
    $added = 0
    foreach ($p in @($picks)) {
        $before = Find-Sub $pc $p.FeedUrl
        if ($null -ne $before -and (Test-Following $before)) { Write-Note ("  Already following: " + $p.Title); continue }
        $pc = Add-Follow $pc $p.FeedUrl $p.Title (Get-Prop $p 'Author' '') (Get-Prop $p 'ArtUrl' $null)
        Write-Good ("  Following: " + $p.Title)
        $added++
    }
    Save-PcList $pc
    if ($added -gt 0) { Write-Note '  Choose 5 (Sync with phone) to send the changes to Listen.' }
}

function Menu-Search {
    while ($true) {
        Write-Title 'SEARCH APPLE PODCASTS'
        $term = (Read-Host '  Show name (Enter = back)').Trim()
        if (-not $term) { return }
        try { $results = Search-Apple $term } catch { Write-Bad ("  Search failed: " + $_.Exception.Message); continue }
        if (@($results).Count -eq 0) { Write-Warn2 '  Nothing found.'; continue }
        $pc = Load-PcList
        $picks = Select-FromList $results {
            param($r)
            $tag = ''; $s = Find-Sub $pc $r.FeedUrl; if ($null -ne $s -and (Test-Following $s)) { $tag = '  [FOLLOWING]' }
            $count = ''; if ($r.Count) { $count = "  ($($r.Count) episodes)" }
            "$($r.Title)  -  $($r.Author)$count$tag"
        } "RESULTS FOR '$term' (pick the ones to follow)"
        if (@($picks).Count -gt 0) { Follow-Picked $picks; Pause-Here }
    }
}

function Menu-Opml {
    Write-Title 'IMPORT AN OPML FILE'
    Write-Note '  Most podcast apps can export your shows as an .opml file. Drag the file into this'
    Write-Note '  window (or type its full path) and press Enter.'
    $path = (Read-Host '  OPML file (Enter = back)').Trim().Trim('"')
    if (-not $path) { return }
    if (-not (Test-Path -LiteralPath $path)) { Write-Bad '  That file was not found.'; Pause-Here; return }
    try { $shows = Read-Opml $path } catch { Write-Bad ("  That file couldn't be read as OPML: " + $_.Exception.Message); Pause-Here; return }
    if (@($shows).Count -eq 0) { Write-Warn2 '  No podcast feeds in that file.'; Pause-Here; return }
    $pc = Load-PcList
    $picks = Select-FromList $shows {
        param($r)
        $tag = ''; $s = Find-Sub $pc $r.FeedUrl; if ($null -ne $s -and (Test-Following $s)) { $tag = '  [FOLLOWING]' }
        "$($r.Title)$tag"
    } "SHOWS IN THE FILE (A = follow all)"
    if (@($picks).Count -gt 0) { Follow-Picked $picks; Pause-Here }
}

function Menu-AddUrl {
    Write-Title 'ADD A FEED ADDRESS'
    Write-Note '  For private or Patreon feeds: paste the full address (right-click to paste).'
    $url = (Read-Host '  Feed address (Enter = back)').Trim().Trim('"')
    if (-not $url) { return }
    if ($url -notmatch '^[a-zA-Z]+://') { $url = 'https://' + $url }
    if ($url -match '^(feed|itpc|pcast|podcast)://') { $url = 'https://' + $url.Substring($url.IndexOf('://') + 3) }
    Write-Note '  Checking the feed...'
    try { $info = Get-FeedInfo $url } catch { Write-Bad ("  Couldn't use that address: " + $_.Exception.Message); Pause-Here; return }
    $name = $info.Title; if (-not $name) { $name = $url }
    Write-Host ("  Found: {0}  -  {1}" -f $name, $info.Author)
    if ($url -match '^http://') {
        Write-Warn2 '  This feed uses plain http. Listen on the phone can only read secure (https) feeds,'
        Write-Warn2 '  so if the show has no https address it may say "needs a secure link" there.'
    }
    if (Read-YesNo '  Follow it?') {
        Follow-Picked @([pscustomobject]@{ Title = $name; Author = $info.Author; FeedUrl = $url; ArtUrl = $info.ArtUrl })
    }
    Pause-Here
}

function Menu-List {
    Update-FromPhone
    $pc = Load-PcList
    $following = @($pc | Where-Object { Test-Following $_ } | Sort-Object { (Show-Name $_).ToLowerInvariant() -replace '^(the|a|an)\s+', '' } |
        ForEach-Object { [pscustomobject]@{ Label = (Show-Name $_); Sub = $_ } })
    if ($following.Count -eq 0) { Write-Warn2 '  No shows followed yet.'; Pause-Here; return }
    $picks = Select-FromList $following { param($r) "$($r.Label)  -  $($r.Sub.author)" } ("FOLLOWED SHOWS ({0})  -  pick any to UNFOLLOW" -f $following.Count)
    if (@($picks).Count -eq 0) { return }
    Write-Host ''
    foreach ($p in $picks) { Write-Host ("  - " + $p.Label) }
    if (-not (Read-YesNo ("  Unfollow these {0} show(s)?" -f @($picks).Count))) { return }
    foreach ($p in $picks) { $pc = Remove-Follow $pc $p.Sub.showId }
    Save-PcList $pc
    Write-Good '  Unfollowed. Choose 5 (Sync with phone) to send it to Listen.'
    Write-Note '  (Their downloaded episodes stay on the phone; remove them in Listen > Podcasts > Downloads.)'
    Pause-Here
}

function Menu-Sync {
    Write-Title 'SYNC WITH THE PHONE'
    if (-not (Connect-Phone)) { Pause-Here; return }
    try {
        Write-Note "  Reading the phone's list first..."
        $phone = Read-PhoneSubs
        $pc = Load-PcList
        $merged = Merge-Subs $phone $pc
        $phoneOn = @($phone | Where-Object { Test-Following $_ } | ForEach-Object { $_.showId })
        $pcOn = @($pc | Where-Object { Test-Following $_ } | ForEach-Object { $_.showId })
        $mergedOn = @($merged | Where-Object { Test-Following $_ })
        $toFollow = @($mergedOn | Where-Object { $phoneOn -notcontains $_.showId })
        $toUnfollow = @($merged | Where-Object { -not (Test-Following $_) -and ($phoneOn -contains $_.showId) })
        $fromPhone = @($mergedOn | Where-Object { $pcOn -notcontains $_.showId })

        Write-Host ''
        Write-Host ("  Followed after the sync: {0} show(s)" -f $mergedOn.Count) -ForegroundColor Cyan
        if ($fromPhone.Count -gt 0) {
            Write-Host '  New on this PC (followed on the phone):' -ForegroundColor Cyan
            foreach ($s in $fromPhone) { Write-Host ("    + " + (Show-Name $s)) -ForegroundColor Green }
        }
        if ($toFollow.Count -gt 0) {
            Write-Host '  Will be FOLLOWED on the phone:' -ForegroundColor Cyan
            foreach ($s in $toFollow) { Write-Host ("    + " + (Show-Name $s)) -ForegroundColor Green }
        }
        if ($toUnfollow.Count -gt 0) {
            Write-Host '  Will be UNFOLLOWED on the phone:' -ForegroundColor Cyan
            foreach ($s in $toUnfollow) { Write-Host ("    - " + (Show-Name $s)) -ForegroundColor Yellow }
        }
        Save-PcList $merged
        if ($toFollow.Count -eq 0 -and $toUnfollow.Count -eq 0) {
            Write-Good '  The phone already has this list. Nothing to send.'
            Pause-Here; return
        }
        Write-Host ''
        if (-not (Read-YesNo '  Send these changes to Listen?')) { return }
        Write-PhoneFile 'podcasts_from_pc.json' (ConvertTo-SubsText $merged)
        Write-Good '  Sent. Open Listen (or go back to it): it picks the changes up by itself and'
        Write-Good '  fetches the episodes of newly followed shows.'
    } catch {
        Write-Bad ("  Sync failed: " + $_.Exception.Message)
    }
    Pause-Here
}

function Menu-Backup {
    while ($true) {
        Write-Title 'BACK UP / RESTORE PODCAST DATA'
        Write-Note '  Follows, listening positions, played marks and podcast settings (not the episodes themselves).'
        Write-Host '   B  Back up from the phone to this PC'
        Write-Host '   R  Restore a backup to the phone'
        $c = (Read-Host '  Choose (Enter = back)').Trim()
        if ($c -eq '') { return }
        if ($c -ieq 'B') { Backup-Podcasts }
        elseif ($c -ieq 'R') { Restore-Podcasts }
    }
}

function Backup-Podcasts {
    if (-not (Connect-Phone)) { Pause-Here; return }
    $dest = Join-Path $BackupRoot ('Podcasts ' + (Get-Date).ToString('yyyy-MM-dd_HHmm'))
    [void](New-Item -ItemType Directory -Path $dest -Force)
    $saved = 0
    foreach ($name in @('podcasts.json', 'podcast_episodes.json', 'settings.json')) {
        $text = Read-PhoneFile $name
        if ($null -ne $text) { Write-Utf8 (Join-Path $dest $name) $text; $saved++ }
    }
    if (Test-Path -LiteralPath $PcList) { Copy-Item -LiteralPath $PcList -Destination (Join-Path $dest 'pc-podcasts.json') }
    if ($saved -eq 0) { Write-Warn2 '  Listen has no podcast data on the phone yet.' } else { Write-Good "  Saved to $dest" }
    Pause-Here
}

function Restore-Podcasts {
    if (-not (Test-Path -LiteralPath $BackupRoot)) { Write-Warn2 '  No backups found.'; Pause-Here; return }
    $dirs = @(Get-ChildItem -LiteralPath $BackupRoot -Directory | Where-Object { $_.Name -like 'Podcasts *' } |
        Sort-Object Name -Descending | ForEach-Object { [pscustomobject]@{ Label = $_.Name; Path = $_.FullName } })
    if ($dirs.Count -eq 0) { Write-Warn2 '  No podcast backups found.'; Pause-Here; return }
    $pick = @(Select-FromList $dirs { param($d) $d.Label } 'PICK A BACKUP (newest first)')
    if ($pick.Count -ne 1) { if ($pick.Count -gt 1) { Write-Warn2 '  Pick just one.'; Pause-Here }; return }
    if (-not (Connect-Phone)) { Pause-Here; return }
    Write-Warn2 "  This replaces the phone's follows, positions and played marks with the backup,"
    Write-Warn2 '  and sets the podcast speed and "after finishing" setting from it. Music and'
    Write-Warn2 '  audiobook settings are left as they are.'
    if (-not (Read-YesNo "  Restore $($pick[0].Label)?")) { return }
    try {
        [void](Invoke-Phone "am force-stop $AppPackage")
        $dir = $pick[0].Path
        foreach ($name in @('podcasts.json', 'podcast_episodes.json')) {
            $file = Join-Path $dir $name
            if (Test-Path -LiteralPath $file) { Write-PhoneFile $name ([IO.File]::ReadAllText($file, [Text.Encoding]::UTF8)) }
        }
        # Only the podcast settings come back; the rest of settings.json stays as the phone has it.
        $backupSettings = Join-Path $dir 'settings.json'
        if (Test-Path -LiteralPath $backupSettings) {
            $from = [IO.File]::ReadAllText($backupSettings, [Text.Encoding]::UTF8).TrimStart([char]0xFEFF) | ConvertFrom-Json
            $phoneText = Read-PhoneFile 'settings.json'
            $to = if ($phoneText) { $phoneText.TrimStart([char]0xFEFF) | ConvertFrom-Json } else { [pscustomobject]@{ version = 1 } }
            foreach ($key in @('podcastSpeed', 'deleteAfterFinishing')) {
                $v = Get-Prop $from $key $null
                if ($null -ne $v) { $to | Add-Member -NotePropertyName $key -NotePropertyValue $v -Force }
            }
            Write-PhoneFile 'settings.json' ($to | ConvertTo-Json -Depth 10)
        }
        # Any sync Listen hadn't picked up yet would undo the restore: drop it.
        [void](Invoke-Phone ("rm -f " + (Q "$PhoneState/podcasts_from_pc.json")))
        # Keep the PC's list in step with what was restored.
        $restored = Join-Path $dir 'podcasts.json'
        if (Test-Path -LiteralPath $restored) {
            $list = Read-SubsText ([IO.File]::ReadAllText($restored, [Text.Encoding]::UTF8))
            if ($null -ne $list) { Save-PcList $list }
        }
        Write-Good '  Restored. Open Listen to see it.'
    } catch {
        Write-Bad ("  Restore failed: " + $_.Exception.Message)
    }
    Pause-Here
}

function Menu-Main {
    while ($true) {
        $pc = Load-PcList
        $count = @($pc | Where-Object { Test-Following $_ }).Count
        $phone = if (Connect-Phone -Quiet) { "phone $($script:Serial) connected" } else { 'phone not connected' }
        Write-Title 'LISTEN - PODCASTS'
        Write-Note ("  Following {0} show(s) on this PC      {1}" -f $count, $phone)
        Write-Host ''
        Write-Host '   1  Search Apple Podcasts and follow'
        Write-Host '   2  Import an OPML file (from another podcast app)'
        Write-Host '   3  Add a feed address (private or Patreon feeds)'
        Write-Host '   4  List followed shows / unfollow'
        Write-Host '   5  Sync with the phone'
        Write-Host '   6  Back up / restore podcast data'
        Write-Host '   Q  Quit'
        $c = (Read-Host '  Choose').Trim()
        switch -Regex ($c) {
            '^1$' { Menu-Search }
            '^2$' { Menu-Opml }
            '^3$' { Menu-AddUrl }
            '^4$' { Menu-List }
            '^5$' { Menu-Sync }
            '^6$' { Menu-Backup }
            '^[qQ]$' { return }
        }
    }
}
#endregion

#---------------------------------------------------------------------------
# region  START
#---------------------------------------------------------------------------
if (-not (Test-Path -LiteralPath $PcRoot)) { [void](New-Item -ItemType Directory -Path $PcRoot -Force) }
$script:Adb = Find-Adb
if ($env:LISTEN_PODCASTS_NO_MENU) { return }   # test hook: load the functions only
if (-not $script:Adb) {
    Write-Warn2 'adb.exe (Android platform-tools) was not found: search, OPML and feed addresses'
    Write-Warn2 'work, but syncing with the phone needs it. Set $AdbPath near the top of this file.'
} else {
    Write-Note "  adb: $($script:Adb)"
    Update-FromPhone
}
Menu-Main
try { Remove-Item -LiteralPath $TempDir -Recurse -Force -ErrorAction SilentlyContinue } catch {}
Write-Host 'Bye.'
#endregion
