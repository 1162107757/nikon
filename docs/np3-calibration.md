# NX Studio 基准与 NP3 差分校准

这套工具把真实 NX Studio 导出、配方字节和当前 Huevia 模型结果关联起来。工具本身不包含可公开分发的用户 RAW/NX 成片；生成清单或运行合成测试不会产生官方校准证据，也不会扩大 NP3 兼容范围。

初始结构审计识别了 8 份非中性 `0x1400` 配方和 1 份 `0x0802` Creative 配方，作为分别研究的组。后续兼容结论应以生产解析器和经审查的真实 NX 证据为准；这套工具本身不放宽产品解析器、不修改配方、不根据字节相关性擅自命名字段，也不把手工补丁、第三方配方或合成测试称作官方单变量导出。

## 工具与依赖

入口：`scripts/np3-calibration/calibrate.py`。需要 Python 3.10+ 与 Pillow；本机已有 Pillow，审计/清单功能仅使用标准库。`render` 另需 JDK 17+，可指定 `--jdk`。不会启动相机、安装软件、改产品版本或生成 APK。

```powershell
python scripts/np3-calibration/calibrate.py --help
python scripts/np3-calibration/test_calibration.py
python scripts/np3-calibration/test_normalize16.py
python scripts/np3-calibration/test_diagnostic.py
python scripts/np3-calibration/test_controls.py
# 可选：真实生产源码的 Java 桥测试（合成像素，不是 NX 基准）
python scripts/np3-calibration/test_bridge.py
```

工具输出只写用户指定的新路径，不覆盖现有文件。建议把私有 RAW、NX 导出和实验产物放在已忽略的 `.research/np3-calibration/`，不要把第三方样本或用户照片复制进源码目录。

## 私有真实会话的文件准备

拿到用户明确提供的真实 RAW 后，可使用 `prepare-session` 校验其明确 SHA/大小，并从固定 142 ZIP 只读抽取 8 份未知字段配方及 1 份 Creative 原始文件：

```powershell
python scripts/np3-calibration/calibrate.py prepare-session .research/np3-calibration/session --raw source.NEF --raw-sha256 '实际64位SHA256' --raw-bytes 28315223 --nx-version '实际版本与build'
```

该命令创建 `in/u1.NP3` 至 `in/u8.NP3`、独立 Creative `in/c1.NP3`，以及 `out/`、`evidence/`、`manifests/`。`inputs-index.json` 记录固定 ZIP SHA、成员原路径、原 SHA、复制后 SHA、字节不变核对、基底和未知字段原字节。`sequence.md/json` 只是计划，默认顺序为 Quick sharp `0/-1/+1/复归0`，再分别手动改变三项空间参数，最后导出真正的三项各零基线。必要时增加手动匹配 Quick sharp 联动三元组的控制实验，区分联动与未知字段的相关性。

输入原件与实际 GUI 输出分目录，命令不操作 NX，不创建成片或伪造已导出的证据；所有新 manifest 都保持 `pending`，同一 RAW 会话默认列为训练证据。

## NKSC 当前工作设置与追加日志

`observe-sidecar` 只读工作目录中的 NKSC 文件，不调用 NX 接口。它保存内容寻址的私有侧车快照、逐项原值报告，并向 `provenance/gui-capture-log.jsonl` 追加有 SHA 链的记录；不改旧日志、旧 pending 或别的审计证据。日志记录 GUI 操作报告的实际来源、操作者、文件 SHA/大小/时间，以及 TIFF 元数据，不重复像素比较。

```powershell
python scripts/np3-calibration/calibrate.py observe-sidecar .research/np3-calibration/session .research/np3-calibration/session/NKSC_PARAM/source.NEF.nksc --gui-report gui-operations.json --operator 'Codex computer-use' --files source.NEF baseline.NP3 recipe.NP3 B.tif
```

`gui-operations.json` 应如实记载实际 GUI 操作和来源；计划不能冒充操作记录。日志区分“操作者报告了导出”和“文件观察者独立核对了哪些文件身份”，当前侧车快照不等于之前每一次导出时刻的状态。该日志是单写入者追加日志，写入前后校验既有 SHA 链。

已研究的 NKSC 中，`nine:NineEdits` 是转义保存的 XML 字符串，包含当前工作 filter/active/parameters；外层 `ast:XMLPackets` 是嵌入 XMP，可能保存 `camera-raw-defaults`，不能把它的 Adobe 默认锐化/降噪等值当作 NX 当前工作参数。WB `Data`、Trim、SizeRes 等不透明字段仅保留编码与 SHA，不猜测 Kelvin、裁切或缩放语义；模块 `active=true` 与参数 `enable=0` 可能同时存在，工具不擅自折叠为开/关。TIFF MakerNote 未见明文字段，也不能据此证明某处理不存在。日志永不生成颜色/空间校准通过判定。

## 先调查 0x1400，Creative 单独成组

生成可执行的待采集清单和完整 manifest 模板：

```powershell
python scripts/np3-calibration/calibrate.py plan .research/np3-calibration/field1400-v2 --purpose field_0x1400
python scripts/np3-calibration/calibrate.py plan .research/np3-calibration/flexible-zero-v2 --purpose flexible_color
python scripts/np3-calibration/calibrate.py audit dist/nikon-cloud-recipes-20261018/nikon-cloud-recipes-142.zip
python scripts/np3-calibration/calibrate.py diff baseline.NP3 changed.NP3
# ZIP 内的单个文件可以直接只读，不必解压：
python scripts/np3-calibration/calibrate.py audit 'dist/nikon-cloud-recipes-20261018/nikon-cloud-recipes-142.zip::Nikon Creators/BlueHour_Luna.NP3'
```

`audit` 检查 TLV 边界、重复记录、终止标记，并输出字段真实字节分布与 SHA。已有 142 份样本的 `0x1400` 为 `7f01 ×1`、`8001 ×38`、`8101 ×7`、`ff01 ×96`；基底 `0020 ×141`、`0802 ×1`。这只描述文件，不能解释未知字段的视觉含义。

采集按下列顺序进行：

1. `field_0x1400` 字节研究可在 NX 中从实际默认 Flexible Color 重置，连续两次不改参数导出，确认稳定性与自动变化的元数据。记录锐化、中范围锐化、清晰度的实际三个数值；**Quick sharp=0 不表示三项分别为零**。例如本次 NX 1.11.0.3000 GUI 操作者报告按此顺序为 `(2, 1, 0.5)`，应以每次实际 UI 和导出记录为准。此基线适合语义差分，不作为像素拟合的零调整输入。
2. 每次从同一已保存基线恢复，只改**一个实际可见的 UI 控件**。分别导出中性、低一档、高一档，记录 UI 原名与数值，并保存操作截图或日志。不要通过修改二进制制造 `127/128/129` 样本。
3. 覆盖基础色调、锐化系列、一个 Blender 通道、一个 Grading 轴，保留 `0x1400` 未变化的负证据。对 8 份现有异常配方另做不修改的 NX 往返，记录保留、归一化或拒绝；往返成功本身仍不能确定语义。
4. `Bluegrain_jyota` 的 Creative 基底另建 `--purpose creative_base_0802`。基线和变体都必须是 `0802`；不能把 `0020→0802` 的整体变化归结为一个未知参数。

`diff` 展示变化记录、记录内偏移及前后字节。只有 Flexible `0020` 基底一致，且除名称/备注以外仅 `0x1400` 从 `7f01/8001/8101` 之间变化时，才标记 `isolated_0x1400_byte_pair`。这仍只是**待人工审查的字节证据**，不是语义结论。配合 manifest 中的一项 UI 变更和真实来源，才能提交候选单变量证据；还应独立重复导出确认。

## A/B/C 基准必须从同一 RAW 开始

- **A：**同一真实 NEF/NRW 在 NX Studio **零调整 Flexible Color** 下的已显影图：锐化、中范围锐化、清晰度分别设为 `0/0/0`，对比度/高光/阴影/白阶/黑阶/饱和度全零，Color Blender 全零，Color Grading 各区色度与亮度全零，禁用自定义曲线。导出此状态的基线 NP3，并确认 A 实际使用这份基线。不得直接使用 NX 默认、Reset 或仅 Quick sharp=0 的图作为 A。
- **B：**同一 RAW、同一基础显影与导出设置，套用目标 NP3 的 NX 成片。除了实验参数，其他选项保持相同。
- **C：**当前生产代码对 A 的已解码 RGB 应用目标 NP3 的结果。Java 桥直接调用 `profile.renderPixels`，包括共享空间引擎，支持完整尺寸，不受缩略图 4 MP 上限影响。配方是否可用由所记录版本的生产解析器决定，桥不会绕过拒绝逻辑。

固定 NX 精确版本/build、Latest Picture Control、SDR、白平衡 Kelvin/色偏、曝光补偿、ADL/D-Lighting、降噪、镜头校正、裁切、方向、尺寸及 sRGB 导出配置。禁止自动白平衡及其他 Auto 选项。**B 只改变实验预设/控制，其他 RAW 显影设置与 A 相同**；颜色单项实验中 B 的三项空间处理仍全零，空间实验再单独改变一项。manifest `settings` 中的三个空间数值记录 **A 基线**，会与导出的基线 NP3 自动核对；B 的处理参数来自目标配方和 `changed_controls`。所有参数都应由截图或操作日志佐证。

先用 8 位无损 TIFF/PNG 隔离颜色与空间算法误差，再单独验证 JPEG 路径。评估器拒绝直接读取 16 位输入，避免 Pillow 静默量化；下述 `normalize16` 仅生成显式量化的诊断副本。C 固定另存带 sRGB ICC 的无损 PNG。Java 桥调用真实产品模型与空间路径，但不模拟 Android JPEG 95 编码器或 EXIF 写入，所以这份 C 适合算法校准，不等同于手机编解码端到端验收。

A 仍包含 Flexible 的基础显影和量化，但预设的颜色与空间调整必须是零。若 A 已有锐化或调色，C 再应用目标 NP3 会叠加这些处理，不能用来声明颜色/空间模型通过校准。RAW 内部处理顺序不一定能在显示 RGB 上交换，8 位剪裁也不可逆，因此即使零调整实验通过，也无法推导任意相机或手机 JPEG 都与 NX 一致。

manifest v2 使用 `A_baseline` 和显式 `baseline_kind`：`flexible_color` 拟合必须选 `neutral_zero_adjustment`，工具从保存的基线 NP3 验证中性语义；`field_0x1400` 与 Creative 差分研究可选 `nx_default_or_observed`，保留 NX 的实际默认数值。后者可采集、审计和生成诊断对照，但非中性 A 永远不会得到颜色/空间校准的通过判定。旧 v1 模板不再被接受，应重新生成 v2。

导出 A 之前可先检查基线 NP3，无需凑齐整个实验：

```powershell
python scripts/np3-calibration/calibrate.py baseline baseline.NP3 --require-zero
# 字段语义研究只读取实际基线，不强制零：
python scripts/np3-calibration/calibrate.py baseline observed-default.NP3
```

## 16 位官方导出的显式诊断副本

NX 官方 16 位导出保留为原件。不要把 Pillow 打开的 `RGB` 模式当作保留 16 位精度的证明；其 RGB TIFF 解码可能已经丢弃低字节。`normalize16` 从已核验的 TIFF strip 直接读取原生无符号 uint16 像素，只使用 Pillow 读取元数据和写最终 8 位 PNG：

```powershell
python scripts/np3-calibration/calibrate.py normalize16 .research/np3-calibration/session/out/z16a.tif --frame 0 --output .research/np3-calibration/session/diagnostic/z16a-srgb8.png --report .research/np3-calibration/session/diagnostic/z16a-normalization.json
# 如系统不能解析本地 LittleCMS 路径，显式指定现有库，工具不会下载或安装：
python scripts/np3-calibration/calibrate.py normalize16 input16.tif --output diagnostic8.png --report normalization.json --lcms 'C:\absolute\path\lcms2.dll'
```

当前严格接受 classic TIFF、三通道 RGB、无符号 16 位、无压缩、交错 strips、正常方向，无 alpha/predictor/tiles；支持两种字节序。单页默认选该页；多 IFD 文件必须显式指定 `--frame`，报告列出所有页面元数据与所选索引。本次 NX 文件的 frame 0 是全分辨率 RGB16，frame 1 是 RGB8 小预览，不能把后者当作源图。压缩、BigTIFF、缺少/损坏 ICC 或不明布局会报错，不会退回隐式 8 位解码。内嵌 ICC 必须可解析并明确标识 sRGB；命令经本地 LittleCMS 的 `TYPE_RGB_16→TYPE_RGB_16` 转换至固定目标 sRGB ICC，使用相对色度意图、禁用优化、不启用黑点补偿及抖动。转换保留 uint16 输出，然后逐通道明确量化为 `u8 = (u16 + 128) // 257`，即 `u16/257` 四舍五入到最近整数；不缩放、转向、自动曝光或增加锐化。[LittleCMS 公开 API 与常量](https://github.com/mm2/Little-CMS/blob/master/include/lcms2.h)支持此调用方式。

输出是无损、带目标 sRGB ICC 的 PNG，含 `huevia.normalize16` 诊断来源标记。独立 JSON 记录原 TIFF 与新 PNG 的 SHA/大小、规范小端原生 RGB16 像素 SHA、RGB8 像素 SHA、源/目标 ICC SHA、strip 布局、精确舍入公式、LittleCMS 库 SHA/版本、驱动 SHA 与 Python/Pillow 版本。只固定目标 ICC 的创建日期和空 profile ID 以避免无意义的时间变化，原 ICC 不改；源文件转换后再次核对 SHA。新输出/报告不得覆盖已有文件。

此步骤仅供诊断，不等同于 NX 官方 8 位导出，也不证明输入由 NX 生成；官方 GUI 来源须由前述追加日志另行关联。评估器看到该 PNG 的诊断标记时固定返回 `diagnostic_only_normalized_16bit`，即使有显式阈值与留出声明也不会产生颜色/空间校准 PASS。不要通过重编码、移除标记或将衍生图谎称官方导出来绕过这一限制；要升级为可验收路径，需要单独制定并审查 16 位基准协议。

这类实验使用独立 `huevia.np3-diagnostic-input.v1` 输入清单，不能交给 `seal` 冒充官方 capture。字段精确为 `schema/status/experiment_id/source_raw/baseline_np3/recipe_np3/A_normalization/B_normalization/provenance_evidence/limitations`；`status` 固定 `diagnostic_only`，原 RAW、两份配方、两份转换 JSON 与不可变来源证据均用 `path/sha256/bytes` 关联。转换报告继续绑定原 TIFF 和衍生 PNG。尚未明确解码或锁定的 WB/非预设工作设置写进限制，不编造完整校准配置。

```powershell
python scripts/np3-calibration/calibrate.py render diagnostic-input.json --diagnostic --output C-diagnostic.png --output-manifest diagnostic-render.json
python scripts/np3-calibration/calibrate.py evaluate diagnostic-render.json --diagnostic --directory diagnostic-comparison --samples 65536
```

诊断与正式 capture 共用同一 `render_product`，调用实际生产 `profile.renderPixels`；源码、模型、桥、输入及输出身份均记录。不会把图缩小以绕过生产渲染失败；全尺寸无法处理时应先报告限制。输出 C 继承诊断标记。误差在完整尺寸图上使用确定性网格采样，报告实际采样数与步长；并排图会缩小仅供查看，报告明确它不是数值输入。诊断评估没有阈值、训练或留出验收入口，不拟合任何参数，固定不产生 PASS。

诊断 JSON 的 `metric_roles` 明确每组测量谁是参考、谁是候选；ΔE00/MAE 对称，但剪裁比例有方向。用于模型误差的 `C_to_B` 固定以 NX B 为参考、C 为候选，`new_clip_fraction` 因而表示 C 相对 B 新增的通道剪裁，不能与 `A_to_B/A_to_C` 的候选角色混用。

### 单控制官方参考的批量诊断

已准备的 `tone-controls/stimuli-index.json` 记录受控派生输入：六项基础单控、蓝色/青色 Blender brightness 单控，以及单独标记为复合色彩隔离的 `QSunColor`。派生文件本身不是官方 NP3 导出或像素证据。原始生成清单保留来源 SHA 和所有字节差分，真实 GUI 导出需另记来源。

```powershell
python scripts/np3-calibration/analyze_controls.py plan .research/np3-calibration/session --output .research/np3-calibration/session/manifests/tone-controls-diagnostic.pending.json --nx-version '实际版本与build'
# 等待 NX GUI 真实输出、记录实际操作后再运行；可先用 --only 分析已采集的单项。
python scripts/np3-calibration/analyze_controls.py analyze .research/np3-calibration/session/manifests/tone-controls-diagnostic.pending.json --directory .research/np3-calibration/session/diagnostic/controls-run1 --gui-report actual-gui-controls.json --only QBluM100 QCyaM74
```

默认映射为 `out/<注册名>-16a.tif`、frame 0；若实际命名不同，应在待处理计划中明确修改映射。实际 GUI 报告沿用 `huevia.nx-gui-operation-report.v1`，必须注明来源枚举、真实操作者、`nx_version_reported`、`source_raw_sha256_reported`，并在 `exports_reported[].file` 列出每个实际导出的文件（相对路径基于实验 workspace）。文件存在和报告覆盖核对通过前，不会生成这一批的衍生图。

独立幅度验证输入可放 `in/contrast-validation/`，使用 `plan ... --group contrast-validation` 为 `QConP25/QConP100` 单独建待处理计划；不会修改原九项计划与已导入刺激。幅度候选应在查看这些输出前另行封存，工具只评估记录的当前生产实现。

若另外准备了 `QOraP20`，其独立来源清单及包含三份刺激的 `stimuli-index-with-orange.json` 应另存，原两份清单和 pending 不改；新计划可使用 `--group contrast-orange-validation`。工作目录增加保留验证 RAW 后，创建计划时必须用 `--raw` 明确选择实际 A/B 的源 NEF。工具还会核对 A 原 TIFF16 的真实 GUI 报告是否绑定相同 RAW SHA 和 NX 版本；默认读取会话的 `provenance/gui-reported-exports-zero16.json`，自定义来源可用 `--baseline-gui-report`。旧计划保持原件，执行时补查原会话的 A 来源报告，不能只修改 `--raw` 就拿旧 A 冒充新验证图。新 RAW 仅核对 SHA/大小、保持未拟合，直到真实 GUI 导出后再单独评估。

批量脚本只读原 NP3/RAW/TIFF16，使用已经验证的零调整 z 的诊断 A，逐份显式转换 B 并调用同一全尺寸生产模型生成 C。每次渲染核对生产源码快照，源码在批次中变化就中止，防止把不同实现混作一组。每项报告 C→B 的误差、实际采样网格、完全黑/白像素比例和 RGB 均值；整体黑场等退化可以与真实 NX 单控输出直接对照。完整图不缩放，查看用并排图另行注明。工具不改模型、不拟合参数、不把单项或复合单图诊断当作留出验收，所有结果固定是诊断。

## 填写和封存 manifest

编辑 `manifest-template.json` 的所有 `TODO`：实验 ID、同场景分组、训练/留出分区、固定设置、实际人工步骤和 UI 单项变更。`regions` 可记录归一化框 `[x0,y0,x1,y1]`，类型为 `gray/skin/saturated/other`。`acceptance` 默认为 `null`，工具不内置合格阈值；若实验负责人事先确定阈值，填写：

```json
{"median_de00_max": 3, "p95_de00_max": 8, "new_clip_fraction_max": 0.01}
```

这些数值仅是格式示例，不是官方标准或默认建议。阈值不应根据留出结果反复调节。

完成真实人工导出后封存文件 SHA、大小与来源：

```powershell
python scripts/np3-calibration/calibrate.py seal manifest-template.json capture.json --raw source.NEF --baseline-np3 baseline.NP3 --recipe-np3 recipe.NP3 --a A.tif --b B.tif --operator '操作人' --nx-version '实际版本与build' --evidence settings.png --evidence export-log.txt --attest-nx-gui-export
python scripts/np3-calibration/calibrate.py validate capture.json
```

`seal` 要求操作者明确确认：A/B 来自同一 RAW、相同非实验设置，A 确实使用所保存的基线 NP3，B 使用所记录的目标配方，并确实由 NX Studio 官方 GUI 导出。至少需要一份操作证据。工具对来源作记录，不能仅凭文件格式独立证明其由尼康软件产生；合成、直接编程生成或手工修补的样本不得使用此确认。

v2 同时接受原有 `provenance.kind=manual_nx_studio` 与 `nx_studio_ui`。通过自动 UI 操作 NX 时应使用 `--provenance-kind nx_studio_ui --operator 'Codex computer-use'`，如实记录自动操作的真实截图/步骤，不能称作人类手工完成。历史字段 `manual_export_attested` 保留兼容，表示确认发生了真实 GUI 导出，不要求操作者必须是人；来源枚举与 `operator` 负责明确区别。`--attest-manual-nx` 仍是 `--attest-nx-gui-export` 的旧兼容别名。

`validate` 校验精确字段集、必填设置、文件 SHA/大小、实验基底组及单变量记录；也会输出 NP3 差分证据，不要求先生成 C，因此可用于当前尚不支持的 `0x1400` 和 Creative 研究。

## 生产模型渲染与评估

```powershell
python scripts/np3-calibration/calibrate.py render capture.json --output C.png --output-manifest with-C.json
python scripts/np3-calibration/calibrate.py evaluate with-C.json --directory comparison-report
# 同时评估一组实验可检查场景/RAW 跨训练与留出的泄漏：
python scripts/np3-calibration/calibrate.py evaluate train-with-C.json holdout-with-C.json --directory cohort-report --samples 65536
```

渲染记录模型版本、A/配方 SHA、实际编译的生产源码及驱动源码清单 SHA、Python/Pillow 环境。原 A/B、配方和 RAW 不被修改；输入、输出、源码身份在新 manifest 中绑定。桥如果缺少共享空间引擎会直接失败，不会退回只有逐像素颜色处理的替代实现。

评估要求 A/B/C 完整尺寸一致，不会偷偷对齐、旋转或缩小。嵌入 sRGB ICC 会经 LittleCMS 归一化，PNG sRGB 标记也被明确识别；未知空间或仅有 EXIF 声明时可以生成带警示的测量报告，但不能得到可验收的通过判定。A/B 实际文件格式必须匹配记录。

报告包含 A→B 的配方变化量、C→B 的 ΔE00 中位数/P95/均值、编码亮度 RMSE、RGB MAE、原参考/预测剪裁比例及新增剪裁比例，并按人工标注区域分别计算。指标采用明确记录的确定性网格采样，默认最多 65536 点；剪裁统计也是采样统计。并排图仅用于人工检查，不参与数值计算。[ΔE00 数值测试依据](https://hajim.rochester.edu/ece/sites/gsharma/ciede2000/)来自 Sharma 等人的实现验证数据；本工具并非 CIE 或尼康认证实现。

同一 RAW、连拍或同图裁切应使用相同 `scene_group`；同一 RAW SHA 或场景组跨训练和留出时整组评估拒绝。另留整份未拟合的配方，报告会列出未见配方的留出数量。工具本身不做拟合。只有显式阈值、`holdout` 分区、A/B/C 均有明确嵌入 sRGB 来源，且 A 声明并经基线 NP3 验证为零调整时，才可能显示 `meets_explicit_internal_thresholds`；训练组只报告误差。非中性或未验证的 A 会标为 `indeterminate_non_neutral_baseline`，不能借像素差较小宣称绝对模型通过。

## 当前还缺什么

第三方 142 份配方的结构证据与工具测试不能代替官方 GUI 基准。私有会话即使已导出真实 NP3/TIFF，也还需要逐项核对来源、零调整 A、工作设置和留出结果，才能讨论特定模型的精度。真实字节差分与当前侧车观察不自动确立字段语义、支持范围或官方颜色精度；应先审核差分、重复性与来源，再调整模型和支持范围。单图诊断出现明显偏差时，应保留原结果，补单变量证据和独立场景，不能针对该图拟合后声称通用精度。
