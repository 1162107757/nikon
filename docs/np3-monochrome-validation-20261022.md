# Dreamy 单色手机模拟：78 测试包验证

本次修复原始 Premium Dreamy Mono／Premium Dreamy Peep 在手机上被拒绝的问题。NP3 字节未改写；新增严格的 Deep Tone Monochrome 子集解析及独立模型。Flexible Color v3 数值实现保持原样，缓存与原图恢复登记单色模型身份。

## 交付身份

- APK：`dist/huevia-0.13.10-beta-debug.apk`，`cc.huevia`，78／0.13.10-beta，3,442,285 bytes。
- SHA-256：`0b1767980b10b467a34464015c15090951401c6d4300c9bb64a3a61fd7ea88e5`。
- APK v2／v3 签名验证通过，沿用 signer `04db0f9d3d76c9831a0778cfabc1a0aaab984e05398da16eb644950ccfa0ae31`。
- 单色模型：`np3-0310-deep-tone-monochrome-spatial-v1`。
- 仅创建该 APK 的限时下载链接，匿名下载所得大小／SHA 与冻结 APK 一致。未创建或发布 78 的版本记录；前后检查 beta/latest 均为 77。
- 私人 NEF、TIFF 与对照图仅在本机，未上传。临时下载记录在本机 `dist/huevia-0.13.10-beta-download-sign.json`，仅授权这一个 APK。

## 回归与实际 Android

主机通过：NP3 合成 24,829、单色与旧 FC 固定输出 130,982、固定 142 配方归档 648,268、照片编辑 34,780、批处理 609、来源关联 82、相册保存 221、删除 36、原图字节 18 项检查。

Android 专用模拟器直接安装冻结 APK，使用公开配方及合成图片：

- [151 项渲染检查](validation/np3-native/78/monochrome-native/dreamy-20261022T051433Z/renderer-only/light/report.json)：两款模板、完整 1800×1200 JPEG、0／37／100% 强度、alpha、sRGB／EXIF、取消及输入保留。
- [184 项单色批处理检查](validation/np3-native/78/monochrome-native/dreamy-batch-20261022T051643Z/monochrome-batch/light/report.json)：同一全新合成源分别执行两款独立批处理，各首次成功 1／失败 0，再次重套复用 1；来源关联正确，两款输出不同，均与独立渲染器 JPEG 逐字节相同。
- 同目录保存 152 个当前产品源码／Manifest／build.gradle 的散列、探针／素材／APK 绑定及签名报告。独占的只读无窗口模拟器已关闭，PID、serial 和端口均复核退出。

旧原生批处理探针未独立核对 MediaStore，不能据此声称没有发布：批处理代码会尝试发布原图与效果，旧报告的 false 字段不是测量结果，见[更正记录](validation/np3-native/78/monochrome-native/MEDIASTORE-CLARIFICATION.md)。系统相册界面、多张照片分别设置后的交互与物理手机性能由手机验收。主机批处理和保存检查通过不能代替这项人工验收。

## NX 对照与边界

通过 NX Studio GUI 导入原 Mono，确认 `0x0655` 对应深色调单色、`8300` 红／`8400` 绿、`8000` 无调色。由同一基准只改变单项设置，导出 6048×4024 未压缩 RGB TIFF16；保存 NKSC、完整曲线和非 PC 控制绑定。红色对照是在原 Mono 内只改绿→红，并未声称直接导入原 Peep。

使用 DSC_6182 选择紧凑近似后，先冻结权重和 1.35 响应（候选 SHA `cd39476ed979a70eb83df707bf8954982ebc6c330be16dedbb1c332d3137d540`），才检查 DSC_6184。原始 TIFF16 经显式 ICC16→sRGB8 诊断归一化，实际 Java 对完整 24,337,152 像素渲染并执行共享空间处理。两款 Java 逐点管线分别与冻结公式的全图预测零像素差异；未用第二张照片重新拟合。

| DSC_6184 完整像素诊断 | RGB MAE（0–255） | ΔE00 中位 | ΔE00 P95 |
| --- | ---: | ---: | ---: |
| 绿色／Mono | 2.02114 | 0.65608 | 1.96273 |
| 红色／Peep 对照 | 2.23308 | 0.49203 | 1.69811 |

完整统计与另列的 61,206 格点统计保持区分。本机证据在 `.research/np3-calibration/other-bases-20261022/diagnostic/frozen-product-holdout-run1/{green,red}/report.json`；推荐图在各色 `figures-v2/`。红色瓶身深黑区域仍比 NX 稍浅。两张都是室内原图，且应用处理已显影的 RGB，不能据此宣称完全恢复 Nikon 算法、跨场景校准或 RAW 显影等价。

支持条件、固定公式及其它拒绝范围见 [NP3 渲染说明](np3-rendering.md)。手机验收步骤见 `dist/huevia-0.13.10-beta-TEST-NOTES.txt`。
