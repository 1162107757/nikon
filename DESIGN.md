---
name: 相机预设
description: 原生安卓预设管理、相机导入与无线相册
colors:
  primary: "#D9001B"
  on-primary: "#FFFFFF"
  surface: "#FFFFFF"
  container: "#F5F6F7"
  text: "#202124"
  secondary-text: "#686D75"
  primary-container: "#FFE8EB"
  outline: "#D9DCE1"
  success: "#21633C"
  error: "#B3261E"
  dark-primary: "#FFB3BF"
  dark-on-primary: "#4D0011"
  dark-surface: "#151618"
  dark-container: "#242629"
  dark-text: "#F1F2EC"
  dark-secondary-text: "#BDC2BC"
  dark-primary-container: "#48252C"
  dark-outline: "#8B938B"
  dark-success: "#99DBB0"
  dark-error: "#FFB4AB"
typography:
  page-title:
    fontFamily: "sans-serif"
    fontSize: "25sp"
    fontWeight: 700
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
  secondary-page-title:
    fontFamily: "sans-serif"
    fontSize: "20sp"
    fontWeight: 700
rounded:
  control: "8dp"
  preview: "9dp"
  information-card: "10dp"
  guide-step: "12dp"
spacing:
  content: "16dp"
  gap: "8dp"
  icon-target: "48dp"
components:
  primary-button:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.control}"
  secondary-button:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.primary}"
    borderColor: "{colors.outline}"
    rounded: "{rounded.control}"
---

# Design System: 相机预设

## Overview

沿用用户授权实现的最新红白目录与相册设计。照片帮助选择内容，操作就近说明它改变的是手机文件还是相机中的预设。普通流程使用“预设”“下载到手机”“导入相机”等词语，技术诊断留在辅助入口。

本文件按 0.2.1-beta 的 Java / Views 源码和真机截图同步。用户指出 0.2.0-beta 与设计图偏差明显后，本版保留既有红白方案，修正标签、导航、按钮、卡片和引导层级。最终包已覆盖安装，手机安装包与交付 APK 的 SHA-256 一致；根页、详情与引导截图已从最终包重捕。应用采用系统原生 Views 和手绘矢量图标，未引入 MDC Material 3 组件库。字号、主题及横屏的具体检查范围与截图版本见 [界面实现记录](docs/ui-implementation-review.md)。

## Colors

UiPalette 是色彩角色的代码来源。浅色使用纯白背景、浅灰容器、深灰正文和红色主操作；深色使用独立角色。选中的来源标签和底栏目的地只改变文字、图标及下划线颜色，不使用整块粉色背景。粉色容器仅用于状态提示与按下反馈。成功结果使用红色圆底白勾和明确文字；连接成功可用绿色状态点，失败和待核对状态也有文字。品牌色固定，不从壁纸动态取色。

按当前色值计算，浅色正文对白底为 16.10:1，次要文字对白底为 5.21:1、对浅灰容器为 4.81:1，主按钮白字对红底为 5.29:1。这些是指定文字角色的静态结果，不代表整机无障碍验收或所有边框、状态均已通过检查。

## Typography

使用安卓系统 sans-serif 与常规/粗体；相册按钮使用 sans-serif-medium。所有文字采用 sp，外部留白和命中区域采用 dp。根页面标题 25sp，带返回的二级页面标题 20sp，预设详情及结果正文标题 24sp，列表名称 18sp，来源标签 15sp，正文通常 14–16sp，辅助文字 11–13sp。按钮文字 15sp，紧凑列表操作 12sp。

## Layout

稳定的“预设 / 相册”两目的地，只在根页面显示全局底栏。预设详情、导入确认、导入结果、照片详情及无线引导隐藏底栏，以返回箭头完成层级导航；帮助入口仅位于根页。页面使用系统栏、刘海与输入法安全区；横屏和宽屏内容及导航有界，最大 720dp。

首页顺序为标题、“在线预设 / 我的预设”、USB 灰栏与手机添加、照片列表。左右边距 16dp，顶栏按 48dp 图标触区加上下各 8dp 留白组成。来源标签采用 47dp 文字区与 2dp 选中下划线；底栏等宽单元最小高 60dp，上下外留白 3dp / 4dp，另有 1dp 顶部分隔及系统安全区，图标和文字各自居中。

首页样片宽高比为 1.68:1，详情为 1.25:1，按当前内容宽度计算高度；缺少真实样片时占位保持同样比例。USB 灰栏包含 25dp 图标、两行状态和红色添加按钮；小于 360dp 或字体比例大于 1.25 时分行。列表采用 ListView，顶部内容随列表滚动。相册内为“相机照片 / 已下载”，采用自适应 GridView，104dp 目标列宽和 4dp 间隔。

相册可用高度小于 500dp 时，空态隐藏大幅示意图，收紧至左右 16dp、上下 12dp 留白，标题 18sp、说明 13sp，并保留完整可见的连接按钮。最终包的横屏空态已在手机截图中检查；这不代表有照片网格、多选或传输状态也已完成横屏验收。

## Elevation & Depth

通过实色表面、细描边、分隔和留白分组。所有消费界面按钮清除默认 elevation、translationZ 和 StateListAnimator，避免系统 Button 生成凸起阴影。保护决策与兼容参数编辑可使用系统对话框；主要确认和结果使用完整页面。

## Shapes

主按钮和普通描边按钮为 8dp 圆角，预设图片 9dp，信息卡 10dp，无线步骤卡 12dp。列表紧凑描边操作为 24dp 圆角、最小 80dp 宽 / 48dp 高；紧凑实红添加按钮为 7dp 圆角。图标由 AppIcon 绘制：24dp、1.8 单位圆头线条。图标触区为 48dp，普通主按钮最小高 52dp。高级诊断保留独立暗色 DiagnosticTheme，以匹配其原有界面。

## Components

- 预设条目：图片优先，底部名称、简短说明与描边“查看 / 下载”操作并列；缺图与加载失败有明确占位。
- 预设详情：大图、名称、灰底文件来源与 USB 兼容信息、文字形式的真实样片关联入口，底部单一下载或导入主按钮。示意图中的概念照片不打包进应用。
- 导入确认：灰底预设摘要包含 80 × 88dp 缩略图，相机信息、52dp 描边空槽选择器及灰底兼容摘要依次分组。“查看并调整”是文字入口，详细参数按需查看，最终按钮命名目标槽位。
- 导入结果：成功居中展示 80dp 红底白勾、槽位与名称，按导入源 SHA-256 查找对应预设的可用样片，尺寸为 200 × 160dp，随后是读回核对信息与相机使用说明。失败与结果未确认保留真实状态及恢复动作，未确认时先只读核对。成功页不再放置显著的导出报告按钮。本轮未接相机，该页面属于源码核查范围。
- 相册：区分相机缩略图与实际已下载文件，支持多选任务、实际进度、部分完成及取消剩余。
- 无线连接：相机与手机连接线稿、三块最小高 120dp 的灰底步骤卡、28dp 红色数字标记与代码绘制的小示意图。“查看已下载”灰条、描边 Wi-Fi 设置按钮及红色读取按钮提供下一步，地址设置收进文字入口；小于 360dp 或字体比例大于 1.2 时隐藏步骤右侧示意图，让文字使用更多宽度。
- 交互反馈：系统按钮语义和 RippleDrawable；加载显示实际阶段，有总量才显示百分比。

## Do's and Don'ts

- 沿用色彩角色与统一矢量图标；不要用 emoji 充当结构图标。
- 关联的真实样片才展示为预设参考，不挪用概念图或其他预设效果。
- 在线库未配置、暂无本地文件、无线未连接时都应给出可执行的下一步。
- 下载到手机不等于导入相机；导入成功也不等于已自动选择或证明拍摄效果等效。
- 已查看默认字号的最终包页面及相册横屏空态；1.3 字号首页、引导滚动、深色首页与深色引导来自同轮中间包，明确保留版本范围。真机截图验证布局与可见状态，不替代真实样片、相机导入或无线下载验证。
