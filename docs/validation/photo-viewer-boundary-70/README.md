# 相机预览与本地编辑界面边界 · 70

2026-10-20，`0.13.2-beta`（versionCode 70）。最终 APK 为 `dist/nikon-usb-0.13.2-beta-debug.apk`，3,417,709 bytes，SHA-256 `c1872bf77d9cfaa7439211fd86c3fb6a65acd0a5c73607f9325a0962d9ec7cca`。Android 35 构建与 APK v2/v3 签名校验通过。

按用户最新规则，只有相机预览、原图未到手机时，恢复原有全屏查看器：返回、收藏、底部大小与导出、左右切图及预览原因／重试。不创建滤镜编辑器及其在线目录工作。已有完整本地原图时显示滤镜编辑器，判断不依赖相机／下载／直播标签，也不要求先导出一个滤镜效果。下载／直播的已完成本地照片更新后可切入编辑；切回未下载照片时解绑旧编辑器并丢弃旧回调。可见按钮简写为“原图”，按住对比及辅助功能说明保留。

`DownloadedPhoto` 的产品合同为已完成的本地 JPEG，预览缓存另用 `PhotoPreview`，下载中的文件保持 `.part`。UI 在此合同之上检查本地文件存在、非空及 JPEG 扩展名；不把预览或临时文件猜成完整原图，不在 UI 线程重新读取整张原图或计算哈希。编辑器仍在后台核对原图与来源。

## 实际检查

专用 `emulator-5582`，`ranchu`，网络隔离，实际字号 1.0，运行：

```powershell
.\tests\photo-editor-device-probe\run.ps1 -ExpectedVersion 70 -Scope viewer-boundary -Mode phone
```

结果 **PASS，122 项**。[原生报告](phone/report.json)记载的 APK 摘要与最终文件一致；[环境](phone/environment.txt)记录实际字号，脚本结束恢复 1.0。

覆盖冷启动远程预览不创建编辑器／目录、查看器工具显隐与返回、收藏／导出存在、真实预览错误弹窗及重试入口、完整本地照片编辑和“原图”文案、编辑后切回远程时解绑并显示当前预览像素、相机描述与已完成本地文件共存时可编辑、通过实际 `updateViewerStatus` 进行资格升级／降级、空 JPEG 和 `.part` 不进入编辑、完整原图字节不变。

首批截图已核对，无须修复或追加确认轮：

- [未下载照片：原查看器](phone/remote-preview.png)
- [本地完整照片：滤镜编辑与原图按钮](phone/local-editor.png)
- [切回未下载照片：无旧滤镜面板](phone/return-remote.png)
- [相机照片已有完整本地文件：可编辑](phone/remote-saved-editor.png)

## 验证范围

测试使用既有模板生成的实际 JPEG 与类型正确的合成相机对象。错误／重试入口以短时合成连接标志和始终拒绝读取的 `PhotoCameraClient` 触发，未连接相机或接收网络图像；报告明确说明夹具。原生截图、状态切换与原图字节检查成立，不能扩展为真实相机导出或下载协议认证。

本轮未重跑 69 的 951 项三模式及 4,440 项主机检查；它们仍属于 [69 的证据](../photo-editor-categories-69/README.md)。分类数据库未重新迁移。本次结论为 **SHIP，限已验证查看／编辑资格分支与按钮文案**。
