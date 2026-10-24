# JPEG import native regression

This instrumentation runs only on a task-owned `ranchu`/`goldfish` emulator, targeting `cc.huevia`. It uses real MediaStore, the actual repository, Android decoder, editor, border settings freeze and renderer. No physical device or production service behavior is certified.

`fixtures/phone-screenshot.jpg` is the user's unmodified error screenshot, not the original photo selected inside it. SHA-256 is checked at runtime. Its complete primary JPEG is followed by 24 bytes. Additional variants (an ending EOI, another image, truncated primary and missing frame) are explicitly synthetic test data.

Build with `build.ps1`, then install the probe at `.tooling/jpeg-import-90/probe/jpeg-probe.apk` into the isolated emulator. Install the existing 89 app and run:

```powershell
adb -s emulator-5582 shell am instrument -w -e mode baseline cc.huevia.jpegqa/.JpegImportProbe
```

Upgrade that emulator to 90 and run with `-e mode fixed`. Reports are written under `/sdcard/Android/data/cc.huevia/files/jpeg-import-90/{baseline,fixed}/`. The fixed mode also produces `editor-after-import.png` and `border-output.JPG`. Each invocation deletes only its own inserted MediaStore URIs and verifies the delete count. Private working copies remain in the disposable read-only emulator overlay until shutdown.

The baseline expects the two compatibility cases to be rejected; the fixed mode expects them to be accepted with exact source bytes. A separate diagnostic records that both versions' Android decoder accepts deliberately shortened entropy with an injected EOI; that is preexisting behavior, not a claim that such a file is complete. The original 89/90 comparison and final hashes are preserved in `docs/validation/jpeg-import-90/`.
