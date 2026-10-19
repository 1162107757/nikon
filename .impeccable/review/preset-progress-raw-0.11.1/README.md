# Native UI and actual NEF publication evidence

Frozen product: **58 / 0.11.1-beta**.

SHA-256: `f1952cca9f98fdfaef4e01875eada653291e5f0ca535cd5cfd05630280a3a568`.

Only the explicitly assigned **emulator-5560** was used. The product APK was not
replaced. No physical phone, camera, cloud deployment, real photograph or real
account was used by this helper.

| Run | Final result | Cleanup / boundary |
| --- | --- | --- |
| `light-portrait/report.json` | PASS, 249 checks | Current fixtures and exact owned RAW public/private records cleaned; previous known-SHA publication journal recovered. |
| `dark-portrait/report.json` | PASS, 208 checks | Current fixtures restored and cleaned. Actual MediaStore transfer was already covered in light portrait. |
| `light-landscape/report.json` | Limited; stopped at first drag assertion | Native capture obtained. The helper's center drag started inside the floating Tabbar in the short landscape viewport. Cleanup passed; no product defect or horizontal scroll pass is claimed. |

The portrait checks use 120 numbered synthetic JPEGs and an isolated project.
Actual touch drags move the header/hero away, virtual rows stay below 15 live
children, and the 44dp top action returns to the header. Transfer updates preserve
list/header/adapter/visible-row identities and the visible anchor. A new arrival
preserves the current anchor. Selection, stars and immersive opening/back work.
Injected automatic 0/49/99% progress retains header/tile geometry; manual total
68% and current stage 80% match the native bar and copy. These snapshots verify
rendering rather than actual camera transfer timing.

The light run additionally saves a uniquely identified **33,554,432-byte synthetic
TIFF/EP NEF** through production RawPhotoStore, RawPhotoPanel and actual Android
MediaStore. URI MIME is `image/x-nikon-nef`; full public-stream SHA and length
equal the source. A repeat publication returns the same URI. The runtime task
and service lease is present in the same UI turn, an in-flight background state
was observed, and both task/file leases are released after completion. The final
synthetic NEF SHA is
`863dce785438eed8330b791e2237250a0cb4faa7ec9f33007b6195d155e8c9f7`.
Only that owned public URI and its exact private source/content/publication paths
were removed. Common directories, locks and all unrelated rows were preserved.
This is not evidence of physical Nikon model compatibility or extended camera
background connectivity.

The final native PNGs were inspected for light/dark receiving, scrolled rows,
automatic/manual percentages, JPG/NEF semantics and actual RAW completion.
Historical images under `history/` describe helper setup assumptions, not a final
product failure. The early raw cleanup interruption was followed by exact-SHA
journal recovery and a final light PASS with both cleanup flags true. A failure
before cleanup was never used as the final green result.
