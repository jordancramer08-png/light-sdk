# Copies comics (.cbz, and .cbr repacked as .cbz) into the Reader app's private comics folder
# on the Light Phone, keeping each comic's folder path under D:\Comics. Note files (.txt)
# sitting in the same folders as the comics go too.
# Called by "Send Comics to Phone.cmd". Nothing in D:\Comics is ever changed: repacking and
# shrinking happen on copies in %TEMP%, which are deleted at the end.

$ErrorActionPreference = 'Stop'
$Package    = 'com.thelightphone.reader'
$PhoneDir   = 'files/shared/comics'
$TempDir    = '/data/local/tmp'
$ComicsRoot = 'D:\Comics'   # your whole comics library (subfolders included)
$MaxSide    = 2000          # shrinking: longest side of a page, in pixels
$JpegQuality = 85           # shrinking: JPEG quality of a shrunk page

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.Drawing

# --- find adb, 7-Zip and tar ---
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
if (-not (Test-Path $adb)) { Write-Host "Can't find adb. Install Android Studio first (see the walkthrough)."; exit 1 }

$SevenZip = 'C:\Program Files\7-Zip\7z.exe'
if (-not (Test-Path $SevenZip)) { $SevenZip = (Get-Command 7z -ErrorAction SilentlyContinue).Source }
$Tar = Join-Path $env:SystemRoot 'System32\tar.exe'
if (-not (Test-Path $Tar)) { $Tar = $null }

# Runs a program with its output hidden; returns its exit code.
function Run-Quiet([string]$exe, [string[]]$arguments) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $exe @arguments *> $null
    $code = $LASTEXITCODE
    $ErrorActionPreference = $old
    return $code
}

# --- which comics? ---
# Each pick is a file plus the folder it goes in on the phone (RelDir, relative to D:\Comics).
function Test-UnderRoot([string]$path) {
    return $path.StartsWith($ComicsRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)
}

# The file's folder relative to $base ('' when it sits right in $base).
function Get-RelDir($file, [string]$base) {
    $dir = $file.DirectoryName
    if ($dir.Length -le $base.TrimEnd('\').Length) { return '' }
    return $dir.Substring($base.TrimEnd('\').Length).TrimStart('\')
}

function New-Pick($file, [string]$base) {
    [pscustomobject]@{ File = $file; RelDir = (Get-RelDir $file $base) }
}

function Test-ComicFile($file) { return $file.Extension -eq '.cbz' -or $file.Extension -eq '.cbr' }

# Dropped files and folders. Anything under D:\Comics keeps its path from there; a folder
# from somewhere else keeps its own name; a loose file from somewhere else goes at the top.
function Find-Dropped($paths) {
    foreach ($p in $paths) {
        if (Test-Path -LiteralPath $p -PathType Container) {
            $folder = Get-Item -LiteralPath $p
            $base = if (Test-UnderRoot $folder.FullName) { $ComicsRoot } else { $folder.Parent.FullName }
            Get-ChildItem -LiteralPath $folder.FullName -File -Recurse |
                Where-Object { Test-ComicFile $_ } |
                ForEach-Object { New-Pick $_ $base }
        } elseif (Test-Path -LiteralPath $p -PathType Leaf) {
            $file = Get-Item -LiteralPath $p
            $base = if (Test-UnderRoot $file.FullName) { $ComicsRoot } else { $file.DirectoryName }
            if ((Test-ComicFile $file) -or $file.Extension -eq '.txt') { New-Pick $file $base }
            else { Write-Host "Skipping (not a .cbz, .cbr, .txt or folder): $p" }
        } else {
            Write-Host "Skipping (not found): $p"
        }
    }
}

if ($args.Count -gt 0) {
    $picks = @(Find-Dropped $args)
} else {
    if (-not (Test-Path -LiteralPath $ComicsRoot)) { Write-Host "Comics folder not found: $ComicsRoot"; exit 1 }
    Write-Host "Reading your comics in $ComicsRoot ..."
    $all = @(Get-ChildItem -LiteralPath $ComicsRoot -File -Recurse | Where-Object { Test-ComicFile $_ } | Sort-Object FullName)
    $byKey = @{}
    $rows = foreach ($f in $all) {
        $folder = Get-RelDir $f $ComicsRoot
        $byKey["$folder\$($f.Name)"] = $f
        [pscustomobject][ordered]@{ Folder = $folder; Name = $f.Name; 'Size MB' = [math]::Round($f.Length / 1MB, 1) }
    }
    Write-Host 'Pick comics in the window: type in the filter box to search, Ctrl+click to pick several, then OK.'
    Write-Host 'Tip: type a folder name in the filter box, then press Ctrl+A to pick that whole folder.'
    $picked = @($rows | Out-GridView -Title 'Pick comics to send to the phone' -PassThru)
    $picks = @($picked | ForEach-Object { New-Pick $byKey["$($_.Folder)\$($_.Name)"] $ComicsRoot })
}

$comics = @($picks | Where-Object { Test-ComicFile $_.File })
if ($comics.Count -eq 0) { Write-Host 'No comics picked.'; exit 1 }

# Note files in the same folders as the comics being sent (plus any dropped directly).
$notes = @($picks | Where-Object { $_.File.Extension -eq '.txt' })
$folders = $comics | Group-Object { $_.File.DirectoryName } | ForEach-Object { $_.Group[0] }
foreach ($c in $folders) {
    $notes += @(Get-ChildItem -LiteralPath $c.File.DirectoryName -Filter *.txt -File |
        ForEach-Object { [pscustomobject]@{ File = $_; RelDir = $c.RelDir } })
}
$notes = @($notes | Sort-Object { $_.File.FullName } -Unique)

# --- phone connected and app installed? ---
$devices = & $adb devices | Select-String "`tdevice$"
if (-not $devices) { Write-Host 'No phone found. Plug it in, unlock it, and allow USB debugging.'; exit 1 }
$installed = & $adb shell pm list packages $Package | Select-String -SimpleMatch "package:$Package"
if (-not $installed) { Write-Host 'The Reader app is not installed yet. Run "Build and Install Reader.cmd" first.'; exit 1 }

# --- shrink? ---
$answer = Read-Host 'Shrink pages to phone size? (Y/N)'
$shrink = $answer.Trim() -notmatch '^(n|no)$'
if ($shrink) { Write-Host "Pages wider or taller than $MaxSide px will be resized and saved as JPEG (quality $JpegQuality)." }

# --- repacking and shrinking (on copies in %TEMP%) ---
$Work = Join-Path $env:TEMP ('reader-comics-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $Work | Out-Null

$JpegCodec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
$JpegParams = New-Object System.Drawing.Imaging.EncoderParameters(1)
$JpegParams.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter([System.Drawing.Imaging.Encoder]::Quality, [long]$JpegQuality)
$PageExtensions = @('.jpg', '.jpeg', '.png', '.bmp', '.gif')

# Unpacks a comic into $dir. Returns '' when it worked, or why it didn't.
function Expand-Comic($file, [string]$dir) {
    if ($file.Extension -eq '.cbz') {
        try { [System.IO.Compression.ZipFile]::ExtractToDirectory($file.FullName, $dir); return '' }
        catch { Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction SilentlyContinue; New-Item -ItemType Directory -Path $dir | Out-Null }
    }
    if ($SevenZip) {
        if ((Run-Quiet $SevenZip @('x', '-y', "-o$dir", '--', $file.FullName)) -le 1) { return '' }
    }
    if ($Tar) {
        if ((Run-Quiet $Tar @('-xf', $file.FullName, '-C', $dir)) -eq 0) { return '' }
    }
    if (-not $SevenZip -and -not $Tar) { return 'no 7-Zip or tar.exe on this PC to open it' }
    if (-not $SevenZip) { return "Windows tar can't open it (installing 7-Zip from 7-zip.org may help)" }
    return "neither 7-Zip nor Windows tar could open it"
}

# Resizes one page so its longer side is at most $MaxSide, saved as JPEG. Pages already
# small enough, and pictures System.Drawing can't read (e.g. WebP), are left as they are.
function Shrink-Page([string]$path) {
    try {
        $stream = New-Object IO.MemoryStream(, [IO.File]::ReadAllBytes($path))
        $image = [System.Drawing.Image]::FromStream($stream)
    } catch { return }
    $bitmap = $null
    try {
        $long = [Math]::Max($image.Width, $image.Height)
        if ($long -le $MaxSide) { return }
        $out = [IO.Path]::ChangeExtension($path, '.jpg')
        if ($out -ne $path -and (Test-Path -LiteralPath $out)) { return }   # a page of that name already exists
        $scale = $MaxSide / $long
        $width = [Math]::Max(1, [int][Math]::Round($image.Width * $scale))
        $height = [Math]::Max(1, [int][Math]::Round($image.Height * $scale))
        $bitmap = New-Object System.Drawing.Bitmap($width, $height)
        $g = [System.Drawing.Graphics]::FromImage($bitmap)
        $g.Clear([System.Drawing.Color]::White)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        $edges = New-Object System.Drawing.Imaging.ImageAttributes
        $edges.SetWrapMode([System.Drawing.Drawing2D.WrapMode]::TileFlipXY)
        $g.DrawImage($image, (New-Object System.Drawing.Rectangle(0, 0, $width, $height)), 0, 0, $image.Width, $image.Height, [System.Drawing.GraphicsUnit]::Pixel, $edges)
        $g.Dispose()
        $image.Dispose(); $image = $null
        $stream.Dispose()
        if ($out -ne $path) { Remove-Item -LiteralPath $path }
        $bitmap.Save($out, $JpegCodec, $JpegParams)
    } finally {
        if ($image) { $image.Dispose() }
        if ($bitmap) { $bitmap.Dispose() }
        $stream.Dispose()
    }
}

# Packs every file in $dir into a new .cbz at $dest. Pages are stored as they are (no recompression).
function Pack-Comic([string]$dir, [string]$dest) {
    $root = (Resolve-Path -LiteralPath $dir).ProviderPath.TrimEnd('\')
    $zip = [System.IO.Compression.ZipFile]::Open($dest, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($f in Get-ChildItem -LiteralPath $root -File -Recurse | Sort-Object FullName) {
            $name = $f.FullName.Substring($root.Length + 1).Replace('\', '/')
            [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $f.FullName, $name, [System.IO.Compression.CompressionLevel]::NoCompression)
        }
    } finally {
        $zip.Dispose()
    }
}

# Works out what to send for each comic: the original .cbz, or a repacked / shrunk copy.
# Sets .Send (the local file to send) or .Problem (why it's skipped).
function Prepare-Comic($pick, [int]$number) {
    $file = $pick.File
    $pick | Add-Member -NotePropertyName Send -NotePropertyValue $null -Force
    $pick | Add-Member -NotePropertyName Problem -NotePropertyValue $null -Force
    if ($file.Extension -eq '.cbz' -and -not $shrink) { $pick.Send = $file.FullName; return }

    $unpacked = Join-Path $Work "unpacked-$number"
    New-Item -ItemType Directory -Path $unpacked | Out-Null
    try {
        $why = Expand-Comic $file $unpacked
        if ($why) { $pick.Problem = $why; return }
        if ($shrink) {
            Get-ChildItem -LiteralPath $unpacked -File -Recurse |
                Where-Object { $PageExtensions -contains $_.Extension.ToLower() } |
                ForEach-Object { Shrink-Page $_.FullName }
        }
        $dest = Join-Path $Work "comic-$number.cbz"
        Pack-Comic $unpacked $dest
        $pick.Send = $dest
    } catch {
        $pick.Problem = "couldn't repack it ($($_.Exception.Message))"
    } finally {
        Remove-Item -LiteralPath $unpacked -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Format-MB([double]$bytes) { '{0:N1} MB' -f ($bytes / 1MB) }

# Adds up file sizes (0 for none).
function Sum-Bytes($sizes) {
    $total = [double]0
    foreach ($s in @($sizes)) { if ($s) { $total += $s } }
    return $total
}

# Phone-side names: plain ASCII (accents and curly quotes flattened) so nothing gets
# mangled between Windows, adb and the phone. Applied to every folder name in the path too.
function PhoneName([string]$name) {
    $n = $name.Replace([char]0x2019, "'").Replace([char]0x2018, "'").Replace([char]0x201C, '"').Replace([char]0x201D, '"')
    $n = $n.Replace([char]0x2013, '-').Replace([char]0x2014, '-')
    $n = $n.Normalize([Text.NormalizationForm]::FormD)
    $n = -join ($n.ToCharArray() | Where-Object { [Globalization.CharUnicodeInfo]::GetUnicodeCategory($_) -ne 'NonSpacingMark' })
    $n = $n -replace '[^\x20-\x7E]', '_'
    $n = $n -replace '"', ''
    return $n
}

# The folder on the phone for a pick: files/shared/comics/<its folders, flattened>
function PhoneFolder($pick) {
    if (-not $pick.RelDir) { return $PhoneDir }
    $parts = $pick.RelDir.Split('\') | ForEach-Object { PhoneName $_ }
    return "$PhoneDir/" + ($parts -join '/')
}

# Single-quote a path for the phone's shell (handles spaces and apostrophes).
function Quote([string]$s) { "'" + ($s -replace "'", "'\''") + "'" }

try {
    $i = 0
    foreach ($c in $comics) {
        $i++
        if ($c.File.Extension -eq '.cbr' -or $shrink) { Write-Host "Preparing $i of $($comics.Count): $($c.File.Name)" }
        Prepare-Comic $c $i
        if ($c.Problem) { Write-Host "   Skipping $($c.File.Name): $($c.Problem)" }
    }
    $ready = @($comics | Where-Object { $_.Send })
    $notesSize = Sum-Bytes ($notes | ForEach-Object { $_.File.Length })
    $before = (Sum-Bytes ($comics | ForEach-Object { $_.File.Length })) + $notesSize
    $after = (Sum-Bytes ($ready | ForEach-Object { (Get-Item -LiteralPath $_.Send).Length })) + $notesSize

    # --- confirm ---
    Write-Host ''
    Write-Host "Picked $($comics.Count) comic(s) and $($notes.Count) note(s): $(Format-MB $before)."
    if ($shrink) { Write-Host "After shrinking: $(Format-MB $after)." }
    $skipped = $comics.Count - $ready.Count
    if ($skipped -gt 0) { Write-Host "$skipped comic(s) can't be sent (see above)." }
    if ($ready.Count -eq 0) { Write-Host 'Nothing to send.'; exit 1 }
    $answer = Read-Host 'Send these? (Y/N)'
    if ($answer.Trim() -notmatch '^(y|yes)$') { Write-Host 'Nothing was sent.'; exit 0 }

    # --- send ---
    $made = @{}
    function Send-One([string]$local, [string]$folder, [string]$name) {
        if (-not $made.ContainsKey($folder)) {
            & $adb shell "run-as $Package mkdir -p $(Quote $folder)" | Out-Host
            $made[$folder] = $true
        }
        $tmp = "$TempDir/reader-upload.tmp"
        & $adb push $local $tmp | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Host '   push failed'; return $false }
        & $adb shell "run-as $Package cp $tmp $(Quote "$folder/$name") && rm $tmp" | Out-Host
        if ($LASTEXITCODE -ne 0) { Write-Host '   copy into the app failed'; return $false }
        return $true
    }

    $sentComics = 0
    foreach ($c in $ready) {
        $name = PhoneName ([IO.Path]::ChangeExtension($c.File.Name, '.cbz'))
        Write-Host "Sending $($c.File.Name)"
        if (Send-One $c.Send (PhoneFolder $c) $name) { $sentComics++ }
    }
    $sentNotes = 0
    foreach ($n in $notes) {
        Write-Host "Sending $($n.File.Name)"
        if (Send-One $n.File.FullName (PhoneFolder $n) (PhoneName $n.File.Name)) { $sentNotes++ }
    }

    Write-Host ''
    Write-Host "Sent $sentComics of $($comics.Count) comic(s) and $sentNotes of $($notes.Count) note(s)."
    if ($shrink) { Write-Host "Size before shrinking: $(Format-MB $before). After: $(Format-MB $after)." }
    else { Write-Host "Size: $(Format-MB $after)." }
    Write-Host 'Open Reader on the phone and tap COMICS - new comics are prepared the first time their folder opens.'
} finally {
    Remove-Item -LiteralPath $Work -Recurse -Force -ErrorAction SilentlyContinue
}
