# Floating tab-bar height review — candidate 87
**Verdict: PASS / SHIP for this height adjustment. No blocker found within the requested scope.**
- Version `0.17.3-beta` / `87`; APK SHA-256 `9b9b79dd74f559991379ff5227122dcd61d09867f4433f33f7d363c9cdfc0f32`, 3,532,397 bytes.
- `report.json` is PASS. Reviewed all 12 native PNGs: phone, 1.3× font, and dark × presets, sync, album, settings.
- Native measured height is **179px / 68dp** in phone/dark and **184px / 70dp** at 1.3× font. The base decreases **8dp**, from 76dp to 68dp; the existing font-growth rule remains.
- The shorter bar retains the complete icon-and-name selected background. Inactive tabs remain transparent. No label clipping, crowding, or overlap is visible in the twelve captures.
- Nine XML states (presets/album/settings in each mode) record all four targets at least 48dp and labels fully inside their targets. The smallest recorded target is 214 × 152px; cell minimum height remains 58dp in source.
- Sync was reached by a real tap and visually checked in each mode. Its continuous connection animation prevented uiautomator idle, so **no separate sync XML geometry is claimed**. The initial incomplete tool run remains `initial-dump-idle-failure.json`.
- Source confines reduced padding, 26dp icon slot and 2dp label gap to horizontal navigation. The full-item selected state and 11sp label remain; vertical rail values remain unchanged.
- APK/source fingerprints and restored temporary system settings are recorded in the completed capture report. No further capture or device operation was performed for this review.
- This is a bounded UI geometry/visual verdict. Version 86's 1,092 assertions remain historical; no version 87 full-album/protocol suite, landscape/tablet suite, physical-phone install, camera, TalkBack, or frame-rate certification is claimed.
