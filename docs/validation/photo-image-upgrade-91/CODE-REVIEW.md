# 图片细节、导出与直方图：91 候选独立代码审查

审查时间：2026-10-24，首次源码检查约 11:47（Asia/Shanghai）。

依据：已批准的 `docs/design-review/photo-detail-export-histogram-20261024/REQUIREMENTS.md`。本次为只读源码审查；未改生产代码，未构建，未运行测试、模拟器或真机。后续界面截图确认尚未完成。本文件不把测试断言数量当作独立完整认证。

检查范围：`PhotoFilterEditor`、`AlbumPanel` 本轮接入、`PhotoDetailDialog/View/Viewport`、`PhotoDetailSource`、`PhotoPresetRenderer/Batch/BatchState`、`PhotoExportSpec`、`PhotoHistogramPanel`、`PhotoExportDialog`、`PhotoExportProgressDialog`、`ncp/PhotoHistogram`。仅为追踪直接调用链，读取了既有 `PhotoPresetSources`、`PhotoLiveGallery/PhotoEffectGallery`、`PhotoLocalTasks` 和账号变化入口。90 的 JPEG 导入修正不在本次评判范围。

首次结论：发现 4 条明确行为问题；下列复现步骤是由源码控制流推导的待执行用例，不冒充已在设备上复现。

## 12:15 源码边界及后端证据复核

复审新增读取 `album/PhotoSavedVersion` 与 `ui/PhotoExportAvailability`。审查者仍未构建、运行测试或操作设备；12:15 只读核对了下述由实施代理生成的后端原生报告，UI 完整确认仍待补。

| 首轮问题 | 源码复审结论 | 当前依据 |
| --- | --- | --- |
| 1. 接受后账号失效 | 源码闭环，待运行验证 | `Edit.withAccount()` 一次绑定并保留发布进度；单张与批量入口均绑定 scope，`AlbumPanel:3650` 传入当前账号 supplier。`PhotoPresetBatch:278–284` 在接受前核对，`323、330、337、356、449–465` 在逐项及发布安全点核对，失效 scope 记入 stoppedAccounts，冻结重试不转到新账号。 |
| 2. 历史成品无法使用 | 源码闭环，待运行验证 | `PhotoSavedVersion:16–24` 只在 effect 关联 IOException 后、JPEG 自身有效时回落；InterruptedIOException 继续抛出。编辑器跳过旧 draft，独立 unrecoverableSaved 状态禁止调色前和原始文件入口，批量用同 helper。`PhotoPresetBatch:619–645` 现将无法证明旧 Q95 的成品／模型记录缺失、损坏或字节不符作为不可复用，走真实完整 Q95 重编码；选中源读取、路径、SHA 与取消错误继续失败，不把未知质量字节冒充 Q95。 |
| 3. 收起直方图丢最终预览 | 源码闭环，待运行验证 | `PhotoFilterEditor:455` 清理诊断请求后，以当前快照重排无统计、无警示的普通预览。 |
| 4. 导出状态／查看动作未接入 | 源码闭环，待原生状态验证 | `PhotoExportAvailability` 按冻结输入及实际规格调用 `findResult()` 和 `publicationStatus()`，区别 NOT_GENERATED／APP_RESULT／PHONE_SAVED／PHONE_MISSING／PENDING／UNKNOWN。`PhotoExportDialog:239–280` 按实际规格与保存位置区分请求 generation，丢弃旧请求，核对超时按未知处理；两入口均接 provider 与查看动作，默认已有结果走查看。 |

11:55 复审曾发现首轮第 2 条的残余：有效历史 JPEG 虽可打开细节，默认 FULL 仍因 `.record`／模型记录不可恢复而失败。11:59 已重新读取修正后的 `canPreserveFullEffect()`，确认其只在记录证据不可证明时返回 false；`owned()`、源路径与符号链接核对、源 SHA 和取消检查位于记录 fallback 之外。`runEdits():334–350` 与 `findResult():600–604` 在不可复用时继续采用选中文件的真实字节和中性 profile，未套用当前新草稿。该残余源码闭环。

11:59 核对的 `PhotoPresetBatch.java` SHA-256 为 `8c23c9120cb12f185bafff4e6392524f6c7a2ae2ba3dab6b65faadc29eba2cf2`。12:15 最新只读核对值为 `51246d85320158949e2ee3961abf39e0015189807eeed2c30068af3ec22ecd94`，与本次提交的边界修正及后端 provenance 一致。

本次边界修正只去掉了 `canPreserveFullEffect():622` 中“原 absolute 路径必须等于 canonical 路径”的限制，允许 Android `/data/user/0` 与 `/data/data` 的合法父目录别名。`owned():584–590` 仍要求 canonical 后的直属目录为支持的私有 camera-album，仍限制大小并验证 JPEG；622 仍拒绝叶子文件符号链接，`checkedLedger():647–651` 仍拒绝记录路径越界／符号链接。本次检查未见该修正扩大为允许读取相册目录外的文件。

已只读核对的后端证据为 `docs/validation/photo-detail-export-histogram/backend/report.json`、`provenance.json` 与 `instrumentation.txt`：报告标记 PASS，169 个记录项均为 passed，versionCode=91，运行 APK SHA-256 为 `49f222150fa9fc188e314c5a6e1fd290c668f8ca495cdf05962ffcd4f334ac53`。provenance 中探针、脚本、Manifest、Renderer、Batch 和 ExportSpec 共 6 项输入 SHA 均与当前文件一致。相关 orphan 用例具体覆盖无完成记录时重新编码为真 Q95（非 Q100 源复制）、完整尺寸、源字节不变、再次复用、损坏 JPEG 拒绝；该报告范围明确为隔离模拟器合成私有夹具上的真实 JPEG／PNG／EXIF，不含 MediaStore、真机、相机或 UI 认证。

另只读核对了 host 源码中合法目录别名可用、相册外文件拒绝与取消保持硬失败的针对性用例（`PhotoPresetBatchTest:679–699`），以及实施方 `HOST-VALIDATION.md` 的 688 项通过记录。审查者未重跑这些用例，未凭数量追加完整认证。

当前总评：首次四项问题及复审残余均已在本次检查的源码中闭环，最新目录别名修正未见新增实质缺陷；后端对应报告已核对。framebuffer、账号切换、取消及回执状态的完整原生行为仍须其各自证据，不能由后端编解码报告替代。实施方已报告细节页 safeInsets 可能遮住页头，尚待一批源修正及新候选 UI 验证；本审查未独立确认该截图，不能声明整体 UI 完成。常规同文件 `bind()` 是状态刷新的 no-op；显式 `bindSavedVersion()` 先 unbind，未将此机制另列缺陷。

以下保留首次发现与原行号，作为修复原因与验证用例记录。

## 1. [P1] 接受导出后没有执行账号校验，退出账号后仍会发布剩余照片

- 位置：`app/src/main/java/cc/huevia/album/PhotoPresetBatch.java:280–315`，尤见 284–291、308、315；保存版本的账号为空见 66–68。
- 触发：在账号 A 下确认一组耗时的导出任务，收起进度页并退出登录或切换账号，然后等待后续项目执行。
- 原因与结果：`Edit.owner` 只用于最终草稿写回；`runEdits()` 每项只检查取消、输入字节和草稿 SHA，渲染及发布也只以 `job.cancel` 为取消条件。`startEdits()` 未冻结一个独立的执行账号，`Edit.saved()` 更是固定空 owner。现有账号变化回调未停止该任务，`PhotoLocalTasks` 只提供与删除的互斥。因此账号已失效后仍会开始后续项目并写入手机相册，不符合需求第 6、7 节的账号范围失效停止约束。
- 建议：接受时冻结执行账号（含 saved 项），在每项开始、耗时渲染安全点以及发布前校验该账号仍有效；失效时停止相关剩余项，保留冻结参数和已完成结果，禁止把重试转到当前新账号。
- 针对性验证：用可控的首项渲染屏障使账号在接受后变化，确认第二项不开始、发布不发生、旧草稿不会写入新账号范围。

## 2. [P2] 关联不可恢复的真实历史成品无法进入“已保存版本”细节和导出分支

- 位置：`app/src/main/java/cc/huevia/ui/PhotoFilterEditor.java:133`；对应批量入口为 `app/src/main/java/cc/huevia/ui/AlbumPanel.java:3667`。
- 触发：打开一个 JPEG 本身完整可解码的 `effect-<key>__Effect.JPG`，其原图已经不可用，或原图关联不能可信恢复；选择该已保存版本后进入细节或新导出设置。
- 原因与结果：编辑器准备过程无条件先调用 `processingOriginalFor(photo)`。该函数在关联缺失或原图不可用时抛错，导致 `ready` 一直为 false。后面的 `compare=false`、整张已保存 JPEG 细节路径和 `Edit.saved()` 重编码路径都无法到达；批量同样会在准备设置时失败。需求第 3.1、3.4、5.5 节明确要求此时仍能以完整已保存文件为源，含烘焙边框地查看真实像素并缩小／重新编码。
- 建议：将“验证选中完整 JPEG”和“尝试恢复可编辑原图关联”分开。仅关联不可恢复时，以经校验的选中 JPEG 进入 saved-only 状态，禁用调色前和原始文件入口；不得把该文件当作可恢复编辑的原图。损坏的选中文件仍应失败。
- 针对性验证：夹具应保留真实 effect 文件身份和有效成品字节，再使源关联不可恢复；普通重命名为 `SAVED.JPG` 的烘焙 JPEG 不覆盖这条失败路径。

## 3. [P2] 收起直方图会丢掉最后一次编辑预览，之后画面可能停在旧参数

- 位置：`app/src/main/java/cc/huevia/ui/PhotoFilterEditor.java:454`；结果丢弃条件见 416。
- 触发：打开直方图，快速改变曝光或滤镜强度，在最终 `schedulePreview()` 尚未提交结果时收起直方图。
- 原因与结果：收起操作递增 `previewRequest` 并清空预览队列，但没有为当前编辑重排普通预览。正在执行的最新渲染到达 416 时因 request 不匹配而被丢弃，已排队的最终渲染则直接被移除。`current` 因而保留上一次的照片，滑块和冻结导出却已经是新参数；直到再次修改参数才恢复一致。
- 建议：收起后立即按当前编辑快照安排一帧无统计、无警示的预览，或者仅让诊断结果失效并确保最新普通照片仍能提交。
- 针对性验证：用预览执行屏障稳定制造“最终编辑在途 → 收起”顺序，释放后比较当前照片与最终参数的渲染；不要只检查收起时布局未移动。

## 4. [P2] 导出设置未接入已有成品／相册回执状态，无法提供批准的状态与默认动作

- 位置：`app/src/main/java/cc/huevia/ui/PhotoExportDialog.java:148–153、222`；调用方 `PhotoFilterEditor.java:475–482`、`AlbumPanel.java:3670–3672`。
- 触发：先将某一编辑和实际规格成功保存至手机相册，再次打开相同设置；也可分别删除相册副本或使回执核对不可用后重开。
- 原因与结果：设置面板只接收尺寸和缩略图，主操作始终为“导出照片／导出 N 张照片”。本轮新增的 `PhotoPresetBatch.findResult(Edit)` 没有调用方，设置页和两个调用方也未查询 `publicationStatus`，所以无法分别显示“已生成，可直接保存”“查看已保存照片”“重新保存到手机相册”“暂无法核对已保存照片”。需求第 5.4 节规定的用户可见状态和已保存默认动作尚未实现。底层发布器仍做去重；此条不声称会因此重复发布。
- 建议：根据完整冻结编辑和实际规格异步查找并校验规范成品，再核对发布回执，将结果按同一快照提交给设置面板；规格变化使旧状态失效，未知状态不能当作缺失，已核对存在时提供查看动作。
- 针对性验证：对同一实际规格分别覆盖有效已保存、有效 App 成品但相册缺失、回执不可核对三种状态，并确认档位名不同但实际规格相同时采用同一结果。

## 已核对但不作运行认证的路径

- 100% 视口的 scale=1 使用 Canvas 实际像素坐标；完整区域读取 sample=1，绘制使用明确源／目标矩形，未见以 dp 替代照片像素的计算。
- 可恢复编辑的细节走完整源图处理后生成无损 PNG，再做 region decode；沿用完整图空间滤镜与构图，外加边框未写入细节 PNG。该设计避免了瓦片独立计算半径与 halo 的接缝问题；仍需独立的真实编解码、framebuffer 和设备内存证据。
- 直方图使用完整构图的预览内容、排除外加边框；RGB 共享纵轴，高光任一通道 255、暗部全零，警示拷贝与导出输入分开。
- 实际输出规格进入非默认导出模型身份；完整 Q95 保留旧模型身份，缩小发生于完整含框画布之后，未发现按档位名称区分同一实际规格的代码。
- 当前成功发布后草稿写回按冻结编辑身份匹配，未见以旧任务覆盖新草稿；本轮检查不能替代真实 MediaStore、断权、存储不足、账号切换和取消验证。

在以上范围之外未做全 App 设计评审，也未对未提供的截图作界面通过声明。
