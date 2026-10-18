# Zomboid Map Companion - Chromium Runtime Installer (Windows x64)

This directory contains the **source code** for `ZomboidMapCompanionChromiumInstaller.exe`, a console-based executable written in Go. It does **not** bundle Chromium binaries or invoke PowerShell.

## End-user instructions

1. Install/enable ZombieBuddy and subscribe to Zomboid Map Companion.
2. **Close Project Zomboid completely.**
3. Double-click `ZomboidMapCompanionChromiumInstaller.exe` on Windows 10/11 x64.
4. Review the CinemaMod source URL and target directory. Type `INSTALL` to approve the download and extraction.
5. After `SUCCESS`, start Project Zomboid, approve the mod with ZombieBuddy, and press **F7**.

The installer requires internet access and fetches the exact MCEF runtime revision used by the existing Java mod, plus the publisher's `.sha256` file. Downloads are stored in a temporary directory; the downloaded `.tar.gz` must pass SHA-256 verification before its contents can be installed.

## Why does the installed directory still say LiveWebMap?

The already-compiled Java mod expects this historical path:

`%USERPROFILE%\LiveWebMapMCEFDev\thirdparty\mcef-natives\windows_amd64`

**Do not rename this directory** without first changing and rebuilding the Java mod. The installer and mod are publicly branded *Zomboid Map Companion*, but they retain the original runtime path to remain compatible.

## Source and security

- CinemaMod MCEF: https://github.com/CinemaMod/mcef
- Runtime revision: `eaeb3d4370aa3526ee237ad1981ad59af3de4dd1`
- Archive: `https://mcef-download.cinemamod.com/java-cef-builds/eaeb3d4370aa3526ee237ad1981ad59af3de4dd1/windows_amd64.tar.gz`
- Publisher SHA-256: append `.sha256` to the archive URL.
- Only installs after explicit confirmation; **no administrator rights needed**.
- Does not modify Project Zomboid, Steam, Windows security settings, or run any downloaded binaries.
- Rejects archives with unsafe paths, Windows device filenames, symlinks, duplicate filenames, and oversized content.
- If a complete runtime is already present, it leaves it alone. If the destination exists but is incomplete, it refuses to overwrite it.
- The installer is **not code-signed**. Windows SmartScreen may flag an unfamiliar, unsigned executable. Users should only download from the project's official release page and may audit/build from the included source. Do not disable antivirus protections.

**Release validation note:** This code was cross-compiled and tested on Linux using Go unit tests. A Windows machine and an end-to-end download/install test against the CinemaMod server were not available. Test the executable on Windows with a clean runtime directory and confirm the CinemaMod URL/checksum endpoint are accessible before publishing the release. The existing `.sha256` endpoint is required by default, so if the upstream service changes, the install will fail safely rather than accepting an unverified download.

## Optional advanced usage

A local archive and independently verified hash can be provided at a terminal:

```
ZomboidMapCompanionChromiumInstaller.exe -archive "C:\Downloads\windows_amd64.tar.gz" -sha256 YOUR_VERIFIED_64_HEX_HASH
```

To skip the interactive approval prompt (for deliberate automation), add `-yes`. **Do not use `-sha256` with an untrusted checksum.** If only `-archive` is supplied, the installer still downloads the publisher's `.sha256` file for verification.

## Build from source

Requires Go 1.23 or newer. No third-party Go modules are required.

```powershell
$env:GOOS = 'windows'
$env:GOARCH = 'amd64'
$env:CGO_ENABLED = '0'
go test ./...
go build -trimpath -ldflags='-s -w -buildid=' -o ZomboidMapCompanionChromiumInstaller.exe .
```

To run the tests from a Linux/macOS terminal, use `go test ./...`. The output `.exe` is a native Windows console program, not a PowerShell wrapper.

## Release provenance

This project contains the Go code used to produce a Chromium installer in this conversation. To establish that a GitHub release binary corresponds to this code, build from the tagged commit and compare the artifact with the published executable. With Go 1.23.2, Windows amd64, CGO disabled, and the exact build flags shown, a local rebuild matched the originally generated installer (`be33ee6bcaecdf0570e7aa0ee31cc910b93a9196d6346dc95671cfa1893cb0f9`) byte-for-byte. This verifies only the originally generated executable, not an independently uploaded GitHub release file. Compare the GitHub asset hash before asserting that release equivalence.
