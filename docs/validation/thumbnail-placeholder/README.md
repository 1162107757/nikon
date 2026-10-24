# 相册缩略图占位专项验证

已按用户审核的[设计稿](../../design-review/thumbnail-placeholder-20261024/design-board-v1.png)实现；生产变更范围和状态规则见[实现说明](../../design-review/thumbnail-placeholder-20261024/IMPLEMENTATION.md)。

## 本地最终测试包

“梳理机型边框需求”任务已确认此 SHA 为固定的**本地最终测试包**，其边框独立修正复核结论为 SHIP。占位组件已在同一份 APK 上完成原生重验和有界视觉复核；本记录仅证明占位组件专项。版本 `89 / 0.18.0-beta`，APK SHA-256：

`1f74ece275423198208750b804e7dad8e72c443b881c02eccb85eea9c4cd2ba0`

测试使用该 APK 的独立不可变副本。每次执行均比对设备实际安装 APK 的字节哈希及版本，未通过旧包、网页或设计图代替原生结果。

交付包为仓库内的 [huevia-0.18.0-beta-debug.apk](../../../dist/huevia-0.18.0-beta-debug.apk)。构建负责人确认不再重建；此次状态更新又核对了该文件 SHA，未重跑测试或更改原始证据。仅本地交付，未发布、未安装实体手机。

初始候选 `e6401c06…638670` 的三模式原始记录与复核已归档至 [initial-e6401c06/](initial-e6401c06/)，上一候选 `d433422a…7eb96f6` 归档至 [candidate-d433422a/](candidate-d433422a/)，不与本次结果混用。两份生产源码保持源哈希不变；最终候选重验未修改生产或测试代码。

## 结果与范围

| 模式 | 原生场景 | 结果 | 记录 |
|---|---|---|---|
| phone | 浅色、系统字号 1.0 | PASS，50 项断言 | [报告](phone/report.json) · [加载](phone/01-loading.png) · [成功/失败](phone/02-resolved.png) · [刷新恢复](phone/03-recovered.png) |
| font1.3 | 浅色、系统字号 1.3 | PASS，50 项断言 | [报告](font1.3/report.json) · [加载](font1.3/01-loading.png) · [成功/失败](font1.3/02-resolved.png) · [刷新恢复](font1.3/03-recovered.png) |
| dark | 深色、系统字号 1.0 | PASS，50 项断言 | [报告](dark/report.json) · [加载](dark/01-loading.png) · [成功/失败](dark/02-resolved.png) · [刷新恢复](dark/03-recovered.png) |

同一套 50 项断言在三个配置下重复执行，共 150 次；不是 150 个不同场景。验证实际 `LocalAlbumPanel` 的 MediaStore 查询、两个图片 worker 的等待状态、真实 JPEG 解码成功与失败、过期 view tag 防串图，以及修复失败图片后通过原有刷新入口恢复。真实照片仍为 Bitmap/CENTER_CROP；占位无 intrinsic 尺寸，因此小图形不会被 ImageView 放大。

9 张截图均为 `UiAutomation.takeScreenshot` 的原始原生 PNG（1080×2424），未编辑像素。逐图 SHA-256、设备密度、实际 fontScale、uiMode 在各模式 report.json 中。测试源、构建和 runner 哈希见各目录 provenance.json。控件说明文字的 sRGB 对比计算见 [contrast.json](contrast.json)，不代表整 App 的无障碍认证。

[独立视觉复核](FINISH-REVIEW.md)在核对三份报告后检查了指定的 6 张原生截图，结论为 PASS，范围仅为上述测试包及缩略图组件；探针执行者另已逐张检查全部 9 张截图。

## 数据清理与并行协作

仅在本任务独占的 `emulator-5586` 生成两张测试图片/模式，共六条 MediaStore 数据。每条均按其精确 URI 删除，且验证 `verifiedAbsent=true`；未使用真实用户照片。三模式的系统字号、旋转、夜间模式和三项运行时权限均已恢复至各次捕获的基线，见各目录 restoration.json。本轮三次夜间基线均为 no；上一候选不同的基线仍保留在其归档中。测试未操作其他任务的 emulator-5582 或实体手机。

交付时两份生产源码与记录的源哈希一致。测试结束后仅关闭独占的 5586 释放资源，见 [emulator-cleanup.json](emulator-cleanup.json)；其 final-candidate 状态保留关停时的历史记录。5582 由另一任务管理。当前证据仅对应本次测试包哈希与范围，不自动转用到其他字节不同的 APK。

生产文件仅 `PhotoPlaceholder.java` 与 `LocalAlbumPanel.java`，交付时的源哈希见 [owned-source-pins.json](owned-source-pins.json)。另一任务负责边框文件、统一版本、全局产品/设计文档与最终 APK；本任务不写入其生产文件或构建输出。

这是缩略图组件的专项证据，不涵盖全相册、相机、边框、性能或全 App 测试。[复现方式](../../../tests/thumbnail-placeholder-device-probe/README.md)。
