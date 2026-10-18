# LiveWebMap Beta - standalone CinemaMod Chromium installer.
# Download only on explicit user execution + confirmation; NOT part of Workshop mod.
# This intentionally installs to the legacy relative path expected by the verified dev JAR.
param(
    [string]$ArchivePath = '',
    [string]$ExpectedSha256 = '',
    [switch]$Yes
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$REV = 'eaeb3d4370aa3526ee237ad1981ad59af3de4dd1'
$BASE = "https://mcef-download.cinemamod.com/java-cef-builds/$REV/windows_amd64.tar.gz"
$HASH_URL = $BASE + '.sha256'
$TARGET = Join-Path $env:USERPROFILE 'LiveWebMapMCEFDev\thirdparty\mcef-natives\windows_amd64'
$REQUIRED = @('jcef.dll', 'libcef.dll', 'jcef_helper.exe')
$MAX_BYTES = 400MB
$sourceRoot = Join-Path $env:TEMP ('LiveWebMapChromium_' + [guid]::NewGuid().ToString('N'))

function Verify-Files([string]$folder) {
    foreach ($name in $REQUIRED) {
        $file = Join-Path $folder $name
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { return $false }
        if ((Get-Item -LiteralPath $file).Length -lt 1) { return $false }
    }
    return $true
}
function Valid-ArchivePaths([string]$file) {
    $members = @(& tar.exe -tzf $file 2>&1)
    if ($LASTEXITCODE -ne 0 -or $members.Count -lt 5) { throw 'Could not list contents of Chromium archive.' }
    foreach ($raw in $members) {
        $m = ([string]$raw).Replace('\','/').Trim()
        if (-not $m) { continue }
        if ($m.StartsWith('/') -or $m.StartsWith('../') -or $m.Contains('/../') -or
            $m.Contains(':') -or $m.StartsWith('//')) {
            throw "Unsafe relative path in runtime archive: $m"
        }
    }
}

if (-not [Environment]::Is64BitOperatingSystem) { throw 'Windows x64 is required.' }
if (-not (Get-Command tar.exe -ErrorAction SilentlyContinue)) {
    throw 'Windows tar.exe is required (included with Windows 10/11).'
}
if (Verify-Files $TARGET) {
    Write-Host "Chromium already appears installed at: $TARGET" -ForegroundColor Green
    Write-Host 'No files changed. Close this window and launch Project Zomboid.'
    exit 0
}
if (Test-Path -LiteralPath $TARGET) {
    throw "An incomplete native folder already exists. Nothing was overwritten: $TARGET`nBack it up or move it aside while the game is closed, then rerun."
}
Write-Host 'LIVEWEBMAP BETA - CHROMIUM RUNTIME INSTALLER' -ForegroundColor Cyan
Write-Host ''
Write-Host 'This is an optional, separate download. The Workshop mod will never start it.'
Write-Host 'CinemaMod/MCEF: https://github.com/CinemaMod/mcef'
Write-Host "Runtime archive: $BASE"
Write-Host "Checksum source: $HASH_URL"
Write-Host "Install destination: $TARGET"
Write-Host 'This installer does not request administrator rights, change Windows security settings, or run downloaded EXEs.'
Write-Host 'The downloaded runtime nevertheless contains native DLLs/EXEs that Project Zomboid will load.'
Write-Host ''
if (-not $Yes) {
    $permission = Read-Host 'Type INSTALL to continue or anything else to cancel'
    if ($permission -cne 'INSTALL') { Write-Host 'Cancelled; no changes made.'; exit 0 }
}
New-Item -ItemType Directory -Path $sourceRoot -Force | Out-Null
try {
    # Prefer checksum from the publisher's service. For an offline archive, the user
    # may provide an independently verified 64-digit SHA-256 explicitly.
    if (-not $ExpectedSha256) {
        $hashFile = Join-Path $sourceRoot 'runtime.sha256'
        Write-Host 'Fetching publisher-hosted SHA-256 checksum...'
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $HASH_URL -OutFile $hashFile -UseBasicParsing -TimeoutSec 45
        $hashText = Get-Content -LiteralPath $hashFile -Raw
        $matcher = [regex]::Match($hashText,'(?i)\b[0-9a-f]{64}\b')
        if (-not $matcher.Success -or $hashText.Length -gt 1024) { throw 'Invalid publisher checksum response.' }
        $ExpectedSha256 = $matcher.Value.ToLowerInvariant()
    }
    if ($ExpectedSha256 -notmatch '^(?i:[a-f0-9]{64})$') { throw 'Expected SHA-256 must have 64 hex characters.' }

    $archive = Join-Path $sourceRoot 'windows_amd64.tar.gz'
    if ($ArchivePath) {
        if (-not (Test-Path -LiteralPath $ArchivePath -PathType Leaf)) { throw "Archive file not found: $ArchivePath" }
        Copy-Item -LiteralPath $ArchivePath -Destination $archive
    } else {
        Write-Host 'Downloading official CinemaMod Chromium archive (this can be large)...'
        Invoke-WebRequest -Uri $BASE -OutFile $archive -UseBasicParsing -TimeoutSec 900
    }
    $size = (Get-Item -LiteralPath $archive).Length
    if ($size -le 0 -or $size -gt $MAX_BYTES) { throw "Unexpected archive size ($size bytes)." }
    $digest = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Host "Downloaded archive SHA-256: $digest"
    if ($digest -ne $ExpectedSha256.ToLowerInvariant()) { throw 'SHA-256 mismatch. No native files installed.' }

    Valid-ArchivePaths $archive
    $unpack = Join-Path $sourceRoot 'extracted'
    New-Item -ItemType Directory -Path $unpack -Force | Out-Null
    Write-Host 'Extracting verified archive using built-in Windows tar.exe...'
    & tar.exe -xzf $archive -C $unpack
    if ($LASTEXITCODE -ne 0) { throw 'Windows archive extraction failed.' }
    $native = Join-Path $unpack 'windows_amd64'
    if (-not (Verify-Files $native)) { throw 'Archive does not contain expected java-cef Windows runtime files.' }

    # Refuse archive-created symbolic links/reparse points anywhere in the native tree.
    $reparse = @(Get-ChildItem -LiteralPath $unpack -Recurse -Force -ErrorAction Stop | Where-Object {
        (($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)
    })
    if ($reparse.Count -gt 0) { throw 'Archive contains symbolic links/junctions; refusing install.' }

    $parent = Split-Path -Parent $TARGET
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    if (Test-Path -LiteralPath $TARGET) { throw 'Target already exists; never overwrite existing Chromium binaries.' }
    # Same volume is not guaranteed if TEMP has been relocated; normal directory move still works.
    Move-Item -LiteralPath $native -Destination $TARGET -ErrorAction Stop
    [IO.File]::WriteAllText((Join-Path $parent 'LIVEWEBMAP_RUNTIME_SOURCE.txt'),
      ("CinemaMod java-cef revision: $REV`nSource: $BASE`nSHA256: $digest`n"),
      [Text.UTF8Encoding]::new($false))
    Write-Host ''
    Write-Host 'SUCCESS - Chromium installed for the original LiveWebMap Java build.' -ForegroundColor Green
    Write-Host "Location: $TARGET"
    Write-Host 'You can now run Project Zomboid with ZombieBuddy + LiveWebMap Beta enabled.'
} finally {
    if (Test-Path -LiteralPath $sourceRoot) {
        Remove-Item -LiteralPath $sourceRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
