# Photo image upgrade native probe

Standalone framework instrumentation, loaded into the installed candidate's class loader. It opens production `MainActivity` / local photo workspace, real detail page, histogram overlay and unified export settings. It never clears app data or runs on a physical device.

Build with `powershell -ExecutionPolicy Bypass -File tests/photo-image-upgrade-device-probe/build.ps1`. After the root agent installs the agreed immutable candidate on the reserved **emulator-5586**, run:

```powershell
powershell -ExecutionPolicy Bypass -File tests/photo-image-upgrade-device-probe/run.ps1 -Mode phone -CandidateApk D:/absolute/candidate.apk
```

Repeat `font1.3`, `dark`, and `landscape` serially. The runner verifies hardware, reads actual candidate version, checks installed APK SHA from instrumentation, pins probe inputs and restores font/night/rotation/permission settings. It only installs this probe, never the production candidate.

Coverage:

- A unique 2401×1601 high-frequency JPEG is decoded at true 100%. Tile pixels are compared to the complete JPEG decoder output, and more than 500 real framebuffer pixels are mapped back to source coordinates, with per-point evidence in `pixel-mapping.json`. This detects replacing the source with the editor's 1280px preview.
- Fit / physical 1× / 2×, minimap drag, edited/before identical geometry and viewport, exclusion of added borders, whole saved JPEG with baked border and unavailable before state.
- Real explicit same-photo rebind invalidation, scope callback guard, private detail PNG cleanup. The saved-version fixture uses the app's recognized `effect-<sha>__Effect.JPG` naming with no original association, exercising the missing-link fallback.
- Histogram opening/closing preserves photo and dock rectangles. RGB and both warnings work without changing `Editor.current` pixels, draft identity, original bytes or actual encoded JPEG bytes; the visible diagnostic copy may change its bitmap reference.
- A `CountDownLatch` gates only the preview executor, a real exposure edit is queued, then the histogram is closed before releasing the worker. The final `Editor.current` must match a fresh render of the final parameter snapshot, detecting dropped in-flight edits. No main-thread blocking is used.
- Unified export defaults, share quality, custom defaults, empty/invalid/overflow/out-of-range inputs, valid controls and full defaults on reopening. It does not click the export confirmation or publish new MediaStore items; the backend export probe covers encoded specifications, publication and batch recovery.

All JPEGs/drafts are uniquely owned fixtures and deleted in `finally`; the report records integrity/absence and appearance preference restoration. Evidence is native, unedited PNG plus JSON under `docs/validation/photo-image-upgrade-91/<mode>/`. Screens show the shipping preset template photograph for UI context; its scaling is only a fixture choice, never a claim of recovered optical detail. No camera or physical phone validation is claimed.

`hero-editor-closed.png`, `hero-editor-expanded.png`, `hero-detail.png`, and `hero-export.png` are byte-identical copies of the corresponding raw captures. Their fixture comes from the shipping `res/drawable-nodpi/preset_template.png`; none is a recreated screen.

Use `-ProgressOnly` with a mode for the independent progress surface (evidence `<mode>-progress/`). This runs a real three-photo `PhotoPresetBatch` in a UUID `ContextWrapper` private files/cache/external tree, always `APP_ONLY`. The account-check callback pauses only the background worker on the second item: the production ledger naturally contains completed / processing / waiting rows. The real progress dialog is captured, its stop button is clicked, and the test verifies completed output survives while original fixture bytes remain intact. It removes the whole checked private scratch tree and does not publish to MediaStore. This optional path skips the editor/detail/export-input suite rather than repeating it.
