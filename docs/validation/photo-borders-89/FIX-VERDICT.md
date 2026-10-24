# Photo borders — scoped fix verdict

Review date: 2026-10-24. Final candidate: 89 / 0.18.0-beta, APK SHA-256 `1f74ece275423198208750b804e7dad8e72c443b881c02eccb85eea9c4cd2ba0`.

## 1. Scope and evidence validity

This is the verdict pass for the two P2 findings in `FINISH-REVIEW.md`, plus its landscape reachability evidence request. The original review remains unchanged as the historical review of candidate `d433422a64a97e21878c747614057b6103caa2195cdaaf982a25787bf7eb96f6`. This pass does not reopen the approved design, expand into a global audit, or cover the separately owned album placeholder work.

The final APK at `dist/huevia-0.18.0-beta-debug.apk` hashes to the stated `1f74…` value. All 76 assets in the final `screenshots.json` match their recorded hashes. The hero capture matches the final phone template-1 capture byte for byte. All five final configuration reports name this same APK and pass: phone 70, landscape 62, large-font 50, dark 50 and tablet 50, totaling 282 repeated/configuration assertion executions.

## 2. P2 dialog-session finding — RESOLVED

`app/src/main/java/cc/huevia/ui/PhotoFilterEditor.java:189` now identifies the editing binding with owner, binding generation and source SHA. `PhotoBorderPanel.java:38`, `:43` and `:50` capture that identity and starting settings for field, custom-camera and logo mutation callbacks. `showFieldDialog()` at `:49` retains the child dialog, and `dismiss()` at `:48` dismisses both the child and main sheet. Existing invalidation and close paths call that dismissal.

The final phone and landscape native reports pass actual field-dialog and logo-dialog closure on unbind/rebind of the same source, binding-identity change, and deliberate invocation of retained stale callbacks without mutation. The synthetic owner-property change also rejects old field input. The custom-camera child uses the same tracked-dialog path and captured-identity guard, confirmed from source. The synthetic owner check is a whitebox guard regression, not a claim that a production account-switch flow was exercised.

No remaining change is required for this finding.

## 3. P2 neutral-brand layout finding — RESOLVED

`app/src/main/java/cc/huevia/album/PhotoBorderRenderer.java:47` now accepts a maximum brand width, measures with the actual draw paint, scales within that width and vertically centers the result. The centered brand/model group at `:54`–`:55` uses the matching measured width and an explicit cap; the white footer at `:67` caps the brand before its divider. The sidebar also passes an explicit available width. Nikon uses the authentic mark aspect ratio.

Visually inspected all ten actual native renderer JPEGs: `phone/brand-{NIKON,FUJIFILM,SONY,CANON,LONG}-{1,3}.JPG`. Each four-brand centered output has a clear brand/model gap and centered combined group. Each footer keeps the neutral mark within its slot, with no collision with the divider or parameters. The long-model stress case stays within its model allocation in both templates. Nikon retains the approved visual hierarchy and authentic marks. Longer labels shrink as intended, rather than crossing into adjacent content.

No remaining change is required for this finding.

## 4. Landscape evidence request — CLOSED

Inspected `landscape/camera-manual-scrolled.png`: the selected model, custom-model control, explanatory copy and full apply button are visible. `tests/photo-borders-device-probe/src/cc/huevia/borderqa/BorderNativeProbe.java:49`–`:50` now injects pointer scroll events, checks the apply button's visible height and taps its visible center. The final landscape report passes `landscape-apply-reachable-by-real-scroll` and subsequent manual-camera application. This closes the original limitation caused by directly invoking an offscreen view's `performClick()`.

## 5. Disposition and limits

**Disposition: SHIP.** Both scoped P2 findings are **resolved**. **Remaining required changes: none.** No additional recapture, rebuild or design change is requested by this verdict.

This conclusion combines source inspection, final report inspection, SHA-256 checks and direct visual inspection of the ten brand exports and scrolled landscape capture. The reviewer did not mutate production source or rerun builds. The 282 executions include repeated configuration coverage; they are not 282 independent behavioral cases. Evidence remains emulator-based with an explicit QA photo crop and synthetic EXIF; it does not certify physical hardware, production account/network flows, or performance. The original finish review's other stated limits remain applicable.
