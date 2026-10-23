# 81 / 0.15.0-beta validation

Final APK: `dist/huevia-0.15.0-beta-debug.apk`, 3,487,341 bytes. SHA-256 `7861ae7163d17a5651a8c41b25ddf1b368e868cd326bdcc04a2bd72d5df7aa80`. Package `cc.huevia`, min SDK 26, target 35, existing signer `04db0f9d3d76c9831a0778cfabc1a0aaab984e05398da16eb644950ccfa0ae31`, signature schemes 2 and 3.

## Current native evidence

| Directory | Font / orientation | Result |
| --- | --- | --- |
| [phone](phone/report.json) | 1.0 / portrait | PASS 172 |
| [large-font](large-font/report.json) | 1.3 / portrait | PASS 172 |
| [landscape](landscape/report.json) | 1.0 / landscape | PASS 172 |

All 516 assertion records belong to the final SHA above. Each directory retains the actual environment, restoration, probe provenance, report and ten native screenshots. This is an isolated read-only Android emulator using the existing synthetic app template and explicitly offline local preset/catalog fixtures. It exercises real Views, Canvas, MotionEvents, JPEG/EXIF, private drafts/recipes and MediaStore. It does not certify physical phones, camera transport, the live catalog, sustained performance, TalkBack or colour calibration.

Covered: real crop corner gesture, original/free/common ratios, rotate, straighten, tentative cancel/apply, one-step undo/redo, complete PED3 identity and old draft migration, recipe naming error/rename/account isolation, individual and batch application preserving each target crop, original-pixel crop dimensions, EXIF orientation 1, batch and single-render JPEG equality, exact saved-version restoration, and source bytes/times retained.

Explicit repeated export creates a new public copy with fresh capture/modification dates while keeping older copies and private rendered JPEG bytes. Decoded public/private pixels are equal: date stamping does not recompress JPEG data. Receipt status verifies the public bytes. Altered public copies block deletion; intact copies can all be removed under the actual exclusive photo-delete lease while retaining the private render cache.

The user-reported photo-live crash was reproduced through the actual main navigation. [Before-fix stack](photo-live-crash/before-fix.txt) shows an empty project ID reaching UUID validation from `updateLivePresetProgress`. The UI now treats no selected project as no enabled automatic preset, while storage validation remains strict. Every native mode waits for the real project store to initialize, enters/leaves the empty-project live page three times and forces the same status refresh. These 17 records per mode are included in 172, not added again. A separate [manual main-navigation and cold-restart confirmation](photo-live-crash/after-fix-main-navigation.json) records settled screenshots and surviving processes; its uiautomator idle limitation is explicit and it adds no records to 516.

The fresh review found M1: queued recipe A could apply before B and cause B to be dropped by the stale-snapshot guard. The correction adds latest-request identity while preserving photo/account/snapshot checks. Five additional records per mode hold real editor IO, queue different A→B recipes, release work, assert B wins with unchanged geometry and one undo step; normal undo/redo then runs. The [initial review](FINISH-REVIEW.md) is preserved; the [fix verdict](FIX-VERDICT.md) scores M1 resolved / SHIP at that finding and fix-batch regression scope.

## Host evidence

These are separate units, not one combined metric: [editor](host-editor.txt) PASS 34,780 legacy editor checks, 644,888 manual behaviour/migration/identity checks, 1,158 composition/recipe geometry/storage checks; [batch](host-batch.txt) PASS 609 plus 82 source-link checks; [project automatic presets](host-project-auto.txt) PASS 48. Android codec and gallery behaviour are certified only by the native evidence above. Historical 80 spatial checks were not rerun or counted for 81.

## Visual and historical evidence

The builder inspected the initial 27 captures in one batch and applied one correction batch (large-font save caption, inline name error, crop action tint and export wording). Final confirmation was bounded to the same states; the later user-reported crash added the empty-project state. Thirty final native state captures plus a hero duplicate are in `.impeccable/review/photo-composition-recipes-81/`; their manifest pins raw origin hashes and the final APK. Review copies include origin metadata; raw files remain unchanged. Metadata scan: 31 rasters, zero missing. Native Views skip the HTML/CSS detector.

`initial/` retains the earlier `af0b41…f067` candidate with 146 records per mode. `pre-photo-live-fix/` retains `936b31…18b12` with 150. `before-review-fix/` retains `f5b344…bdd03` with 167 and the initial review's capture manifest. These are candidate history, not final-SHA evidence, and are not accumulated into the 516 count.

## Publication boundary

User authorized direct beta upload. The immutable `huevia81-reviewed` operator stages the exact APK as the sole addition to the downloaded production archive (40 → 41 files), preserving every existing runtime byte and Unix mode. Read-only preparation passes 22 syntax checks and 57 runtime/artifact tests; serialized test execution avoids the host memory exhaustion recorded by the discarded `huevia81-livefix` candidate. Archive guards pass 8 and diagnostic/preflight guards pass 2. [Publication](PUBLICATION.md) and its [sanitized receipt](publication.json) confirm release `8448462f-6610-4800-b057-6f045ce03cda`, revision 2, published beta, with exact final APK bytes and metadata verified on both domains. Existing release rows and function configuration were preserved. The page's HTTP HTML/script and exact metadata query were also checked; no rendered-browser verification is claimed. The owned disposable emulator was closed after validation.
