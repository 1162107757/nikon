# Filter navigation and live preview — 82 / 0.15.1-beta

Scope: the user-supplied live default-preset flicker screenshots, inline horizontal categories, retained leftmost GRID entry to the full filter picker, and category/result synchronization inside that picker. UI only; no photo/preset processing, export timestamp or geometry changes.

Final artifact: `dist/huevia-0.15.1-beta-debug.apk`, 3,487,341 bytes, SHA-256 `2543241174c2c7b6656b08b4142d3c3812d095b2ecd732836a11ab9a3c173987`, `cc.huevia`, versionCode82, original signer `04db0f9d3d76c9831a0778cfabc1a0aaab984e05398da16eb644950ccfa0ae31`, v2/v3 signatures. The finish verdict and publication receipts determine final status separately.

## Native evidence

| Mode | Font / orientation / theme | Current checks |
| --- | --- | --- |
| [phone](phone/report.json) | 1.0 / portrait / light | PASS163 |
| [large-font](large-font/report.json) | 1.3 / portrait / light | PASS163 |
| [landscape](landscape/report.json) | 1.0 / landscape / light | PASS155 |
| [dark](dark/report.json) | 1.0 / portrait / dark | PASS163 |

644 assertion records on the same final SHA. Actual native MotionEvents select online portrait, black-and-white, All, local landscape, inline categories and GRID; a real horizontal gesture reveals later labels. Assertions check exact matching entry identities and counts, not just selection colour. The leftmost GRID opens the full picker while preserving category. At least two complete category labels are visible, including the narrower landscape dock. Environment and restoration files record actual display/font values and restore night mode.

For live previews the real renderer and worker produce visible images, then a controlled worker gate plus LRU eviction and adapter rebind model cache pressure. Already displayed Drawable identity and card top positions remain unchanged. `live-loading.png` records the first opened live picker, which can already have completed previews; it is not a claim that all placeholders were captured. Live None selection checks the bound click action and callback exactly once via `performClick`; pointer injection for that separate card was inconclusive in an early probe. It is not included in the physical-touch claims above.

The finish review found M1: retained images could override a confirmation failure with READY. Failure states now clear the image and remain authoritative. Final native checks drive the actual choose/read ERROR branch using temporary in-memory record removal, restore that record and recover through the actual retry/renderer. The actual choose/profile UNSUPPORTED branch is reached by deliberately seeding a stale READY thumbnail onto the known unsupported offline fixture. Both remain disabled after adapter rebind, stale imagery clears, and retry is visible only for ERROR. This explicit UI-state injection does not certify unsupported rendering or change preset files/production content. See each report's `confirmationFailureFixture` and [fix verdict](FIX-VERDICT.md).

The old 81 APK reproduced the cache-pressure image-loss failure: [baseline report](../photo-filter-navigation-81/phone/report.json). Its offline-fixture category touches already passed; the user's physical category failure was not reproduced on their hardware. The revised implementation centralizes category application and displays actual filtered classification/count; the current tests exercise that result. `early-probe-phone/` retains an early 5adbb candidate probe failure. `before-review-fix/` archives the superseded 61d8 candidate's 560 passing records and original manifest; its uncovered M1 is recorded in the first review. Other intermediate runner failures involved the additional None pointer check and are not passing evidence. Final probe provenance is retained per mode.

All evidence is an isolated read-only emulator with an existing synthetic application template and explicitly offline real research/hand-authored preset fixtures. It does not certify a physical phone, camera transport, live production catalog, sustained frame rate, colour calibration or TalkBack. Version81's 516 broader edit/export checks and older host suites were not rerun or accumulated into 82.

## Review and release

Builder opened the complete pre-fix 28-state matrix in one batch, then confirmed the four final inline and live-stable captures after M1. Review copies in `.impeccable/review/photo-filter-navigation-82/` include final raw-origin hashes and APK identity; metadata scan: 29 rasters including the hero duplicate, zero missing. Native Views skip the HTML/CSS detector. No new production image or icon is introduced. Fresh finish review is recorded in `FINISH-REVIEW.md`; the same reviewer's `FIX-VERDICT.md` retains the M1 finding scope.

The same reviewer reopened all 28 final captures plus hero and scored M1 resolved, remaining clear, disposition SHIP for that fix and its recorded regression batch. This does not enlarge the initial review or native evidence into whole-app/hardware certification. The required documenter merged the implemented state and evidence into the persistent system and editor documentation. [Source snapshot](source-snapshot.json) records 178 source/probe files after the build, with only the version, two UI sources and three probe files differing from the published 81 snapshot; it is not reproducible-build certification.

Read-only `huevia82-reviewed` preparation passes 22 production syntax checks and 57 runtime/artifact tests, with separate 8 archive guards and 2 diagnostic/preflight guards. Only the new APK is added to the downloaded production function archive, 41 →42 members; all previous runtime bytes, Unix modes and configuration are guarded. The earlier `huevia82-final` stage pins superseded 61d8 and is not deployable evidence for the final artifact. No deployment or publication is inferred from preparation checks. Final public status belongs to `PUBLICATION.md` and `publication.json` when created.

Published beta release `4c9aee32-1c86-4482-a68e-9c16f583406a`, revision 2. [Publication record](PUBLICATION.md) and [sanitized receipt](publication.json) confirm that both the primary and CloudBase origins return the exact final signed APK and latest metadata. The actual download HTML/script and exact page metadata query identify 82. Existing release rows remain unchanged. CUA initialization was unavailable; only the HTTP/page-query result is claimed. [Download beta](https://huevia.cc/preset-library/download/?channel=beta). The task-owned read-only emulator was closed after final native verification and review.
