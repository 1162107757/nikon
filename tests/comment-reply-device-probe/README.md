# Comment reply native probe

Build with `./tests/comment-reply-device-probe/build.ps1`. It uses the existing Android 35 / JDK 17 toolchain and development signature, with isolated build output; it does not rebuild the product APK.

Only use an explicitly selected disposable emulator. The probe rejects non-emulator hardware, product versions other than 59, a mismatched `-e sha`, or an existing real account/session/camera operation. For this delivery the dedicated device was `emulator-5560`:

```powershell
adb -s emulator-5560 install -r tests/comment-reply-device-probe/comment-reply-probe.apk
adb -s emulator-5560 shell am instrument -w -e sha 774ac5a78d599b33105d98e349192b226d20946819a4017041b740ff15f43828 -e theme light com.nikonpreset.usbprobe.replyqa/.ReplyProbe
# Repeat with -e theme dark.
```

The product's actual `CommunityPanel` comment rows and composer are mounted in an isolated scroll container. Synthetic identities live only in memory, and an injected transport captures request JSON without publishing anything. It verifies root/selected-recipient labels, unavailable targets, composer naming and binding, same-thread target changes clearing a draft, and actual submitted root/target/text. Appearance and the previous in-memory session are restored. Screenshots cover the comment section rather than the full preset detail screen. No QQ, real user, live message delivery, physical phone or camera is exercised.

Final evidence: [light](../../docs/validation/comment-replies-59/light/report.json), [dark](../../docs/validation/comment-replies-59/dark/report.json). Each passed 18 checks. The helper was uninstalled and the task-owned emulator stopped.
