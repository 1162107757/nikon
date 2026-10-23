## verdict

- **M1 — resolved.** `PresetSelectionPage.Card.bind` lines 392–397 now makes ERROR/UNSUPPORTED authoritative: both bypass the LRU preview, clear the card-held bitmap and ImageView, and retain their failure state. Card-held preview recovery requires READY. The existing switch therefore exposes retry only for ERROR and keeps both failure classes unselectable. Final native assertions drive the actual choose/read ERROR and choose/profile UNSUPPORTED branches, verify state through adapter rebind, cleared image, disabled content and correct retry visibility, then restore the valid record and exercise the actual renderer retry back to READY/selectable. The repeated held-worker LRU-eviction/rebind checks retain the same displayed Drawable and card top.

The failure fixtures are disclosed in each final report: ERROR temporarily removes only an in-memory library record and restores it before retry/render; UNSUPPORTED deliberately seeds a stale READY thumbnail on the known unsupported offline fixture to reach confirmation rejection. That injected image covers UI state handling and does not imply rendering support. No preset file or production content mutation is claimed.

The exact 28 replacement captures and `hero-repro.png` from the first review were reopened. All are valid; portrait is 1080×2424 and landscape 2424×1080. The four live-loading/stable pairs preserve normal-card geometry while previews fill in; static captures do not establish frame rate. All 28 raw hashes match the replaced capture manifest. The final APK is 3,487,341 bytes, SHA-256 `2543241174c2c7b6656b08b4142d3c3812d095b2ecd732836a11ab9a3c173987`. Final phone/large-font/landscape/dark reports are PASS 163/163/155/163, totaling 644 assertion records on that same SHA, with actual font scale 1.3 and landscape orientation recorded. The prior `61d8ae4d…` candidate and initial FIX review remain historical evidence under their original scope.

Regressions introduced by this correction: none found in the supplied recaptures, corrected state path or recorded regression batch. The fixed-height dock, leftmost grid, inline category navigation, category-filtered picker results and matching-key ready-image retention remain intact.

## remaining

Clear for M1. SHIP covers the scored fix and its recorded regression batch. Evidence remains isolated native-emulator UI with the existing template and offline fixtures. None selection remains a bound-click/callback check; physical pointer injection for that separate card was inconclusive. No physical camera, live catalog, FPS, TalkBack, deployment or publication conclusion is added. Final documentation reconciliation follows this scoring pass.

disposition: ship
