---
name: Huevia · 橙光交互提案
description: 仅约束本目录浅色浏览器原型的已实现设计规格。
colors:
  board: "#e7e5e2"
  paper: "#fafaf8"
  white: "#fff"
  ink: "#262320"
  muted: "#706b66"
  orange: "#c44730"
  peach: "#f6cfb7"
  line: "#e2dfdb"
  editor-canvas: "#232322"
typography:
  display:
    fontFamily: '"Segoe UI", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "32px"
    fontWeight: 500
    lineHeight: 1.35
    letterSpacing: "-.03em"
  title:
    fontFamily: '"Segoe UI", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "26px"
    fontWeight: 500
    letterSpacing: "-.04em"
  body:
    fontFamily: '"Segoe UI", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "14px"
  label:
    fontFamily: '"Segoe UI", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "10px"
rounded:
  field: "12px"
  thumbnail: "19px"
  capsule: "24px"
  camera-panel: "26px"
  sheet: "29px"
  phone: "38px"
spacing:
  grid-tight: "6px"
  control-gap: "8px"
  card-padding: "16px"
  camera-padding: "18px"
  content-padding: "19px"
  sheet-padding: "20px"
  board-gap: "30px"
components:
  button-primary:
    textColor: "{colors.white}"
    typography: "{typography.label}"
    rounded: "{rounded.capsule}"
    padding: "0 15px"
  input-text:
    backgroundColor: "{colors.white}"
    textColor: "{colors.ink}"
    rounded: "{rounded.field}"
    padding: "10px"
---

# Design System: Huevia · 橙光交互提案

## Overview

视觉方向为「橙光进入摄影日常」：把用户固定的 [Ghani Pradita / Paperpillar Insurance UI 参考](https://dribbble.com/shots/26673273-Insurance-Mobile-App-UI-Design)转译为预设发现、照片浏览与相机管理。橙红光感集中在发现页，柔白层叠卡片承载信息，炭黑胶囊承载主要动作。

本文件从 `index.html`、`preview.css`、`preview.js` 提取，只约束本目录独立提案；不构成全局品牌确认或 Android 生产规格。界面为浅色，照片编辑页保留深色工作画布，未提供深色主题切换。照片、名称、数量、连接与效果均为演示；状态只保留在当前页面会话。

## Colors

- `board` 是实际评审画板底色，`paper` 是手机与抽屉底色，白色用于卡片；`ink`、`muted` 分别承载主要与辅助信息，`line` 用于细分隔线。页脚参考色样仍含 `#EBEBEB`，不可据此替换实际画板色。
- `orange` 用于焦点、滑块、选中标记和局部强调；`peach` 用于文字选区与参考色样。分类选中态实际为 `#f4d8c6 / #78301e`，连接状态胶囊为 `#f4ddc9 / #6c432a`。
- 主操作使用 `linear-gradient(#46433e,#262421)` 配白字。编辑画布使用 `editor-canvas`，上方标题与状态栏为白色，下方控件保持 `#f8f7f4`。
- 首屏光感由多层 CSS 径向与线性渐变生成，包含灰紫、橙红、杏色与淡奶油光区；它是发现页背景，不是所有页面的统一填充。

## Typography

全界面使用系统无衬线字体栈，不加载外部字体。拉丁品牌字标收紧字距；中文标题使用较轻字重，照片名称与数字保持克制。

- 评审标题：32px / 500 / 1.35，字距 `-.03em`；手机页标题 26px / 500，字距 `-.04em`。
- 首屏主文案：26px / 500 / 1.55；内容分组标题 16px / 550；预设名称 12px / 550。
- 默认正文 14px；手机说明多为 9–11px，较长描述为 11px / 1.9。标签与操作多为 9–10px，底部导航为 9px。这些值描述当前评审画布，不代表原生移动端字号标准。
- 收藏数量为 29px / 450；数量和滑块输出启用等宽数字。图标默认为 20px、1.6px 线宽的内联 SVG，按组件缩放。

## Layout

评审外框最大宽 1280px，默认内边距 `28px 44px 24px`。默认三列 `minmax(0,356px)`，间距 30px；手机画布宽度上限 356px、高 746px。三台手机分别从预设库、相册、设置进入，每台独立保存导航与交互状态。单屏模式仅显示第一台，仍可独立访问四个导航入口。

手机内部内容区独立纵向滚动，底部保留 76px 固定导航；状态栏高 38px。主内容左右通常留 19–20px，预设为两列、13px 间距，相册为三列、6px 间距。分类与滤镜条支持横向滚动；详情和编辑子页标题吸顶。

| 视口 | 实际响应行为 |
| --- | --- |
| ≥1400px | 外框顶部 34px，三屏间距 42px |
| ≤1000px | 外框 23px，列间距 18px；多屏手机高 720px、圆角 30px，内部字号与边距收紧；单屏高度仍为 746px |
| ≤760px | 改为单列堆叠，最大宽 356px、间距 32px；手机高 746px、圆角 36px；恢复多数手机内部尺寸；预览切换器置于引言下方 |
| ≤390px | 外框 16px，手机高 728px；引言和少量标签继续收紧；单屏模式仍保留 746px 高度 |

## Elevation & Depth

层次由暖灰底、柔白表面、轻阴影和少量透明模糊共同建立。普通卡片阴影为 `0 6px 14px #302b2312, 0 2px 4px #302b2307`；手机外壳为 `0 25px 40px -22px #332d2445, 0 1px 0 1px #ffffffb3`。

收藏卡使用半透明白色渐变、14px 背景模糊与内高光；底部导航使用近白透明底与 18px 模糊。抽屉遮罩为 `#26232050`、4px 模糊，抽屉位于手机内部的最高交互层；提示条位于导航上方。深度服务于层级，不扩散到每张照片。

## Shapes

手机外壳使用大圆角裁切；卡片通常为 22–27px，胶囊操作为 24px，预设照片为 19px，相册缩略图为 13px。抽屉只保留 29px 顶部圆角，编辑控件区为 28px 顶部圆角。小状态点、头像与圆形图标按钮保持圆形。分隔线细而低对比，照片以容器裁切形成边界。

## Components

- **按钮**：主要动作最小高 36px、左右 15px；详情与抽屉主操作增至 43px。次要按钮为白底轻阴影；详情次操作改用暖灰底。悬停统一亮度 `.97`，按下位移 1px；按钮、链接和输入框的键盘焦点为 3px 橙色描边、偏移 3px。
- **分段与分类**：来源和照片来源采用灰底轨道、白色选中胶囊；分类采用杏色选中态。仅改变当前手机状态，不联动其他手机。
- **照片卡与相册**：预设图高 113px，附分类／格式标签与双语名称；相册封面高 169px，底部渐变保护文字；三列照片为正方形，选择模式显示橙色勾选圆点。
- **相机与设置**：白色圆角分组、49px 最小行高、左图标与右侧状态／箭头；相机对象使用 CSS 渐变和 SVG 图标，不依赖产品实拍图。
- **编辑器**：上方照片画布、下方浅色控件；滤镜缩略图选中后显示橙色描边。强度为 0–100，曝光／对比度／饱和度为 -50–50；用 CSS filter 即时示意。按住或键盘按下比较键可看原图，松开恢复；从同步进入编辑后返回同步，从相册进入则返回相册。
- **抽屉与提示**：抽屉最大高 85%，内部滚动，含标题、关闭按钮与拖动柄视觉提示；点击遮罩或 Escape 关闭，Tab 焦点在抽屉控件内循环。命名输入框最小高 42px、12px 圆角；文件选择仅展示本地选择。提示条使用深色半透明底、17px 圆角，约 3.4 秒消失。
- **动效**：提示进入与照片悬停使用 180ms、`cubic-bezier(.16,1,.3,1)`；开启减少动态效果偏好时禁用动画、过渡与平滑滚动。

## Do's and Don'ts

- **Do** 延续橙红光区、柔白卡片、炭黑动作与照片主导的比例，按现有页面角色使用。
- **Do** 复用现有六格摄影 atlas，并保留 [素材溯源](assets/provenance.md)、JSON 提示词与 PNG 元数据。
- **Do** 保留演示说明、未连接状态、下载前置条件和 NP3 机型支持提示；检查结果另见 `review/VALIDATION.md`。
- **Don't** 把 CSS 调色描述为经校准的 Nikon 预设效果，或把模拟连接、导入与导出描述为真实设备操作。
- **Don't** 从本原型推断已通过生产无障碍验收、Android 适配或全局品牌审批；不在此文件引入未实现的主题、组件或交互。
