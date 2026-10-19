# 手机 App 的 QQ 登录接入

核对日期：2026-10-18。QQ 登录用于手机 App 的普通用户；后台管理员继续使用独立的管理员账号。

本项目 QQ 互联移动应用 AppID 为 **`1105984855`**。2026-10-18 用户确认应用已经审核通过／上线；该审核状态来自用户提供的信息，未访问其 QQ 互联控制台复核。**生产服务端已启用此 AppID，Android 工程已集成腾讯官方 SDK 3.5.19 和 `tencent1105984855` 授权回调 scheme；真实手机 QQ 授权尚未验收。**当前验证链路不需要 AppKey，不应将其发送到聊天或放入 APK。

## 当前需要在 QQ 互联核对的内容

用户反馈 QQ 互联平台登记信息与当前测试包不一致，将自行修改。本项目没有代为修改 QQ 互联控制台；保存后的实际生效情况仍待用户核对。

| 控制台项目 | 当前测试包对应值 |
| --- | --- |
| 移动应用 AppID | `1105984855` |
| Android 包名 | `com.nikonpreset.usbprobe` |
| Android 签名证书 MD5 | `945aa44a13dd8c10155ad0078edf698e` |

以上 MD5 是当前测试 APK 所用签名证书的摘要，不是 APK 文件本身的 MD5。请在此 AppID 的 Android 平台资料中填写与实际安装包一致的包名和签名，再确认修改已保存、生效；后续更换签名或正式发布包时需要重新核对，不能直接沿用不同证书的摘要。

平台资料核对后，在可正常上网、已安装 QQ 的手机上安装当前测试 APK，进入应用的账号登录页，点击“QQ 登录”，阅读说明并选择“同意并继续”，在 QQ 内完成授权。返回应用后应显示 QQ 账号资料；再测试退出、重新登录和取消授权。遇到包名或签名不匹配时，先核对上述平台配置。截图可以保留错误提示，但不要包含授权 token 或 AppKey。

**上述操作是待执行的真实手机验收步骤，不能据此标记 QQ 登录成功。**目前已确认的是 Android 集成完成、生产服务端配置已启用及 5 项公网保护检查通过。

## QQ 互联资料核对

1. 在 [QQ 互联](https://connect.qq.com/) 注册并完成开发者资料审核，准备控制台要求的个人或企业资料。使用能长期管理的开发者账号。官方流程：[成为开发者](https://wiki.connect.qq.com/成为开发者)。
2. 创建 **移动应用**，填写实际应用名称、图标、简介、联系方式和 Android 平台信息。不要为了手机 App 登录创建网站应用。申请及授权范围以控制台当前要求为准：[创建应用](https://wiki.connect.qq.com/__trashed-2)。
3. 当前工程 Android 包名为 `com.nikonpreset.usbprobe`。在提交前确定正式包名和稳定的正式签名，再填写与最终 APK 一致的包名、签名。开发调试 APK 的签名不能自动作为将来正式包的签名。腾讯提供的获取方法见 [Android SDK 常见问题，第 4.3、4.4 节](https://wiki.connect.qq.com/android常见问题)。
4. 准备用户可以访问的隐私政策和 QQ SDK 信息说明。在用户同意后再启用 SDK。接入时应更新实际收集信息、用途、第三方 SDK 目录及账号注销/数据删除说明，参考 [QQ 互联 SDK 隐私保护声明](https://wiki.connect.qq.com/qq互联sdk隐私保护声明)。
5. 当前 AppID 已提供为 **`1105984855`**，后续 Android SDK 和服务端必须使用相同值。AppKey 不要发送到聊天，不要写入网页、App 配置仓库或普通日志。当前 SDK access token 验证方式不使用 AppKey；将来若增加授权码交换，AppKey 只放服务端受保护的配置中。

不要填写猜测的签名摘要，也不要为了调试临时更换用户已安装 App 的签名。用户已确认应用上线，但腾讯控制台登记的包名、签名和当前安装 APK 是否匹配仍需核对；AppID 或审核状态本身不能证明这条手机链路已经成功。

## 手机和服务端流程

1. 手机在用户主动点击 QQ 登录、同意相应说明后，使用腾讯官方 Android SDK 发起授权。授权从官方 Android 登录文档的 `get_simple_userinfo` 基础资料 scope 开始，结合应用实际开通接口确认，不请求 `all`。参考 [登录/校验登录态](https://wiki.connect.qq.com/登录-校验登录态)、[Android SDK 环境搭建](https://wiki.connect.qq.com/qq登录)。
2. SDK 返回的 access token 通过 HTTPS 请求体发送到本项目服务端。服务端不采用客户端自报的 openid、昵称或头像，也不把客户端 `expires_in` 当作自身会话有效期。
3. 服务端只向 `https://graph.qq.com/oauth2.0/me` 发送 access token，并指定 `fmt=json`。验证腾讯返回的 `client_id` 与服务端 `QQ_APP_ID` 完全一致，再采用其 `openid`。参考 [获取用户 OpenID](https://wiki.connect.qq.com/获取用户openid_oauth2-0)。
4. 服务端以验证后的 AppID/openid 调用 `https://graph.qq.com/user/get_user_info`，要求 `ret=0`，只使用昵称和头像。100×100 的 QQ 头像可能缺失，按顺序回退到较小头像。参考 [get_user_info](https://wiki.connect.qq.com/get_user_info)。
5. 本项目用 `(provider, AppID, openid)` 区分普通用户。数据库仅保存必要的身份、资料和登录记录；不保存 QQ access token。服务端签发独立的普通用户会话，普通用户不能通过该会话操作管理员接口。

OpenID 是应用内身份标识，不是用户的 QQ 号码。后台展示昵称、头像、用户 ID、来源、注册时间、最近登录时间、登录次数及账号状态即可；不要推断用户手机号或 QQ 号码。QQ SDK 调用和登录按钮的细节应遵循 [官方接入与审核规范](https://wiki.connect.qq.com/网站审核规范)。

## UnionID 和资料范围

当前不请求、不依赖 UnionID。它用于同一开发者多个应用间的用户关联，仍需按 QQ 互联要求获取对应权限；官方错误码 `100048` 表示未申请该接口权限。文档还说明 UnionID 长度可能超过 32 字节，不能直接套用 OpenID 字段。后续确需跨应用打通再加迁移和账号合并流程。参考 [UnionID 介绍](https://wiki.connect.qq.com/unionid介绍)。

性别、地区、出生年份、QQ 会员等字段不是当前登录必需信息，服务端模块不返回这些字段。昵称或头像不构成权限依据；普通用户仍不能获得管理员能力。

## 服务端配置与失败处理

在函数服务端配置 `QQ_APP_ID`。`server/qq.mjs` 提供：

```js
import { createQqVerifier } from './qq.mjs';

const qqVerifier = createQqVerifier({ appId: process.env.QQ_APP_ID || '' });
qqVerifier.status(); // { provider: 'qq', configured, message }
const verified = await qqVerifier.verify({ accessToken });
// { appId, openid, nickname, avatarUrl }
```

完整的两次腾讯调用共享 8 秒超时；每个响应最多 64 KiB，拒绝重定向和无效 JSON。只返回 HTTPS `qlogo.cn` 或其子域头像；官方旧示例中的 HTTP 头像仅在该可信域升级为 HTTPS，没有合法头像时保留空头像。头像不会在认证过程中被服务端抓取。所有错误信息固定，不包含 access token、openid、腾讯原始响应、带 token 的 URL 或原始异常 cause；应用及平台日志也不应记录登录请求体。

| HTTP 状态 | 错误码 | 处理 |
| --- | --- | --- |
| 503 | `qq_not_configured` | 完成申请并配置真实 AppID |
| 400 | `qq_invalid_token` | 拒绝格式错误的 token，重新授权 |
| 401 | `qq_verification_failed` | 授权失效、应用不匹配或腾讯拒绝，重新登录 |
| 429 | `qq_login_busy` / `qq_login_throttled` | 登录请求过多，等待后重试；不反复自动刷新授权 |
| 502 | `qq_provider_unavailable` | 网络或资料响应异常，保留现有本地功能并允许重试 |
| 504 | `qq_timeout` | 腾讯验证超时，允许重试 |

`node --test test/qq.test.mjs` 在 `online-library` 目录执行。这些测试使用明确的合成腾讯响应，覆盖应用匹配、客户端伪造身份、无 AppID、失效 token、头像域名、响应大小、超时和错误脱敏；通过测试不代表已完成真实 QQ 登录验收。

普通用户 API 以 `/preset-library` 为前缀：`POST /auth/qq` 接收 JSON `{ accessToken }`，返回独立的 `Bearer` 会话、失效时间和最小用户资料；`GET /auth/me` 读取当前资料，`POST /auth/logout` 注销当前会话，后两项使用 `Authorization: Bearer …`。会话有效期为 7 天，每用户最多保留 10 条，数据库只存本系统会话的哈希。后台停用账号时同事务撤销所有普通用户会话，重新启用不会恢复旧 token。

服务端部署目标为 `nikon-d8girdjsr252fd90e` 环境（上海）、`nikon-preset-library` 云函数，公开接口基址为 `https://nikon-d8girdjsr252fd90e-1252602548.ap-shanghai.app.tcloudbase.com/preset-library`。本次配置固定使用 `QQ_APP_ID=1105984855`，与 Android SDK 使用的 AppID 保持一致。

2026-10-18 已执行配置-only入口，回执为 `configuration update requested`，没有上传或替换函数代码。随后函数状态核对为 **Active / Nodejs20.19 / HTTP**，请求编号为 `14e79284-11fb-43ca-ba63-78e393b8c4d2`。无凭据公网检查于 **11:19:43（Asia/Shanghai）**完成，**5/5 通过**；报告为 `online-library/build/qq-verification.json`，其中 `qqConfigured=true`、`userDataCreated=false`。格式错误的 QQ token 当前返回 `400 / qq_invalid_token`，证明服务端配置已启用；未配置时的 `503 / qq_not_configured` 仍保留作为配置缺失的失败分支。

仅配置 QQ 时优先使用配置脚本，从 `online-library` 目录运行：

```powershell
# 默认只预览目标和是否有变更，不写云端。
node scripts/configure-qq.mjs --app-id 1105984855
# 在已授权范围内将 AppID 写入现有云函数。
node scripts/configure-qq.mjs --app-id 1105984855 --apply
node scripts/cloud-status.mjs
# 待云函数重新达到 Active 后，执行无管理员凭据的公网检查。
node scripts/verify-qq.mjs --expect-configured
```

配置脚本将现有环境变量作为不输出、不落盘的值原样合并，只更新 `QQ_APP_ID`，不读取本机 `.local` 服务凭据，不创建云端密钥，也不上传代码或修改内存、超时配置。默认预览也需要读取现有云函数配置，但输出仅包含环境、函数名、公开 AppID 和动作，不包含其他环境变量。云 API 只提供整组环境变量更新，因此应避免与其他配置修改同时运行。重复应用同一 AppID 不会再次提交更新。

原完整部署入口仍支持 `--deploy --allow-service-credential --configure-qq`，从当前进程 `QQ_APP_ID` 读取值，同时还会读取/复用服务凭据和上传代码。普通部署保留云端 QQ 配置，不会从本机环境自动启用。只启用 QQ 时不需要执行整包部署。

`verify-qq.mjs` 检查公开服务环境、QQ 配置状态、格式错误 token 拒绝、跨源拒绝，以及普通用户和管理员接口的匿名访问保护，共 5 项，不读取本地秘密、不请求腾讯、不创建用户或会话，结果保存为 `online-library/build/qq-verification.json`。它只能证明服务端配置和保护生效，不证明真实腾讯 token 或手机授权成功。旧管理员验收脚本在 QQ 已配置后需显式加 `--qq-configured`；该脚本会在内部读取管理员交付文件并建立后退出管理员验收会话。

Android 使用的原版 SDK JAR 为 `open_sdk_3.5.19_r9483ffc7_lite.jar`，来源、SHA-256 和依赖信息见 `app/libs/README.md`。SDK 仅在用户接受 QQ 登录说明后初始化，scope 为 `get_simple_userinfo`；包名仍为 `com.nikonpreset.usbprobe`，腾讯控制台签名须与实际测试 APK 一致。

本地 Node 回归 **74/74 通过**，语法及源码检查覆盖 **28 个 JavaScript 模块**；腾讯成功/失败响应测试使用合成资料，不代表生产腾讯授权。还需要验证：正式签名 APK 拉起 QQ、授权取消、用户拒绝、不同 QQ 用户建立独立档案、同一用户重复登录更新资料、授权撤销、会话退出、受限账号拒绝登录，以及普通用户无法调用后台管理接口。只有这些真实手机链路通过后，才能宣布 QQ 登录已完成端到端验收。
