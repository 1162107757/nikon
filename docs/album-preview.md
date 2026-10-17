# Camera album previews

The album requests camera-generated JPEG previews. Viewing a camera photo does not issue
`GetObject (0x1009)`, read pieces of the original, create a downloaded-photo entry, or write
camera settings. The original is requested only by the explicit download action.

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
- The viewer reports the source as `高清预览` or `缩略预览` and displays the actual decoded
  pixel dimensions. No fixed resolution or 2-megapixel guarantee is inferred from an
  advertised operation. The preview cache stores source provenance, has at most two
  completed entries/16 MiB, and keeps displayed entries pinned until released.

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

Synthetic framed-peer tests cover large-preview requests and mode isolation, bounds,
transaction/frame failures, explicit unsupported/missing-thumbnail fallback, busy/error
handling, provenance/cache lifetime, unchanged heartbeat operation, and separation from
original downloads. Production album-controller coverage is 226 checks; PTP/IP client
coverage is 3,956 checks; preview-cache coverage is 53 checks. Actual camera resolution,
speed and firmware compatibility still require device verification.

The final 0.5.6 APK was also checked in an Android 17 emulator against synthetic
loopback JPEGs: a static grid fetched 15 large previews once, detail showed 1280 x 800,
and viewing issued no `GetObject`. One explicit original download produced one 89,320B
transfer with a matching SHA-256. A separate advertised-but-rejected large-preview
session made one 0x90C4 attempt, then used GetThumb and displayed 320 x 200, with no
original requests. See [the delivery record](../dist/nikon-usb-0.5.6-beta-VALIDATION.txt)
and [functional checkpoints](device-review/album-0.5.6-functional.log). These results do
not establish a real camera's preview resolution.
