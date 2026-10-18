LiveWebMap Beta -- separate Chromium runtime installer (Windows x64)
==================================================================

WHY IS THIS SEPARATE?
The Steam Workshop item contains the LiveWebMap mod only. Chromium native
files are downloaded ONLY if YOU choose to run this external installer.
You can inspect the full PowerShell source before running it.

INSTALL STEPS
1. Install ZombieBuddy and subscribe to LiveWebMap Beta in Steam.
2. EXIT Project Zomboid completely.
3. Download and extract this installer ZIP.
4. Review INSTALL_CHROMIUM.ps1 if desired; double-click Install Chromium.cmd.
5. The script displays the CinemaMod source and destination, then asks you to
   type INSTALL to authorize the download and extraction.
6. When it says SUCCESS, start Project Zomboid; approve LiveWebMap in ZombieBuddy
   and enable the mod. Default hotkey of the verified build is F7.

Official third-party source: https://github.com/CinemaMod/mcef
Expected native revision: eaeb3d4370aa3526ee237ad1981ad59af3de4dd1
Archive: https://mcef-download.cinemamod.com/java-cef-builds/eaeb3d4370aa3526ee237ad1981ad59af3de4dd1/windows_amd64.tar.gz

IMPORTANT PATH NOTE
To preserve the exact working dev JAR, it expects the runtime relative to
YOUR Windows user folder at:
  %USERPROFILE%\LiveWebMapMCEFDev\thirdparty\mcef-natives\windows_amd64
The folder name is a historical leftover, not a reference to the author's PC.
The installer creates it automatically. It does not install Java/Gradle.

MANUAL OPTION FOR TECHNICAL USERS
Download the exact tar.gz above and its .sha256 companion, verify SHA-256,
extract it using Windows tar.exe, and place the extracted windows_amd64
folder at the destination printed above. Make sure jcef.dll, libcef.dll and
jcef_helper.exe appear directly in windows_amd64, not a nested second copy.

Nothing in this installer is inserted into Steam Workshop. No administrator
rights are needed. The script does not run jcef_helper.exe by itself.

NOTE: The CinemaMod download endpoint and .sha256 companion were not reachable
for live end-to-end testing in the package-authoring environment. If the
publisher's endpoint has changed, installation will fail without replacing
your existing files; the URL must be updated and retested before public release.
