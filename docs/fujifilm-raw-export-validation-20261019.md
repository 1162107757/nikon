# 富士会话 RAW 入口隔离验证

用户确认相机只保存 JPG，截图显示 DSCF9187.JPG 进入“导出 RAW”后报 `PTP StoreNotAvailable (0x2013), transaction 804`。这个响应本身不能证明 RAW 不存在；相机只拍 JPG 则没有对应拍摄 RAW。

本次与“确认老机型的 NP3 滤镜支持”任务协调共享目录：本任务修改 AlbumController、RawPhotoPanel 及相册／RAW 回归用例；另一任务负责 AlbumPanel 查看器整合、版本与统一 APK 构建，避免并发覆盖。

实现：

- 相册控制器提供当前会话的 NEF 下载能力；富士 JPG 会话不声明此能力。
- 富士会话的单张／批量导出直接进入 JPG 回调，不提供 NEF 格式选项。
- 底层 RAW 列表、配对与直接下载在任何相机 I/O 前拒绝富士会话。
- RAW 页面误入转至本地已保存 NEF，当前富士会话不能切到相机 NEF 列表。
- 保留离线 NEF 查找、浏览和保存，以及尼康原有 RAW 下载／配对。
- 0x2013 使用中文解释存储暂不可用，不将其误报为不存在 RAW。

已运行：

- `tests/run-album.ps1`：826 相册控制器、33 相机能力、22 网关、356 直播项目、100 项目文件检查，共 1337 项通过。
- `tests/run-raw-controller.ps1`：54 格式／配对、8 本地来源、34 控制器／PTP、9 成功配对，共 105 项通过。
- 新增断言覆盖富士三个 RAW 入口的零相机命令、JPG 连接保持、任务占用释放、尼康能力保留及 0x2013 与 RAW 缺失的区分。
- `git diff --check` 通过，仅有仓库换行提示。
- AlbumController 与 RawPhotoPanel 另以 Android 35 类库编译通过，输出隔离在 `.tooling/raw-capability-classes`，未占用统一 APK 构建目录。

已从共享代码确认整合任务接入相机照片页 `supportsRawDownload()` 门控，以及滤镜编辑器的 `canExportRaw()` 导出能力检查。首个 68 APK 早于本次 RAW 修复，不能作为本修复的交付证据，需由整合任务完成后重新构建。

RAW 原文件未被转换、伪造或替换为 JPG。专项测试为主机夹具；该记录不代表实际富士导出、原生界面或另一任务全部新功能已通过机身验收。统一安装包的版本、签名和最终构建摘要由整合任务记录。
