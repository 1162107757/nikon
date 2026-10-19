# Preset detail inline comments native QA

All runs use only the dedicated `emulator-5560`. Product Java Views are real; account sessions, comments, messages, avatar pixels and HTTPS downloads are synthetic fixtures. The fixture library is a separate UUID directory, and each report confirms its cleanup. No physical phone, production login/comment/upload, camera write or deployment is exercised.

## Final candidate

The final frozen product is code 56 / `0.10.2-beta`, SHA-256 `91ce0f51527a50d49ef37915d07439d043c9c77f42751fcb6c13d8d0f52beb10`.

| Evidence directory | Result | Scope |
| --- | --- | --- |
| `inline-final-light/` | PASS, 94 checks | Complete extended interaction regression, portrait light |
| `inline-final-dark/` | PASS, 94 checks | Complete extended interaction regression, portrait dark |
| `inline-final-dark-landscape/` | PASS, 106 checks | Same regression plus explicit landscape freeze and per-screenshot width/height checks |
| `inline-supplement-light/` | PASS, 12 checks | Corrected nested-section scrolling; unobscured current and recovered legacy comments |
| `inline-supplement2-dark-landscape/` | PASS, 15 checks | Corrected nested-section scrolling with explicit Activity landscape request and 2424×1080 pixel checks |

The count includes repeated checks and screenshots, so these counts are not distinct feature totals. Every listed report matches the final installed APK hash and records `ownedFixtureCleaned: true`.

Extended cases use real product callbacks with synthetic 401 responses: A can continue its own draft after re-login, B receives none of A's text/reply target, and a send queued by A is never transmitted after switching to B. Old `source=online` downloads with no UUID recover a unique real UUID from the managed full catalog or bound cache, attach only the comment section, retain the same ScrollView and Y position, decline ambiguous same-SHA threads, and expose an explicit offline retry. The ordinary NCP preview/comparison, ready tone curve and import button remain; NP3 preserves exact downloaded bytes without simulation, a curve or camera import capability.

Use `inline-supplement-light/corrected-legacy-downloaded-comments.png` and `inline-supplement2-dark-landscape/corrected-legacy-downloaded-comments.png` as legacy comment visual evidence. The original 94-check legacy screenshots were obscured by the test's preceding NP3 guard dialog; the functional assertions remain valid. Later screenshot-only passes explicitly closed that dialog and corrected the helper's nested-coordinate scroll. The helper also retained an initial portrait repeat and a failed orientation-only supplemental report; they are history and are not counted as landscape evidence.

The final screenshots were visually checked for circular default/custom avatars, readable commenter/replier identity, the highlighted inbox target, preserved NCP footer, honest NP3 state and unobscured legacy comments. The emulator was restored to `user_rotation=0`, `accelerometer_rotation=1`; the test Activity finished, and exclusive use of `emulator-5560` was explicitly released to the coordinating task. No product source changed during final QA.

## Preliminary candidate

The preserved `inline-light/`, `inline-dark/` and `inline-dark-landscape/` reports each passed 62 checks against product code 56, SHA-256 `09e37a68ba210c82ca5db20628580e92851a92189f5b3360fe2b5879298ee179`. These establish the original embedded comments, pagination, guest login return, delayed A/B callback, inbox focus and NP3 retain-only scenarios. Screenshot dimensions confirm portrait 1080×2424 and landscape 2424×1080.

These are preliminary evidence: they precede the “Huevia 收录” wording change and the later review fixes for old downloads lacking a discussion UUID and drafts crossing account identity. The extended helper adds actual synthetic 401 and queued-send scenarios plus managed full-catalog/cache, duplicate SHA and offline retry scenarios; its final-candidate results must be recorded separately.

`helper-preflight/invalid-integer-fixture.png` preserves the initial helper-only fixture construction failure. The helper originally supplied Java Integer values directly to the strict catalog parser instead of going through JSON numbers. This was corrected before the preliminary product interaction runs; it is not a product failure screenshot.

The helper source/build instructions are in `.tooling/inline-native/`. Every report records the installed APK hash; a matching version number alone is not treated as a frozen candidate match. Actual QQ authorization, production moderation/delivery and camera compatibility remain outside this synthetic native probe.
