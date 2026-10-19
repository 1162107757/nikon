# First-tap Wi-Fi navigation — 2026-10-18

Verified frozen production **0.9.12-beta / 51**, installed APK SHA-256 **47af1bb90212442989997dae334505a30c155d1609dd1ac4567c5ab8ef8c5fe0**.

**Light: 94 PASS. Dark: 94 PASS.** Dedicated `emulator-5558`, Android API 37, 1080 × 2376, density 408, font scale 1.

Four actual native action paths are covered:

1. An existing project's disconnected canvas `去连接` goes directly to the Wi-Fi connection guide after one click.
2. An empty project's disconnected canvas action opens the same guide, retaining a new-project continuation with an empty project ID.
3. `新建项目` in an existing project's selector opens the guide when disconnected, keeping the old project selection while registering a new-project continuation.
4. A generic prerequisite notice's `去连接` action opens the Wi-Fi guide without creating a Photo Live intent.

Each live continuation retains its expected pending project ID, has `pending=true` and `connectionAccepted=false`, and does not mark import acceptance. Back cancels and invalidates the old token, returns to the Photo Live tab, and retains the existing project and all six fixture photographs. The final generic route also has no live token. No connection submit button, account form, camera protocol operation or real MediaStore publication is invoked.

Reports: `light/report.json`, `dark/report.json`, with independently read installed APK hashes. Five screenshots per theme cover each first-click guide and returning from the existing project's guide. Reproduction is documented in `.tooling/live-d-qa/README.md` under navigation-only regression. Appearance, camera address preferences and isolated cache fixtures are restored. The prior `.impeccable/review/photo-live-d-native/` results remain unchanged and continue to refer to 0.9.11.
