disposition: fix

**Additive factual correction · 2026-10-23:** M2 is withdrawn as a reviewer misread, not a product defect or a required implementation change. I reopened the exact untransformed `phone-local-denied.png` at original resolution and confirmed the blue “刷新 / 选择 / + / 选择照片” row, with “选择照片” around y=630 in the 1080 × 2424 capture, above “授权浏览 / 权限设置”. I then reopened the exact large-font, dark and landscape denied captures at original resolution; all also visibly contain the picker row. This supersedes the initial Permission recovery matrix row, the ceiling's empty-toolbar claim, and material fix M2 below. The original review text is retained as history. M1 remains the sole open material finding, so the disposition remains **fix**. An additional visible-bounds and actual-picker-click QA assertion is useful confirmation, not a condition for resolving a nonexistent visual defect.

Inputs: no new album comp or QUALITY BAR card was supplied; this is the approved code-led extension of the incumbent world. The v2 photo-editor board was opened only as an inherited-editor reference. Deeper implementation files were sampled rather than exhaustively audited. No HTML/CSS detector ran; this review applies native Android conventions.

## persistence

Pass at the review checkpoint. PRODUCT.md and DESIGN.md exist and record the incumbent 82 identity; the approved local-album REQUIREMENTS.md and ASSET-INVENTORY.md record the new scope. The opening contract in LocalAlbumPanel.java agrees with those requirements. The 83 delivery record still belongs to the post-review documentation step.

All 28 required review captures were opened, including every landscape capture at original resolution. All raw/review SHA-256 values match capture-manifest.json. Each review PNG has origin metadata, identical IHDR dimensions and identical compressed image data to its raw source: portrait 1080 × 2424, landscape 2424 × 1080. hero-repro.png preserves the phone local-app pixels. These are valid evidence of the rendered states. The transient notices in denied/authorized/results captures are actual overlays and do not invalidate them. The PNG filename's “adjustments” suffix does not certify a visible adjustments tab: its captured state is the filter editor after the recorded assertions.

The evidence is bound to version 83, APK SHA-256 fcd0b31a7d4e201a195af070c0889bbf598fbc354f57709ff80b36f95b100550. Native-matrix.json reports 273 repeated assertions across four configurations, 1,092 executions, with raw-fixture restoration; these are not 1,092 distinct scenarios. The focused host provenance reports 389 tests. Neither quantity overrides the visible layout failures below.

## fidelity

| Element / promise | Assessment | Evidence |
| --- | --- | --- |
| TYPE | match | System sans, native sp scaling, clear weight hierarchy; large-font captures preserve source labels, destination options and dialog actions. Long filenames truncate. |
| MATERIAL | match | Real photo thumbnails, flat native surfaces, Canvas line icons; synthetic colored PNG/JPEG fixtures are plainly QA content. No new production raster or imitation material is owed. |
| GROUND | match | UiPalette retains cloud-white #F7F8FA and graphite #22252A with the incumbent dark editor. The captures show that retained field and temperature. |
| Four root destinations | match | Visible order is 预设 / 同步 / 相册 / 设置. The added local root is separate from camera synchronization as approved. |
| Two-source FIRST VIEWPORT | match in portrait; contradicted in compact landscape | Portrait shows App 内 / 手机, scope, count, date groups and identifiable images. Landscape stacks the title, tabs, scope, toolbar and count above a list reduced to a shallow strip; no complete first photo and filename are visible. This affects local-app, phone-authorized and app-only-results. |
| Permission recovery | contradicted | phone-, large-font-, dark- and landscape-local-denied show an empty toolbar row: “选择照片” is absent, although the recovery copy directs the user to it. “授权浏览” and “权限设置” remain visible. The code creates the picker and the probe searches text, but that does not establish a visible/tappable picker in these renders. |
| Existing photo-first editor | match | JPEG and PNG working-copy views retain the large complete image, compact dark controls, left four-grid entry, horizontally continued category names, source tabs, filter intensity and the four editing tools. Landscape moves the tools to the right and remains readable. |
| Explicit save destination / extra copy | match | All four destination dialogs show App-only versus phone, verified reuse copy, an unchecked explicit new-copy control, and reachable Cancel/Save actions. The native dialog is appropriate for this consequential choice. |
| THESIS / STORY | match except recovery and compact-height browse failures | The available source/import/save code and native evidence support choose → durable private copy → existing editor → explicit publication. LocalPhotoRepository uses files storage; PNG conversion has a white-background confirmation; the source remains retained. |
| FORM | match | Approved narrow extension inside an established application. A new-world concept seed and new comp approval are not applicable here. |
| Truth and behavioral bounds | match within supplied evidence | Save targets are frozen per request, scene preferences commit on task start, APP_ONLY avoids publication, and publication receipts verify saved bytes. Root switching does not call live stop. Physical camera/live transport, sensor RAW fidelity, FPS, color calibration and TalkBack certification remain outside the evidence. |

## ceiling

Not reached for compact-height browsing. Android's adaptive composition is available but unused in the new local root: the new controls consume most of the landscape height and the inherited floating navigation overlays the remaining photo strip. The denied state also spends space on an empty toolbar row while its permission-free recovery action is not visible. No additional ornament, font family, raster asset, animation or replacement visual world is warranted. Static captures do not certify motion.

## material_fixes

1. **M1 — FIRST VIEWPORT / Android compact-height adaptation:** compact the new local-album header/control stack and reserve useful photo space above navigation in landscape; `landscape-local-app.png`, `landscape-phone-authorized.png` and `landscape-app-only-results.png` must show at least one complete identifiable thumbnail row with filenames, while `landscape-local-denied.png` must show its recovery guidance without clipping. Keep controls at 48dp hit size; address this local surface without redesigning the other roots.
2. **M2 — recovery / coverage:** preserve a visibly rendered, tappable “选择照片” system-picker action in the Phone source when media permission is absent, matching the screen's own copy; confirm its visible bounds and actual click path in the denied captures for all four modes. A text-presence assertion alone does not resolve the blank toolbar shown here.

## keep

Keep the cloud-white/graphite identity, real imagery, four-root semantics, permission-independent App 内 library, image-dominant editor with its left four-grid and horizontal categories, retained originals/drafts, and explicit destination/reuse/new-copy choice.
