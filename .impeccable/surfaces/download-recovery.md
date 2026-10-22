---
version: 1
slug: download-recovery
primary_target: app/src/main/java/com/nikonpreset/usbprobe/ui/AlbumPanel.java
related_targets:
  - app/src/main/java/com/nikonpreset/usbprobe/ui/RawPhotoPanel.java
---

# Interrupted camera download contract

MODE: Operate. Preserve the incumbent native typography, palette, buttons, and global GlassBar.

AUTHORITY: The user reports Z6II download interruption, inability to continue after waking the camera, and same-card NEF matching failures; asks to finish and publish. The Fuji connection choice must read 富士 without a model suffix.

INTERACTION: A definite connection loss ends the old busy state immediately. Display the unfinished count, an explicit reconnect/continue action, and an explicit cancel action. Continue requires a fresh trusted camera and full inventory identity check. Do not imply byte-range resume or automatic reconnection. Completed originals remain usable. Pending intent lasts only in the process and clears on explicit cancel/disconnect or account-owner change.

LAYOUT: When disconnected/empty, the full recovery and empty-state content scrolls with enough bottom space to clear GlassBar. When a photo grid exists, bound the recovery header in its own ScrollView and retain the grid as a separate weighted viewport. Actions retain 48dp touch targets and are reachable by actual touch in short-screen and large-text conditions. Reuse native feedback and existing colors.

RAW: An automatic JPEG/NEF match failure shows actual inventory counts and may offer explicit selection from the camera RAW list. A direct NEF validation failure stays on that original's retry path. Retain provenance and file checks. Fuji's label is brand-only while model-specific compatibility guidance stays accurate.

EVIDENCE: Final APK 73 / 0.13.5-beta, SHA-256 f6464b04ed13e749537030e62c087c40dc0a7aae9ee129f2fe703110e1e74f25, passes 62 phone, 62 large-font and 113 actual-landscape native checks (237 total). The final probe verifies Activity resources, measured dimensions and screenshot orientation; landscape is 2424x1080 and orientation 2. Root reviewed all three landscape screenshots plus the earlier six portrait screenshots; an independent reviewer confirmed all layouts, the final three reports and artifact hash, with no material defect found. The first landscape-labelled run remained portrait and is archived separately, excluded from final counts. Synthetic UI fixtures never establish real camera sockets. See docs/z6ii-download-recovery-73.md for host tests, limitations and publication record.
