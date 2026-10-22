# Interrupted download native UI probe

This standalone instrumentation operates only `emulator-5582` with ranchu/goldfish guards in both runner and Java. Coordinate exclusive emulator ownership before running. It never installs a product APK, joins Wi-Fi, opens camera sockets, sends PTP commands, creates photo bytes, or touches a physical camera.

Build only the instrumentation:

```powershell
powershell -ExecutionPolicy Bypass -File tests/download-recovery-device-probe/build.ps1 -ExpectedVersion 73
```

After the main task installs its frozen 73 product APK, run sequentially:

```powershell
powershell -ExecutionPolicy Bypass -File tests/download-recovery-device-probe/run.ps1 -ExpectedVersion 73 -Mode phone
powershell -ExecutionPolicy Bypass -File tests/download-recovery-device-probe/run.ps1 -ExpectedVersion 73 -Mode large-font
powershell -ExecutionPolicy Bypass -File tests/download-recovery-device-probe/run.ps1 -ExpectedVersion 73 -Mode landscape
```

The runner restores font scale and rotation after each scope. It does not change permissions, network state, app version, or app data. The probe requires no account session and no existing camera/Wi-Fi session. It supplies only synthetic metadata and a memory-only plan, sets an in-flight UI fixture, then calls the real `disconnectForConnectionLoss` and lifecycle observer. It verifies that the UI ends busy state without waiting for a suppressed old callback, preserves both the current and queued pending identities, marks the current result unconfirmed, presents native recovery/cancel controls, clears plans on explicit cancel/disconnect, and lets account-owner cleanup stop remaining work without pretending an in-flight local commit has already stopped.

Reports and screenshots include the actually installed APK hash under `.gitignore`d `evidence/<version>/<mode>/`. The native fixture establishes UI state handling only. Actual half-frame cancellation, GUID/descriptor/handle rebind rejection, manual reconnection, original-file completion and deduplication are covered separately by `tests/run-album.ps1 -DownloadRecoveryOnly`. Neither scope certifies Nikon Z6II hardware sleep behavior or real-device background retention.

The final probe additionally covers empty and synthetic-connected photo-grid recovery layouts. It uses bounded real MotionEvent swipes to reveal controls, verifies a 48dp visible hit area and a center clear of the floating navigation, then cancels through real touch coordinates. The synthetic connected client has no sockets, deliberately obsolete photo sessions, and a rejecting connector; the Grid remains independently bounded outside any ScrollView. A final screenshot opens the Fuji guide by changing only in-memory protocol/screen fields and checks the brand-only entry/introduction while preserving the explicit X-T20 compatibility limitation. It never clicks Connect or changes stored protocol preferences.

Mode labels alone are not evidence of orientation: after Activity startup the instrumentation explicitly requests LANDSCAPE or PORTRAIT, waits for the Activity's actual resources and measured decor layout to agree, and checks fontScale 1.0 or 1.3. Every non-failure screenshot rechecks those conditions and its bitmap aspect ratio. Reports record the Activity's actual orientation, font scale, pixel dimensions and dp dimensions. Earlier reports with an incorrect orientation remain separate historical attempts and must not be relabeled as landscape passes.
