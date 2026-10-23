# 本地相册、同步与保存位置 · 83 交接

83 / `0.16.0-beta` 已按用户批准实现，并完成同审阅者修正复核。最终已发布包为 [huevia-0.16.0-beta-debug.apk](../../../dist/huevia-0.16.0-beta-debug.apk)，3,528,301 字节，SHA-256 `f320eef5e6068e94fff54f37509374d09f723492f6fb3077052d727e55d79ebd`。已发布 beta release `5e67b2cd-d628-46c0-b80f-ad4d2c1f4af1`、revision 2；主域名／CloudBase 均返回最终 APK 原字节及匹配的最新元数据，见[发布回执](../../validation/photo-local-album-83/PUBLICATION.md)、[结构化记录](../../validation/photo-local-album-83/publication.json)与[下载测试版](https://huevia.cc/preset-library/download/?channel=beta)。旧发布行及五个旧本地包保留；部署归档的 43 项成员、原模式／配置已独立核验。

## 已批准与实际行为

[REQUIREMENTS.md](REQUIREMENTS.md)记录用户批准合并需求并明确“可以，开始实现”，及后加的悬浮“＋”、居中“点击获取授权”和简化页面要求。沿用此前完成后直接上传 beta 的授权。视觉和资产边界见[ASSET-INVENTORY.md](ASSET-INVENTORY.md)：云白／石墨、原生 sp、Canvas 图标、48dp 触区；新增生产栅格和图标均为 0，本轮没有新视觉世界或组合稿。

- 根导航为“预设／同步／相册／设置”。同步保留原相机照片、直播、已下载、项目和旧数据；新相册默认“App 内”，可切“手机”。根页切换不停止共享相机或直播控制器。
- 相册以日期、真实缩略图、文件名和版本入口为主体。单一 56dp 浮动“＋”在两来源调用 JPEG／PNG 多选系统文件选择器；刷新、选择及相关权限放入原生更多菜单。未授权手机来源居中显示可点击“点击获取授权”，部分授权可重新选范围，拒绝／撤销不阻断正式 App 内照片。
- 紧凑横屏采用最小 48dp 标题行与合并来源信息行，列数由可用 body 宽高、字号和导航预留计算；完整首行照片／文件名可辨识。引导可滚动，导航和加号有预留空间。此页自适应不等于平板认证。
- 手机浏览只读当前权限范围内 JPEG／PNG 的分页与缩略图，不全库复制；明确编辑／批量选择才提交正式私有副本。JPEG 保持源字节，PNG 须确认完整保留源文件并以白底 JPEG 工作副本编辑。导入身份按 URI 与源内容，不承诺不同 URI 的相同像素合并。最多 100 张、单张 96 MiB／5,000 万像素、总量 1 GiB 并检查设备内存；失败不静默缩图，已经提交的正式副本可保留。
- 既有滤镜、13 项调节、构图、配方、对比、撤销、账号／逐张草稿与冻结批处理继续复用，配方套用保留目标构图。编辑说明见[docs/photo-filter-editor.md](../../photo-filter-editor.md)，方向见[本地相册](../../../.impeccable/surfaces/local-album.md)与[编辑器](../../../.impeccable/surfaces/photo-filter-editor.md)。

## 保存、复用与原图边界

相机单张／批量、自动效果和直播首次默认仅 App 内，编辑导出首次默认手机相册。对话框明确两个位置，按场景／直播项目记忆正向确认的选择，取消不记忆；任务与失败重试冻结接受时的目标和额外副本意图。仅 App 内保存正式结果及草稿关联，系统相册新增 0，不自动上传；正式原图、PNG、来源记录及草稿不属于预览缓存。

选择手机相册时，首次新发布按本次保存时间；同照片同版本已有有效发布经 URI 状态／字节／归属核对后复用，此时明确勾选“另存新副本”才额外新增，元数据更新不重压像素。已存在公有副本、渲染缓存字节及原字节／时间保留；成功发布后更新本地效果文件修改时间。83 的默认复用覆盖历史 81／82“每次显式导出都新增”的行为；旧交付证据仍只适用于旧版本。

未修改的导入 JPEG 可以只读核对仍存在的相同手机原来源，避免重复发布；来源记录不会变成 App 自有发布回执，也不授予删除外部手机原图的权限。来源缺失／改变与当前不可读取分开处理，不可核对时要求恢复授权／重新选择或明确另存。PNG 的白底 JPEG 是另一个版本。Android `/data/user/0` 与 `/data/data` 合法私有根别名已兼容；目录穿越、越界、符号链接及所有权检查保留，失效自有回执在相同保护下更新。

## 最终验证与来源

| 记录 | 最终范围 |
| --- | --- |
| [native-matrix.json](../../validation/photo-local-album-83/native-matrix.json) | phone／实际 1.3 字号／横屏／深色各 PASS 300，四模式恢复 PASS；1,200 次重复断言执行，不是 1,200 个独立用例 |
| [原生证据目录](../../validation/photo-local-album-83/README.md) | 每模式 7 个状态，28 张原始 PNG；各模式有环境、恢复、报告与来源记录，全部绑定最终 APK |
| [最终复核 manifest](../../../.impeccable/review/local-album-83/fix-confirmation/capture-manifest.json) | 28 张来源不改像素的复核副本及 hero，保留原始路径、哈希、APK 和来源元数据；PNG 编辑图是断言后的滤镜态，不是可见调节页证明 |
| [final-source-pins.json](../../validation/photo-local-album-83/final-source-pins.json) | 189 项构建／探针输入在 10 次阶段观察及最终快照间不变；不是可复现构建证明 |
| [source-snapshot.json](../../validation/photo-local-album-83/source-snapshot.json) | 374 文件构建后源码记录，ZIP SHA-256 `6c6f71092c712c5b5286a675ccbced002f9fb125874f1b375b049af1a8642a62`；不能倒推历史编译输入 |
| [最终聚焦 host 来源](../../validation/photo-local-album-83/host-gallery-final-provenance.json) | 69 项前后输入哈希一致；gallery 250、origin 86、deletion 36、identity 18，共 389 检查，含规范化点路径别名／失效自有回执回归；Android 私有根别名由原生另证 |
| [较早 host 来源](../../validation/photo-local-album-83/host-provenance.json) | 保留实际历史输出及各组单位；事后源码哈希不证明当时执行输入，循环不等于独立场景，12 项目标控制器检查包含于 889 而不另加 |
| [最终发布准备](../../validation/photo-local-album-83/operator83-final-result.json) | 57 runtime、22 production syntax、8 archive、2 diagnostic PASS；另列 9 operator/helper syntax。只新增 APK 42→43，旧五包、运行字节、Unix 模式／配置保留；该准备记录本身不证明发布，实际上架另由[发布回执](../../validation/photo-local-album-83/publication.json)确认 |

原生在隔离 Android 模拟器使用真实 MediaStore、私有文件、生成 JPEG／透明 PNG 与既有仓库样图，执行授权拒绝／撤销／授予、实际导入、编辑／批量、仅 App 内零新增、有效版本复用与明确新副本。手写 512 字节结构 TIFF／NEF 只验证 RAW 保存包装、冻结目标和失败重试，不是相机传感器数据。当前运行的故障注入只恢复其自身夹具的实测字节与修改时间。

授权与“＋”通过实际绑定 View 点击到框架 Activity 派发，Instrumentation 监控返回取消并核对无导入。没有认证外部 DocumentsUI／权限控制器画面、文字和完整系统流程。通知权限预授予以隔离无关模态；host 使用的 Android／provider／codec／acquisition 替身不认证真实 Android 解码、EXIF 或权限。以上不认证实体相机／直播传输、传感器 RAW 保真、吞吐／FPS、颜色标定、TalkBack 或手机长期使用。原生 Views 不运行 HTML/CSS 检测器。

## 完成复核与保留历史

[FIX-VERDICT.md](../../../.impeccable/review/local-album-83/FIX-VERDICT.md)为同一审阅者结论：M1 紧凑横屏浏览／授权引导 **resolved**，remaining clear，disposition **ship**，范围限 M1、后加控件布局与记录的回归批次。审阅者打开全部最终 28 张与 hero，未发现修正批次回归；菜单打开态没有视觉认证。M2“未授权缺选图入口”是已撤回的审阅事实误读，其更正保留于[初审](../../../.impeccable/review/local-album-83/finish-review.md)，不写成产品修复。

`fix-before-fcd0/`保存旧四模式每模式 273 的候选／探针／截图，不计入最终 300。`preliminary-1311/`、来源别名诊断、探针路径假设和通知模态中断等历史按[验证 README](../../validation/photo-local-album-83/README.md)保留实际失败／恢复边界；后来的恢复不追认旧夹具已恢复。80／81／82 的布局、哈希、测试数与发布回执保持原范围，不累计为本轮。

发布最终核验覆盖实际下载 HTML、download.js 及精确 beta 页面元数据查询，均与 83 对应；范围限 HTTP，没有浏览器渲染结论。专用 `ranchu` 模拟器 `emulator-5582` 在捕获与复核后关闭，见[清理记录](../../validation/photo-local-album-83/emulator-cleanup.json)，没有实体设备操作。

本次最终状态同步只刷新上述七份文档；产品、测试、设备、云端和发布数据库均未由本次文档同步修改。
