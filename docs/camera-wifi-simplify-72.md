# 相机 Wi-Fi 连接简化（72 / 0.13.4-beta）

本轮与相册刷新任务共享工作区并划分文件责任：连接任务负责 MainActivity 的 Wi-Fi 流程、CameraWifiSession / CameraWifiNetwork；相册任务负责 AlbumPanel / PullRefreshLayout、版本号和唯一的产品 APK 构建。设备验收按独占窗口串行执行，避免覆盖安装或网络状态相互影响。

## 使用流程

- 手机已经连接 Wi-Fi：直接观察并复用当前物理 Wi-Fi，随后继续原有相机协议握手，无需重新输入网络名称或密码。
- 尚未连接：显示“连接相机 Wi-Fi”，主操作“选择相机 Wi-Fi”打开系统选网；返回后最多检查 12 秒，连接可用即自动继续。结果码为取消、返回数据为空，也以实际网络状态为准。
- 从手机设置自行连接后，可以点“我已连接，继续”。未检测到网络时保留界面并给出提示。
- “手动填写”保留为 Android 10 及以上的备用入口，使用原有系统指定网络申请。只有此入口申请附近设备／旧系统定位权限；密码不写入偏好或视图保存状态，关闭表单即清空。

现有 Wi-Fi 并不证明它属于相机。实际相机握手仍负责识别设备，普通家庭 Wi-Fi 不产生相机连接成功状态。系统选网可能首次要求输入热点密码，由 Android 管理后续已保存网络。

## 连接生命周期

`CameraWifiNetwork.connectedWifiNetwork()` 优先使用实际 PTP 通道所在的物理 Wi-Fi，再使用当前默认的物理 Wi-Fi，最后使用唯一的现有物理 Wi-Fi（允许蜂窝网络作为默认上网通道）。排除 VPN；多个不明确的通道或网络不猜测目标。

`CameraWifiSession.useConnectedWifi()` 返回 0 表示没有安全候选，无回调和路由副作用；正 token 表示已建立观察请求。仅选定 Network 的 `onAvailable` 可以续接，失联时原有路由守卫阻止重试落到另一个同地址路由器。

复用请求与 WifiNetworkSpecifier 的独立标记确保原有 CameraConnectionService / CameraWifiRetention 机制继续按实际 PTP 通道维持需求。界面取消只撤销自身尚未完成的请求，不断开健康的运行时会话。系统面板返回动作与轮询以界面 epoch 隔离，取消、完成或销毁时清理；不会后台无限尝试连接。

## 验证范围

连接后端主机测试：519 项保持机制、27 项 socket 目标、37 项网关／本机地址、604 项会话测试通过；MainActivity 与实际 Android API 隔离编译通过；实际运行时 Wi-Fi 失联 13 项测试通过。独立代码审查未发现待修复缺陷。

72 / `0.13.4-beta` 的 **9053 验证候选**已完成 Wi-Fi 专项四轮原生验收，全部 PASS，共 **216 项**。四份报告记录的已安装 APK SHA-256 均为 `9053c6b32eec3750c13da46800b03fb0e7517c55c4d04c78626bb10c8f585721`，版本均为 versionCode 72 / versionName `0.13.4-beta`，独立只读复核确认一致。此次 216 项仅归属于该哈希。

统一构建任务已确认 [72 / `0.13.4-beta` 最终交付 APK](../dist/nikon-usb-0.13.4-beta-debug.apk)：SHA-256 `e563135f99857a19938d3a49ef7fceeab587250a843fd43dde8ed0ff161202b7`，3,425,901 字节；独立只读复核已核对实际文件哈希与大小。相对 9053 验证候选，修改范围仅为 AlbumPanel 相机分页 footer 的横屏定位与 padding，Wi-Fi 源码未变。

e563 交付包的相册专项原生验收 phone 136、large-font 136、landscape 155 均 PASS，共 427 项，三份报告均记录 e563，见 [相册验收记录](validation/photo-album-refresh-72/README.md)。Wi-Fi 的 216 项实测仍仅对应 9053 候选，原始报告和哈希完整保留，不将其改标为 e563 的测试结果。

| 场景 | 外观 | 通过项数 | 9053 候选报告 |
| --- | --- | ---: | --- |
| 无现有 Wi-Fi | 浅色 | 79 | [disconnected-light](../tests/wifi-simplify-device-probe/evidence/72/disconnected-light/report.json) |
| 无现有 Wi-Fi | 深色 | 79 | [disconnected-dark](../tests/wifi-simplify-device-probe/evidence/72/disconnected-dark/report.json) |
| 复用现有 Wi-Fi | 浅色 | 29 | [connected-light](../tests/wifi-simplify-device-probe/evidence/72/connected-light/report.json) |
| 复用现有 Wi-Fi | 深色 | 29 | [connected-dark](../tests/wifi-simplify-device-probe/evidence/72/connected-dark/report.json) |

独立原生探针位于 [tests/wifi-simplify-device-probe](../tests/wifi-simplify-device-probe/README.md)，仅允许 emulator-5582 的 ranchu / goldfish 硬件。实际覆盖无凭据选网、手动表单无效输入校验与密码清理、取消与过期回调、取消结果码／空返回数据后的有界检查，以及未授予附近设备／定位权限时复用精确的现有 Network。续接使用 spy，不启动 PTP 握手，也不提交有效的手动联网申请。

初轮 `manual-empty-ssid-validation` 失败源于探针未等待 AlertDialog 的异步 OnShow 安装按钮监听，相关 Handler 等待边界已修复；产品代码与 APK 均未因该问题改动。初轮报告保留在 [disconnected-light-initial](../tests/wifi-simplify-device-probe/evidence/72/disconnected-light-initial/report.json)，不计入最终通过项数。此次 216 项与上述 1,200 项后端／运行时检查分别记录，范围不互相替代。

这些测试不认证真实相机或手机厂商后台策略；原有硬件后台测试保留其原版本和现场条件，不作为此次复用路径的实测结果。
