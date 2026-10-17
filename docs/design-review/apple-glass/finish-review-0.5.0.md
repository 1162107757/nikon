# 0.5.0-beta native phone UI finish review

disposition: ship

Recorded 2026-10-16 for version 0.5.0-beta / versionCode 17. The independent reviewer returned the five sections below after inspecting the supplied captures and relevant native source. The persistence paragraph records the state at review time; PRODUCT.md, DESIGN.md, the surface brief, sidecar and template documentation were then synchronized with this scoped outcome. Earlier version evidence retains its original scope.

Artifact: `dist/nikon-usb-0.5.0-beta-debug.apk`; SHA-256 `48507b4136aba51194da8715d79ad2374c759ddd6cb2bbb522aa50daacb46369`. The APK was installed over the previous release on OnePlus / Android 16 owner user 0, preserving the existing AgfaOptima preset. The installed `base.apk` checksum matches delivery. Physical-phone evidence is installation, retained data and preset home; feature captures otherwise come from Pixel 9 / Android 17 emulator UI. `tests/run.ps1` passed 8,212 checks, including 1,108 preview checks; `tests/run-probe.ps1` passed 3,487. These counts are separate and do not accumulate older releases.

Reviewed captures, all under `.impeccable/review/`:

- `phone-apple-v05-phone-home.png` — OnePlus / Android 16 preset home.
- `phone-apple-v05-preset-home.png` — emulator preset home.
- `phone-apple-v05-identity-detail.png` — synthetic identity NCP detail.
- `phone-apple-v05-tone-detail.png` — synthetic tone NCP detail.
- `phone-apple-v05-dark-settings.png` — dark Settings.
- `phone-apple-v05-dark-dialog.png` — dark native appearance choice.
- `phone-apple-v05-template-picker.png` — template choices.
- `phone-apple-v05-original.png` — explicitly unapplied original template.
- `phone-apple-v05-custom-template.png` — selected custom template.
- `phone-apple-v05-custom-persisted.png` — persisted custom template.
- `phone-apple-v05-wireless-detected.png` — synthetic wireless read-only detection.

Motion was inspected in source only: 220ms page and 180ms photo entrances, interruption cleanup and system-disabled behavior. Static images do not certify timing or smoothness. No real-camera registration or photographic effects, tablet, enlarged-font, TalkBack or performance result is claimed. Source inspection includes `MainActivity`, `AppAppearance`, `PageTransitions`, `PresetPreviewRenderer` and `UiPalette`; the custom-template implementation publishes and verifies the content-addressed JPEG before switching preferences, and preview loading retains explicit retry actions.

## persistence

Pass. PRODUCT.md, DESIGN.md and the surface brief exist and describe the implemented world. FORM records seed `b120d374` and the prior user approval. This extension is code-led; no new comp obligation applies. Validation paragraphs still await this review’s recorded outcome.

## fidelity

| Element | Result | Evidence |
|---|---|---|
| TYPE | match | Native system lettering, clear title/body hierarchy and readable controls across supplied light/dark captures. |
| MATERIAL | match | Solid grouped surfaces, photographic template imagery, restrained navigation material and consistent drawn icons. |
| GROUND | match | Cloud-white and graphite surfaces agree with UiPalette and the committed direction. |
| THESIS | match | Three root destinations remain visible; connection controls stay in Settings. |
| OWN-WORLD | match | Existing rounded shapes, compact source controls and blue actions are preserved. |
| STORY | match | Independent wireless detection reports nine empty slots and explicitly states registration is unavailable; it offers USB recovery. |
| FIRST VIEWPORT | match | Image-led cards, quiet actual connection status and legible “模板模拟” badges. |
| FORM | match | Scoped extension of the approved native implementation; no replacement visual world. |
| Appearance | match | Dark Settings and native choice dialog retain contrast; source persists System/Light/Dark and applies the selected theme. |
| Template workflow | match | Identity/tone captures show different results from the same template; original is explicitly unapplied. Custom-template and persisted captures retain the replacement. |
| Motion | match within source scope | PageTransitions defines 220/180ms entrances, interruption cleanup and system-disabled handling; screenshots do not establish timing or smoothness. |

All eleven supplied captures exist, match their labels and contain valid phone UI evidence.

## ceiling

Reached for the reviewed phone UI scope: OnePlus/Android 16 home and Pixel 9/Android 17 emulator feature captures. No material craft-floor defect found. The wireless fixture remains synthetic evidence; camera registration, tablet behavior, enlarged-font layout, TalkBack and animation performance are outside this verdict.

## material_fixes

None.

## keep

Preserve the quiet grouped Settings, compact source selection, prominent template imagery and explicit distinction between phone approximation, original template, reference photographs and actual camera results.
