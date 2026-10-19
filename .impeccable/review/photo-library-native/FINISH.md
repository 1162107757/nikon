# Photo library finish review

## 当前：0.9.14-beta / 53

**照片功能、本轮代码／原生 UI 及本次真机观察范围限定 SHIP — 2026-10-18**。当前已审范围无待修 P1/P2。本机在系统 Huevia 耗电管理设为“完全允许后台行为”后，所观测 HOME 后台与实际锁屏传输通过；不延伸为长时间 Doze 或所有设备保证。

最终 APK SHA-256：`257a028ca814be1625e74e2217a184d9e33c1554f8749dfff37672da6592cd5c`

- 已独立读取本目录 `light/report.json`、`dark/report.json`：均绑定上述 53 APK，各 **84 项 PASS**，自建测试文件已清理，原有三份基线文件保留。覆盖分组、版本切换、按住比较、选择持久、效果取消／删除、整组删除及无关组保留；使用自建 JPEG 与真实 MediaStore，未访问用户照片。
- 新增指定 SSID 连接的异步、权限、Activity 销毁、取消及 onLost 状态处理经窄复核；available 后、业务交接前的取消竞态已由 `deliveryPending` 修复。网络丢失只使旧无线句柄与相册连接失效，本地照片任务和 USB lease 保留，错误网络回退保持阻断。凭据 UI 的浅／深色各 **39 项 PASS**，具体边界见 `../camera-wifi-ui-native/FINISH.md`。
- 集成方提供最终回归结果：Trace 541、PTP 4552、Album 826、project 356、folder 100 通过；构建及签名通过。集成方已覆盖安装手机并回读版本、APK 哈希与启动状态。
- 已独立核对同哈希 53 的真实手机／相机证据：HOME 后 **113.2 秒**、实际锁屏后 **166.0 秒**仍为同一进程、请求和两条 PTP 通道；锁屏期间 GetObject tx 318 用时 **51,556 ms**完成。锁屏后的本包 MediaStore 时间／大小记录出现 **6,840,961 bytes 原图**及随后 **2,175,697 bytes 副本**。仅查询本包条目的时间、大小及 owner，没有读取文件名或图片内容。恢复亮屏时连接总观察 **345.2 秒**保持一致，仍需用户自行解锁。
- 本次通过以系统 Huevia 耗电管理的“完全允许后台行为”为测试条件；原“智能优化”下 53 的失败保留于 `docs/validation/phone-wifi-53-attempt1/`。此结论限单次真实会话与上述短时观察，不宣称可绕过系统策略、长时间 Doze 或所有设备兼容。详情及证据为 `docs/validation/phone-wifi-53-summary.json` 与 `../camera-wifi-ui-native/FINISH.md`。下面 52 的 42 项合成 echo 结果不属于 53，不替代本次独立物理证据。

## 历史：0.9.13-beta / 52

**当时限定 SHIP — 2026-10-18**，仅覆盖下述代码、照片 UI 与合成网络证据。

**后续真机结果：失败。** 用户确认 HOME 后相机热点自行关闭，手机系统已切换到另一热点。因此 52 未完成真实相机后台目标；该失败促成 53 的指定 SSID 请求与路由保护改造，不以旧合成检查覆盖或否定真实失败。

版本：`0.9.13-beta / 52`  
最终 APK SHA-256：`6a2cda9e068ea9b4a311c49dfbe419d7367014c9cace82b376e8dfc37a6cbac6`

### 52 已关闭的 P2

- 项目／直播查看器的滑动邻图与目标页面统一使用各照片选定版本，避免先显示原图再跳到效果。
- 照片目录或版本快照未就绪时阻止整组删除，确认范围与实际成员展开一致。
- 按住“原图”追溯经过校验的根原图，旧叠加效果不再把父效果当成原图。
- 内部／外部目录的同键效果别名经完成记录与完整内容校验后复用结果、系统相册 URI 和保存状态；不同预设键即使输出字节相同仍独立。批处理也双向跨目录复用已有结果。

### 52 实际验证范围

- 只读复核分组、来源链、版本异步切换、按住比较、删除保护、发布状态及 Wi-Fi 申请／释放代码；窄复核效果别名修复与新增用例。Host 结果由实现代理提供：221 gallery、36 deletion、18 identity、138 batch、62 source-link 通过。
- 最终同哈希 APK 的原生浅色／深色流程各 **84 项 PASS**。使用自建 JPEG 与真实 MediaStore，覆盖分组、版本切换、比较、选择持久化、效果取消／删除、整组删除和无关组保留。已独立读取报告并查看实际截图；测试文件清理完成，原有三份基线文件保留。
- Android 17（API 37）后台网络 smoke **42 项 PASS**。仅创建一次 Wi-Fi NetworkRequest；前台、HOME 后 1／6 秒、熄屏后 1／5／9 秒均保留相同 callback、目标 Network 与 echo 往返。报告绑定相同最终 APK 哈希；测试清理完成并恢复亮屏状态。
- 上述后台证据是合成 echo 与真实 Android 网络 API 验证，未执行真实相机 PTP 传输、长时间锁屏或厂商省电验收。审查及测试未访问用户照片。

52 证据：`history/v52-6a2cda9e/light/report.json`、`history/v52-6a2cda9e/dark/report.json` 与 `docs/validation/background-wifi-android17-smoke-20261018.json`。
