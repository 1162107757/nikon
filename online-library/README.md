# 在线预设库管理后台

这套后台用于上传 NCP 与样片、编辑预设草稿和分类、查看版本、上架和下架，以及管理普通 App 用户。Android App 继续读取现有 HTTPS 目录格式，无需接入 CloudBase SDK，也无需把云端凭据放入 App。

**分类功能（后台 1.3.0）：** 预设编辑器支持选择建议分类或输入自定义分类，列表支持按分类筛选，历史记录保留各版本分类。建议值为人像、风景、胶片、黑白、日常、夜景；并非固定枚举，也不另设分类表或管理菜单。分类为可选的单个 `category` 字符串；旧数据与未填写的分类均显示“未分类”。修改分类后先保存草稿、再发布更新，App 才会看到新分类。迁移见 [004-preset-categories.sql](database/004-preset-categories.sql)；真实 PostgreSQL 验收入口见 [category-database-acceptance.sql](test/category-database-acceptance.sql)，包含 20 项断言且强制回滚全部合成数据。数据库验收和公网部署需单独执行，不能据本地 Node 测试宣称已上线。

**历史发布记录（2026-10-18，后台 1.1.0）：** 当时的函数只读状态为 Active / Nodejs20.19 / HTTP。左侧提供“预设管理 / 用户管理”，右侧显示对应内容；窄屏改为顶部导航。用户模块支持搜索、分页、详情和启停确认，QQ 当时显示“待接入”。该轮 50 项 Node 测试、23 模块语法检查及真实 PostgreSQL 用户模块 24 项回滚断言通过；Web UI 独立复核为 SHIP，仅限当时审阅的网页范围，见[设计复核](../docs/design-review/admin-management-2026-10-18.md)。

后台 1.1.0 是独立的服务端与 Web 升级，不更改 Android 0.9.3-beta（42）APK 或其交付记录。2026-10-18 用户已提供 QQ 互联 AppID `1105984855` 并确认已经审核通过／上线，审核状态未由本项目访问控制台复核。本次生产服务端已启用此 AppID，Android 工程已集成腾讯官方 SDK 3.5.19 与 `tencent1105984855` 回调，真实手机 QQ 授权尚未验收，见 [QQ 登录说明](../docs/qq-login.md)。

**QQ 服务端配置验收（2026-10-18）：** 仅更新现有云函数的 `QQ_APP_ID`，未上传代码；随后状态为 Active / Nodejs20.19 / HTTP。11:19:43 +08:00 无凭据公网检查 **5/5 通过**，`build/qq-verification.json` 记录 `qqConfigured=true`、`userDataCreated=false`；没有请求腾讯、创建用户或 App 会话。本次本地 Node 回归 **74/74**、语法及源码检查 **28 模块**通过，均不能替代真实手机授权验收。

**本轮线上验收：** 用户管理 API 于 09:22:11 +08:00 完成 16 项检查，报告 `build/user-management-verification.json` 为 `passed=true`，涵盖管理员访问隔离、分页、搜索、输入校验、CSRF、普通 Bearer 隔离、QQ 未配置的显式失败与退出撤销；没有创建 QQ 用户或 App 会话，线上用户列表目前真实为空。原有上传、草稿、发布、下载、版本冲突、下架与会话撤销流程于 09:24:08 +08:00 重新完成 76 项检查，零失败，报告为 `build/public-workflow-verification.json`。此前基础公网 HTTP 12 项报告 `build/http-verification.json` 保留其原验收范围。

本轮完整流程同时调用 JDK 17 上的生产 Android Java 在线库数据层，11 项真实 HTTPS 探针检查通过；未执行 Android 图片解码，不代表手机运行或相机兼容性验收。原有 PostgreSQL 发布事务另有 24 项回滚断言记录。本轮仅使用合成文件，已精确清理 1 份预设、2 条版本、2 条资源和 2 个私有对象；尚未发布真实预设，首批内容需管理员上传并上架。Android 0.9.3-beta 及其交付记录不变，本轮没有新增 APK 构建或手机安装。

本地浏览器使用合成用户验证“山海”搜索仅返回一项、停用确认及状态更新、退出后回到登录页，浏览器错误日志为空。临时页面已关闭、视口和主题恢复、本地合成服务已停止；这些网页检查不代表真实 QQ 授权成功。

## 目标环境与地址

| 配置 | 值 |
| --- | --- |
| CloudBase 环境 ID | `nikon-d8girdjsr252fd90e` |
| 地域 | 上海，`ap-shanghai` |
| 正式访问域名 | `https://huevia.cc` |
| 公开网关 Origin | `https://nikon-d8girdjsr252fd90e-1252602548.ap-shanghai.app.tcloudbase.com` |
| 数据库/存储 API Origin | `https://nikon-d8girdjsr252fd90e.api.tcloudbasegateway.com` |
| HTTP 路径前缀 | `/preset-library` |
| PostgreSQL 对象存储 bucket | `nikon-presets`，须保持私有 |

已公开的后台入口（主域名根路径也会进入后台）：

```text
https://huevia.cc/preset-library/admin/
```

App 使用的公开目录地址：

```text
https://nikon-d8girdjsr252fd90e-1252602548.ap-shanghai.app.tcloudbase.com/preset-library/catalog.json
```

`PUBLIC_BASE_URL` 只填写 Origin，路径前缀由 `BASE_PATH` 单独控制。公开文件与目录使用稳定 HTTPS 地址，不使用 `cloud://`、HTTP、账号密码地址或带临时签名查询参数的下载链接。

初始管理员账号为 `admin`，随机密码保存在本机 Git 忽略的 `online-library/.local/initial-admin.json`。请在本机查看交付文件，勿将其内容写入 Git、部署包、截图或共享日志；App 浏览和下载公开预设不需要该账号。

2026-10-21 已绑定正式域名 `huevia.cc`，HTTP 自动跳转 HTTPS；[测试版下载页](https://huevia.cc/preset-library/download/?channel=beta)与[公开目录](https://huevia.cc/preset-library/catalog.json)可直接访问。当日随后将 DNSPod 的 `@` 改为 `A 118.24.164.117`，由备案轻量服务器的 Caddy 代理到原 CloudBase 正式域名网关，浏览器保持 `huevia.cc`。前端证书由 Caddy 自动续期；CloudBase 上游独立证书 `bjxjYb4F` 仍于北京时间 **2027-01-19 10:59:59** 到期，须在到期前更新网关绑定。未绑定 `www.huevia.cc`。详见[轻量服务器配置与验收](../docs/validation/lighthouse-huevia-20261021/README.md)。

云函数增加 `ALLOWED_BROWSER_ORIGINS=https://huevia.cc`，仅允许精确 HTTPS Origin，不支持通配符或路径；原 Origin 仍可用。`PUBLIC_BASE_URL` 保持原值，兼容已安装 Android 的更新下载与头像地址，公开资产和既有数据库快照链接保持稳定。后台私有预览与网页 APK 下载使用当前访问域名，避免跨域 Cookie 和下载地址校验失败。此次未重新构建或发布 Android 安装包。

本地完整测试 **138 项**、语法检查 **58 模块**通过；线上 **19 项**检查通过，包括 241 款新旧域名目录一致、管理员登录及会话撤销、CSRF、HTTP 跳转，以及测试版 APK 完整字节与 SHA-256。详见[域名配置与验收](../docs/validation/domain-huevia-20261021/README.md)。旧默认域名仍遵循平台的[默认域名访问机制](https://docs.cloudbase.net/service/alias)。

## 日常使用与发布规则

1. 用独立的后台管理员账号登录。
2. 新建预设，上传 NCP；网页可附 PNG、JPEG 或 WebP 样片，填写名称、分类、简介、适用机型和排序。底层上传接口另外支持 GIF。
3. 保存草稿后检查文件与样片。保存草稿会形成版本记录，但不会改变用户当前看到的已发布内容。
4. 点击上架，将当前草稿作为已发布快照。已有上架内容被编辑后，需要再次上架才会更新公开目录。
5. 下架只让预设退出当前目录，保留草稿、历史版本和文件；需要时可再次上架。

效果图为可选项。只上传有效 NCP 并保存名称即可上架；没有效果图时，公开目录的 `previewUrl` 为空，App 使用默认模板图并标注“模板示意”，不将其作为真实预设样片。后续补充效果图仍需先保存草稿、再发布更新，才会改变 App 中的在线内容。已有图片继续执行完整内容与元数据校验，不会因效果图可选而放行无效文件。

后台写操作携带 `expectedRevision`。如果其他窗口已修改同一预设，服务器返回版本冲突，需刷新后确认内容，避免旧页面覆盖新修改。版本接口提供历史快照查看，当前没有独立的自动回滚接口。

草稿与发布信息分别保存。公开 `catalog.json` 仅从 `status=published` 的 `published` 快照生成，不读取外层草稿、管理员信息或内部存储路径。目录按已发布排序值升序排列，同值按预设 ID 稳定排序。

### 文件不可变与下架边界

文件按 SHA-256 内容寻址。相同文件复用同一资源；替换文件会产生新的内容地址，已发布的旧链接不会被新文件覆盖。数据库保留每次保存的草稿版本以及当前发布版本。

bucket 保持私有，不给匿名用户直接读取、列举或写入权限。后台通过服务端凭据读取私有对象，再由 `/files/…` 返回经过大小与 SHA-256 核对的内容：

- 从未发布的文件，仅已登录管理员可通过受保护的资源接口读取。
- 文件曾被成功发布后，数据库的 `ever_published` 会保持为 `true`，公开文件路由才允许读取。
- 下架不会撤回已发布文件，也不会清除 App 已缓存的目录或已下载的本地预设。旧文件链接继续可读，以免破坏旧目录与历史版本。

因此，发布代表允许公开分发对应文件。需要彻底撤回文件时，不能把“下架”等同于撤销访问，须另行设计撤回与缓存处理流程。

## Android 包名迁移（75 起）

Android 75 / 0.13.7-beta 起安装包名为 `cc.huevia`。新 APK 发布前先应用 `database/012-huevia-package.sql`，再部署对应服务代码。服务端校验同时接受新旧两种固定包名及原签名证书；`/releases/latest` 按 `packageName` 隔离查询，未传包名的旧客户端仍只返回 `com.nikonpreset.usbprobe` 的版本。新客户端和新版下载页明确查询 `cc.huevia`，撤回记录仍在各自包名内阻止回退。

2026-10-21 已按用户要求应用迁移 012，并发布 **0.13.8-beta（76）**；17:25:17（北京时间）下载服务为 Active。原有 8 个测试版本均已撤回，旧下载链接返回 404，仅新版保持上架。正式渠道仍为空，下载首页明确回退到标注的测试版。新旧包名的应用可共存，旧包名的数据不自动迁移；已有 `cc.huevia` 安装可覆盖升级。

本次 COS 写入持续出现 TLS 握手超时，因此将同一份已验证 v2/v3 签名的 APK 随下载服务发布。文件位于 `server/bundled-apks/<sha256>.apk`，通过原有版本下载路由读取，仍执行上架状态、流量预算、长度、哈希与读后撤回检查；没有随包文件时继续读取原私有对象存储。**76 仍上架期间，后续服务发布必须保留这份 APK。** 普通后台上传流程不变。151 项服务测试、55 项隔离发布运行测试及 63 模块语法检查通过；生产 14 项检查通过，详见[发布验证](../docs/validation/huevia-release-76-20261021/README.md)。

## 用户管理与 QQ 登录边界

用户管理面向普通 App 用户，与后台 `admin` 账号和管理员 Cookie 会话分开。列表可按昵称或用户 ID 搜索，按全部 / 正常 / 已停用筛选并分页；详情显示昵称、头像、用户 ID、来源、注册时间、最近登录、登录次数及状态，QQ OpenID 仅显示掩码。没有真实用户时显示空列表，不生成演示生产用户。

启用和停用均需管理员确认，并携带详情中原样返回的 `updatedAt` 作为 `expectedUpdatedAt`；版本冲突须刷新。停用用户与撤销该用户全部普通会话在同一数据库事务完成，重新启用不会恢复旧 token。

生产服务端已启用 QQ 互联 AppID `1105984855`，由用户确认审核通过／上线。服务端验证模块向腾讯核对 access token、AppID 和返回身份，再读取资料；客户端自报的 openid、昵称和头像不能作为可信身份。数据库不保存 QQ 原始 access token，普通用户获得本系统独立的 7 天 Bearer 会话，每用户最多保留 10 条，只持久化会话哈希。普通用户 Bearer 不授予后台管理权限。模块腾讯响应测试均为明确 mock，真实手机 QQ 授权仍待验收，详见 [QQ 申请与服务端接入](../docs/qq-login.md)。

## 校验与大小限制

| 内容 | 当前限制 |
| --- | --- |
| NCP 上传和校验 | 最大 1 MiB；只接受经结构检查的 `NCP\0` / `0100` 文件 |
| 样片 HTTP 上传 | 最大 4 MiB，由当前上传接口限制 |
| 独立样片校验库 | 最大 8 MiB，对齐 Android 在线样片上限；不代表上传接口接受 8 MiB |
| 样片尺寸 | 每边最多 16384，最多 6400 万像素 |
| 已发布目录 | 最多 256 项；最终 UTF-8 JSON 最多 512 KiB |
| 名称/简介 | 名称必填且最多 160 字符；简介最多 2000 字符 |
| 分类 | 可选单个字符串；NFC 规范化后最多 24 个 Unicode code points；空串表示未分类 |
| 适用机型 | 最多 32 项，每项最多 80 字符 |
| 排序 | `-1000000` 至 `1000000` 的整数 |

服务器根据完整内容检查签名、长度、容器结构与文件元数据，不依据扩展名猜测 NCP。样片校验包含 PNG 数据块校验、JPEG 标记和扫描结构、GIF 帧与 LZW 像素数量、WebP 容器及帧结构；这不等同于完整 JPEG/WebP 解码器。Android 仍执行自己的下载、大小、哈希及图像解码检查。

名称、简介和机型字段会规范化首尾空白，拒绝控制字符。简介当前使用单段文字，不接受换行，以保持 Android 目录解析兼容。

分类先拒绝原始 C0/C1 控制字符与不配对代理项，再执行 NFC 与 ECMAScript `trim()`；不按 UTF-16 单元计数，因此 24 个 emoji 也满足长度上限。`全部` 和 `未分类` 是界面保留名称，不可作为实际分类，未分类请传空串。JSON 缺省或 `null` 均规范为空串。服务端不会按名称猜测类别，历史草稿与已发布快照无需批量重写。公开目录保持 `version: 1`，每个条目增加 `category` 字段，仅从已发布快照读取；旧版本缺省输出 `"category": ""`。

## 本地检查、测试与打包

需要 Node.js `20.19` 或更新版本。项目运行时使用 Node 内置模块，没有第三方 npm 依赖。

在 `online-library` 目录执行：

```powershell
npm run check
npm test
npm run package
```

也可以独立运行校验测试：

```powershell
node --test test/validation.test.mjs
```

[test/database-acceptance.sql](test/database-acceptance.sql) 是需明确发起的真实 PostgreSQL 集成验证，默认 `npm test` 不执行它。它只插入随机命名的合成资源和预设，在内层异常子事务末尾强制回滚，再断言资源、预设、版本及目录中均无测试残留；不读取管理员、密码哈希或服务凭据。本次通过控制面的 `ExecutePGSql` 完成了 24 项断言，未向存储上传合成文件，也未提交测试预设。

后台 1.1.0 的 [test/user-database-acceptance.sql](test/user-database-acceptance.sql) 另有 24 项真实 PostgreSQL 断言于 09:07:47 +08:00 通过，报告为本机 `build/user-database-verification.json`；所有合成用户、身份和会话影响已回滚。它验证用户存储与会话事务，不调用腾讯、不证明真实 QQ 登录。当前 1.1.0 部署包包含 14 个文件、50113 bytes，SHA-256 为 `65c2c2ae7029ed9fffae7e18adbf6c962e263d53288a01ff600321807dc743c1`；后续重新打包以对应 `build/manifest.json` 为准。

打包产物为 `build/nikon-preset-library.zip` 和 `build/manifest.json`。清单记录压缩包与文件的 SHA-256。部署包包含 `server/`、`admin/`、`package.json` 与可执行的 `scf_bootstrap`，不包含测试、数据库迁移、运维脚本或 `.env`。数据库初始化与管理员设置须单独执行，不能仅上传 ZIP 就视为部署完成。

### 显式本地预览

```powershell
node scripts/preview-local.mjs
```

默认访问 `http://127.0.0.1:8767/preset-library/admin/`。这个入口明确使用 `MemoryStore`、内存对象存储和合成文件，只用于界面联调；退出后内容丢失，**不会连接或写入 CloudBase**。测试身份只供本地使用，不是线上初始管理员，本文不提供测试密码。

生产入口为 `npm start` / `server/index.mjs`，使用真实云端存储适配器。未配置服务密钥时，受影响接口返回配置未完成，不会自动切换为 `MemoryStore`。不要把本地预览脚本、测试身份或合成预设加入部署包。

## 部署配置与凭据边界

`.env.example` 只列出配置名称与非敏感默认值；当前服务器不自动加载该文件。将配置注入云函数运行环境：

| 环境变量 | 用途 |
| --- | --- |
| `CLOUDBASE_ENV_ID` | 上述目标环境 ID |
| `CLOUDBASE_API_ORIGIN` | 上述数据库/存储 API Origin |
| `CLOUDBASE_STORAGE_BUCKET` | 私有 PostgreSQL 对象存储 bucket，默认 `nikon-presets` |
| `PUBLIC_BASE_URL` | 上述公开上海网关 Origin |
| `BASE_PATH` | 默认 `/preset-library` |
| `CLOUDBASE_SERVICE_KEY` | 仅服务端使用的高权限服务密钥 |
| `QQ_APP_ID` | QQ 互联移动应用 AppID，本项目使用 `1105984855`，须与 Android SDK 一致 |
| `PORT` | 本地监听端口；云函数启动脚本使用 `9000` |

`CLOUDBASE_SERVICE_KEY` 具有较高的数据库和存储权限。**获取、创建或注入它必须有用户明确批准；本次相关操作已获授权并完成服务端配置。** 密钥只能通过受控的服务端配置注入，不能写进 Android、后台静态资源、Git、部署清单、命令示例或日志，也不能作为浏览器登录凭据使用。命令行的允许标记不能替代用户授权。

数据库使用 [database/001-library.sql](database/001-library.sql) 创建管理员、会话、资源、预设及版本表，以及草稿保存与发布事务。[database/002-users.sql](database/002-users.sql) 增加普通用户、QQ 身份与普通会话三表和五个 RPC。表与 RPC 对匿名和普通认证角色关闭访问，仅服务端 `service_role` 可操作；普通用户经服务端业务接口访问自己的会话资料，不直连数据库。存储侧需要单独创建私有 bucket，并核对匿名权限；SQL 表权限不能替代 bucket 权限。

### 初始化、管理员与部署脚本

管理员没有公开注册接口，也没有可用于线上的固定默认密码。[scripts/setup-cloud.mjs](scripts/setup-cloud.mjs) 已实现以下入口；以下命令包含云端变更，需在授权范围内执行，并使用目标环境的已登录 CloudBase CLI。命令从 `online-library` 目录运行：

```powershell
node scripts/setup-cloud.mjs --help
node scripts/setup-cloud.mjs --migrate
node scripts/setup-cloud.mjs --bootstrap-admin
```

`--migrate` 按文件名顺序执行 `database/` 中所有符合 `NNN-name.sql` 的迁移，当前依次为 `001-library.sql`、`002-users.sql`、`003-password-accounts.sql`、`004-preset-categories.sql`，再创建/核对私有 `nikon-presets` bucket。004 只扩展公开目录函数，保留排序、发布事务、大小限制与仅 `service_role` 可执行的权限，不重写旧预设。`--bootstrap-admin` 为 `admin` 生成随机的独立初始密码，以 `server/auth.mjs` 的带随机盐 scrypt 哈希保存到数据库；本机交付信息保存在 Git 忽略的 `.local/initial-admin.json`，脚本不打印密码。已存在的管理员会被保留，不能用此入口重置未知账号的密码。

运行检查、测试和打包后，使用明确允许服务凭据操作的部署入口：

```powershell
npm run check
npm test
npm run package
node scripts/setup-cloud.mjs --deploy --allow-service-credential
```

`--deploy` 只在已获明确授权后使用。脚本在受控本机 `.local/service-credential.json` 中保存/复用目标环境服务凭据，再注入云函数配置；`.local/deployment-state.json` 记录函数创建状态。不要把这些文件放入 Git、部署包或共享日志，也不要删除状态文件后盲目重复创建服务密钥。当前部署目标函数名为 `nikon-preset-library`，Node.js 运行时为 `20.19`。

更新既有函数时，普通 `--deploy` 会保留现有环境变量，再合并本项目必须的服务端配置，不因更新代码清空已有供应商配置，也不会读取本机 `QQ_APP_ID` 自动启用 QQ。明确追加 `--configure-qq` 时，脚本才读取当前进程的真实 `QQ_APP_ID` 并写入云函数。

只配置现有函数的 QQ AppID 时使用 `node scripts/configure-qq.mjs --app-id 1105984855` 预览，明确追加 `--apply` 才写入。此入口只合并更新 `QQ_APP_ID`、保留其他现有环境变量，不读取本机 `.local` 服务凭据、不创建密钥、不上传代码、不修改内存或超时。云函数恢复 `Active` 后运行 `node scripts/verify-qq.mjs --expect-configured`，可做 5 项无需管理员凭据、不会请求腾讯或创建用户的公网检查。不得同时修改同一函数的其他环境配置。配置 AppID 也不等于完成真实手机 QQ 授权验收。

数据库、私有 bucket、初始管理员与函数代码/配置已由上述流程完成。**`--deploy` 不会自动创建或放行公网网关路由。** `/preset-library` 路由在用户明确授权后单独创建；后台 1.1.0 当时已部署为 Active，线上用户接口 16 项与重新执行的文件流程 76 项检查通过，生产 Java 数据层 HTTPS 探针 11 项通过。上述历史验收时 QQ 未配置；本次 AppID `1105984855` 已启用并通过 5 项无凭据公网检查，手机验收仍待进行，见独立的 [QQ 登录说明](../docs/qq-login.md)。

管理员密码须为 8 至 256 字符，不要求字母、数字或符号组合。脚本应避免把明文密码放进命令参数、代码或日志；密码重置后应清理该管理员的既有会话。浏览器使用有限期的 HttpOnly 会话 Cookie，写操作另外检查同源和 CSRF Token。

后台顶部提供“修改密码”：填写当前密码、新密码和确认新密码，新密码至少 8 个字符且与当前密码不同，不要求字符组合，空格和符号原样保留。修改成功后，该管理员在所有设备上的会话都会退出，使用新密码重新登录；其他管理员及 App 用户不受影响。当前密码错误保留登录，连续尝试受账号限频保护。

用户追加要求的最少 8 位策略已于 2026-10-21 14:01:45（北京时间）上线，仅更新三处密码相关运行文件，无需数据库迁移；150 项服务测试、63 模块语法检查及浏览器 8 位纯数字操作通过。14:04:21 完成线上 11 项检查并清理临时测试账号，见[8 位密码验证](../docs/validation/admin-password-8-20261021/README.md)。

2026-10-21 经用户明确批准，迁移 013 与改密功能已上线；13:47:18（北京时间）函数为 Active，现有环境配置保持。发布以原线上代码为基线，仅替换六个改密相关文件，排除工作区其他待发改动。149 项服务测试、34 项隔离 PostgreSQL 检查、63 模块语法／源码检查通过；13:54:23 线上 11 项验收通过，临时测试管理员及其会话／尝试记录已清理，现有真实管理员密码未改变。见[修改密码验证记录](../docs/validation/admin-password-20261021/README.md)。

部署此功能时，获批后先运行 `node scripts/apply-admin-password.mjs --apply` 执行 `database/013-admin-password.sql` 并核对函数权限，再发布服务端与后台静态文件。迁移仅新增服务端专用事务函数，不修改现有密码或会话；管理员登录和改密在同一账号行锁下核对已验证的密码哈希，防止并发旧密码登录或覆盖新密码。迁移执行与代码部署仍遵循下方云端变更批准流程。

### 预期部署顺序

1. 核对目标环境与地域；取得获取/注入服务密钥及执行云端变更的明确批准。
2. 通过受控流程配置密钥，初始化数据库，创建并核对私有 bucket，初始化独立管理员。
3. 运行本地检查、测试和打包，核对清单及 ZIP 不含凭据或本地测试内容。
4. 配置 Node 20 运行时、HTTP 服务与环境变量；`scf_bootstrap` 在 `/var/user` 启动监听 `9000` 的服务器。
5. 将目标网关 `/preset-library` 路由指向该服务。处理器兼容网关保留或去掉此前缀，但最终公开 URL 仍须与 `PUBLIC_BASE_URL` 和 `BASE_PATH` 一致。
6. 验收健康检查、管理员登录、私有草稿文件、上传、版本冲突、上架/下架、公开目录与文件哈希；使用经过确认的真实预设发布首批内容。
7. 在 App 中核对目录地址并验证下载。0.9.3-beta 起从未保存过在线库配置的安装自动初始化上述地址；已有自定义或关闭配置的用户自行决定是否修改。

## 当前 HTTP 接口

以下路径均以 `/preset-library` 为前缀。

| 方法 | 路径 | 访问与作用 |
| --- | --- | --- |
| `GET` | `/admin/` | 后台界面 |
| `GET` | `/health` | 服务状态；不代表数据库/存储全链路验收通过 |
| `GET` | `/catalog.json` | 匿名读取已上架目录 |
| `GET` | `/files/{asset-id}.{extension}` | 匿名读取曾发布的不可变文件 |
| `GET` | `/api/session` | 当前登录信息与 CSRF Token |
| `POST` | `/api/login`, `/api/logout` | 登录、退出；变更请求检查同源 |
| `POST` | `/api/password` | 修改当前管理员密码；`currentPassword`、`newPassword`、`confirmPassword`，须登录与 CSRF；成功后撤销该管理员全部会话 |
| `GET`, `POST` | `/api/presets` | 管理员列出预设、创建草稿 |
| `PUT` | `/api/presets/{id}` | 管理员更新草稿，需当前版本号 |
| `GET` | `/api/presets/{id}/versions` | 管理员查看版本 |
| `POST` | `/api/presets/{id}/publish` | 管理员上架当前草稿，需当前版本号 |
| `POST` | `/api/presets/{id}/unpublish` | 管理员下架，需当前版本号 |
| `POST` | `/api/uploads?kind=ncp\|preview&filename=…` | 管理员上传原始二进制内容 |
| `GET` | `/api/assets/{asset-id}/content` | 管理员读取含未发布草稿在内的文件 |
| `GET` | `/api/users` | 管理员搜索、筛选和分页查看普通用户，并读取 QQ 接入状态 |
| `GET` | `/api/users/{id}` | 管理员查看用户详情，QQ OpenID 仅掩码 |
| `POST` | `/api/users/{id}/status` | 管理员启停，需 `status` 和最新的 `expectedUpdatedAt` |
| `POST` | `/auth/qq` | 普通 App 用户提交 JSON `{ accessToken }`；当前返回 `503 / qq_not_configured` |
| `GET` | `/auth/me` | 使用普通用户 Bearer 读取自己的资料和会话失效时间 |
| `POST` | `/auth/logout` | 使用普通用户 Bearer 撤销当前会话 |

除登录请求外，管理写操作还需要有效会话与 `X-CSRF-Token`。日常使用直接通过后台页面完成，不需要手工组装这些请求。

预设创建/更新 JSON 可包含 `category`（规则见上表）；普通查询、版本记录与公开目录都会显式返回该字段，旧数据默认为空串。后台按分类筛选当前已读取的管理列表，不新增公开写接口；与其他元数据一致，更新请求省略分类等同于未分类，因此调用方应随完整草稿一并提交当前分类。

用户列表参数为 `q`（最多 80 字符，昵称或 ID 的字面子串）、`status=all|active|disabled`、`page`（从 1 开始）和 `pageSize`（默认 20，最多 100）。响应包含 `items/total/page/pageSize/summary/qqLogin`，其中 `summary` 是全库总数、正常数和停用数，`total` 是当前条件匹配数。普通 `/auth/me`、`/auth/logout` 使用 `Authorization: Bearer …`，不接受管理员 Cookie 替代普通会话。

两类文件下载接口均须使用 `GET`。实测公网网关会将文件 `HEAD` 响应的 `Content-Length` 改为 `0`，因此服务器明确拒绝文件 `HEAD`，返回 `405` 和 `Allow: GET`；App 与网页的实际下载使用 `GET`，不受此限制影响。

公网实际响应中，NCP 保留 `public, max-age=31536000, immutable`，样片被网关收紧为 `no-store, no-cache, must-revalidate, max-age=0`。这是缓存策略差异；文件完整性依据实际 `GET` 内容、长度、ETag 和 SHA-256 核对，不以缓存头作为完整性通过条件。

## Android App 接入

Android 0.9.3-beta（versionCode 42）已内置上述完整 `catalog.json` 地址，仅在从未保存过在线库配置时于工作线程初始化；保留既有自定义地址、明确留空关闭的选择及损坏配置，不静默替换。保存默认地址失败时提示用户重新配置，本地预设仍可使用。

需手工切换时，在 App 的「设置 → 在线预设库 → 在线预设库地址」填入上面的 HTTPS 地址，点击「保存并加载」；留空可关闭。App 按既有格式读取 `version: 1` 和 `presets`，下载后继续校验 NCP 大小、SHA-256 与格式。

App 不需要 CloudBase SDK、数据库直连、服务密钥或后台管理员账号。已有本地预设和相机导入流程不依赖后台登录。生产 Java 在线库的数据层已通过真实 HTTPS 目录与文件互通检查；Android 图片解码、手机运行及真实相机流程未在本轮新增验收。

## 社区评论审核

账号创建后的前 24 小时内，只要尚无一条通过审核并公开的评论，其新评论和回复都会进入 `pending`。创建接口仍返回 `201` 和 `comment.status=pending`，待审正文不进入公开评论、回复、定位接口，也不向被回复用户发送消息。后台按 `status=pending` 筛选，使用原审核接口的 `decision=restore` 通过，或 `decision=hide` 与 `reason` 驳回。通过回复与生成一条站内通知在同一事务内完成；重复审核、自回复不重复或额外通知。取消尚未公开的评论不会暴露作者或生成公开删除占位。创建超过 24 小时的普通账号可直接发表低风险评论；所有评论仍受禁言、频率和日配额限制。

已部署的 `database/005-community.sql` 可重复应用：它扩展原三状态检查并保留已有内容的公开历史。隔离 PostgreSQL 验收通过 `node online-library/test/community-postgres.mjs` 运行，覆盖旧表升级、重复迁移、待审隐私、审核通知原子性与完整回滚，不连接线上数据库。
