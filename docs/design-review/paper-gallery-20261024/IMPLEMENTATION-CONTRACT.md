# 纸白印样 · 预设页实施契约

本文件是 **Codex / 实施者的执行依据**。设计基准为同目录 `index.html`（1 CSS px = 1 dp）。
所有数值以本文件为准；`index.html` 与本文件冲突时，以本文件为准。

**范围**：仅「预设」根页 + 四件套设计 token 基建 + 共享底栏。
**不在范围**：同步 / 相册 / 设置三个根页、预设详情、编辑器、构图、导出弹窗、连接引导。
**状态**：待用户批准。批准前不得修改 `app/src/`。

---

## 0. 先决条件

| 项 | 说明 |
| --- | --- |
| 禁止引入依赖 | 不得加入 AndroidX、Material、任何第三方库。沿用纯 `android.widget` + 手写 Drawable。 |
| 禁止新增 XML layout | 本项目 `res/` 下无 `layout/`，保持现状，全部用 Java 构建 View。 |
| 禁止改动业务层 | `album/`、`usb/`、`ptp/`、`ncp/`、`library/`、`connection/`、`account/` 一行不动。 |
| 禁止改动已有行为 | 来源切换、分类筛选、分页加载、预览按需渲染、下拉刷新、长按删除、悬浮加号动作全部保持现有逻辑。 |
| 字体 | 只用系统自带：`sans-serif-condensed` / `sans-serif` / `monospace`。不得打包字体文件。 |

---

## 1. 新建两个 token 类

### 1.1 `app/src/main/java/cc/huevia/ui/HueMetrics.java`（新建）

集中间距、圆角、线宽、触区。所有返回 **px**，构造时传 `Context`。

```
间距（dp）：S2=2  S4=4  S8=8  S12=12  S16=16  S20=20  S24=24  S32=32  S48=48
页边距     ：GUTTER = 20
圆角（dp）：R_FRAME = 2      图片、面板、任何承载内容的方块
            R_CONTROL = 4    输入框、按压态底、图标按钮
            R_ROUND = -1     哨兵值，表示全圆（实现时取 height/2），全局仅悬浮加号使用
线宽       ：RULE = 1dp（至少 1px，density 低时不得为 0）
触区       ：TOUCH_MIN = 48  任何可点元素的最小命中区，视觉可以更小
```

**旧圆角值 14 / 18 / 19 / 20 / 22 / 24 / 25 / 26 全部作废**，不得在新代码中出现。

### 1.2 `app/src/main/java/cc/huevia/ui/HueType.java`（新建）

字阶。提供 `apply(TextView, Role)`，一次设置字族、字号、字重、字距、行高。

| Role | 字族 | 字号 | 字重 | 字距 | 用处 |
| --- | --- | --- | --- | --- | --- |
| `DISPLAY` | `sans-serif-condensed` | 30sp | BOLD | -0.01em | 根页标题 |
| `TITLE` | `sans-serif-condensed` | 20sp | BOLD | -0.01em | 二级页标题（本轮不用，先定义） |
| `BODY_STRONG` | `sans-serif` | 15sp | 600 | 0 | 预设名、列表主行 |
| `CONTROL` | `sans-serif` | 15sp | 500 | 0 | 来源标签、按钮 |
| `META` | `sans-serif` | 13sp | 400 | 0 | 分类、次要说明 |
| `DATA` | `monospace` | 11sp | 400 | **+0.08em** | 齿孔印字、计数、EXIF ← **签名元素** |
| `NAV` | `sans-serif` | 11sp | 500 | +0.01em | 底栏标签 |

实现要点：
- 字重 600 在无 variable font 的 Android 上用 `Typeface.create("sans-serif-medium", Typeface.NORMAL)` 近似；
  700 用 `Typeface.create("sans-serif-condensed", Typeface.BOLD)`。不要用 `setFakeBoldText`。
- 字距用 `setLetterSpacing(em)`（API 21+，minSdk 26 安全）。
- **字号一律 `COMPLEX_UNIT_SP`**，必须跟随系统字号缩放；1.3 倍字号下所有文字不得被裁切。

---

## 2. 改写 `UiPalette.java`

逐字段替换为下表。**保留现有字段名和构造签名**，让调用方零改动；
`primaryContainer` / `photoAccent` / `photoTrack` / `glass` / `rim` 等旧字段保留但重新取值，不得删除（其他页面仍在用）。

| 字段 | 浅色 | 深色 | 用处 |
| --- | --- | --- | --- |
| `surface` | `#FBFBFA` | `#141416` | 纸白底 |
| `container` | `#F2F2F0` | `#1D1E21` | 凹面（输入框、按压态） |
| `card` | `#FBFBFA` | `#141416` | **与 surface 同色**：白卡取消，卡片不再是独立色层 |
| `text` | `#17181A` | `#ECECEA` | 墨 |
| `secondary` | `#6E7178` | `#7E8188` | 淡墨（齿孔印字、说明） |
| `primary` | `#1B4D8F` | `#7FA8E8` | 蓝晒青，**唯一强调色** |
| `onPrimary` | `#FBFBFA` | `#141416` | 强调色上的文字 |
| `primaryContainer` | `#EAF0F8` | `#1C2A3D` | 青底，极少量选择底 |
| `onPrimaryContainer` | `#1B4D8F` | `#7FA8E8` | |
| `outline` | `#E3E3E1` | `#2A2B2E` | 发丝线 |
| `success` | `#2F6B4F` | `#86C3A3` | |
| `error` | `#9F2F24` | `#F0998C` | |
| `action` / `actionEnd` | `#17181A` / `#17181A` | `#ECECEA` / `#ECECEA` | **取消渐变**，主操作为纯墨色 |
| `onAction` | `#FBFBFA` | `#141416` | |
| `glass` | `#FBFBFA` | `#141416` | 底栏改为实色，详见 §5 |
| `rim` | `#E3E3E1` | `#2A2B2E` | 底栏描边 = 发丝线 |
| `photoAccent` | `#1B4D8F` | `#7FA8E8` | 与 primary 统一，不再独立 |
| `photoTrack` | `#E3E3E1` | `#2A2B2E` | |

**对比度要求（必须实测）**：`secondary` 对 `surface` ≥ 4.5:1；`primary` 对 `surface` ≥ 4.5:1；
`text` 对 `surface` ≥ 7:1。浅深色各测一次，写进验证记录。

### 暖赭对照变体（备选，不默认启用）

若用户改选暖色，**只换两个字段**，其余不动：
`primary` = `#8C4A2F` / `#E8A98B`，`primaryContainer` = `#F7EDE6` / `#3A2A20`。

---

## 3. 改写 `HueTheme.java`

| 方法 | 变更 |
| --- | --- |
| `action(Context, UiPalette, float)` | 去掉 `GradientDrawable.Orientation.TOP_BOTTOM` 双色渐变，改纯色 `p.action`。圆角参数一律传 `HueMetrics.R_CONTROL`。 |
| `rounded(int, float)` | 保留。 |
| 新增 `hairline(UiPalette)` | 返回 1dp 高、`p.outline` 色的 Drawable，供分隔线使用。 |
| 新增 `frame(UiPalette)` | 图片容器背景：`R_FRAME` 圆角 + 1px 内描边（浅色 7% 墨 `#12171 81A`→即 `0x12171 81A`，写作 `0x12`+`171 81A` 的 alpha 7%；深色 9% 白 `0x17FFFFFF`）。**不使用投影。** |
| 新增 `activeRule(UiPalette, boolean accent)` | 2dp 高规线，`accent=true` 取 `p.primary`，否则取 `p.text`。 |
| 删除 | 所有 `EditorGlassDrawable` / `PhotoGlassDrawable` 以外的毛玻璃用法不在本轮范围，保持原状。 |

---

## 4. 预设页布局：改写 `MainActivity.renderHome()`（现 762–831 行）

### 4.1 纵向尺寸表（dp，自上而下，无压缩态）

| # | 区域 | 高 | 上间距 | 构成 |
| --- | --- | --- | --- | --- |
| 1 | 状态栏 | 32 | — | 系统，内容从 insets 下方开始 |
| 2 | 标题行 | 48 | 12 | 左：`DISPLAY` 的「预设」；右：刷新图标按钮 48×48 触区，视觉 22dp |
| 3 | 发丝线 | 1 | 12 | 左右内缩至 `GUTTER`（20），不是全出血 |
| 4 | 来源标签 | 40 | 16 | 「在线」「我的」，`CONTROL` 字阶，间距 24 |
| 5 | 状态行 | 20 | 16 | `DATA` 字阶，单行；内容随标签切换 |
| 6 | 分类行 | 32 | 20 | 横向可滚动；右端固定「N 份」计数 |
| 7 | 印样网格 | — | 16 | 首格照片顶边落在 **265dp** |

**验收硬指标**：393×881dp 手机、字号 1.0、浅色下，首格照片顶边 y = 265dp ± 4dp；
首屏（底栏上沿以上）完整可见照片格 ≥ 4 个。当前实现为 ~390dp / 2 个。

### 4.2 逐区规格

**标题行** — 删除第 777 行的 `intro`（「把喜欢的色彩，带进相机」）。
工具首页不需要广告语，省下的 25dp 给内容。页面标题文字保持「预设」。

**来源标签** — 删除第 779 行的胶囊容器（`shape(..., 25)` 的 `tabs` 背景）与 `padding`。
改为两个纯文字按钮，激活态 = `text` 色 + 600 字重 + 底部 2dp 墨色规线（规线宽度 = 文字宽度，
不是整列宽）；非激活 = `secondary` 色 + 500 字重，无规线。触区仍 ≥ 48dp 高。
文案由「在线预设 / 我的预设」缩短为 **「在线 / 我的」**（上方标题已说明这是预设页）。

**状态行** — 删除第 785–789 行的 `cameraState` 卡片（`shape(colors.card,18)` + 44dp 行 + chevron）。
改为单行文字：
- 在线标签：`● 在线目录 · N 款` + 右端 `刷新 ›`
- 我的标签：`● 仅保存在本机` + 右端 `登录后同步云端 ›`（已登录则显示同步状态，复用 `personal.status()` 的实际文案）
圆点 5dp；已连接取 `success`，未连接取 `secondary`。右端链接取 `ink-soft`，整行 `DATA` 字阶。
**相机连接入口不删除**，移入该行右端或保留在设置页 —— 以现有可达性为准，不得让用户失去入口。

**分类行** — 删除 `addPresetCategoryChip` 的胶囊底与填充。
改为纯文字：激活 = `primary` 色 + 600 字重 + 2dp 蓝晒青规线；非激活 = `secondary` + 400。
间距 20dp。排序逻辑（ALL → SUGGESTED → 自定义 → 未分类）完全保留。

**计数** — 删除第 807–809 行整个 `resultHeading`（「我的色彩 / 发现预设」+「N 份」独立一行）。
标题冗余（来源标签已说明），计数移到分类行右端，`DATA` 字阶，`secondary` 色。
计数必须反映**当前筛选后**的数量，`catalogHasMore` 时仍显示 `N+`。

**印样网格** — `GridView` 保留，参数调整：
| 项 | 旧 | 新 |
| --- | --- | --- |
| 水平间距 | 12 | 12 |
| 垂直间距 | 14 | **24**（行距大于列距，读作一行行的印样） |
| 左右 padding | 18 | **20**（= `GUTTER`，与上方所有区左对齐） |
| 列数逻辑 | 保留现有 compact / ≥600dp / fontScale 判断 | 不变 |

### 4.3 印样格（`PresetAdapter` 的 item）

```
┌─────────────────────┐
│                     │  照片：宽度填满列，比例 4:3
│       photo         │  圆角 R_FRAME(2)，1px 内描边，无投影
│                     │  ← 取消漂在图上的「模板模拟」深色药丸
└─────────────────────┘
  ↕ 8
LIBRARY_55              ← BODY_STRONG 15/600，行高 20，单行截断
  ↕ 2
NCP · 胶片 · 模板模拟     ← DATA 11sp 等宽 +0.08em，行高 16，单行截断
                           secondary 色。这是签名元素。
```

- **取消外层白卡**：item 根容器背景透明，照片直接落在 `surface` 上。省下的内边距给照片。
- **齿孔印字组成**：`格式 · 分类 · 状态`，中间用 ` · ` 连接，缺项跳过不留空点。
  状态位放原本的「模板模拟」「样片」「待校验」等标注 —— 信息一个不少，图上零遮挡。
- 多选/批量选中态：照片内描边换成 2dp `primary`，预设名变 `primary` 色。不加勾选图标、不加蒙层。
- 按压态：整格 `ripple`，圆角 `R_FRAME`。

---

## 5. 共享底栏（跨四个根页，需单独确认）

两个变体，`index.html` 顶部可切换对比。**默认实现「贴底」**，除非用户明确要悬浮。

### 变体 A · 贴底（纸白体系的自洽答案）
- 高 64dp 栏体 + 系统手势区，贴屏幕底边，无圆角无投影
- 顶边 1dp `outline` 发丝线
- 四等分；图标 23dp，标签 `NAV` 字阶，间距 4dp
- 激活 = 图标与标签取 `text`，**顶边中央 28dp 宽 2dp 高的 `primary` 规线**
- 非激活 = `secondary`，无规线
- **取消杏色圆角填充块**

### 变体 B · 悬浮（85–88 的既有偏好）
- 左右各内缩 12dp，底部 14dp，高 64dp，圆角 18dp
- 1dp `outline` 描边 + 极轻投影（y=8, blur=26, 13% 墨）
- 其余同变体 A，规线位置下移至距顶 6dp

两个变体都需要 `GlassBar` 停用毛玻璃采样、改实色 —— 纸白底上毛玻璃无可采样内容，
纯属性能浪费。保留 `GlassBar` 类与其降级逻辑，只把材质改为 `p.glass` 实色 + `p.rim` 描边。

### 悬浮加号
56dp 圆形，`R_ROUND`；填充 `action`（纯墨），图标 `onAction`。
右 20dp；贴底变体距底 96dp，悬浮变体距底 92dp。投影 y=6 blur=18 22% 墨。
**全局唯一的圆形元素。**

---

## 6. 一条贯穿系统的规则

> **激活态 = 2dp 规线。**

来源标签、分类、底栏当前项，三处用同一个视觉语言表达「当前在这里」——
标签与底栏用墨色，分类用蓝晒青。不再有胶囊底、填充块、杏色贴纸三种各说各话的选中态。
后续铺开到其他页面时沿用这一条，不得新增第四种选中表达。

---

## 7. 验收

### 7.1 必须复现的状态

预设页：在线正常 / 在线离线缓存 / 在线加载失败可重试 / 在线分页加载中 / 我的为空 /
我的已登录同步中 / 我的未登录 / 分类筛选后为空 / 长名称截断 / 不支持模拟的预设占位。

### 7.2 必须跑的配置矩阵

phone 1.0 字号 · phone 1.3 字号 · 深色 · 横屏 · ≥600dp 平板，五配置各出原生截图。

### 7.3 硬指标

| 指标 | 要求 |
| --- | --- |
| 首格照片顶边 | 265dp ± 4dp（393×881dp / 1.0 字号 / 浅色） |
| 首屏完整照片格 | ≥ 4 |
| 触区 | 所有可点元素实测 ≥ 48dp，含分类文字与来源标签 |
| 1.3 倍字号 | 无文字裁切、无重叠、无横向溢出；来源标签保持单行 |
| 对比度 | `secondary`/`primary` 对 `surface` ≥ 4.5:1，`text` ≥ 7:1，浅深色各测 |
| 圆角 | 全页实际使用的圆角值只有 2 / 4 / full 三种 |
| 回归 | 来源切换、分类筛选、分页、下拉刷新、长按删除、加号、预览按需渲染行为不变 |

### 7.4 不可省略

来源与分类的既有排序规则、`catalogHasMore` 的 `N+` 表达、缺网时的同源缓存提示与重试、
不支持手机模拟的预设仍可打开详情并删除、相机连接入口的可达性。

---

## 8. 交付物

1. 五配置原生截图，含上述全部状态
2. 首格照片顶边与触区的实测数值（不是设计值）
3. 浅深色对比度实测表
4. 更新 `DESIGN.md` 与 `.impeccable/design.json` 的 token
5. `docs/validation/paper-gallery-<版本号>/README.md`，按本项目既有格式记录 APK 字节数、SHA-256、
   断言数与范围边界；模拟器证据不得表述为实机结论

未复核、未记录即未完成。
