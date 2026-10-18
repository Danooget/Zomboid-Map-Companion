# Zomboid Map Companion — source transparency

**This package includes the original Java, Lua, and Go installer source code, not merely a README or JAR disassembly.**

## Where the code lives

| Folder | Contents |
| --- | --- |
| `java/src/main/java/livemap/` | The 12 original Java sources corresponding to all 12 top-level `livemap` classes present in the supplied released `LiveWebMap.jar` |
| `java/src/main/resources/livemap/` | Original GPS-bridge JavaScript source |
| `java/build.gradle`, `java/settings.gradle`, `java/gradle/` | The author's original Java/Gradle build scripts and wrapper |
| `java/thirdparty/mcef/common/java-cef/` | Relevant original CinemaMod/JCEF fork Java source and its license, with upstream test sources omitted |
| `mod/LiveWebMap/42/` | Project Zomboid Build 42 Lua source files and mod metadata from the working mod archive |
| `installer/` | Standalone Go Windows Chromium runtime installer, tests, build instructions, and historical PowerShell installer |

## Validation and limitations

- The supplied released `LiveWebMap.jar` was SHA-256 `cf5fe524ad45e903179829c08b64b5a609fb1dac5edf0ab5ef7323fbba3a53e4` and contained **12** top-level mod Java classes. The new archive contains corresponding original `.java` files for all 12. The two Java files supplied earlier in the friends release (`McefProbe.java` and `LiveWebMapPanel.java`) also match the development archive byte-for-byte.
- This package intentionally **excludes built binaries**, native Chromium runtime, Gradle download caches, cached browser data, and Project Zomboid proprietary JARs. It also excludes two unused legacy Java files not compiled into the released mod; see `java/BUILDING.md`.
- Complete original mod-side source is now available, but an **end-to-end Java rebuild has not yet been tested** with JDK 25, ZombieBuddy, game JARs, and Gson. Do not claim independently verified byte-for-byte reproducible JAR builds until that has been demonstrated.
- The installer is written in **Go**. Its older PowerShell script is preserved only under `installer/legacy-powershell-installer/`. See `installer/README.md` for checksum validation, supported systems, and instructions.
- `LiveWebMap` is intentionally retained as an **internal class/mod/path identifier** to preserve compatibility. The public name is **Zomboid Map Companion**.

## Licensing

Some included code is third-party CinemaMod/JCEF material. Preserve the upstream license files under `java/thirdparty/mcef/`. No new license grant for the author's original code is asserted by this archive; the repository owner should add the license they intend to apply after checking third-party obligations.

## About GitHub's automatically generated source archives

GitHub's “Source code (zip)” and “Source code (tar.gz)” files for a release are snapshots of the **commit to which that release tag points**. Upload the extracted source files and commit them, then create a new tag/release to have the generated archives include them. Uploading this ZIP file as a binary release asset is not a substitute for committing its contents into the repository.
