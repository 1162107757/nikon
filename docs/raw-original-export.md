# RAW original export

This path transfers the camera's original `.NEF` bytes. It does not encode JPEG
as RAW, apply phone presets to a NEF, rewrite EXIF, or change automatic JPEG live
reception. JPEG effects remain full-size JPEG outputs from the established renderer.

## Controller API

- `loadRawPage(offset, limit, Callback<PhotoPage>)`: explicit RAW inventory. The
  cursor counts all camera objects; an empty page can have `hasMore()==true`.
- `downloadRaw(PhotoItem, RawDownloadCallback)`: transfer the explicitly chosen NEF.
- `downloadRawForJpeg(PhotoItem, RawDownloadCallback)`: automatic pairing only after
  a complete inventory of all available cards proves one unique name, equal valid
  capture time, the same trusted camera GUID, the same parent on a shared card,
  and equal sequence numbers where both are present. Duplicate names, missing
  metadata, an unreadable card, or an unidentified device require explicit choice.
- `downloadRawForLocal(DownloadedPhoto original, RawDownloadCallback)`: verify the
  local original's JPEG SHA against independently persisted source evidence. Reuse
  an already saved NEF offline, otherwise require the original camera and descriptor.
  The caller must resolve an effect to its original first. JPEGs from before this
  evidence existed cannot be matched by their displayed filename.
- `listRawDownloads`, `findRawDownload(sourceIdentity)`, and `publishRaw`: completed
  local RAW files and publication retries do not need a camera connection.

Callbacks run on the Android main handler. Byte progress is actual received/total
bytes. `onStage` distinguishes `discovery`, `receiving`, and `verifying`;
`onDiscoveryProgress` counts metadata checked, not received bytes. `isRawBusy()`
reserves the manual RAW operation through its camera and local stages. Publication
has its own local-task lease; the caller keeps its saving state until its callback.

The native export panel also owns one runtime foreground-photo lease for an accepted
single export or complete batch, covering download, verification and system-gallery
publication. Closing the page does not release an in-flight chain; final success,
failure or draining the current file after stopping the batch releases the lease.
Retries obtain a new lease. This uses the same photo service as existing JPEG work.

The saved-file UI uses `listForDisplay()` to read bounded completion metadata,
canonical paths and lengths without hashing every potentially large NEF. This is
only a presentation snapshot: reuse, export and publication retain full structural
and SHA verification. The strict `list()` API remains available for integrity checks.

## Preservation and storage

NEF discovery does not assume an undocumented vendor format number. It recognizes
a `.nef` file descriptor and verifies the received Nikon classic TIFF/EP structure,
including endian marker, IFD/payload bounds, Nikon maker identity, a CFA sensor
plane, and complete strip/tile extents. Unknown layouts fail without publishing a
partial or renamed JPEG. This is structural validation, not a RAW demosaicing engine.

`GetObject` is streamed with a 512 MiB limit, exact declared lengths, a confirmed PTP
terminal response, and descriptor checks before and after reception. Private files
are committed from a same-directory `.part`, fsynced, hashed, and indexed by source
and content. Repeated downloads and JPEG source aliases reuse the same immutable
NEF. All full-file hashing, index persistence, and publication run on the local RAW
worker, leaving the camera worker available for read-only keepalive.

Private completed files live in app external Pictures `camera-raw`, with app files
`camera-raw` fallback. `RawJpegReferenceStore` is an optional separate, atomic
sidecar containing the JPEG SHA, byte length, trusted camera GUID, and complete
original descriptor. It never migrates or alters the established JPEG store/catalog.
Byte-identical JPEGs recorded from distinct capture sources are marked ambiguous.

`RawPhotoPublisher` copies the original bytes to `Pictures/Huevia/RAW/` using
`image/x-nikon-nef`. Android 29+ uses a pending MediaStore image and a durable
publication journal; Android 26–28 uses the existing storage permission and media
scanner. No document or destination picker is opened. A failed public save retains
the private original for retry. A modified or foreign public row is never overwritten.
Visibility and NEF decoding in a system gallery depend on that gallery's support.

Manual remote RAW operations reject an active/pending photo live receiver. Starting
live reception or moving the session to preset import while RAW owns the operation
also rejects with an explanatory message. The camera is not disconnected.

## Verification

Synthetic host checks (not evidence of physical-camera compatibility):

- `tests/run-raw-controller.ps1`: 54 structure/unique-pairing cases, 8 persistent
  JPEG provenance cases, 31 controller/raw transfer/cancellation cases, and 9
  successful same-card/cross-card pairing cases over actual PTP/IP frame parsing.
- `tests/run-raw-store.ps1`: 157 original-store/publication/legacy/crash recovery cases,
  including metadata-only display and strict integrity validation before reuse.
- Existing regression: 826 controller, 356 project persistence, 100 project folder,
  221 system album saver, 36 deletion, 18 original byte identity, 111 live receipt,
  36 library grouping/version, 277 batch preset, 82 source-link, and 48 automatic
  project preset checks passed with the new source dependencies.

Physical Nikon NEF transfer and the phone's actual MediaStore/gallery remain a
separate integration check. Synthetic TIFF fixtures must not be presented as that check.
