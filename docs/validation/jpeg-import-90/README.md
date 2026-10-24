# JPEG 导入附加数据兼容修正 · 90

2026-10-24，本地测试包 90 / `0.18.1-beta`。APK：`dist/huevia-0.18.1-beta-debug.apk`，3,831,930 字节，SHA-256 `9f5ae959e5b2a91dbd195bb28e3ea752d2ca392b8aebf47123903ac39a8a689b`。

## 复现依据与结论

用户反馈相册顶部“未开始编辑 / JPEG 数据不完整”。此次收到的是错误界面的截图，并非截图中实际点选的照片。该截图文件本身也暴露同类兼容问题：815,072 字节，主 JPEG 的 EOI 在 815,048 字节结束，其后有 24 字节不透明附加数据；SHA-256 `830faf7e29108884ac960bb3df6a1a8ffc5d2338bca14f7ea1cfe4b50bcdc767`。不据此认定附加数据的厂家或用途，也不声称已拿到原来点选的照片。

在隔离 Android 模拟器上安装原 89 包，真实 MediaStore 导入该截图被“相机返回的 JPEG 照片不完整”拒绝；在相同字节后构造额外 EOI 的测试夹具，会进入第二层校验并精确复现“JPEG 数据不完整”。见 [baseline.json](baseline.json)。旧逻辑既要求 EOI 位于物理文件尾，又要求主图 EOI 后无任何字节，导致完整主图带附加数据也无法导入。

90 将原有完整主图段扫描集中到 `PhotoPreviewCache.validateCompleteJpeg`，按 APP 段长度跳过内嵌缩略图，检查帧、扫描和主图结束标记，允许结束后保留附加数据。手机导入继续执行 Android 解码、元数据前后对照、大小核对及二次整文件 SHA 校验。共享文件校验对尾部不是 EOI 的文件使用主图结构校验回退；原有常规文件快速路径保留。

所有读取均不改写、截断或重编码源文件；导入副本包含附加数据，身份和来源记录按完整原字节计算。缺主图 EOI、无帧／扫描、损坏主图后接另一张图片仍被拒绝。边框、布局和机型设置没有改动。

## 验证

- [gallery-tests.txt](gallery-tests.txt)：相册保存250、导入来源86、删除36、原字节身份18、JPEG结构专项58次检查。JPEG专项使用真实 ImageIO 基线／渐进 JPEG、APP内嵌图、附加数据、第二张图片、截断、错误长度、取消及字节不变检查。
- [real-fixture-host.txt](real-fixture-host.txt)：同一58项专项加1项真实截图文件检查，共59；不与58相加为独立场景。
- [batch-tests.txt](batch-tests.txt)：629次批处理及82次来源关联检查。
- [album-tests.txt](album-tests.txt)：889次生产相册控制器、33次相机能力、22次网关连接、356次直播项目存储、100次项目目录检查，覆盖共享校验涉及的相机接收与不完整文件拒绝。主机Context新增AssetManager类型占位以恢复独立编译；不模拟字体或图片解码。
- [baseline.json](baseline.json)：原89包10次断言，确认两种错误及既有解码限制。
- [fixed.json](fixed.json)：90包34次断言，真实 MediaStore／Android 解码、导入副本复用、原字节不变、负例拒绝、手机导入实际编辑器流程、边框预览、冻结设置及真实JPEG导出。8条本次自建 MediaStore 记录均精确删除。原89对照的3条记录也已精确删除。
- [editor-after-import.png](editor-after-import.png)是实际编辑器截图；画面内部的“JPEG 数据不完整”是作为照片导入的原始错误截图内容，不能误读为新版仍然报错。[border-output.JPG](border-output.JPG)为实际渲染成品。两者均已直接查看。

构建见 [build.txt](build.txt)，专项探针源及复现说明见 `tests/jpeg-import-device-probe/`。只使用任务独占只读 `emulator-5582`，没有安装或操作实体手机／相机，没有公开发布。89的五配置视觉复核保持其原版本范围，不作为90重新完成五配置认证。

[source-snapshot.json](source-snapshot.json)对照89构建后输入，仅版本、`LocalPhotoRepository`及`PhotoPreviewCache`三个生产文件发生变化，其余191项输入不变；该快照不是可复现构建证明。[artifacts.json](artifacts.json)保留夹具及原生成品哈希。任务模拟器已核实身份后关闭，见[cleanup.json](cleanup.json)：初始沉浸式提示设置读取为空，恢复空值的命令失败，关闭前仍为`confirmed`；只读、无快照的独占模拟器关闭后丢弃该任务覆盖层，没有将此写成成功恢复设置。

## 已知边界与历史

首轮探针曾预期 Android 能拒绝“人为截断熵数据后再伪造 EOI”的文件，实际平台解码器接受，见 `initial/` 中失败记录。随后在原89包和90包分别核实，两者行为一致，最终探针显式记录此既有边界；没有改变生产代码以迎合该断言。本修正证明结构及来源一致性，不提供所有JPEG熵数据损坏检测、像素完整性或防篡改认证。

用户最初点选照片的原文件仍未取得；已复现并修正截图文件暴露的同类校验误判。若新版仍对某张图报错，需该原文件确定是否还有其他格式或读取问题。
