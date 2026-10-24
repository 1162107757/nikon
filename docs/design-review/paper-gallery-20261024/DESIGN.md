---
name: Huevia · 纸白印样
description: 相纸白、墨色与蓝晒青的原生 Android 摄影工作流
status: 待批准 — 本文件是设计基准，不是已实现状态
scope: 预设根页 + token 基建 + 共享底栏
colors:
  paper: "#FBFBFA"
  paper-sunk: "#F2F2F0"
  ink: "#17181A"
  ink-soft: "#46484C"
  ink-muted: "#6E7178"
  hairline: "#E3E3E1"
  accent: "#1B4D8F"
  accent-wash: "#EAF0F8"
  ok: "#2F6B4F"
  error: "#9F2F24"
  on-ink: "#FBFBFA"
  dark-paper: "#141416"
  dark-paper-sunk: "#1D1E21"
  dark-ink: "#ECECEA"
  dark-ink-soft: "#AFB2B7"
  dark-ink-muted: "#7E8188"
  dark-hairline: "#2A2B2E"
  dark-accent: "#7FA8E8"
  dark-accent-wash: "#1C2A3D"
  dark-ok: "#86C3A3"
  dark-error: "#F0998C"
  dark-on-ink: "#141416"
typography:
  display:
    fontFamily: "sans-serif-condensed"
    fontSize: "30sp"
    fontWeight: 700
    letterSpacing: "-0.01em"
  title:
    fontFamily: "sans-serif-condensed"
    fontSize: "20sp"
    fontWeight: 700
    letterSpacing: "-0.01em"
  body-strong:
    fontFamily: "sans-serif"
    fontSize: "15sp"
    fontWeight: 600
  control:
    fontFamily: "sans-serif"
    fontSize: "15sp"
    fontWeight: 500
  meta:
    fontFamily: "sans-serif"
    fontSize: "13sp"
    fontWeight: 400
  data:
    fontFamily: "monospace"
    fontSize: "11sp"
    fontWeight: 400
    letterSpacing: "0.08em"
  nav:
    fontFamily: "sans-serif"
    fontSize: "11sp"
    fontWeight: 500
    letterSpacing: "0.01em"
rounded:
  frame: "2dp"
  control: "4dp"
  round: "full"
spacing:
  scale: "2 4 8 12 16 20 24 32 48"
  gutter: "20dp"
rule:
  hairline: "1dp"
  active: "2dp"
touch:
  minimum: "48dp"
---

# 纸白印样

## 论点

预设库本质上是同一张模板照片经 N 种配方渲染后的平铺对比 —— 这正是摄影师看印样
（contact sheet）挑片的动作。界面做成相纸，照片是内容，技术元信息退成齿孔边的印字。

整套视觉从这个事实长出来，不是从「极简浅色」这个空壳长出来。

## 三个字族，各有职责

| 字族 | 身份 | 承担 |
| --- | --- | --- |
| `sans-serif-condensed` | 仪器刻字 | 页面标题。相机机身与镜头筒的刻字语言（NIKKOR Z 24-70mm f/2.8 S），也是胶片盒的字体。 |
| `sans-serif` | 人话 | 预设名、按钮、说明。与中文 Noto Sans CJK 和谐配对。 |
| `monospace` | 数据印字 | **签名元素。** 所有技术标注：齿孔印字、计数、EXIF。 |

分族解决了一个老问题：`LIBRARY_55` 这类全大写拉丁与中文在同一字级里字重和基线互相打架。
现在它们分属两个明确的register，不再冲突。

## 为什么是这个白、这个蓝

**纸白取近中性微冷 `#FBFBFA`，不是 `#F4F1EA` 那类奶油米白。**
这是个色彩分级工具。暖底会给每一张缩略图叠一层黄偏，用户没法准确判断预设效果。
相纸基色本就是中性偏冷的 —— 这是功能论据，不是口味论据。

**强调色取蓝晒青 `#1B4D8F`。** 蓝晒（cyanotype）是摄影史上最早的印相工艺。
两个实在的理由：样片里主导的是肤色和暖木色，暖色选中态糊在上面认不出来；
蓝在色相上是对立面，压在任何照片上都清楚。

这是单个 token。若改用暖赭，只换 `accent` 与 `accent-wash` 两个值，其余不动。

## 一条贯穿系统的规则

> **激活态 = 2dp 规线。**

来源标签、分类、底栏当前项，三处共用这一个视觉语言 —— 标签与底栏用墨色，分类用蓝晒青。
不再有胶囊底、填充块、杏色贴纸三种各说各话的选中态。后续页面沿用，不得新增第四种。

## 对上一版的作废清单

| 作废 | 原因 |
| --- | --- |
| 橙黄渐变标题区 | 占屏高 18% 只装两个字；且只有预设页有，不成系统 |
| 七个圆角值 14/19/20/22/24/25/26 | 没有节奏，只有「都挺圆」。收敛到 2/4/full |
| 白卡 `#FFFFFF` on `#FAFAF8` | 肉眼分不开，只白白吃掉内边距 |
| 漂在图上的深色药丸标注 | 遮挡内容。并入齿孔印字行，信息一个不少 |
| 「N 个版本」大灰药丸 | 元信息视觉权重压过照片。改 `data` 字阶 |
| 砖红 `#B3432D` + 杏色 `#F4D6C0` + 橙黄渐变 | 三套暖色各干各的。收敛到单一强调色 |
| 主操作双色渐变 | 改纯墨色 |
| 广告语「把喜欢的色彩，带进相机」 | 工具首页不需要。省下 25dp 给内容 |
| 独立的「我的色彩 / N 份」标题行 | 来源标签已说明。计数移到分类行右端，省 44dp |

## 密度收益

首格照片顶边由 `~390dp` 降到 `265dp`；首屏完整可见照片格由 2 个增至 4 个（含部分可见为 6 个）。

## 范围边界

本文件覆盖预设根页、token 基建与共享底栏。同步 / 相册 / 设置三个根页、预设详情、编辑器、
构图、导出弹窗、连接引导均未设计。深色 token 已给出但未做完整对比度矩阵实测。
`index.html` 中的格间色彩差异由 CSS filter 近似模拟，不代表 NCP / NP3 的真实渲染结果。

未批准、未实现、未复核。
