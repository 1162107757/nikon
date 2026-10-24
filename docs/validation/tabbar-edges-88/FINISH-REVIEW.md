# Tab bar edge review — version 88

Disposition: **SHIP within the requested tab geometry scope**; no remaining material findings.
Candidate: `0.17.4-beta (88)`, SHA-256 `c2c69e5eb9b288cc285c9fd22a1ea4da22abccdd182fcfcfdbf3d92cd4294bf7`.
Reviewed the supplied reference, `MainActivity`/`GlassBar`, final `report.json`, and all six native screenshots plus six XML states (phone, font scale 1.3, dark; first/last tab selected).
The selected icon and name share one capsule; first/last end curves follow the outer pill, with no visible clipping or drift.
Independently checked XML targets against the report: top, bottom and outer-side insets are exactly `13/13/13 px` in all six states (approximately 5 dp; zero difference).
Bar heights are 179 px / 68 dp and 184 px / 70 dp; targets are 229–230 px wide and 153–158 px high, exceeding 48 dp at this density. Labels remain fully inside their selected targets.
Code uses one dynamic half-height radius for the outer outline/rim and the inner selection, equal cell widths, full cell height, and fixed 5 dp padding; existing rail routing is unchanged.
This is an emulator geometry review only. No connected physical phone, full gallery regression, camera protocol, or broader app certification is claimed.
