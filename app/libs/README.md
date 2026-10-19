# QQ Android SDK dependencies

Verified against Tencent's official download page on 2026-10-18:

- Download page: <https://wiki.connect.qq.com/sdk下载>
- Android SDK: **3.5.19**, released **2026-04-14**.
- Official archive URL: <https://tangram-1251316161.file.myqcloud.com/files/20260414/143310b667ece8922594fbe866dabdef.zip>
- Archive SHA-256: `6272d4123d8327400a655c48f1fb7eb24b83fd5beeb527951ed100b219192b9b`.
- Vendored file: `open_sdk_3.5.19_r9483ffc7_lite.jar`, extracted unchanged from that archive.
- JAR SHA-256: `9d57fe61ff9026d34ac84bc63dc719f61da6aa40533a299cc6f73d4ce9df7af8`.

This is Tencent's proprietary SDK, not an Apache/MIT licensed implementation. The archive includes the SDK JAR and a demo APK and does **not** include a separate LICENSE file. Its use is governed by the [QQ Connect Developer Agreement](https://wiki.connect.qq.com/开发者协议) and the [SDK privacy statement](https://wiki.connect.qq.com/qq互联sdk隐私保护声明). Preserve Tencent's ownership; do not represent this binary as project-authored code.

The application initializes the SDK only after the user accepts the QQ login disclosure, requests `get_simple_userinfo` only, and exchanges the provider token for the application's own session. No AppKey is included. The application does not explicitly call `saveSession`, `initSessionCache`, `setAccessToken` or `setOpenId`, but **the Tencent SDK automatically writes a temporary provider session before invoking its completion listener**. The controller removes that SDK cache before releasing an attempt and clears its SDK session after each outcome, including late completion after the Activity closes. It retains only the application context for that cleanup. Old callbacks may remove the SDK's unnecessary provider cache, but cannot clear a newer attempt's listeners or in-memory SDK session. This is not a claim that the SDK never briefly writes credentials to disk. The application's own account storage and server database keep only the separate application session, never a QQ provider token. The SDK's documented [custom logger API](https://wiki.connect.qq.com/业务自定义输出互联sdk日志) adds a callback; it does not replace the default SDK log sinks.

For this pinned binary, `QqLoginController.start()` first sets the SDK's public `com.tencent.open.log.c.a` and `.b` default log-level gates to zero and disables the existing default logcat tracer with `com.tencent.open.log.e.a.a(false)`, then installs a no-op custom tracer. These are public but obfuscated SDK controls, so they are deliberately scoped to this exact binary; a linkage failure prevents authorization. SDK startup can still create a log writer and emit its fixed version header (the pinned binary's internal header currently says `3.5.18.lite`); the file gate excludes subsequent response messages. These controls do not claim to suppress every possible vendor `printStackTrace`. **Any SDK upgrade must recheck these controls and runtime log behavior before testing with real credentials.** The app itself never logs SDK responses or exceptions.

The QQ login button image at `app/src/main/res/drawable/ic_qq_login.png` is Tencent's unmodified blue 24×24 asset from <https://wiki.connect.qq.com/wp-content/uploads/2021/01/bt_blue_24X24.png>, referenced by the official [visual assets page](https://wiki.connect.qq.com/视觉素材下载). SHA-256: `24281ae0688269d8888fb96b92095ad868a6e48e530ebcdff4af720c4ff34d48`. Its QQ branding remains Tencent's property and is used for the QQ login entry.

## Optional legacy Fragment type: compilation only

Tencent exposes `android.support.v4.app.Fragment` in optional overloads of `Tencent.login`. Even when the native `Activity` overload is selected, javac resolves those overload types. The application itself uses native `android.app.Activity` and does not call any Fragment or sharing APIs.

- Vendored file: `compile-only/support-fragment-28.0.0.jar` (the unchanged `classes.jar` extracted from the official Google Maven AAR).
- Source: <https://dl.google.com/dl/android/maven2/com/android/support/support-fragment/28.0.0/support-fragment-28.0.0.aar>.
- AAR SHA-256: `3772fc738ada86824ba1a4b3f197c3dbd67b7ddcfe2c9db1de95ef2e3487a915`.
- JAR SHA-256: `b16d6f097e58557d795d47120cdea459daa3cd25375fffce2e0e9d7fbfb41add`.
- License: Apache License 2.0, as specified in the [official artifact POM](https://dl.google.com/dl/android/maven2/com/android/support/support-fragment/28.0.0/support-fragment-28.0.0.pom); see the adjacent `compile-only/LICENSE-support-fragment.txt`.

It is **not** packaged in the APK. Gradle and `scripts/build-apk.ps1` both put it on the compiler class path only. Introducing a Fragment-based QQ integration or QQ sharing would require a separate dependency and runtime review. The manual APK script also copies the QQ JAR's `assets/` files; compiling only its Java classes is insufficient for H5 fallback.

Compilation and APK inspection do not establish real authorization success. Validate a matching package/signature on a real device with the approved application, including cancellation, client absence, H5 fallback where authorized, and server-side verification. QQ uses fixed request codes; a cancelled or timed-out authorization keeps an in-memory attempt boundary until its old result is consumed. This prevents old authorization from completing a new attempt. If the provider never returns a result, finish its authorization page or restart the application before retrying.
