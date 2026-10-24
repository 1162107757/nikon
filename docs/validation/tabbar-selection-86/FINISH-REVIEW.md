# Bottom-tab selection review — candidate 86

**Verdict: PASS / SHIP for the requested selected-state change.** In all twelve reviewed native screenshots, the active bottom tab's icon and name sit inside one continuous rounded background. No issue requires another UI pass within this scope.

## Candidate and completed checks

- Version `0.17.2-beta`, versionCode `86`; APK size 3,532,397 bytes.
- APK SHA-256: `13781dbceb52ce290cc3f15348e3e83131c72b64d7aaa340a59b1b53d6ab1483`.
- `matrix.json` completed **PASS** at `2026-10-24T00:24:18.5887672Z`, with `sourcesStable: true`.
- Phone, fontScale 1.3, and dark each passed **364** existing native assertions: **1,092 total**. All three reports identify the SHA above.

## Visual confirmation

Viewed the original native PNGs under `../orange-ui-86/local-album/{phone,large-font,dark}/`:

| Screenshot | Selected tab | Phone | 1.3× font | Dark |
|---|---|---|---|---|
| `presets.png` | 预设 | PASS | PASS | PASS |
| `sync.png` | 同步 | PASS | PASS | PASS |
| `local-app.png` | 相册 | PASS | PASS | PASS |
| `settings.png` | 设置 | PASS | PASS | PASS |

Each selected item has one background extending around the complete icon and label, including the label's bottom edge. The three inactive items retain transparent backgrounds and remain distinct. All four labels are legible, without clipping or collision, including at 1.3× font size. The light peach and dark warm-brown states preserve the approved palette. The existing floating bar position, outer shape, gesture-area gap, and adjacent FAB separation show no observed change in these captures.

The active icon and label still use `UiPalette.text` against the opaque `primaryContainer`: calculated token contrast is **11.33:1** in light appearance and **9.57:1** in dark appearance. This supports the observed readability of the newly covered label area; inactive colors are unchanged.

## Source and behavior confirmation

`MainActivity.nav()` applies the active `primaryContainer` background to the complete cell only when the parent is horizontal, using a 28dp radius. Its icon slot has no separate background in this mode. Cell padding, icon and label sizes, label spacing, width allocation, click handler, content description, and selected state are unchanged. The native root tap, route, target, and order assertions continue to pass.

For the vertical rail, the cell remains transparent with the prior 20dp cell radius, and the active icon slot keeps its prior 16dp radius and 54 × 30dp slot. Rail rows remain 72dp high. This is source confirmation only: version 86 landscape/tablet suites were not run and are not claimed here.

This review covers only the requested bottom-tab selected state and its direct regression checks. Version 85 evidence remains separate. The evidence is from the task-owned emulator; no physical phone was connected, and this report does not claim physical-device, camera, live-network, TalkBack, or frame-rate certification. No further application scan was performed.
