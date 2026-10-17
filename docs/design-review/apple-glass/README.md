# 相机预设 · Apple 风格视觉提案

打开 `index.html` 查看交互设计稿。所有资源均为本地文件，不需要依赖安装或外部 CDN。也可在项目根目录运行：

```powershell
python -m http.server 8766 --bind 127.0.0.1 --directory docs/design-review/apple-glass
```

然后打开 http://127.0.0.1:8766/ 。宽屏并列展示预设库、相册和设置；窄屏通过页面上方的标签切换查看三个页面。每台示意设备内均有“预设 / 相册 / 设置”底部导航，照片沉浸查看从相册点入。

相机连接集中在“设置”：当前相机、断开连接、Wi-Fi 地址与端口、USB 连接，以及在线预设库地址、帮助和关于。预设库与相册顶部仅显示小圆点和“已连接 / 未连接”，不再放置连接卡片或连接方式入口。

三台示意设备共享本次页面中的演示连接状态。在设置中断开连接，其他页面的状态随之更新；选择 Wi-Fi 可演示照片浏览与预设导入，选择 USB 仅演示预设导入，此时相册的相机照片页提示需要 Wi-Fi，已下载照片仍可查看。刷新或“重置预览”会恢复默认示例状态，输入地址不会发起网络请求。

还可体验：浅色/深色外观、未配置在线库与初始空态、预设详情、相册来源切换、多选、点图查看、轻点显隐、双击缩放和原图保存演示。所有数据均为设计示例，不执行真实连接、导入或文件下载。

本目录保存获批时的独立网页提案，交互仍为本地演示。原生 Android 实现现已在 0.4.0-beta 落地并安装到手机；正式实现和验证范围见项目根目录 README 与 `dist/nikon-usb-0.4.0-beta-VALIDATION.txt`。网页截图不能代替原生截图或相机实测。

本轮设置架构图片：

- `preview-settings-light.jpg`：浅色的预设库、相册、设置三屏。
- `preview-settings-dark.jpg`：深色三屏。
- `preview-settings-mobile.jpg`：390px 窄屏设置页。
- `preview-wifi-settings.jpg`：从设置打开的 Wi-Fi 地址与端口抽屉。

此前的 `preview-light.jpg`、`preview-dark.jpg`、`preview-mobile.jpg`、`preview-sheet.jpg`、`preview-empty.jpg` 和 `segmented-update.jpg` 为前序方案记录，不代表本轮设置架构。实际复核范围与结果见 `VALIDATION.md`。

Atlas 摄影素材为 AI 生成概念图，来源与生成提示词见 `assets/provenance.md`。这些照片不能作为任何预设的实际效果证明。
