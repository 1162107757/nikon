# 91 focused host validation

Executed by the root/owning agents during this implementation. These counts are assertion totals, not device or full-app certification.

| Check | Result |
| --- | --- |
| PhotoDetailViewport | PASS, 57 coordinate checks |
| PhotoHistogram | PASS, 1,083 assertions |
| PhotoExportSpec | PASS, 14,229 sizing/identity assertions |
| Existing photo editor strength/draft | PASS, 34,780 checks |
| Basic adjustment behavior/migration | PASS, 644,888 checks |
| Composition/recipe geometry/storage | PASS, 1,158 checks |
| Border migration/identity/frozen export | PASS, 29 checks |
| Export batch incl account invalidation and orphan JPEG | PASS, 688 checks (backend agent, including Android directory-alias ownership) |
| Preset source validation | PASS, 82 checks (backend agent) |
| Photo library / project automatic processing | PASS, 36 / 53 checks (backend agent) |
| Export availability state matrix | PASS, 71 checks (receipt agent) |

The unified Android 35 / Java 17 APK builds and v2/v3 signature verification passed. Existing Tencent support annotation/deprecated API warnings remain. Native framebuffer/codec/UI coverage and exact APK identity are recorded separately by their probes; a host stub does not prove actual MediaStore behavior.
