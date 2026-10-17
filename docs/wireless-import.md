# 无线预设导入 · 0.5.6-beta

当前实现通过标准 PTP/IP 将受支持的 NCP 兼容适配数据登记到相机空槽，0.5.6 也支持明确确认后覆盖已有槽；USB 使用同样的目标核对规则。**0.5.3、0.5.4 已收到 Z6II 用户无线导入成功反馈**，但缺少完整报告独立复核。0.5.5 已加入成功后保留连接及普通后台、锁屏维护；用户最新仍报告该版导入后系统中的相机 Wi-Fi 也断开，本次手机不在线，未取得新报告。0.5.6 修复了源码可确认的保活过早关闭边界，但不能据此认定本次断开的根因；真实相机结果仍须验证。USB 与无线证据分别记录。

## 使用

1. 在设置中将预设导入方式设为 Wi-Fi，配置相机地址并只读检测，再打开本地预设详情进入导入。
2. 在相机开启“连接至智能设备 → Wi-Fi 连接 → 建立 Wi-Fi 连接”。手机在系统设置连接相机显示的 Wi-Fi，无法上网时仍保持连接，并关闭占用相机的其他应用。
3. 回到 App，核对相机 IPv4 和端口，默认端口为 `15740`。网关只提供可编辑的地址建议，不代表已发现或验证相机。
4. 设置检测后 App 保留这次无线会话，返回预设库也会只读保活。进入导入时在同一连接重新读取能力与槽位；若已连接相册，则一次性移交原会话。期间保持连接，不在相机或其他应用修改槽位。
5. 确认页列出 C1–C9，优先选择第一个空槽；全满时仍可选择已有槽。选择已用槽会显示原名称和替换说明，点击“覆盖 Cn…”后还须确认“将 Cn「旧名」替换为「新名」”。相机中的原槽若已变化，写入会停止，须重新读取并确认。
6. 成功读回后继续保持只读连接，可再次选择预设并明确确认导入。每次仍重新检查，不连续自动写入。结果待核对时，先重新连接原相机与原地址只读核对；不要重复登记。再在相机菜单检查预设是否可选择并拍摄使用。

必要操作未在当前无线会话的相机能力中公开、协议被拒绝或需要尼康专有配对认证时，流程停止并保留失败原因。当前实现不包含专有配对协议。可用 USB 继续使用既有流程，但不能用换通道重试代替处理待核对结果。

## 实现边界

`CameraWifiNetwork` 为相册与预设导入共用网络选择和持久客户端 GUID。原实现已通过选定 `Network` 的 socket factory 将每个 socket 绑定到 Wi-Fi，避免把相机流量交给蜂窝网络；App 没有调用系统 Wi-Fi 断开接口。网关匹配优先，其次采用当前 Wi-Fi 或唯一 Wi-Fi；歧义与无 Wi-Fi 状态明确报错。Android 说明，这种 factory 的流量走指定网络，而该网络一旦断开，其 socket 也会失效，绑定不能维持已经消失的相机热点。[Android Network.getSocketFactory](https://developer.android.com/reference/android/net/Network#getSocketFactory())

`PtpIpClient` 默认维持相册只读模式。优化校准使用显式独立模式，只开放已知读取、会话管理及受限制的登记请求；相册实例不能获得登记权限。无线包装器复用 `PictureControlReadSession` / `PictureControlImportSession`。

独立 `probe()` 与待核对恢复仍在只读完成后关闭自有 PTP 会话和双 socket。设置检测改用 `prepareReadOnly()` 保持会话，即使未声明登记操作 `0x90CD` 也可以只读完成；跨预设库、相册和设置页面仍每隔 10 秒在同一串行 worker 只读 Standard 保活。真正进入导入时调用 `refreshPreparedSnapshot(true)`，在同一 owner 上完整重新读取设备信息、能力、Standard 和九个自定义槽，并要求声明登记能力，不能把先前缓存当作新快照。

已有相册连接通过 `transferToPictureControl()` 一次性移交给新的显式优化校准 owner。相册 worker 先等待在途请求完整结束并使旧回调失效；旧客户端永久失效，旧 `close()` 或协议中止不会关闭新 owner。双 socket、已打开的 PTP 会话、唯一事件读取线程和递增事务序列全部延续，`prepareFromHandoff()` 不重新握手或发送 `OpenSession`。移交端点必须完全一致，且只能消费一次；原相册 API 不获得登记权限，也不接管外部客户端的会话。没有可复用会话时，`prepareImport()` 才建立一次新的双通道连接。

确认后使用 `importPreparedNcpPresetKeepingConnection()`：从原始 NCP 重新计算载荷，再读设备信息、Standard 能力和全部九个自定义槽，随后最多一次 `SetPicCtrlData (0x90CD)`，并逐字节核对完整读回。只有全部成功，才将原连接移回独立的只读 owner，不发送 `CloseSession`。原 `importPreparedNcpPreset()`、独立导入和 USB 的关闭契约保留，仍要求自有 `CloseSession` 获确认。

既有 API 默认仍只准空槽，初查与临写前复查都必须为空。覆盖使用末参为不可变 `PictureControlImportSession.OverwriteConfirmation` 的显式重载：该对象复制用户确认时的非空原槽字节，并派生目标、SHA-256、名称及 base。本次新鲜能力与九槽检查中的目标、紧接写入前的目标二读，均须与确认快照完整匹配；改名、改内容、改 base 或变空都停止且零写入。确认凭据不能用调用者任意填写的 hash/name 代替。新载荷仍受原有严格参数验证限制，没有相机端原子比较并写入保证。

USB 覆盖专用 `WRITE_CONFIRMED_PC` 模式只比原空槽模式多允许读取 `GetPicCtrlCapability (0x90CF, Standard=1)`，以便本次打开会话后重新验证能力；其它操作白名单、目标范围、`new=0` 和一次写入预算保持。报告另记录 `overwriteRequested`、`confirmedTargetItem/Sha256/Name/BasePictureControl`、`freshCapabilityVerified` 与 `initialTargetMatched/recheckTargetMatched`，不会把成功覆盖伪报为空槽登记。

每次明确确认的导入仍只有一次登记预算。`transferAfterVerifiedPictureControlImport()` 另由传输层核对该连接的写入 ACK、同目标精确读回及严格下一个事务号，才允许生成新优化校准 owner；旧 owner 永久失效。没有通用预算重置接口，失败、不明、读回差异或取消都不能沿用旧成功证据。显式 Open/CloseSession 尝试会清除该会话的成功证据，但不重置已消费的写入预算。下一次导入须由用户重新确认并完整检查。

普通页面切换、切到后台、锁屏或 Activity 重建不主动释放连接。显式重新连接相册、更改端点、取消、切换 USB 或启动恢复核对仍会释放原连接；明确断开或停止连接服务时，空闲持有会话正常关闭后释放 socket，未消费的移交句柄另有关闭兜底。取消在途操作时立即关闭 socket；协议阶段未确认时直接中止连接，不补发 `CloseSession`。保活或导入失败不会自动重连、重试登记、删除或回滚。

只读报告沿用 `sessionRetainedForConfirmation` 字段名：`true` 也可表示设置检测后持有的只读会话，故可同时有 `completed=true`、`sessionCloseConfirmed=false`。只有当前 owner 的 `isPrepared()` 仍为真才算有效连接；缓存报告或 `hasPreparedSession()` 都不能代替它。后者仅表示仍有待清理的快照状态，即使物理连接已断也会执行清理并记录报告。释放或消费后清除持有标记。报告 diagnostics 另保留首个真实握手、命令通道或事件通道关闭原因；唯一事件读取线程的故障会关闭当前 owner，不会误用已撤销的相册 owner。

导入报告新增 `sessionRetainedAfterImport`：成功保留时它与 `completed`、`ncpRegistrationVerified`、`readbackVerified` 都为 `true`，而 `sessionCloseConfirmed=false`，不伪造关闭成功。它记录完成当时的事实，之后断线、保活或释放不改变历史导入结果；当前连接状态使用独立只读快照。该快照的九槽来自本次新鲜检查，目标由精确读回更新，Standard 沿用此前快照；下一次导入仍会完整刷新。保留提交前若取消或移交失败，结果不会报告完成，但已有读回证据保留，连接终止且不自动重试。

`CameraConnectionRuntime` 在应用级持有连接与串行 worker，`connectedDevice` 前台服务显示持续连接通知，并持有 CPU/Wi-Fi 锁、每 10 秒发起只读保活。相册心跳是已声明的 `GetStorageIDs`，不重新枚举照片或建立连接。相册生命周期 observer 与 UI listener 分离；新 Activity 可从完整缓存的 `ConnectionInfo`、原照片索引恢复分页，也可接续尚在建立的连接。关闭共享 `AlbumPanel` 只移除该界面的 listener 和图像任务，不关闭共享 controller。

优化校准只读保活收到完整、匹配事务的 `GeneralError (0x2002)` 或 `DeviceBusy (0x2019)`，且原 owner、会话、连接和协议状态仍有效时，记录异常并保留会话，等待下一次只读心跳；这不算读取成功。Runtime 与后端共用 `isRecoverableKeepAliveResponse()` 分类。其它响应码（包括 `SessionNotOpen`、`InvalidTransactionID`）、事务不匹配、坏帧、不可解析数据或真实断线仍终止原连接。该例外不适用于登记；写入返回 `0x2002` 仍停止，不保留会话、不重放。相册保活对完整 PTP 拒绝响应的既有处理保持。

这种维护针对普通后台与锁屏，不会在系统强制终止进程、用户强制停止应用或相机热点自身断开后自动重连。0.5.4 打开外部文件选择器后的传输关闭仍是原因未判定的历史观察；后台、锁屏设备验证以对应版本交付记录为准，协议检查本身不能证明服务调度或厂商省电行为。

PTP/IP 发送数据使用 operation request 的 DataPhase `2`，随后发送 StartData（type `9`，事务号与 UINT64 数据长度）、EndData（type `12`，事务号与数据），最后验证 operation response（type `7`）。读取或无数据请求使用 DataPhase `1`。断线、部分发送、事务不匹配或未确认响应会关闭连接并停止后续相机命令；不会在未确认阶段发送清理或重试请求。

本机待核对 journal 的 schema 2 保存 `transport`、无线 host / port 以及既有载荷和目标证据；覆盖另存 `overwriteRequested`、原槽 hash 与名称。应用级 `ImportStatus` 携带不可变的 journal JSON，Activity 重建后也按同一意图恢复。结果不明后只读比较预期新内容、确认的原内容或其它状态，不自动重放覆盖；旧 schema 缺少通道时按 USB 恢复。会话关闭失败、读回差异和未确认协议阶段与写请求被接受分别记录。精确登记不等同于原生 NCP 载入或实际拍摄效果验证。

## 连接间隙修复与验证

用户截图显示失败发生在写入前的 `WirelessConnection` 阶段，错误为 `EHOSTUNREACH / No route to host`，没有发送登记请求。源码与合成回归已确认旧流程存在“预检结束关闭会话及双 socket，确认后重新连接”的间隙，0.5.3 移除了真正导入中的这次主动关闭与重连。相机是否因此关闭热点仍是根据现象作出的推断，这一触发关系尚未用相机日志复核；尼康的一般 Wi-Fi 排错说明也不能证明 `CloseSession` 必然关闭热点。[尼康 Z6II 排错说明](https://onlinemanual.nikonimglib.com/z7II_z6II/en/14_troubleshooting_02.html)

用户后续反馈 0.5.3 已可导入，但读能力与空槽时仍断开一次。审计发现设置检测后仍会关闭会话、相册进入预设前仍会关闭旧连接再连接；0.5.4 分别改为保留并刷新、一次性原连接移交。用户所述断开的确切路径与相机热点关闭原因仍需完整报告复核，不能将剩余原因全部归为某个已证明的固件行为。

后续反馈把 0.5.4 的断开时点明确为导入成功后。源码确认原成功路径仍会主动 `CloseSession` 并释放双 socket；0.5.5 新增只在完整成功后保留原连接的路径。源码中的主动关闭是已知事实，相机热点是否因此关闭仍不能仅凭用户描述认定。

最新用户报告来自已安装的 0.5.5：导入后不仅 App 连接中断，系统中的相机 Wi-Fi 也断开。本次手机未在线，未取得新的 `last-report`、journal 或网络状态，不能认定具体原因。只读审查确认另一个源码缺口：此前 Standard 心跳收到完整、匹配事务的 `GeneralError (0x2002)` 仍会主动关闭原会话与双 socket。0.5.6 将它与 `DeviceBusy` 一样限定保留健康 owner，并保留异常诊断；这证明该代码路径已修正，不能证明用户当次实际收到该响应，也不能证明尼康热点因它关闭。

0.5.6 当前完整自动检查通过 **20,982 项**：`run.ps1` 9,505 项（含 PTP/IP 3,956、相册 host 226、预览缓存 53）；`run-probe.ps1` 11,477 项（普通 USB 685、优化校准只读 453、USB 复制与适配 1,253、无线 9,086）。新增覆盖显式已有槽替换、默认 API 拒绝覆盖、两次快照比对的变化与取消、同连接再次确认覆盖，以及导入后首次 GeneralError 保活拒绝后下一次心跳继续；写入 GeneralError、错误事务及其它响应仍终止。这些检查不包含 0.5.6 Android UI、安装或真实相机结论。详见 [PTP_CORE.md](../tests/PTP_CORE.md)。

历史 0.5.5 完整自动检查通过 **17,832 项**：`run.ps1` 9,205 项（PTP/IP 3,719、相册 host 166）；`run-probe.ps1` 8,627 项（普通 USB 685、优化校准只读 453、USB 复制与适配 1,004、无线 6,485）。新增覆盖同连接两次明确确认的不同空槽导入、成功后心跳/刷新且不关闭、严格 ACK/目标/字节/事务证据、Close/Open 后旧证据与写预算不可重用、失败及提交前取消不保留、历史结果独立，以及相册只读保活、取消完成、metadata 恢复和 observer/UI listener 分离。最后边界覆盖合法 busy 保留、错事务 busy 终止、坏 StorageIDs 关闭原连接及旧代失败不干扰新连接。这些检查不包含前台服务调度、锁屏或手机安装结论。详见 [PTP_CORE.md](../tests/PTP_CORE.md)。

历史 0.5.5 相同最终 APK 的 Android 17 模拟器集成验证：相册切后台 131.113 秒、授予电池优化豁免后的强制深度 Doze 120.231 秒、第一次导入成功后后台关屏 188.747 秒均保活，两次 Activity 重建保留原连接。C1、C2 两笔明确确认的虚拟登记分别读回核对 614B，哈希一致。全程同一 session、848.036 秒，正事务 1–139 连续；仅最后手动断开发送 Close，服务和 CPU 锁随后释放。另一只读轮拒绝系统通知权限后仍有连接服务及心跳，后台传输丢失后释放且零写入。该证据来自本机内存槽服务，未访问相机热点。该版最终包已保数据安装到 PKG110 / Android 16，版本与 APK 哈希一致，手机权限未修改；真实相机及厂商省电仍待复测。详见 [连接协议日志](device-review/wireless-0.5.5-background.log)、[生命周期日志](device-review/wireless-0.5.5-lifecycle.log)、[断线轮日志](device-review/wireless-0.5.5-loss.log)与 [0.5.5 交付记录](../dist/nikon-usb-0.5.5-beta-VALIDATION.txt)。

历史 0.5.4：完整自动检查共 **15,628 项**（8,690 core + 6,938 probe，其中无线 4,796）；覆盖相册移交、只读持有与刷新、事件线程、能力及失败边界。这些历史检查不验证成功后持续保留，后者由 0.5.5 的新契约和回归单独覆盖。详见 [PTP_CORE.md](../tests/PTP_CORE.md)。

0.5.4 最终 APK 另在 Pixel 9 / Android 17 模拟器前台验证相册 → 设置检测 → 跨页保留 → 预设刷新 → 确认 → 虚拟登记及读回，全程同一 session，135.984 秒，仅一次握手 / Open / Close，8 次定时心跳，唯一 614 字节登记与读回哈希一致。[前台全链路日志](device-review/wireless-0.5.4-retained.log)只来自监听本机的内存槽服务。另一次探索在外部文档选择器打开、App 进入后台后传输关闭，原因未判定，零写，独立保留[日志](device-review/wireless-0.5.4-background-picker.log)，不能声称后台稳定性已验证。已保数据安装至 PKG110 / Android 16，版本与安装包哈希一致；详细范围见 [0.5.4 交付记录](../dist/nikon-usb-0.5.4-beta-VALIDATION.txt)。

历史 0.5.3：两项自动检查共 13,898 项（8,212 core + 5,686 probe）。其最终 APK 曾在 Pixel 9 / Android 17 模拟器以本机内存槽位服务验证：确认停留 132.336 秒、13 次心跳后同连接登记并精确读回 614 字节；取消轮 193.527 秒、19 次心跳后零写入释放，服务关闭后拒绝重连。日志见 [成功](device-review/wireless-0.5.3-success.log)与[取消](device-review/wireless-0.5.3-cancel.log)。0.5.3 交付记录包含 PKG110 / Android 16 保数据安装证据，详见 [该版记录](../dist/nikon-usb-0.5.3-beta-VALIDATION.txt)；用户后续相机成功反馈另如上所述，不能代替 0.5.4 复验。

历史范围：0.3.0 的 10,520 项由 6,906 core、3,487 probe 与 127 library 组成；当时的 Pixel 9 / Android 17 模拟器证据仅涉及无线引导、地址校验、深色大字号与读取中取消，截图为 `.impeccable/review/phone-wireless-*.png`。当时没有实体手机安装或真实相机无线写入证据，签名冲突也属于该历史构建记录，不能作为后续版本的安装状态。安装与界面验证应以对应版本的交付记录为准。

## 依据

- 用户最后进一步确认断开表现是机身关闭 Wi-Fi 热点。当前无该次机身或 App 报告，不能把已修复的心跳关闭路径认定为根因，也不能由 Android 前台服务推断机身热点永不关闭。已核验的 [Z6II c3 说明](https://onlinemanual.nikonimglib.com/z7II_z6II/en/09_menu_guide_05_c03.html)只描述显示和控制屏待机，未声明热点关闭时序；目前没有可靠依据要求在 `SetPicCtrlData` 后额外切换远程模式或发送 DeviceReady 来维持热点，故未增加这类相机状态修改。
- [尼康 Z6II 智能设备无线连接手册](https://onlinemanual.nikonimglib.com/z7II_z6II/en/10_establishing_wireless_connections_03.html)说明 Wi-Fi 开启与系统连接流程，没有声明本 App 使用的预设登记能力。
- [尼康 Z6II 电脑 Wi-Fi 连接手册](https://onlinemanual.nikonimglib.com/z7II_z6II/en/10_establishing_wireless_connections_02.html)将内置 Wi-Fi 电脑用途描述为图片上传；[Camera Control Pro 2 帮助](https://nikonimglib.com/ccp2/onlinehelp/en/00_introduction_01.html)的无线 LAN 说明列出 Z9、Z8 及支持 WT 发射器的相机。它们不能证明 Z6II 内置 Wi-Fi 接受 `0x90CD`；是否开放仍需实际无线会话验证。
- [libgphoto2 原始 PTP 定义](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptp.h)定义 `0x90CC` / `0x90CD` 的读取与发送方向；[原始 PTP/IP 实现](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptpip.c)用于核对数据相位和包格式。它们是协议实现依据，不是 Z6II 无线兼容性证据。
