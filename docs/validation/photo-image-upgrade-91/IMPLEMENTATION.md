# 91 · 图片细节、直方图与导出规格

状态：**本轮实现与限定验证完成。最终f717151b…三配置172／172／174次PASS，206项源输入／安装包／签名与环境清理已核对；独立复核有界清单clear、限定ship。用户最终视觉批准、实体手机或全App认证均不由此推定，未公开发布。** 更新日期：2026-10-24。

用户已通过“开始”批准[详细需求](../../design-review/photo-detail-export-histogram-20261024/REQUIREMENTS.md)及正式的[编辑／细节设计图](../../design-review/photo-detail-export-histogram-20261024/review-editor-v1.png)、[导出设计图](../../design-review/photo-detail-export-histogram-20261024/review-export-v1.png)。目标版本为 `0.19.0-beta` / `versionCode 91`，包名仍为 `cc.huevia`；原生 Java 17 / Android Views，minSdk 26、targetSdk 35。设计图中的尺寸、体积、直方图和队列是设计示例，运行结果另行记录。

用户随后查看实际截图，明确要求替换占高的大黑底细节面板和直方图大连体选中块，又要求像素尺寸从左下缩放控件拆到照片右上小tag。最新又指出主编辑“细节100%”留白、过大且不像可点击：白色图标落在白底，现改为实际照片左下的深色真实玻璃“查看细节”，18dp放大镜＋12sp文字、内容宽约91dp、视觉32dp／触达48dp。点击沿用真实showDetails路由；同时移除直方图宿主把横屏已分配剩余高度重复减64dp的onMeasure，避免曲线裁切。其余玻璃与像素语义见[GLASS-BRIEF](../../design-review/photo-detail-export-histogram-20261024/GLASS-BRIEF.md)，技术范围保留；旧候选证据不代表新源或新视觉已通过。

## 最终交付与证据身份

最终本地[APK](../../../dist/huevia-0.19.0-beta-debug.apk)为91／`0.19.0-beta`，3,893,370字节，SHA-256 `f717151b99bb70d75a8038acb5f8e04f3ea28e121891ddf1fd8bdf73d47ea7a8`。[FINAL-IDENTITY.json](FINAL-IDENTITY.json)核对[source-snapshot.json](source-snapshot.json)206项生产输入与当前文件一致、dist＝不可变候选＝已测包字节，v2／v3签名均通过；输入快照不单独构成可复现构建证明。本文亦只读核对了文件大小、SHA及快照身份。最终实现移除失效Button.onDraw标记，改Ripple背景实体LayerDrawable／GradientDrawable层14×2dp、bottomInset12dp（y34–36），非选中透明；warning、尺寸与统计逻辑未改。

[UI-MATRIX.json](UI-MATRIX.json)区分当前与历史身份：f717…隔离模拟器phone／font1.3／landscape分别PASS172／172／174，共518次含配置重复的断言执行，不是518个独立场景。每配置均执行RGB→亮度→RGB三阶段，原始UiAutomation framebuffer活动区均172／216桃色像素、非活动区0，失败5e批次的阈值未改，模式线与字形无交叠／裁切。每份JSON与原生PNG均保留，phone例为[RGB选中](phone/histogram-mode-line-rgb-selected.json)及[切换亮度](phone/histogram-mode-line-luminance-selected.json)。

| 当前f717配置 | 原生检查 | 真实模式线（三阶段） | 1:1 framebuffer／region对照 |
| --- | --- | --- | --- |
| [phone](phone/report.json) | PASS172 | 活动172/216、非活动0 | 1849点最大通道差0；region1848点错误0 |
| [font1.3](font1.3/report.json) | PASS172 | 活动172/216、非活动0 | 1849点最大通道差0；region1848点错误0 |
| [landscape](landscape/report.json) | PASS174 | 活动172/216、非活动0 | 1849点最大通道差0；region2772点错误0 |

当前原生检查也保留真实玻璃有界采样、入口点击、完整曲线／文字、主图与五工具锚点、警示／导出字节隔离及源守卫。正常入口宽236px（约89.9dp），1.3字号272px，视觉32dp／触达48dp；每配置实际生产入口三次performClick均打开真实细节Dialog，此结论是View点击回调路径，不外推为触摸延迟或TalkBack测试。供用户查看的[final-entry-f717151b.png](final-entry-f717151b.png)与 `phone/hero-editor-closed.png` 逐字节同SHA `927005dd38e1c03742c0d4608316f501965a31fb2e76470c8a7f3c2926f9576c`，是原样截图副本。

ac5…入口／G1／G3／独立metric实图及158／158／160次历史检查保存在[glass-ac5c3ff1-entry-confirmed](glass-ac5c3ff1-entry-confirmed/)，不与当前518次相加。ac5…较eaa…仅AlbumPanel改动，后续5e…／f717…仅Panel模式绘制改动，后端／导出／进度源码未变，本轮不重复后端或进度测试。复用证据始终保留原包身份：

| 范围 | 已有证据及APK身份 | 当前适用边界 |
| --- | --- | --- |
| ac5入口／宿主UI | `ac5c3ff1d419cf45f068249b78731878f0f00eb723f2972e8aeb662f6ccd584a`；phone158／font1.3 158／landscape160 | 三配置正式PASS及入口／G1／G3／metric限定实图确认，G2短线另修 |
| dark | `eaa4914742a7d3a7b8ae0286ded76f999ec718207963923406751a92b7e86c58`；[dark报告](dark/report.json)143次 | 既有dark原生记录，未称f717…／5e…／ac5…dark重跑 |
| backend | `b5a781622d618fcf425a7d2bedc4e950c03463d2eb52595189535d7f8bb91e8b`；[backend报告](../photo-detail-export-histogram/backend/report.json)169次 | 后端源未变，保留b5…真实编解码／EXIF／私有批量范围；不是f717…／5e…／ac5…后端实测 |
| progress | `0d35b8c8d6367c1a62078a264183ca41bd24bc357ebd22ee8dcb08f83870bb31`；[历史进度报告](pre-glass-0d35b8c8/phone-progress/report.json)23次 | 进度源未变，仅引用原APP_ONLY批量停止／比例证据，不冒称最新包重跑 |

[独立完成复核](FINISH-REVIEW.md)最后章已实际审阅f717…三配置的RGB／亮度原生模式切换图及横屏hero，并结合ac5…已闭项，将G1–G3、右上独立尺寸tag与紧凑入口评分resolved、remaining clear、disposition ship；只覆盖该有界清单及直接回归，没有重做全App审查，不替代用户视觉批准。不能把上表不同候选拼成“最新包四配置／后端／进度均已全测”。

三份当前报告均确认自有JPEG／草稿／私有细节PNG清理、源SHA保持、字号／外观／旋转及权限恢复PASS；[UI-DEVICE-CLEANUP.json](UI-DEVICE-CLEANUP.json)记录UI探针卸载、实体设备操作false、MediaStore发布0。[EMULATOR-CLEANUP.json](EMULATOR-CLEANUP.json)确认ranchu／Pixel_9／端口与任务自有PID23100后仅关闭emulator-5586，backend探针亦卸载，shutdownVerified=true。本轮实现、限定复核、身份与清理无当前待办；实体手机／MediaStore生命周期等未覆盖项属于下述证据边界，未冒称通过。

| 候选 | 记录与适用边界 |
| --- | --- |
| 0d35b8c8… | 无玻璃旧视觉的功能／旧M1–M5记录已归档[pre-glass-0d35b8c8](pre-glass-0d35b8c8/)；功能PASS不代表用户视觉接受 |
| 2ec0cdbe… | 首轮玻璃四配置128／128／128／130次PASS，共514次含配置重复；[原记录](glass-first-2ec0cdbe/UI-MATRIX.json)保留，不与后续轮相加。视觉仍有直方图遮人物、原生选中弱、1.3字号百分比截断；进度23次仅引用0d35…旧包，不冒称该包重跑 |
| 5be8f121… | 留白／选中背景／大字号／尺寸tag修正后的中间包，backend169通过；phone和font1.3各141程序检查PASS，实图文字半截，强化glyph诊断FAIL。dark／landscape因此暂停，不宣称该包视觉通过，见[VISUAL-FAIL](glass-5be8f121-visual-fail/VISUAL-FAIL-5be8f121.json) |
| eaa49147… | 背景后padding归零／居中与header关闭baseline对齐，backend169及字形检查通过；dark保留该包PASS143。其余过程及横屏残余见[归档](glass-eaa49147-landscape-residual/)，不当作ac5…新包验证 |
| ac5c3ff1… | “查看细节”小玻璃入口、贴实际照片左下及宿主重复扣高修正，三配置158／158／160正式PASS；独立入口／G1／G3／metric已看过，G2模式仍仅粗体未见短线 |
| 5e9688ba… | 尝试移动原onDraw线／关闭outline clip后，phone在87项处FAIL，RGB选中区framebuffer0/216桃色像素且肉眼无线；字体／横屏停止，见[失败归档](glass-5e9688ba-mode-line-fail/) |
| f717151b… | 最终实体背景LayerDrawable14×2dp／bottomInset12；三配置172／172／174 PASS、每配置三阶段选中172/216与非选中0；限定独立清单clear，206输入／签名／包身份与清理完成 |

backend历史成功记录分别保存在[pre-glass-0d35b8c8-pass](../photo-detail-export-histogram/backend/pre-glass-0d35b8c8-pass/)、[glass-first-2ec0cdbe-pass](../photo-detail-export-histogram/backend/glass-first-2ec0cdbe-pass/)、[glass-5be8f121-pass](../photo-detail-export-histogram/backend/glass-5be8f121-pass/)及[glass-eaa49147-pass](../photo-detail-export-histogram/backend/glass-eaa49147-pass/)，当前主目录需按其report内APK SHA解释，不沿用旧候选PASS冒称新包已跑。

5be8…原生背景的InsetDrawable层将上下各52px内距带进高126px按钮，仅剩22px绘字区，真实glyph越界。初次功能／几何检查未覆盖这个裁切条件，程序PASS不能推翻实图FAIL；[5be-glyph-diagnostic](5be-glyph-diagnostic/)保留失败与恢复记录。eaa…字形修正与模式线分开：5e…移动线／关闭outline clip仍在[87项处FAIL](glass-5e9688ba-mode-line-fail/phone/report.json)，[原始采样](glass-5e9688ba-mode-line-fail/phone/histogram-mode-line-rgb-selected.json)为0/216桃色像素；onDraw被裁切仅未证实假说，不写成根因。f717…以实体背景层产生选中线，三配置真实像素与独立实图已确认，失败档案未被成功结果覆盖。

## 实际实现范围

本轮在既有五项编辑工具“滤镜／调节／构图／边框／配方”上增加查看与导出能力，不增加根导航。90 的 JPEG 导入修正保留：验证主 JPEG 完整性时允许主图 EOI 后的合法附加字节，完整原文件和来源 SHA 继续保留；不重新定义其历史测试结论。该专项见[90 验证](../jpeg-import-90/README.md)。

### 真实像素细节

- `PhotoDetailDialog`、`PhotoDetailView` 与 `PhotoDetailViewport` 提供独立全屏的适应／100%／200%、双指缩放、双击、拖动和定位小窗。100% 定义为一个内容像素对应一个屏幕物理像素；Canvas 使用实际像素坐标，不用 dp 或编辑预览尺寸替代。200% 只放大已有像素。
- 可恢复编辑使用冻结的原图、效果、构图与账号／绑定身份。`PhotoPresetRenderer.renderDetail()` 先按完整源分辨率生成无外加边框、JPEG 编码前的无损临时 PNG；“当前效果／调色前”共用构图，切换保留视口。不会为每个瓦片独立计算空间滤镜，避免瓦片边界产生不同处理。
- `PhotoDetailSource` 按需 region decode：512px 瓦片、12MiB 瓦片缓存及低分辨率定位概览。区域读取的内存预算不代表完整效果生成恒定内存；完整处理仍需足够 RAM 和私有临时磁盘。失败应明确提示，不把低清预览放大冒充真实 100%。
- 有效历史 effect JPEG 的可信原图／参数关联不可恢复时，`PhotoSavedVersion` 允许以完整已保存文件进入 saved-only 分支。此时包含已经烘焙的边框，标“已保存版本／查看完整文件像素”，禁用调色前和原始文件复制，不猜测去框。选中的 JPEG 自身损坏或取消不会作为正常历史回退处理。
- 未接受的细节请求受源 SHA、账号、编辑快照和生命周期约束；关闭、失效或页面释放时清理请求与私有临时资源。具体物理像素与清理证据见最终矩阵，未由此承诺所有设备的耗时或峰值内存。

### 预览直方图与裁切警示

- `PhotoHistogram` 从当前构图的 sRGB 8bit 效果预览计数，不统计外加边框，不是 RAW 或全分辨率测光。亮度使用 `round(0.2126R + 0.7152G + 0.0722B)` 的 256 档；RGB 共用纵轴。
- 高光为任一通道等于 255，暗部为三通道全为 0；百分比分别显示 0、非零小于 0.1% 或一位小数。统计范围不随局部缩放／拖动变化。
- `PhotoHistogramPanel` 由画布工具入口展开，默认关闭，换图重置。面板是覆盖层，主图与工具区保留原锚点；小空间可滚动。更新、失败与有效的零值有不同文字反馈。
- 高光／暗部的颜色斜线写入独立诊断 Bitmap；编辑真实效果、源字节、草稿／撤销身份、细节和导出输入不因此改变。收起面板后按当前快照重新安排普通预览，避免丢失在途的最后一次调节。

### 三档 JPEG 与冻结任务

| 规格 | 最终画布与质量 |
| --- | --- |
| 完整尺寸 | 当前构图加边框的自然尺寸，JPEG 95；不受自定义 8192px 上限约束 |
| 分享 | 最终含框长边最多 2048px、JPEG 90；不放大小图 |
| 自定义 | 长边 640–8192px、JPEG 70–100；首次 `min(4096,完整长边)` 与质量 90 |

单张自定义不允许输入超过其完整画布长边，完整长边小于 640px 时不可用。批量设置是一项逐张上限：每张按自身含框尺寸解析，小图保留自身尺寸，不强制放大到共同长边。边框、文字和 Logo 随整张最终画布一起缩放。

`PhotoExportSpec` 的结果身份基于实际宽高、质量、sRGB／编码及元数据策略；档位名称本身不生成不同的成品身份。主成品实际 JPEG 重编码、保留拍摄元数据白名单并去除定位；质量 100 不称无损。无可靠体积预估时显示“导出后显示实际大小”，完成后才有实际字节数。

`PhotoExportDialog` 同时承载规格、保存位置和明确的“另存新副本”。手机相册为首次默认，保留既有场景记忆。`PhotoExportAvailability` 在后台核对规范成品与手机回执，区分尚未生成、App 内已有、手机仍存在、明确缺失、未完成及未知；已核对存在时默认查看，未知不冒充缺失。规格／位置变化或关闭会废止旧查询，详见[组件说明](EXPORT-AVAILABILITY.md)。

点击主操作接受后冻结逐张源 SHA、草稿、账号、模型、构图／边框、规格、目标和新副本选择。`Edit.withAccount()` 区分绑定的访客空账号与旧的未绑定调用；`withDestination()` 和重试保留原绑定。执行器在逐项及渲染／发布安全点核对当前账号；失效时停止相关项，不能转到新账号。继续调图、切照片或重绑 UI 不修改已接受任务，旧任务完成也不应把新草稿标为已导出。

`PhotoExportProgressDialog` 使用真实逐项 waiting／processing／completed／reused／failed／cancelled 状态。关闭进度页不会取消已接受任务；明确停止保留已完成结果。私有渲染的 reused 只表示复用成品，不单独证明手机相册副本存在。持久化到 App 被杀后的队列恢复不在本轮范围。

“保存 JPEG 原始文件”是独立的低强调动作，只对可信 JPEG 原图提供；确认明确忽略编辑及规格，保留原有字节和元数据（可能含定位），不清除草稿。PNG 白底工作 JPEG 不伪称 PNG 原文件；既有 NEF 原文件保存仍为单独路径。本轮不增加 RAW 显影、曲线／HSL、局部蒙版或 AI 修图。

## 代码与证据归属

| 负责部分 | 主要文件／记录 |
| --- | --- |
| 原生接入 | `ui/PhotoFilterEditor.java`、`ui/AlbumPanel.java`、`ui/AppIcon.java`、`app/build.gradle` |
| 细节 | `ui/PhotoDetailDialog.java`、`ui/PhotoDetailView.java`、`ui/PhotoDetailViewport.java`、`album/PhotoDetailSource.java` |
| 直方图／规格 UI | `ncp/PhotoHistogram.java`、`ui/PhotoHistogramPanel.java`、`ui/PhotoExportDialog.java` |
| 最新玻璃视觉（待截图／回归） | `ui/PhotoGlassDrawable.java`，以及细节palette、直方图浮层与照片变换锚定接入；见[GLASS-BRIEF](../../design-review/photo-detail-export-histogram-20261024/GLASS-BRIEF.md) |
| 导出任务 | `album/PhotoExportSpec.java`、`album/PhotoPresetRenderer.java`、`album/PhotoPresetBatch.java`、`album/PhotoPresetBatchState.java`、`ui/PhotoExportProgressDialog.java` |
| 历史版本／已保存状态 | `album/PhotoSavedVersion.java`、`ui/PhotoExportAvailability.java`、[状态说明](EXPORT-AVAILABILITY.md) |
| 独立源码审查 | [CODE-REVIEW.md](CODE-REVIEW.md)，保留首次发现与后续修正，不由本文代判关闭 |

## 0d35…历史功能候选证据与交付边界

下列已执行证据绑定同一0d35…历史功能候选。用户后续否定其中细节／直方图视觉，因此这些PASS不构成当前视觉接受或最终交付；旧M1–M5复核即使闭环，也只解决当时限定问题。早期候选、旧版专项及跨配置重复断言不相加为独立场景。

### 候选身份与已执行检查

- 当时构建：`huevia-0.19.0-beta-debug.apk`，`versionCode 91` / `0.19.0-beta`，3,889,274 字节；当前dist已更新，不用当前文件冒充该历史字节。
- 历史功能候选 SHA-256：`0d35b8c8d6367c1a62078a264183ca41bd24bc357ebd22ee8dcb08f83870bb31`。下列四配置UI和进度页均按此安装包验证；它未获用户视觉接受。此前 `49f222150fa9fc188e314c5a6e1fd290c668f8ca495cdf05962ffcd4f334ac53` 也是中间候选，二者均不作为新玻璃视觉的最终交付身份。
- 归档[source-snapshot.json](pre-glass-0d35b8c8/source-snapshot.json)记录当时205项 App 主源输入、构建配置及本地构建脚本；归档[FINAL-IDENTITY.json](pre-glass-0d35b8c8/FINAL-IDENTITY.json)复核当时输入及dist匹配。二者不证明最新玻璃源码相同。快照不是可复现构建证明；该候选Android35／Java17构建及v2/v3签名核对通过，既有Tencent注解／弃用API警告保留。
- [HOST-VALIDATION.md](HOST-VALIDATION.md) 分别记录视口57、直方图1,083、规格14,229、既有强度／草稿34,780、基础调节644,888、构图／配方1,158、边框29、批量688、来源82、图库36／项目自动53、回执状态71项检查。它们是各专项断言数量，包含边界替身；不合并为独立场景或 Android／全 App 认证。

Android 原生 backend probe 的归档[报告](../photo-detail-export-histogram/backend/pre-glass-0d35b8c8-pass/report.json)、[instrumentation日志](../photo-detail-export-histogram/backend/pre-glass-0d35b8c8-pass/instrumentation.txt)及[来源](../photo-detail-export-histogram/backend/pre-glass-0d35b8c8-pass/provenance.json)对应上述0d35…APK，结果 **PASS169**，`scratchRemoved=true`。范围为隔离模拟器内合成私有夹具上的真实 JPEG／PNG／EXIF 编解码及生产批量路径：

- 含框分享实际长边2048；JPEG 70／90／95／100的实际量化；小图不放大；完整尺寸8201px长边不受8192限制；分享与自定义实际规格相同时字节及模型身份一致。
- EXIF方向1–8、输出sRGB、方向归一、实际宽高、拍摄字段与精确快门1/640保留，GPS剔除；处理后原字节SHA保持。
- 高锐化及构图下，全分辨率无损当前效果／调色前与完整预JPEG结果逐像素一致、不含外加边框。此项不代替 UI framebuffer 的物理1:1映射检查。
- 真实 `effect-…__Effect.JPG` 无完成 `.record` 时，生产 `PhotoPresetBatch` 的完整Q95产生新编码（不是复制未知质量字节），第二次精确复用已核对成品；坏JPEG拒绝，源文件保留。

该 backend probe **没有运行 MediaStore 发布、设置或权限流程**，不能用它认证手机相册副本的存在／删除／未知回执生命周期、实体手机或 UI。首次夹具写入器将1/640量化的问题及第二次 Android 合法目录别名被拒均保留失败档案；前者修正测试夹具，后者修正生产目录所有权核对。当时 `PhotoPresetBatch.java` 来源SHA为 `51246d85320158949e2ee3961abf39e0015189807eeed2c30068af3ec22ecd94`，成功记录不覆盖两次失败历史。49f222…中间候选成功记录另存[initial-49f22215-pass](../photo-detail-export-histogram/backend/initial-49f22215-pass/)；backend主目录随当前候选更新，必须按报告SHA解释，以上0d35…链接固定到其归档。

### 首轮视觉问题与修正范围

49f222…首轮截图及进度记录保存在[initial-49f22215-visual](initial-49f22215-visual/)，首次phone探针选图绑定错误单独归档[initial-49f22215-probe-binding](initial-49f22215-probe-binding/)。后者是夹具未走真实workspace导致旧localDetail重绑，不能当作产品已保存版本回退失败；修正探针后再执行正式矩阵。首轮可审阅截图发现的五项实质问题由[独立完成复核](FINISH-REVIEW.md)保留，当前包已一次批量修正：

1. 细节页按真实安全区避让顶部／底部系统栏，并为深底使用白色系统图标。
2. 横屏直方图按当次MeasureSpec安排完整曲线空间，保留照片与工具锚点。
3. 横屏导出只固定主按钮，JPEG／NEF原始文件动作随正文滚动，释放参数阅读空间。
4. 非法输入清除“正在核对”状态，禁用主动作使用符合当前错误的文字。
5. 停止后进度条只表达completed／total，取消项目不填成成功；进度页采用不透明系统栏。

以上五项已由[独立复核](FINISH-REVIEW.md)在0d35…旧清单中评分resolved，首轮phone收起引导遮挡也以重拍关闭；这仅记录旧清单闭环，用户随后否定的照片占比／大块选择及后续玻璃、入口、横屏问题不由此获得SHIP。

### 0d35…原生矩阵与清理

[归档UI-MATRIX.json](pre-glass-0d35b8c8/UI-MATRIX.json) 汇总同一0d35…安装包在任务专用 `emulator-5586` 上的真实 Java Views 运行记录。phone、实际1.3字号、dark各 **PASS114**，landscape **PASS116**，phone-progress **PASS23**，共481次断言执行，包含跨配置重复，**不是481个独立用例**。四配置的环境、报告、像素映射、恢复及原生PNG均保留在 `pre-glass-0d35b8c8/` 对应目录，进度页见[历史phone-progress](pre-glass-0d35b8c8/phone-progress/)。

| 配置 | 检查 | 1:1 framebuffer 对照 | region像素对照 |
| --- | --- | --- | --- |
| [phone](pre-glass-0d35b8c8/phone/report.json) | PASS114 | 1,849个点，最大通道误差0 | 1,764个点，错误0 |
| [font1.3](pre-glass-0d35b8c8/font1.3/report.json) | PASS114 | 1,849个点，最大通道误差0 | 1,764个点，错误0 |
| [dark](pre-glass-0d35b8c8/dark/report.json) | PASS114 | 1,849个点，最大通道误差0 | 1,764个点，错误0 |
| [landscape](pre-glass-0d35b8c8/landscape/report.json) | PASS116 | 1,247个点，最大通道误差0 | 924个点，错误0 |

像素oracle为完整解码的2401×1601高频合成JPEG，实际100%为scale=1、regionSample=1，不从预览推导正确性。矩阵还覆盖200%／适应／定位窗、同构图调色前切换及中心保持、显式同文件重绑关闭旧页、历史effect真实workspace回退及调色前禁用、直方图开关不移图／工具、警示不改真实效果／草稿身份／源SHA／编码字节、最终编辑在途收起直方图仍提交、三档默认值、非法尺寸／质量与恢复。安全区、白色系统栏图标和横屏适配在新矩阵中有相应断言及截图；独立的M1–M5评分仍由完成审阅者给出。

进度页使用隔离APP_ONLY的真实三项生产Batch，确认一项完成、下一项处理中及另一项等待的ledger、明确停止、保留完成文件、源字节保持、两项停止和进度不冒充100%。关闭进度页与取消的语义分开。这条原生进度证据不等于生产登录账号切换或手机相册发布生命周期验证；账号失效执行守卫有对应host检查，未外推为真实账号服务认证。

该候选五份原生报告的自有夹具／草稿／进度scratch清理、系统字号／外观／旋转设置与权限恢复均为PASS。归档[UI-DEVICE-CLEANUP.json](pre-glass-0d35b8c8/UI-DEVICE-CLEANUP.json)记录自有探针已卸载、未触碰实体设备、MediaStore发布数0。关闭细节源后的私有PNG目录不存在由原生scopeGuard确认；外部 `run-as` 因目标非debuggable被拒，未绕过，也不声称从外部列过整个缓存。历史[EMULATOR-CLEANUP.json](pre-glass-0d35b8c8/EMULATOR-CLEANUP.json)记录当时核对5586的ranchu硬件、专属PID39908与Pixel_9端口后关闭，`shutdownVerified=true`；不把该次关闭当作后续新会话已关闭。

| 证据 | 已记录范围与边界 |
| --- | --- |
| 历史功能候选构建 | 0d35…构建、签名、大小／SHA及当时205项输入快照与backend／全部UI安装身份一致；不代表新玻璃源码 |
| Host 计算／任务检查 | 已通过，见上表各专项与HOST记录；不能替代 Android 编解码或权限行为 |
| 原生导出 probe | PASS169，APK匹配，私有夹具清理完成；不含MediaStore／设置／权限 |
| 原生 UI probe | phone／font1.3／dark各114、landscape116、progress23 PASS；截图、物理像素映射、交互、恢复分别记录 |
| 历史 effect 回退 | 原生backend覆盖真实effect无record的完整Q95新编码及二次复用；UI覆盖真实workspace回退、完整JPEG像素和调色前禁用 |
| 保存与回执 | 状态矩阵host71通过，真实UI默认核对／输入路径已运行；本轮原生未新增MediaStore发布，不认证公有副本存在／删除／未知／新副本完整生命周期 |
| 旧设计对照与M1–M5 | 0d35…四配置／进度证据已齐；旧五项复核只适用于该候选，不能覆盖用户随后否定的视觉 |
| 当前玻璃视觉 | f717…三配置172／172／174及真实模式线／1:1检查通过，独立有界清单clear／ship，206输入与包／签名身份及PID23100会话清理完成；dark eaa143／backend b5 169／progress 0d35 23仍按原身份引用 |
| 环境清理 | 自有探针／夹具／草稿／PNG与设置／权限恢复PASS；5586身份核对后关闭且shutdownVerified=true |

本轮未安装实体手机或公开发布。限定隔离模拟器与合成夹具不证明实体相机、生产网络／账号、RAW精度、FPS、色彩标定、所有设备内存条件或TalkBack，未覆盖tablet与真实MediaStore发布生命周期；dark没有在最终包重测，后端与进度沿用明确旧SHA。独立ship仅限本轮评分修正，不替代用户最终视觉批准。已批准需求的原始验收checkbox保留审核时形式，实际覆盖以本页及关联报告为准。
