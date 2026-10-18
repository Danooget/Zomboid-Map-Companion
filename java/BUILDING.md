# Java source and build notes

These are the **original author-supplied Java source files** recovered from `LiveWebMapMCEFDev.7z`. The release's 12 top-level custom `livemap` classes have matching source filenames in `src/main/java/livemap/`; all are included. The original `build.gradle`, `settings.gradle`, wrapper and JS resource are also included, along with the subset of the upstream JCEF Java source required by the build.

## Build prerequisites

- Windows x64 computer with Project Zomboid Build 42 and ZombieBuddy development dependencies.
- **JDK 25** (the original script has `options.release = 25`).
- Gradle via included `gradlew.bat` wrapper (may download Gradle from the distribution URL).
- The game's `projectzomboid.jar` and `ZombieBuddy.jar`; original `build.gradle` uses Steam's conventional Windows installation path `C:/Program Files (x86)/Steam/steamapps/common/ProjectZomboid/`. **Edit these paths for your installation.**
- Gson JAR located under your existing Gradle cache; original `build.gradle` uses a local fileTree instead of resolving this dependency from a Maven repository. If missing, provision it or change the dependency configuration.

Run from this directory in PowerShell:

```powershell
.\gradlew.bat clean jar
```

The script outputs `build/libs/LiveWebMap.jar`. **This is a build recipe from the original project, not a verified independently reproducible release.** We have not run the Java compilation end to end in this environment because the Java 25 toolchain and licensed game JARs are not available here. The output should be tested in-game before releasing a new version. No claim is made that a fresh build will be byte-for-byte identical to the original JAR.

## Upstream JCEF attribution

`thirdparty/mcef/common/java-cef/java/` contains the CinemaMod MCEF project's `java-cef` Java sources used by this Gradle build, excluding upstream tests. The upstream `java-cef/LICENSE.txt` is included, as is the parent MCEF project's `thirdparty/mcef/LICENSE` (LGPL-2.1 text). Original upstream source: https://github.com/CinemaMod/mcef . This package does not contain CinemaMod's Chromium native binaries, Gradle cache, or private game JARs.

Two experimental files in the original dev archive, `BrowserHelper.java` and `BrowserHost.java`, were excluded. The original `build.gradle` excludes both, neither corresponds to a class in the distributed release JAR, and `BrowserHost.java` contains a hard-coded local development path. Their exclusion does not remove any of the 12 released custom Java classes.

Do not rename historical `LiveWebMap` identifiers or Chromium runtime paths without updating Java, Lua, and the installer in coordination.
