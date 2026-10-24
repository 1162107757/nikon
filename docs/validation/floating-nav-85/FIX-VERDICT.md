# Floating navigation — corrected candidate 85

**Verdict: PASS / SHIP for the requested floating-navigation change. M1 and M2 are closed.** No remaining blocker was found in the two fixes or their direct navigation regressions. This is the single confirmation pass after the first-round FIX review.

## Candidate and evidence

- `0.17.1-beta`, versionCode `85`, 3,532,397 bytes.
- APK SHA-256: `f9e193d0fdae1603e57ed933b1656e316216effbce930d8f24f9cbf2bb8dce48`.
- `matrix.json`: **PASS**, completed `2026-10-24T00:15:05.1215339Z`, `sourcesStable: true`; all five reports identify this SHA.

| Mode | Native assertions | Result |
|---|---:|---|
| Phone, 411 × 923dp | 364 | PASS |
| Large font, 1.3× | 364 | PASS |
| Landscape, 923 × 411dp | 365 | PASS |
| Dark | 364 | PASS |
| Tablet, 853 × 1200dp | 364 | PASS |
| **Total** | **1,821** | **PASS** |

The first candidate's findings and failed run remain historical in `FINISH-REVIEW.md` and `first-candidate-0a38/`. They are not evidence against this corrected SHA.

## Fix confirmation

### M1 — Landscape authorization clipping: CLOSED

`LocalAlbumPanel.navReserve()` now uses 104dp plus 24dp-per-font-scale growth only below 600dp width; wider rail windows retain the prior 92dp plus 20dp growth.

In `../orange-ui-85/local-album/landscape/local-denied.png`, the complete central authorization button, rounded lower edge, guidance, and picker are visible. The report records the authorization target at `[1318,648,1598,774]` inside the uncovered area `[513,263,2403,1017]`; the formerly failing complete-target assertion now passes. The landscape local-album suite completes all 365 assertions.

### M2 — Compact narrow preset FAB/navigation overlap: CLOSED

`MainActivity` now selects the 16dp FAB margin and 76dp grid reservation only when the window is compact **and** at least 600dp wide. Compact windows with a bottom pill retain 104dp FAB clearance and 108dp list padding, with matching font growth.

`compact-narrow.png` and `compact-narrow.json` directly exercise a native **440 × 380dp**, 320dpi window. The preset FAB is `[724,396,832,504]`; the Settings target is `[610,544,774,670]`. All four navigation targets start at y=544, leaving a **40px / 20dp** vertical gap after the FAB. Each navigation target measures **164 × 126px / 82 × 63dp**, exceeding 48dp in both dimensions. The native geometry report confirms no FAB intersection with any navigation target. The screenshot confirms four clear labels, a detached bottom pill, and the FAB above it. This supplement is a geometry check, not a sixth full local-album suite.

## Direct regression review

Inspected the corrected native `presets.png`, `local-app.png`, and `settings.png` for phone, large-font, and dark modes, plus landscape/tablet presets and landscape denied-photo guidance. The orange identity, warm selected capsule, four existing icons/labels, safe bottom gap, and separate sync/local-album destinations remain intact. Phone, large-font, and dark FABs remain separated from the pill. Wide windows retain the rail. Existing native root tap, order, complete-target, album-row visibility, picker, and editor-navigation assertions pass.

Scope remains floating navigation and its adjacent reservations. No additional application scan or aesthetic iteration was performed. This verdict uses emulator screenshots, native geometry, and source inspection; it does not claim physical-phone, physical-camera, live-network, TalkBack, or measured frame-rate certification. Device size, density, rotation, and font settings were restored after the compact-window supplement.
