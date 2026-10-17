# 合成 PTP/IP UI 验证工具

这些服务仅监听 `127.0.0.1`，没有相机或设备访问能力。合成响应用于检查 App 流程，不能证明真实相机兼容性。

## 相册模式

默认行为保留 49 张合成照片、缩略图及原图读取。先使用 `SyntheticAlbumPhotos.java` 生成照片，再从项目根目录启动：

```powershell
node tests/tools/album-camera-server.js --fixtures=app/build/album-ui-fixtures --port=15740 --seconds=600
```

模拟器相册连接地址为 `10.0.2.2:15740`。默认日志位于 `app/build/album-ui-fixtures/server.log`。

## 预设能力只读模式

此模式无需生成或加载照片，日志目录自动建立：

```powershell
node tests/tools/album-camera-server.js --picture-control-readonly --fixtures=app/build/picture-control-ui-fixtures --port=15742 --seconds=600
```

在模拟器中，不选择预设，打开 **设置 → 无线预设导入**，填写 `10.0.2.2`、端口 `15742`，点击 **检测预设导入**。

服务返回严格型号校验所需的 `Z6II`，描述、固件和序列号保留合成测试标记。仅声明 `GetDeviceInfo`、`OpenSession`、`CloseSession`、`GetPicCtrlData (0x90CC)`、`GetPicCtrlCapability (0x90CF)`。能力为 28 个零字节；Standard 为 36 字节且 base=1，C1–C9 均为 36 字节空槽。不会声明或接受登记操作 `0x90CD`，全部 data-out 均拒绝。

预期结果：

- App 完成只读检测，显示九个空槽，以及“未声明无线预设登记接口”的说明；不会进入导入确认。
- App 保存地址，关闭会话，返回设置后仍没有持续的相机连接；检测结果不会点亮连接状态。
- 日志只有 14 条正常读取命令：`1001,1002,90CF,90CC×10,1003`，没有相册枚举或登记命令。
- `server.log` 包含 `PC_READONLY_SUMMARY ... commands=14 expected=14 planMatched=true uploads=0`。
- 本次操作不创建导入 journal，不改写已有待核对 journal。

每次启动会覆盖指定日志目录中的 `server.log`；使用不同目录可保留前次验证记录。服务默认 600 秒后退出，`--seconds` 范围为 1–3600 秒。

## 预设导入虚拟槽位模式

```powershell
node tests/tools/album-camera-server.js --picture-control-import-synthetic --fixtures=app/build/picture-control-import-ui-fixtures --port=15743 --seconds=600
```

此模式同样仅监听 `127.0.0.1`，无需照片。型号为严格匹配用的 `Z6II`，设备描述、固件和序列号明确标记为合成测试；增加声明 `SetPicCtrlData (0x90CD)`，其余能力数据、Standard 和初始九个空槽与只读模式相同。**所有槽位只存在于进程内存，没有真实相机访问，停止服务即消失。** 两个预设模式不能同时指定，默认相册模式和只读模式仍拒绝所有 data-out。

在模拟器中使用内置合成 NCP 测试文件，从预设详情发起无线导入，地址为 `10.0.2.2:15743`。到达确认页后等待至少 30 秒，观察只读 Standard 心跳，再确认导入。

服务严格检查会话和递增事务编号，允许已打开会话中的 `GetDeviceInfo` 复查、Standard 只读心跳；只有先读取设备、能力、Standard 和九个自定义槽位后，才接受一次 `0x90CD(target=201–209,new=0)`。登记必须使用 data-out 阶段、对应事务的 StartData / 可选 Data / EndData，且总长度恰好为当前 NCP 转换器输出的 **614 字节**（36 字节记录 + 578 字节曲线）；名称、基础类型、控制字段及曲线头也会校验。超长、截断、乱序、交错事务、重复登记、已有槽覆盖及其它写操作均拒绝。

完整接收后只更新虚拟槽位；随后 `0x90CC` 返回相同字节用于 App 读回核对。日志记录每条命令的事务、参数、阶段，`VIRTUAL_WRITE` / `VIRTUAL_READBACK` 记录相同 SHA-256。`PC_IMPORT_SYNTHETIC_SUMMARY` 包含连接数、OpenSession / CloseSession、Standard 复读计数和虚拟写入次数；`heartbeats` 是首读之后所有 Standard 复读次数，包含导入前复查，应结合时间戳和命令序列判断确认页心跳。

收到 `CloseSession` 后进入 `state=closed`，日志出现 `SIMULATED_AP_CLOSED`，此后所有新 InitCommand 都被拒绝。该行为模拟相机在会话结束后停止 AP，因此旧的“扫描后关闭，点击导入再重连”流程会失败，新流程应在同一 `session=1` 上完成等待、复查、单次虚拟写入、读回和关闭。服务保留 loopback 监听，仅用于记录新握手被拒绝；不会控制电脑或手机 Wi-Fi。

预期成功日志：`connections=1 opens=1 closes=1 virtualWrites=1`，同一会话内只有一次 `0x90CD`，读回哈希一致，等待期间有带时间戳的 Standard 读取。此验证只证明合成网络端到端流程，不能代替 Nikon 真机导入实测。

## 相册转预设的混合模式

```powershell
node tests/tools/album-camera-server.js --picture-control-album-import-synthetic --fixtures=app/build/picture-control-album-import-ui-fixtures --port=15744 --seconds=600
```

混合模式保留上述虚拟槽位模式的全部限制，额外声明只读照片操作 `GetStorageIDs (0x1004)`、`GetObjectHandles (0x1007)`、`GetObjectInfo (0x1008)`、`GetObject (0x1009)`、`GetThumb (0x100A)`。返回一个合成存储空间和 **零张照片**；任何照片句柄均返回 unknown object。本模式不生成或读取照片文件，也不提供真实设备访问，三个预设模式互斥。

模拟器先从 **设置 → Wi-Fi 无线连接** 连接 `10.0.2.2:15744`，确认相册连接成功并显示空相册；再打开本地合成 NCP，从相册会话转入读取预设槽位，确认后执行一次虚拟导入。日志中应依次出现：

- 唯一 `INIT_COMMAND` / `INIT_EVENT` / `OPEN_SESSION`；`EMPTY_ALBUM_STORAGE` 和 `EMPTY_ALBUM_HANDLES photos=0`。
- 同一 `session=1` 上读取设备、能力与槽位，确认页持续 Standard 心跳；相册转预设期间没有 CloseSession 或新 Init。
- 确认后仍在同一会话重新读取设备、能力、九槽及目标槽，唯一 `VIRTUAL_WRITE` 与 `VIRTUAL_READBACK` 哈希相同。
- 最后唯一 CloseSession；汇总 `connections=1 opens=1 closes=1 virtualWrites=1`，打开会话后的事务编号持续递增。

此空相册场景验证物理会话沿用；有照片时的在途缩略图排空、旧缓存回调及旧 owner 失效，需要对应的 host 回归测试覆盖，不能由零照片 UI 测试替代。默认 49 张合成照片模式仍保持原行为。

## 成功后保持连接与连续显式导入

```powershell
node tests/tools/album-camera-server.js --picture-control-album-import-synthetic --keep-import-session --fixtures=app/build/picture-control-keep-ui-fixtures --port=15745 --seconds=1200
```

`--keep-import-session` 只允许与上述两种 synthetic 导入模式组合；与默认相册或只读模式组合会拒绝启动。未启用时仍是一次虚拟登记上限。启用后允许在同一连接内显式导入不同空槽，每个 C1–C9 最多接受一次登记尝试，总登记成功数最多九次；已有数据绝不覆盖，异常帧也不能在同一槽重试。

每次成功写入后，服务清除该轮设备、能力和九槽读取证据；下一次登记必须重新读取 GetDeviceInfo、能力和九个槽位，并在登记前紧接一次目标槽读取。仍只接受原有严格验证的 614 字节、StartData / Data / EndData 事务，槽位始终仅存在于服务内存。该参数不会放宽文件读取、设备访问、监听地址或其它写操作。

模拟器先添加合成 NCP，建立相册或设置中的预设连接，然后：

1. 导入 C1 并读回核对，在成功页等待一次以上 Standard 心跳；日志此时应是一次 Init/Open、`virtualWrites=1`、零 CloseSession。
2. 点击完成、返回预设页，再进入导入，确认 C1 已占用且不能选择；明确选择另一个空槽，再次导入并读回核对。
3. 第二次成功页仍应只有一次 Init/Open、`virtualWrites=2`、零 CloseSession，两个目标各有一次登记及匹配的读回 SHA-256。
4. 只有在设置中手动断开后，才应出现唯一 CloseSession；此后新 Init 仍被拒绝。汇总中的 `attemptedTargets=[201,202]` 等字段记录实际接受的目标，便于核对没有重复登记。

前台连接保持、成功结果与后续当前连接状态应分开验证：之后的心跳失败不能把已读回核对的历史登记结果改为失败。后台连接、系统文档选择器、真实相机兼容性和预设会话回到相册的重连行为均不由这个工具保证。
