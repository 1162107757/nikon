---
name: Huevia
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

## 0.11.4 个人云端与预设管理

“我的预设”的分栏下方用一行次要文字显示云端／仅本机状态与入口，保留图库为主体，不增加常驻大卡片。首次选择文件说明本机与云端差别，已有数据上传需用户明确选择，未上传数量与“已同步”并列说明。设备状态采用“绿点 · 相机名称 · 已连接”，过长名称单行截断。刷新用连续圆弧和相接箭头，避免视觉断裂。预览失败占位保留说明，不阻挡整张卡片的详情与删除手势。浅深色证据见 [61 验证](docs/validation/personal-presets-61/README.md)。

## 0.11.3 发布入口与未读提示

预设主页右下角 54dp 实心蓝色悬浮加号，距底部导航保留间隙；“我的”操作说明为导入本地预设，“在线”为发布预设，游客点击发布进入登录。标题区只保留连接状态。底栏设置图标右上显示 8dp 未读圆点；设置内消息行使用 22dp 最小宽度数字胶囊，超过 99 显示 99+，浅色白字／红底，深色深字／浅红底。成功同步已读才隐藏，不以重建整页制造刷新闪动。浅深色原生证据见 [60 验证](docs/validation/preset-entry-60/README.md)。

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

## Web 管理后台（2026-10-18）

### Overview

管理后台沿用“云白与石墨”的语义色彩，以左侧任务导航、右侧工作区承载预设维护与 App 用户管理。它是独立的 Web 表面；以上 YAML、原生字号、圆角与导航规则仍属于 Android，不因本节而改变。Web 的实际来源为 [页面结构](online-library/admin/index.html)、[交互](online-library/admin/app.js)及 [最终样式](online-library/admin/styles.css)；[基础 tokens](online-library/admin/tokens.css)先加载，后加载的样式覆盖其颜色、字体与组件尺寸。

### Colors

Web 使用下列有效语义角色。浅色成功绿已为状态文字单独加深；不要用 Android 的成功绿替换它。

| 角色 | 浅色 | 深色 |
| --- | --- | --- |
| 页面 / 次级表面 / 内容表面 | `#F7F8FA` / `#EEF0F4` / `#FFFFFF` | `#22252A` / `#30353D` / `#2A2E34` |
| 正文 / 次要文字 | `#20242B` / `#626B78` | `#F4F5F7` / `#ADB6C3` |
| 操作 / 操作上文字 / 选中底色 | `#0065DC` / `#FFFFFF` / `#E9F1FF` | `#8DBBFF` / `#07244A` / `#263D5A` |
| 分隔 | `#D9DEE6` | `#474E58` |
| 成功 / 错误 / 警示 | `#217A43` / `#BA302A` / `#946200` | `#7ED9A1` / `#FFB4AB` / `#EAC17C` |

**The Semantic Status Rule.** 绿色、警示色和圆点必须配合文字标签。QQ 是否配置、用户是否启用和预设是否上架使用各自的真实服务端状态；占位提示沿用不透明的次要文字色（`opacity: 1`）。外观提供跟随系统、浅色与深色，并在当前浏览器持久保存。

### Typography

采用系统字体栈 `-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif`，正文为 15px（`.9375rem`），行高 1.5。页面标题为 28px（`1.75rem`）、700 字重、字距 -0.025em；预设名称为 15px，用户昵称为 14px，按钮和字段标签通常为 13px，辅助时间与说明为 11–12px。时间、计数和用户属性使用等宽数字；不用展示字体干扰维护工作。

### Layout

桌面侧栏固定于左侧，宽 232px；761–1150px 视口缩为 204px。顶部与主内容同步留出侧栏宽度，内容左右内边距通常为 32px，中等视口为 24px，1500px 及以上为 48px。侧栏只提供“预设管理”“用户管理”，当前目的地用蓝字与浅蓝底标记。

760px 及以下将导航重排为顶部横向两项；顶部工具区固定高 72px，导航紧接其下，内容不保留桌面侧栏空位。内容左右留白为 20px，380px 及以下为 14px。列表由多列改为纵向信息行：用户时间保留明确标签，较窄桌面可隐藏注册时间列，详情仍可查看完整属性。切换目的地后将工作区焦点与滚动位置回到顶部；关闭用户详情恢复原列表滚动位置。

### Elevation & Depth

工作区主要依靠实色表面、细边界与留白建立层次。顶部栏不使用模糊或阴影，登录卡同样保持平面。右侧详情 / 编辑抽屉与居中确认对话框使用遮罩和对话框阴影；底部短提示保留局部半透明材料。减少透明效果偏好改用实色后备。

### Shapes

按钮、搜索框与当前导航采用较紧凑的圆角（10px），桌面列表容器为 14px，手机列表为 12px，登录卡与确认框为 16px。桌面抽屉左侧圆角为 16px，760px 及以下取消圆角，600px 及以下扩为全屏。头像保持圆形，状态标签保持胶囊形；图标统一为细线、圆头、圆角 SVG。

### Components

- **操作与字段：** 主按钮用操作色，次操作用内容表面和描边，安静操作用文字。一般按钮最小高 42px，导航项为 46px，搜索与筛选为 44px；顶部、分页及手机局部操作按源码收紧。键盘焦点使用 3px 操作色外框；提交期间禁用重复操作并显示进行中文字。按钮颜色反馈为 160ms，抽屉入场约 200ms；减少动效时关闭过渡与动画。
- **预设列表与编辑：** 工具栏包含名称 / 简介 / 机型搜索、状态、分类筛选和排序；内容行以效果图、名称、分类、状态及维护入口建立层次。编辑器的单分类支持建议值或自由输入。草稿、版本、上架、下架与待更新分别表达，保存不等于上架。
- **用户列表与详情：** 昵称 / 用户 ID 搜索、状态筛选、分页及正常 / 停用计数读取服务端结果。列表展示头像、来源、状态、注册与最近登录；详情补充累计登录、最近更新及脱敏 QQ 标识。加载、错误、无用户和无搜索结果有独立文字状态；头像缺失使用统一线性占位图标。
- **账号状态确认：** 启用与停用均需明确确认。停用说明 App 会话失效且资料保留；资料冲突提示重新读取详情。普通 App 用户与管理员账号独立，不以 QQ 用户替代后台登录。
- **QQ 接入提示：** 用户已确认尚未申请 QQ 互联。后台根据服务端返回显示待接入状态，空列表说明接入后才会出现注册用户；已配置只代表配置状态，不代表真实 QQ 登录已验收。

### Do's and Don'ts

- **Do** 从最终 Web 样式提取有效值，保留浅深色、键盘焦点和减少动效偏好。
- **Do** 让真实状态、空态与下一步操作优先于装饰；用户详情只展示脱敏 QQ 标识。
- **Don't** 用本节的 px、侧栏或 Web tokens 覆盖 Android 的 dp / sp、底部导航或既有 sidecar。
- **Don't** 把本地合成用户或截图写成真实 QQ 用户、手机登录或线上部署证据。

独立完成复核结论为 **SHIP，仅限已审阅的 Web UI**。本地桌面与窄屏合成截图、对比度修复及测试边界见 [管理后台完成复核](docs/design-review/admin-management-2026-10-18.md)。本节不表示新版线上部署、真实 QQ 登录、Android 新登录或真实手机已验收。

## Android 0.9.7 预设卡片与分类

预设首页沿用原生云白、石墨及蓝色角色，手机竖屏改为 GridView 双列卡片。封面比例约 4:5，卡片圆角 16dp，水平间距 12dp、垂直间距 14dp，名称 15sp 两行，分类 11sp，下载以 44dp 图标触区呈现。来源切换下方使用横向分类筛选，当前分类采用 primary / primaryContainer；只列出实际已有分类，旧数据显示未分类。详情提供本地归类及新建入口。每个来源和分类保留原生滚动状态，主图、模板示意、模板模拟与导入语义不变。

宽度不小于 600dp 时源码定义三列，较大字体定义单列回退，本轮运行验收只覆盖手机竖屏浅色及 1.3 倍字号深色。限定复核与证据见 docs/design-review/preset-feed-0.9.7.md，不能将源码自适应能力等同于平板、横屏或性能认证。

## 0.9.14-beta · 指定相机 Wi-Fi 连接

保留原生云白／石墨、系统蓝和 D 画布。Wi-Fi 连接前使用原生模态表单确认相机网络名称、掩码密码及明确的开放网络选项；输入框保持 52dp 高和既有容器色。无效输入在表单内说明，不关闭对话框；取消保留原页面。密码禁止 view-state 和自动填充保存，说明只用于本次请求。系统连接等待有独立可取消状态，实际 Network 可用后自动继续原来的相机读取和直播／导入续接。

最终 53 版 SHA `257a028ca814be1625e74e2217a184d9e33c1554f8749dfff37672da6592cd5c` 在隔离模拟器的凭据表单浅深色各 39 项、原照片功能各 84 项通过；独立复核限代码与 UI 无未关闭 P1/P2。合成表单未提交有效连接，没有 Wi-Fi／PTP；旧 52 版 echo 不作为本版通过。实体手机在厂商“完全允许后台行为”配置后，另行完成后台 113 秒、实际锁屏 166 秒同请求／通道和真实原图传输，独立复核限本次观察通过。证据位于 `.impeccable/review/camera-wifi-ui-native/`、`photo-library-native/` 与 `docs/validation/phone-wifi-53-summary.json`。新截图均为 QA，合成凭据不进入产品资源。实体手机覆盖升级与哈希回读已核验。

## 0.9.13-beta · 原图与效果版本

保留已批准 D 布局、三列直播缩略图与云白／石墨色。下载和直播列表按照片归组，多个版本用缩略图右下的深色小角标说明；没有独立展示重复效果卡片。全屏查看器在星标旁增加紧凑的版本按钮，使用原生单选弹窗展示“原图”及实际预设名称。选择持久保留，左右滑动与邻图预览保持一致；按住对比使用严格来源链的真正原图。

删除照片确认包含整组原图、效果与系统相册副本；版本弹窗在效果选中时提供“删除当前效果”，明确原图和其他效果保留。快照读取中不承诺整组删除。保存状态异步核验，保留原有简短按钮尺寸与照片大小信息。

最终 0.9.13-beta / 52、SHA-256 `6a2cda9e068ea9b4a311c49dfbe419d7367014c9cace82b376e8dfc37a6cbac6` 在隔离 Android17 手机模拟器的浅深色各通过 84 项原生 UI／MediaStore 检查；合成 JPEG 与精确 URI 自有清理，既有三文件基线保留。独立收尾复核为限定 SHIP。证据位于 `.impeccable/review/photo-library-native/`，其截图只作 QA，不打包为产品图片。旧 ABC／D 稿和旧版本证据完整保留。未新增发布栅格资源，模板与图标原始溯源不变；真实相机、TalkBack、平板和横屏未据此认证。

后台网络需求另有最终同 SHA 的 Android17 短时 HOME／灭屏 42 项通过，限真实网络 API 加合成 echo；实体手机已保留数据覆盖安装并核验包哈希，真实相机长时间后台传输仍待验收。完整边界见 `dist/nikon-usb-0.9.13-beta-VALIDATION.txt`。

## 0.9.11-beta · 照片直播 D 原生实施

保留云白 / 石墨世界和现有标题、系统蓝及底部导航。照片直播按用户批准的 D 组合稿采用：紧凑项目行 → 固定 284dp 上限主图（左上默认预设、右上放大、底部 76dp 传输浮层）→ 统计 / 接收记录 → 照片工具行 → 三列方形缩略图。传输和错误状态不改变区域高度，选择工具浮于导航上方；预设选择保留原生全屏导航并使用双列效果图 / 名称。原 ABC 和 D 浏览器稿保留。

百分比只表达实际当前原图字节，未知总量不显示假比例；接收之后独立显示保存。自动预设及字节事件局部更新，稳定照片来源键与异步解码保留旧像素至新图就绪，筛选及批选不改变主图。项目菜单支持新建第二项目，跨项目接收及停止后保存注明实际归属。

最终同 SHA 0.9.11-beta / 50 在专用 Android17 手机模拟器的浅深色各通过 297 项；独立收尾复核为 SHIP，限已检查 D 原生 UI 和模拟器交互。实体 PKG110 覆盖升级成功，回读版本 / 安装 APK 哈希一致并正常启动；不将模拟器状态注入或安装证据扩大为真实相机连续传输、锁屏、MediaStore 或物理帧率保证。详细记录：[原生说明](docs/design-review/photo-live-redesign/NATIVE-D.md)、[交付验收](dist/nikon-usb-0.9.11-beta-VALIDATION.txt)。本轮未新增产品图片，QA 风景及预设均为合成且不打包。

## 0.10.1-beta · 社区、版本管理与下载

### Overview

社区和可选更新继续使用云白／石墨、系统蓝及系统无衬线字体。Android 的来源为 [CommunityPanel](app/src/main/java/com/nikonpreset/usbprobe/ui/CommunityPanel.java)、[注册验证](app/src/main/java/com/nikonpreset/usbprobe/ui/AccountPanel.java)及 [UpdatePanel](app/src/main/java/com/nikonpreset/usbprobe/ui/UpdatePanel.java)；Web 来源为 [社区样式](online-library/admin/community.css)、[版本样式](online-library/admin/releases.css)与 [下载页样式](online-library/download/download.css)。本节追加局部组件规则，前述 Android tokens 和相机／直播历史保持其原范围。

### Layout

Android 在线预设页提供“发布预设”和消息入口，设置增加“我的投稿”“消息”“检查更新”，既有三个根目的地保持不变。社区详情按封面、作者／来源、简介、下载、交流排序；顶级评论与 20dp 缩进的回复形成两级，更多评论和更多回复有显式加载操作。投稿编辑使用纵向字段、文件操作、权利确认和审核状态；待审时清楚说明暂不能编辑。

后台桌面侧栏在原有预设、用户管理后增加“社区管理”“App 版本”；所有启用管理员均可访问版本管理。760px 及以下四项横向导航可水平滚动，文字保留、图标隐藏。社区内的五项栏目另行横向排列；700px 及以下内容行换行、规则表单由双列改为单列。版本区先展示上传表单，再展示真实渠道、版本与状态；700px 及以下重排为两列／纵向信息。下载页主内容最大 960px，标题与下载主操作居中，更新说明与二维码在桌面分两列，600px 及以下改为单列，主下载按钮宽度上限 320px。其后三张原生界面预览在桌面为三列、间距 24px；600px 及以下为可横向滚动、逐项吸附的画廊，每项占宽 78%、间距 18px。

### Components

- **注册图形验证：** 原生表单内使用 84dp 高的 PNG 图片区域、12dp 圆角和明确的验证码字段；图片可点击更换，加载、失败和过期使用文字说明。图片只承担图形验证，不当作产品插画。
- **投稿与评论：** 草稿、待审核、已发布、未通过和已下架分别表达；权利复选项最小高 48dp。顶级评论保留作者、正文、时间和回复操作，自有内容提供删除确认，其他内容提供举报。新号未有可见评论时，注册首 24 小时内评论先送审，提交反馈为“评论已提交，审核后展示”；待审内容不显示为已公开成功。已公开评论删除后显示“评论已删除”，从未公开的待审内容删除不生成公开占位。消息目标已删除、隐藏或下架时明确不可用。消息未读数、逐条阅读与“全部已读”使用同一服务端状态。
- **更新面板：** 25sp 标题、48dp 高的渠道入口、24dp 圆角版本卡；下载主操作高 50dp，取消操作高 48dp。检查、下载、校验、完成和错误有独立文字及进度，下载进度原位更新，不重建相机页面。安装前的任务阻拦、断开相机确认和 Android 安装确认保持独立。
- **后台审核：** 14px 圆角列表与 18px 圆角对话框承载投稿、文件、历史修订及审核操作；评论正文保留换行并折行长文本。待审评论提供“通过审核”“不予展示”，分别进入现有 restore／hide 操作和明确确认；已可见／隐藏评论使用隐藏／恢复。状态及处理说明配文字，风控规则保存成功用成功色，错误用错误色；规则变更审计显示前后值。键盘焦点为 3px 操作色外框。
- **版本与下载：** 后台版本表与上传面板使用 20px 圆角，详情对话框为 24px，包名、哈希和证书长串可折行。发布有二次确认，草稿、已上架与已撤回使用真实状态。公网下载页复用 Web 语义色，54px 高胶囊主按钮、明确正式／测试标签、最低 Android 版本、大小和更新说明；校验信息收在可展开区，二维码指向当前渠道的下载页。三张界面预览来自 `community-native/final/light/` 的真实 Java Views QA 截图，分别说明交流、投稿和消息，明确注明“截图内容为演示数据”；保留图片替代文字及各自说明，不将演示评论当作真实用户内容。下载页保留浅深色、键盘焦点、减少动效和高对比偏好。

### Do's and Don'ts

- **Do** 区分游客浏览、登录后投稿／交流、审核通过和公开下载；用作者及来源标签说明官方精选与用户分享。
- **Do** 分开表达下载完成、安装包校验完成、管理员上架和用户确认安装；相机任务进行中有清楚的继续条件。
- **Don't** 将本地合成投稿、评论、账号或发布状态用作真实生产内容或用户手机升级证据。
- **Don't** 将图形验证码写成商业级机器人防护或绝对防刷，也不把截图复核推广为 TalkBack、相机兼容性、下载恢复或安装实测。

独立 UI 复核的 **SHIP 仅限审阅的九张截图**。[Web 操作记录](.impeccable/review/releases-community-ui-qa.json)使用隔离 localhost、内存记录与真实既有 APK 元数据；其中早期测试账号名称不是当前版本管理权限规则。原生候选 `ee597f811e370e42e71e9af5faeca6fc1edbca191a7b0799106c72cbd8308963` 的 [final 证据目录](.impeccable/review/community-native/final/)包含浅色、深色、深色横屏各 41 项合成界面检查，以及独立真实公网验证码 GET／PNG 显示 11 项，SHA 与检查范围不改写为后续包。

最终 55 / `0.10.1-beta` 已发布至 beta，3,257,885 字节、SHA-256 `81021bfc4d7468dd8181d899f9ae5f4a260fbf30b95ac0eefc2be18ffaf9cca6`。新号首评送审及 Android 反馈已实施，独立 [真实更新验收](.impeccable/review/update-native/README.md)覆盖隔离新模拟器 5562 的取消／强停恢复、校验、未知来源授权、系统安装器和正常 Play Protect 扫描后升级至公开 55，并核对包哈希及保留自有私有标记；该证据不由旧截图推断，不涉及用户手机或相机。新增下载画廊的 [生产页面检查](.impeccable/review/download-final/report.json)为游客 GET，覆盖桌面、手机浅深色、三张预览、公开 55 及无页面溢出；不扩展原九图 SHIP。当前网址是 CloudBase 测试域名，首次浏览器访问可能有平台确认，正式商业域名未配置。完整流程、限制、基线来源及证据分类见 [社区与更新说明](docs/community-and-updates.md)。
