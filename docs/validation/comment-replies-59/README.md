# Reply target clarification — 0.11.2-beta

Frozen APK SHA-256: `774ac5a78d599b33105d98e349192b226d20946819a4017041b740ff15f43828`, version code 59, 3,347,997 bytes. Published to beta on 2026-10-19; public bytes and latest metadata match.

- Native light: [18 PASS](light/report.json).
- Native dark: [18 PASS](dark/report.json).
- Both actual comment rows and composer screenshots were inspected. Reply identities read “小王 回复 小周”; the composer names its recipient. Unsupported/hidden target identity is not invented.
- A same-thread recipient change clears the previous recipient's draft. The injected POST retains the root for pagination while sending the selected reply's distinct `replyToId`.
- Java host: repository/drafts 33, paging 17, reply paging 94, recipient labels 9 PASS.
- Node backend regression: 103 PASS. Disposable PostgreSQL runs all migrations, repeats 009, exercises actual target/recipient selection, duplicate suppression, pending approval, hidden/deleted targets, cross-thread/cross-preset rejection, privacy and old-client behavior, and reruns prior community acceptance. All fixtures roll back.
- Migration 009 applied and its projection/recipient definitions verified in the authorized environment. Backend 1.4.1 deployed and Active. Four production guest GETs passed; the sampled preset had no root comments or replies, so those reads do not establish an online end-to-end notification test. No production comments, test users or messages were created.

The emulator helper's first attempt omitted required synthetic account timestamps. Only the helper was corrected; final light/dark reports refer to the same product APK. Its initial failure image is preserved under `history/`. Sessions and appearance were restored, the helper removed, and emulator-5560 stopped. The native probe uses an isolated comment container and does not certify full-detail page geometry, long names, landscape, TalkBack or physical-device performance.

Historical replies keep their recorded root target. The old app never recorded which child reply a user intended, so that intent cannot be retroactively reconstructed.
