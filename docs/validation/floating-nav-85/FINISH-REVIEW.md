# Floating navigation finish review — first candidate 85 (historical)

**Verdict: FIX before shipping this candidate.** The restored phone pill is visually consistent with the approved orange application, but its surrounding space has two concrete adaptive-layout defects.

This is the preserved first-round verdict for SHA `0a38…5378`. It does not describe the later corrected build. Evidence is frozen under `first-candidate-0a38/`; the current working source and the live `matrix.json` may refer to the corrected build.

## Candidate and scope

- Version: `0.17.1-beta`, versionCode `85`.
- APK SHA-256: `0a38e1161d083e8785a0a0ca75df6cb68905f18132846e979bf20cd53bed5378`; 3,532,397 bytes.
- Reviewed: floating navigation, four root labels/icons and targets, safe-area placement, adjacent preset/album FABs, and the changed local-album bottom reservation. No general application redesign or whole-app defect search.
- Evidence: existing native instrumentation on the task-owned emulator. This is not physical-phone, camera, live-network, TalkBack, or frame-rate certification. Earlier version 84 evidence does not certify version 85.

## Findings

### P1 — Bottom reservation clips the landscape authorization action

`LocalAlbumPanel.java:195` reserves `104dp + font growth` even when `MainActivity.java:672` has chosen the left navigation rail. The guidance ScrollView loses this unnecessary bottom area (`LocalAlbumPanel.java:181`). In the captured 923 × 411dp landscape window, the bottom of the central **点击获取授权** action is visibly clipped. Its complete touch target is unavailable in the initial viewport.

This is verified by `first-candidate-0a38/native/local-album/landscape/failure.png` and `first-candidate-0a38/native/local-album/landscape/report.json`, whose failing stage is `local-action-entire-touch-target-visible-above-navigation-central-authorization` after 80 checks. An additional unchanged copy is `first-candidate-0a38/failure-original.png`. The generic runner message “native photo editor verification failed” is not the cause: the report and screenshot identify a real local-album layout failure.

Use a rail-specific reservation in wide windows and retain the full reservation only where the floating bottom bar exists. Recheck the denied-photo guidance and picker, including complete targets.

### P1 — Compact narrow windows can stack the preset FAB over navigation

`MainActivity.java:708` gives the preset FAB a 16dp bottom margin whenever `screenHeightDp < 480`. The navigation decision uses a different condition, `screenWidthDp >= 600`. Thus a window below 600dp wide and below 480dp tall receives both the floating bottom bar and the compact 16dp FAB margin.

At normal font size, the bottom bar occupies 14–90dp above the safe bottom. The FAB occupies 16–70dp and shares the bar's 24dp trailing edge in windows up to 408dp wide. It is added after the navigation and can cover the Settings area. This is a deterministic source finding; the existing 923dp-wide landscape screenshot uses the rail and does not exercise this window class.

Choose the FAB reservation from the actual navigation mode. A compact window with a bottom bar still needs the 104dp plus font-growth separation.

## Verified positives

- Phone 411 × 923dp and fontScale 1.3 both passed all **364** existing local-album assertions, archived under `first-candidate-0a38/native/local-album/{phone,large-font}/report.json`. Native root taps, order, targets, and root destinations passed.
- Inspected native `presets.png`, `local-app.png`, and `settings.png` under `first-candidate-0a38/native/local-album/{phone,large-font}/`. The pill floats above the gesture area, all four icons and Chinese labels remain clear, the warm selected capsule agrees with the approved palette, and the preset/album FABs remain above the bar in these captured modes.
- MainActivity applies system-bar, cutout, and IME safe insets to the enclosing shell; the 14dp pill gap is inside that safe area.
- The GlassBar keeps content sampling separate from its children, limits captures to 10Hz at 1/6 dimensions, and retains opaque fallbacks for older APIs, disabled animation, low RAM, battery saver, and capture failure. This is source verification, not measured performance certification.
- The wide navigation rail remains visually distinct from the requested phone bottom restoration; the landscape capture shows the four labels/icons and selection clearly.

## Evidence coverage and limits

The historical `first-candidate-0a38/matrix.json` completed **FAIL** at `2026-10-24T00:08:21.4672294Z`. Phone and large-font completed. Landscape stopped at the authorization-target failure, so only `local-app.png` and `failure.png` were produced there. Landscape presets, dark, and tablet evidence were not produced by this run and have no visual verdict for this candidate. This first matrix must not be described as passing.

Inspect the corrected candidate once, including the previously blocked landscape/dark/tablet modes and a compact window below 600dp wide. No additional aesthetic direction is required.
