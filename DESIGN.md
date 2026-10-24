---
name: Huevia
description: 橙光、暖白与炭黑的原生 Android 摄影工作流
colors:
  surface: "#FAFAF8"
  container: "#EFEDE9"
  card: "#FFFFFF"
  text: "#262320"
  secondary-text: "#706A63"
  primary: "#B3432D"
  on-primary: "#FFFFFF"
  primary-container: "#F4D6C0"
  on-primary-container: "#85311F"
  outline: "#E4DFD8"
  success: "#42735B"
  error: "#AD3B30"
  action: "#46413B"
  action-end: "#27241F"
  on-action: "#FFFFFF"
  navigation-glass: "rgba(250, 250, 248, 0.9607843137)"
  dark-surface: "#201F1D"
  dark-container: "#33302C"
  dark-card: "#292724"
  dark-text: "#F6F1EB"
  dark-secondary-text: "#BAB1A7"
  dark-primary: "#F3B493"
  dark-on-primary: "#352016"
  dark-primary-container: "#51382D"
  dark-on-primary-container: "#F3B493"
  dark-outline: "#4C4640"
  dark-success: "#9AC8AE"
  dark-error: "#FFB4A7"
  dark-action: "#F0E8DF"
  dark-action-end: "#DAD0C5"
  dark-on-action: "#26211C"
  dark-navigation-glass: "rgba(41, 39, 36, 0.9411764706)"
  photo-accent: "#F3B493"
  photo-track: "#635247"
  photo-control-scrim: "rgba(38, 35, 32, 0.7215686275)"
  editor-canvas: "#141415"
  editor-tools: "#0B0B0B"
  editor-divider: "#292929"
  editor-text: "#F4F5F7"
typography:
  page-title:
    fontFamily: "sans-serif-medium"
    fontSize: "26sp"
    fontWeight: 500
    letterSpacing: "-0.02em"
  back-page-title:
    fontFamily: "sans-serif-medium"
    fontSize: "18sp"
    fontWeight: 500
    letterSpacing: "-0.02em"
  preset-name:
    fontFamily: "sans-serif"
    fontSize: "15sp"
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
    fontSize: "11sp"
    fontWeight: 400
rounded:
  field: "14dp"
  photo: "14dp"
  navigation-selection: "16dp"
  source-pill: "19dp"
  preset-card: "20dp"
  group: "22dp"
  photo-control: "24dp"
  source-track: "25dp"
  button: "26dp"
  fab: "28dp"
spacing:
  gap: "8dp"
  grid-horizontal: "12dp"
  grid-vertical: "14dp"
  page: "20dp"
  touch-target: "48dp"
components:
  primary-button:
    backgroundColor: "{colors.action}"
    textColor: "{colors.on-action}"
    rounded: "{rounded.button}"
    padding: "10dp 16dp"
    height: "48dp"
  secondary-button:
    backgroundColor: "{colors.container}"
    textColor: "{colors.text}"
    rounded: "{rounded.button}"
    height: "48dp"
  input-field:
    backgroundColor: "{colors.container}"
    textColor: "{colors.text}"
    rounded: "{rounded.field}"
    padding: "12dp 14dp"
  category-selected:
    backgroundColor: "{colors.primary-container}"
    textColor: "{colors.on-primary-container}"
    rounded: "{rounded.photo-control}"
    padding: "9dp 14dp"
    height: "48dp"
  source-pill:
    backgroundColor: "{colors.card}"
    textColor: "{colors.text}"
    rounded: "{rounded.source-pill}"
    height: "40dp"
  settings-group:
    backgroundColor: "{colors.card}"
    textColor: "{colors.text}"
    rounded: "{rounded.group}"
  preset-card:
    backgroundColor: "{colors.card}"
    textColor: "{colors.text}"
    rounded: "{rounded.preset-card}"
  navigation:
    backgroundColor: "{colors.navigation-glass}"
    textColor: "{colors.text}"
    height: "68dp"
  photo-control:
    backgroundColor: "{colors.photo-control-scrim}"
    textColor: "{colors.on-action}"
    rounded: "{rounded.photo-control}"
    height: "48dp"
    width: "48dp"
  editor-dock:
    backgroundColor: "{colors.editor-tools}"
    textColor: "{colors.editor-text}"
    padding: "0dp 10dp"
    height: "224dp"
---

# Design System: Huevia

## Overview

**Creative North Star: "橙光进入照片"**

Huevia 的原生 Android Java / Views 采用用户批准的橙光世界：暖白层次、橙杏光晕、炭黑胶囊与系统无衬线字。光晕为发现预设建立气氛，照片仍是内容；编辑时让位于完整比例画布、黑色工具区与杏色控制。

本文件记录 0.17.4-beta（88）的实际实现；本次按用户参考图将手机悬浮底栏四边统一为 5dp 内缩，让首尾选中项与外栏弧线同心、等距，保留 87 的 68dp 基础高度、86 的整项选中态、85 的悬浮位置与内容留位，以及 84 的暖色设计与四个根入口，全面取代原生界面此前的云白／蓝色声明。当前代码权威为 [UiPalette](app/src/main/java/cc/huevia/ui/UiPalette.java) 与 [HueTheme](app/src/main/java/cc/huevia/ui/HueTheme.java)，布局取自 [MainActivity](app/src/main/java/cc/huevia/MainActivity.java)、[LocalAlbumPanel](app/src/main/java/cc/huevia/ui/LocalAlbumPanel.java)、[AlbumPanel](app/src/main/java/cc/huevia/ui/AlbumPanel.java)、[PhotoFilterEditor](app/src/main/java/cc/huevia/ui/PhotoFilterEditor.java) 及其原生面板。批准的 [详细交互稿](docs/design-review/insurance-inspired-20261023/detailed/index.html) 与 [84 需求](docs/design-review/orange-ui-84/REQUIREMENTS.md) 解释意图，发生差异时以本版实际颜色、布局与截图为准。

浅色与深色是完整配对的语义系统，设置中的跟随系统／浅色／深色保持持久化。保留现有 Huevia 标识、代码绘制图标族和有来源的照片模板；本轮新增生产栅格为零。下方 Web 管理后台和旧社区／版本 Web 记录保留独立作用域，不能反向覆盖当前原生规则。

**Key Characteristics:**

- 暖白页面、白色分组与暖石墨深色。
- 橙杏光区只承担预设发现页的光感，照片与真实状态保持清楚。
- 炭黑渐变主动作、杏色选择态与可读的深陶土选中文字。
- 四个根入口、紧凑高度重排、宽屏侧栏和原生 48dp 操作目标。
- 系统 sans-serif 与 sp 字号；编辑工具保留黑底、杏色强调和完整比例照片。

**84 历史验证（不覆盖 85–88 的悬浮导航、选中态与几何变更）：** 最终视觉证据为 [.impeccable/review/orange-ui-84/](.impeccable/review/orange-ui-84/) 的 phone、large-font、landscape、dark、tablet 原生截图，含 presets、settings、live、editor-adjustments、import-slots 和 crop；[实体手机证据](docs/validation/orange-ui-84/physical/) 来自 OnePlus Ace 5 的现有用户数据。最终包 SHA-256 为 `b4a5b10fa5dffcd0850508eb0a7dc4a88cffa47ca5a2b2ed3894dfd2efcbdd03`（3,532,397 字节）。[矩阵](docs/validation/orange-ui-84/matrix.json) 为 PASS、sourcesStable 为 true：手机／1.3 字号／深色／平板各 364，横屏 365，共 1,821 次重复断言执行，非独立场景数量。最终 [修正复核](docs/validation/orange-ui-84/FIX-VERDICT.md) 将 M1–M6 评分为 resolved、remaining clear、disposition ship，范围限六项修正与直接回归；完整首轮见 [FINISH-REVIEW](docs/validation/orange-ui-84/FINISH-REVIEW.md)。实体证据限保数据覆盖安装与只读界面对照，没有相机写入、导出或账号修改；不构成生产网络、真实相机、FPS 或 TalkBack 认证。

早期原生规则仅为历史。旧 Apple 方案及照片编辑演变可查 [旧版完成复核](docs/design-review/apple-glass/finish-review-0.5.2.md)、[80](docs/design-review/photo-basic-adjustments-20261022/REQUIREMENTS.md)、[81](docs/design-review/photo-composition-recipes-20261023/REQUIREMENTS.md)、[82](docs/design-review/photo-filter-navigation-20261023/REQUIREMENTS.md) 和 [83](docs/design-review/local-album-20261023/REQUIREMENTS.md)，不重新累计为 84 的验收。

**89 局部实际落地（2026-10-24）：** [照片边框](docs/design-review/photo-borders-20261024/IMPLEMENTATION.md)沿用以上橙光全局世界与原生黑色编辑区；标题、根导航和全局布局未重设计。三款边框与小机型栏以用户批准的 [v3 稿](docs/design-review/photo-borders-20261024/border-templates-v3.png)及其[批准记录](docs/design-review/photo-borders-20261024/border-templates-v3.json)为方向，实际 Android Views／Canvas 产物为当前局部规则。协调加入的[相册缩略图占位](docs/design-review/thumbnail-placeholder-20261024/IMPLEMENTATION.md)只定义加载／不可用／真实照片状态，不改变边框及全局世界。最终 89 / 0.18.0-beta 本地 APK 的 SHA-256 为 `1f74ece275423198208750b804e7dad8e72c443b881c02eccb85eea9c4cd2ba0`；[边框最终 SHIP](docs/validation/photo-borders-89/FIX-VERDICT.md)只关闭初审两个 P2 及横屏可达性补证，[占位 PASS](docs/validation/thumbnail-placeholder/FINISH-REVIEW.md)保持独立组件范围。边框 282 次重复／配置断言与 76 个产物、占位 150 次重复／配置检查与 9 张 PNG 分别记录，不合并为独立场景；本轮未实体安装或公开发布，没有 HTML／CSS 检测适用面。

## Colors

配色从暖白与石墨中展开，用深陶土指示普通界面操作、杏色表达选择和照片工具强调。上方 frontmatter 为精确原语；Android ARGB 的透明颜色在其中转换为 CSS rgba，避免把 alpha 当成红色。 `.impeccable/design.json` 的色阶为面板按 OKLCH 生成的展示扩展，不是新增的 Android 调色板；HTML 标本将 dp／sp 以 CSS px 近似显示，原生源代码仍为组件行为权威。

### Primary

- **深陶土**（primary）：普通界面的链接、输入／状态强调与槽位选中描边；不再使用系统蓝作为全局操作色。
- **杏色选择底与深陶土选中文字**（primary-container / on-primary-container）：分类胶囊、槽位等小字选中态。深色分别采用暖棕底与浅杏字。
- **照片杏光**（photo-accent / photo-track）：黑色照片工具的滑杆、选中参数、导出和直播进度；轨道保持暖棕。

### Neutral

- **暖白页面、白色内容、暖灰容器**（surface / card / container）：建立页面、分组和字段层次；深色对应暖石墨页面、抬高的深棕灰容器与卡片。
- **炭黑渐变动作**（action → action-end / on-action）：HueTheme 主操作与悬浮加号。深色反转为暖米色渐变与深色文字。社区、更新、RAW 和回执的部分原生面板使用同一 action 角色的实色变体。
- **暖墨正文、暖灰辅助字与细分隔**（text / secondary-text / outline）：稳定的信息层次，深色使用对应浅暖色。
- **轻透导航底**（navigation-glass）：手机 GlassBar 使用暖白／暖石墨染色与条件式背景模糊；不满足材料条件时采用不透明 card。宽屏侧栏沿用轻透实色。
- **照片画布与黑色工具**（editor-canvas / editor-tools / editor-divider / editor-text）：与 App 浅深外观独立，持续服务照片编辑。

成功与错误沿用各自语义角色并配文字。连接状态只来自实际已验证会话；“未连接”“正在读取”“待核对”不能由装饰圆点替代。

**The Native Authority Rule.** 新增原生页面从 UiPalette 和 HueTheme 取值；历史蓝色记录及浏览器原型不再定义本版原生全局样式。

**The Selected Text Rule.** 杏色选择底必须配 onPrimaryContainer，不能直接把 primary 当作小字颜色。 最终独立计算中，浅色选中配对为 6.1986:1、深色为 6.0206:1；这两个结果限相应实色配对。

## Typography

**Display / Body Font:** Android 系统 `sans-serif`；标题和部分标签使用 `sans-serif-medium`，不打包 SF 字体。文字使用 sp，布局与命中区域使用 dp；frontmatter 中的单位是原生单位。

### Hierarchy

- **根标题**：26sp、系统 medium，字距 -0.02em；带返回页与紧凑根标题为 18sp，同样单行并在必要时省略。
- **照片／预设信息**：正常卡片名称为 15sp 粗体，紧凑高度为 13sp 单行；格式与分类为 11sp，设置相机型号为 19sp。
- **正文与操作**：正文通常 14–16sp，主要按钮 14–15sp，来源与分类 13sp，辅助说明 11–13sp。
- **根导航**：11sp，当前目的地加粗；图标和文字共同表达目的地。
- **编辑器**：参数与模式 13–14sp，辅助状态 11–12sp；精确值仍能显式输入，不用缩字代替可读性。

**89 边框局部字形：** 机型选择栏使用系统 11sp 名称与 9sp 自动／手动状态，14dp 相机图形置于完整 48dp 行内；缩小的是图形与文字，操作行不随之缩小。侧栏成品参数使用 Tomorrow ExtraBold Italic（SIL OFL 1.1），仅为参考字体的接近匹配；原参考字体未识别。成品字形按照片比例绘制，设置界面仍使用原生 sp。Nikon 使用有来源的官方字标和黄色方标，富士／索尼／佳能仅用中性品牌文字，不声称官方 Logo。

**The Reachable Action Rule.** 自有主要操作保留至少 48dp 命中区域，紧凑高度通过合并信息、滚动与重排节省空间。 内建系统对话框保持 Android 自身排布；48dp 是本版自有主要操作的设计基线，不宣称所有系统生成子控件都完成逐项测量。

## Layout

根目的地固定为“预设／同步／相册／设置”。“同步”承载相机、照片直播与已下载；“相册”默认 App 内，并列手机来源。详情、导入确认／结果、账号、社区、更新与沉浸照片按实际返回层级显示，编辑时隐藏根导航。系统栏、刘海与输入法安全区由 WindowInsets 处理；对话框关闭及窗口恢复焦点后重新应用系统图标明暗。

- **手机与宽屏**：宽度小于 600dp 使用居中的 GlassBar 悬浮底栏，宽 `min(360, screenWidthDp − 48)` dp，基础高 68dp，按 `68 + round(max(0, fontScale − 1.2) × 24)` dp 增长；底部距 WindowInsets 安全区内缘 14dp。宽度不小于 600dp 保持 80dp 左侧栏，剩余工作区居中且最大宽 720dp。侧栏每项 72dp，选中图标底为 54 × 30dp 的杏色胶囊。
- **内容节奏**：预设标题／来源和设置左右留白通常 20dp，预设网格左右 18dp，列距 12dp、行距 14dp；设置组按 24dp 标题上留白、9dp 标题下留白分隔。设置行最小高 66dp，左图标为 32dp 暖灰底。
- **预设发现**：正常高度保留标题、简短引导、48dp 来源控制、连接卡、分类、真实计数和双列卡片。有效工作区达到 600dp 时三列；非紧凑且字体比例不小于 1.8 时回退单列。正常卡片图高为卡宽的 1.10 倍；卡片不是旧版固定 4:5 封面规则。
- **紧凑高度**：screenHeightDp 小于 480 时，光区由 218dp 收至 168dp，根标题／连接状态合并；隐藏引导与独立连接卡，来源与个人状态同排，收紧分类与计数间隔。列数取 `max(3, effectiveWidthDp / 170)`，图片高 80dp；最终横屏证据显示四张完整卡片及可达加号，不强制所有紧凑设备恰好四列。
- **相册与加号留位**：日期分组、方形缩略图、文件名和真实版本信息保持可见；紧凑横屏把来源与摘要合并并按可用区域重算列数。LocalAlbum 在 `screenWidthDp < 600` 时导航预留为 `104 + round(max(0, fontScale − 1.2) × 24)` dp，宽度不小于 600dp 时保持 `92 + round(max(0, fontScale − 1.2) × 20)` dp；列表另留 8dp、相册加号另留 12dp，授权空态可滚动。预设加号仅在紧凑高度且 `screenWidthDp >= 600` 时距底部 16dp，其余为 `104 + round(max(0, fontScale − 1.2) × 24)` dp。预设列表仅在紧凑高度且 `screenWidthDp >= 600` 时保留 76dp 底部内边距，其余为 `108 + round(max(0, fontScale − 1.2) × 24)` dp，使小于 600dp 的紧凑窗口仍为浮栏留位。
- **照片编辑**：完整比例照片填入可用画布，保留缩放与平移。竖屏工具区高 224dp；横屏使用右侧 300dp 工具区，构图也在横屏采用 300dp 可滚动侧控件。工具换页不跳动画布；滤镜／调节／构图／配方共用稳定入口。

- **89 边框局部布局**：原黑色 176dp 内容区＋48dp 工具导航及分隔线保持不变；五个入口依次为“滤镜／调节／构图／边框／配方”。边框内“样式／信息／外观”使用同一内容区，样式展示当前照片的四个真实缩略图和紧凑机型行；横屏沿用侧工具区，机型选择的应用操作通过实际滚动到达。

## Elevation & Depth

层次主要来自暖白／卡片／容器的色调差、留白与细分隔。HueTheme 卡片在浅色为 2dp elevation，环境／聚光阴影分别为 Android ARGB `#12302B23` / `#18302B23`；深色为 0dp。来源选中胶囊仅 1dp，悬浮加号 4dp；普通主按钮清除默认 elevation 与 StateListAnimator。

预设首页光区由 HueTheme 的缓存线性／径向 shader 叠加并渐隐至页面色，不使用栅格背景、模糊捕获或持续动画。手机悬浮底栏恢复 GlassBar：8dp elevation，浅色环境／聚光阴影为 Android ARGB `#26302B23` / `#30302B23`，深色为 `#40000000` / `#60000000`；0.5dp 细边为暖棕灰 `#18302B23`，深色 `#35FFFFFF`。API 31+ 且非低内存、硬件加速、动画开启、非省电状态时，对独立内容层以 1/6 尺寸采样并施加 18dp 模糊，上限每 100ms 一次；只由来源绘制帧驱动，无常驻定时器。条件不满足、采样失败或无背景时退回不透明 card，卸载释放采样。宽屏侧栏保留轻透实色与右侧分隔。黑色照片工具保持平面；收藏、更多与图标对比使用 photo-control-scrim，最差白底合成后的白色图标对比为 6.2610:1。

页面切换使用 PageTransitions 的可打断入场：普通 220ms、20dp、初始 alpha 0.82；照片 180ms、最多 8dp、初始 alpha 0.65；曲线为 cubic-bezier(0.22, 1, 0.36, 1)。系统动画关闭、视图卸载或取消时恢复完全可见。这里记录源码行为，不声称物理帧率或流畅度已认证。

**The Photograph Rule.** 照片内容保持真实来源与状态；光晕、模拟角标和工具不能冒充真实相机效果或遮掉关键内容。

## Shapes

圆角由角色确定：字段与本地照片 14dp，宽屏侧栏选中图标底 16dp，预设来源选中胶囊 19dp，预设卡片 20dp，设置与相机分组 22dp，照片控制及部分紧凑操作 24dp，来源轨道 25dp，主按钮 26dp，悬浮加号 28dp。手机悬浮根导航的外轮廓与 Rim 使用实际栏高的一半作圆角，整项选中底使用实际单元高度的一半；它们随字号引起的高度变化计算，不再使用固定 34dp／28dp 半径。宽屏保持全高侧栏。

AppIcon 沿用代码绘制的 24dp 画布、1.8 单位圆头／圆角线条；结构图标不换成 emoji。头像与状态点保持圆形。照片控件的衬底跟随命中区域，收藏与更多之间保留 8dp 间隔。诊断界面沿用独立 DiagnosticTheme，不作为消费界面的模板。

## Components

### Buttons

主操作沉稳、可辨：HueTheme 使用垂直 action 渐变、on-action 文字、26dp 胶囊与原生 RippleDrawable；次操作用 container / text。禁用使用 container / secondary-text，任务中不得靠换色制造可继续的错觉。普通自有操作至少 48dp，账号部分主要表单为 52dp。悬浮加号在预设页为 54dp、相册为 56dp，使用相同动作材料；每页只有一个对应主添加入口。

### Chips & source controls

分类以 48dp 目标承载、左右 14dp 内边距，未选为 container / text，选中为 primary-container / on-primary-container 并设置 selected。预设来源外层高 48dp，可见选中胶囊高 40dp、白卡片／深色卡片底；相册 App 内／手机沿用相同卡片选中语言。来源、分类和数量来自实际列表。

### Cards & settings

预设卡片用 20dp 圆角、真实预览、名称、NCP／NP3 格式和分类建立层次；失败保留真实原因与重试，不用随机滤镜补图。设置相机卡与分组为 22dp，行内图标用暖灰小底、文字与尾部箭头／实际值；分隔从文字列开始。RAW 列表、回执、账号、投稿、评论、消息和更新复用语义色、系统字与原生弹窗，保留各自真实状态和业务边界。

### Inputs & dialogs

暖灰容器、14dp 圆角、通常 15–16sp 正文与明确标签。已有表单继续使用 Android 输入法、文件选择、权限和系统对话框；错误显示文字并保留可修正输入。私密字段、提交中禁用、取消与返回保留既有行为。恢复页面时系统栏图标跟随当前明暗表面。

### Navigation

手机悬浮底栏／宽屏侧栏都保留四个有文字的目的地；悬浮材料和尺寸遵循上方布局与深度规则。手机底栏的水平 navigation 填满悬浮容器，四边内边距统一 5dp；四个单元等宽、填满内侧可用高度，单元左右外边距均为 0。单元内的图标与名称保持居中，上下内边距各 4dp、最小高度 58dp，图标槽为 54 × 26dp，11sp 名称上方间距为 2dp。选中项用完整杏色底包住图标和名称，布局后按实际单元高度的一半设置圆角；图标槽没有单独底色。首尾选中项与外栏在左右、上下均内缩 5dp，内外弧线同心。宽屏竖向侧栏保持左右 8dp／上下 6dp 内边距，单元高 72dp、最小高 58dp、左右各 1dp 外边距；仍只为 54 × 30dp 图标槽显示 16dp 圆角杏色底，名称上方间距为 3dp。两者选中图标均为正文色、标签加粗，未选图标和标签为辅助色。设置未读圆点为 8dp，内容说明包含未读数；数字消息胶囊与真实服务端已读状态一致。

### Import slots

原生三列槽位网格展示 C1–C9 或设备实际支持数量；单元最小高 84dp、13sp 最多三行，显示真实占用名称／空槽位，选中为杏底深陶土字和操作色描边。兼容摘要、参数调整、明确目标及覆盖确认仍是独立可读步骤；NP3 的不可写入状态不因新外观改变。

### Photography tools

预览主图分别标注模板模拟、发布者样片与真正照片；来源见 [模板素材记录](docs/assets/preset-template-provenance.md)。详情的只读色调曲线继续读取真实采样，使用当前 primary 与有标签参考线。编辑工具为黑底、浅文字、杏色滑杆与选中参数；13 项调节、精确值、撤销／重做、构图与配方保持真实状态。

HoldToCompare 的文字或图标目标为 48dp，照片上图标态使用共享深色衬底；文字态保留自己的深色半透明胶囊与白色细边。按住显示真正原图，松开、移出、取消、失焦或隐藏恢复效果；键盘／辅助点击为两次点击切换。更改对比状态保持照片变换，不在按下时执行图片 IO。当前功能与代码语义不等于 TalkBack 实测。

### Photo borders · 89

照片保留完整内容，边框在色彩处理和定向构图后向外追加画布；预览、模板缩略图与完整 JPEG 共用按照片比例排版。模糊居中以照片模糊底、圆角原片、下方软影及居中品牌／机型／参数组组成；模糊侧栏以左侧品牌与 F／ISO／S 参数配右侧完整照片；白底信息条保留品牌／机型、黄色 Nikon 方标、分隔、参数与原拍摄时间的层次。照片输出中的模糊和阴影属于边框，不推广为 App 表面材料。

机型默认读取原图 EXIF，手动模式提供尼康／富士／索尼／佳能、机型搜索与自定义；信息页可补填或隐藏机型、焦距、光圈、快门、ISO、拍摄时间，缺失信息自动隐藏。外观提供边距、圆角、阴影、背景模糊与 Logo 选择。手动值只改变边框展示；保留原图字节与现有保存位置／新副本规则。PED4 兼容旧草稿的默认无边框，撤销、重做、草稿、冻结身份和逐张批量均承载独立边框设置。

**The Border Space Rule.** 品牌按真实绘制字形测量并约束在明确槽位内；品牌／机型组合留出间隔，白底品牌不越过分隔或参数，长文字在各自分配范围内缩放。保持 Nikon 官方标志的原始比例，不用中性文字的宽度估算代替真实绘制尺寸。字段、自定义机型及 Logo 子弹窗随当前编辑绑定关闭，旧 owner／绑定代次／原图身份的回调不能写入新草稿。

### Detail, histogram & export · 91

本轮按[已批准技术需求](docs/design-review/photo-detail-export-histogram-20261024/REQUIREMENTS.md)延伸黑色编辑器与杏色控件，保留“滤镜／调节／构图／边框／配方”、系统字体、既有工具区和全局导航。用户随后否定大黑底面板与连体大选择、要求照片更大与小玻璃浮层，对应旧图视觉由[GLASS-BRIEF](docs/design-review/photo-detail-export-histogram-20261024/GLASS-BRIEF.md)覆盖。最终f717151b…实现、三配置原生回归与本轮有界独立修正确认已完成；不以旧功能PASS或独立ship代替用户最终视觉批准。

主编辑入口按用户最新反馈由“细节100%”改为“查看细节”：深色实际照片玻璃承载18dp放大镜和12sp文字，内容宽约91dp，视觉高32dp／触达48dp，贴实际照片左下并有点击反馈，进入真实细节页。图标与文字在浅照片上仍须可读，不以白图标落白底形成看似留白的入口。

细节使用满安全区独立画布，不以大footer占高。实际照片左下锚定约232×55dp缩放palette，仅“适应／100%／200%”三按钮，选中视觉32dp、触达48dp。按用户后续要求，尺寸／倍率拆成照片右上11sp单行小玻璃tag，避开header及顶部216dp比较胶囊；比较保留“当前效果／调色前”，详细说明进入info，定位小窗右下避让palette。100%仍是内容像素与屏幕物理像素1:1，200%不增加真实细节，同构图对比不含外加边框。顶部操作按真实systemBars／displayCutout避让，深底使用白色系统图标，不重复叠加insets。不可恢复历史版本明确“已保存版本／查看完整文件像素”，保留烘焙内容并禁用调色前，不因精简控件而省略限制。

直方图从照片工具开关，通常280×104dp玻璃浮层跟随照片变换；上方有足够留白优先放留白，紧凑横屏可沿照片下缘并避两角操作，保持主体与曲线完整。1.3字号按需约316dp／上限328dp并受可用宽度约束，百分比完整。亮度／RGB以轻下划线表达，暗部／高光为相隔6dp的独立28dp视觉chip、触达48dp。模式线为Ripple背景中的实体LayerDrawable／GradientDrawable层14×2dp、bottomInset12dp（y34–36），非选中透明；清theme tint。旧onDraw已移除，5e…移动线／关闭outline clip仍FAIL，裁切仅未证实假说。背景后清零padding／居中、header关闭baseline对齐，以实际glyph和framebuffer核对。横屏不重复扣剩余高度64dp，info有独立槽位。更新／不可用／零值分别表达，警示仅供诊断。`PhotoGlassDrawable`采样真实照片局部，最长192px后box blur、约65%深tint／35%图色加细rim，不写入照片、1:1数据、统计或导出。

导出页将照片摘要、完整／分享／自定义、实际含框尺寸／JPEG 质量、保存位置／方式、体积说明与主操作放在同一流程；内容可滚动，主要操作固定。紧凑横屏只固定主按钮，低强调的JPEG／NEF原始文件动作随正文滚动，保留参数阅读空间。完整默认质量95，分享长边≤2048／质量90且不放大，自定义640–8192／质量70–100。自定义初值为 min(4096,完整长边)／90，单张不超过完整尺寸，批量按每张完整尺寸限制。质量100不称无损，无可靠预估时写“导出后显示实际大小”。

**The Export State Rule.** “尚未生成”“App 内已有”“手机相册仍存在”“明确缺失”“上次未完成”“暂无法核对”由真实成品与回执核对决定；默认操作随状态变为导出、查看、重新保存或核对继续，未知不等于缺失。非法输入清除核对中文字，禁用主动作说明修正输入。“另存新副本”必须明确选择。主编码成品标明保留拍摄信息、不含定位；独立的“保存 JPEG 原始文件”另行确认忽略编辑与规格且可能保留定位。进度按真实逐项状态显示，进度条仅表达已完成／总数，取消项目不填成成功；进度页使用不透明系统栏。关闭面板与明确停止分开，接受后快照不随继续调图或重绑而变化。

历史矩阵、5be字形失败及[5e模式线失败](docs/validation/photo-image-upgrade-91/glass-5e9688ba-mode-line-fail/)保留原身份，不跨轮累加。最终f717…[当前矩阵](docs/validation/photo-image-upgrade-91/UI-MATRIX.json)phone172／font1.3 172／landscape174，共518次重复／配置断言；每配置三阶段模式线均活动172/216、非活动0，阈值未改、字形无交叠／裁切；1:1对照各1849点最大差0。[最终独立确认](docs/validation/photo-image-upgrade-91/FINISH-REVIEW.md)已将G1–G3及尺寸tag／紧凑入口判resolved、clear、限定ship，只限这些修正及直接回归，非用户最终视觉批准或全App认证。206输入与包／签名身份、夹具／设置／权限恢复及自有模拟器关闭完成。dark eaa143／backend b5 169／progress 0d35 23仍为明确旧SHA，不称最终包全测，详见[实施记录](docs/validation/photo-image-upgrade-91/IMPLEMENTATION.md)。未扩为实体手机、MediaStore发布、相机、tablet、性能或TalkBack认证。

### Live & local album

照片直播保持项目行、主图、真实传输／保存状态、接收记录和缩略图顺序；照片动作与进度采用 photo-accent，track 使用 photo-track，选中操作条横向滚动并留出计数空间。相册使用单一 56dp 加号和 48dp 更多入口，未授权时居中显示“点击获取授权”与简短引导；授权、导入和保存反馈不以静态空卡代替。

### Album thumbnail placeholders · 89

`LocalAlbumPanel`／`PhotoPlaceholder` 保留原方格、14dp 圆角、日期、文件名和选择层。占位从现有 container 向 card 混合 22%，使用 AppIcon 小照片符号，通常 24–28dp、紧凑格最小 18dp；灰线从辅助字色向占位底混合 25%。等待时为静态三点，中央杏色、两侧灰色；无逐格动画、常驻计时器或额外图片解码。失败显示 10sp“暂无预览”，不足以容纳时保留图形与状态描述；沿用更多菜单的刷新入口。

**The Thumbnail State Rule.** 占位图在容器 bounds 内绘制且不声明 intrinsic 尺寸，仅内部符号使用固定 dp，避免 CENTER_CROP 放大导航图形。真实 Bitmap 就绪后仍用 CENTER_CROP 并替换占位，恢复文件／版本描述。加载、不可用、成功和刷新恢复必须反映真实状态，不伪造照片；原 12MiB 缓存、两个 worker、24 个排队位置、失败去重和过期绑定校验保留。

## Do's and Don'ts

### Do:

- **Do** 从 UiPalette / HueTheme 和本版原生截图提取规则，维护浅深色完整对应。
- **Do** 在分类、槽位选择等杏色小字区域使用 onPrimaryContainer，并同时保留选中语义。
- **Do** 为连接、权限、下载、保存、加载和失败提供真实文字状态；圆点与颜色只作辅助。
- **Do** 保留 sp 字号缩放、系统返回、WindowInsets 安全区、48dp 主要操作与完整比例照片。
- **Do** 保留现有标识与模板溯源；模板模拟、发布者样片和用户照片分别标识。

- **Do** 将边框品牌／机型／参数约束在各自槽位，原图内容、字节和自动／手动来源保持清楚。
- **Do** 以固定小占位符号表达真实加载或不可用状态，就绪后显示真实 CENTER_CROP 照片。

### Don't:

- **Don't** 把旧原生蓝色或原型示例数据当作当前实现；恢复悬浮导航时仍使用现有暖色 tokens 与真实四个入口。
- **Don't** 用橙色铺满所有表面；编辑画布与工具保持黑色材料，局部操作使用杏色。
- **Don't** 用无底白图标直接覆盖任意照片；收藏、更多和图标对比使用共享照片控件衬底。
- **Don't** 将模拟、下载、导入读回、实际拍摄与已发布／已安装混称同一种成功。
- **Don't** 把重复断言次数或限定 UI 复核写成真实相机、生产网络、FPS、颜色标定或 TalkBack 认证。

- **Don't** 将参考字体近似匹配或其他品牌的中性文字称为相同字体／官方 Logo，也不把边框材料推广为全局材料。
- **Don't** 用会被 CENTER_CROP 放大的导航图形或设计稿切片代替原生占位，也不将不同专项的重复断言相加为独立场景。

**以下既有作用域原文保留：** Web 管理后台继续独立；末尾 0.10.1 节的 Android 描述与证据仅属于 55 历史版本，其云白／系统蓝和三个根目的地不适用于当前原生系统。混合记录中的 Web 社区、版本与下载指导保留原范围。

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

## 0.10.1-beta · 社区、版本管理与下载

**历史混合记录（55 / 0.10.1-beta）：** 以下原文的 Android 色彩、导航与界面证据只记录该历史版本；当前原生规范以本文件前八节为准。其中 Web 社区、版本管理与下载页的独立指导原文保留。

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
