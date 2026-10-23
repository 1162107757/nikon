# 构图、我的配方与导出时间 · 81 设计交接

日期：2026-10-23。版本：81 / `0.15.0-beta`。本记录合并已批准的局部扩展、实施规则及可复核证据，不重建 Huevia 的全局设计系统。

用户确认 80 试用“好像没啥问题”，批准此前建议中的构图和可复用配方，并要求修改照片导出后使用最新时间，以便相册靠前显示；沿用“改好了你直接上传测试版就行”的授权。授权范围见[REQUIREMENTS.md](REQUIREMENTS.md)。本轮沿用 [80 第二版照片优先稿](../photo-basic-adjustments-20261022/design-board-v2.png)的材质、工具密度与照片地位，不新增视觉世界或生成设计图。

## 实施结果

常驻工具仍为 224dp 内容和 1dp 分隔，176dp 内容区加 48dp“滤镜／调节／构图／配方”入口。石墨 `#141415` 画布、黑色 `#0B0B0B` 工具、浅蓝 `#8DBBFF` 操作和 Android 系统 sp 字体继续有效；面板切换不改变照片边界、缩放或位置。横屏保留右侧 300dp 可滚动工具区。配方保存“保存当前”保持单行和 48dp 高触区，实际 1.3 字号已检查。

| 能力 | 当前行为 |
| --- | --- |
| 构图 | 全屏暂存，四角裁剪、框内移动、原比例／自由／1:1／4:3／3:2／9:16、顺时针 90°、±45° 拉直（0.1°）。遮罩、三分网格和浅蓝手柄保持照片可辨。拉直放大覆盖，避免黑角。 |
| 提交与历史 | 取消／Back 不改草稿；应用整个构图记一个撤销步骤，未改变不新增步骤。工作区“重置构图”仍须应用。EXIF 归一，预览与完整像素输出共享几何，方向 1。 |
| 我的配方 | 本机按账号隔离，保存预设字节快照、强度和 13 项手动值，不保存照片或构图。可保存、套用、重命名和确认删除；名称 1–40 字符，同名不覆盖，错误在仍打开的表单内显示并保留输入。 |
| 逐张与批量复用 | 套用只替换色彩，保留目标照片自己的构图。多选经“套用效果 → 我的配方”更新未导出草稿，再明确导出；部分失败报告完成数并可重试。 |
| 状态与兼容 | PED3 保存几何，PED1／PED2 迁移为中性几何；几何进入渲染、缓存、完整导出及过期任务身份。最新效果只在完整身份匹配时恢复已导出设置；旧效果缺少参数会提示切原图或恢复草稿。 |
| 导出日期 | 每次显式效果导出新建一张使用本次时间的相册副本；复用缓存也创建新副本。只改新 JPEG 副本日期元数据，无像素重压缩；旧相册副本及缓存字节保留，成功后更新本地效果修改时间，原图字节和时间保留。 |
| 空项目直播入口 | 修复真实点击“照片直播”的空 UUID 闪退：未选项目时跳过配置查询，保留存储校验。最终三模式已初始化实际存储，空项目进入／返回三次均不退出 Activity。 |
| 相册回执 | 私有记录包含实际发布字节的 URI／SHA／长度／时间，支持冻结副本的未完成发布恢复。删除在互斥保护下先核对所有副本；内容被修改或归属变化会阻止删除。 |

完整行为及代码映射在[照片编辑说明](../../photo-filter-editor.md)。主要代码为 `PhotoFilterEditor`、`PhotoCropDialog`、`PhotoGeometry`、`GeometryPreviewProfile`、`PhotoComposition`、`PhotoRecipeStore`、`PhotoEditStore`、`PhotoEffectGallery`、`PhotoPresetRenderer`、`PhotoLiveGallery`、`PhotoPresetBatch` 与 `AlbumPanel`。

## 素材与系统记录

[ASSET-INVENTORY.md](ASSET-INVENTORY.md)记录新增生产栅格为 **0**。五个控制图标扩展现有 Canvas `AppIcon`，继续使用 24dp 坐标与 1.8 描边；裁剪框、遮罩、网格和手柄为运行时几何，照片／缩略图来自当前校验原图。没有外部图标包、生成照片或新字体资源。

[PRODUCT.md](../../../PRODUCT.md)记录产品范围；[DESIGN.md](../../../DESIGN.md)新增 81 局部章节，原 tokens 与历史保留；[design.json](../../../.impeccable/design.json)仅更新照片编辑扩展并归档 80 元数据；[表面合约](../../../.impeccable/surfaces/photo-filter-editor.md)与源码开头的 THESIS／OWN-WORLD／STORY／FIRST VIEWPORT／FORM 对齐。原生 Views 不适用 HTML／CSS 检测器。

## 最终本地包与验证

最终本地 APK 为 3,487,341 字节，SHA-256 `7861ae7163d17a5651a8c41b25ddf1b368e868cd326bdcc04a2bd72d5df7aa80`；沿用签名证书 SHA-256 `04db0f9d3d76c9831a0778cfabc1a0aaab984e05398da16eb644950ccfa0ae31`。

| 模式 | 最终报告 | 实际字号／方向 | 结果 |
| --- | --- | --- | --- |
| 手机竖屏 | [phone/report.json](../../validation/photo-composition-recipes-81/phone/report.json) | 1.0／1 | PASS 172 |
| 大字号 | [large-font/report.json](../../validation/photo-composition-recipes-81/large-font/report.json) | 1.3／1 | PASS 172 |
| 横屏 | [landscape/report.json](../../validation/photo-composition-recipes-81/landscape/report.json) | 1.0／2 | PASS 172 |

三个报告均记录同一最终 SHA，共 **516 条断言记录**；环境与恢复记录各自保留。覆盖真实裁剪拖动、取消／应用、一次撤销／重做、实际字号保存入口单行、同名错误内联、重命名、账号隔离、配方保留目标构图、逐张批量草稿及完整裁剪 JPEG 输出。相册路径核对原图字节／时间、EXIF 归一、单张与批量一致、重复渲染新建当前时间副本、无 JPEG 重压缩、实际发布回执、受修改副本阻止删除及互斥删除保护。

每种模式的十个截图状态为 `photo-live-no-project`、`editor`、`crop-original`、`crop-square`、`recipes`、`recipe-name-error`、`composition-export`、`batch-effects`、`batch-recipes`、`recipe-applied`。最终 30 张截图已逐张确认；供独立复核的副本含一张 hero 共 31 张，素材 metadata 扫描为 0 missing。[初审 FINISH-REVIEW.md](../../validation/photo-composition-recipes-81/FINISH-REVIEW.md)保留 M1 原始 finding；同一 reviewer 的 [FIX-VERDICT.md](../../validation/photo-composition-recipes-81/FIX-VERDICT.md)已确认 resolved／SHIP，仅覆盖 M1 修复和该批次回归，不是新的全表面审核。

主机日志分开记录数量与单位：[host-editor.txt](../../validation/photo-composition-recipes-81/host-editor.txt) 为 34,780 强度／草稿、644,888 手动行为／迁移／身份及 1,158 构图／配方检查；[host-batch.txt](../../validation/photo-composition-recipes-81/host-batch.txt) 为 609 批处理与 82 来源链检查；[host-project-auto.txt](../../validation/photo-composition-recipes-81/host-project-auto.txt) 为 48 项项目自动处理检查。没有把 80 的空间细节或更早主机统计算入本轮。

## 直播闪退追加修复

用户追加“点击照片直播会闪退，一起处理”。在 SHA `936b31b4136cd4e305e67c90e4a587324792bf2a137c63f85486f51915d18b12` 候选中以真实点击复现，异常为 `Invalid UUID string:`；[修复前栈](../../validation/photo-composition-recipes-81/photo-live-crash/before-fix.txt)指向 `AlbumPanel.updateLivePresetProgress` 对空 `selectedLiveProjectId` 的配置查询。修复只在 UI 查询前增加无项目守卫，继续保留项目存储的严格 UUID 校验。最终每模式新增 17 项断言：先初始化真实项目存储，空项目实际进入直播、返回已下载三次，检查 Activity 未退出、默认预设入口禁用且存储引用保持。未创建项目、启动直播或连接相机。

## 配方排队顺序修复与复核

初审 M1 发现：真实 IO 队列繁忙时先点配方 A 再点 B，A 会先改变起始设置，导致 B 被现有快照守卫丢弃。修复为每次选择分配最新请求序号，旧请求回调在修改设置与撤销前退出，同时保留照片、账号及起始手动／几何快照守卫。真实 IO 门闩回归在 UI 线程排入 A→B 后释放，确认最终 B 生效、目标构图不变、只新增一个撤销步骤；后续正常撤销／重做及导出继续通过。每模式新增 5 条记录，已包含在 PASS 172／总 516，不另累计。

同一 reviewer 重新打开最终 30 张必需状态图和 hero，核对 manifest 与实际截图哈希／像素一致，没有发现修复引入的可见回归。M1 为 resolved，`remaining: clear`、`disposition: ship`；该 SHIP 限 M1 和修复批次，初审范围与边界保留。

真实主导航点击进入直播及进程冷启动后恢复空项目状态另见[人工补充记录](../../validation/photo-composition-recipes-81/photo-live-crash/after-fix-main-navigation.json)。UIAutomator idle 检测失败，自动文字断言不确定；以稳定截图与存活进程确认显示状态。该补充记录不计入 516。

## 历史与边界

`before-review-fix/{phone,large-font,landscape}` 每模式 PASS 167，SHA `f5b34441b4616c4ebd9a9fcaad135f258e0ba87d85be146c347e73d57f9bdd03`，保留 M1 修复前候选及初审 manifest；不计入当前 516。

`pre-photo-live-fix/{phone,large-font,landscape}` 每模式 PASS 150，只属于直播修复前候选 SHA `936b31b4136cd4e305e67c90e4a587324792bf2a137c63f85486f51915d18b12`，不计入当前 516。其构图／配方证据保留，但不能证明空项目直播入口已修复。

`initial/{phone,large-font,landscape}` 每模式 PASS 146，仅属于初轮候选 SHA-256 `af0b41fe33c814c6d470845290c828801934819ac4c1db78eb46ef227e31f067`，不计入最终 516。初轮问题包括大字号保存入口及表单错误展示，修正后增加对应断言；最终删除路径也明确使用互斥 lease。80 及更早证据保持原版本、哈希与检查范围，不累计为 81。

验收仅使用隔离只读 Android 模拟器、既有合成模板和 23 条真实／手写本地配方夹具，执行实际原生手势／Canvas、JPEG、EXIF、草稿及 MediaStore 路径。没有实体手机、真实相机、实时生产目录、FPS、TalkBack 或颜色标定结论。

## 完成复核与发布状态

最终 81 已于 2026-10-23 发布 beta，release `8448462f-6610-4800-b057-6f045ce03cda`、revision 2。主域名和 CloudBase 域名均返回与本地最终签名 APK 完全一致的公开字节及最新 beta metadata，旧发布行全部保留。见[公开发布说明](../../validation/photo-composition-recipes-81/PUBLICATION.md)、[精简回执](../../validation/photo-composition-recipes-81/publication.json)和[下载测试版](https://huevia.cc/preset-library/download/?channel=beta)。

最终 `huevia81-reviewed` 的只读准备通过 57 项 runtime／artifact、22 项 syntax，archive guard 8 项、diagnostic 2 项分别通过。此前 discarded `huevia81-livefix` 的失败原因为并行密码测试耗尽主机 malloc 资源；最终 operator 串行运行同样 57 项，避免资源耗尽，不改变生产 runtime。部署仅在下载的生产函数包中新增当前 APK（40 → 41），完整旧成员字节、Unix modes 与函数配置已核对保留；权威材料为 `online-library/build/huevia-81-reviewed/{audit,deployment,public-verification}.json`，精简回执链接见上。

下载页 HTML、实际脚本 HTTP 200 及页面使用的精确 beta metadata 查询均已验证；CUA 初始化失败，没有浏览器渲染视觉复核结论。包发布不代表用户手机已经安装，也不扩大原生模拟器验证范围。[178 文件源快照清单](../../validation/photo-composition-recipes-81/source-snapshot.json)与[ZIP](../../validation/photo-composition-recipes-81/source-snapshot.zip)在验证目录保存；上述 M1 限定 SHIP 与实际发布回执分别承担复核和发布证据。
