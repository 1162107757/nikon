# Camera album previews

Current version: **0.9.2-beta**, code **41**. Historical version evidence below retains its original scope.

The album requests camera-generated JPEG previews. Viewing a camera photo does not issue
`GetObject (0x1009)`, read pieces of the original, create a downloaded-photo entry, or write
camera settings. In normal browsing, the original is requested only by the explicit
download action. Separately enabling Photo Live authorizes new-JPEG reception; starting
a preset batch explicitly authorizes original acquisition for its selected remote photos.

## Resolve originals and reuse preset results (0.9.2)

Previously an effect selected for another batch was treated as a new original,
stacking the preset on already-processed pixels. The shared Batch now calls
processingOriginalFor before deriving. It strictly follows verified source links
to the root original, tracking visited identities and limiting traversal to 64 hops;
missing/corrupt links, cycles or excess depth fail closed. sourceFor retains its
immediate-input meaning for hold-original comparison of existing effects.

Applying A again to effect A reuses its verified output; selecting B from effect A
generates B from the root original. Selecting the original and effects together
merges work by root content, chosen preset and model, deriving/publishing once.
Verified existing effects are reused, gallery publication can retry, and a deleted
target can be regenerated from a still-valid original. Confirmation states that
processing starts from the original; result feedback exposes reused/merged counts.

Final artifact: 2,941,561 bytes, built 2026-10-18 00:34:41 +08:00, SHA-256
`102262e27429b618ad3e4d4e0b6ae314f66d0f1f5c8b00d3b0a5de7e2eb06a99`.
Current host groups pass separately: batch128, source links62, automatic queue23.
The stub uses a one-pixel payload to isolate control/source/deduplication behaviour;
it does not validate Android codecs. Final source is unchanged and v2/v3 signatures
pass. Separate Android 17/API37 emulator checks use persisted synthetic raw4/Tone A
offline, without a camera connection: effect A -> Tone reports reuse1 and retains
two App/MediaStore photos. A -> Identity B adds exactly one; raw4 -> Identity reuses
that same B. Selecting raw+A+B -> Identity completes3/3 with reused1/merged2 and one
result; both local-directory and MediaStore totals stay three. Original/A/B hashes
remain unchanged through reuse/merge. Cold start then effect -> Identity still
reuses one with total3. Confirmation/statistics and dark/font1.3 feedback are checked.
This exercises successful actual Android codec/MediaStore paths. Native source
corruption, deletion and failure injection were not performed; those remain host
coverage. Version41/0.9.2 and the final full SHA are verified. App crash filtering
has zero matches (app/build/v092-crash-filter.log); font1.0/night no are restored.
The App is force-stopped and the task emulator exits with emu-kill ACK. The
synthetic peer had no camera session and is stopped; all processing above uses
existing local synthetic files, with no new camera-original transfer/write
acceptance to count. The phone is offline and has not received 0.9.2; no new
physical-camera, real-photo, background, lock-screen or Doze acceptance is claimed.
See [0.9.2 validation](../dist/nikon-usb-0.9.2-beta-VALIDATION.txt) and
[functional checkpoints](device-review/preset-reapply-0.9.2-functional.log).

## Visual preset selection page (0.9.1)

One native full-screen Dialog page serves default-preset selection for new/existing
Photo Live projects and batch selection from Camera, Photo Live and Downloaded.
Each large card shows a preset name, current check and effect rendered by
PresetPreviewRenderer from that local NCP and the unified/user-replaced template.
Local catalog reads, validation and visible-image rendering use a single bounded
worker/cache; ListView virtualizes rows. Loading/empty/error states offer clear
feedback and retry. Unsupported formats show a reason and cannot be selected;
selection rereads and validates the NCP before returning.

The picker preserves its parent's form and photo selection. Back/cancel does not
change the preset. A selected default fills the existing form; batch selection
returns to the existing processing confirmation, without automatically processing.
Source guards/generation tokens discard obsolete asynchronous work, and closing
does not connect a camera, read originals or request network content. UiPalette,
system safe areas, adaptive card width and reduced-motion-aware page entry follow
the existing native UI.

Live and Downloaded place Filter directly beside their photo count. Its menu has
one checkable Starred item; when checked the header shows a star icon and Starred
with the same dropdown affordance. Toggling it off restores all photos.

This UI change adds/reruns no host or protocol suite; 0.9.0 check counts remain
historical. Final artifact: 2,941,561 bytes, built 2026-10-17 22:58:47
+08:00, SHA-256
`8da86defdc2f4851b384c9df2838e678bd84a65741a2d0f47356569fefc2f3a3`.
Earlier acb candidate native checks use VisualQA: Tone/Identity cards show visibly
different template effects, names and the current None check. Selecting Tone
returns to the preserved name form; reopening/Back retains it. One synthetic
shot4 saves an original/effect, then Live stops. Existing defaults show Tone's
check and selecting None returns. A zero-result Live star filter can be disabled.
Downloaded raw4 -> batch visual picker -> Tone returns to the one-photo confirmation;
cancelling and reopening/Back retain selection. Dark/font1.3, narrow 984x2190 and
landscape layouts scroll to card names with animator scale0. No preset batch is
actually started. Final 8da changes only the full-screen dark system-bar theme/
WindowInsetsController handling from acb. Build and v2/v3 signature checks pass.

Final 8da native rechecks show readable white system-bar icons in dark/font1.3.
Downloaded's checked Starred menu shows one photo and the adjacent count/star
header. Selecting raw4 -> visual two-card picker -> Identity returns to one-photo
confirmation; Cancel performs no processing. Existing Live default opens with None
checked; Identity returns to VisualQA automatically. Live's star filter shows one
photo. Live batch also opens the visual picker, and Back retains the selected one
photo. Cold start preserves VisualQA/default Identity/star filter1 without starting
Live automatically. Code40/0.9.1 is verified, App crash filtering has zero matches,
and font/night/rotation/wm size/animator settings are restored. Final dark picker,
star-filter and Live screenshots are visually checked. The phone has not received
0.9.1. No new physical-camera,
background-transfer or lock-screen acceptance is claimed.
See [0.9.1 validation](../dist/nikon-usb-0.9.1-beta-VALIDATION.txt).

## Project receipts, stars and default presets (0.9.0)

Photo Live projects expose a local receipt sheet with saved/transferring/retry counts,
All/Pending/Saved filters, filenames, timestamps, sizes and concise errors. Saved
thumbnails come from local files only; opening records makes no camera request.
Receipt rows also expose automatic-effect state. Explicit actions retry unfinished
work or continue the selected existing project without creating another project;
local effect failures can retry while offline.

The viewer can persist a star for a photo. Project and Downloaded grids offer a star
filter; binding the downloaded original retains its camera-photo star across Activity
recreation. Removing a star leaves the photo unchanged.

A new or existing project can select a validated local default preset. Newly
discovered photos save their original first, then enter an application-owned serial
local effect queue. Changes/disablement apply only to later photos; queued work
retains its original preset snapshot. Reception takes priority, and automatic work
waits for a manual preset batch without replacing it. Interrupted queued/processing
records recover; failures wait for explicit retry. Existing content-addressed output
and gallery publishing avoid duplicate effects on retry. Sources must be completed,
owned local originals of the recorded byte length. Existing NCP phone approximation
and original-preservation behaviour apply; this adds no camera-native renderer.

Final source is frozen and the build passes. APK: 2,929,273 bytes, built
2026-10-17 21:59:24 +08:00, SHA-256
`ed5bab298536698a323c18b3ee9c58747f77f3647bcfd851ef0755dde23f86ee`.
Current host runs are recorded separately: stars 178; project preset store 146;
receipts 105; controller focused 42, controller 796, project store 355, folders 100;
automatic queue 23; photo presets 94 (batch 54/source 40). These group counts are
not summed, and host facades do not establish Android codecs, MediaStore or
foreground-service behaviour.

Android 17/API37 native checks use PreviewTone and a synthetic camera on port
15766. LiveQA/default setup and shot4's original/effect generation occur on the
earlier 6df candidate; final ed5 restarts retain those files and star. From offline
receipts, Connect leads through Settings back to the same LiveQA without another
name form. Final-package shots5/6, with duplicate events and transient busy/not-ready
responses, each save one original and one effect. Disabling the default makes shot7
save only its original. Four originals and three effects publish directly into the
correct MediaStore project folder, without a Save As picker. Star filters in Live
and Downloaded each show one photo; original/effect stars are independent, effects
support hold-original comparison, and restart retains settings. Final screenshots
verify the repaired hero/result-card layout retains a full thumbnail row. Three
server sessions report three Opens, four original transfers and zero CloseSession
or virtual writes, archived in
[synthetic protocol log](device-review/live-0.9.0-synthetic-protocol.log).
The earlier receipt screenshot is from 6df. Final dark/font1.3 receipts show all four
records, status/size/time/preset text and Continue without clipping. Stopping Live,
force-stopping and cold-starting preserves LiveQA's four originals, four records,
three applied effects and disabled default, without automatically restarting Live.
Version39/0.9.0 is verified; display settings are restored. App/FATAL EXCEPTION
crash filtering has no match; the crash buffer contains an unrelated emulator UWB
hardware-service failure, so the whole buffer is not claimed empty. The emulator
and synthetic server are stopped.

At 2026-10-17 22:14:35 +08:00, phone 286f0dba/PKG110/Android16 receives a
preserve-data install -r from 38/0.8.7 to 39/0.9.0. Services are empty beforehand;
no uninstall/data clearing occurs. Installed base.apk's complete SHA-256 matches
the final artifact, MainActivity launches successfully and PID10046 runs. Read-only
UI text shows the default-preset picker containing existing Agfa Optima; the user
is operating it, so no selection or dismissal is performed. No phone screenshot
export, real-photo selection/processing or camera connection occurs. No new
background-transfer, physical-camera, lock-screen or Doze acceptance is claimed.
See [0.9.0 validation](../dist/nikon-usb-0.9.0-beta-VALIDATION.txt).

## Nonmodal top notices (0.8.7)

Native TopNotice replaces ordinary prerequisite/status dialogs with rounded top
feedback using UiPalette light/dark colours. It has a one-line title, at most two
description lines and 44dp action/dismiss targets. Base duration is 4.5 seconds,
or 8 seconds with an action, respecting accessibility timeout settings. New status
updates replace/deduplicate an existing notice; page changes, pause and destroy
clear it, including tab changes inside Album. Action callbacks check their source
route so an old Connect action cannot recreate a live intent after leaving.
Root feedback uses short copy with optional details and revision deduplication.

Notices cover missing camera/preset/selection/project, busy/receiving tasks,
connection start/success/failure, a live connection without photo inventory and
brief save results. Related actions connect, add, read photos or open details.
Disconnected preset import still opens its guide and preserves pending selection
through slot confirmation. A disconnected Photo Live notice creates a continuation
only when its action is clicked; project confirmation is still required afterwards.
Long errors/failure lists, delete/overwrite/same-camera recovery confirmation,
background-protection choices and project forms keep dialogs.

Production build exits 0; v2/v3 signatures pass. APK: 2,876,025 bytes, built
2026-10-17 20:49:19 +08:00, SHA-256
`e0b9e01b1ddff3da64b564d3bc97e6ab9c7116347d82aa9f4e185c4227662e69`.
No host/protocol suite is added or rerun for this change; prior 18-check results
are historical.

Final Android 17/API37 emulator checks verify disconnected Live's Connect action
opens Settings and creates the typed intent, while switching to Downloaded dismisses
the old notice. A refused port shows a short failure banner; Details alone opens the
raw ECONNREFUSED dialog. Correct-port retry opens New Project, cancelled without
creation. Missing-project feedback expires after about 4.5s. Dark mode/font1.3 text
is not clipped; defaults are restored. HOME/return removes old notices/actions,
which is popup-cleanup acceptance only, not background-transfer acceptance.

Synthetic PreviewTone offline import opens its guide and top notice. Dismissing the
notice with X preserves pending import; connection continues to C2 confirmation,
without a final Import click. Album then receives three synthetic entries and
thumbnails through the same session handoff. Zero selected Apply shows selection
feedback and performs no processing. Two synthetic sessions each have one Open;
total CloseSession/virtualWrites are zero, with no original download or live project
creation. App crash filtering has no match; emulator and server stop afterwards.

Phone `286f0dba` / PKG110 / Android 16 receives preserve-data install -r at
2026-10-17 21:14:26 +08:00 after an empty service check. Version 38 / 0.8.7 and the
complete installed base.apk SHA match the final artifact; MainActivity starts
successfully. No data clearing, phone screenshot export, real-camera connection or
photo operation is performed. Physical top-notice UI, accessibility, Doze and
lock-screen behaviour are not newly accepted.
See [0.8.7 validation](../dist/nikon-usb-0.8.7-beta-VALIDATION.txt).

## Direct preset-import connection guide (0.8.6)

With no connected camera, Import Camera now opens the Wi-Fi guide directly while
retaining the selected preset. The guide explains that successful connection will
continue to slot selection. Its import-only actions are Switch to USB, which leaves
the guide while retaining the preset, and Cancel Import, which returns to the preset
detail and clears continuation. Successful connection uses the existing one-shot
continuation to confirmation and does not automatically write. Ordinary Settings
and Photo Live guides do not show these import-only actions. Album protocols and
Photo Live continuation behaviour are unchanged.

This low-impact navigation/copy change adds no host tests and reruns no host or
protocol suite. Production build exits 0 and v2/v3 signature verification passes.
APK: 2,867,833 bytes, built 2026-10-17 20:33:51 +08:00, SHA-256
`92582d4d10925b24b2264dfdf5eac12c08ce98e5b03c24a6fad85a7f277c87d7`.
The final Android 17/API37 emulator package uses synthetic PreviewTone.NCP (638 B).
Import from detail opens the direct guide with its message and both footer actions;
Cancel returns to that same preset. Switch USB shows a missing-device prompt, then
cancelling and Return Preset permit reopening the guide. Connecting to the loopback
camera after notification Allow automatically opens PreviewTone's C2-empty confirmation.
No final Import click occurs; cancelling returns to detail. An ordinary Settings guide
has neither import message nor import footer. The emulator-only guide screenshot was
visually checked. Protocol: one Init/Open, zero CloseSession, StorageIDs/Handles or
virtual writes; capability 0x90cf and slot 0x90cc reads only. App crash filtering has
no match. The emulator and synthetic server exit after checks with no test process left.

Phone `286f0dba` receives preserve-data install -r at 2026-10-17 20:34:52 +08:00,
version 37 / 0.8.6. Read-only checks open Agfa Optima -> Import -> direct guide and
confirm both footer actions and 192.168.1.1:15740. USB is then unplugged: phone Cancel
return, installed base-APK hash and project-detail recheck are not verified. There is
no real-camera connection/write or real-photo selection. Installation does not clear
app data; this does not imply an installed-hash match or new project audit.
See [0.8.6 validation](../dist/nikon-usb-0.8.6-beta-VALIDATION.txt).

## Resume Photo Live setup after connecting (0.8.5)

Entering Settings from disconnected Start Live retains a one-shot setup intent.
After the user explicitly attempts connection, successful connection and a ready
photo inventory return to Photo Live and open New Project. Reception still requires
the user's project name/confirmation. Connection failure permits retry; cancellation
or leaving clears the intent. An ordinary Settings connection does not open a project,
and the Photo Live intent is mutually exclusive with preset-import continuation.

The pure-Java `PhotoLiveContinuation` uses revision tokens so stale queued work cannot
consume a cancelled or replacement request; it retains no Activity or Runnable.
`tests/run-live-continuation.ps1` passes **18 host checks**, including explicit-connect
gating, failure/retry, one-shot consumption, cancellation and replacement isolation.
These checks do not establish Android navigation/lifecycle behaviour. Other existing
protocol suites were not rerun. Production build exits 0 and v2/v3 signatures pass.
APK: 2,867,833 bytes, built 2026-10-17 20:23:28 +08:00, SHA-256
`349988368a0e9f986d3b542d9434a380e84aa9537485a62ede711ceed9687519`.
Final Android 17/API37 emulator checks use a loopback synthetic camera. Disconnected
Photo Live leads to Settings, USB offers Wi-Fi guidance, and successful connection
opens New Project automatically. Cancelling leaves zero projects and switching
Settings/Album does not reopen the dialog. A refused port followed by a correct-port
retry also opens the dialog. After cancelling continuation, ordinary Settings connect
stays on Settings. Each of the two automatic-continuation runs has one OpenSession;
the same session skips unavailable storage 65537 and reads three handles from 131073.
No project is confirmed, live reception started, original downloaded or write issued.

Phone `286f0dba` receives a preserve-data update at 2026-10-17 20:26:51 +08:00;
code 36 / 0.8.5 and installed base SHA match the artifact. Read-only checks retain
Agfa Optima and the existing project's two originals. Start Live -> Settings shows
return/continuation guidance and returns successfully, without connecting a real
camera, creating a project or touching photos. Services report nothing at 20:28:37.
All three synthetic-session summaries report zero virtualWrites and CloseSession;
the service and emulator are stopped afterwards. App/FATAL EXCEPTION crash filtering
has no match; the system UWB hardware-service crash is unrelated to the App.
No new background or Activity-lifecycle native acceptance is claimed.
See [0.8.5 validation](../dist/nikon-usb-0.8.5-beta-VALIDATION.txt).

## Photo selection placement and prerequisite feedback (0.8.4)

Photo Live selection sits on the project-photo count row below the hero image;
empty projects hide that selection action. The hero fits remaining layout space,
reserving at least one thumbnail row; below 80dp it hides while the count/selection
row remains. Gone hero height is excluded and layout retries are bounded.
Starting live reception without a
camera offers Settings, while an existing session without a ready photo inventory
offers photo preparation. Zero selected photos, missing local presets and active
download/save/delete tasks explain what is needed before proceeding. Permission
returns recheck current prerequisites. Local-only photo batches still work offline.

Advanced Diagnostics keeps prerequisite-blocked actions explainable: missing NCP
offers file selection, missing reports or valid slot snapshots offer read-only
inspection, and missing USB/permission explains connection and detection. Running
operations retain duplicate-submit protection with progress. Choosing a prerequisite
file or running a read-only prerequisite check does not resume a camera write; explicit
write confirmation and protocol verification remain in place.

This change ran **94 photo-preset host checks** (54 batch + 40 source links), exit 0,
before the final prerequisite-entry and responsive-layout UI changes; they were not
rerun afterwards. Log: `app/build/v084-photo-presets-tests.log`. Build and v2/v3
signature verification pass (`app/build/v084-build.log`). The final artifact is
2,863,737 bytes, built 2026-10-17 19:47:10 +08:00, SHA-256
`9db6d2085671953d375980d0e64c3e0b00cb52b243c5d3b1c7179063b656b7b0`.
The final emulator package retains FeedbackQA, two synthetic originals and the prior
effect. Offline PreviewTone completes 1/1 by reusing the effect and saving it to the
system gallery. Default light layout shows the hero, both full thumbnails and Select;
375dp/font1.3 dark layout with the completed-task card hides the hero as necessary
while keeping both thumbnails and Select visible. Both final screenshots were viewed.
Final Diagnostics checks missing USB, NCP and report feedback with their related
read-only/file-selection entries; all dialogs were cancelled. App crash log and
services are empty. Earlier candidates alone cover the original synthetic reception
and missing-preset Add-to-file-picker check; they are not final-package reruns.

Phone `286f0dba`, user 0, receives a preserve-data update at 2026-10-17
19:48–19:49 +08:00. Code 35 / 0.8.4 and installed base SHA match final 9db6.
Read-only checks retain Agfa Optima and the existing live project's two originals;
Select is beside the count. Disconnected Start Live offers Settings and zero-photo
Apply asks for selection; both are dismissed without selecting real photos.
No real photo processing/deletion or phone screenshot export is performed. Final
phone crash log and services are empty; the phone remains on Photo Live overview.
Prior-version controller, date, deletion and rendering results are not new runs.
See [0.8.4 validation](../dist/nikon-usb-0.8.4-beta-VALIDATION.txt).

## Downloaded date groups and local deletion (0.8.2)

Downloaded photographs group by capture date in the local time zone. A separate local
worker reads EXIF DateTimeOriginal, then DateTimeDigitized / DateTime; valid offset
tags are respected and missing/bad metadata falls back to saved-file time. The date
reader caches raw fields by canonical path, byte length and modification time with a
4,096-entry LRU, supports cancellation and never rewrites JPEGs. Pure metadata grouping
orders days and timestamps newest first, retaining input order for equal timestamps;
today/yesterday and cross-year labels use local calendar days rather than 24-hour gaps.

Full-width date headers show photo counts and select/cancel the day. Recycled photo
rows keep the list virtualized instead of inflating the library. Selection uses stable
file paths. The viewer has single-photo trash and the downloaded selection bar has
batch delete. Explicit confirmation covers the App file, its corresponding published
system-gallery copy and project-original membership. The camera card is untouched;
deletion issues no camera write command. Active live reception, publication and preset
processing exclude deletion. Per-photo failure retains the App source for retry.

The focused host runs all exit 0, totalling **1,564 checks**: controller 702 + project
store 355 + folder 100 + Gallery/Saver 171 + production deletion 20 + batch 54 + source
association 40 + date grouping 55 + date reading 67. Dates use real Java calendar/file
tests with an injected EXIF facade; these checks alone do not establish Android EXIF,
MediaStore or physical-phone deletion behaviour. Backend outputs are retained in
`app/build/v082-delete-album-tests.log` and `app/build/v082-delete-focused-summary.log`;
the independent date runner is `tests/run-photo-dates.ps1`.

The final APK is **2,855,545 bytes**, SHA-256
`b2eb2ecbda09a715837b38166db0e8fc72ae29f0065740b8a8d75d8f36f8e1ee`, built
2026-10-17 17:16:47 +08:00, with v2/v3 signature verification passed. On Android 17,
three synthetic JPEGs pushed together group correctly by actual EXIF as today,
yesterday and 2025-12-31. Whole-day selection and cancelling delete work. Synthetic
DeleteQA receives original 4 (741,289 bytes); after stopping live reception, Tone
produces its 2,036,914-byte effect. Downloaded totals five: today three, yesterday
one, previous year one. Confirmed batch deletion of TEST4 and its effect reduces
private photos 5→3 and their MediaStore rows 38/39 from two to zero. The project
remains with 0 photos / 0 B; no ghost thumbnail remains, and connection stays green.
HOME/return during deletion captures foreground-service type 0x11.

Saving TODAY from its viewer publishes row 40; confirmed viewer trash removes both
the App original (3→2) and its one gallery row (1→0). Today's group disappears, leaving
yesterday and the previous year. These deletions target synthetic emulator photos
only. Final-package dark QA at 375dp / font scale 1.3 passes date-header/count,
whole-day-selection and delete-bar layout checks. After explicitly disconnecting in
Settings, force-stop/cold-start retains DeleteQA at 0 photos / 0 B; deleted App photos
and gallery copies do not reappear. The app crash log is empty. Protocol audit counts
one InitCommand / InitEvent / OpenSession set, zero DeleteObject (0x100b), zero
virtualWrites and zero CloseSession. Transport closes only after the explicit Settings
disconnect; deletion does not interrupt it.

Phone `286f0dba`, user 0, preserve-data install -r succeeds at 2026-10-17 17:28 +08:00.
Code 33 / 0.8.2-beta and installed base APK SHA match the final b2eb artifact. Read-only
UI checks retain Agfa Optima and all 11 downloads, grouped today 10 and August 14 one.
Selection mode opens at zero selected and is cancelled; no real phone photograph is
selected or deleted, and no phone screenshot is exported. App crash filtering is
empty and activity services report nothing; the phone is left on Downloaded overview.
This is not a phone deletion, new hardware-camera or Doze acceptance. See
[delivery](../dist/nikon-usb-0.8.2-beta-VALIDATION.txt) and
[functional checkpoints](device-review/album-0.8.2-functional.log).
Original comparison and the NCP phone-simulation capabilities remain unchanged.

## Effect viewing and original comparison (0.8.1)

The completed batch card exposes 查看效果 and opens an actual derived result from that
batch. In the effect viewer, the lower-right 原图 control momentarily displays its
associated input; release restores 预设效果. The shared HoldToCompare control preserves
the PhotoImageView zoom and normalized image position rather than fitting the image
again on every comparison. Cancel, movement outside the control, multiple pointers,
lost focus, hidden/detached views and the Activity pause path restore the effect.
Keyboard and accessibility click toggles remain supported.

Original association lookup, verification and decoding run off the UI and camera
workers. Request and photo identity checks discard stale results after navigation,
so an earlier original cannot replace a different currently displayed effect. The
owner releases the comparison before retiring its bitmaps; HoldToCompare does not
own or recycle them. Missing or changed source files leave comparison unavailable.

Newly rendered effects and verified cache hits durably bind the output to the exact
bytes of their immediate batch input, committed preset and renderer model. Older
0.8.0 output ledgers can be migrated lazily only when their verified output content
key matches source content + a committed preset + the same model. Filename proximity,
project membership and a visual resemblance are insufficient. Migration is bounded
and cancellable; unsuccessful lookup leaves the existing output intact.

This patch adds result navigation and comparison. It does not strengthen the NCP
phone simulation, add supported parameters or introduce a new rendering pipeline.
The full-resolution JPEG95 approximation and the historical 0.8.0 renderer evidence
below remain unchanged in scope.

This run passes **84 focused host checks**: batch 44 + source association 40.
Production compilation and APK v2/v3 signature verification pass. The older 1,264
0.8.0 checks below are not relabelled as this patch's rerun. On Android 17, an earlier
0.8.1 candidate (SHA prefix `4516`) preserve-data upgrades a 0.8.0 installation that
created a 24MP / EXIF orientation-6 Tone effect; its old output record migrates lazily
and press/release comparison succeeds. That migration interaction retains its
candidate scope rather than being called a final-artifact repeat.

The final APK is **2,835,065 bytes**, SHA-256
`df2d621cb589ec4ced80806c8f4a8f0be3442f3a5e94eb5a4ad40b618dd5115e`, built
2026-10-17 16:45:33 +08:00. On Android 17 it reapplies Tone to the existing effect,
finishes 1/1 through HOME, and 查看效果 opens the actual `Effect_PreviewTone` derived
result (viewer size 1.4 MB). All 11,050 sampled screen points differ while Original
is held, and none differ after release. Before-press and after-release whole PNG
hashes are identical separately at fitted and enlarged views; same-sized comparison
avoids floating-point transform drift. HOME while holding restores the effect on
return. From a fresh fitted view, a quick left swipe reaches an original without the
comparison button; swiping back restores a ready effect button. The app crash filter
is empty.

Phone `286f0dba`, user 0, was preserve-data updated around 2026-10-17 16:48 +08:00;
code 32 / 0.8.1-beta and installed base APK hash match the final artifact. Eleven
downloaded photos, including two older effects, and Agfa Optima remain visible.
The first older effect (viewer size 2.3 MB) has its Original/Preset Effect press/release
UI states checked through UIAutomator only. Exporting a real-photo screen capture was
rejected by automatic approval review and was neither executed nor retried; no phone
pixel comparison was performed. The 11,050-point and PNG-hash evidence above is solely
from synthetic emulator images. The current phone app PID's crash filter is empty.
No new phone batch ran, and no camera service was active. This patch does not establish
new physical-camera, PTP or hardware-background results. See [delivery](../dist/nikon-usb-0.8.1-beta-VALIDATION.txt)
and [functional checkpoints](device-review/photo-presets-0.8.1-functional.log).
The NCP simulation algorithm and renderer capabilities are unchanged.

## Batch presets and direct gallery saving (0.8.0)

Users select 1–100 camera, downloaded or live-project photos and a committed local
preset. Remote sources use the existing camera download boundary; local files are not
redownloaded. An application-owned serial local worker survives Activity rebinds and
keeps image/EXIF/copy work off the camera worker. Live publication is asynchronous too,
so the gallery lock cannot hold up camera transactions. Cancel drains an in-flight
original callback before stopping and checks between pixel strips/items. Per-photo
errors are retained while later photos continue; unsupported presets fail explicitly.

This is the existing phone approximation for the verified NCP 0100 curve subset, not
Nikon's native Picture Control pipeline. Full-resolution rendering decodes one mutable
sRGB ARGB8888 bitmap at sample size 1, uses a bounded reusable strip in place, and writes
a separate JPEG at quality 95. Memory shortage fails that photo without downsizing.
Orientation/capture EXIF is copied by whitelist, sRGB dimensions are verified and the
unfiltered embedded thumbnail is omitted. Original files are never rewritten.

Completed originals and effects publish directly through PhotoLiveGallery. API 29+
uses MediaStore; API 26–28 uses permission-gated public Pictures plus media scanning,
with only host-facade checks for that older path so far. Content-source/preset/model
keys and output checksums reuse derived results; a failed gallery publish leaves the
complete local effect for retry. Effects stay visible in 已下载 and retain a source
project's stable gallery directory, but are not recorded as live-original membership.

The complete focused run passes **1,264 checks**: controller 683 + project store 342 +
folder 100 = album 1,125, Gallery 99 and batch 40. The 15:44:35 / `76d563a6…` candidate
verifies concurrent live arrivals, 24MP dimensions/EXIF, repeat deduplication and cancel
on Android. An earlier candidate additionally verifies unchanged direct-copy bytes and
background Activity rebind. The final runtime/service routes progress separately from
root-page rebuilding; the preset chooser uses content-height rows and a capped,
scrollable list. Renderer and batch algorithms are unchanged. Final APK: **2,830,969
bytes**, SHA-256 `9ae39f02e4373bbfa1a5b0cdb3cd4c98d9d10a6913ee56b57602c387a3bb1be2`,
built 2026-10-17 15:57:58 +08:00. On Android 17, the final artifact completes a new
offline batch 1/1 through HOME and Activity clear-task/recreation, publishes a
1600×1000 effect, and releases its standalone mediaProcessing service afterward.
The dark 375dp / 1.3-font chooser fits its single preset to content. The app crash
filter is empty; 24MP/concurrent-live results retain their candidate scope.

PKG110 user 0 was preserve-data updated to code 31 / 0.8.0-beta around 2026-10-17
15:59 +08:00; the installed base APK hash matches. The phone remains at its password
lockscreen, so this confirms installation only, without a new UI/project-count,
physical-photo batch or camera-connection check. See [delivery](../dist/nikon-usb-0.8.0-beta-VALIDATION.txt)
and [functional checkpoints](device-review/photo-presets-0.8.0-functional.log).

## Project details and rename (0.7.2)

Project details expose creation time, photo count and aggregate received bytes, plus
rename. `PhotoLiveProject.totalBytes` sums persisted entry sizes on the worker rather
than reading original-file statistics on the UI. Rename atomically retains UUID,
creation order, serialized photo entries and original files. Invalid/failed edits
leave the old name, and a normalized no-op does not rewrite the record.

An independent immutable project-folder binding is committed before the mutable name
changes. Existing MediaStore rows stay in place and later photos/retries retain that
same directory; the existing `.project` format is unchanged. The matching live run and
saved candidates update names only after durable success, without replacing the queue,
changing published URIs or reconnecting the camera.

The final production album host run passes **1,094 checks**: controller 652, store 342
and folder binding 100. The final APK is **2,806,393 bytes**, SHA-256
`da0a8ecae6f842a1a0c8740fdafe01d9db1b7d540ddb1f0c96a1a8fad444b5c4`,
built 2026-10-17 15:14:43 +08:00 after the final UI error-tip fix; production is frozen.
An earlier 0.7.2 candidate, with the same backend before that UI-only fix, performed the
old 0.7.1 project's first offline rename. The final installed emulator hash/code matches;
force-stop/cold-start retains Renamed_Project with two photos, 176.7 KB and creation
time 7:11. Invalid `bad/name` shows a fully visible inline error and cancel preserves
the name. In a separate final-artifact live run, Live_Original receives photo 6, becomes
Live_Renamed, then receives missed-event photo 7. Details update to two photos / 178.9 KB
and latest TEST_0007.JPG. Rows 46/47 stay pending=0 under the original Live_Original
directory; old rows 38/39 stay in Old_Project. Active rename does not reconnect or send
CloseSession. Candidate and final transfers are recorded separately.

Final detail QA covers dark 375dp / 1.3 font with animations off and landscape. Scrolling
reaches the latest-photo note and bottom rename/close controls; fields are not claimed
simultaneously visible in landscape. Emulator settings were restored, reception stopped
and disconnected before the last cold-start, and the app crash filter is empty.
See [narrow detail](../.impeccable/review/phone-apple-v072-info-dark-narrow.png),
[landscape](../.impeccable/review/phone-apple-v072-info-landscape.png) and
[inline validation](../.impeccable/review/phone-apple-v072-rename-inline.png).

PKG110 was preserve-data updated 29→30 at 2026-10-17 15:19:36 +08:00 with services empty
before installation; its installed base hash matches. Project `2026-10-17 14:44` still
shows two photos / 14.6 MB, latest DSC_6138.JPG and creation time 14:44, with details and
rename visible. The phone was left offline in its info dialog without restarting live
reception. No real project rename or camera operation was performed. See
[functional checkpoints](device-review/projects-0.7.2-functional.log) and
[delivery validation](../dist/nikon-usb-0.7.2-beta-VALIDATION.txt). Earlier protocol/Doze
evidence remains historical; this patch does not establish new physical-camera results.

## Project selector and upgrade retention (0.7.1)

The selected project is a rounded 48dp control with a separate `CHEVRON_DOWN` vector;
long text uses END ellipsis rather than incorporating a down-arrow character into its
name. The chooser distinguishes project date/photo count, selected check and active
reception status. The underlying durable project/original format is unchanged. The
final production store classes pass **249 checks**; this patch does not claim a new
complete protocol/core/probe or Doze run.

On Android 17 / Pixel 9, the older 0.7.0 APK created the long-name project
`AutumnPortraitSessionAtTheLakesideAndOldTown2026` with two synthetic photos (4/5) and
empty Weekend. Reception was stopped and the peer disconnected before a preserve-data
upgrade to final 0.7.1. Both projects, offline switching and the correct empty state
remain; force-stop/cold-start retains the selected long-project ID and both photos.
Light/dark selector/menu screenshots are legible. The installed emulator base hash
matches the final artifact, app services are empty and the app crash filter is empty;
unrelated emulator UWB service failures are not app crashes. `run-as` is unavailable for
this non-debuggable package: retention evidence is the UI plus host tests, not a private
Android disk inspection.

The final APK is **2,802,297 bytes**, SHA-256
`462c508e4f335cf0bf1dd473af6b419d6f8e81a05a727b92bddfb30b1b293220`,
built 2026-10-17 14:51:09 +08:00 with no later production changes. The initial delivery
left PKG110's active 0.7.0 live session untouched. After the user explicitly requested
installation, UI confirmed live reception stopped and the existing project contained
two photos. The idle camera connection was manually disconnected and services were
empty before preserve-data installation to code 29 at 2026-10-17 14:59:56 +08:00.
Installed base hash matches the artifact. Post-install phone UI shows the new selector
and project `2026-10-17 14:44` still containing two photos; connection/live reception did
not resume. The phone remains on Photo Live with its existing project. No active
transfer was interrupted. This is not a new protocol/Doze/camera-capture test.
See [delivery](../dist/nikon-usb-0.7.1-beta-VALIDATION.txt),
[functional checkpoints](device-review/projects-0.7.1-functional.log),
[selector](../.impeccable/review/phone-apple-v071-project-selector-light.png),
[light menu](../.impeccable/review/phone-apple-v071-project-menu-light.png) and
[dark menu](../.impeccable/review/phone-apple-v071-project-menu-dark.png).

## Fast startup and named projects (0.7.0)

Startup creates the named durable project before reading camera photos, then captures
fresh `GetStorageIDs` and per-storage `GetObjectHandles` inventories. Readiness does not
wait for per-old-photo ObjectInfo, preview or original requests. New events, discovered
new photos and queued originals take priority. Old descriptors are optional idle work,
at most one per reconciliation; the first descriptor for an unknown baseline handle
only primes its identity. Reuse before that first sample is not guaranteed detectable.
The first readable inventory of a previously unavailable slot still forms its baseline;
an event cannot bypass that boundary.

`cancelBrowseRequests()` uses a separate browse token to cancel queued pages and
thumbnails. One in-flight response drains before the worker yields, preserving the
same camera connection. Entering Photo Live does not preload the camera's first grid.

Each named run receives an immutable project ID; equal display names remain separate.
Projects persist original-file membership for offline history, a three-column virtualized
thumbnail grid and detail paging limited to the selected project. Existing downloaded
files stay in place and remain available under 已下载; creating projects does not move
or import earlier files. Checksummed atomic snapshots reject damaged records, and new
records verify app-directory ownership, complete JPEGs and source hashes. A completed
original survives classification/publication failure; retry runs those local steps
without another camera download. Android 29+ project publications use
`Pictures/相机预设/<safe-project-name>-<id8>/`; previously published URIs remain reusable.

The local synthetic server passes **238 checks** (51 existing live + 117 dual-storage +
60 large-baseline + 10 argument checks). `--photo-live-synthetic --live-baseline-count=10000`
provides handles 1–3 and 100–10096 using shared in-memory fixture buffers, leaving 4–49
available for new-photo injection; it also combines with `--live-storage-synthetic`.
The complete 0.7.0 run passes **10,595 core** (626 controller + 106 project-store),
**12,089 probe** and **238 server** checks: **22,922 total**.

The final APK on Android 17 / Pixel 9 starts Studio_A against 10,000 old photos and a
continuously unavailable first slot: one fresh StorageIDs plus two Handles requests,
and zero old metadata/preview/original requests before ready. Later idle sampling stays
at one descriptor per scan; duplicate and missed events receive only new photos.
HOME/screen-off reception and an original busy response recover correctly, and a
missed-event photo arrives during forced deep Doze IDLE. This run confirms OFF/Asleep/IDLE,
not a displayed secure keyguard (showing=false). Studio_A keeps four photos and Studio_B
three; B arrivals do not replace a selected A project. Project grids/detail boundaries
and offline force-stop/relaunch preserve membership. All seven project MediaStore rows
are pending=0 and their hashes match the fixtures. Stop blocks the next photo; explicit
disconnect releases service/CPU ownership. One Init/Open, no CloseSession or writes.

The final 2,798,201-byte APK (code 28; SHA-256
`2381807a1b02b5af059304ef1aa287f54759ea68256f41396f23a769fdf1b3c5`)
was preserve-data installed on PKG110 / Android 16 user 0 at 2026-10-17 14:40:59 +08:00,
27→28; installed base hash matches and the original Agfa preset/downloads remain.
No production source changed after the 14:33:33 build. Installation waited until the
camera session/live task were stopped and foreground services empty; no phone battery
or permission settings changed, and no real-camera capture/import was initiated.
Z6II speed and hardware secure-lock behavior are not certified by these synthetic checks.
See [delivery validation](../dist/nikon-usb-0.7.0-beta-VALIDATION.txt),
[functional checkpoints](device-review/live-0.7.0-functional.log),
[baseline](device-review/live-0.7.0-baseline.log), [Doze](device-review/live-0.7.0-doze.log)
and [protocol audit](device-review/live-0.7.0-audit.md). Historical evidence below remains
limited to its stated version.

## Photo Live

The album has a **照片直播** tab. The user connects a browse-ready camera and explicitly
starts reception. Existing camera objects form a baseline and are not downloaded. New
JPEG identities discovered afterwards enter the receive queue; RAW files, video and
remote shutter control are not implemented.

The existing PTP/IP event reader forwards validated `ObjectAdded (0x4002)` handles to
the owning controller. A 3-second `GetStorageIDs` / `GetObjectHandles` reconciliation
compensates for missed events, with one optional idle metadata check per reconciliation
to detect changes after a handle's first descriptor has been sampled.
Duplicate events and polling share the same identity rather than creating duplicate
downloads. All commands stay on the album's single serial worker; discovery does not
create another connection or event reader.

For each new JPEG, the worker first obtains the supported camera preview, publishes it
to the existing preview cache and yields before receiving `GetObject (0x1009)`. A missing
preview does not become a substitute original. Completed originals are committed only
after size, JPEG completeness and before/after ObjectInfo identity checks. Stable
camera/storage/handle/ObjectInfo fingerprints, deterministic local destinations and a
durable completion journal verify saved-file hashes and prevent duplicate reception
across controller replacement. Partial and changed files are not exposed as completed.

The original is saved in the app's picture directory. On Android 10 / API 29 and newer,
`PhotoLiveGallery` additionally publishes it through MediaStore into
`Pictures/相机预设/<safe-project-name>-<id8>/` for named projects (the original root path
for unclassified legacy files), checking ownership, pending-row state, byte count and content hash.
Its durable publication journal can recover an interrupted publication. Android 26–28
keep the completed app file without requesting legacy external-storage write access.
Gallery publication failure leaves the original intact; explicit retry publishes that
local file without downloading it again.

The waiting-transfer queue is bounded to 128. Successful recent history is trimmed
toward 120 entries, while queued/current items, unsaved originals, original failures and
gallery failures retain their retry identities. Total retained entries are bounded to
256. Reaching either bound defers new metadata inspection with a finite retry deadline;
it does not consume camera-error retries, mark a skipped identity as accepted or discard
outstanding work. Completing/retrying older work allows discovery to resume. Complete,
matched temporary camera responses use bounded backoff; invalid framing, wrong
transactions, lost sessions and event-channel EOF still terminate the unusable owner.

The controller and queue are application-owned. Changing tabs, opening another page or
replacing the Activity does not stop reception; the existing connection foreground
service remains responsible for background execution. **停止接收** cancels queued work
and lets an in-flight framed read drain without closing a healthy camera connection.
Preset ownership handoff also pauses reception and drains current reads; it does not
automatically reconnect or restart live mode. System process termination or a camera
turning off its hotspot cannot be treated as continuous reception.

## Temporarily unavailable card slots (0.6.1)

A complete, correctly matched `StoreNotAvailable (0x2013)` response describes camera
storage, not proof that the Wi-Fi link disconnected. Album enumeration and live scans
read each storage independently, skip unavailable slots and continue with accessible
cards. A temporary outage retains previously observed handles and identities, so a
recovering card does not turn already known photographs into new receptions. The first
successful inventory of a slot that has never been readable forms that slot's baseline.
If all slots are unavailable, the healthy session stays owned and waits with backoff;
there is no automatic reconnect.

If a slot becomes unavailable while initial ObjectInfo metadata is being read, its
remaining baseline metadata is paused without blocking another card's initialization.
The captured baseline handles remain excluded from new-photo reception. Invalid
transactions, malformed datasets/frames, lost sessions and real EOF still terminate an
unusable connection; the storage response does not relax those protocol boundaries.

## Background and screen-off protection (0.6.1)

The existing application-owned queue, connected-device foreground service, CPU partial
wake lock and per-socket camera Wi-Fi binding remain in use. Ordinary navigation,
HOME and Activity UI detachment do not stop reception. Settings now reports actual
battery-optimization exemption and notification/channel visibility. When starting live
mode without exemption, the user can open the system exemption request or choose
foreground use. Return handling queries the current exemption directly rather than
requiring a result payload; cancellation without exemption does not start reception.
Destroyed Activities clear the pending action rather than replaying it after recreation.

The low-latency Wi-Fi lock is active only with the screen on and the app in the
foreground; Android 34+ also maps high-performance lock requests to that restricted
mode. It is not a screen-off guarantee. Doze normally restricts networking and ignores
wake locks; user-granted battery exemption permits network access and partial wake
locks. See the official [Wi-Fi lock reference](https://developer.android.com/reference/android/net/wifi/WifiManager#WIFI_MODE_FULL_LOW_LATENCY)
and [Doze guidance](https://developer.android.com/training/monitoring-device-state/doze-standby).
Process termination, OEM background controls, actual network loss and a camera turning
off its hotspot remain outside a continuous-connection guarantee.

The physical PKG110 phone already had battery exemption during this round's read-only
inspection; its reported storage error must not be described as a missing exemption.
No phone power setting or physical-camera shooting was performed. Final 0.6.1 validation
is recorded below separately from the historical 0.6.0 results.

## Shared camera connection

Preset import and album browsing can hand the same healthy, idle physical Wi-Fi/PTP
session to each other. The two sockets, open session, single event reader and increasing
transaction sequence continue without another handshake. The former owner is revoked;
the album owner has read-only permissions. Returning to import still requires fresh
capability/slot checks and explicit confirmation, with no automatic write replay.

All pages report the shared live connection separately from photo-browsing readiness.
The album reads the actual device capabilities and photo index after handoff. Missing
browse capabilities or a complete, matched rejection that does not invalidate the
session can leave the camera connected with photos temporarily unavailable; retry uses
that same session. Invalid datasets, wrong transactions, session loss and EOF terminate
the unusable owner. Normal navigation does not close the connection.

## Viewer gestures

- The top bar contains back and filename only. Visible previous/next buttons and the
  counter were removed in 0.5.8. The bottom is one row of original-file size and a
  compact Download/Save-as action, at least 88dp wide and 44dp high.
- At fitted scale, horizontal dragging draws the current and neighbouring page together
  on Canvas at the finger's offset. Release displacement and velocity determine whether
  a critically damped spring completes the page change or returns it to centre. Edges
  resist dragging; another touch grabs the current presentation offset and can reverse
  the transition. Switching photos resets the view without downloading an original.
- When zoomed, one-finger dragging pans the photo without switching it. Pinch zoom tracks
  the moving finger focus, and releasing one finger re-anchors the remaining drag.
  Multi-touch and cancellation do not commit a page change.
- Double-tap animates between the fitted and enlarged view, respecting disabled system
  animations. A new touch can interrupt the animation.
- Upgrading the same photo from an initial preview to a larger preview or an explicitly
  downloaded original preserves its relative zoom and image centre. Bitmap replacement
  keeps the existing gesture surface instead of rebuilding it during a pinch.
- Neighbour pages reuse cached previews or request bounded previews ahead of navigation;
  they never fetch an original. Loading/error placeholders can still be swiped away.
  Tapping a failed remote preview retries it. At a paused metadata-page boundary, a
  left swipe or accessibility forward action retries the page rather than inventing a
  neighbour. Forward/back accessibility scroll actions remain available without visible
  navigation controls.

## Preview selection

- A connected Nikon camera must advertise `GetLargeThumb (0x90C4)` in the current
  `GetDeviceInfo` operation list before it is used. The operation receives exactly one
  valid photo handle and is bounded to 8 MiB.
- Both visible grid cells and the immersive viewer prefer this larger camera preview.
  Existing grid request limits, paging cursors, cancellation generations and the shared
  connection worker remain in effect. The grid decodes to its display size, while the
  viewer retains greater detail with a 4-megapixel decoded-bitmap limit and ARGB colour.
  Grid geometry is captured on the main thread; both remote and downloaded grid images
  use strict sampling with a 320-pixel cap and a 10 MiB visible-set budget within the
  existing 12 MiB bitmap cache. This prevents larger ARGB thumbnails from repeatedly
  evicting visible neighbours and triggering continuous network or disk reads.
- Only a complete response with the correct transaction and `OperationNotSupported
  (0x2005)` or `NoThumbnailPresent (0x2010)` permits a fallback to advertised `GetThumb
  (0x100A)`. A 0x2005 rejection disables repeated large-preview requests for that camera
  session; a missing thumbnail does not disable the operation globally.
- Busy and other camera rejections are reported without changing representation or
  downloading the original. Incorrect transactions, malformed framing, interrupted
  streams and declared transfers over the cap terminate the unusable connection.
  Invalid JPEG content is reported without silently requesting another representation.
- Preview metadata retains source provenance and actual decoded pixel dimensions;
  no fixed resolution or 2-megapixel guarantee is inferred from an advertised operation.
  The 0.5.6/0.5.7 viewer showed these values; the compact 0.5.8 chrome shows original-file
  size instead. The preview cache has at most two completed entries/16 MiB and keeps
  displayed entries pinned until released.

## Protocol evidence

The upstream libgphoto2 implementation defines Nikon `GetLargeThumb` as a read with
one object-handle argument and a returned data phase:
[ptp.c, ptp_nikon_getlargethumb](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptp.c).
The operation identifier is defined in
[upstream ptp.h](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptp.h).
Its public Z 6_2 V1.50 device-information capture includes operation 0x90C4:
[Nikon Z 6_2 capability capture](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/cameras/nikon-z6-2.txt).
This capture is a compatibility lead, not evidence that a particular camera's current
firmware/connection mode supports the operation; runtime capability and response checks
are mandatory. The implementation does not assume or copy private SnapBridge behaviour.

## Host validation

The complete **0.6.1** core suite passes **10,381** checks, including **518 production
album-controller** checks. The complete probe suite passes **12,089**; the synthetic
server suites pass **168** (51 photo-live + 117 storage), for **22,638** total checks.
Storage regressions exercise per-slot refusal, retained old identities, recovery,
first-readable inventory baseline, all-slot waiting and initial ObjectInfo refusal
without blocking the other card. Invalid transaction/framing behavior stays terminal.

The final APK passed Android 17 / API 37 emulator checks with slot 65537 continuously
returning 0x2013, while slot 131073's initial 1–3 remained baseline photos. Both slots
temporarily unavailable left live mode enabled and the connection held, waiting for
storage. After recovery, HOME/background reception delivered 4 despite duplicate events.
The device then reported `SCREEN_STATE_OFF`, `INTERACTIVE_STATE_SLEEP` and keyguard
showing; photo 5 arrived with duplicate events and one original-busy retry. With the
user-granted battery exemption and forced deep Doze `IDLE`, event-free photo 6 was still
found by polling and saved. MediaStore rows 40–42 contain exactly those three live JPEGs
with `IS_PENDING=0`; their content hashes match the fixtures. Stop prevented preview and
original reads for the later 7 while retaining the connection. Settings showed actual
protection enabled; explicit disconnect removed the service and held CPU lock, with an
empty application crash buffer. The 468-line protocol log has one initialization/open
session, no CloseSession and no preset registration.

The final **2,785,913-byte** APK has SHA-256
`f458b989e30a6cc84206351f22d718b79e99209a0c419caa7ae07e950838e18c`.
Preserve-data installation upgraded PKG110 / Android 16, user 0, from code 26 to
**27 / 0.6.1-beta**, last updated 2026-10-17 14:10:20 +08:00; the installed base APK
hash matched. Its camera service was stopped before installation. No uninstall, data
clear, physical-phone permission change or real-camera capture was performed. The
final APK verified accepting system exemption and automatically starting on return;
denial/cancellation smoke checks used an earlier draft with the same control flow and
are not final-APK branch coverage. Physical Z6II shooting and hardware screen-off
reception still need user testing. Force-stop, OEM process termination and camera AP
shutdown remain outside a continuous-reception guarantee. See the
[0.6.1 delivery record](../dist/nikon-usb-0.6.1-beta-VALIDATION.txt),
[functional checkpoints](device-review/live-0.6.1-functional.log),
[actual screen-off/Doze state](device-review/live-0.6.1-doze.log) and
[protocol log](device-review/live-0.6.1-protocol.log).

The complete 0.6.0 core suite passes **10,309** checks, including **4,459 PTP/IP client**,
**446 production album-controller**, **53 preview-cache**, **43 swipe-classifier** and
**38 page-motion** checks. The complete probe suite passes **12,089**, and the local
live-server regression passes **51**, for **22,449** total checks. Framed synthetic
peers cover baselines, duplicate/missed ObjectAdded events, preview-before-original,
stable original and file integrity, busy backoff, listener replacement, cancellation,
handoff, terminal faults, RAW exclusion and gallery-publication retry using a completed
local original. Controlled-worker regressions also fill the 128-entry queue for six
rounds without exhausting metadata retries, recover its free slot, protect outstanding
history, and recover admission from the 256-entry identity cap. The local server's 51
checks validate dynamic additions, duplicate/no-event delivery and one-shot 0x2009 /
0x2019 responses over real loopback frames.

Host publication tests inject the publisher boundary; they do not execute Android
MediaStore. Separate final-APK emulator checks baseline six existing photos, including
4–6 added before startup, and receive only the later 7–11. These five complete originals
were retained in the app and published to MediaStore with `IS_PENDING=0`; their byte
counts and SHA-256 hashes matched the fixtures (7: 92,366B; 8: 94,893B; 9: 95,452B;
10: 93,561B; 11: 92,082B). Duplicate events for 7 did not duplicate its original, 8
recovered after temporary metadata/original rejection, and 9 arrived without an event.
All five had a large-preview request before their successful original transfer.

HOME/background reception delivered 10, and task removal/Activity recreation restored
four received photos on the same connection. A new 11 did not replace the user's open
view of 10. After Stop, the added 12 was not received. Read-only preset capability and
nine-slot inspection, followed by return to the album, retained the same connection and
did not restart live mode. Final manual disconnect closed the transport and released
the connection service; PID 9005 had no crash log. The 834-line frozen protocol log
contains one command/event initialization and one OpenSession, six GetObject requests
(one busy rejection, five complete originals), zero CloseSession and zero preset writes.
See the [functional checkpoints](device-review/live-0.6.0-functional.log) and
[independent protocol audit](device-review/live-0.6.0-audit.md).

Preserve-data installation on PKG110 / Android 16, user 0, succeeded at
2026-10-17 13:49:19 +08:00 with code 26 / 0.6.0-beta. The installed `base.apk` SHA-256
matched the final 2,781,817-byte APK:
`00ac0cf7c6dae130e059dbd532359f1a8c83713e10ba16db1d223ac65d336005`.
No physical-camera operation or physical-phone shooting/reception test was performed.
An Android gallery failure-injection probe could not run because the package was not
debuggable; fault/crash-window recovery therefore retains source/host evidence only.
See the [0.6.0 delivery record](../dist/nikon-usb-0.6.0-beta-VALIDATION.txt).
The following results retain their original version scope.

The complete historical 0.5.8 core suite passes 9,981 checks, including 43 swipe-classifier and 38
Android-independent page-motion checks. The probe suite was not rerun; the historical
0.5.7 probe count of 12,089 is not added to a current-version total.

The frozen APK passed emulator checks for two pages visible during a half drag, release
from photo 1 to 2, short-drag spring-back, cancel and first-edge suppression, zoomed pan
and pinch without navigation, and reaching photo 3 with system animations disabled.
Explicit downloads of photos 3 and 2 changed the compact action to Save-as; cancelling
the system file picker still allowed a swipe back to photo 1.

Preserve-data installation on PKG110 / Android 16, user 0, succeeded with code 25 /
0.5.8-beta. The installed `base.apk` SHA-256 matched the final APK, which was also used
for the emulator checks. No physical camera or physical-phone gesture/frame-rate test
was performed. See the [0.5.8 delivery record](../dist/nikon-usb-0.5.8-beta-VALIDATION.txt)
for the delivery scope. The following suite and Android results belong to earlier versions.

Synthetic framed-peer tests cover large-preview requests and mode isolation, bounds,
transaction/frame failures, explicit unsupported/missing-thumbnail fallback, busy/error
handling, provenance/cache lifetime, unchanged heartbeat operation, and separation from
original downloads. In 0.5.7, production album-controller coverage is 319 checks;
PTP/IP client coverage is 4,258 checks; preview-cache coverage is 53 checks; the swipe
gesture classifier has 28 checks. The complete core/probe suites pass 9,928 + 12,089 =
22,017 checks, including bidirectional ownership, cancellation completion, and live
session/browse-readiness separation. Actual camera resolution, speed and firmware
compatibility still require device verification.

The frozen 0.5.7 APK passed an Android 17 emulator round trip on one connection:
explicit C2 import -> 49-photo album -> explicit C3 import -> album. Remote 1/2 swipes,
pagination to 49/49, double-tap zoom, zoomed pan without navigation, moving-focus pinch
and remaining-finger drag, cancel/vertical suppression, and local 48/49 swipes passed.
The before-download checkpoint has no original requests. Only the two explicit
downloads of TEST_0048 and TEST_0049 read originals; their sizes and SHA-256 hashes
matched the fixtures. Ordinary HOME/return retained the foreground connection service.
Final manual disconnect closed the transport and released service/CPU locks; this path
did not send a protocol `CloseSession`. See the
[functional checkpoints](device-review/album-0.5.7-functional.log).

Preserve-data installation to PKG110 / Android 16, user 0, succeeded with code 24 /
0.5.7-beta, and the installed `base.apk` SHA-256 matched the frozen artifact. No physical
camera or phone gesture test was performed, and emulator injection does not establish
physical frame rate or gesture feel. The user reports that 0.5.6 no longer drops the
hotspot after import; that feedback does not establish compatibility or stability for
every camera. See the [0.5.7 delivery record](../dist/nikon-usb-0.5.7-beta-VALIDATION.txt)
for the complete scope.

Historically, the final 0.5.6 APK was checked in an Android 17 emulator against synthetic
loopback JPEGs: a static grid fetched 15 large previews once, detail showed 1280 x 800,
and viewing issued no `GetObject`. One explicit original download produced one 89,320B
transfer with a matching SHA-256. A separate advertised-but-rejected large-preview
session made one 0x90C4 attempt, then used GetThumb and displayed 320 x 200, with no
original requests. See [the delivery record](../dist/nikon-usb-0.5.6-beta-VALIDATION.txt)
and [functional checkpoints](device-review/album-0.5.6-functional.log). These results do
not establish a real camera's preview resolution.
