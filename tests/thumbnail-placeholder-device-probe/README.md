# Native thumbnail placeholder probe

This focused standalone Android instrumentation uses the actual `LocalAlbumPanel`, MediaStore query and thumbnail decoder. It inserts exactly two uniquely named owned rows: a JPEG encoded from the installed app's `preset_template` resource and deliberately invalid JPEG bytes. It gates the panel's two image workers to capture real pending tiles, checks failed versus loaded rendering and the stale-view tag guard, rewrites only its invalid row with the valid JPEG, then verifies native refresh recovery. Fixtures are deleted by exact owned URI in `finally` and their absence is checked. Screenshots come directly from `UiAutomation.takeScreenshot` without pixel edits.

Build only the standalone probe (Java 17, Android SDK 35, the repository's existing debug signing key):

```powershell
./tests/thumbnail-placeholder-device-probe/build.ps1
```

After the coordinating task supplies the final candidate APK, installs it on the reserved emulator and authorizes the run:

```powershell
./tests/thumbnail-placeholder-device-probe/run.ps1 -Mode phone -CandidateApk 'D:\absolute\path\candidate.apk'
./tests/thumbnail-placeholder-device-probe/run.ps1 -Mode font1.3 -CandidateApk 'D:\absolute\path\candidate.apk'
./tests/thumbnail-placeholder-device-probe/run.ps1 -Mode dark -CandidateApk 'D:\absolute\path\candidate.apk'
```

The runner only accepts `emulator-5586`, verifies emulator hardware, and does not start an emulator, build the application, install the candidate, clear app data, change connectivity, or touch physical devices. The installed app must match the supplied candidate's exact SHA-256 and version. It installs only the standalone probe APK. System font scale, orientation, night mode and the three relevant runtime permission grant states are captured and restored. Reports, fixture provenance, exact package identity and raw screenshots go to `docs/validation/thumbnail-placeholder/<mode>/`. Probe build inputs and APK hashes are included. This is a focused visual/decoder check, not the full gallery, camera, performance or accessibility suite.
