# Zomboid Map Companion Beta

**An interactive, real-time map for Project Zomboid Build 42.**

Zomboid Map Companion brings the Project Zomboid online map directly into your game through an integrated Chromium browser. Explore Knox Country without switching windows, and track your character's location in real time.

## Features

- **Interactive map:** Browse the Project Zomboid map directly in-game.
- **Live GPS tracking:** See your character's current location on the map.
- **Resizable window:** Move, resize, minimize, and maximize the map.
- **Integrated browser controls:** Navigate, zoom, and interact with the website.
- **Performance options:** Adjust browser rendering quality to suit your system.
- **Project Viewpoint compatibility:** Supports the Viewpoint rendering mod.
- **F7 shortcut:** Open or close the map at any time.

## Requirements

**Zomboid Map Companion requires additional components. Subscribing through Steam Workshop alone is not sufficient.**

- Project Zomboid Build 42
- Windows 64-bit
- ZombieBuddy Java mod loader
- CinemaMod MCEF / Chromium runtime

## Installation

### 1. Install ZombieBuddy

Install and configure the ZombieBuddy Java mod loader according to its installation instructions.

[ZombieBuddy Github Repo](https://github.com/zed-0xff/ZombieBuddy)

[ZombieBuddy Steam Workshop](https://steamcommunity.com/workshop/filedetails/?id=3619862853)

### 2. Subscribe to Zomboid Map Companion

Subscribe to **Zomboid Map Companion Beta** on the Steam Workshop.

**Steam Workshop:** [Zomboid Map Companion Beta](YOUR_STEAM_WORKSHOP_URL)

### 3. Install the Chromium runtime

Zomboid Map Companion uses Chromium Embedded Framework through the CinemaMod MCEF project. These native browser components must be installed separately.

1. Visit the [GitHub Releases page](../../releases).
2. Download **ZMC_Chromium_Installer_v0.1.0.exe** from the latest release.
3. Review the information displayed by the installer and approve the installation.
4. Wait for the installer to download, verify, and extract the Chromium runtime.

The installer downloads the browser runtime from CinemaMod's distribution. Chromium binaries are not included in the Steam Workshop mod.

**Security and transparency:** The installer is provided separately so users can review its PowerShell source before choosing to run it. Zomboid Map Companion does not automatically download Chromium when the mod starts.

### 4. Launch Project Zomboid

1. Enable Zomboid Map Companion Beta and ZombieBuddy.
2. Approve Zomboid Map Companion when prompted by ZombieBuddy.
3. Load into your game.
4. Press **F7** to open the map.

Your character's location should update automatically.

## Troubleshooting

**The map does not open**

Make sure Zomboid Map Companion and ZombieBuddy are both enabled, and that the Chromium runtime installer completed successfully.

**The map window opens but the website does not load**

Check your internet connection and verify that the runtime was installed correctly.

**The game crashes during startup**

Check for duplicate Zomboid Map Companion installations, confirm that you are running the supported Build 42 version, and review your Project Zomboid `console.txt` log.

If you encounter a bug, please open a [GitHub Issue](../../issues) with a description of the problem and relevant logs.

## Third-party projects and credits

Zomboid Map Companion uses and integrates with the following independent projects:

- [CinemaMod / MCEF](https://github.com/CinemaMod/mcef) — Chromium integration and browser runtime
- [Project Zomboid Map](https://projectzomboidmap.com/) — Interactive online map
- [ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy) — Java mod-loading support
- [Project Zomboid](https://projectzomboid.com/) — The game

Zomboid Map Companion is an independent community project and is not officially affiliated with The Indie Stone, CinemaMod, or the Project Zomboid Map website.

Third-party components remain subject to their respective licenses.

## Beta status

Zomboid Map Companion is under active development. Features, compatibility, and installation procedures may change between releases.

Bug reports and feedback are welcome through GitHub Issues or the Steam Workshop page.
