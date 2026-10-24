# 图片处理升级：图像生成探索记录

日期：2026-10-24（Asia/Shanghai）

状态：**探索参考，非最终布局依据。** 本目录的 `design-board-v1-*.png` 为内置图像生成器的视觉探索，均标明“设计稿 · 待审核”；不是 App 已实现截图，不作为逐像素验收或测试证明。正式审核使用同目录主代理生成的 `review-editor-v1.png` / `review-export-v1.png` 及需求文档。

## 方式与来源

使用 **built-in image_gen**，未使用 CLI/API fallback。输出均为不透明背景。原始生成文件保留，选稿复制到项目目录；未修改生产源码、未构建或操作设备。

输入参考：
- `D:/dev/ai-project/nikon/docs/validation/photo-borders-89/phone/editor-template-1.png`：现有原生编辑页，参考石墨黑/杏色样式、既有五入口结构。不是沿用桌面静物照片。
- `D:/dev/ai-project/nikon/app/src/main/res/drawable-nodpi/preset_template.png`：既有 4:3 人物湖边样片，六屏共同照片来源。
- 修正调用的唯一编辑目标为本目录 `design-board-v1-editor-initial.png`。

| 项目 | 本地保留文件 | 原始生成文件 |
|---|---|---|
| 编辑板初稿 | `design-board-v1-editor-initial.png` | `C:/Users/11621/.codex/generated_images/01a16bc8-de02-7850-82e1-ebc4b7b27edd/exec-e851e5e8-dd36-413d-a0c7-a6f46d3697c5.png` |
| 编辑板一次修正稿 | `design-board-v1-editor.png` | `C:/Users/11621/.codex/generated_images/01a16bc8-de02-7850-82e1-ebc4b7b27edd/exec-d0a50324-ee95-4d62-a1f7-fd141e4856a2.png` |
| 导出板 | `design-board-v1-export.png` | `C:/Users/11621/.codex/generated_images/01a16bc8-de02-7850-82e1-ebc4b7b27edd/exec-fde4856a-ee32-4424-8a50-8aa809c471fc.png` |

导出板第一次调用返回 `connection failed: error sending request`，未得到图片。随后内置工具重试成功，并加入“保存 JPEG 原始文件”次入口。没有切换 CLI。

## 有界检查结果与限制

- 编辑板：暖灰白外板、石墨黑原生编辑、杏色强调和既有五入口顺序成立。照片来自参考样片。初稿 C 的“适应”误写为“当前”，一次定点修正后已纠正并补全导航视窗。
- **编辑板仍存在实质布局误差：A/B 主照片与参数区没有严格保持同一坐标，B 的直方图把照片向下挤动，与其下方“不挤占照片与工具”文字矛盾。此误差已向主代理报告；停止再生成，由固定布局审核板解决。不能将本图的跳位当需求。**
- 导出板：完整尺寸 6000×4500、自定义 2048×1536、Q95/Q90、“约”体积范围、原始 JPEG 次入口、批量 3/8 和停止后续基本表达齐全；所有值为设计示例，不能声称为样片真实元数据、实测体积或性能。
- 主流程“保留拍摄信息 · 不含定位”只适用于编码成品。原始 JPEG 次入口只对可信原 JPEG 出现，另需确认忽略编辑/尺寸/质量并可能保留定位；这个确认页不在探索图中，详见正式需求。
- 自定义默认为 `min(4096, 完整画布长边)`、Q90；图中 2048 为用户修改后的示例。
- 未再进行第二轮润色或全 App 视觉审计。主代理已接管精确中文、坐标、最终审核板。

## 编辑板初次生成精确提示词

```text
Use case: ui-mockup.
Asset type: A high-resolution landscape mobile product UI approval board, 3840 by 2160 target aspect ratio, for the existing native Android photo editor Huevia.
Primary request: Create one polished detailed design review board showing exactly THREE complete tall app screens, left to right A, B, C. This is a proposed UI design, not a device test screenshot.
Input images: Image 1 is the incumbent native editor screenshot, reference ONLY for the charcoal-black, warm-apricot existing app language and five bottom tools; do not reuse its tabletop photograph or border treatment. Image 2 is the exact shared real photograph: woman beside an Italian mountain lake, red scarf, foliage, red coffee cup. Use this same photograph in all screens with consistent colour and subject identity. For A and B show the complete 4:3 photograph fit within the canvas without clipping. C shows a genuinely enlarged crop of this photo around the woman's face and nearby leaves, no fake sharpening or invented sharpness claims.
Board composition: Warm off-white #F3F1EE backdrop, clean generous grid, no thick phone hardware, no dramatic shadows, no decorative shapes. Across top: clear large Chinese title “看清细节，再做调整”, small eyebrow “Huevia · 图片处理升级”, and an unmistakable pill “设计稿 · 待审核”. Three flat tall rounded app screen rectangles, each proportion close to 390:844, aligned at same top and bottom, equal widths with comfortable gutters. Screen labels outside top “A  日常编辑”, “B  直方图诊断”, “C  细节 100%”. Under each screen one short readable Chinese note as specified below. At board bottom small note “照片、数值和图表均为设计示例 · 非实现截图”. Professional, restrained, credible UI design not a marketing splash.
Common app style: flat graphite #121212 screen, lower tool region nearly black #090909, soft grey secondary text #BEB6AD, white primary text, warm apricot #F3B493 for primary action, selected control and active underline. Use a clean Android/system sans-serif typeface with exceptionally accurate Chinese typesetting. All titles/buttons have generous 44–48 dp touch area, steady spacing, realistic 24 dp icons and visible safe-area bottom gesture space. Do not use glass effects or gradients.
Screen A:
Top status bar subtle 9:41. Header back arrow left, centred “DSC_9187.JPG” with tiny “1 / 1”, right warm apricot pill “导出”.
Below header a slim canvas tools row: left “无滤镜”, right small histogram icon with text “直方图” in inactive grey, and quiet overflow icon.
Photo canvas generous and black with the full shared lake photo fit in centre; no frame, metadata border, or artificial edge. Just below photo at left a small outline button “细节 100%”; right simple before/after comparison icon.
Bottom adjustment panel: current parameter “曝光” left and value “+0.3” right; a horizontal fine tick slider with apricot central thumb. Beneath a compact horizontal parameter picker with icon+label “曝光”, “对比度”, “高光”, “阴影” and a glimpse of next parameter, exposure selected. Do not claim only four total parameters.
Bottom five existing tools in this EXACT order, all visible: “滤镜  调节  构图  边框  配方”. “调节” is apricot selected with short underline. No sixth tab.
Screen B:
Exactly same header, image placement, photo size, lower tool region and controls as A. Histogram button active apricot.
In the black unused canvas space ABOVE the photo, overlay a compact dark-grey histogram component, without moving or cropping the photo and without changing lower panel height. Top of compact component segmented “亮度” selected / “RGB”; adjacent tiny label “预览统计”. Below a plausible softly filled luminance histogram with dark-to-light horizontal scale, never present the graph as a scientific result. Two ends have small warning controls: blue triangle with “暗部”, red triangle with “高光”, both shown inactive or subtle; these toggles are diagnostic overlays, not photographic content. Keep graph compact and photo visible.
The full photograph itself is identical to A, no clipping-colour overlay enabled in this default example.
Same exposure +0.3 and bottom five tools order “滤镜 调节 构图 边框 配方”, 调节 selected.
Screen C:
A SEPARATE full-screen detail viewer; do NOT show the editor's five tool tabs.
Top status bar and header back arrow left, “细节” centred, text “完成” apricot right.
Beneath header two-segment control “当前效果” selected apricot tint, “调色前” inactive grey, readable generously sized.
Large photo viewport shows the same photo enlarged around woman's face, hair and leaves, clearly a meaningful local crop, natural texture. No border. At lower-right within viewport a small thumbnail of whole reference photo with thin apricot rectangle marking the current zoom viewport. This overview is a navigator, not another photo card.
Bottom controls: pills “适应”, “100%”, “200%”, with “100%” apricot filled and dark text; others charcoal with light text. Below readable small text “像素 1:1 · 同构图对比 · 不含边框”. Preserve bottom system gesture safe area.
Under-screen explanatory notes exactly:
A “细节入口独立，常用调节继续顺手”
B “直方图按需展开，不挤占照片与工具”
C “按原始像素查看，双击切换倍率”
Constraints: Chinese legibility is essential. Follow exact labels, five-tool order and three screen count. No invented sixth navigation tab. No new cloud feature, RAW development, curves or HSL. No claims of already implemented, measured performance or passed tests. No Apple logo or iPhone notch. No phone device casing. All screens complete and include safe area, no clipping at board edges.
```

## 编辑板一次定点修正精确提示词

```text
Use case: ui-mockup precise correction.
Edit the supplied design review board, preserving its existing tasteful warm off-white background, three-screen grid, typography, apricot/charcoal palette, complete UI labels, exact source photograph, same title “看清细节，再做调整” and “设计稿 · 待审核” marker. Change ONLY the following specific defects; do not redesign other elements:
1. Screens A and B are the SAME editor with a diagnostic overlay toggled. Their main whole-photo rectangles MUST be at IDENTICAL vertical positions and identical size and 4:3 aspect ratio. Their “细节 100%” / comparison row, exposure slider panel, parameter row, and five bottom tabs MUST all have identical y coordinates. In A reserve a black blank canvas band above the main photo; in B put the compact histogram in this reserved blank band, NEVER move/resize/crop the photo or lower tool panels. You may slightly tighten the top band uniformly in A and B to fit, but all editor geometry must be the same. The full woman-by-lake photograph remains visible and uncropped in both. Keep tool labels “滤镜 调节 构图 边框 配方” in that exact order, 调节 active. Keep B histogram “亮度 / RGB”, “预览统计”, “暗部”, “高光” above the photo.
2. Screen C bottom zoom control first pill is incorrectly “当前”. Replace it with exact Chinese “适应”. Keep “100%” apricot selected and “200%” unchanged. The three pills must read exactly “适应 100% 200%”.
3. Screen C overview navigator thumbnail needs a clear thin apricot rectangular viewport marker visible inside it, accurately signifying the currently enlarged face area.
4. A/B exposure parameter first label must read exact Chinese “曝光”, not a malformed character.
This remains a concept image, not an implementation screenshot. Do not add performance claims. Do not add bottom tabs to C, do not add sixth tab anywhere, no phone casing. Preserve board footer “照片、数值和图表均为设计示例 · 非实现截图”. Exceptionally accurate Chinese text. This is the ONE bounded correction pass.
```

## 导出板首次调用精确提示词（失败，无输出）

```text
Use case: ui-mockup.
Asset type: A high-resolution landscape mobile product UI approval board, 3840 by 2160 target aspect ratio, for the existing native Android photo editor Huevia.
Primary request: Create a polished, precise product design review board showing exactly THREE complete tall app screens, side by side. These are proposed native Android interfaces, not real device test screenshots. The export workflow is next-step continuation of the app's black photo editor; do not redesign global app navigation.
Input images: Image 1 is the incumbent native editor screenshot, style and structural reference only: warm apricot primary buttons on graphite-black, restrained native sans-serif. Do not reuse its tabletop photo or photo border. Image 2 is the exact common sample photograph: woman with red scarf and red coffee cup beside a mountain lake; use it in all six image thumbnails on this board. It is a 4:3 photograph, preserve that ratio.
Board composition: warm off-white #F3F1EE background. Clear large title across top “按用途导出”, small eyebrow “Huevia · 图片处理升级”, unmissable warm apricot pill “设计稿 · 待审核”. Exactly three full portrait screens of equal width and height, about 390:844 ratio, aligned top/bottom with comfortable gutters. Flat rounded screen edges, NO thick phone device frame, no iPhone notch, no drop-shadow theatrics. Outside screen labels “A  完整尺寸”, “B  自定义导出”, “C  批量进度”. Below each screen one short readable sentence. Board footer “照片、尺寸、体积与进度均为设计示例 · 实际结果随原图、构图及边框计算”. This is a practical approval board, not a splash page.
All app screens: graphite black #121212, nearly-black lower safe area, white titles and grey #BEB6AD body copy, warm apricot #F3B493 active control/CTA, clean Android/system sans-serif with accurate legible Chinese. 44–48 dp minimum touch areas, realistic readable typography, generous spacing. No screen is cut off at bottom; preserve system gesture safe area. No glass blur, gradients, cloud, RAW development, curves, HSL or new bottom tabs.
Screen A, full-screen export setup:
statusbar 9:41, header title “导出照片”, right “取消”.
Compact 4:3 thumbnail of the shared woman/lake photo plus filename “DSC_9187.JPG” and muted “1 张照片”.
Three-choice segmented control “完整尺寸” (selected apricot) / “分享” / “自定义”.
An uncluttered settings body with separators, not stacks of decorative cards:
Label “输出尺寸”, large value “6000 × 4500 px”, small helper “最终画布尺寸”.
Label “格式”, value “JPEG”.
Label “质量”, value “95”.
Label “预计体积”, value “约 24–30 MB”. Use 约 because this is an estimate and sample data.
Simple tappable destination row “保存到” left, “手机相册” plus chevron right.
Muted static informational row with small information icon “保留拍摄信息 · 不含定位”. This is NOT an interactive GPS/privacy toggle.
Near bottom full-width apricot primary button “导出照片”, small safe-area gap and gesture bar. No editor tool tabs in this dedicated export page.
Screen B, same full-screen export setup:
same header “导出照片” / “取消”, same photo and file row.
Same segmented “完整尺寸” / “分享” / “自定义”, but 自定义 selected apricot.
Label “长边”, clear numeric input “2048” with unit “px”. A readable ratio-locked output row “2048 × 1536 px”, helper “保持比例 · 不放大”.
Label “格式” value “JPEG”.
Label “质量”, current value “90” shown in small tappable numeric pill. Below a precise horizontal slider with apricot thumb at 90, grey inactive remainder, with unobtrusive endpoints “60” and “100”.
Label “预计体积”, value “约 1–3 MB”.
Destination “保存到” / “手机相册” chevron.
Same static “保留拍摄信息 · 不含定位”.
Bottom full-width apricot “导出照片” and safe-area gesturebar. Ensure all controls fit without cropping. Do NOT show unsupported enlargement, RAW or PNG output.
Screen C, separate batch progress:
statusbar, header “正在导出”.
Main heading “正在导出 8 张照片”.
Large progress text “3 / 8”, helper “第 3 张处理中”. Apricot progressbar, about 30% fill.
A compact batch queue with exactly five visible rows: thumbnail of the same photo, filename, status to right:
“DSC_9187.JPG” — green-grey check and “已完成”
“DSC_9188.JPG” — green-grey check and “已完成”
“DSC_9189.JPG” — small apricot spinner and “正在导出”
“DSC_9190.JPG” — grey “等待中”
“DSC_9191.JPG” — grey “等待中”
Then a quiet line “另有 3 张等待中”. No file-size claims or fake speed/remaining-time estimates.
Bottom secondary outlined button “停止后续”, muted helper “已完成照片保留”, safe-area gesturebar. No contradictory completion summary and no visible failed result while currently exporting. Do NOT imply processing survives app kill or automatic recovery.
Outside below-screen notes exactly:
A “保留当前画布尺寸，导出设置一次看清”
B “按长边缩小，尺寸与体积同步预估”
C “逐张显示进度，失败后可单独重试”
Constraints: Chinese exact and crisp. All pictured dimensions are design examples and estimates, not current asset metadata or observed test results. Use the exact thumbnail in all screen rows, no decorative invented camera photos. Keep typography and spacing consistent and believable. This is a design review board visibly marked “设计稿 · 待审核”, never label it shipped or tested. Exactly 3 screens, complete edge-to-edge UI without device hardware or overlapping content.
```

## 导出板成功重试精确提示词

```text
Use case: ui-mockup.
Asset type: A high-resolution landscape mobile product UI approval board, 3840 by 2160 target aspect ratio, for the existing native Android photo editor Huevia.
Primary request: Create a polished, precise product design review board showing exactly THREE complete tall app screens, side by side. These are proposed native Android interfaces, not real device test screenshots. The export workflow is next-step continuation of the app's black photo editor; do not redesign global app navigation.
Input images: Image 1 is the incumbent native editor screenshot, style and structural reference only: warm apricot primary buttons on graphite-black, restrained native sans-serif. Do not reuse its tabletop photo or photo border. Image 2 is the exact common sample photograph: woman with red scarf and red coffee cup beside a mountain lake; use it in all six image thumbnails on this board. It is a 4:3 photograph, preserve that ratio.
Board composition: warm off-white #F3F1EE background. Clear large title across top “按用途导出”, small eyebrow “Huevia · 图片处理升级”, unmissable warm apricot pill “设计稿 · 待审核”. Exactly three full portrait screens of equal width and height, about 390:844 ratio, aligned top/bottom with comfortable gutters. Flat rounded screen edges, NO thick phone device frame, no iPhone notch, no drop-shadow theatrics. Outside screen labels “A  完整尺寸”, “B  自定义导出”, “C  批量进度”. Below each screen one short readable sentence. Board footer “照片、尺寸、体积与进度均为设计示例 · 实际结果随原图、构图及边框计算”. This is a practical approval board, not a splash page.
All app screens: graphite black #121212, nearly-black lower safe area, white titles and grey #BEB6AD body copy, warm apricot #F3B493 active control/CTA, clean Android/system sans-serif with accurate legible Chinese. 44–48 dp minimum touch areas, realistic readable typography, generous spacing. No screen is cut off at bottom; preserve system gesture safe area. No glass blur, gradients, cloud, RAW development, curves, HSL or new bottom tabs.
Screen A, full-screen export setup:
statusbar 9:41, header title “导出照片”, right “取消”.
Compact 4:3 thumbnail of the shared woman/lake photo plus filename “DSC_9187.JPG” and muted “1 张照片”.
Three-choice segmented control “完整尺寸” (selected apricot) / “分享” / “自定义”.
An uncluttered settings body with separators, not stacks of decorative cards:
Label “输出尺寸”, large value “6000 × 4500 px”, small helper “最终画布尺寸”.
Label “格式”, value “JPEG”.
Label “质量”, value “95”.
Label “预计体积”, value “约 24–30 MB”. Use 约 because this is an estimate and sample data.
Simple tappable destination row “保存到” left, “手机相册” plus chevron right.
Muted static informational row with small information icon “保留拍摄信息 · 不含定位”. This is NOT an interactive GPS/privacy toggle.
Near bottom full-width apricot primary button “导出照片”, then below it a low-emphasis grey text action “保存 JPEG 原始文件”, then safe-area gap and gesture bar. This secondary action is only shown for a trusted original JPEG; its later confirmation warns it ignores all edits, size and quality settings and preserves original metadata which may include location. Do not show a privacy promise for this secondary original-file action. No editor tool tabs in this dedicated export page.
Screen B, same full-screen export setup:
same header “导出照片” / “取消”, same photo and file row.
Same segmented “完整尺寸” / “分享” / “自定义”, but 自定义 selected apricot.
Label “长边”, clear numeric input “2048” with unit “px”. A readable ratio-locked output row “2048 × 1536 px”, helper “保持比例 · 不放大”.
Label “格式” value “JPEG”.
Label “质量”, current value “90” shown in small tappable numeric pill. Below a precise horizontal slider with apricot thumb at 90, grey inactive remainder, with unobtrusive endpoints “60” and “100”.
Label “预计体积”, value “约 1–3 MB”.
Destination “保存到” / “手机相册” chevron.
Same static “保留拍摄信息 · 不含定位” only refers to the newly encoded output from the main export button.
Bottom full-width apricot “导出照片”, with low-emphasis grey text action below “保存 JPEG 原始文件”, and safe-area gesturebar. The original-file action opens a separate confirmation for ignoring all edits/size/quality and preserving original metadata which may include location; do not imply this action strips location. Ensure all controls fit without cropping. Do NOT show unsupported enlargement, RAW or PNG output. In this screen 2048 is a user-edited demonstration value; custom default is min(4096, final canvas long edge), quality 90. Do not need to show this formula in the UI.
Screen C, separate batch progress:
statusbar, header “正在导出”.
Main heading “正在导出 8 张照片”.
Large progress text “3 / 8”, helper “第 3 张处理中”. Apricot progressbar, about 30% fill.
A compact batch queue with exactly five visible rows: thumbnail of the same photo, filename, status to right:
“DSC_9187.JPG” — green-grey check and “已完成”
“DSC_9188.JPG” — green-grey check and “已完成”
“DSC_9189.JPG” — small apricot spinner and “正在导出”
“DSC_9190.JPG” — grey “等待中”
“DSC_9191.JPG” — grey “等待中”
Then a quiet line “另有 3 张等待中”. No file-size claims or fake speed/remaining-time estimates.
Bottom secondary outlined button “停止后续”, muted helper “已完成照片保留”, safe-area gesturebar. No contradictory completion summary and no visible failed result while currently exporting. Do NOT imply processing survives app kill or automatic recovery.
Outside below-screen notes exactly:
A “保留当前画布尺寸，导出设置一次看清”
B “按长边缩小，尺寸与体积同步预估”
C “逐张显示进度，失败后可单独重试”
Constraints: Chinese exact and crisp. All pictured dimensions are design examples and estimates, not current asset metadata or observed test results. Use the exact thumbnail in all screen rows, no decorative invented camera photos. Keep typography and spacing consistent and believable. This is a design review board visibly marked “设计稿 · 待审核”, never label it shipped or tested. Exactly 3 screens, complete edge-to-edge UI without device hardware or overlapping content.
Retry note: The previous built-in generation call failed with a connection error and returned no asset. This is a fresh built-in retry with the above clarified secondary original-file action. Do not print this note on the image.
```

