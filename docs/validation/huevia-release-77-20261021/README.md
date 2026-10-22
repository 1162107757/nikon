# Huevia 0.13.9-beta 发布验收

2026-10-21 准备并部署，2026-10-22 完成上架及双域公网校验。

- 包名 `cc.huevia`，versionCode **77**，版本 `0.13.9-beta`，最低 API 26。
- APK 3,438,189 字节，SHA-256 `88d284ffadb92c8ce15c1e5833f00c9fb25a8608b8071557c6464f4c7e6d5ec5`。
- v2/v3 签名通过，沿用证书 `04db0f9d3d76c9831a0778cfabc1a0aaab984e05398da16eb644950ccfa0ae31`。
- [测试版下载页](https://huevia.cc/preset-library/download/?channel=beta)。

新版加入 NP3 空间近似、已实证的 Quick sharp ±1 定向兼容，以及 v3 对比度／Color Blender 亮度修正；保留历史效果与精确原图关联，处理及批量导出共用新模型。[58 项原生渲染检查及主机回归](../np3-native/77/README.md)通过。两张同室内 NEF 的实际 NX 对照支持改进方向，完整色彩／空间校准仍未通过，Creative 基底仍明确拒绝。私有原片和对照图未上传。

部署以当时线上代码为基线，仅新增按 SHA 命名的 77 APK；38 个原有文件的字节与权限全部保持，包括 76 APK。环境配置摘要前后相同，未部署工作区其它代码、改密码或创建密钥。[准备审计](audit.json)、[57 项隔离测试](runtime-tests.txt)和[实际部署验证](deployment.json)分开保存。

平台重新封装了 `scf_bootstrap` 的 ZIP 时间、extra 元数据及压缩流，提交 ZIP `0a95c79840fbd72ba1137ec157bdc77b98a01964347a796fc5d6e2ab7f7c49d9` 与线上 ZIP `279ce4378019ccb63496cdd9efde48c14b1498575226aa8bf48c4f6d68d052a4` 摘要不同。独立[离线审计](offline-deployment-repack-audit.json)与在线复核确认全部 39 个成员的路径、字节、SHA、CRC 和 Unix 权限一致，内容身份为 `02f26c44f75b3762a6e2520da6402ad8b00184397a0386320e91d08d1ddeb151`。原检查曾因整个 ZIP 摘要不同失败；随后只恢复验证，没有重复部署。[最终 8 项正负例](archive-content-final-tests.json)通过。

使用既有服务凭据登记单一签名随包文件草稿并写入 `upload_verified` 审计，发布通过既有带 revision 和精确确认的 `nikon_app_release_action` RPC。首次发布的备用域名末次查询中断，随后仅执行只读 `--verify`，未重复发布。配置读取和归档下载的重试仅限有限次只读操作；源码绑定见[最终操作工具记录](final-operator-binding.json)。

[公网验证](public-verification.json)确认发行 `f31228cb-5913-4e4e-b9ac-7910bcc9c285` 已 published：`huevia.cc` 与 CloudBase 域名均下载到与本地签名 APK 逐字节相同的文件，最新更新信息的包名、版本、SDK、长度、SHA、签名及说明匹配，发布审计齐全。全部既有发行记录保持原样，76 仍为 published。

未来服务打包必须保留 `online-library/server/bundled-apks/` 中的 76 和 77 两份已发布 APK；77 的精确文件已同步到工作区此目录。构建时的 `publication: not-published` 是历史状态，当前发布结果以本页和公网验证记录为准。
