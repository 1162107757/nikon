# 手机端 NP3 调色

手机端读取预设中的参数，对照片已经解码的 RGB 像素进行近似调色。原始 NP3 与照片保持原样，处理结果另存。这个过程发生在手机上，不要求拍摄机型支持 NP3，也不会让老机型获得在机内加载 Flexible Color 的能力。

NP3 是容器格式，不能仅凭扩展名断言滤镜可用。当前处理经过结构和参数检查的 NP3 `0310` Flexible Color 子集，以及下述 Monochrome／Deep Tone Monochrome 受限子集；Creative Picture Control、尚未解释的非中性字段、未知版本及损坏文件会返回具体原因。

0.13.11-beta（79）增加 `np3-0310-monochrome-spatial-v1`，支持原始 Scott’s Ilford 400 LO：`0x064d` 基底在 NX Studio 中实证为 `[MC] 单色`，本轮仅支持 Filter Effect Off（`8000`）／Toning 黑白（`8000`）及有效自定义曲线。已编码 sRGB 通道归一化后，`g = .2126R + .7152G + .0722B`，`q = g^1.92`，基底响应 `t = q / (q + .48 × (1 − g))`，随后应用原始 257 点曲线、8 位舍入、共享空间处理和强度混合。基底响应端点固定且单调有界；权重固定，两项响应参数只使用 DSC_6182 选择并冻结，DSC_6184 不参与拟合。Deep Tone 的原数值路径与模型键保持不变。这仍是已显影 RGB 上的应用近似，未恢复尼康完整算法。

0.13.10-beta（78）增加 `np3-0310-deep-tone-monochrome-spatial-v1`，支持原始 Premium Dreamy Mono／Premium Dreamy Peep：基底 `0x0655`、红／绿 Filter Effect、无 Toning、有效的 257 点自定义曲线、未激活的传统／Flexible 调整以及已知空间参数。其它滤镜、Auto、Toning、无曲线、Quick sharp 或未知字段仍明确拒绝。按二进制字段分派，文件名不会决定是否支持。

Deep Tone 单色近似顺序为 sRGB 解码 → 线性 RGB 加权（红 `.80/.18/.02`、绿 `.15/.80/.05`）→ sRGB 编码 → 共用 `1.35` 次幂 → 原始采样曲线 → 既有空间处理 → 强度混合。曲线编辑器 Gamma 不重复应用；曲线插值直接计算，不用粗粒度合成 LUT。参数只使用 DSC_6182 的受控单项对照选择，之后冻结，再用 DSC_6184 检查。这些权重与响应是应用近似，并非恢复尼康算法或完成跨场景校准。两份 NEF 都是室内样本，照片及 NX 对照仅保存在本机。

完整第三方归档的 236 份 NP3 中，当前严格解析接受 228 份 Flexible Color 和 4 份上述单色子集，4 份保留拒绝。其中 Ross A-X 通过结构兼容检查，不代表它也取得 NX 色彩校准。下文 141／142 的统计仅属于固定 Nikon Cloud／Creators 子集。

79 的冻结 APK（SHA-256 `81ce86556a10a828a71412a2d06e86bb260b42266ad42737292bddf7c270a1a0`）已通过 [299 项 Android 原生检查](validation/np3-native/79/monochrome-native/ilford-recovery-20261022T1331Z/monochrome-batch/light/report.json)，包含三款单色的模板／JPEG／强度与各自批处理，以及两图 Scott 37%＋Dreamy Peep 100% 独立设置后的一次批量导出和再次复用。此次实际核对 MediaStore 提交行及读取字节。完整范围、NX 残差和构建身份见 [Scott 验证记录](np3-ilford-validation-20261022.md)。物理手机界面和相册体验仍由测试包验收；79 尚未发布到 beta/latest。

78 的隔离 Android 验证直接安装冻结 APK（SHA-256 `0b1767980b10b467a34464015c15090951401c6d4300c9bb64a3a61fd7ea88e5`）：[渲染检查 151 项](validation/np3-native/78/monochrome-native/dreamy-20261022T051433Z/renderer-only/light/report.json)和[单色批处理检查 184 项](validation/np3-native/78/monochrome-native/dreamy-batch-20261022T051643Z/monochrome-batch/light/report.json)均通过。实际使用 1800×1200 合成 JPEG，覆盖两款 0／37／100% 强度、sRGB／EXIF、取消、每款各自批处理及结果复用、原图关联与独立渲染 JPEG 字节一致。旧批处理探针未独立核对 MediaStore；批处理代码本身会尝试发布，历史报告的 false 不是“未发布”的观测证据，见[更正记录](validation/np3-native/78/monochrome-native/MEDIASTORE-CLARIFICATION.md)。物理手机的系统相册和界面流程留待用户验收。此测试包未发布到 beta/latest。

## 支持范围与效果边界

| 内容 | 手机端处理 |
| --- | --- |
| 32 个基础 TLV 记录、名称、可选 UTF-8 备注 | 校验顺序、长度、版本及值域，读取时复制所需数据 |
| 自定义曲线 | 读取 257 个大端 16 位采样，按输入位置线性插值；保留合法的非单调段 |
| 对比度、高光、阴影、白阶、黑阶、饱和度 | 作为明确标注的应用近似映射参与调色；自定义曲线启用时覆盖前五项 |
| Color Blender | 读取红、橙、黄、绿、青、蓝、紫、洋红八组色相、色度、亮度参数，近似调整对应颜色 |
| Color Grading | 读取高光、中间调、阴影的色相、色度、亮度，以及混合度和平衡，近似执行分区调色 |
| Quick sharp（`0x1400`） | 保留原有中性／未指定行为；新增经 NX Studio 1.11.0 证实的 ±1 编码及精确三项空间参数组合，不重复叠加锐化 |
| 锐化、中等范围锐化、清晰度 | v2 引入、v3 保留的三尺度空间近似，在颜色变换之后执行；支持正向增强与负向柔化，仍有真实对照中未复现的响应 |
| 相机基础色彩、RAW 显影、降噪、镜头校正、白平衡 | 不在当前手机后处理模型的复现范围 |

Flexible Color 产品模型仍为 `np3-0310-encoded-hsl-zones-spatial-v3`，本次单色扩展未改动其数值处理。无自定义曲线时，对比度使用原始已编码 RGB 的共同偏移，而不是 v2 的逐通道线性拉伸：输入归一化到 0–1 后，`Ysrc = .2126 Rsrc + .7152 Gsrc + .0722 Bsrc`，三通道同时加 `-.0008 × contrast × sin(2πYsrc)`。这里的 Y 是已编码显示 RGB 上的近似亮度，不是线性光亮度。高光、阴影、白阶、黑阶仍使用各原始通道的平滑掩模；合并这些项后才统一 clamp、舍入到 8 位，再进入 HSL。`mapTone` 表示该公式的灰阶截面，不能当作独立应用于彩色三通道的同一 LUT。

Color Blender 仍在八个色相锚点之间插值。v3 将其亮度幅度乘以**原始输入像素的 HSL 饱和度 `Ssrc`**：`ΔL = brightness / 200 × Ssrc × (1 − |2L − 1|)`。`Ssrc` 在色调、全局饱和度和 Blender 处理之前求取，无彩色输入权重为 0；它不是修改后的饱和度，也不是单纯的 RGB 最大最小差。该修正避免近灰像素仅因微小色差拥有橙／蓝等色相，就承受完整的色相专属亮度调整。

自定义曲线的 257 个采样、线性插值与量化路径不变，已烘焙进采样的控制点／Levels／Gamma 元数据不再重复应用。但 source-HSL-S 亮度权重也作用于曲线之后的 Blender：固定 corpus 的 47 份曲线配方中，**34 份含非零 Blender brightness**，它们的最终照片效果会随新权重变化，不能把“曲线采样未变”写成“全部曲线配方输出未变”。

全局饱和度、Blender 色相／色度、Color Grading 和空间核未在本轮修改。Grading 仍以已编码亮度上的高斯权重混合三个分区，RGB 偏移预计算为 1025 个采样后线性插值。上述幅度、权重和顺序都是明确的 App 近似；本轮 NX 单控证据支持修正方向，没有公开或恢复尼康的完整渲染算法。

v2 在逐点颜色之后执行 `SpatialPreviewFilter`：使用输入像素半径 1／4／12 的 alpha 加权箱式均值构造细节、中尺度、大尺度亮度频段，带低幅噪声软阈值和局部范围限幅；负值柔化。完全透明像素不污染邻域，输出 alpha 保留原始值。具体频段、阈值、增益在类注释中明确列出，均为 App 近似，并非已恢复的尼康算法。三个空间参数分别按锐化正向／9、负向／3，中范围／5、清晰度／5 归一化。

`renderPixels` 是模板、照片编辑预览和完整尺寸导出的共同入口；原单点 `applyArgb` 只负责颜色，不能代表带空间处理的整张结果。强度在完整颜色与空间处理之后，按原始像素进行整数混合，包含嵌套强度的舍入语义。整图保留一个可变 Bitmap，通过环形行缓存保留邻域，不创建第二幅全尺寸图；6000 像素宽的空间工作缓存约 5.2 MB，另有 64 MiB 硬上限，内存不随照片高度增长。失败或取消的临时输出沿用原有丢弃／不发布规则。

当前半径固定于实际输入像素。缩略图会先缩小，因此共同路径只保证**相同输入尺寸**下处理一致，不代表缩小预览与原尺寸导出的空间尺度严格相同；这也需要后续对照和尺度校准。

对 JPEG 再套用预设，会叠加在相机已经处理过的颜色、白平衡和曲线上；压缩、剪裁的高光或丢失的暗部不能恢复。对于非 sRGB、HDR 或广色域照片，本模型也没有独立的色彩管理或 HDR 显影流程。因此即使所有参数均成功解析，结果也不能视为尼康机内直出或 NX Studio 的等效输出。

相同参数和输入像素的输出具有确定性，alpha 保持不变；输入数组及文件字节不会被修改。缩略图和批量处理共用同一调色模型。模型版本参与缓存标识，以便以后校准算法时刷新结果。

## 照片流程与兼容性

本地导入或在线下载的受支持配方可在详情查看模板效果、按住对比原图，也可在相册的“选择预设”或直播项目默认预设中选择。处理范围沿用现有流程：应用相册中已完成的完整 JPEG 原图；不是 RAW 显影或任意系统图库导入。效果按完整尺寸转为 8 位 sRGB JPEG 95 另存，内存不足时明确失败，不静默缩小原图。

旧 NCP 保持原模型版本和输出键；NP3 按 Flexible Color 或单色模型使用独立键，保存预设 SHA、原图 SHA 和模型身份对应的原图关联。v3 不把 v1／v2 的旧效果复用成新模型结果，已有完成结果及原图关联保留。若输出和完成记录已提交但关联尚未保存，恢复仅尝试已登记的旧 NCP、旧 NP3 v1／v2、当前 NP3 两种模型及已写入的精确模型记录，以完整身份核对原图；不根据相邻文件或画面猜测。重新套用会追溯原图，不对旧效果图叠加同一配方。

历史 0.11.0-beta / versionCode 57 在隔离 Android 17 模拟器上通过浅色、深色各 60 项原生检查，覆盖真实 NP3、本地模板、1800×1200 JPEG 编解码与 EXIF、效果复用、原图关联、真实 MediaStore 提交及输出字节一致。报告见 [浅色](validation/np3-native/light/report.json)与 [深色](validation/np3-native/dark/report.json)，仅属于当时模型，不能作为 v3 原生验收。下述 24MP 主机对照也不证明物理手机性能或 24MP 长时间批处理；现有相机导入流程不因手机可渲染而新增 NP3 写入能力。

## 真实文件验证

独立 runner：

```powershell
.\tests\run-np3.ps1
# 如需指定 JDK：
.\tests\run-np3.ps1 -JdkHome 'D:\dev\Java\jdk-17.0.19'
```

runner 先编译并运行独立 JVM 合成测试。如果本地已有 `dist/nikon-cloud-recipes-20261018/nikon-cloud-recipes-142.zip`，还会直接只读验证其中全部 142 份 NP3；不下载、不解压、不复制第三方预设进源码目录。缺少 ZIP 时明确显示 corpus 被跳过，合成测试仍运行。可用 `-CorpusZip` 指定相同已知样本包的位置。

样本包身份由 `cloud-only-inventory.json` 及 ZIP 的 SHA-256 固定为 `7a1cf3cd719ed9ec69dae4936625afb1fc3cc1871bc6d61f46c27903a32a087c`。其中 95 份只有基础记录、47 份附带自定义曲线。当前定向兼容的预期为 **141 份可调色、1 份明确拒绝**；全部拒绝、八份已验证配方中的任何新增拒绝，或 Creative 被误接受，均会使测试失败。此前的 133／9 是补齐 Quick sharp 前的历史结果，旧报告保持原归属。

可调色文件逐份检查 4096 个覆盖 RGB 空间与全部 alpha 值的像素：必须产生可见变化、保留 alpha、重复输出一致、不修改输入、不共享返回数组或调用方文件数据，并检查曲线结果范围。空间参数为零时再检查逐点颜色与图像渲染相同；非零空间处理另由独立频段／边界／噪声／透明像素／环形缓存测试验证。测试还要求至少 100 种不同输出；补齐 Quick sharp 后的 v2 历史运行得到 135 种输出，当前版本以 runner 实际记录为准。这些检查证明输入输出与覆盖范围，不证明尼康色彩还原精度。

固定 142 份子集中唯一明确拒绝的样本为 `Nikon Creators/Bluegrain_jyota.NP3`：它使用 `0x0802` Creative 基底，还有传统调整及创意强度参数。当前模型尚未实现这个 Creative 基底的基础效果，不能通过只移除基底检查来支持它。

### Quick sharp 的受限扩展

2026-10-21 在真实 NX Studio 1.11.0 中，先从同一 Flexible Color 基准只调 Quick sharp，再导出 NP3；另手动设置完全相同的三个空间数值，使 Quick sharp 状态为未指定。±1 两档均有独立 16 位 TIFF 主图像素审计，已解码的完整主图像素逐字节相同。该对照只支持一个结论：在已验证组合中，`0x1400` 记录联动状态，空间效果已经包含在存储的三项数值内；不应再次乘 Quick sharp 增益。具体来源和独立报告见[兼容性研究](np3-compatibility-research-20261021.md#nx-studio-1110-的本地实证与定向兼容)。

| `0x1400` payload | 本轮接受条件（原始 payload） | 处理 |
| --- | --- | --- |
| `ff01`、`8001` | 保留此前参数校验行为 | 按独立存储的三个空间数值处理 |
| `7f01`（Quick sharp -1） | field6／field7／field22 必须全部为 `8004` | 三项空间值均为 0，不另加 Quick 效果 |
| `8101`（Quick sharp +1） | field6=`8e04`、field7=`8604`、field22=`8c04` | 锐化 3.5／清晰度 1.5／中范围锐化 3，只执行一次 |
| 其它值、错误类型或不匹配组合 | 本轮未被证实 | 明确拒绝，不推算其它档位 |

这次新增支持的八份原始配方如下；它们的编码及 tuple 与上述实证相符。原文件未被改写。

| 文件（均位于 Nikon Creators） | 已验证的状态 |
| --- | --- |
| `24Look_24Frames.NP3` | `7f01`，三项空间数值均为 0 |
| `BlueHour_Luna.NP3` | `8101`，精确匹配 +1 tuple |
| `GoldenHourGlow_DAnn.NP3` | `8101`，精确匹配 +1 tuple |
| `HKReminisce_Hiuman.NP3` | `8101`，精确匹配 +1 tuple |
| `PainterlyLight_DAnn.NP3` | `8101`，精确匹配 +1 tuple |
| `Pastelpink_Yuki.NP3` | `8101`，精确匹配 +1 tuple |
| `SunsetFilm_JKBoy.NP3` | `8101`，精确匹配 +1 tuple |
| `WarmDimLight_Luna.NP3` | `8101`，精确匹配 +1 tuple |

主机回归还检查了定向组合与手动等值组合的整图输出完全相同，任意一项 tuple 字节 ±1、未知状态或错误类型仍被拒绝。**141／142 是当前样本的解析与近似渲染覆盖，不代表所有 NP3 格式都支持，也不代表手机输出已通过 NX Studio 全色彩校准。**

### v3 的真实像素诊断

本轮使用 NX Studio 1.11.0.3000 与用户提供的两份 NEF，在同一室内环境采集单控、完整 `SunsetFilm_JKBoy`（u7）及三项空间归零的 `QSunColor`。每份 RAW 都有自己的零调整 Flexible A；6184 没有复用 6182 的图像，也未用于修改已冻结候选。它是另一份原始照片的固定预测验证，不是独立光照／场景覆盖。

NX 原件为 6048×4024 TIFF16；显式 ICC16 转换后按 `(u16+128)//257` 生成诊断 sRGB8。候选和旧 v2 均完整渲染 **24,337,152 像素**，再在原尺寸的 **61,206 个确定性网格点**测量，未用缩略图计算误差。冻结候选源码 SHA 为 `9c16448dd17c3f1878bd6023ae345e406a06efd7072ce5a9dfc8b999c241055c`；产品 v3 的数值实现与之相同，差别是 Javadoc 和模型身份键。[独立产品复核](../.research/np3-calibration/nx-1.11.0-DSC6182/diagnostic/product-v3-review-run1/review-report.json)还验证了完整 6184／u7 的全部 RGB 像素与候选相同。表格与来源见[研究记录](np3-compatibility-research-20261021.md#v3-色彩修正与两份真实-raw-的固定诊断)。

完整 u7 的 RGB MAE（0–255）在 6182 上为 **21.362→5.171**，在 6184 上为 **19.697→4.792**（旧 v2→固定候选）。但 6184 的颜色隔离 QSunColor 仍为 **26.682→10.433**；完整 u7 更接近 NX 可能包含误差抵消，不能把所有残差归给空间处理。该空间组合的 NX 响应与 App 局部近似明显不同，具体涉及哪项锐化／清晰度、哪种交互，仍待单项实验。

这些结果支持两项近似修正，没有完成全色彩校准，也没有官方合格阈值或任意 JPEG 等效性结论。诊断 PNG 不是 NX 官方 8 位导出；私有原图不嵌入文档、不进入 App 或公开分发。

## 格式依据与后续校准

参数位置、编码和可选曲线结构参考 [ssssota 的 NP3 读写源码](https://github.com/ssssota/nikon-flexible-color-picture-control/tree/main/src)，其工作是读取与写入格式，并没有提供可直接复刻的尼康像素渲染器。独立项目的[格式研究说明](https://github.com/noobsaibot666/nikon_cps_generator-converter/blob/main/docs/NP3_FORMAT.md)同样列出尚未解释的字段；当前实现不会忽略它们的未知非中性值。

功能含义参考 [Nikon NX Studio Flexible Color 帮助](https://nikonimglib.com/nxstdo/onlinehelp/en/the_picture_controls_flexible_color_23.html)。该帮助说明了曲线与基础色调参数的覆盖关系、Color Blender 的八组颜色，以及 Color Grading 的分区、混合与平衡；它不是公开的渲染算法规范。

后续需在已完成的两份室内 RAW 基础上扩大场景和光照，固定白平衡、色彩空间、曝光及导出选项，分离三个空间参数、其它色调项和 Grading，再验证曲线／Blender 组合及未见配方。校准完成前，界面与导出说明应持续使用“近似调色”，不宣称与官方处理一致。

2026-10-21 已加入[基准采集与评估工具](np3-calibration.md)，并完成[剩余兼容性研究](np3-compatibility-research-20261021.md)。初期只有统计及外部线索时保留 133／142；取得本地 NX 单变量和 ±1 匹配参数的 16 位主像素对照后，才按精确组合扩展到 141／142。Creative 基底仍单独保留为未支持。

本地 77／0.13.9-beta 的分阶段主机与 Android 渲染验证见[本轮记录](validation/np3-native/77/README.md)。初始 42 项 Android 证据只属于补齐 Quick sharp 前的候选包；后续最终包及扩展兼容的原生结果须按其实际哈希另行记录。此处不是全色彩校准通过或发布完成声明。
