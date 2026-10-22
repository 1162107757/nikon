# Camera Wi-Fi simplification native probe

This standalone instrumentation APK tests only `emulator-5582` on Android 13 or newer. The shell runner and instrumentation both reject hardware other than `ranchu`/`goldfish`. It never selects a real device. Coordinate exclusive emulator ownership with the other UI task before running it.

It calls private `MainActivity.ensureCameraWifi(Runnable, Consumer<String>)` with spy continuations. It does not call the album connection action or start a PTP handshake. No real camera, user photos, or saved account session may be present. A connected emulator Wi-Fi is only a physical Network adoption fixture; these checks do not establish camera compatibility or background retention.

Build the probe without building or changing the product APK:

```powershell
powershell -ExecutionPolicy Bypass -File tests/wifi-simplify-device-probe/build.ps1 -ExpectedVersion 72
```

After the other task installs the agreed product APK and releases the emulator, run the scopes sequentially:

```powershell
powershell -ExecutionPolicy Bypass -File tests/wifi-simplify-device-probe/run.ps1 -Scope disconnected -Theme light -ExpectedVersion 72
powershell -ExecutionPolicy Bypass -File tests/wifi-simplify-device-probe/run.ps1 -Scope connected -Theme light -ExpectedVersion 72
```

The disconnected scope turns on airplane mode, disables Wi-Fi, and grants manual fallback permissions. It checks that the primary chooser has no credential inputs, retry stays on the chooser, cancellation settles once, manual input validation never creates a route, and passwords are masked and cleared on dismissal. An active `RESULT_CANCELED`/null-data result must start the bounded Wi-Fi check; canceling it must remove the poll and leave the spies unchanged after its next polling interval. This active-result case sets a reflected pending callback that invokes the exact production wait method, avoiding an unnecessary second system panel. A retired system picker action plus late `RESULT_CANCELED`/null-data result must not reopen or continue the request. This stale-result check opens and closes the Android system Wi-Fi panel once; no network is selected there.

The connected scope enables the emulator's already saved `AndroidWifi` connection and denies nearby permission. It also asserts that fine location is not granted; the app manifest limits location permissions to Android 12 and older. It verifies the existing physical Network is adopted without a credential or permission flow, the spy runs exactly once, the waiting lease is released, a late settings result has no effect, and ordinary UI cleanup preserves a healthy runtime-owned route. The probe explicitly disconnects its adopted observer during cleanup.

The runner snapshots and restores airplane/Wi-Fi state and the target app's nearby permission grant. It stops only the target app around these changes. Permission flags are not changed deliberately. It does not install the product APK, change app version, clear app data, or operate another device. Run `-Theme dark` for the same checks and dialog screenshots in the existing dark theme when an additional visual pass is needed.

Evidence is written under `tests/wifi-simplify-device-probe/evidence/<version>/<scope>-<theme>/`. The JSON includes the installed product APK SHA-256, version, checks, and scope limitations. Build output and evidence are ignored by Git.

Reflection contract: `cameraWifiDialog`, `pendingWifiConnect`, `pendingSystemWifiReturn`, `wifiSettingsPoll`, `wifiUiEpoch`, `wifiRequestLease`, `connections.wifiSession`, `album.controller`, `ready`, `wifiSession.network`, and `wifiSession.callback`; methods `ensureCameraWifi`, `cancelPendingCameraWifi`, `waitForSystemCameraWifi`, `onActivityResult`, `connectedWifiNetwork`, `isConnected`, `usesSpecificRoute`, `isActive`, `disconnect`, and `hasLiveSession`. System picker request code is `109`. Update the probe if these private interfaces change.
