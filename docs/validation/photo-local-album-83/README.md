# Local album and save destination validation — 83

This record covers the user-approved local gallery, renamed synchronization root and explicit save destinations. Publication is confirmed only by `publication.json` / `PUBLICATION.md` after actual public verification. A staged archive or passing test is not a published release.

Final status: **published**83 /0.16.0-beta, release `5e67b2cd-d628-46c0-b80f-ad4d2c1f4af1`, revision2. [Publication receipt](PUBLICATION.md) confirms exact signed APK bytes and matching latest metadata at both origins, the actual download-page query, and preservation of previous release rows. [Final reviewer verdict](../../../.impeccable/review/local-album-83/FIX-VERDICT.md) is **ship** for M1 plus the later floating-plus/central-authorization/overflow scope. The dedicated read-only QA emulator was closed after review; see `emulator-cleanup.json`.

## Authority and evidence

- Approved behavior and subsequent floating-plus/central-permission simplification: [requirements](../../design-review/local-album-20261023/REQUIREMENTS.md).
- Native authoritative result: `native-matrix.json`, the per-mode `report.json`, `environment.txt`, `restoration.txt`, `provenance.json` and seven raw PNGs. This JSON supplies exact APK/probe hashes and counts. Four modes repeat the same assertions; their sum counts executions, not independent cases.
- Raw native captures are unmodified. `.impeccable/review/local-album-83/` holds metadata-bearing copies, manifest and initial finish review. Later fix captures/verdict are separately retained so old evidence is not overwritten.
- `host-gallery-final-provenance.json` binds the final focused host execution to 69 before/after input hashes. Its log reports gallery250, origin86, deletion36 and identity18, totaling389. It covers the origin alias and stale owned-publication receipt regressions.
- `host-provenance.json` preserves actual earlier command outputs recovered from execution events. Its later input snapshot is explicitly **not** proof of historical execution inputs. These logs cover batch629 plus source links82, controller889, related controllers/stores511, automatic project53, receipt117, task store227, library36, editor core34780/manual644888/composition1158. Numeric loops are not independent test scenarios. The12 destination controller checks are included in889 and must not be added again.

## Behavioral boundary

Native tests run on a task-owned isolated Android emulator with real MediaStore rows, generated JPEG/transparent PNG, actual private files and an existing repository photo sample. They exercise media permission denial/revocation/grant, exact JPEG import, retained PNG plus white working JPEG, existing editing/batch entry points, private-only export, default publication/reuse and explicit new dated copy. A hand-authored512-byte structural TIFF/NEF fixture exercises the RAW save wrapper and frozen destination retry; it is not sensor data or a camera certification. Current-run injected faults restore measured bytes and modification time in `finally` and record the outcome.

The probe dispatches real native bound clicks/long presses; it does not certify every physical gesture. Picker dispatch checks use an instrumentation activity monitor and cancel without certifying external DocumentsUI layout. Notification permission is pregranted to exclude an unrelated modal; notification permission UX is outside scope. Host tests substitute Android/provider/codec/acquisition boundaries as stated in provenance. Neither host nor emulator results certify physical camera/live transport, sensor RAW fidelity, throughput/FPS, color calibration, TalkBack or long-running use on an actual phone.

## Retained failed / superseded runs

- `preliminary-1311/`: earlier native197 checks passed, but runner failed to restore a custom night-mode subtype. This is not a final passing run. Runner restoration was corrected and the native configurations subsequently restored successfully.
- `failed-origin-7bfb/` plus `origin-diagnostic-7bfb/`: unchanged imported JPEG failed origin reuse because the valid Android `/data/user/0` files path canonicalized to `/data/data`. Diagnostic4 checks are forensic only. Product path containment was fixed while traversal/outside/symlink checks were retained.
- `probe-path-assumption-fcd0/`: probe passed a noncanonical path to a canonical lookup API. The real gallery had the photo; only the probe was corrected.
- `notif-dialog-fcd0/`: unrelated notification modal interrupted an injected RAW fault. Its `fixture-state.txt` truthfully records that old synthetic fixture was not restored; later final runs record restoration of their own fixtures only.
- The first `fcd0b31a…` four-mode matrix passed273 assertions per mode. Finish review still found material compact-height gallery crowding. The reviewer subsequently withdrew an incorrect absent-picker finding; its factual correction remains in the original report. The user then requested floating “＋” import and central authorization, incorporated with the landscape correction. Superseded matrix/candidate/probe are archived as `fix-before-fcd0/` before the confirmation run.

Only final fix confirmation and reviewer verdict close the layout finding. No HTML/CSS detector runs against native Views.

## Final native confirmation

The simplified floating-plus/central-authorization candidate is version83 /0.16.0-beta, APK SHA-256 `f320eef5e6068e94fff54f37509374d09f723492f6fb3077052d727e55d79ebd`, 3,528,301bytes. It passed300 assertions in each of phone, large-font, landscape and dark, with restoration PASS for all modes and28 screenshots. The1,200 executions repeat the300 assertions. `final-source-pins.json` verifies189 build/probe inputs unchanged around10 phase checks plus the final snapshot. The additional27 checks per mode cover useful first-row/guidance bounds and native control-to-platform dispatch/cancellation; they do not certify external system-picker or permission-controller rendering.

The post-build source snapshot contains374 source files; its ZIP SHA-256 is `6c6f71092c712c5b5286a675ccbced002f9fb125874f1b375b049af1a8642a62`. It is a current source record, not a reproducible-build or historical compilation attestation. Final review copies and manifest are in `.impeccable/review/local-album-83/fix-confirmation/`. The reviewer's scoped disposition is recorded separately in `FIX-VERDICT.md`.
