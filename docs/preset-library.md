# 本地预设库与统一在线目录

## 0.11.1 的预览、下载与删除

未下载在线预设可按需读取并校验 NCP／NP3 配方，在当前模板上生成手机近似效果。配方缓存与“我的预设”独立，浏览不加入本地库；发布者样片另列为“实拍参考”。不支持的配方明确显示原因，不能把模板原图标作预设效果。

`PresetDownloadTasks` 在进程中按 SHA 合并下载，最多四个活动任务、两个执行线程。卡片与详情显示已知总量的百分比，以及保存或载入样片等阶段；未知总量使用加载状态，不伪造比例。离开页面和 Activity 重建后重新订阅任务，库提交／删除的版本事件触发 worker 刷新。只有完整配方校验、保存和可选样片处理结束后才显示“已下载”；样片单独失败时保留已完成配方并明确提示。

“我的预设”详情提供删除入口，卡片支持长按。删除前检查正在下载、相机操作、直播和照片处理；确认列出将取消默认预设的项目，以及需重新下载才能重试的旧任务。删除在项目预设的串行 worker 上重新核对引用版本，变化时要求重新确认。持久化移除标记阻止旧元数据或旧版迁移恢复该条目；用户显式再次导入／下载可重新加入。仅清理 App 管理且无其他引用的预设和配套样片，不删拍摄原图、效果图、外部图片或相机槽位。清理失败明确反馈，保留任务历史。

以下章节保留数据层的历史接口与验收范围；当前支持格式另见 [NP3 说明](np3-rendering.md)。

此数据层不附带预设或照片，也不声明尼康官方服务接入。0.9.5-beta 起，App 使用 `useManagedEndpoint` 接入产品自有的 CloudBase HTTPS 目录；“在线预设”直接显示管理后台已发布的内容，设置与帮助中不再提供地址编辑入口。旧版本保存的自定义地址或关闭状态会迁移到内置地址，已经下载或从手机添加的预设不受影响。Repository 构造本身仍不设置默认地址，泛用配置 API 保留用于独立数据层测试。真实目录与样片由管理后台维护，见 [在线预设管理后台](../online-library/README.md)。下载到手机和写入相机是独立流程；本数据层不调用相机。

内置目录为 `https://nikon-d8girdjsr252fd90e-1252602548.ap-shanghai.app.tcloudbase.com/preset-library/catalog.json`。用户已授权并创建 `/preset-library` 公网路由，服务已上线：此前基础 HTTP 12 项与完整上传发布下载流程 76 项检查通过；生产 Java 数据层在 JDK 17 上的真实 HTTPS 探针 11 项检查通过，未执行 Android 图片解码。该次服务验收使用的合成预设和文件已清理，不代表目录中已经发布真实预设。App 读取公开目录不使用管理员账号或云端密钥，也不要求用户登录。

## UI 接口

0.9.7-beta 起，`PresetRecord.category` 与 `OnlinePreset.category` 为可选单分类，旧元数据、缺省和 null 统一为空串，界面显示“未分类”。`PresetCategories` 提供人像、风景、胶片、黑白、日常、夜景建议及统一规范化；自定义名称先拒绝原始控制字符和孤立代理项，再 NFC 与 ECMAScript trim，最多 24 个 Unicode code points。全部、未分类仅作为筛选名称，不可存为实际分类。

`library.setCategory(id, category)` 为 worker API，原子保存成功后才更新内存；更改分类、关联样片均保留原始 NCP 与校验值。首次下载保存目录分类；同 SHA 复用已有条目时不覆盖用户设置，包括用户明确清空的分类。目录缓存、本地磁盘重开均保留分类。该数据层本轮 301 项 host 检查通过；实际 HTTPS 探针的 11 项检查也覆盖在线、下载、本地和缓存重开后的分类，不执行 Android 图片解码。

包为 `com.nikonpreset.usbprobe.library`。构造、迁移、文件读取、下载及图像校验均放在工作线程。

```java
PresetLibrary library = new PresetLibrary(getFilesDir());
List<PresetRecord> records = library.list();
PresetLibrary.AddResult result = library.importFile(originalFileName, originalBytes);
byte[] originalNcp = library.readNcp(result.record.id);
library.attachPreview(result.record.id, persistentContentUri.toString());

OnlinePresetRepository repository = new OnlinePresetRepository(getFilesDir(), library);
repository.useManagedEndpoint(shippedHttpsManifestUrl); // App 产品入口：迁移旧配置、固定自有目录
OnlinePresetRepository.CatalogResult catalog = repository.fetchCatalog();
OnlinePresetRepository.CatalogResult cached = repository.cachedCatalog();
PresetLibrary.AddResult downloaded = repository.download(item, (done, total) -> {
    // 派发回主线程显示进度；此回调运行在下载调用线程。
});
File actualPreview = repository.downloadPreview(item); // 可选；错误单独处理
```

- `PresetRecord` 的 public final 字段：`id/displayName/originalFileName/sha256/source/previewUri/description/ncpVersion/byteLength/addedAt`。ID 是小写 SHA-256；`source` 为 `phone/online/legacy`，不是“已导入相机”状态。
- `AddResult.record` 为提交或复用的条目，`added=false` 表示相同内容已存在。去重不按名称；相同名称、不同内容会保留为不同记录。复用原条目时保留原名称、来源和用户样片。
- `findBySha256(String)` 可用于在线条目的“已下载”标记；真正读取或重复下载时仍会校验本地内容。
- `readNcp(id)` 每次检查大小、SHA-256 与 NCP 结构，再返回独立字节数组。应把它的结果传给现有导入器；不能把“结构已检查”显示成相机兼容性已验证。已有严格转换器继续决定当前可导入的 NCP 子集。
- `attachPreview(id, localUri)` 只接受 `content:`、`file:` 或空字符串；返回更新后的不可变条目。UI 负责取得和保留系统文件 URI 权限、检查用户所选图片可读性。此方法不复制任意 URI 的内容。
- `downloadPreview(item)` 会缓存通过验证的实际图片，并在该 SHA-256 的本地条目还没有样片时关联缓存 `file:` URI；不会覆盖用户已关联的样片。若先下载了样片、后下载 NCP，可再次调用此方法关联已缓存图片。样片失败不影响有效 NCP，也不能用其他图片冒充该预设的真实样片。
- `CatalogResult.status`：`ok/cached/not_configured/no_cache/error`；另有 `message/presets/fromCache`。`fetchCatalog()` 无地址时明确返回 `not_configured`，不请求网络、不生成列表。失败不静默伪装成最新在线数据。
- `useManagedEndpoint(url)` 只接受非空 HTTPS 地址。读取当前配置、必要时原子提交内置地址后才启用网络。可读取的旧配置文档（含旧自定义、关闭状态或损坏 JSON）会被迁移；配置不是文件、文件读取失败或提交失败时，内存端点清空，并提供“重新加载”的提示，不会继续请求旧地址。App 的“重新加载”操作会重试迁移。
- `cachedCatalog()` 是明确的缓存入口；启用产品统一目录后，只提供端点与内置地址完全一致的缓存。旧自定义地址的缓存不会冒充内置库，也不主动删除。失败迁移期间不展示缓存。消息注明缓存及文件可能尚未下载，已下载的本地文件始终保留。
- 泛用 Repository 实例可使用 `setEndpoint(url)`，也可用 `initializeDefaultEndpoint(url)` 仅初始化从未配置过的实例，旧行为保持不变；这两个 API 不在 App 中开放。已调用 `useManagedEndpoint` 的实例不能再通过 `setEndpoint` 改用其他地址或关闭统一目录。
- `OnlinePreset` 的 public final 字段：`id/name/description/fileUrl/sha256/byteLength/previewUrl/compatibleModels`。其兼容机型是资源提供方描述，不替代当前导入器的设备与参数检查。
- 进度 `total=-1` 代表未知总量；有已知文件长度才计算百分比。中断下载线程或在进度回调抛出 `IOException` 可取消；底层阻塞读取受超时限制。取消不会自动重试。

## 目录 schema version 1

HTTP 响应体为 UTF-8 JSON，对象包含整数 `version: 1` 和数组 `presets`。空目录合法：

```json
{"version":1,"presets":[]}
```

每个 presets 项为对象：

| 字段 | 规则 |
| --- | --- |
| `id` | 必填、非空字符串，最多 128 个 UTF-16 单位；目录内唯一 |
| `name` | 必填、非空名称，最多 160 个 UTF-16 单位 |
| `description` | 可选单行说明，最多 2000 个 UTF-16 单位 |
| `fileUrl` | 必填完整 HTTPS 地址，不含 URL 账号、密码或片段 |
| `sha256` | 必填，NCP 原文件 64 位十六进制 SHA-256；大小写均可 |
| `byteLength` | 必填整数，8 至 1048576；必须等于原文件实际字节数 |
| `previewUrl` | 可选实际配套样片的完整 HTTPS 地址；为空表示暂无样片 |
| `compatibleModels` | 可选字符串数组，最多 32 项；每项为非空机型名、最多 80 个 UTF-16 单位 |

最多 256 项；重复 JSON 键、重复目录 ID、错误类型、过深结构、无效 UTF-8 或未知版本均拒绝。未知字段不参与应用行为、不保存到规范化目录缓存。字符串字段不得含控制字符。远程 ID 与名称不会用于构造本地文件路径。

## 网络、文件和样片边界

- 所有请求及最多 3 次重定向仅允许 HTTPS；相对重定向先解析，再重新检查协议及 URL。HTTP 降级被拒绝。使用系统默认 TLS 与主机名验证，不设置宽松证书策略。
- 显式关闭自动重定向；连接超时 8 秒，单次读取超时 10 秒，操作检查 45 秒总时限。阻塞操作返回后再次检查总时限，因此总耗时可能额外包含一次阻塞等待。
- 仅接受完整 HTTP 200；拒绝非 identity 内容编码。目录上限 512 KiB，NCP 上限 1 MiB，样片上限 8 MiB；既检查声明长度，也限制实际流长度。
- 完整下载后检查目录中声明的字节数、SHA-256 及 `NcpFile.validate()`，都通过后才提交本地元数据。下载错误、取消、摘要不符及无效 NCP 不进入可读取预设列表。
- SHA-256 验证文件与当前 HTTPS 目录一致，不构成对发布者身份或拍摄效果的额外认证。
- 样片只接 PNG/JPEG/GIF/WebP；检查文件头与结束边界，再用 Android BitmapFactory 检查尺寸并进行受限采样解码。HTML、截断图像、无法解码图像及过大尺寸不会作为有效样片提交。尺寸最多 16384×16384，且不超过 6400 万像素；实际验证解码采样至最长边不超过约 1024。
- 样片缓存不代表照片效果经过相机验证；没有图片时使用“暂无样片”，离线未缓存时显示对应缓存状态。

## 持久化与旧版迁移

- 本地内容置于应用私有 `files/preset-library/`；数据文件及元数据以 SHA-256 命名，各自通过同目录临时文件、fsync 和原子移动提交。平台不支持原子移动时明确失败，不退化为直接覆盖写。
- 元数据文件是提交标记。数据文件提交后若元数据提交失败，可能留下忽略的数据孤件；它不会在列表中显示为已添加。
- 构造库时自动读取 `files/selected.ncp` 和可选的 `files/preset.json`。旧摘要存在时先核对；成功迁移幂等，原文件不删除、不覆盖。迁移或个别条目恢复错误通过 `diagnostics()` 提供给 UI。
- 最多管理 5000 个本地条目。恢复时会检查各 NCP；大库的构造也应放工作线程。
- 目录配置及缓存位于 `files/online-presets/`，同样采用原子保存。全新 Repository 单独构造时不写配置；App 在工作线程显式迁移到统一目录，再按页面操作加载真实目录，不生成占位预设。迁移不改动 `files/preset-library/` 的本地内容，也不清空用户样片。

## 定向验证

运行 `tests/run-library.ps1`。测试使用合成 NCP 与注入的 HTTPS 响应，不访问网络或相机，覆盖实际持久化、迁移、同名与内容去重、损坏检查、原子提交失败、目录验证、缓存、重定向、大小限制、取消、摘要错误、半下载及 HTML 样片拒绝。0.9.5-beta 的库 host 分组 231 项检查通过，新增统一地址迁移、旧目录缓存隔离、下载与导入的原始字节保留，以及非文件配置、读取超限、文件系统写入失败后不访问旧服务和修复后重试等验收。

0.9.3-beta 的库 host 分组为 176 项检查；另一次真实公网验收以 JDK 17 运行生产 `OnlinePresetRepository` / HTTPS 数据层，11 项探针通过，并纳入服务端 76 项完整流程报告 `online-library/build/public-workflow-verification.json`。这是目录与文件数据互通验证，不是 Android 图片解码或手机安装验证。服务端文件接口只接受 `GET`；公网缓存头可能被网关收紧，完整性仍按真实字节、长度和哈希检查。

Android BitmapFactory 的有效图片解码成功路径仅在 SDK 下编译；普通 JVM 的 Android SDK stub 不能证明实际解码行为，该部分需应用集成后验证。合成 NCP 只用于测试结构与文件传输，不宣称可导入相机或具备真实样片。
