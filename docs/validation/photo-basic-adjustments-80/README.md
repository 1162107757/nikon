# Huevia 80 / 0.14.0-beta 基础照片调节验证

最终包：`dist/huevia-0.14.0-beta-debug.apk`，3,458,669 字节，SHA-256 `4aea78f19ddc4e245e27470b29c9f111674f74c2e22b3229d03a2ec5fd6834bb`。包名 `cc.huevia`，最低 SDK 26，签名方案 v2/v3，沿用既有测试签名。

## 实现

紧凑“滤镜／调节”共享不裁切画布，横屏侧栏；13 项手动参数叠加完整预设及强度，支持手动单独使用。32 步会话撤销／重做、精确值、当前参数说明、各类重置及每张照片独立草稿。旧 PED1 草稿迁移为中性调节，零调节保留原处理身份；PED2 存储完整参数。单张及批量导出冻结各张设置并按原始尺寸另存 JPG，保护原图与新草稿。

设计和实施契约见[第二版设计记录](../../design-review/photo-basic-adjustments-20261022/README.md)、[编辑说明](../../photo-filter-editor.md)。

## 最终原生检查

| 配置 | 实测字号 / 方向 | 结果 | 证据 |
| --- | --- | --- | --- |
| phone | 1.0 / portrait | PASS 157 | [报告](phone/report.json) |
| large-font | 1.3 / portrait | PASS 157 | [报告](large-font/report.json) |
| landscape | 1.0 / landscape | PASS 157 | [报告](landscape/report.json) |

合计 471 条检查记录，均绑定最终 SHA。每个目录包含环境、恢复、探针源码及 APK 溯源记录和六张截图：滤镜、调节、色彩、非法精确值提示、参数说明、导出。所有最终截图经人工打开检查，精确值截图故意展示超范围提示，横屏导出为可滚动对话框。复核副本位于 `.impeccable/review/photo-basic-adjustments-80/`，仅嵌入来源元数据；像素来自这些原始截图。

范围包括真实拖动的单步撤销、键盘调节、精确值校验、帮助菜单及文案、每张草稿恢复、原始尺寸 JPEG、批量／单张输出字节一致、原图字节保护、缓存复用与参数变化、过期冻结导出、保存效果图空操作在有／无暂存草稿两种情况下不改变显示或存储身份。隔离只读模拟器测试后已关闭。

## 主机验证

- `tests/run-photo-editor.ps1`：原强度／草稿 34,780 项；新手动行为／迁移／身份 644,888 项，见 [host-editor.txt](host-editor.txt)。
- `tests/run-np3-spatial.ps1`：16 案例，41,534,647 条断言，见 [host-spatial.txt](host-spatial.txt)。
- `tests/run-photo-presets.ps1`：批量 609 项，源关联 82 项，见 [host-batch.txt](host-batch.txt)。

`validated-source-snapshot.json` 是最终验证后源码摘要，不能解释为构建前后稳定性证明。

## 完成复核

初轮 [FINISH-REVIEW.md](FINISH-REVIEW.md) 对旧候选提出四项修正。一次集中修正后重建并完成上述最终原生检查；[FINISH-VERDICT.md](FINISH-VERDICT.md) 将保存版本空操作、预设上下文对比度、参数说明、文档对齐四项全部判定 resolved，`disposition: ship`。该结论只覆盖这四项修正。

`initial/` 保存初轮候选（SHA `78c1ff…`，每模式 132 项）与旧源码摘要，属于历史证据，不是待发布包。

## 证据边界

使用既有合成模板照片、真实配方及手写本地夹具，执行实际渲染、JPEG、EXIF、草稿和 MediaStore 路径。没有实体手机、相机联机、实时生产目录、硬件帧率、TalkBack 或 NX Studio 颜色标定结论。当前效果为 JPEG 基础处理，色温是相对冷暖，不能解释为 RAW 开尔文白平衡。

## 发布

发布准备位于 `online-library/build/huevia-80-final/`：基于线上归档仅添加最终签名 APK，保留 39 个原有文件的内容及 Unix 模式，生成 40 文件归档；22 项语法检查和 57 项运行时测试通过。离线归档防篡改／重打包边界 8 项及安全诊断 2 项检查通过。公开发布与下载核对结果另记于本目录 `PUBLICATION.md`。
