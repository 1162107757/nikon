# D native UI verification — 2026-10-18

Production APK: **0.9.11-beta / 50**, SHA-256 **d5037ed50bcfa8dccffd54739daace836bc5f9bc11bdb642ee870715136f3f39**.

Final native results: **light 297 PASS + dark 297 PASS**, isolated `emulator-5558`, Android API 37, 1080 × 2376, density 408, font scale 1. Each report independently hashes the installed production APK.

The D composition is implemented in native Android views: project selector above the current-photo canvas; upper-left default preset; upper-right expand; lower floating transfer panel; saved/queued count and history; filter/selection row; three square thumbnail columns. Canvas is 978 × 724 px (about 383.5 × 283.9 dp), consistent with the approved D's 284 dp canvas. Text alignment and a 44 dp transfer action are checked. The preset page opens full screen, following the earlier native navigation requirement, with D's two effect-image/name cards per row.

Verified behavior:

- Ten received-byte updates at 200 ms intervals advance 68% through 77%, retaining shell, panel, canvas, grid, progress, current bitmap and first thumbnail identities. No extra hero decode is submitted.
- Waiting, receiving, unknown size, saving, failure, preparing and stopped states keep canvas/list bounds fixed. Unknown sizes do not show invented percentages; saving is separate from reception.
- Filtering stars and selecting photos preserve the current image. Batch selection opens the correct preset confirmation and leaves the project default unchanged. Choosing the second default preset card commits the fixture setting and returns automatically.
- A displayed preview remains while a new original decodes. Project switching clears the previous project's image; stale callbacks cannot paint the new project; a missing preview source does not repeatedly decode.
- Existing projects retain a new-project entry. Active reception gives a stop-first notice. After stopping, a second-project name form opens; cancel preserves the previous project and six fixture originals.

Evidence is in `light/` and `dark/`: `report.json`, `instrumentation.txt`, `receiving-68.png`, `waiting.png`, `saving.png`, `unknown.png`, `error.png`, `preparing.png`, `stopped.png`, `idle.png`, `selected.png`, `filter-cleared.png`, `batch-preset-picker.png`, `default-preset-picker.png`, `default-preset-changed.png`, and `new-project-form.png`.

Scope: synthetic concept photographs, hand-authored NCP fixtures and isolated cache project/default stores only. No physical-phone interaction, account submission, camera connection/transfer, or actual MediaStore publication was performed by this UI probe. Original camera transport and phone installation validation belong to the coordinating task. Reproduction commands are in `.tooling/live-d-qa/README.md`.
