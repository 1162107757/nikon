# 0.14.0-beta 已发布

版本号 80，包名 `cc.huevia`，渠道 `beta`，状态 `published`。

- [测试版下载页](https://huevia.cc/preset-library/download/?channel=beta)
- [本次 APK](https://huevia.cc/preset-library/releases/e7cb7d02-d9bc-4a59-9f00-a42bcf2a3659/file)
- [测试说明](../../../dist/huevia-0.14.0-beta-TEST-NOTES.md)
- [机器可读发布回执](publication.json)

公开文件为最终通过验证的 APK：3,458,669 字节，SHA-256 `4aea78f19ddc4e245e27470b29c9f111674f74c2e22b3229d03a2ec5fd6834bb`。主域名及 CloudBase 域名各实际下载一次，与本地签名包逐字节一致；两域名的最新测试版接口均返回 80 / `0.14.0-beta` 及相同包名、大小、SHA、签名、最低 SDK 和发布说明。浏览器重新加载公开下载页后，显示 `Huevia 0.14.0-beta`、选中测试版、3.30 MiB，下载链接指向本次 release ID；13 项基础调节的更新说明可见。

此次使用现有服务凭据与既有发布 RPC 登记并发布签名包。代码部署基于线上原始归档，仅增加 `server/bundled-apks/4aea78f19ddc4e245e27470b29c9f111674f74c2e22b3229d03a2ec5fd6834bb.apk`。所有 39 个既有文件内容及 Unix 模式、函数配置、旧发布记录均已核对保留。云端会重新压缩归档，因此请求／观察 ZIP 的容器哈希不同；完整成员路径、内容字节、数量、大小及模式一致。

请求归档 SHA：`f830095332d2fe0ec0b3beb1c3bf07f03b10780c6e735cd39bec328719bb8c70`。

观察归档 SHA：`af7b0ad60e1d0cefaa91737150279669b2af462320c559912c4c184d3b4ebd7e`。

完整成员身份 SHA：`6e73169ef418ffaa15afd232f9a61ef6ce2063e783ad1f9b4faeecdd55f1b676`。

详细操作审计在 `online-library/build/huevia-80-final/{audit,deployment,public-verification}.json`，原始运行时测试日志在同目录。现有版本 76、77 的本地 bundle 均保留，新版本的最终 APK 已同步到本地 bundle 目录，供后续部署保留。初轮被修正的候选包没有发布。

最终 [ship 复核](FINISH-VERDICT.md) 只覆盖四项已解决的修正；功能验证与证据边界见[验证说明](README.md)。
