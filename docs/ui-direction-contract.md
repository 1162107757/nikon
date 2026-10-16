# Direction contract

THESIS: Use the most recently presented red-and-white catalog and album diagrams: photographs lead preset choice, and download and camera import remain distinct actions. Keep two destinations, 预设 and 相册.

OWN-WORLD: White and softly tinted grey surfaces, deep red selected controls, charcoal system Chinese typography, consistent outlined vector icons, 16dp content gutters, and stable picture-caption alignment. Support a separate dark scheme without changing the approved information hierarchy.

STORY: Browse online or local presets, inspect a real associated sample, download or select an NCP, then confirm a camera empty slot. In the album, connect wirelessly, choose pictures, download, and view local files.

FIRST VIEWPORT: Compact title, source tabs, concise USB status and phone-file action, then an image-first scrolling preset list. Two destination controls sit above system insets. When no real assets or catalog exist, show truthful empty content with configuration/add actions rather than the mock photographs.

FORM: Daily catalog, grounded candidate 4; seed:50feb9eb53005b70. Native Java Views implementation using existing build tooling. Approved references: docs/design-review/home-with-album.png, docs/design-review/import-flow.png, docs/design-review/album-overview-v2.png, docs/design-review/wireless-guide.png. Approval evidence: user said “开始按照设计图实现” after these references were presented. A/B/C was not separately voted on; follow the most recently displayed red-and-white set as stated in the implementation commentary.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

## Content and implementation limits

The user confirmed no online service URL yet: ship a real configurable catalog client and a clear unconfigured state, with no invented online entries. Mock photographs are not bundled as preset evidence. Only user-associated or catalog-associated real photos are shown. Existing USB import checks stay in the backend. Wireless read-only code is subject to camera testing; connection failure must not be disguised as a successful album.
