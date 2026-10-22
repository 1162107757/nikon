# 74 / 0.13.6-beta 原生在线状态与选择稳定性

最终 APK：`dist/nikon-usb-0.13.6-beta-debug.apk`，3,434,093 字节，SHA-256 `bd8c0516cb8e03c370ebf2d08cf25229296588a5bed4ca9dcb5ec76940c8fa62`。同一包已安装到专用只读 Pixel_9 模拟器 `emulator-5582`，硬件 `ranchu`；没有操作真实手机或连接相机。

| 场景 | 结果 | 实际字号 | 实际 configuration 方向 | 窗口像素 |
| --- | --- | --- | --- | --- |
| phone | PASS，567 次断言 | 1.0 | 1（竖屏） | 1080 × 2424 |
| large-font | PASS，567 次断言 | 约 1.3 | 1（竖屏） | 1080 × 2424 |
| landscape | PASS，567 次断言 | 1.0 | 2（横屏） | 2424 × 1080 |

三组共 1,701 次断言记录，包含逐卡片的重复断言，不代表 1,701 个不同业务场景。每份 `report.json` 记录最终 APK 摘要、实际字号／方向／窗口尺寸、各张截图的方向与像素，以及状态文本的可见矩形和逐行省略数。`restoration.txt` 三组均 PASS，恢复了运行前的字号和旋转设置。

## 本轮改变及验证

无缓存错误简化为“在线滤镜加载失败 · 轻点重试”；缓存提示为“离线缓存 · N 款 · 轻点刷新”。底部与展开面板复用状态方法，完整诊断只在长按提示中提供。源切换、分类、渲染与既有布局不变。

原生测试覆盖默认在线、六个主分类及自定义／未分类、我的预设、同 SHA 本地分类保留、加载、合法零条缓存、无缓存失败、底部和展开层点击重试、恢复 23 条目录，以及队列满时有界等待和自动选中。错误／缓存／空状态每一行省略数均为零，整个状态控件位于可见区域，文字垂直高度完整。

普通选择及队列等待／真实缩略图完成前后，同时检查同一条的子 View 引用、数量、scrollX、每张卡片的局部位置尺寸、屏幕矩形和可见裁剪矩形保持相同。切照片时旧卡片失效。测试支持“不重建、不自动滚动、所测边界坐标稳定”，不构成逐帧录制或硬件刷新率认证。

根线程已对照既有设计图查看手机错误、1.3 字号展开错误、真实横屏错误、分类三列展开图，短提示和圆角层级完整，无需再次改产品或重建。关键图片包括各场景的 `online-no-cache.png`、`online-no-cache-picker.png`、`online-retry-cache-picker.png`、`selection-expanded.png`。独立最终复核结论另见接续审计记录。

## 夹具与可复现来源

原生在线 UI 使用 23 条明确标注的离线目录，配方来自既有真实 NP3 与手工 NCP 测试夹具，逐份核对实际字节摘要；不向产品添加测试后门，不打包测试资产进产品。每个目录保留 `qa-catalog-manifest.json`。此验证不冒充设备实时读取生产 241 条目录；生产目录已在本轮单独匿名只读核对，见 `tests/.build/resume-audit-20261021/preset-public-catalog-summary.json`。

探针 APK SHA-256 为 `f92441f322ddd7894de144b20e465e036210b6b170d2ccecb4876500c0077693`，Java 源码 SHA-256 为 `9ee190391a7d7f9d1680d026632ea83b3b742ee005f0ca59cf67152186f883cd`。每组 `provenance.json` 保留源码、构建脚本、运行脚本和探针 APK 摘要。构建产物位于 `.tooling/photo-editor-categories-74/`。

```powershell
.\tests\photo-editor-device-probe\build.ps1 -ExpectedVersion 74 -Scope selection-polish
.\tests\photo-editor-device-probe\run.ps1 -ExpectedVersion 74 -Scope selection-polish -Mode phone
.\tests\photo-editor-device-probe\run.ps1 -ExpectedVersion 74 -Scope selection-polish -Mode large-font
.\tests\photo-editor-device-probe\run.ps1 -ExpectedVersion 74 -Scope selection-polish -Mode landscape
```

范围限定为本轮在线状态及选择界面的真实 Android Views／现有渲染。没有重新认证真实相机传输、用户手机、TalkBack、平板、持续毛玻璃性能或 NX Studio 色彩匹配。旧 69／70／71／72／73 的专项结果各自保留，没有计入本轮原生数量。
