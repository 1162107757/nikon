# 已审核占位图的原生实现

用户批准 v1 设计后实施，模式为 Operate。生产范围仅 `ui/PhotoPlaceholder.java` 和 `ui/LocalAlbumPanel.java` 的占位绑定／状态描述。图片方格、14dp圆角、日期分组、文件名、选择覆盖层及真实图片布局沿用现有实现。

## 画面与状态

- 占位底由 `container` 向 `card` 混合22%，浅色约 `#F3F1EE`，深色约 `#312E2A`；不使用设计稿位图或虚假照片。
- 小照片符号复用 AppIcon 的 PRESETS 图形，通常24–28dp；紧凑方格可缩至18dp。灰线在 `secondary` 与占位底间混合25%，保留轻量留白。
- 等待／加载：三点静态提示，两侧灰点、中间杏色点。无逐格动画、常驻计时器或额外解码工作。
- 预览不可用：同一小图形与10sp“暂无预览”。文案随系统字号缩放；若小方格容不下则隐藏文字，保留图形及tile状态描述。沿用原有更多操作中的刷新入口。
- 已就绪：真实Bitmap替换占位，继续使用 CENTER_CROP。可访问描述在占位时说明“预览加载中”或“预览暂不可用”，成功后回到原有文件／版本信息。

## 关键约束

PhotoPlaceholder不声明intrinsic尺寸，ImageView直接使用容器bounds绘制；仅内部图形使用固定dp尺寸，从而避免旧ALBUM/INFO导航图标被CENTER_CROP放大。无需切换真实照片的scaleType或padding。

保留12MiB缓存、两个图片worker、24个排队位置、pending／failed去重、刷新清理失败状态、imageEpoch与view tag校验，以及原有45ms批次刷新。未改照片原始字节、权限、导入／导出、相机或边框业务。

## 并行分工

已与“梳理机型边框需求”任务确认独占文件。边框任务统一版本、生产构建、全局DESIGN／PRODUCT和最终APK；本任务只使用独立emulator-5586与占位专项验证目录。此文件不是整个合包的测试或发布证明；最终原生状态和边界见 `docs/validation/thumbnail-placeholder/`。
