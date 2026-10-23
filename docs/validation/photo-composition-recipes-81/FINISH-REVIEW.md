disposition: fix

Reviewed artifact: 81 / 0.15.0-beta, `dist/huevia-0.15.0-beta-debug.apk`, 3,487,341 bytes, SHA-256 `f5b34441b4616c4ebd9a9fcaad135f258e0ba87d85be146c347e73d57f9bdd03`. This is the scoped Operate extension of the approved 80 world; no new comp, QUALITY BAR card, or concept roll applies. Source inspection was sampled, not a full implementation audit.

## persistence

Pass. `PRODUCT.md`, `DESIGN.md`, the surface contract, source opening contract, and the accepted extension requirements exist and describe the same photo-first world. The approved incumbent reference is `docs/design-review/photo-basic-adjustments-20261022/design-board-v2.png`; historical 80 filter captures were also opened for phone, large font, and landscape. The earlier artifact identity still present in some documentation was explicitly identified as document staging in the review packet; this review binds only the artifact identity above.

Evidence check passes: all 30 named captures and `hero-repro.png` were opened. Portrait captures are 1080 × 2424; landscape captures are 2424 × 1080. Each shows its named native state without a malformed or blank viewport. Black regions in the editor are its intentional photo backing. Every manifest raw SHA matches its source capture, and all 30 review copies are pixel-identical to those raw captures despite different PNG byte encodings. The hero is the supplied editor checkpoint, not a new reproduction of the two-panel design board.

The three raw reports each record PASS 167 on the reviewed SHA, 501 assertion records total, with actual font scales 1.0 / approximately 1.3 / 1.0 and orientations 1 / 1 / 2. These are supplied isolated Android emulator results using labeled synthetic/template and offline preset fixtures with real JPEG, EXIF, draft storage, and MediaStore paths. They do not certify physical hardware/cameras, a live catalog, sustained FPS, TalkBack, or colour calibration. Native Java Views have no HTML/CSS detector pass; no hook finding was supplied.

## fidelity

Inventory established from the incumbent board before reading the builder contract: a near-black editing stage, a dominant real photograph spanning the available width, centered filename and count, left Back and pale-blue Export, a subdued preset context with outlined favorite/overflow, a lower thumbnail strip and strength slider, and blue selected-mode indicators. The second panel retains the photograph while replacing the filter controls with a compact adjustment rail.

| Salient element | Classification | Evidence |
| --- | --- | --- |
| TYPE | match | Native system typography retains the incumbent's restrained filename, context, label, and value hierarchy. Actual 1.3 scaling remains readable; “保存当前” stays on one line. Native system sp type is the explicit approved material, not an unchosen display-face substitute. |
| MATERIAL | match | The actual photograph remains the focal material; flat black tools, pale-blue actions, and consistent Canvas line icons remain. Crop handles, thirds, and masking express live geometry rather than imitation physical material. |
| GROUND | match | Current pixel samples are canvas `#141415` and tools `#0B0B0B`, matching the explicit contract. The incumbent board samples approximately `#131313` on its stage and `#0B0B0B` on tools; there is no warm-cream or blue-slate drift. |
| Photo prominence and header | match | The photo, slim filename/index/Back/Export hierarchy, preset context, favorite, overflow, and comparison control retain their incumbent order and treatment. |
| Four lower modes | adaptation | “构图 / 配方” join “滤镜 / 调节” within the existing tool height, explicitly authorized by REQUIREMENTS.md and FIRST VIEWPORT. The added workflows do not replace the photo-first composition. |
| Crop workspace | adaptation | An on-demand full-screen tentative workspace, explicit Cancel/Apply, crop frame, ratio rail, rotation, straighten, and retained-area feedback implement the accepted COMPOSITION scope. The sideways square fixture follows its explicit 90° rotation/EXIF test state. |
| Recipe panel and name error | adaptation | A scrolling local recipe list fits the inherited panel. The save dialog retains input and shows a readable inline duplicate-name recovery message in all three capture modes, as required by MY RECIPES. |
| Batch effect and recipe selection | adaptation | Native choice dialogs expose the approved “套用效果 → 我的配方” route and target count; summaries wrap at large font and the landscape list visibly scrolls. |
| Export confirmation | adaptation | The existing transient sheet adds actual crop dimensions, rotation/straighten context, and the explicit new-copy/current-time promise. Its landscape form is scrollable, as recorded by the native evidence. |
| Recipe application ordering | contradicted | `PhotoFilterEditor.java:250–252` captures a starting snapshot for every queued click without a request serial. Two different recipe clicks made before either completion share that snapshot: the first applies, and the latest click silently fails the equality guard. The recipe rows remain enabled (`:241`), and the IO executor is single-threaded (`:49`). This violates the requested single-photo apply action; M1 below is required. |
| Photo-live empty-project recovery | match | All three no-project captures show the native empty state instead of a crash. The sampled `AlbumPanel.updateLivePresetProgress` branch guards the empty project ID before configuration lookup, matching the existing adjacent guard. |

The five contract blocks otherwise retain their scoped promises: THESIS keeps the verified original and photograph central; OWN-WORLD retains graphite/black/blue and native type; STORY exposes colour, composition, reusable recipes, and explicit dated export; FIRST VIEWPORT retains its fixed tool region and landscape dock; FORM remains native Views with the inherited controls, insets, and Back model. The first viewport remains memorable as a photograph editor. The asynchronous recipe ordering exception is material, not a request for new visual polish.

## ceiling

Reached for the scoped incumbent visual extension. No new frame, ornamental density, display lettering, decorative depth, or motion system is needed to fulfill this approved world. The new crop geometry, focused form error, restrained recipe list, and explicit export information use the world's existing native devices. No additional craft-floor refusal is visible in the scoped captures. Motion and accessibility certification cannot be derived from these still captures or the supplied limited probe scope.

## material_fixes

1. **M1 — Contract / MY RECIPES:** Make queued single-photo recipe selection honor the latest requested recipe. At `PhotoFilterEditor.java:250–252`, add request identity/cancellation so an earlier recipe completion cannot apply ahead of, and thereby invalidate, the latest click; retain the photo/account and intervening-edit protections. Verify with a deliberately held IO queue: click distinct A then B, release it, and assert B's preset/strength/all 13 values apply, target geometry stays unchanged, undo returns to the pre-application state, and later manual edits or photo/account changes still reject stale work. Rebuild and bind confirmation evidence to the new APK SHA.

## keep

Keep the photograph dominant, the 224dp flat tool region and four findable modes, tentative crop Cancel/Apply semantics, each target's independent geometry, recoverable inline name errors, and dated new-copy exports that preserve originals and avoid JPEG recompression.
