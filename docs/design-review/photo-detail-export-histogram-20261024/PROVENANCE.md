# 设计图来源与边界

正式审核图为 `review-editor-v1.png` 与 `review-export-v1.png`，由本目录 `review.html` 的静态 UI 布局渲染。渲染脚本 `render-review.cjs` 通过独立无界面 Edge 实例打开本地文件，不操作用户浏览器或 Android App。两图均为 2 倍像素密度，文字、布局与几何来自可复查的源文件，不是生成器重新拼写的标签。

样片 `assets/sample.png` 是仓库 `app/src/main/res/drawable-nodpi/preset_template.png` 的逐字节副本，未修改源照片。现有 UI 依据为 `docs/validation/photo-borders-89/phone/editor-template-1.png` 及 DESIGN.md 的黑色编辑器与杏色控件。屏内文件名、尺寸、质量、队列及直方图形状均为流程示例，未对示例照片实施所显示的调节，也不代表实际导出、速度或色彩效果。细节放大是设计示意，真实像素实现须按 REQUIREMENTS.md 验收。

`design-geometry.json` 只证明两张静态设计屏中照片和工具区坐标一致：照片 x=0、y=215.25、390×292.5 CSS px；工具区 x=0、y=595、390×225 CSS px。它不证明 Android 实现已经完成或通过测试。

此前内置 imagegen 生成的 `design-board-v1-editor*.png` 与 `design-board-v1-export.png` 保留为探索参考；前者未准确锁定 A/B 照片位置，不能作为实现依据。完整生成提示词、原始路径及已知限制见 [PROMPTS.md](PROMPTS.md)。正式审核不依赖这些探索图的文字或坐标。

复现渲染时设置 `HUEVIA_DESIGN_PLAYWRIGHT_PATH` 为 Playwright 包目录，可用 `HUEVIA_DESIGN_BROWSER_PATH` 指定独立 Chromium/Edge 可执行文件，运行 `node render-review.cjs`。此脚本只写当前审核目录内的两张设计 PNG 与几何 JSON，不构建 Android 或修改生产文件。
