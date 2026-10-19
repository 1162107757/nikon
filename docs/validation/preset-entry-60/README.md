# Huevia 60 / 0.11.3-beta

APK SHA-256: `0ab5543e52831c09a5d583f553167d53d57dfcd11768e6f41bec162d1b535241`.

Physical phone read-only diagnosis found repeated `NoSuchMethodError: No static method readString(Path, Charset)` at `SubmissionDraftStore.load`, reached from `CommunityPanel.openEditor`. Fresh drafts did not exercise that call. The replacement uses bounded streams and a strict UTF-8 decoder. No phone app installation or camera operations were performed this turn.

Dedicated emulator 5560, API37: final APK, light25 and dark25 checks passed. Synthetic in-memory account/API fixtures; no public comments, uploads or real user messages. Helper APK SHA-256 `e0ac5e9d3a9d21b9b3743109267197b474e944f308f73a46bf4e41a59773dee6`. Tests include guest publish -> login, local plus -> document intent, authenticated publish, persisted draft re-open, Settings dot/numeric/99+, failed read retaining count, successful page read clearing count, account isolation, and no screen recreation during unread-only refresh. Owned draft/account fixtures cleaned. Images in light/ and dark/ are native screenshots, not production data. Inspected online floating button, persisted editor and settings badges for both palette layouts.

Java repository/drafts33, paging17, replies94, recipients9; Node108; JS source46 PASS. Disposable PostgreSQL migration010 repeated safely; normal first comment visible, suspicious established comment pending, missing decision pending, legacy idempotency preserved, selected recipients unchanged, page-only owner-bound read acknowledges no foreign/unseen messages, service-only grants PASS. Historical migration009 recipient suite also passes separately.

Not a claim of full content-safety detection, all OEM compatibility, TalkBack, landscape or real-phone UI revalidation. Public production acceptance is read-only; no test messages sent to existing users.
