# Android 原生照片编辑验收

## 74 版错误重试文案与卡片几何专项

脚本当前默认目标为 74。先由协调任务构建并安装产品 APK，再运行 `build.ps1 -ExpectedVersion 74 -Scope selection-polish`；设备验收入口为 `run.ps1 -ExpectedVersion 74 -Scope selection-polish -Mode phone`，另以 `large-font`、`landscape` 分别复验。`full`、`viewer-boundary`、`selection-polish`、`album-refresh` 继续可显式选择，各版本与 scope 的证据目录独立。74 的选择界面证据写入 `docs/validation/photo-selection-polish-74/{mode}`，探针为 `.tooling/photo-editor-categories-74/editor-probe.apk`；这些命令不会覆盖 69／71／72 的证据。

74 的 `selection-polish` 在既有在线分类、真实满队列恢复和选择样式流程中，增加无缓存错误的“重试”完整可读检查，并加强同一批卡片在点击及异步完成前后的几何稳定断言。实际覆盖和通过项数以新运行的 `report.json` 为准，不借用历史通过结果。在线目录仍为明确标注的离线种子，不能当作设备实时生产目录验收。

构建会生成 `.tooling/photo-editor-categories-74/provenance.json`，记录本次探针 Java 源码、构建／运行脚本及测试 APK 的 SHA-256、请求版本／scope 与 UTC 构建时间。`run.ps1` 校验测试 APK 哈希及目标版本后，将旁车复制进当前证据目录；该记录仅说明本次构建来源，不反填旧版历史。实际字号、configuration 方向及显示尺寸由探针写入报告；横屏须等待实际横向 configuration，截图也检查横向像素尺寸。

运行脚本仅允许已核对 ranchu／goldfish 的专用 `emulator-5582`。它保存运行前 `font_scale`、`accelerometer_rotation`、`user_rotation`，强制本轮字号与方向并回读，将设置写入 `environment.txt`，最后逐项恢复原值并以 `restoration.txt` 记录回读结果。原值缺失时恢复为缺失，不假定原字号为 1.0。直接执行 instrument 不会替代上述设置与恢复。

## 72 版相册刷新与导出按钮专项

72 版历史入口：构建 `build.ps1 -ExpectedVersion 72 -Scope album-refresh`，运行 `run.ps1 -ExpectedVersion 72 -Scope album-refresh -Mode phone`；大字体与横屏分别使用 `large-font`、`landscape`。测试 APK 隔离到 `.tooling/photo-album-refresh-72/editor-probe.apk`，证据位于 `docs/validation/photo-album-refresh-72/{mode}`，不覆盖旧版编辑器证据。

专项通过实际 `dispatchTouchEvent` 覆盖已下载列表短拉、取消、越过 64dp 后松手与加载中重复手势，并在真实本地读取 worker 完成后检查指示器停止；普通点击／长按仍走原生列表路径。相机分页使用有类型的合成元数据、既有真实 JPEG 缩略图和异步 gate，验证真实 controller 页请求失败、底部点击重试、追加与结束，以及原 grid/footer 引用和指示器状态。没有相机网络连接或原图传输认证；右上导出另测 64×48dp 触摸、64×36dp 视觉和文本完整性。通过结果以实际运行报告为准。

## 71 版选中态细节专项

71 版历史入口：`build.ps1 -ExpectedVersion 71` 构建独立探针；安装对应产品后以 `run.ps1 -ExpectedVersion 71 -Scope selection-polish -Mode phone` 运行。该 scope 先完成既有在线分类／满队列恢复／稳定预设卡片／来源切换／离线目录重试流程，再用实际 Views 检查来源分段和分类的 48dp 触摸区域、标签单行、两行控件及分类项之间间距、类别切换的子 View 身份与位置稳定，以及展开网格完整一行。黑白类别来自明确的离线夹具，额外截图为 `selection-portrait`、`selection-black-white`、`selection-expanded`。

证据写入 `docs/validation/photo-selection-polish-71/{phone,large-font,landscape}`，报告记录实际产品版本、APK SHA-256、字号、触摸边界与可读取的来源材质 outline；材质的视觉效果仍需查看原生截图，不以触摸矩形冒充可见背景范围。`full` 与 `viewer-boundary` 保留，各 scope 及版本独立输出，不覆盖 69／70 的历史证据。

## 70 版查看器边界专项

70 版历史入口：使用 `build.ps1 -ExpectedVersion 70`，安装对应产品后运行 `run.ps1 -ExpectedVersion 70 -Scope viewer-boundary -Mode phone`。`full` 仍保留原有编辑、分类、缓存和导出回归流程；本专项只执行既有种子准备及查看器资格检查。

专项输出到 `docs/validation/photo-viewer-boundary-70/{phone,large-font,landscape}`，不覆盖 69 版证据。报告记录实际产品版本、APK SHA-256、模板 JPEG SHA 与夹具范围；包含 `remote-preview`、`local-editor`、`remote-saved-editor`、`return-remote` 截图。检查远程预览无编辑器、隐藏／恢复工具、原因查看／重试、完整本地原图可编辑、已保存的相机来源可编辑、切回远程不串图，以及 `updateViewerStatus()` 驱动的资格升级／降级和空文件／`.part` 拒绝。

预览错误交互使用明确的合成连接状态和拒绝所有数据读写的 `PhotoCameraClient` 代理，仅激活真实原生错误弹窗及重试回调。代理不能连接网络或返回照片字节；该项不证明真实相机、PTP 传输、完整性查找或系统相册导出成功。其余图片使用既有模板生成的完整本地 JPEG；在线目录继续采用明确标注的离线种子，专项不访问生产目录。本文件原 69 版说明保留如下，不能把历史验证当成新版已通过。

仅运行在 `emulator-5582`，且校验 `ranchu/goldfish` 硬件。脚本开启飞行模式，不连接相机、不操作真实手机。使用只读 AVD 的临时层；关闭该模拟器即丢弃测试数据。

69 版历史目标为产品 69 / 0.13.1-beta，当时先构建并安装产品，再执行 `build.ps1 -ExpectedVersion 69` 和 `run.ps1 -ExpectedVersion 69 -Mode phone`。探针中的 `large-font` 只是场景名，本身不修改 Android 字号；真实 `font_scale` 需由外部设置。当时的 `run.ps1` 在 instrument 前设置并回读：`large-font` 为 1.3，其他场景为 1.0，值不符即停止，并将实际设置写入证据 `environment.txt`；finally 恢复 1.0。74 脚本已改为恢复运行前原值。`-Mode landscape` 请求横屏；脚本预先设置 `settings put secure immersive_mode_confirmations confirmed`，避免 Android 首次全屏教学层遮住证据。

测试 APK 与产品使用同一调试签名，但测试代码、配方与图片不打入产品 APK。构建时从本地研究压缩包取两份真实 NP3（支持与明确不支持），复用 hand-authored NCP fixture，并使用 App 已有模板图片。配方仅放在 `.tooling/photo-editor-categories-69` 的忽略目录中。图像是现有近似模板，来源见 `docs/assets/preset-template-provenance.md`，不冒充真实相机色彩标定证据。在线分类场景使用明确标注的离线种子目录，配方格式、长度与 SHA 来自上述真实来源；这不是实时生产目录验证。

通过产品实际 Views／处理类验证：59 个配方的大库、缓存淘汰／重载、暂停刷新、旧／新版本切换与单原图角标、当前照片预览与强度、离开重进后的独立草稿、快速切图、预览不提前生成永久效果、分类／三列面板／搜索空态、导出确认、混合三张导出、真实 Android JPG 与逐张渲染字节一致、原始像素尺寸、来源链、MediaStore 实际字节和重复效果复用。

69 证据保留在 `docs/validation/photo-editor-categories-69/{phone,large-font,landscape}`，报告记录产品 APK 的实际 SHA-256，完成情况见该目录 README 与报告。旧 `docs/validation/photo-editor-native/` 保留 68 的每场景 144 项历史记录，不能扩大到后续版本，也不作为真实相机、TalkBack、性能或全部 Android 机型的证明。
