# 0.5.2-beta native detail tone curve finish review

disposition: ship

Recorded 2026-10-17 (Asia/Shanghai) for version 0.5.2-beta / versionCode 19. This is a narrow code-led extension of the approved native cloud-white/graphite surface, FORM seed `b120d374`. The independent reviewer inspected the four final captures below and ToneCurveView, MainActivity and HoldToCompare. The sole first-pass material fix concerned reference-line and legend contrast; the reviewer scored it resolved after the correction and returned SHIP within the reviewed Pixel 9 / Android 17 phone UI scope, including dark theme at 1.3 font scale. This closes that finding rather than certifying every surface or device condition. PRODUCT.md, DESIGN.md, the surface brief, sidecar and template documentation are synchronized with this outcome; earlier evidence keeps its own version scope.

Artifact: `dist/nikon-usb-0.5.2-beta-debug.apk`, 2,712,185 bytes; SHA-256 `7fedfaf59fc3b2f6256fce1fbae9f08969ba4e364de91bdb957329e6ecaa503e`. The phone was disconnected and 0.5.2 has not been installed on it. The recorded OnePlus PKG110 / Android 16 installation of versionCode 18 remains 0.5.1 history.

Reviewed final captures, all under `.impeccable/review/`:

- `phone-apple-v052-curve-tone.png` — actual synthetic tone curve and shortened “原图” control.
- `phone-apple-v052-original-held.png` — unapplied template while held, with the original badge.
- `phone-apple-v052-curve-identity.png` — actual synthetic identity curve.
- `phone-apple-v052-curve-dark-large.png` — dark theme at 1.3 font scale.

The final APK passed eight native assertions in [the functional log](../../device-review/curve-0.5.2-functional.log): tone's 257 samples with representative outputs 10 / 172 / 240; enabled short label; DOWN-original; UP-effect; identity outputs 0 / 128 / 255; tone cache after switching; dark/enlarged text; and restoration of system settings. No full core or probe suite was rerun for this UI-only patch. The 8,212 core checks, 1,108 included preview checks and 3,487 probe checks belong to 0.5.0. TalkBack, tablets, physical gestures, performance and camera behavior are outside this verdict.

## persistence

Pass. The existing approved world and FORM seed `b120d374` are preserved. The local curve card and shorter photo control are code-led extensions, with no new visual-world or comp obligation. Canonical documentation records the actual implementation and bounded evidence.

## fidelity

Pass within the captures. The native card sits below the photo/name area, uses the card surface and 16dp corners, has a compact 188dp base height and grows with system text. A 2dp blue polyline connects all 257 actual normalized NCP samples without smoothing; input/output endpoints use 0–255 labels and the legend distinguishes the actual curve from the dashed linear reference. Both reference strokes now use secondary color at alpha 200, yielding about 3.44:1 against the light card and 4.74:1 against the dark card.

MainActivity parses the original hash-verified local NCP on the image worker through NcpPreviewProfile. A content-hash profile LRU holds at most eight items; pending work retains the latest target and page epoch. Unsupported or failed inputs show a retry state, and undownloaded online details have no chart. The curve does not imply camera rendering equivalence and is read-only. HoldToCompare's visible label is “原图”, with the 48dp target, fixed crop, immediate hold/release behavior and full keyboard/accessibility explanations retained. Native DOWN and UP were exercised; this is not TalkBack certification.

## ceiling

Reached for the reviewed Pixel 9 / Android 17 phone UI, including dark theme at 1.3 font scale. Four final captures and eight final-APK assertions support this narrow change. The physical phone was unavailable, and no broader surface, device, gesture, camera or performance certification is claimed.

## material_fixes

Resolved: the first-pass dashed linear reference and matching legend stroke had insufficient contrast at alpha 110 / 130. Both now use alpha 200. The reviewer inspected the recaptured final images, scored this sole finding resolved and retained the scoped SHIP disposition. No other material finding was recorded.

## keep

Preserve the actual 257-point unsmoothed mapping, clear axes and legend, truthful unavailable/retry state, compact text-aware card, and the short “原图” label with its full interaction semantics and 48dp target.
