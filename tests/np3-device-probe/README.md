# NP3 native Android probe

This instrumentation is restricted to the disposable `emulator-5582` layer. Its run script verifies emulator hardware and enables airplane mode before launching the product. It never chooses a connected physical device.

```powershell
.\tests\np3-device-probe\build.ps1
# Install the current product APK on emulator-5582 before running:
.\tests\np3-device-probe\run.ps1 -Theme light -ExpectedVersion 57
.\tests\np3-device-probe\run.ps1 -Theme dark -ExpectedVersion 57
```

The build reads two original NP3 samples from the repository's research archive into ignored `.tooling/np3-native` assets, compiles only the probe source against Android 35, and uses the existing development signature. Product classes are accessed through reflection, so the probe cannot substitute a host rendering implementation.

Checks cover the supported BleachBypass recipe and explicitly unsupported Bluegrain, original template immutability, native template pixels, full 1800 × 1200 JPEG processing, sRGB and capture EXIF, cancellation before rendering, a fresh local batch, output reuse, verified original links, real committed MediaStore bytes, and native detail/selector states in each theme.

Evidence is written to the target app's `externalFiles/np3-native/<theme>` directory and pulled into `docs/validation/np3-native/<theme>`. Unique synthetic photographs and their QA outputs remain in the disposable emulator layer. This probe does not establish equivalence to Nikon's proprietary rendering, physical-device performance, or camera import compatibility.
