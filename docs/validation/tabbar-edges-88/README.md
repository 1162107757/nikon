# 88 / 0.17.4-beta · 首尾选中胶囊等距

根据用户附图修正悬浮tabbar的两端：选中块与外框上、下、外侧统一5dp，圆角同心。四个tab等宽满高，不再叠加首尾margin；外栏圆角为实际栏高的一半，选中底为实际单元高的一半。保留68dp基础高度、大字号适配、图标＋名称一起选中、当前暖色和14dp底部安全间隔。竖侧栏保持原样，没有增加产品栅格资源。

最终 [`huevia-0.17.4-beta-debug.apk`](../../../dist/huevia-0.17.4-beta-debug.apk)：3,532,397字节，SHA-256 `c2c69e5eb9b288cc285c9fd22a1ea4da22abccdd182fcfcfdbf3d92cd4294bf7`。手机当前未连接，88未覆盖安装到实体手机或公开发布。

[report.json](report.json) 记录最终APK在任务专用只读模拟器上的实际点击和几何测量。手机／实际字号1.3／深色，各捕获首项“预设”和末项“设置”，共6张原始ADB截图、6个XML几何态。六态的选中块上、下、外侧间距均为 **13 / 13 / 13px**，约5dp；栏高179px（68dp）、大字号184px（70dp）。四导航目标为229–230×153–158px，均≥48dp，名称完整包含在单元中。

截图在 [phone](phone/)、[large-font](large-font/)、[dark](dark/)，原图来源和完整哈希见 [screenshots.json](screenshots.json)。独立 [FINISH-REVIEW](FINISH-REVIEW.md) 对照用户参考图及六组截图／XML，结论为本次几何微调 scoped SHIP，无剩余材料问题。临时字号、旋转及夜间模式已恢复；当前MainActivity与采集时源码指纹一致，安装包完整SHA一致。

本轮仅编译／签名并验证导航几何，没有重跑完整图库或协议套件；85／86／87证据保留各自SHA，不累计为88测试。横屏／平板侧栏只作源码未变确认，不声称88的这些设备完成动态测试。没有实体手机、相机、实时网络、FPS、色彩标定或TalkBack认证。任务专用模拟器收尾关闭，见 [emulator-cleanup.json](emulator-cleanup.json)。
