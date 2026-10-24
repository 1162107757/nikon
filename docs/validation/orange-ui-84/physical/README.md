# 一加 Ace 5 实机界面检查

这些图片来自已保数据覆盖安装的 84 / 0.17.0-beta，1080×2376、现有 408dpi、字号 1.0。横屏为同一设备临时旋转后的 2376×1080。所有 PNG 是 `adb exec-out screencap -p` 原始输出，无像素编辑。检查使用用户已有预设、相册及直播项目，不向手机植入测试数据。

| 文件 | 状态 |
| --- | --- |
| [presets.png](presets.png) | 临时浅色；我的预设、16 份真实记录及现有模板图 |
| [preset-detail.png](preset-detail.png) | 已有 Kodak Gold 100 NCP，只读打开 |
| [sync.png](sync.png) | 相机照片，未连接 |
| [live.png](live.png) | 已有项目与照片，暖色动作与照片上控件 |
| [album.png](album.png) | App 内已有内容 |
| [editor.png](editor.png) | 初次进入，在线滤镜仍在加载；不作为稳定加载证据 |
| [editor-adjustments.png](editor-adjustments.png) | 已有 JPEG 的稳定调节页 |
| [crop.png](crop.png) | 构图界面，未应用变更 |
| [editor-after-crop-cancel.png](editor-after-crop-cancel.png) | 系统返回取消构图后的稳定编辑页 |
| [settings.png](settings.png) | 临时浅色设置 |
| [settings-system-dark.png](settings-system-dark.png) | 原“跟随系统”深色设置 |
| [presets-restored-system-dark.png](presets-restored-system-dark.png) | 外观恢复后预设页 |
| [landscape-system-dark.png](landscape-system-dark.png) | 系统深色横屏，四列首行可见；拍摄后旋转已恢复 |
| [installed-final.png](installed-final.png) | 最终停留预设页，跟随系统深色 |

[device-restoration.json](device-restoration.json) 记录了已安装包完整哈希、版本、进程及最终设置。USB 常亮已关闭回原值，30 分钟息屏未改；外观、旋转、同步来源已恢复。截图含用户已有内容，仅作本地对照证据；本轮未公开分享或上传这些图片。

没有操作实体相机、导出照片、改变编辑参数或应用裁剪，也没有测试真实网络目录、账号写操作、FPS、TalkBack 或显示颜色准确性。
