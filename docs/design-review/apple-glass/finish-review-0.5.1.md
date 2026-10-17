# 0.5.1-beta native detail comparison finish review

disposition: ship

Recorded 2026-10-17 (Asia/Shanghai) for version 0.5.1-beta / versionCode 18. This is a narrow code-led extension of the approved native cloud-white/graphite surface, FORM seed `b120d374`. The independent reviewer inspected the four captures below and `MainActivity` / `HoldToCompare`, returned SHIP within the phone UI scope, and found no material fixes. PRODUCT.md, DESIGN.md, the surface brief, sidecar and template documentation have been synchronized with this outcome. Earlier evidence retains its own version scope.

Artifact: `dist/nikon-usb-0.5.1-beta-debug.apk`, 2,708,089 bytes; SHA-256 `01941168d2b2e40f0aa9900fc93dab2fe19c8577fdb1dd86eb3560904becb015`. It was installed over the previous release on OnePlus PKG110 / Android 16 owner user 0 while retaining data, versionCode 18 was read back, and the installed `base.apk` checksum matches delivery. The phone user was using another app, so further foreground testing stopped. Comparison gesture evidence is from the Pixel 9 / Android 17 emulator only.

Reviewed captures, all under `.impeccable/review/`:

- `phone-apple-v051-effect.png` — detail effect with the bottom/end comparison capsule.
- `phone-apple-v051-held-original.png` — original template while held, with the corresponding badge.
- `phone-apple-v051-released-effect.png` — restored effect after release.
- `phone-apple-v051-dark-large-effect.png` — dark theme at 1.3 font scale.

The emulator passed 15 functional assertions in [the gesture log](../../device-review/compare-0.5.1-gesture.log), including changed image-region pixels while held and exact restoration on release, move-out/back, CANCEL, HOME/resume, Enter toggling and dark/enlarged-text rendering. No complete test suite was rerun for this UI-only patch. The 8,212 core checks, 1,108 included preview checks and 3,487 probe checks belong to 0.5.0; they are not 0.5.1 results. No TalkBack, tablet, physical-gesture, performance or camera certification is claimed.

## persistence

Pass. The existing approved world is retained in PRODUCT.md, DESIGN.md and the surface brief, with FORM seed `b120d374`. This local control is code-led and does not establish a new visual world or comp obligation. Documentation records the current behavior and the limits of the evidence.

## fidelity

Pass. The 48dp-high capsule has 12dp bottom/end margins, readable 13sp system-medium text and a restrained dark surface. It avoids the face in the supplied template and preserves the image's role. Effect and original share the same CENTER_CROP view; the crop stays stable and the badge correctly changes between “模板模拟” and “模板原图”. Dark theme at 1.3 font scale remains clear.

MainActivity prefetches the original on the image worker into the shared LRU and rejects obsolete template versions and page epochs. HoldToCompare enables the button only when both bitmaps are ready, switches without image I/O on DOWN and applies no hold transition. Source guards restore the effect on move-out, cancellation, extra pointers, focus loss, pause, hiding and detach. Keyboard/accessibility click uses a separate toggle path. Keyboard activation was exercised; the accessibility source path is not a TalkBack result.

## ceiling

Reached for the captured Pixel 9 / Android 17 phone UI scope. The four valid captures and 15 emulator assertions support the immediate comparison behavior and dark/enlarged-text presentation. Physical-phone evidence covers data-preserving installation and version 18 only. TalkBack, tablets, physical comparison gestures, performance and camera behavior remain outside this verdict.

## material_fixes

None.

## keep

Preserve direct DOWN-original / UP-effect behavior, fixed crop, the quiet bottom/end capsule, explicit state badges and the original-template viewer in Settings.
