---
name: Huevia · 橙光功能细化稿
description: 从本目录浏览器实现提取的浅色设计规格；不替代原生实施与验证。
colors:
  board: "#e7e5e2"
  paper: "#fafaf8"
  card: "#fff"
  fill: "#efede9"
  ink: "#262320"
  muted: "#706a63"
  line: "#e4dfd8"
  accent: "#c44730"
  peach: "#f6cfb7"
  success: "#42735b"
  danger: "#ad3b30"
  editor-canvas: "#1c1c1b"
  editor-tools: "#101010"
typography:
  display:
    fontFamily: '"Segoe UI", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "25px"
    fontWeight: 500
    letterSpacing: "-.03em"
  preset-title:
    fontFamily: '"Segoe UI", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "26px"
    fontWeight: 550
    letterSpacing: "-.04em"
  title:
    fontFamily: '"Segoe UI", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "23px"
    fontWeight: 550
    lineHeight: 1.35
    letterSpacing: "-.035em"
  body:
    fontFamily: '"Segoe UI", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "12px"
    lineHeight: 1.9
  label:
    fontFamily: '"Segoe UI", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "11px"
rounded:
  field: "13px"
  photo: "14px"
  chip: "18px"
  preset-photo: "19px"
  card: "22px"
  button: "26px"
  sheet: "28px"
  phone: "38px"
spacing:
  control-gap: "10px"
  grid-gap: "12px"
  card-padding: "17px"
  content-edge: "20px"
  section-gap: "22px"
components:
  button-primary:
    backgroundColor: "linear-gradient(#46413b,#27241f)"
    textColor: "{colors.card}"
    rounded: "{rounded.button}"
    padding: "0 20px"
  button-secondary:
    backgroundColor: "#eeebe5"
    textColor: "{colors.ink}"
    rounded: "{rounded.button}"
    padding: "0 18px"
  input-text:
    backgroundColor: "{colors.card}"
    textColor: "{colors.ink}"
    rounded: "{rounded.field}"
    padding: "10px 12px"
  chip-selected:
    backgroundColor: "#f4d6c0"
    textColor: "#77311e"
    rounded: "{rounded.chip}"
    padding: "0 13px"
  card:
    backgroundColor: "{colors.card}"
    rounded: "{rounded.card}"
    padding: "{spacing.card-padding}"
  navigation:
    backgroundColor: "#fafaf8f0"
    textColor: "{colors.ink}"
    height: "78px"
    padding: "8px 12px 15px"
---

# Design System: Huevia · 橙光功能细化稿

## Overview

**Creative North Star: "橙光进入摄影日常"**

延续用户固定的 Paperpillar 保险界面参考与上一版橙／白／灰视觉世界：橙光吸引进入预设，柔白分组承载功能，炭黑胶囊承接主要动作，照片保留主体位置。本次改变页面密度与功能完整性，不重新选择品牌方向。

本规格只描述 [index.html](index.html)、[design.css](design.css)、[design.js](design.js) 的独立浏览器预览：四个根入口、22 个路由、75 个可选状态。功能及原生位置以 [FUNCTION-MAP.md](FUNCTION-MAP.md) 为准，后续实施以 [IMPLEMENTATION-CONTRACT.md](IMPLEMENTATION-CONTRACT.md) 对照。当前是浅色设计；黑色编辑／浏览／构图工作区属于照片工具。原生现有深色和跟随系统选项仅记录为后续适配，未制作对应主题。

**Key Characteristics:**

- 紧凑橙光标题、白色功能分组、真实来源层级与明确状态反馈。
- 照片优先；编辑工具贴边固定，切换模式保持照片画布位置。
- 评审目录、状态选择和源码说明位于手机之外，不进入消费者界面。

## Colors

暖灰画板托起柔白手机，橙红与杏色用于局部方向提示，墨色承担内容和主要动作。

- **Primary：** `accent` 用于选择标记、进度与强调，`peach` 用于文字选区。主要按钮实际采用 frontmatter 中的炭黑渐变，不能因强调色是橙红就改成全橙按钮。
- **Neutral：** `board` 为评审外框，`paper` 为手机和抽屉，`card` 为白色功能表面，`fill` 为轻填充；`ink`、`muted`、`line` 区分正文、辅助信息和分隔。
- **状态：** 成功使用低饱和绿，错误使用暖红；提示同时保留图标、标题或原因。状态块按组件使用对应浅底和深字，不把 token 当作所有提示的统一填色。
- **照片工作区：** `editor-canvas` 承载原比例照片，`editor-tools` 承载底部控件，模式栏另用近黑（`#090909`）。这是工作空间层次，不是全局深色主题。
- **橙光标题：** CSS 多层径向／线性渐变由灰紫、橙红、杏色与奶油光区组成；仅用于预设首页及相接状态栏。

## Typography

全界面使用 frontmatter 的系统无衬线字体栈，无外部字体。评审标题、预设标题和普通根页标题分别使用对应角色；子页标题为较紧凑的（16px），分组标题为（15px / 550），卡片名称为（13px / 550）。默认页面基线为（14px / 1.5），长说明使用 `body`，辅助内容多为（9–11px），底部导航为（10px）。图标为代码内联 SVG，默认（21px）、圆端圆连接线（1.65px）。

这些小字号描述当前评审画布，不能直接认作原生字号验收结论。原生需另核对设备 dp、字号放大、换行和可读性；本稿没有宣称完成全套颜色／对比度测试。

## Layout

桌面体验区最大宽（1304px），三列为目录（194px）／手机（至少390px）／规格（278px），列距（36px），左右留白（36px）。目录与规格可粘住浏览器视口；消费者手机内部独立滚动。总览由四个真实根页渲染函数生成，随容器宽度按 `width / 390` 缩放整屏，不维护另一套静态页面。

手机设计空间实际为（390 × 844px），外壳使用 `phone` 圆角。状态栏（38px），常规顶部栏（64px），根导航（78px）；主内容左右通常使用 `content-edge`。带操作栏时，最终 CSS 将有导航内容底部预留为（166px），无导航为（98px），避免操作遮盖内容。预设双列、照片三列方图；分类、滤镜与参数条横向滚动。

预设页把标题、副文案和在线／我的来源收进紧凑渐变区；连同状态栏约占顶部（180px）。后续连接状态、分类、数量与双列预设进入首屏，不恢复首版大面积收藏统计卡。

| 视口 | 当前代码行为 |
| --- | --- |
| ≥1450px | 三列改为（212px／至少390px／292px），间距（55px），最大宽（1390px）。 |
| ≤1120px | 三列改为（165px／390px／至少200px），间距（22px）；总览改两列。 |
| ≤930px | 目录和手机保留两列，最大宽（700px）；状态与规格移至下方两列，解除粘住。 |
| ≤620px | 页面改纵向；目录变横滑条，规格置手机下方；手机宽为（390px）与可用宽度较小值，高仍为（844px），圆角（30px）；总览单列。 |

编辑器隐藏根导航：顶部状态栏及操作区共（96px），画布延伸至底部工具上缘；工具 CSS 总高（225px）= 内容（224px）+ 顶部分隔（1px）。滤镜、调节、配方共用此几何。原生横屏右侧（300dp）是实施合同中的待接入要求，浏览器没有实现横屏原生适配。

## Elevation & Depth

层次依靠暖灰底、白色表面、克制软阴影及少量半透明材质。卡片共用 `0 5px 14px #302b230a, 0 2px 5px #302b2305`；手机外壳为 `0 25px 40px -22px #3a302850, 0 1px 0 1px #ffffffb0`。主要按钮使用内高光和短阴影，照片本身不重复加浮层。

导航背景模糊（16px），固定操作栏模糊（12px）；抽屉遮罩为 `#24201d66`，模糊（4px）。抽屉覆盖手机内部，最高（90%）并独立滚动；提示条位于其上。提示进入（160ms），照片滤镜示意过渡（80ms）；减少动态效果偏好会关闭动画、过渡和平滑滚动。

## Shapes

卡片与分组使用 `card` 圆角，照片方格使用 `photo`，预设封面使用 `preset-photo`。胶囊按钮、来源分段、分类、小标签采用递减曲率；抽屉只保留上方 `sheet` 圆角。头像、悬浮加号和选择标记为圆形。图像由容器裁切形成边界，设置分组内部使用细分隔线。

## Components

| 组件 | 视觉与实际功能约束 |
| --- | --- |
| 按钮、字段 | 常规主要／次要动作最小高（48px），图标按钮常规（48px），悬浮加号（54px）；编辑器与紧凑控件另有较小视觉尺寸，原生触区须按合同核对。字段最小高（46px），白底细描边；错误留在表单。全局悬停亮度（.96）、按下下移（1px）、键盘焦点（3px 橙色描边，外偏3px）；禁用透明度（.42）。 |
| 来源、分类与导航 | 灰色分段轨道配白色选中项，分类配杏色选中态。四根入口为预设、同步、相册、设置；同步保留相机／直播／已下载，相册保留 App 内／手机。根页切换保留连接与直播状态。 |
| 预设与导入 | 双列封面带分类和 NCP／NP3 格式标签；详情含模板、原图比较、只读曲线与讨论。导入使用三列 C1–C9 槽位；占用、选中和覆盖警示可区分。NCP 下载后读取能力再确认；NP3 相机导入明确禁用；不确定结果只读核对。断线后经 Wi-Fi／USB 演示连接会回到槽位读取。 |
| 照片、选择与权限 | 日期分组、文件名和三列缩略图构成浏览主体；选中用橙色勾选圆点。部分授权只展示可访问照片，全选也只覆盖该范围；未授权、撤销、读取失败有继续入口。PNG 工作副本确认与原图／已保存效果／草稿版本保持独立。 |
| 固定照片工具 | 黑底滤镜区保留四宫格、在线／我的、完整分类和强度；调节保留13项、精确输入、帮助、撤销／重做；配方保存滤镜与参数，不含构图。相机小预览只有浏览和下载完整原图入口，不能直接进入调色。 |
| 构图与保存 | 构图在独立黑色暂存页中显示比例框和三分线；原比例、自由、1:1、4:3、3:2、9:16会更新框体几何，支持90°旋转与±45°拉直示意。取消丢弃暂存，应用记一步撤销。导出明确 App 内／手机目标，同版本复用；额外副本需明确勾选，任务与重试沿用冻结目标。 |
| 状态与系统衔接 | 直播把接收、保存、效果处理分开，记录与任务提供失败项重试；设置容纳连接、账号、投稿、消息和更新。底部抽屉承接确认、选择、精确输入和错误；系统权限、Wi-Fi、USB、QQ与安装流程只展示衔接位置。 |

照片统一复用 [photo-atlas.png](../assets/photo-atlas.png)：（1536 × 1024px），三列两行六张概念摄影，以（300% × 200%）背景裁切。完整提示与生成信息保存在 [JSON元数据](../assets/photo-atlas.png.json) 和 [provenance.md](../assets/provenance.md)；不证明 Nikon NCP／NP3 真实渲染。图标源是 `index.html` 中的 SVG symbols 与本目录 `icons.svg`，相机造型由 CSS／SVG 绘制。

## Do's and Don'ts

- **Do** 沿用已固定的橙光、柔白分组与炭黑动作；继续细化时同时保留真实来源、能力限制和失败恢复。
- **Do** 以本目录实现、功能对照及实施合同交叉核对；原生交付须另提供实际设备、字号与流程证据。
- **Don't** 把状态演练、源码说明或演示夹具写进消费者手机界面。
- **Don't** 将浏览器 CSS 滤镜、演示账号／连接／任务当作后台、系统、相机协议或真实照片渲染的复现。
- **Don't** 将此文件理解为原生已完成、深色已适配或全部可访问性已验证。最终有界复核为 SHIP，确认部分授权全选、比例裁剪框与重连读取三处修正；该结论仅适用于本浏览器预览范围。
