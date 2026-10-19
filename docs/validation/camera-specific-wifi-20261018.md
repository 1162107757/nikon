# 指定相机 Wi-Fi 的连接流程

0.9.13 的真实手机测试没有通过：HOME 后相机的两条 PTP 连接消失，手机切到其他热点；用户确认没有手动切网，相机热点也已关闭。前台服务和 CPU 锁仍在，不能据此认定数据仍在收发。旧 Android17 合成 echo 验证只适用于固定单网络的短时生命周期。

通用 `TRANSPORT_WIFI` 请求维护满足条件的网络，不能指定某个热点。0.9.14 改为 Android 10 以上使用精确网络名称的 `WifiNetworkSpecifier`，先取得系统授权和实际 Network，再建立两个 PTP 通道。相机热点消失的先后原因仍需真实时序记录核验。

## 手机上的操作

1. 在相机上开启 Wi-Fi，保持网络名称和密码页面可见。
2. Huevia 的“设置 → Wi-Fi 无线连接 → 连接相机”会请求系统所需权限。
3. 在 App 对话框填写相机显示的网络名称和密码，再允许系统连接；无需先在系统设置中手动加入网络。
4. 系统返回实际 Network 后继续现有的相机读取、导入或直播续接。连接相机本身不写入预设。

密码仅用于本次请求，禁止 view-state 与自动填充保存，不写普通偏好或诊断记录；下次新建 Wi-Fi 连接时重新填写。网络名称保留在手机私有偏好中便于输入。开放网络需明确勾选“此网络没有密码”。

Android 13 以上使用附近 Wi-Fi 权限；Android 10–12 依据系统 API 要求请求定位权限及系统位置开关，本应用不读取位置。Android 8–9 保留原系统 Wi-Fi 设置方式。

## 所有权和异常

- 请求归应用运行时及连接服务所有；Activity 退后台、锁屏、相册与预设移交、停止直播等待不会释放健康的相机网络。
- 正在等待系统授权也持有连接服务。取消只影响同一请求 token；刚 available、尚未交付业务的取消同样回收，已交付连接不随页面销毁回收。
- PTP 仅使用系统返回的指定 Network，失联后阻止回退到其他热点，即使对方网关也恰好是同一 IP。
- 真实网络丢失时清除无线句柄和直播连接，立即显示断开；本地保存、预设处理和 USB 操作继续。不会在后台重新弹出授权或重试相机写入。
- 明确断开或切换 USB 时，先关闭应用持有的 PTP 通道，再释放对应 Wi-Fi 请求，旧回调和清理不能释放新请求。

## 断线时序

`ConnectionTrace` 只记录固定事件、PTP 操作码／事务／耗时与关闭类别，不含网络名、密码、照片名称、句柄、参数或文件内容。日志包含墙钟和单调时间，异步有界写入应用外部 files 目录的 `connection-trace.log`，用于比较 UI 后台、最后成功响应、首次关闭、网络丢失的顺序。记录本身不增加 PTP 指令。

## 官方依据

- [Android Wi-Fi network request](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap)
- [附近 Wi-Fi 权限](https://developer.android.com/develop/connectivity/wifi/wifi-permissions)
- [AOSP WifiNetworkFactory](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/service/java/com/android/server/wifi/WifiNetworkFactory.java)

验证结果与实际安装包哈希以 `dist/nikon-usb-0.9.14-beta-VALIDATION.txt` 为准；实现指定热点请求并不等于已证明任意机身、任意省电策略均不会断线。

## 本机实际后台策略与结果

PKG110 的 Android 电池优化豁免已开启，但厂商“耗电管理”仍为“智能优化后台运行”。第一次 specific 测试在 HOME 后暂停后台执行，回前台才处理积压协议和丢网回调。通过正常系统应用详情界面，将 Huevia 单独改成“完全允许后台行为”并确认后，第二次观察得到：后台 113 秒、锁屏 166 秒同请求／同 TCP 通道并持续响应；用户锁屏拍摄的原图在 51.6 秒内完整传入，随后系统相册出现本包发布的副本。

设置入口是系统“应用详情 → Huevia → 耗电管理”；这台手机已完成设置，不需要用户重复修改。其他厂商可能使用不同名称，应用内 Android 标准电池优化状态不能代替厂商后台策略检查。记录只支持此次本机短时观察，长时间深度待机仍需另行实测。
