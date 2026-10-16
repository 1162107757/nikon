# 本地预设库与统一在线目录

此数据层不附带预设、照片或默认在线地址，也不声明尼康官方服务接入。在线地址由应用配置，真实目录与样片需由后续资源维护者提供。下载到手机和写入相机是独立流程；本数据层不调用相机。

## UI 接口

包为 `com.nikonpreset.usbprobe.library`。构造、迁移、文件读取、下载及图像校验均放在工作线程。

```java
PresetLibrary library = new PresetLibrary(getFilesDir());
List<PresetRecord> records = library.list();
PresetLibrary.AddResult result = library.importFile(originalFileName, originalBytes);
byte[] originalNcp = library.readNcp(result.record.id);
library.attachPreview(result.record.id, persistentContentUri.toString());

OnlinePresetRepository repository = new OnlinePresetRepository(getFilesDir(), library);
repository.setEndpoint(httpsManifestUrl); // 空字符串清除配置，保留显式可打开的缓存
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
- `cachedCatalog()` 是明确的缓存入口，可显示旧地址上次保存的目录；消息注明缓存及文件可能尚未下载。清空或更改地址不会删除已经下载的本地文件。
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
- 目录配置及缓存位于 `files/online-presets/`，同样采用原子保存。首次无配置、无缓存时为空状态。

## 定向验证

运行 `tests/run-library.ps1`。测试使用合成 NCP 与注入的 HTTPS 响应，不访问网络或相机，覆盖实际持久化、迁移、同名与内容去重、损坏检查、原子提交失败、目录验证、缓存、重定向、大小限制、取消、摘要错误、半下载及 HTML 样片拒绝。

Android BitmapFactory 的有效图片解码成功路径仅在 SDK 下编译；普通 JVM 的 Android SDK stub 不能证明实际解码行为，该部分需应用集成后验证。合成 NCP 只用于测试结构与文件传输，不宣称可导入相机或具备真实样片。
