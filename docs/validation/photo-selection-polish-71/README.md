# 预设选择样式调整 · 71

2026-10-20，`0.13.3-beta`（71），3,417,709 bytes。APK SHA-256：`7507bd9556c0254395d5d50350f9d88a1cc90578ba5798ffc4d0c8517eb95265`，构建与 v2/v3 签名通过。

按用户红框反馈调整两级选择的视觉关系：“在线／我的”共用 32dp 高的分段轨道，选中面片 28dp 高；分类使用蓝字与 18×2dp 短下划线。来源组与全部入口相隔 12dp，标题行与分类行相隔 6dp，类别之间留 6dp。按钮保留 48dp 触摸区；类别字重固定，点击时不改变宽度。展开列表复用同一套样式与分类 View；保留之前完整原图才能进入编辑的规则及“原图”按钮。

## 原生检查与审图

固定 `emulator-5582` / `ranchu`，同一最终 APK，实际字号由每个模式的 `environment.txt` 回读。三个 `report.json` 的摘要均匹配最终包。

| 模式 | 实际字号 | 结果 | 截图 |
| --- | ---: | --- | --- |
| 竖屏 | 1.0 | PASS 282 | [黑白选中](phone/selection-black-white.png)、[展开](phone/selection-expanded.png) |
| 大字竖屏 | 1.3 | PASS 282 | [黑白选中](large-font/selection-black-white.png) |
| 横屏 | 1.0 | PASS 282 | [展开](landscape/selection-expanded.png) |

共 846 项。覆盖实际触摸区域、分组及类别间距、来源材料边界、分类点击前后子 View 身份与矩形不变，以及已有的在线／我的切换、分类、满队列选择、缩略图完成后横条位置稳定、离线缓存／无缓存／重试恢复和本地分类保留。主栏与展开层截图按同一首批审阅，字号与横屏均可读，无需修复确认轮。脚本结束恢复字号 1.0。

测试复用明确标记的 23 项离线目录和既有真实／手工配方，预览使用实际 Android 图片渲染。未连接真实相机、未认证用户手机帧率或实时生产目录。69／70 的检查保留原版本范围，不算入本轮结果；没有新的数据库迁移或服务部署。

复验：`tests/photo-editor-device-probe/run.ps1 -ExpectedVersion 71 -Scope selection-polish -Mode phone`，另支持 `large-font`、`landscape`。本轮结论：**SHIP，限已审阅的选择样式、间距与点击稳定性。**
