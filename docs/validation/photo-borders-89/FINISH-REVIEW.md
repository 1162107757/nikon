# Photo borders — Impeccable finish review

Review date: 2026-10-24. Candidate: 89 / 0.18.0-beta, APK SHA-256 `d433422a64a97e21878c747614057b6103caa2195cdaaf982a25787bf7eb96f6`.

## 1. Scope and capture validity

This review covers the approved local Android Views photo-border extension: three frame templates, automatic original-photo metadata, manual camera and information controls, per-photo drafts, preview and export. It follows `docs/design-review/photo-borders-20261024/IMPLEMENTATION.md`, the approved `border-templates-v3.png` and its approval record, the supplementary camera sheet, the three supplied reference photographs, and the Impeccable `craft-floor.md` and `android.md` references. It does not request a global app redesign or changes to the separately owned `LocalAlbumPanel` / `PhotoPlaceholder` work. No shipped agent definition was read. No HTML/CSS detector applies to these native Views.

Final evidence is native emulator / Android UiAutomation capture, with no fullscreen education overlay. All 65 assets listed in `screenshots.json` were hash-checked successfully. The hero `.impeccable/review/hero-repro.png` is byte-identical to `phone/editor-template-1.png`. Invalidated `initial` and nested `phone/phone` captures were excluded.

Visually inspected all seven phone states; large-font template 1, information, appearance and manual camera; all seven landscape states; all seven tablet states; dark manual camera and appearance. The dark template and automatic-camera hashes are identical to the already inspected phone captures. Also inspected the three actual phone template JPEGs and both missing-metadata/orientation JPEGs. These are application-rendered outputs, not design-board slices. The matrix reports 50 checks in each of phone, font scale 1.3, dark, landscape and tablet: 250 repeated-configuration checks for this candidate.

## 2. Contract fidelity

The three Nikon defaults meet the approved visual direction. Centered blur retains the inset full photograph, rounded corners, downward soft shadow, centered wordmark/model group and parameters. The sidebar keeps the full photograph to the right and the white Nikon mark plus F / ISO / S information to the left. The white template preserves the company/model, yellow mark, divider, parameters and original capture time grouping. The exported files preserve those layouts at image scale; the reference parameter font is an explicitly disclosed close match, Tomorrow ExtraBold Italic, rather than an asserted exact face.

The editor keeps the approved black canvas, compact style/information/appearance controls, real photo thumbnails, apricot selection and fifth border tool. Source and native checks confirm the requested 11sp camera-model text, 14dp pictogram and 48dp clickable row. Font scale 1.3 remains readable without clipped visible control labels. The fixed photographic output appropriately does not scale its raster lettering with the system UI font. No material contrast or safe-area collision appears in the inspected states.

The matrix provides evidence for automatic capture information, non-destructive manual model override, undo/redo, draft reopen, frozen per-photo export and a two-camera batch. Source paths show shared proportional preview/export layout and border-aware draft/cache identity. Nikon marks are sourced production assets; other brands intentionally use neutral brand text. The approved layout is sufficiently faithful; no aesthetic redesign is needed.

## 3. Material findings

### P2 — Border dialogs are not fully bound to the current editing session

`app/src/main/java/cc/huevia/ui/PhotoFilterEditor.java:189` exposes `PhotoBorderPanel.Host.identity()` as `sourceSha` alone. Field and custom-camera positive callbacks in `PhotoBorderPanel.java:38` and `:43` accept `ready`, the same SHA and equal starting border settings. Their child `AlertDialog` instances are shown without being retained in `popup`; `invalidate()` and `dismiss()` at `:47`–`:48` only dismiss the tracked main sheet. The logo dialog in `:39` is also untracked and its choice callback has no session guard.

Consequently an unbind / account change / later bind can leave a child dialog callback alive. A field/custom-camera callback can pass its old guard when the same original and settings reappear; a logo callback can act on the current border directly. This can apply an old user's pending choice to a new editing binding and persist it as that binding's draft. The editor already has an owner and binding `generation` for this purpose (`PhotoFilterEditor.java:147`–`:153`), but the border dialog contract omits them. This finding is confirmed from source lifecycle paths; it was not reproduced as a physical-device or account-service event during this review.

### P2 — Neutral brand labels exceed the renderer's reserved brand space

`app/src/main/java/cc/huevia/album/PhotoBorderRenderer.java:47` draws non-Nikon brand text at `textSize = height` without a maximum width. The white footer call at `:64` starts the brand at `0.606 × photo width`, reserves only about `0.058 × width` before the divider at `0.664 × width`, and starts parameters at `0.68 × width`. With the default footer height, neutral FUJIFILM / SONY / Canon lettering is substantially wider than that reserved space, so it can cross the divider and collide with the parameter text. These brands are exposed by the approved selector and must produce readable output.

The centered frame uses a related inconsistent width calculation at `:50`–`:53`: the neutral-brand width is measured with the smaller model paint, while `brand()` draws it at the larger logo height. This underestimates the group width and can remove the intended gap before the camera model. The supplied exports exercise Nikon; the two-camera batch asserts Fuji metadata, not this neutral-brand visual composition.

## 4. Recommended changes

1. Give border controls an editing-session identity that includes the owner and binding generation, and reject callbacks after panel close or invalidation. Retain and dismiss all field, custom-camera and logo dialogs when the binding changes; apply the same captured-session guard to each mutation callback.
2. Measure the brand with the actual draw paint and constrain neutral text to an explicit available width. Use that measured width when centering the brand/model group and keep the white-footer brand inside its reserved slot. Preserve the approved Nikon geometry and sourced marks.
3. Verify stale callbacks after unbind and same-source rebind, including a changed owner and a logo dialog. Inspect actual rendered center/footer JPEGs for Nikon, Fujifilm, Sony and Canon, plus a long model. Capture the landscape manual sheet after scrolling to its apply action through a real UI gesture: current `PhotoBorderPanel.java:46` supplies an outer ScrollView, but `BorderNativeProbe.java:27` calls `performClick()` directly on the apply view even when offscreen, so its PASS does not demonstrate gesture reachability. This last item closes an evidence limit rather than proving the current scroll implementation broken.

## 5. Verdict and limits

**Disposition: FIX, then rebuild and verify the two scoped corrections.** The approved Nikon default appearance and final native capture set pass visual review. No general design rebuild or unrelated changes are requested. The session lifecycle gap and exposed non-Nikon output overlap should be corrected before shipping this candidate.

This is an emulator-backed finish review of the stated candidate and supplied evidence, with a static source assessment of the two findings. The 250 checks repeat a 50-check suite across configurations; they are not 250 independent behavioral cases. They use an explicit QA original-photo crop and synthetic exact rational EXIF. They do not certify physical camera/phone compatibility, production network or account flows, gesture performance, refresh rate, FPS, predictive Back, TalkBack, or all keyboard/custom-text states. No production source was modified and no build was rerun by this reviewer.
