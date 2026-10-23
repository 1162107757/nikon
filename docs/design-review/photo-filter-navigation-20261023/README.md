# 直播预览与滤镜分类修正 · 82 / 0.15.1-beta

用户要求修复直播默认预设预览闪动，在编辑页原工具行展示可横滑的多个分类名称，保留最左侧四宫格打开完整选择框，并修正完整框分类响应。[需求](REQUIREMENTS.md)与[素材约束](ASSET-INVENTORY.md)限定本轮；继承 80／81 的原生 Operate 世界，不增加工具高度、新组合稿、图标或生产栅格。用户截图仅作缺陷证据。

## 已实现

[PhotoFilterEditor](../../../app/src/main/java/cc/huevia/ui/PhotoFilterEditor.java)保留 224dp 工具加 1dp 分隔，48dp 头行容纳左侧 GRID、在线／我的、竖屏短状态及横滑分类。横屏仍是 300dp 右栏，只隐藏短状态，完整状态仍在选择框、提示和辅助描述。内嵌与完整框共用 `selectCategory`，同步分类状态、缩略条、网格、实际数量与滚动起点；标题给出来源、分类和实际预设数。“无滤镜”、搜索、离线缓存／重试、来源及分类顺序保留，分类不改变照片已套用效果。

[PresetSelectionPage](../../../app/src/main/java/cc/huevia/ui/PresetSelectionPage.java)只将完成预览绑定到对应条目的已挂载卡片。同条目／键的已就绪图片可在 LRU 淘汰与重绑后保留，常规说明行预留高度。ERROR／UNSUPPORTED 优先于卡片持有图片，清旧图、禁用选择，ERROR 显示重试；账号／页面／generation 守卫保留。无进一步装饰或布局改造。

## 最终本地证据

[APK](../../../dist/huevia-0.15.1-beta-debug.apk)：82 / `0.15.1-beta`，3,487,341 字节，SHA-256 `2543241174c2c7b6656b08b4142d3c3812d095b2ecd732836a11ab9a3c173987`；原证书 `04db0f9d3d76c9831a0778cfabc1a0aaab984e05398da16eb644950ccfa0ae31`。

| 模式 | 实际字号／方向／主题 | 最终断言 |
| --- | --- | --- |
| [phone](../../validation/photo-filter-navigation-82/phone/report.json) | 1.0／竖屏／浅色 | PASS 163 |
| [large-font](../../validation/photo-filter-navigation-82/large-font/report.json) | 约 1.3／竖屏／浅色 | PASS 163 |
| [landscape](../../validation/photo-filter-navigation-82/landscape/report.json) | 1.0／横屏／浅色 | PASS 155 |
| [dark](../../validation/photo-filter-navigation-82/dark/report.json) | 1.0／竖屏／深色 | PASS 163 |

四报告同 SHA，共 644 条；每模式有环境和恢复记录。28 张原生原始状态在 `docs/validation/photo-filter-navigation-82/<mode>/`，含 hero 的 29 张复核副本在 `.impeccable/review/photo-filter-navigation-82/`，其 [manifest](../../../.impeccable/review/photo-filter-navigation-82/capture-manifest.json)关联最终包与原始图。原生 Views 不运行 HTML/CSS 检测器。

分类、GRID 和横滑使用实际 MotionEvents，按条目身份与数量验证；受控真实 worker、缓存淘汰和重绑核对已显示 Drawable 与卡片位置。旧 [81 基线](../../validation/photo-filter-navigation-81/phone/report.json)确实复现缓存压力丢图；该离线基线分类触摸本已通过，未复现用户实体设备的分类故障。真实选择确认读取失败及恢复后的真实重试通过；profile 失败经明确注入旧 READY 缩略图后走实际确认解析，不能据此声称支持该预设。单独“不使用预设”卡片只验证绑定 `performClick` 与一次返回回调，指针注入未定。

证据限只读隔离模拟器、既有应用模板及离线研究／手写配方。没有实体手机、真实相机、实时生产目录、帧率、TalkBack 或颜色标定结论。81 的 516、80 的 471 及旧主机套件不累计为 82。初审 `61d8ae4d…` 的 560 条和更早探针保留候选范围。

## 完成复核与发布分开记录

[初审](../../validation/photo-filter-navigation-82/FINISH-REVIEW.md)仅提出 M1：确认失败时卡片保留图错误恢复 READY。最后产品修正已将 ERROR／UNSUPPORTED 设为权威，新增实际失败／重试验证，最终 644 与最终状态图已更新。相同独立审阅者的 [FIX-VERDICT](../../validation/photo-filter-navigation-82/FIX-VERDICT.md)评分 M1 resolved、remaining clear、disposition ship，仅覆盖 M1 与已记录回归批次；全部 28 张和 hero 已重新打开，未发现修正引入的回归。不扩展为全产品复核。

依用户直接上传测试版授权，已发布 beta release `4c9aee32-1c86-4482-a68e-9c16f583406a`、revision 2；[发布说明](../../validation/photo-filter-navigation-82/PUBLICATION.md)和[精简回执](../../validation/photo-filter-navigation-82/publication.json)核对主域名／CloudBase 两处公开 APK 原字节及最新元数据均匹配最终 82，全部旧发布行保留。[下载测试版](https://huevia.cc/preset-library/download/?channel=beta)。

只读 `huevia-82-reviewed` 准备完成 57 runtime/artifact、22 syntax、8 archive、2 diagnostic 检查。实际生产归档只新增此 APK，41→42 项，已有运行字节、Unix 模式和函数配置均保留。下载 HTML、实际 download.js 和精确页面元数据查询通过 HTTP 200 验证；CUA 初始化失败，未作渲染后浏览器视觉检查。本轮只读隔离模拟器已在复核后关闭。

持久记录已合并到 [PRODUCT](../../../PRODUCT.md)、[DESIGN](../../../DESIGN.md)、[sidecar](../../../.impeccable/design.json)、[表面合约](../../../.impeccable/surfaces/photo-filter-editor.md)和[编辑说明](../../photo-filter-editor.md)。旧 80／81 的行为、摘要、测试数量和发布回执保留各自范围。
