## verdict

- **M1 — resolved:** `PhotoFilterEditor.java:251–253` gives each recipe click a main-thread request sequence; success and failure callbacks require the latest request, and retain the photo/account and starting-snapshot guards. A superseded success returns before modifying settings or undo history. The held-IO A→B regression confirms B wins, target geometry is unchanged, and only B adds one undo step in all three native modes. The refreshed recipe-applied captures retain the expected photo, recipe panel, context, and tool layout.

Reviewed artifact: 81 / 0.15.0-beta, `dist/huevia-0.15.0-beta-debug.apk`, 3,487,341 bytes, independently checked SHA-256 `7861ae7163d17a5651a8c41b25ddf1b368e868cd326bdcc04a2bd72d5df7aa80`.

The targeted probe at `tests/photo-editor-device-probe/src/cc/huevia/editorqa/EditorNativeProbe.java:672` creates a separate stored A recipe at 30% / exposure 1, holds the real editor IO executor, invokes A then B on the UI thread, and releases the queue. Each current report contains the held/drained, B-at-70%-and-exposure-4, unchanged-composition, and single-undo assertions. The subsequent existing undo check restores exposure 0 while retaining the target crop; redo and the later export path complete within the passing run. Source inspection confirms the complete preset bytes/profile, strength, and adjustment object are committed together for the accepted request.

`phone/report.json`, `large-font/report.json`, and `landscape/report.json` each record PASS 172 on this exact SHA, 516 assertion records total, with font scales 1.0 / approximately 1.3 / 1.0 and orientations 1 / 1 / 2. These remain supplied isolated-emulator results using the stated template and offline fixtures.

All 30 original required screenshot paths were reopened after replacement, together with `hero-repro.png`. Their states and dimensions remain valid: 1080 × 2424 portrait and 2424 × 1080 landscape. Every manifest raw hash matches its current source capture; every review copy is pixel-identical to that raw source. The landscape export sheet remains its recorded scrollable form. No fix-batch visual regression was found. The original `FINISH-REVIEW.md` is preserved; prior f5b34441 evidence is archived under `before-review-fix/`.

This SHIP is limited to the scored M1 fix and its fix-batch regression check. It retains the initial review's scope and does not constitute a new whole-surface review or evidence of publication.

## remaining

clear

disposition: ship
