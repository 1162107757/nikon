# 照片直播后台 Wi-Fi 保留修复

**最新状态：0.9.14 在本机调整厂商后台策略后，限定实测通过。** 精确热点请求配合“完全允许后台行为”，真实相机后台 113 秒、锁屏 166 秒持续保持原请求和双通道；用户锁屏拍摄的原图完整收到，系统相册新增发布记录。见 [真实手机汇总](phone-wifi-53-summary.json)及 [指定热点流程](camera-specific-wifi-20261018.md)。这不构成长时深度 Doze 或所有设备的保证。

前次通用请求以及第一次 specific 真机测试均失败，用户确认未手动切网、相机热点已关闭；前台服务仍在，但后台执行停止并在回前台后恢复。系统实际仍选“智能优化后台运行”，与 Android 电池优化白名单是两层配置。旧失败及其范围保留于各版交付记录，不能拿下述 Android17 固定网络 echo 当成真机成功。

## 代码证据

- `MainActivity.onPause()` 只暂停账号 UI、顶部提示和图片对比，没有主动停止直播或断开相机。
- 直播调度器、PTP/IP 事件读取线程和现有连接心跳属于应用运行时；前台服务已有 CPU 部分唤醒锁和 `connectedDevice` 类型。
- 缺少的是对无互联网 Wi-Fi 的应用期网络需求。`CameraWifiNetwork` 原来只读取已有 `Network` 并绑定相机 socket；`WifiLock` 在 Android 14 以上不能补足此项，因为低延迟模式仅在前台且屏幕点亮时生效，高性能模式也会映射到该模式。
- 这是一项确定的代码保护缺口，不是对用户手机现场断线原因的唯一归因。初次只读采集手机 `286f0dba` 时设备不在线；用户接回手机后，只读结果为：当时没有本包前台服务条目，Huevia 在 Doze 白名单，手机亮屏、充电，深浅 Doze 均为 ACTIVE；最近 3000 条经过筛选的日志没有本包或 HueviaConnection 的服务错误。该快照不能证明此前断线的触发点。未操作手机、清日志、切前后台或读取用户照片。

## 0.9.13 通用请求（历史，真机未通过）

- 连接服务持有一个 `TRANSPORT_WIFI` 的 `requestNetwork`，不要求互联网能力；在无线连接、已持有会话和相册移交期间保留，实际无线工作结束或服务销毁才释放。
- 网络需求不属于 Activity，不随前后台切换或锁屏释放；字节进度和照片之间的等待不重复申请。
- 保留 PTP socket 的既有 Network 绑定，不创建指定 SSID 的新连接、不改变进程默认网络、不发送 `CloseSession`，也不自动重连或重试相机写入。
- `CameraWifiNetwork` 从成功建立的物理 PTP socket 记录实际目标 Network，弱引用及 closed 检查避免连接资源泄漏。若同时存在不同目标网络，则不猜测目标；默认网络改变不改写已有 socket 的目标。
- Android 的 generic Wi-Fi 请求可能选择另一条 Wi-Fi，因而 `onAvailable` 必须比对实际 PTP 目标；错网时立即撤销需求并记录告警，不能继续维护错网。目标 `onLost` 同样撤销，不发起重连；旧 callback 不影响新目标。
- 普通权限新增 `CHANGE_NETWORK_STATE`。网络申请失败只记录保护告警，不关闭健康相机会话。
- Wi-Fi 锁保留为前台延迟优化；后台由网络需求、已有连接服务、CPU 锁及已获准的电池豁免共同维护。系统强行停止进程、厂商额外省电限制、无线链路或相机主动停止热点仍需现场区分。

## 验证

- `tests/run-wifi-retention.ps1`：519 项需求生命周期检查和 10 项实际 socket 目标检查通过，覆盖 500 次重复状态通知仅一份申请、无互联网匹配、错网/丢网释放、陈旧 callback、默认网络变化、双目标歧义、失败 socket、断开与销毁的单次回收、系统拒绝申请、重复释放与服务不可用。
- 使用 API 35 `android.jar` 与已有本地编译依赖对 `CameraWifiNetwork`、`CameraWifiRetention`、`CameraConnectionRuntime`、`CameraConnectionService` 隔离编译成功；未执行产品 APK 构建、修改版本或安装设备。
- 真机应在已授权后台运行的条件下验证：直播等待 → 切后台 → 新拍 JPEG → 锁屏 → 新拍 JPEG → 返回项目。需要同时确认前台服务、网络需求、原图保存和相机热点状态；合成测试不能证明机身无线兼容性。
- 原生运行观测标签为 `HueviaConnection`，信息包括 request 发起、与 PTP 实际 Network 匹配、释放、错网和目标丢失；不输出 SSID、照片名称、路径、账号或密钥。

## 最终 APK 的 Android 17 原生 smoke

在根明确释放的现有模拟器 `emulator-5558` 上，对最终 `0.9.13-beta / versionCode 52` 进行了独立 instrumentation 验证，产品 APK SHA-256：`6a2cda9e068ea9b4a311c49dfbe419d7367014c9cace82b376e8dfc37a6cbac6`。

- 设备运行 Android 17 / API 37；目标 App 的 `CHANGE_NETWORK_STATE` 普通权限已授予。
- 使用产品真实 `CameraWifiNetwork` 将一个合成 echo socket 绑定到实际 Wi-Fi Network，再调用真实 `CameraWifiRetention`。主机 echo 仅监听本任务 `127.0.0.1`，不使用 PTP、不连接相机、不读取照片。
- 原生日志确认 request 与 socket 的实际目标 Wi-Fi 匹配；整个过程中只创建一次 request。
- 42 项原生检查通过：前台、HOME 后 1/6 秒、实际灭屏后 1/5/9 秒，均保留同一 callback、同一目标 Network，网络仍可用且 echo 往返成功。后台时 Activity 已不可见，灭屏时 `PowerManager.isInteractive()` 为 false。
- 测试期间持有自有、限时 CPU 部分唤醒锁，模拟已有连接服务的 CPU 保护；没有强制保持屏幕点亮。结束后回收测试 request、socket 和唤醒锁，恢复原屏幕状态、卸载自有 probe；产品数据保留，echo helper 已退出。
- 结果见 [Android 17 原生 smoke JSON](background-wifi-android17-smoke-20261018.json)。独立 probe 源码与构建脚本在 `.tooling/background-native-smoke/`。

这份证据确认了真实 Android 网络申请与短时前后台/灭屏保留行为，不是 Nikon 相机连续拍摄、长时间 Doze、厂商省电策略或真实机身热点保持的测试。真实手机现场问题仍需相机连接时验证。

## 后续 specific 连接实现

泛 `TRANSPORT_WIFI` 请求维护系统选择的最佳 Wi-Fi，可能转到另一个已保存网络。原来的目标 Network 比对只能在切换后发现不符，无法锁定 SSID。现增加 `CameraWifiSession`：

- 通过精确 SSID 的 `WifiNetworkSpecifier` 请求本地 Wi-Fi；空密码表示开放网络，非空为 WPA2，先验证输入。密码仅用于本次 Android 连接，不在该管理器中持久化或写日志。
- 请求由应用运行时和前台服务持有；请求中、已连接都属于 `connectedDevice` 工作。只有系统 `onAvailable` 后才允许建立 PTP，健康的同 SSID 请求可复用。
- `connect(ssid, password, Listener)` 返回 token；60 秒超时。结果在主线程异步投递，监听器在成功、失败、取消后清理。`cancel(token)` 只影响同 token 的待连接请求，Activity 重建不会关闭健康连接。
- Android `onAvailable` 到业务 `onConnected` 实际投递之间仍算可取消窗口；此时取消或销毁页面会释放未交付请求并使排队结果失效。只有交付到业务后的健康连接由服务继续持有，避免页面丢弃回调后遗留孤立 Wi-Fi 请求。
- 物理连接通过 owner/token 固定 Network 路由，连接器前后都检查路由版本。网络丢失或请求失败后保留阻断状态，不回退到别的路由；旧 callback、旧取消和旧清理不能覆盖新请求。
- pending / connected / fail-closed specific 状态都不申请 generic 保留。显式断开先关闭 PTP，再释放对应 token 的 Wi-Fi 请求；相册与预设之间移交不重建网络。
- specific 真正丢失时，应用运行时立即使无线 prepared / album / handoff 句柄失效、停止直播网络工作并提示相机 Wi-Fi 已断开；只关闭本地 socket，不发 `CloseSession`。无线 owner 的 lease 单独撤销，本地保存、预设处理及无关 USB lease 不变，不执行全局断开或清除失联 route guard。
- `ConnectionTrace` 记录固定 `WIFI_SPECIFIC_*` 状态 token，包括系统 UID 网络 BLOCKED/UNBLOCKED 与物理 LOST 的区分，不输出 SSID、密码、Network id 或任意异常文本。

API 35 隔离编译通过。`tests/run-wifi-retention.ps1` 当前为 557 项 specific 生命周期检查、519 项 generic 保留检查、10 项 socket 目标检查通过；`tests/run-wifi-runtime.ps1` 对真实 Runtime 源码的 13 项断线回归通过，验证立即清无线句柄、只关闭 socket、不取消本地处理、不误清新代连接，以及初次失败不制造旧相机断开。specific 尚未进行新的 Android 系统授权连接及 Nikon 真机后台实测；它也不能阻止机身主动关闭热点，仍需用 PTP 收发时序确认根因。

## 官方依据

- [WifiManager Wi-Fi 锁限制](https://developer.android.com/reference/android/net/wifi/WifiManager#WIFI_MODE_FULL_LOW_LATENCY)
- [HIGH_PERF 在 API 34 的映射行为](https://developer.android.com/reference/android/net/wifi/WifiManager#WIFI_MODE_FULL_HIGH_PERF)
- [ConnectivityManager.requestNetwork 的保持与释放语义](https://developer.android.com/reference/android/net/ConnectivityManager#requestNetwork(android.net.NetworkRequest,android.net.ConnectivityManager.NetworkCallback))
- [NetworkRequest 与仅监听网络的区别](https://developer.android.com/reference/android/net/NetworkRequest)
