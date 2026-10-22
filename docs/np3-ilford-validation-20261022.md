# Scott’s Ilford 400 LO：普通单色扩展

0.13.11-beta（79）增加原始 Scott’s Ilford 400 LO 的手机近似模拟，包含模板、照片预览、强度、完整 JPEG 和每张照片独立设置的批处理入口。详情页只在显示时替换已知的旧“不支持手机模拟”平台说明，保留文件和存储的原简介。

## 文件与支持条件

原 NP3 为 1036 bytes，SHA-256 `ff57c07d5c7df1b6f6ffe031ada0b987f6242043c28a2837dc40e3ebdd14561f`。文件未改写。原第三方归档仍在本地，未复制进产品 APK。

交付前还以无凭据的公开请求获取当前线上目录及该配方文件，两者均 HTTP 200；实际 1036 bytes 和 SHA-256 与上述原文件、目录声明完全一致。见[线上文件核对](validation/np3-native/79/online-scott-hash.json)。

在 NX Studio GUI 导入后，修改空间参数时显示基底 `[MC] 单色`；Filter Effect 为“无”，Toning 为“黑白”。新增严格支持 `0310`／基底 `064d`／filter `8000`／toning `8000`／有效自定义曲线／已知哨兵及空间参数的子集。未知字段、其它滤镜、调色、Auto、Quick sharp、无曲线或其它基底仍拒绝；名称不作为兼容白名单。

标准单色模型身份为 `np3-0310-monochrome-spatial-v1`。原 Deep Tone 模型身份和数值路径不变。模板成功／失败缓存都包含新的聚合身份，完整 JPEG 与原图恢复另登记标准单色身份，保留现有强度和原图关联语义。

完整 236 配方归档当前为 228 Flexible Color + 4 单色结构兼容，4 份拒绝。结构兼容不代表这些文件均完成 NX 校准。

## NX 受控参考与固定模型

本轮 GUI 共采集 6 份 6048×4024、未压缩 RGB TIFF16，保留每份 NKSC 和原生主图像素／ICC 的散列。每张原图的组内 38 个非 Picture Control 模块均核对相同。私人原图和对照仅本地保存。

DSC_6182 的四份为：Flexible-zero 基线、原 Scott 全参数、只将三项空间参数归零、再关闭曲线且对比度／亮度为零。曲线对照支持先生成基底灰度、再应用原始采样曲线：使用 NX 无曲线输出再套原曲线，预测曲线零空间输出的 MAE 约 0.052／255。

选择固定、非负且和为 1 的 Rec709 权重，不使用单张照片拟合 RGB 权重。通道为归一化已编码 sRGB：

```
g = clamp(.2126 R + .7152 G + .0722 B, 0, 1)
q = pow(g, 1.92)
t = q / (q + .48 * (1 - g))
output = round(255 * original257PointCurve(t))
```

随后执行既有共享空间处理，再进行强度混合。两项基础响应参数只由 DSC_6182 选择并取整，响应端点固定、单调有界；控制点／Gamma 元数据不重复应用。冻结 spec SHA-256 为 `1ffa112f25e016b4d564663b087cc2bf2b7ac46b2394bb15dac2c554ca5de656`；DSC_6184 像素未用于选择或调参。

显式 ICC16→sRGB8 生成诊断副本后，真实 Java 完整渲染两张各 24,337,152 像素。两张逐点管线均与冻结公式逐像素一致；完整空间处理对 NX 的全图统计如下，另有 61,206 格点结果独立记录。

| 原图 | 全图 RGB MAE／255 | ΔE00 中位 | ΔE00 P95 |
| --- | ---: | ---: | ---: |
| 6182 训练复核 | 2.23297 | 0.63641 | 1.61410 |
| 6184 冻结验证 | 3.66887 | 0.91057 | 3.58029 |

已人工检查横排图和原尺寸中心裁切。6184 叶片及茎部的灰阶略亮、局部锐度仍有差异。两张原图都是室内场景；这不是 Nikon 算法恢复、RAW 显影等价或跨场景完整校准。

本地证据根为 `.research/np3-calibration/ilford-20261022/`：训练／验证采集索引在 `evidence/`，冻结 spec 和真实产品验证在 `diagnostic/`。推荐图为 `diagnostic/fixed-product-validation-run1/6184/DSC6184-Scott-Ilford-400-LO-NX16-diagnostic-vs-phone-overview.png`。

## 主机和构建

独立主机检查通过：24,829 FC 合成、131,630 Deep Tone／旧 FC 回归及全归档、164,095 标准单色、648,268 固定142配方、34,780照片编辑、609批处理、82原图关联、221相册保存、36删除、18原图字节检查。新增公式测试覆盖42,024个代表像素和四种合法曲线，参考直接读取曲线独立插值；两款原 Dreamy 的逐点和整图空间输出 SHA 均不变。

APK 为 `dist/huevia-0.13.11-beta-debug.apk`，`cc.huevia`，79／0.13.11-beta，3,442,285 bytes，SHA-256 `81ce86556a10a828a71412a2d06e86bb260b42266ad42737292bddf7c270a1a0`。APK v2／v3 签名验证通过，沿用现有 signer。编译前后152个产品源散列相同，见 `dist/huevia-0.13.11-beta-build-proof.json`；这记录构建期间的工作区稳定性，不声称源码嵌入 APK。

临时下载仅授权此 APK，匿名下载后大小／SHA 与本地冻结文件一致。未创建或发布79版本记录；beta/latest 前后保持77。手机验收步骤见 `dist/huevia-0.13.11-beta-TEST-NOTES.txt`。

## Android 原生验证

同一冻结 APK 在独立 Android 模拟器 `emulator-5582` 通过 [299 项检查](validation/np3-native/79/monochrome-native/ilford-recovery-20261022T1331Z/monochrome-batch/light/report.json)。报告 SHA-256 为 `37adf35e2ea5089300cc768269dcfb6f6ffc2d4320e76a426f3cd0acbf1e0496`，Probe SHA-256 为 `f592bfd1bf64c37e48cbebdd05b612e9705c99379088fee82569d2543938cbc5`；验证前后152个产品源未变，绑定见同目录 `artifact-binding.json`。

实际调用 APK 内的产品类，使用原始公开配方和 1800×1200 合成 JPEG，覆盖 Scott／Dreamy Mono／Dreamy Peep 的模板、0／37／100% 强度、Alpha、EXIF、sRGB、取消和原图保护。三款分别批处理均生成不同结果、与独立完整渲染的 JPEG 字节一致，第二次导出复用原结果且不重写。

另以两个全新原图，通过编辑器实际 `startEdits` 接口一次提交 Scott 37% 和 Dreamy Peep 100% 两份独立草稿：首次 completed=2／failed=0，重复时 reused=2；各图预设、强度、来源关联、导出标记和完整 JPEG 字节均正确。实际查询 MediaStore 已提交行并读取内容，效果和原图发布字节均匹配。此处为管线实测，不是手势界面或物理手机验收；不以模拟器耗时证明手机速度或 NX 一致性。

本次启动器及 QEMU 进程均已退出，ADB 已无该 serial，见[清理记录](validation/np3-native/79/monochrome-native/ilford-recovery-20261022T1331Z/monochrome-batch/light/cleanup.json)。保留本任务 D 盘临时 AVD 和本地证据；没有修改用户 C 盘 AVD 或其它程序。
