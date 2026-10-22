---
version: 4
slug: camera-wifi-connection
primary_target: app/src/main/java/com/nikonpreset/usbprobe/MainActivity.java
related_targets:
  - app/src/main/java/com/nikonpreset/usbprobe/connection/CameraWifiSession.java
  - app/src/main/java/com/nikonpreset/usbprobe/album/CameraWifiNetwork.java
---

# Connection flow contract

MODE: Operate. A narrow refinement of Huevia's existing native Android cloud-white / graphite and system-blue interface.

AUTHORITY: User asks to simplify Wi-Fi name/password entry and coordinate with the other task to prevent conflicts. Preserve the connection service and existing background retention behavior.

INTERACTION: Adopt an existing unambiguous physical Wi-Fi before showing a form. With no existing Wi-Fi, the primary action opens Android system Wi-Fi selection and returns to an explicit pending action, with a bounded 12-second check. “我已连接，继续” retries existing-network adoption; “手动填写” is a secondary fallback. Only the fallback asks for nearby/location permission. Actual camera protocol handshake remains the evidence of camera connection.

FORM: Reuse native AlertDialog, system sp typography, incumbent button / textLink / UiPalette components, a scrollable content area, 48dp action touch areas, and the existing manual inputs at 52dp. The system choice has no credential fields. Pending, unavailable-network, validation, cancellation and manual-input states remain explicit and dismissible. Passwords are masked, not saved or autofilled, and cleared on dismissal. No new raster, icon system, token palette or decorative material.

LIFECYCLE: Epoch guards retire stale settings results and polls; finishing/canceling clears handlers and operation leases. Healthy application-owned connections survive ordinary screen cleanup. Network observation confirms the exact chosen physical route; existing service demand remains separate from observation. Do not describe either a detected Wi-Fi or a gateway as a successfully identified camera.

EVIDENCE: Isolated actual-API UI compilation and 1,200 focused backend/runtime assertions pass; independent code review found no actionable defect. The 9053 validation candidate, version 72 / 0.13.4-beta, passes native Wi-Fi QA in four scopes: disconnected light 79, disconnected dark 79, connected light 29, connected dark 29, totaling 216. All four reports record installed APK SHA-256 9053c6b32eec3750c13da46800b03fb0e7517c55c4d04c78626bb10c8f585721; an independent read-only review confirmed the versions, hashes and counts. These 216 checks belong only to that APK hash. The initial disconnected-light failure is archived separately and was caused by the probe's missing native Handler idle boundaries; only the probe changed to fix that issue. Scope is emulator UI, cancellation/return handling, manual invalid-input/privacy checks and exact existing Network adoption without nearby/location grants. Spy continuations never start PTP, and no valid manual connection request is submitted; these results do not certify real-camera compatibility or background retention. See docs/camera-wifi-simplify-72.md and tests/wifi-simplify-device-probe/README.md. No real-device or NX Studio claims are added.

FINAL DELIVERY: The unified build owner confirms final APK 72 / 0.13.4-beta, SHA-256 e563135f99857a19938d3a49ef7fceeab587250a843fd43dde8ed0ff161202b7, 3,425,901 bytes; independent read-only verification confirms the actual artifact hash and size. Changes after the 9053 validation candidate are limited to AlbumPanel camera-pagination footer landscape placement/padding; Wi-Fi sources remain unchanged. The final e563 APK passes album-refresh phone 136, large-font 136 and landscape 155 checks, totaling 427, with all three reports recording e563. Wi-Fi's 216 native checks remain attributed only to the 9053 validation candidate; preserve those reports and their original hashes without relabeling them as e563 results. The unified task owns the overall validation record and delivery.
