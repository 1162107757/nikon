# NP3 剩余兼容性研究（2026-10-21）

当前结论：取得 NX Studio 1.11.0.3000 的真实单变量 NP3 及 ±1 匹配参数的完整 16 位主像素对照后，产品按精确组合扩展为 **141/142 份手机近似渲染、1 份 Creative 明确拒绝**。`0x1400` 的 Quick sharp ±1 在已验证 tuple 中不额外增加空间效果，不能重复施加锐化，也不能据此开放其它档位或冲突组合。Bluegrain 涉及 Creative 基底及多个传统调整参数，仍不能只删除基底检查。解析覆盖、手机近似效果、与 Nikon 渲染一致是三件不同的事。

随后使用两份同室内环境的独立 NEF 完成单控与组合的固定预测诊断，产品色彩模型更新为 `np3-0310-encoded-hsl-zones-spatial-v3`：无曲线对比度改为编码亮度上的共同 sine RGB 偏移，Blender brightness 增加原始输入 HSL 饱和度权重。解析范围没有因此扩大；下方保留旧 v2 与冻结候选的实际误差及尚未复现的响应，**本轮不是完整色彩校准通过**。

时间顺序：初期只读取真实 corpus、公开文档和公开源码，证据不足时保留 133／9；下方初期统计及外部报告按原范围保留。后续根任务通过 NX GUI 采集本地文件，独立审计程序核对 NP3 记录和 TIFF 原始主像素，再加入定向兼容规则。同期新增的三尺度空间近似是 App 自定义模型；Quick sharp 联动实证不构成 App 全色彩或空间算法校准通过的证明。

## 证据等级

| 等级 | 本文含义 | 可以证明什么 |
| --- | --- | --- |
| A：可直接复核 | 本地原始字节及哈希；Nikon 官方帮助的明确描述 | 文件里有什么；官方功能联动与适用限制 |
| B：可追溯外部报告 | 原作者公开提交、代码和实验说明 | 有价值的语义线索，仍需在本地独立复现 |
| C：相关性推断 | 多参数配方中的统计分组 | 选择下一项实验，不能直接放开未知参数 |
| A：本轮新增实证 | 同一 NX 版本／原片的单变量导出；独立核对原始 NP3 与完整无压缩 16 位 TIFF 主像素 | 已验证 ±1 的精确组合可按所存三项空间值处理；不能推广至未见档位、Creative、其它版本或 App 与 NX 的像素等价 |

## 142 份真实文件

输入为 `dist/nikon-cloud-recipes-20261018/nikon-cloud-recipes-142.zip`，SHA-256：

`7a1cf3cd719ed9ec69dae4936625afb1fc3cc1871bc6d61f46c27903a32a087c`

只读扫描忽略名称和备注后的记录差异。`field20` 是 TLV ID `0x00001400`；该 corpus 中两字节 payload 位于十进制偏移 222（`0xDE`）。这里的偏移不是旧 NCP 的固定字段布局。

| 基底（record 3） | field20 payload | 数量 | 初期行为（NX 实证前） |
| --- | --- | ---: | --- |
| `0020` Flexible Color | `8001` | 38 | 近似渲染 |
| `0020` Flexible Color | `ff01` | 95 | 近似渲染 |
| `0020` Flexible Color | `7f01` | 1 | 明确拒绝 |
| `0020` Flexible Color | `8101` | 7 | 明确拒绝 |
| `0802` Creative 类基底 | `ff01` | 1 | 明确拒绝 |

八份非中性 `0x1400` 文件为 `24Look_24Frames`、`BlueHour_Luna`、`GoldenHourGlow_DAnn`、`HKReminisce_Hiuman`、`PainterlyLight_DAnn`、`Pastelpink_Yuki`、`SunsetFilm_JKBoy`、`WarmDimLight_Luna`，均在 ZIP 的 `Nikon Creators/` 内。前者为 `7f01`，其余为 `8101`。与中性值文件比较，即使排除名称、备注及 field20，最接近的配方仍有 5–9 条其它记录不同；这批文件没有可用于直接定性的单变量对照。

临时统计和逐文件哈希保存在被忽略的 `.tooling/np3-compatibility-research-20261021/corpus-analysis.json`。此处保留关键统计，避免让结论依赖本地临时文件。

### 三档锐度 tuple

| field20 | 数量 | `(field6, field7, field22)` 原始值 | 按已知编码读取的（锐化，清晰度，中范围锐化） |
| --- | ---: | --- | --- |
| `7f01` | 1 | `(8004,8004,8004)` | `(0,0,0)` |
| `8001` | 38 | `(8804,8204,8404)`，全部相同 | `(2,0.5,1)` |
| `8101` | 7 | `(8e04,8604,8c04)`，全部相同 | `(3.5,1.5,3)` |
| `ff01` | 96（含 Bluegrain） | 多种人工调整组合 | 不呈现单一 tuple |

只看 corpus 时，这是 **C 级相关性**。官方说明 Quick sharp 同时调节锐化、中范围锐化和清晰度；这与三档 tuple 的整体移动相符。官方没有在该帮助中公开 TLV ID、编码或像素算法。[Nikon：Flexible Color](https://nikonimglib.com/nxstdo/onlinehelp/en/the_picture_controls_flexible_color_23.html)

因此初期优先实验 Quick sharp，没有根据 `0x80±1` 猜成全局色相。是否只是记录界面联动状态、是否另有空间增益，以及手动改动三项后的状态转换，随后由下述本地实证补足。

## NX Studio 1.11.0 的本地实证与定向兼容

采集使用 NX Studio **1.11.0.3000**、同一 NEF、Flexible Color、相同其它图像设置和原尺寸 sRGB TIFF16 导出。原片 SHA-256 为 `142656e30e8735fa29684a3b46145eba0a7b219a947ce6bf45d68a387ceb72e5`。根任务实际操作 GUI；独立审计核对已保存的文件，未独立目击 GUI，因此其它设置一致性仍以采集记录为来源。

先从同一基准只改 Quick sharp，保存 `n0`（0）、`m`（-1）、`p`（+1）。`0x1400` 分别是 `8001`／`7f01`／`8101`，空间 tuple 分别为 `(2,0.5,1)`／`(0,0,0)`／`(3.5,1.5,3)`，顺序为锐化／清晰度／中范围锐化。再手动设置相同三项空间值，得到未指定 Quick 状态的 `t`（匹配 +1）和 `z`（匹配 -1）；对应 NP3 除名称和 `0x1400` 外，记录顺序、长度及其余参数均相同。

独立 TIFF 解析器直接读取无压缩、交错 RGB、每通道 16 位的完整主图 strip，没有经 Pillow 降到 8 位，也没有仅比较预览或采样区域。两组的主图均为 **6048×4024**、24,337,152 像素、73,011,456 个 16 位通道、**146,022,912 个主像素字节**；同组 ICC 字节也完全相同。外层 TIFF 文件摘要可因元数据不同而变化，判定依据是解析后的完整主像素。

| 已验证组合 | 对照文件 | 完整 16 位主像素结果 | 主像素 SHA-256 |
| --- | --- | --- | --- |
| Quick +1 与手动锐化 3.5／清晰度 1.5／中范围 3 | `p16a/b.tif` 与 `t16a/b.tif`，含各自重复导出 | 四份主像素逐字节相同 | `0fefe224515fc9a65c7b4d58fda562c5d0ec5d589ab37ab18c965d2a6743d22c` |
| Quick -1 与手动三项全 0 | `m16a.tif` 与 `z16a.tif` | 两份主像素逐字节相同 | `751ddef5c0f82037a0b0293fa158289d0eec468aefc6089e6ceba18c38bce0d9` |

原始独立报告：[+1 完整 16 位对照](../.research/np3-calibration/nx-1.11.0-DSC6182/evidence/independent-quicksharp-matched-plus16-20261021.json)、[-1 完整 16 位对照](../.research/np3-calibration/nx-1.11.0-DSC6182/evidence/independent-quicksharp-matched-minus16-20261021.json)。真实原片、图像和 GUI 操作证据保留在被忽略的本地研究目录，不进入 App 或对外上传。

这证明本次样本中的 Quick sharp 联动状态没有在存储的三项数值之外再施加一份效果。产品因此只新增以下定向规则：`7f01` 必须精确匹配 `(8004,8004,8004)`；`8101` 必须精确匹配 `(8e04,8604,8c04)`。原有 `ff01`／`8001` 行为保持。未知值、错误类型和任一不匹配 tuple 仍明确拒绝，Quick 本身不加入额外空间增益。

八份原始 Flexible Color 配方均满足上述精确规则。补齐 Quick sharp 时的历史 v2 主机回归为 19,401 项合成检查，以及固定 142 份 corpus 中 **141 支持／1 拒绝、135 种不同输出、648,268 项检查**；同时覆盖与手动等值组合的整图输出一致、tuple 字节 ±1 拒绝、未知状态拒绝和源字节保留。这些旧计数与当时源版本绑定；v3 的构建和原生验证由统一验收记录另行绑定，不能沿用旧候选包哈希。

**限制：全色彩校准尚未通过。** 上述 PASS 是两组 NX 自身的匹配参数实验，不能证明 App 自定义三尺度滤波或颜色公式与 NX 相同；也不覆盖其它 Quick 档位、Creative、所有版本、更多原片或 JPEG 后处理的官方等价性。

### Bluegrain 的独立缺口

`Nikon Creators/Bluegrain_jyota.NP3` 的 SHA-256 为：

`1cea7a90bfa80fdd3d68b7e6dbe371a5993447a12be85026eca4251f0a273999`

关键记录：基底 `0802`；field6/7/22 为 `8b04/8304/8204`；传统调整 field8/10/11 为 `8004/8c04/7404`；field20 为 `ff01`；field21 为 `850a`；field25–30 均为 `8001`。Flexible Color 路径要求相关传统字段为未启用标记、Creative 强度为未启用标记，所以它同时违反多项条件。

官方帮助确认 Creative Picture Control 有效果强度且可调参数随基底变化，并说明 Creative 下不能使用自定义曲线；这没有给出 `0802` 对应的具体名称或基底变换。[Nikon：Picture Controls](https://nikonimglib.com/nxstdo/onlinehelp/en/the_picture_controls_tool_22.html)

当前没有可追溯的 `0802` 单变量导出或基底参考图。不应猜测其名称、套用 Flexible Color 公式，或把“Bluegrain”名称当成颗粒格式证据。

## v3 色彩修正与两份真实 RAW 的固定诊断

### 本轮机制与不变部分

无自定义曲线时，v2 的逐通道 `2^(contrast/100)` 拉伸被替换为共同 RGB 偏移。原始已编码 RGB 归一化到 0–1 后：

```text
Ysrc = .2126 Rsrc + .7152 Gsrc + .0722 Bsrc
contrastOffset = -.0008 × contrast × sin(2πYsrc)
RGB_i = channel(originalNonContrastBasic(original_i) + contrastOffset) / 255
```

`originalNonContrastBasic` 保留原有高光、阴影、白阶、黑阶项，读取各原始通道；合并后才 clamp、量化到 8 位。三通道共享同一偏移，不能分别对 R/G/B 的输入套 sine；`mapTone` 只是该模型的灰阶截面。Y 是编码域近似亮度，不是线性光亮度，也不是从尼康算法规范中恢复的公式。

Blender brightness 的原幅度再乘原始输入 HSL 饱和度 `Ssrc`。`Ssrc = Δsrc / (1 − |2Lsrc − 1|)`，无彩色输入为 0；它在色调、全局 saturation 和 Blender 改动前取得，不能用处理后的 S 或纯 RGB delta 代替。亮度项成为 `brightness / 200 × Ssrc × (1 − |2L − 1|)`。色相锚点／色相幅度、Blender 色度、其它基础掩模、Grading 和三尺度空间核均未在这次改动中调整。

自定义曲线的 257 点采样读取、插值和量化保持原行为；已烘焙的控制点／Levels／Gamma 不重复执行。但新的 brightness 权重同样作用于曲线路径。只读复核固定 ZIP 的 47 份曲线配方，34 份包含非零 Blender brightness，因此这些配方的最终颜色会受 v3 影响；保留采样并不代表所有曲线配方的最终输出不变。

机制选择来自 6182 单控比较，随后冻结候选源码 SHA **`9c16448dd17c3f1878bd6023ae345e406a06efd7072ce5a9dfc8b999c241055c`**，再做外档 +25／+100 及 6184 固定预测，没有据其结果修改系数、权重或 LUT。[候选锁与原始来源](../.research/np3-calibration/nx-1.11.0-DSC6182/diagnostic/brightness-weight-private-run1/candidate-lock.json)保留选择范围和时间。产品 v3 与该锁定候选的数值实现一致；去除注释及模型键后源码相同。[独立产品复核](../.research/np3-calibration/nx-1.11.0-DSC6182/diagnostic/product-v3-review-run1/review-report.json)另将 6184／u7 的 24,337,152 个解码 RGB 像素逐一比较，产品 v3 与冻结候选完全一致。下表仍引用各自原始冻结诊断记录，不冒充当前 APK 的原生验收。

### 原片、来源和测量边界

| 原片 | 大小 | SHA-256 | 本轮用途 |
| --- | ---: | --- | --- |
| `DSC_6182.NEF` | 28,315,223 B | `142656e30e8735fa29684a3b46145eba0a7b219a947ce6bf45d68a387ceb72e5` | 单控机制比较、候选选择及外档固定验证 |
| `DSC_6184.NEF` | 28,247,465 B | `477c76ee6655921a17e2988560e28212ce7e5734c63cd89c2da1126a64c2df5d` | 锁定后独立 RAW 的固定预测，未参与拟合 |

两份照片来自同一室内环境；第二份是不同原始文件，不是独立光照或独立场景覆盖。两份均使用 NX Studio 1.11.0.3000 的 Latest Picture Control，各自采集零调整 Flexible A，所有基础色调、三项空间值归零且曲线关闭。6184 的 A 是独立导出的 `V6184-z-16a.tif`，没有借用 6182 的图像。

实际导出由 Codex computer-use 操作官方 GUI，来源标为 `nx_studio_ui`；派生单控 NP3 仅是输入刺激，不称作官方 NP3 导出。其它工作设置一致性来自操作记录；6184 的五份实际工作快照中，39 个已解析 filter 只有 PictureControl 改变，其余 38 个原值及不透明数据 SHA 相同。[GUI 追加日志](../.research/np3-calibration/nx-1.11.0-DSC6182/provenance/gui-capture-log.jsonl)与[五快照核对](../.research/np3-calibration/nx-1.11.0-DSC6182/provenance/DSC6184-five-observed-snapshots-invariants.json)保留此证据。它是观察态一致性，不证明每项导出内部处理均已被解码。

原件为无压缩、原尺寸、内嵌 sRGB ICC 的 **6048×4024 TIFF16**。直接读取 uint16 strip，ICC 16→16 转换后按 `(u16+128)//257` 明确舍入成诊断 sRGB8，保留原件／目标／ICC SHA；不让 Pillow 隐式截掉低字节。诊断 PNG 不是 NX 官方 8 位导出。候选与冻结旧 v2 均执行完整 `profile.renderPixels`，包括有空间值时的空间处理，完整渲染 **24,337,152 像素**，没有缩图或用单点替代整图。

以下误差在原尺寸图的 **61,206 个确定性网格点（步长 20×20）**计算，未声称穷举全部像素。RGB MAE 单位为 0–255；新增剪裁以 NX B 为参考，表示至少一个通道在预测 C 中新到达 0／255 端点的采样像素比例。每格均为 **旧 v2 → 固定候选**；本轮没有预设或事后补造合格阈值。

| 原片／配方 | RGB MAE | ΔE00 中位 | ΔE00 P95 | 新增剪裁比例 |
| --- | ---: | ---: | ---: | ---: |
| [6182 · 对比度 +25](../.research/np3-calibration/nx-1.11.0-DSC6182/diagnostic/fixed-candidate-validation-run1/DSC6182-QConP25/report.json) | 5.921 → 0.346 | 1.794 → 0.385 | 4.038 → 0.957 | 8.725% → 0.000% |
| [6182 · 对比度 +100](../.research/np3-calibration/nx-1.11.0-DSC6182/diagnostic/fixed-candidate-validation-run1/DSC6182-QConP100/report.json) | 17.672 → 1.107 | 5.789 → 0.546 | 12.245 → 1.143 | 44.081% → 0.096% |
| [6182 · 完整 u7](../.research/np3-calibration/nx-1.11.0-DSC6182/diagnostic/brightness-weight-private-run1/sat-sine/u7-report.json) | 21.362 → 5.171 | 6.251 → 1.788 | 19.658 → 3.964 | 19.575% → 0.054% |
| [6184 · 对比度 +50](../.research/np3-calibration/nx-1.11.0-DSC6182/diagnostic/fixed-candidate-validation-run1/DSC6184-QConP50/report.json) | 7.082 → 0.433 | 3.082 → 0.371 | 6.086 → 0.876 | 12.504% → 0.087% |
| [6184 · 橙 brightness +20](../.research/np3-calibration/nx-1.11.0-DSC6182/diagnostic/fixed-candidate-validation-run1/DSC6184-QOraP20/report.json) | 12.392 → 0.599 | 4.843 → 0.344 | 7.205 → 0.991 | 0.000% → 0.000% |
| [6184 · 完整 u7](../.research/np3-calibration/nx-1.11.0-DSC6182/diagnostic/fixed-candidate-validation-run1/DSC6184-u7/report.json) | 19.697 → 4.792 | 6.379 → 2.241 | 9.292 → 4.752 | 1.145% → 0.057% |
| [6184 · QSunColor 色彩隔离](../.research/np3-calibration/nx-1.11.0-DSC6182/diagnostic/fixed-candidate-validation-run1/DSC6184-QSunColor/report.json) | 26.682 → 10.433 | 9.007 → 4.077 | 11.240 → 4.998 | 0.765% → 0.047% |

6182 完整 u7 的旧值另见[原 v2 对照](../.research/np3-calibration/nx-1.11.0-DSC6182/diagnostic/u7-comparison/report.json)；其固定候选来自上表同一锁定源码与 inventory。其它早期机制探索中有只在原尺寸网格点调用 `applyArgb` 的数据，它们不混称为上表的完整 24MP 渲染。

### QSunColor 异常与未解决部分

6184 的 QSunColor 残差仍明显：候选 MAE 10.433、ΔE00 中位 4.077，反而大于完整 u7 的 4.792／2.241。两配方除名称外只差三项空间值与 Quick 状态（`0x0600/0700/1400/1600`），使用同一个 A。[固定空间分离报告](../.research/np3-calibration/nx-1.11.0-DSC6182/diagnostic/fixed-candidate-validation-run1/DSC6184-spatial-separation.json)进一步量化“完整 u7 − 空间归零”的差异：

| 比较对象 | 两种输出的 RGB MAE | 平均编码 Y 变化（0–255） | `\|ΔY\| > 2` 的采样点比例 |
| --- | ---: | ---: | ---: |
| NX 两份真实导出 | 8.545 | +7.839 | 85.72% |
| 固定候选两份预测 | 0.219 | −0.002 | 2.23% |

这说明该组合的真实整体响应没有被当前局部空间近似复现。尚不能区分锐化、中范围锐化、清晰度哪项或其交互造成差异，也不能把全部 u7 残差归为空间错误；完整 u7 的较小误差可能包含颜色与空间误差抵消。需要分别改变三项空间值、保留其它条件并重复导出，继续审查顺序与组合响应；本轮没有据此改动空间参数或候选。

工具本身也做了[像素读写审计](../.research/np3-calibration/nx-1.11.0-DSC6182/diagnostic/bridge-readwrite-audit-run1/report.json)：6182 A 的 73,011,456 个 RGB8 通道经 ICC 标准化后改动 0；QSunColor 的 61,206 点上，Java getRGB/setRGB 与完整渲染相对同一生产点变换的误差为 0。它核对的是桥忠实执行产品路径，不能证明产品的量化顺序等同尼康内部流程。

这些数据支持本轮对比度和近灰 Blender brightness 修正，仍保留组合与空间残差。两份同室内 RAW、部分控件与单一复合配方不足以覆盖其它场景、曲线／Grading、相机或已处理 JPEG；没有完整校准 PASS、官方阈值或通用 NX 等价性结论。真实图像仅留本地研究目录，本文不嵌入或公开分发私人图片。

## 公开源码与历史复核

### ssssota：中性模板，不是 field20 的语义定义

核对到提交 `ed0397fbf1e4d4f2bc11ab3070522c613667d359`。`base.ts` 在 `0xDE` 固定写入 `80 01`；`offset.ts`、`types.ts`、`deserialize.ts` 没有 field20 的命名控制或读写项。公开历史主要补充备注和曲线。其代码是已知参数的重要结构依据，但不能据此声称未知字段已支持。

来源：[base.ts](https://github.com/ssssota/nikon-flexible-color-picture-control/blob/ed0397fbf1e4d4f2bc11ab3070522c613667d359/src/base.ts)、[offset.ts](https://github.com/ssssota/nikon-flexible-color-picture-control/blob/ed0397fbf1e4d4f2bc11ab3070522c613667d359/src/offset.ts)、[deserialize.ts](https://github.com/ssssota/nikon-flexible-color-picture-control/blob/ed0397fbf1e4d4f2bc11ab3070522c613667d359/src/deserialize.ts)。

### justinthevoid/recipe：QuickSharp 外部实验报告

提交 `e3486bdec95913db4ed642b88e3eb7740e51a5dc`（2026-04-20）记录了对已知可用 NP3 的排查：此前把 `0xDE` 当作 GrainSize 写入 `01`，Cloud 界面显示 QuickSharp 为 `-127`，并阻止相机同步；改回 `80` 后通过其 Cloud 往返流程。当前生成器也明确注释 field20 的旧颗粒解释有冲突，并固定为 `8001`。[原作者修复提交](https://github.com/justinthevoid/recipe/commit/e3486bdec95913db4ed642b88e3eb7740e51a5dc)、[固定版本 generate.go](https://github.com/justinthevoid/recipe/blob/cafcdc1531a4bf71093eac6bcb08cde42669e884/internal/formats/np3/generate.go)

这是 **B 级外部 Cloud 实验报告**，不是本地复现，也不是 NX Studio 导出证明。该仓库的 `offsets.go` 仍保留 GrainAmount/GrainSize 旧名称，颗粒写入测试已因 TLV 冲突跳过；不能摘取旧常量名作为格式定论。Cloud 接受文件也不能证明渲染颜色一致。

本轮还只读查看了 Nikon Imaging Cloud 匿名页面公开的 JS 文本（未执行脚本、未登录、未调用写接口）。它把 `quickSharp`、`sharpening`、`middleRangeSharp`、`clarity` 与 `hue` 分开显示，但未在已查看片段中找到本地 NP3 二进制解码器，所以这只能佐证界面名称，不能补足字节映射。公开资源：[参数展示 chunk](https://imagingcloud.nikon.com/_next/static/chunks/6725-e9d0568c7aaa0d12.js)。资源快照仅留在被忽略的研究目录。

### 其它项目没有补足单变量证据

`noobsaibot666/nikon_cps_generator-converter` 的格式文档仍列出包含 field20 在内的未确认记录，已验证工作集中在已知字段和曲线；`krafta-visio` 的读写也沿用已有参数映射，没有提供 field20 的命名实验。旧 NCP 标量布局和 MakerNote 的 PictureControl 记录不是这个 NP3 TLV 容器，不能平移字段含义。

来源：[NP3_FORMAT.md（固定文档提交）](https://github.com/noobsaibot666/nikon_cps_generator-converter/blob/b37cca7454eedd73aee04d732d07d44a58e77264/docs/NP3_FORMAT.md)、[krafta-visio module.js](https://github.com/krafta-visio/nikon-flexible-color-creator/blob/7875fed9d594403ab40ed2056460cf2b1e7b5b0f/module.js)、[旧 NCP 解析器](https://github.com/simeonpilgrim/nikon-firmware-tools/blob/master/nfiles/dumpncp.pl)。

官方 NX Studio 将 Film Grain 作为独立调整工具，并说明相同参数的随机图案也会变化。这个 UI 功能本身没有证明颗粒存储在 NP3 field8 或 field20。[Nikon：Film Grain](https://nikonimglib.com/nxstdo/onlinehelp/en/the_film_grain_tool_37.html)

## 后续验证顺序

1. **保留已证实语义**：±1 的单变量与 matched tuple 证据已完成。继续将 NX 版本、基底、所有设置、文件 SHA、GUI 步骤和完整主像素对照绑定；扩大其它档位前先重新取得同等证据，不能用现有 ±1 推算全范围。
2. **保持定向兼容**：八份配方按精确编码与 tuple 接纳；继续回归未知值、冲突 tuple 和边界值，不忽略其它未证实字段。最终安装包及原生 probe 仍需重新绑定真实产物后验收。
3. **单独研究 Bluegrain**：在相同 NX 版本确认 `0802` 的基底名称，导出默认基底及效果强度、传统参数的单变量样本。先估计可见效果和缺口，再决定实现范围；能读出参数不等于掌握 Creative 基底变换。
4. **扩大像素基准**：在本轮两份同室内 RAW 的诊断之外，增加独立场景／光照和未见配方，固定曝光/WB/色彩空间/降噪/镜头修正/输出尺寸；三项空间值须各自单变量并重复导出。继续分离曲线、Grading 与组合响应，预先定义内部验收指标。JPEG 已过原机渲染，手机后处理无法逆转其 RAW 流水线，不能把当前固定预测改善写成任意 JPEG 的官方等价性。

不能承诺任意 NP3 全覆盖、Nikon 私有 Creative 基底完全复现或逐像素一致；这些目标需要进一步证据，部分还可能受专有 RAW/色彩算法限制。手机端支持不会改变旧机身固件的 NP3 导入能力；官方相机适用性限制依然独立存在。[Nikon：Flexible Color 的相机要求](https://nikonimglib.com/nxstdo/onlinehelp/en/the_picture_controls_flexible_color_23.html)

## 早期社区 RAW 候选：下载未完成

这是本地原片到位前准备的备选来源，以下下载失败记录保留原范围。后续 Quick sharp 的真实 NX 对照使用用户授权的本地 NEF，不依赖这个尚未取得的社区样本。

优先候选为 raw.pixls.us 的 Nikon Z 9 lossless NEF。原始上传者明确称三份 Z9 文件拍摄了 X-Rite ColorChecker Passport、除改名外未经修改。[原始上传者说明](https://github.com/darktable-org/darktable/issues/10889)

独立 `kuolas/raw-files` 项目的逐条样本清单给出以下来源、CC0 标记和预期 SHA（这是第三方清单证据，尚未代替主站逐条许可核对）：[samples.tsv](https://github.com/kuolas/raw-files/blob/master/tests/samples.tsv)。

| 项目 | 候选信息 |
| --- | --- |
| 记录号 / 机型 | raw.pixls.us `5146` / Nikon Z 9 |
| 源文件直链 | [raw.pixls.us 5146 NEF](https://raw.pixls.us/getfile.php/5146/nice/Nikon%20-%20Z%209%20-%208bit%208bit%20compressed%20%283%3A2%29.NEF) |
| 第三方清单许可 | CC0；主站当前逐条许可尚未读取成功 |
| 第三方预期 SHA-256 | `99d0fe24a38f0f7623aeab21440d266a5ac09f9665cf5283f590337e84323c38` |
| 实际原始大小 / SHA / EXIF | 未取得文件，未核验 |
| 目标目录 | `.tooling/np3-calibration/vendor-source/`，被忽略，不进入产品 |

2026-10-21 在本机实际尝试主站首页、`/json/getrepository.php?set=all` 和上述已观察直链，PowerShell、curl/Schannel、Python/OpenSSL 均出现 TLS 连接失败或 unexpected EOF；网页工具也无法打开直链。未下载到 NEF，没有绕过 TLS、切换镜像或修改文件。网络恢复后先核对主站记录及 CC0，再下载并核验字节数、SHA、EXIF 和 ColorChecker 内容；当前不得标记为已有校准原片。

Nikon USA 本轮找到的官方 RAW 下载页为 N-RAW/R3D 视频样片，不能当成上述静态 NEF 校准源：[官方视频 RAW 下载](https://www.nikonusa.com/content/Zcinema-raw-file-downloads)。
