# 照片边框原生探针

仅在任务独占、只读Android模拟器运行；脚本固定 `emulator-5582`，不得把它换成用户手机。目标 `cc.huevia` 89 / 0.18.0-beta。

先构建目标App，再执行本目录 `build.ps1`，安装 `.tooling/photo-borders-89/probe/border-probe.apk`。之后 `run.ps1` 逐组执行手机／字号1.3／深色／横屏／平板尺寸，并恢复本轮原始配置。`-Modes phone,landscape` 可只补指定组；总矩阵需结合未重跑配置的同APK原报告，不能把重复记录累加成新场景。

使用真实App的编辑器、绑定View点击、EXIF、Canvas、字体、JPEG与App内批处理。横屏机型确认另外注入实际MotionEvent，证明外层滚动可达应用按钮。旧输入和Logo回调通过保留按钮后重新绑定原图验证；owner测试为白盒属性变更守卫，不是登录服务测试。

`fixtures/reference.jpg` 来源为用户本轮提供的完整白底信息条成品 `codex-clipboard-2fdb5559-f199-4e28-8dbb-3f17a6011c92.jpg`。探针只取其上方照片，生成1500×998 JPEG并写入明确合成的拍摄参数；原图不修改、不会进入生产资产。构造的ExposureTime在TIFF内精确写成1/640，以避免Android框架兼容写入的/10000量化。测试输出在App外部私有目录；没有相机连接或手机相册发布。

实际结果、最终APK身份、截图、主机回归和证据边界见 `docs/validation/photo-borders-89/README.md`。
