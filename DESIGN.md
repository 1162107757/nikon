---
name: 相机预设
description: 云白与石墨色的原生安卓预设库、相册及相机设置
colors:
  surface: "#F7F8FA"
  container: "#EEF0F4"
  card: "#FFFFFF"
  text: "#20242B"
  secondary-text: "#626B78"
  primary: "#0065DC"
  on-primary: "#FFFFFF"
  primary-container: "#E9F1FF"
  outline: "#D9DEE6"
  success: "#258449"
  error: "#BA302A"
  dark-surface: "#22252A"
  dark-container: "#30353D"
  dark-card: "#2A2E34"
  dark-text: "#F4F5F7"
  dark-secondary-text: "#ADB6C3"
  dark-primary: "#8DBBFF"
  dark-on-primary: "#07244A"
  dark-primary-container: "#263D5A"
  dark-outline: "#474E58"
  dark-success: "#7ED9A1"
  dark-error: "#FFB4AB"
typography:
  page-title:
    fontFamily: "sans-serif"
    fontSize: "32sp"
    fontWeight: 700
    letterSpacing: "-0.03em"
  secondary-page-title:
    fontFamily: "sans-serif"
    fontSize: "20sp"
    fontWeight: 700
    letterSpacing: "-0.015em"
  detail-title:
    fontFamily: "sans-serif"
    fontSize: "24sp"
    fontWeight: 700
  preset-name:
    fontFamily: "sans-serif"
    fontSize: "18sp"
    fontWeight: 700
  body:
    fontFamily: "sans-serif"
    fontSize: "15sp"
    fontWeight: 400
  source-label:
    fontFamily: "sans-serif"
    fontSize: "13sp"
    fontWeight: 400
  navigation-label:
    fontFamily: "sans-serif"
    fontSize: "10sp"
    fontWeight: 400
rounded:
  photo: "14dp"
  field: "14dp"
  tone-curve: "16dp"
  source-pill: "19dp"
  group: "22dp"
  guide-card: "24dp"
  camera-card: "26dp"
  button: "26dp"
  navigation: "34dp"
spacing:
  gap: "8dp"
  content: "22dp"
  album-content: "24dp"
  touch-target: "48dp"
  button-height: "52dp"
components:
  primary-button:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.button}"
    padding: "10dp 16dp"
    height: "52dp"
  secondary-button:
    backgroundColor: "{colors.card}"
    textColor: "{colors.primary}"
    rounded: "{rounded.button}"
    padding: "10dp 16dp"
  settings-group:
    backgroundColor: "{colors.card}"
    textColor: "{colors.text}"
    rounded: "{rounded.group}"
  camera-card:
    backgroundColor: "{colors.card}"
    textColor: "{colors.text}"
    rounded: "{rounded.camera-card}"
  photo-tile:
    backgroundColor: "{colors.container}"
    rounded: "{rounded.photo}"
  input-field:
    backgroundColor: "{colors.container}"
    textColor: "{colors.text}"
    rounded: "{rounded.field}"
  source-pill:
    backgroundColor: "{colors.card}"
    textColor: "{colors.text}"
    rounded: "{rounded.source-pill}"
    height: "34dp"
  navigation:
    backgroundColor: "{colors.card}"
    rounded: "{rounded.navigation}"
    height: "68dp"
  hold-to-compare:
    backgroundColor: "rgba(26, 29, 35, 0.8431372549)"
    textColor: "{colors.on-primary}"
    rounded: "24dp"
    padding: "0dp 16dp"
    height: "48dp"
  tone-curve:
    backgroundColor: "{colors.card}"
    textColor: "{colors.text}"
    rounded: "{rounded.tone-curve}"
    height: "188dp"
---

# Design System: 相机预设

## Overview

**Creative North Star: "云白与石墨"**

0.4.0-beta 已实现用户批准的 Apple 风格方案：云白背景、白色分组、石墨文字、蓝色操作和悬浮毛玻璃导航。视觉参考为 [已确认的设置页](docs/design-review/apple-glass/preview-settings-light.jpg)；用户在三目的地设置架构确认后说“开始实现”。这里记录实际原生 Android Java / Views 的实现，代码来源是 `UiPalette`、`MainActivity`、`AlbumPanel`、`GlassBar` 和 `AppIcon`。

界面以安静的内容层次和原生文字为基础。照片承担内容展示，连接操作集中在设置，预设库与相册只显示简短连接状态。深色使用独立石墨配色；按钮、系统返回、对话框和文件选择仍遵循 Android 行为。

0.5.0-beta（versionCode 17）沿用这一世界，扩展持久化外观选择、独立无线预设检测、模板模拟主图和轻量页面过渡。已下载主图（含在线卡片）明确标注手机近似，未下载在线项保留发布者样片，配套实拍独立展示。扩展继续由原生代码和既有语义角色主导。

0.5.1-beta（versionCode 18）仅扩展详情主图的原图对比：右下角安静的深色胶囊在按住时显示模板原图，松开恢复模拟效果；裁切与图像位置固定，角标明确当前状态。它延续既有摄影内容层次，不改变视觉世界与导航。

0.5.2-beta（versionCode 19）沿用同一世界，在已下载详情的照片下方加入紧凑的只读色调曲线卡；蓝色实际曲线与次要色虚线参考共享明确的输入、输出与图例。照片上的对比按钮缩短为“原图”，保持原有命中区域和交互说明。

**Key Characteristics:**

- 云白背景、石墨文字和白色分组。
- 蓝色操作、小胶囊来源切换与悬浮导航。
- 原生字号、安全区、返回和触摸反馈。
- 持久外观选择、轻量页面过渡与有标签的模板近似。

0.5.2 的独立复核审阅四张最终 `phone-apple-v052-*.png` 和相关源码，首轮唯一材料问题为参考线与图例对比度；两处虚线 alpha 均改为 200 后，浅色约 3.44:1、深色约 4.74:1，复核评分 resolved，结论为 **SHIP，仅限已审阅的手机 UI**。最终 APK 在 Pixel 9 / Android 17 模拟器通过 8 项功能断言，覆盖 tone / identity 实际采样、切换后缓存、短按钮 DOWN / UP、深色 1.3 倍字号及恢复设置。0.5.2 尚未安装到真机，也未重跑完整核心或 probe 套件；不扩展至 TalkBack、平板、物理手势、性能或相机效果。见 [0.5.2 完成复核](docs/design-review/apple-glass/finish-review-0.5.2.md)。

0.5.1 的独立复核审阅四张 `phone-apple-v051-*.png` 与 `MainActivity`、`HoldToCompare` 源码，返回 **SHIP，仅限已审阅的手机 UI**，无材料级修复。Pixel 9 / Android 17 模拟器的 15 项功能断言覆盖按下、松开、图像变化与完全恢复、移出后移回、取消、暂停恢复、键盘切换和深色 1.3 倍字号。OnePlus / Android 16 主用户 0 已保数据覆盖安装并读取版本 18，未完成真机对比手势验证。辅助功能路径仅审阅源码；未认证 TalkBack、平板、物理手势、性能或相机效果。本次 UI 补丁未重跑完整套件。详见 [0.5.1 完成复核](docs/design-review/apple-glass/finish-review-0.5.1.md)；以下证据保留各自版本范围。

0.5.0 的独立完成复核审阅全部 11 张 `phone-apple-v05-*.png` 和相关源码，未发现材料级缺陷，结论为 **SHIP，仅限已审阅的手机 UI**。证据覆盖 OnePlus / Android 16 预设首页，以及 Pixel 9 / Android 17 模拟器的浅深色、设置、模板选择与原图、合成 NCP 详情、自选模板持久化和无线只读检测。最终 APK 已保数据覆盖安装到真机主用户 0，原有 AgfaOptima 保留，安装哈希与交付一致。0.5.0 核心检查 8,212 项（含模板 1,108 项）及 probe 检查 3,487 项通过。动画仅检查源码，未认证时序或流畅度；该版真实相机登记、相机效果、平板、放大字号、TalkBack 和性能未验证。详见 [0.5.0 完成复核](docs/design-review/apple-glass/finish-review-0.5.0.md)。

0.4.0 最终 APK 的 17 张手机模拟器截图与相关源码经独立复核，结论为 **SHIP，仅限已审阅的手机 UI**。合成相册服务覆盖分页、轻量预览、显式原图下载及断开后状态；它不证明真实相机兼容性。未完成真实相机/USB、TalkBack、平板、物理双指手势或毛玻璃性能认证。范围与版本见 [0.4.0 验证](dist/nikon-usb-0.4.0-beta-VALIDATION.txt)。旧红白方案和旧版检查保留在 [README 历史记录](README.md)及[原生界面记录](.impeccable/surfaces/om-nikonpreset-usbprobe-mainactivity-java-8eede70e.md)，不作为本版证据。

0.4.0 已在 PKG110 / Android 16 主用户 0 保数据覆盖安装，安装包哈希与交付一致；冷启动成功，三个根页均已点开，进程过滤的 AndroidRuntime 错误日志为空。三张 `phone-apple-device-{home,album,settings}.png` 是 1080 × 2376 的真实手机截图，沿用手机现有深色设置，未改字号或主题。独立复核补看三张真机根页截图后未发现新增材料缺陷，原限定 SHIP 保持。手机未连接相机、未加入合成照片；结论不扩大至相机或其他未测场景。

## Colors

`UiPalette` 是色彩角色的唯一代码来源。云白页面用白卡片建立分组，正文为石墨色，次要文字保留可辨识度；蓝色用于主操作、选中目的地和明确入口。深色将页面、卡片和容器分别调整为石墨层次，使用浅蓝操作色。

设置中的“外观”提供跟随系统、浅色与深色，由 `AppAppearance` 持久保存。手动选择同时更新原生主题和语义颜色；不覆盖系统 `uiMode`，不重建 Activity，保留正在使用的会话。跟随系统时继续响应设备主题变化。

绿色状态点必须与“已连接”等文字一起出现。USB 只有完成只读槽位读取、自有会话正常关闭，并仍检测到该设备及访问授权后才显示已连接；Wi-Fi 使用相册连接实际验证后的状态。Wi-Fi 网关建议、选择连接方式或插入 USB 本身均不产生成功状态。相册状态只表示无线相册连接，不能由 USB 状态代替。

## Typography

使用 Android 系统 `sans-serif`，常规与粗体；相册部分标签使用 `sans-serif-medium`。文字采用 sp，尺寸与命中区域采用 dp，不打包 SF 字体。

- 根标题为 32sp 粗体，字距 -0.03em；带返回的二级标题为 20sp，字距 -0.015em。
- 详情与结果标题为 24sp，预设名称为 18sp；当前相机名称为 19sp。
- 正文通常为 14–16sp，主按钮为 15sp，来源标签为 13sp，辅助说明为 11–13sp。
- 底部导航标签为 10sp，选中态加粗；图标与文字共同表达目的地。

## Layout

根目的地固定为“预设 / 相册 / 设置”。预设详情、导入确认与结果、Wi-Fi 连接页和照片查看使用返回层级；这些二级页面隐藏全局底栏。照片传输时也隐藏根底栏，且禁止切换连接或启动预设导入。系统栏、刘海与输入法安全区由原生 WindowInsets 处理；宽度达到 600dp 时内容最大为 720dp，这一实现不代表平板已经验收。

预设库左右主要留白为 18–22dp；设置为 22dp，相册为 24dp。来源切换总宽 216dp、命中高度 48dp、可见选中胶囊高 34dp。相册在字体比例大于 1.2 时将切换总宽扩为 248dp。设置将当前相机独立成卡，下方为连续的“相机连接”“预设导入”和“应用”分组。

底部导航居中悬浮，最大宽 360dp，距内容安全区底部 14dp；基础高 68dp，字体比例大于 1.2 后按源码公式增加高度。内容预留约 108–112dp 底部空间以避开导航。主要图标命中区域为 48 × 48dp，主要按钮最小高 52dp；设置行最小高 66dp。

预设列表主图比例为 1.68:1，详情为 1.25:1。已下载预设（含在线卡片）的主图标明“模板模拟”，未下载在线预设使用发布者样片；配套实拍参考与模拟图分开显示。不支持的模拟、加载失败及缺少样片均有真实状态，失败可点按查看原因并重试，不用随机滤镜填充。相册采用自适应网格，触底按每批 24 项自动加载；沉浸查看使用黑底、完整按比例居中照片与避开安全区的叠加操作栏。

## Elevation & Depth

正文通过云白、白卡片、浅灰容器、细分隔和留白建立层次。主要按钮清除默认 elevation、translationZ 与 StateListAnimator；选中的预设来源标签使用 1dp 轻微抬升。

`GlassBar` 为独立悬浮导航提供 8dp elevation、细边缘和顶部高光。API 31 及以上在硬件加速、动画开启、非低内存及非省电条件下，从独立的后方内容层以 1/6 尺寸采样，通过 `RenderEffect` 加 18dp 模糊；采样最多每秒 10 次，由内容帧驱动。导航文字与图标保持清晰，不参与模糊。浅色覆盖层采用 Android ARGB `#ECFFFFFF`，深色为 `#E6282C32`。

旧系统、低内存、省电、动画关闭、采样失败或无合适背景时使用实色卡片后备。不得通过降低文字可读性来增强玻璃效果；当前检查不构成实际设备上的模糊性能认证。

## Shapes

圆角按容器角色区分：照片与输入框 14dp，预设来源胶囊 19dp，设置分组 22dp，无线准备卡与地址卡 24dp，当前相机卡和主要按钮 26dp，底部导航 34dp。相册选中来源的视觉胶囊为 17dp 圆角。局部紧凑描边操作保留 24dp 圆角、最小 80dp 宽及 48dp 高。

`AppIcon` 是统一的代码绘制图标族：24dp 画布，1.8 单位圆头与圆角线条。不要用 emoji 或与这一线条风格无关的图标替代结构图标。高级诊断保持独立 `DiagnosticTheme`，不作为消费界面的视觉模板。

## Components

- **按钮与字段：** 蓝底主按钮配白字，次操作使用白卡片配蓝字；原生 RippleDrawable 提供按下反馈，保留焦点和系统按钮语义。Wi-Fi 地址与端口使用 16sp 系统输入，读取期间禁用普通输入，取消保持可用。
- **导航与来源：** 底部三个目的地使用图标、文字和浅蓝选中背景；来源切换使用小胶囊、文字和选中语义，外层保持 48dp 命中高度。
- **设置：** 当前相机卡展示真实型号、连接状态、通道及断开入口。连接分组提供 Wi-Fi 与 USB；预设导入分组提供默认通道与独立无线只读检测；应用分组提供外观、预览模板、在线预设库、帮助与关于。检测完成不等于持续连接或完成登记。在线目录没有默认服务地址。
- **连接与导入：** 先在设置连接，再从本地预设进入导入。导入复用设置选择的通道，先断开相册会话，再重新读取能力和空槽；兼容摘要、参数调整、明确目标确认、单次写入和精确读回检查保持原有后端约束。待核对结果沿 journal 的原通道恢复，并回到预设结果路径。
- **预设卡与详情：** 本地预设主图使用有标签的手机近似模拟，配套实拍保留独立入口。内置 AI 模板可查看、更换及恢复，来源见 [素材记录](docs/assets/preset-template-provenance.md)。只支持已验证的 638 字节 NCP 0100 子集，模型和限制见 [模板模拟说明](docs/preset-template-preview.md)。下载到手机、模板模拟与导入相机分别表达。
- **详情原图对比：** `HoldToCompare` 在已下载预设详情主图右下角提供 48dp 高、24dp 圆角的“原图”胶囊（0.5.2 缩短可见文字，辅助说明仍为“按住看原图，松开恢复预设效果”），距右侧与底部各 12dp；13sp `sans-serif-medium` 白字、深色半透明底和细白边保持照片上的可读性，键盘焦点加粗白边。两幅图复用同一 `CENTER_CROP` 视图；按下立即显示未应用预设的模板，松开恢复效果，角标同步显示“模板原图 / 模板模拟”。原图和效果均就绪后启用，准备中与失败分别显示文字状态。移出按钮、取消、额外触点、失焦、暂停、隐藏和视图卸载均恢复；移回不能重新进入原图。键盘或辅助功能点击使用独立的两次点击切换并提供状态描述。原图在后台预取到共享图像 LRU，模板版本和页面 epoch 拦截旧结果；按下无图片 I/O 或过渡动画。该局部操作取代详情外部原图入口，设置中的原图查看保留。
- **详情色调曲线：** `ToneCurveView` 在已下载详情照片与名称区域下方使用卡片色、16dp 圆角的只读容器；基础高 188dp，按 `188 + max(0, fontScale − 1) × 86` dp 随字号增长，上下间隔为 10dp / 16dp。标题为 13sp 系统 medium，坐标标签 9sp，图例 10sp；横轴“输入”、纵轴“输出”标明 0–255。2dp 蓝色实线逐点连接同一 `NcpPreviewProfile.mapTone()` 的 257 个实际采样，不平滑；1dp 次要色虚线及图例样线以 alpha 200 绘制线性参考。读取与失败显示文字，失败可点按重试，不用占位曲线冒充解析结果。辅助描述包含采样数及三个代表值；这不等于 TalkBack 实测。
- **相册：** 相机照片与已下载分开。每次只请求一页；失败暂停并提供显式重试。连接变化先停用旧网格，再清除数据，避免断开时旧滚动位置访问新列表。已下载原图可离线查看。
- **照片查看：** 先显示已有缩略图，再通过标准 `GetThumb` 读取相机轻量预览；单图最多 4 MiB，缓存最多 2 项 / 8 MiB。显示实际像素尺寸，失败不自动读取原图。“下载原图”只有显式操作才使用 `GetObject`，完成后显示本地原图并保留同一照片的缩放与中心。只含预览的离线照片不能下载原图。

### 页面过渡

`PageTransitions` 仅对目的地变化的内容层做轻量入场，底栏保持稳定：一般页面 220ms、最多 20dp 水平位移，照片查看 180ms 淡入；曲线为 `(.22, 1, .36, 1)`。同一页的数据刷新不重复动画，快速切页和视图卸载会取消旧动画并恢复可见状态。系统关闭动画时直接呈现最终画面，保留原生按下 Ripple。

上述为源码规则；本轮静态截图与复核不认证动画时序、流畅度或设备性能。

## Do's and Don'ts

- **Do** 从 `UiPalette` 和原生组件提取当前规则，保持浅色与深色的完整角色对应。
- **Do** 为连接、加载、错误、结果待核对提供文字，状态点只是辅助。
- **Do** 让普通用户先理解预设、连接和目标槽位；协议调查留在辅助诊断中。
- **Do** 保留系统返回、字号缩放、安全区、48dp 命中区域及真实缺图状态。
- **Don't** 将旧红白 tokens、浏览器原型或旧截图作为 0.4.0 的实现来源。
- **Don't** 将概念照片、合成测试照片或用户私有配方作为可分发预设或实拍证据；内置 AI 模板仅用于明确标注的近似模拟，并保留来源。
- **Don't** 将下载、登记读回一致、原生载入等效和实际拍摄效果混称为同一种成功。
- **Don't** 将模拟器 UI 的 SHIP 结论扩大为真实相机、TalkBack、平板、物理手势或性能认证。
