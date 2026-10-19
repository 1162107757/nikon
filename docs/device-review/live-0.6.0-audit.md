# 0.6.0 photo-live protocol audit

This is an independent read-only count of the frozen synthetic-camera logs. No suite,
Android action, camera operation or log mutation was performed for this audit.

| Evidence | Lines | SHA-256 |
| --- | ---: | --- |
| [Baseline](live-0.6.0-baseline.log) | 97 | `9d6608a2cf30bdaad635ec07f39eb613c716009b55acaecbc63f8b83bf2c5016` |
| [Final protocol](live-0.6.0-protocol.log) | 834 | `5008e61f95382e1c0b81160c987dca0c58760d6e3ebd4b7e6479acfd315fc9ac` |

The baseline snapshot contains six camera handles and zero GetObject requests or
completed originals. Revision 1 introduced 4–6 before live startup; they are baseline
objects, not successful new-photo receptions. Revision 2 subsequently added 7–9,
revision 3 added 10 and repeated events for existing 7, revision 4 added 11, and revision
5 added 12 after Stop.

The final log has one INIT_COMMAND, one INIT_EVENT and one OPEN_SESSION. All 654 CMD
records belong to session 1: DeviceInfo/Open use transaction zero, then positive
transactions 1–652 each appear exactly once. There is no command 0x1003 (CloseSession)
or 0x90CD (SetPicCtrlData); the final summary confirms virtualWrites=0. Lines 833–834
record transport_closed and CLOSED after the explicit disconnect. This path closes the
transport without a protocol CloseSession.

| Handle | ObjectAdded events | GetObject attempts | Completed originals | Original bytes | Large preview before successful original |
| ---: | ---: | ---: | ---: | ---: | --- |
| 7 | 4 | 1 | 1 | 92,366 | Yes |
| 8 | 1 | 2 | 1 | 94,893 | Yes |
| 9 | 0 | 1 | 1 | 95,452 | Yes |
| 10 | 1 | 1 | 1 | 93,561 | Yes |
| 11 | 1 | 1 | 1 | 92,082 | Yes |
| 12 | 2 | 0 | 0 | — | No preview requested |

There are six GetObject commands and five strict ORIGINAL event records. The extra
attempt is handle 8's transaction 82, rejected as temporarily busy; it completes once
at transaction 89. All five successful originals have one preceding LARGE_PREVIEW
event. Handle 7's four notifications still produce one original, while 9 has no event
and is received after index reconciliation. There is no original request for 1–6 or 12.

At transactions 621–631, the same session reads preset capability, Standard and each
custom slot 201–209. It returns to album DeviceInfo/storage/handles at 636–638 and reads
the twelve-photo index without receiving 12 or reopening the connection. This supports
same-session read-only handoff and no automatic live restart after Stop.

These logs support protocol ordering/counts and synthetic transfer sizes. They do not
by themselves establish Android MediaStore state, local hashes, UI behavior, task
lifecycle or physical camera compatibility. Those separately observed Android and
installation results are recorded in [functional checkpoints](live-0.6.0-functional.log)
and the [delivery record](../../dist/nikon-usb-0.6.0-beta-VALIDATION.txt). The Android
gallery failure-injection probe did not execute; source/host recovery coverage must not
be described as a passed Android fault-injection run.
