# QQ account UI instrumentation

Runner: `com.nikonpreset.usbprobe.qqqa/.QqAccountProbe`.

Build with `./tests/qq-device-probe/build.ps1`. The signed APK and hash are written to `tests/.build/qq-device-probe/qq-device-probe.apk`; intermediate outputs are isolated below that directory. The script reads the existing development key and toolchain. It never installs an APK or connects to a device.

Only the coordinating root task may install/run this probe, and only on **emulator-5554**. Do not run it on a phone or a device with account data. The target must be signed with the existing development certificate and have no saved account, AndroidKeyStore account key, in-memory session, or pending logout. These conditions are checked before any synthetic preview.

After root installs both the target and probe on that emulator:

```powershell
& D:/dev/android-sdk/platform-tools/adb.exe -s emulator-5554 shell am instrument -w -r -e device emulator-5554 -e state authorizing -e holdMs 10000 com.nikonpreset.usbprobe.qqqa/.QqAccountProbe
```

`state` accepts `idle`, `privacy`, `authorizing`, `exchanging`, or `profile`. `holdMs` accepts 0–10000. The explicit `device=emulator-5554` argument and recognized emulator hardware are required. Root controls theme, font scale, and orientation before each run.

Each run opens the real AccountPanel, checks the QQ button, icon and 48 dp touch target, clicks it to show the actual privacy dialog, and declines consent. It asserts Tencent remains uninitialized and no account appears. It then sets only transient UI flags to preview authorizing/exchanging loaders, checking containment, centering, disabled actions, stable width, accessibility text, and absence of a false password-button loader. The authorizing cancellation callback restores the controls. No QQ SDK authorization, app login transport, synthetic success response, bearer token, or session persistence is invoked.

All account action buttons, including QQ/password submit and the product privacy buttons, must have zero elevation, zero translationZ, no state animator, and retain their ripple background. `profile` adds a clearly synthetic QQ identity only to the in-memory repository and panel; its intentionally invalid server bearer is never sent or stored. The preview checks refresh/logout/privacy buttons use the same flat style and restores the repository to its original empty state before closing. It never calls refresh or logout.

The selected preview emits a `READY` status bundle for root screenshots, then restores the UI and closes its Activity. PASS reports only fixed stage identifiers, check counts and geometry; failures never dump provider objects or credentials. Synthetic loading states demonstrate real view rendering, not successful QQ authentication or SDK lifecycle acceptance.
