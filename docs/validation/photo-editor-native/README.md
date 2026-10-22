# 0.13.0-beta 照片滤镜编辑验收

产品：68 / 0.13.0-beta。当前 APK SHA-256：`a042c85f59d3fd8166c518eb3486250b59aad5eeefded497cd8bbe6b405ba5ee`。构建完成并通过 APK v2/v3 签名验证；其他聊天的富士 RAW 能力保护已在此次构建内。

## 核心回归

| 命令 | 结果与范围 |
| --- | --- |
| `tests/run-photo-editor.ps1` | 2,144：强度端点／全部比例、alpha、模型隔离、持久草稿、账号隔离、冻结配方、旧任务标记保护、损坏与路径拒绝 |
| `tests/run-photo-presets.ps1` | 300 批处理＋82 来源：独立 NCP 70%／NP3 40%／原图，批量与逐张像素一致、重复复用、不同强度、中断提交恢复、部分失败准确重试、取消保留、来源身份拒绝，以及原有满强度路径 |
| `tests/run-photo-library.ps1` | 36：原照片归组与版本回归 |
| `tests/run-project-auto.ps1` | 48：原有直播项目自动套用回归 |

富士 RAW 专项由并行任务完成，见 [其实际测试范围](../../fujifilm-raw-export-validation-20261019.md)。本功能没有修改富士协议处理逻辑。

## Android 原生证据

专用只读 Pixel 9 AVD，Android 17 / ranchu，硬编码 `emulator-5582`，开启飞行模式，无相机会话，不操作实际手机。照片来自现有 App 模板，加入独立捕获信息生成三个不同内容身份；配方为本地研究 NP3 和已有手写 NCP 夹具，未打包进产品。来源见 [测试说明](../../../tests/photo-editor-device-probe/README.md)。

| 场景 | 原生报告 | 界面截图 |
| --- | --- | --- |
| 竖屏，字体 1.0 | [144 项通过](phone/report.json) | [编辑](phone/editor.png)、[全部](phone/all-filters.png)、[无结果](phone/search-empty.png)、[导出](phone/export.png) |
| 竖屏，字体 1.3 | [144 项通过](large-font/report.json) | [编辑](large-font/editor.png)、[全部](large-font/all-filters.png)、[导出](large-font/export.png) |
| 横屏，字体 1.0 | [144 项通过](landscape/report.json) | [编辑](landscape/editor.png)、[全部](landscape/all-filters.png)、[导出](landscape/export.png) |

每个场景都实际选择配方、模拟用户调节强度、离开重进和快速切换，验证预览不创建永久效果。混合三张导出经过真实 JPEG 编解码，与逐张渲染的 JPEG 字节一致，保留原始 1448×1086 像素、原图字节与可追溯来源；核对真实 MediaStore 副本字节，再导出复用两张效果。

采集预检中 Android 首次全屏教学浮层遮住截图，最初证据无效；设置专用模拟器已确认全屏教学后重采。随后修正了测试截图辅助代码在 accessibility root 为空时的错误；最终以上三份报告均通过且 APK 摘要一致。不能把早期截图或辅助脚本失败作为产品验收结论。

初轮 52 项／三预设的候选证据已保留在 `initial/`，其 APK 摘要为 `da4a219f4764f9d4763b56fc12b17135c71fa201a3aa332f3e44822a773f1ad4`。复核后补入 59 个配方的大库测试，修正已导出历史覆盖旧版本、单原图草稿角标、后部选中卡片／滚动恢复、解析数据缓存上限与横屏完整滤镜行；同时在暂停边界刷新待保存草稿。最终每个场景覆盖原图、旧／新效果的像素与实际版本菜单，单张导出保持所选版本，角标真实可见，第 60 项点击／收起后可见／重新展开位置，48 项缓存的位图与配方／profile 一起淘汰及重载。

后部滚动回归曾发现 GridView 位置恢复方式和缩略图数据刷新之间的冲突，已改用 GridView 选中位置恢复，并只更新可见图片；最终三份报告全部通过。

布局独立复核记录见 [FINISH-REVIEW.md](FINISH-REVIEW.md)，修正得分记录见 [FIX-VERDICT.md](FIX-VERDICT.md)。设计说明与系统记录已同步最终实现，五项修正均已获独立复核确认。没有运行面向 HTML/CSS 的检测器，因为该页面是原生 Views。

限制：此次不是实际相机、TalkBack、硬件手势流畅度、全部机型／平板或长期后台测试；不证明与 NX Studio 色彩一致。仅证明已有近似模型在本次预览和导出中使用一致的配方与强度。
