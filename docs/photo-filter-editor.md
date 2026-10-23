# 照片滤镜、调节、构图、我的配方与逐张批量导出

适用版本：83 / `0.16.0-beta`。本轮新增本地相册、将原相册改名同步，并统一明确保存位置与同版本复用，复用 80／81 的 13 项调节、构图、我的配方和 82 的横滑分类／稳定直播预览。用户追加要求已落实为悬浮“＋”、居中“点击获取授权”和原生更多菜单。范围见[已批准需求](design-review/local-album-20261023/REQUIREMENTS.md)、[本轮交接](design-review/local-album-20261023/README.md)及[原生验证](validation/photo-local-album-83/README.md)。最终已发布包 SHA-256 为 `f320eef5e6068e94fff54f37509374d09f723492f6fb3077052d727e55d79ebd`；完成修正复核为限定 ship，83 已发布 beta，release `5e67b2cd-d628-46c0-b80f-ad4d2c1f4af1`、revision 2，见[发布回执](validation/photo-local-album-83/PUBLICATION.md)及[下载测试版](https://huevia.cc/preset-library/download/?channel=beta)。既有 80／81／82 发布与测试保持各自历史范围。

已有完整本地 JPEG 原图时，打开照片进入暗色编辑页；已确认 PNG 的白底 JPEG 工作副本使用同一入口。编辑资格由完整本地原图决定，与相机／已下载／直播标签页或照片来源无关；相机照片下载、直播保存完成后重新核对资格。只有相机预览时继续使用纯浏览全屏、收藏、底部导出及预览错误原因／重试，不挂载编辑器或请求在线配方。切到预览照片会解除旧编辑绑定，旧照片／账号的异步结果失效。

## 本地相册与同步（83）

根导航视觉顺序为“预设／同步／相册／设置”。同步保留原相机照片、照片直播、已下载和项目数据；本地相册默认“App 内”，可切“手机”，两来源分别保存滚动位置。切根页只切换界面，不停止共享相机连接、直播接收或既有处理任务；冲突操作按实际控制器状态等待或提示。

相册用日期分组、真实方形缩略图和文件名展示照片，App 内按原图归组合并效果版本，可明确选择原图或某个版本。常规三列，非紧凑且宽度至少 600dp 五列；紧凑横屏按实际可用宽高、字号和导航预留计算列数，48dp 标题及来源／状态合并行保留完整首行照片与名称。授权引导可滚动，列表、加号与选择栏均避开浮动导航。

单一 56dp 悬浮“＋”的辅助标签是“选择照片”，在两来源均可调用系统 `ACTION_OPEN_DOCUMENT`，限定 JPEG／PNG、允许多选。常驻操作行收进原生更多菜单，提供刷新、选择、部分授权重新选范围及手机权限设置；长按照片也可多选，最多 100 张。“手机”未授权时在中心显示可点击的“点击获取授权”，并说明可用“＋”只选所需照片。拒绝、部分授权或撤销不影响 App 内照片；回到前台重查访问权限，失效快照和缩略图不继续使用。

手机浏览只查询当前授权 JPEG／PNG 的分页信息并按需解码缩略图，不全库复制。明确打开或批量编辑时，原 JPEG 逐字节保存到正式私有图片目录并记录来源；手机原文件保持不变。PNG 先确认“以白底编辑 PNG”：原 PNG 完整保留，透明部分在 JPEG 工作副本上变白，再进入原编辑器。私有原图／PNG／来源记录及草稿属于正式数据，不是可清预览缓存，也不自动上传。单张 96 MiB、5,000 万像素、批量总量 1 GiB 与设备内存检查在超限时明确停止；不静默缩小后冒充原尺寸。导入中取消或部分失败保留已经提交的正式工作副本。

## 照片优先的紧凑工具

画布为 `#141415`，贴边工具区为 `#0B0B0B`，保留浅蓝 `#8DBBFF` 操作、系统 sp 文字和至少 48dp 触区。上方是返回、文件名／位置和导出；照片工具以紧凑预设说明、星标、更多与角落原图对比组成。预设小字使用克制的深色衬底，在横屏浅色照片上仍可读。版本与重置位于更多菜单。

底部“滤镜／调节／构图／配方”沿用原高度；滤镜、调节和配方共用固定画布、缩放和照片位置，构图按需打开独立全屏工作区。工具内容仍为 224dp 加 1dp 顶部分隔：176dp 的面板内容和 48dp 四个入口，不再常驻大圆角玻璃包围或重复状态页脚。滤镜模式保留 48dp 头行，依次放最左侧四宫格 GRID、在线／我的、竖屏短状态和多个可横滑分类名称，下方是 66×80dp 卡片（52dp 图像、22dp 名称）及强度。调节模式只有撤销／重做、一根滑杆、可点击数值和横向 13 项参数条；已修改项带点标记，参数名／值／选择状态有语义说明。

主图按真实原图或用户已应用的构图比例适应画布，保留缩放和切图；未操作构图时完整显示原比例，缩略图可裁切。既有生成设计图曾重新构图，不能把其照片比例自动套到原片。横屏工具位于右侧 300dp 区域并可纵向滚动；筛选网格和导出确认按可用高度滚动。正常及实际 1.3 字号下保留导出、返回和参数入口。

## 13 项基础调节

所有参数中性值为 0。曝光为 −3.0 至 +3.0 EV，步长 0.1；锐化为 0 至 100，步长 1；其余为 −100 至 +100，步长 1。参数条顺序为亮度、曝光、对比度、高光、阴影、白色、黑色、色温、色调、饱和度、自然饱和度、清晰度、锐化。首次参数为曝光；同一会话切图可保留参数类型，数值与预览始终属于当前照片。

曝光是 JPEG 后期等效曝光补偿，亮度主要影响中间调；高光／阴影覆盖较宽明暗区域，白色／黑色集中于最亮／最暗端。色温为相对冷暖，色调为绿至洋红偏色，不显示绝对开尔文。饱和度整体影响色彩，自然饱和度优先增强较淡的颜色。清晰度增加／减弱局部反差，锐化增加边缘清楚程度；0 不额外处理，也不消除滤镜配方本身已有细节。

`PhotoAdjustments.Parameter.help()` 提供每项简短说明，可从更多菜单“参数说明”或长按滑杆旁数值打开，结合当前照片与原图对比理解作用。点击数值可精确输入；格式或范围错误显示提示并保留最后有效值。长按参数图标或更多菜单可归零单项；“重置调节，保留滤镜”清零手动值，“全部重置”清除滤镜、手动值与构图，保留历史版本。

撤销／重做覆盖滤镜、强度、手动值、应用构图、单张套用配方及重置，当前照片编辑会话最多保留 32 步，一次连续拖动只记一步。已为 0 的单项重置、全部基础参数中性时重置调节、确认未变的精确值均为无操作：不创建草稿、不改变已保存版本，也不覆盖暂存的未导出草稿。按住原图图标显示最初校验原图，松开、取消或失焦恢复完整结果；辅助标签说明按住查看操作。

## 构图

“构图”打开全屏暂存工作区，照片上显示裁剪框、三分网格和四角手柄。拖动四角改变裁剪范围，框内拖动移动位置；比例依次提供原比例、自由、1:1、4:3、3:2、9:16。“旋转 90°”顺时针旋转，拉直范围为 −45.0° 至 +45.0°、步长 0.1°；拉直自动放大覆盖画面以避免黑角。横屏将可滚动控制区放在右侧 300dp，顶部“取消／应用”保持独立。

取消、系统 Back 或关闭不改变照片草稿；点“应用”后整个构图只记一次撤销，未改变设置则不产生新步骤。“重置构图”只在该暂存工作区恢复中性，仍须应用才生效。EXIF 的 8 种方向先归一，预览与完整像素导出使用同一 `PhotoGeometry`／`PhotoComposition` 映射，输出方向为 1。裁剪会减少保留像素；不会为省内存而静默缩小分辨率，原图保持不变。

部分旧效果没有可恢复的构图参数。仅当最新已导出草稿与所选版本的完整身份吻合时恢复其设置；否则构图入口明确提示切回原图或恢复草稿，避免从旧 JPEG 猜测设置。

## 我的配方

“配方”在原面板高度内显示“我的配方”、单行“保存当前”、可滚动列表及“仅滤镜和调节 · 本机按账号保存”说明。每份配方保存不可变的 NCP／NP3 原字节快照、滤镜强度、13 项手动值和模型身份，不保存照片或裁剪／旋转／拉直。游客和各登录账号的本机配方相互隔离，不自动云同步或公开。

保存和重命名要求去除首尾空白后 1–40 个字符，禁止换行；同名（不区分大小写）提示换名，不覆盖旧配方。错误显示在仍打开的命名表单内，保留输入以便修改。每个账号最多 100 份；列表管理菜单提供重命名与确认删除。删配方不改变已套用照片、草稿或已保存效果。

点击配方只替换当前照片的滤镜、强度和调节，保留该照片构图，并记为一次可撤销编辑。快速连续选择在 IO 排队时以最后一次请求为准；被替代的完成结果不能修改设置或增加撤销，仍保留照片、账号及起始设置快照检查。已下载或直播照片多选后，从“套用效果 → 我的配方”进入批量套用：逐张读取自身构图，写入未导出草稿；未导出前不生成相册副本。部分失败报告已完成数并允许重试，随后仍由用户明确批量导出。

## 渲染、草稿与版本

固定处理顺序为：校验原图 → 所选滤镜及 0–100% 强度 → 手动曝光／相对冷暖偏色 → 亮度、对比度与明暗区域 → 饱和度／自然饱和度 → 按分辨率统一尺度的清晰度／锐化 → EXIF 归一后的旋转／拉直／裁剪 → 预览或完整像素导出。每次从原图重建，不在上次效果或压缩 JPEG 上累加；拖动不反复编码 JPEG。预览、缩略图与导出使用同一参数模型，拖动优先更新主图，松手按需刷新缩略图。

滤镜强度只影响滤镜；“无滤镜”或强度 0 时，手动值仍生效。“无滤镜”只取消滤镜并禁用强度，保留手动值；切换滤镜也保留手动值。全部手动值为 0 时严格复用既有滤镜模型，不额外处理；无滤镜、手动值与构图全中性时回到原图。NCP／NP3 原字节不改，手动值不写回相机或直播项目默认设置。

PED3 草稿按账号及校验原图内容隔离，保存不可变预设快照、强度、13 值、几何和模型版本；PED1／PED2 缺少的几何按中性迁移，PED1 缺少的手动值按 0 迁移，保留既有滤镜／强度／导出状态。通常仅恢复未导出草稿；与所选最新效果完整匹配的已导出草稿可恢复编辑参数。显式查看原图或旧／新已保存版本时尊重该版本，暂存的未导出草稿可从更多菜单恢复。暂停／离开先刷新待写草稿，撤销历史只保留当前会话。

快速拖动、切照片、切账号或关闭会使旧异步任务失效，旧完成结果不能覆盖新照片或新值；较早导出不能把后来的参数标成已导出。完整效果身份包含原图、预设、强度、模型、全部非零手动值及非中性几何；中性值与中性几何保留旧模型身份，任一有效设置变化不能误复用别的效果。

## 来源、分类与选择稳定性

默认在线，可切“我的”查看当前账号可见预设。内嵌横滑分类沿用“全部”、人像、风景、胶片、黑白、日常、夜景，再列实际自定义／未分类；只显示该来源已有项。最左侧四宫格打开完整选择框，保留搜索和三列当前照片效果，竖屏约 72%、横屏约 90% 可用高度。两处分类调用同一 `selectCategory`，同步选中、缩略条／网格结果、真实数量并重置结果位置，不改变已套用预设和调节。完整标题显示来源、当前非“全部”分类和实际预设数，“无滤镜”单列保留。切换来源清除搜索和分类并回到起点；横屏完整框将来源放标题行，搜索与分类合为 48dp 一行。

在线目录独立加载，可见或选择的配方才按需读取，核对长度、SHA-256 和容器格式。浏览／套用不自动导入“我的”，不覆盖同 SHA 的本地归类。网络失败使用经验证的同源目录缓存；未缓存配方明确失败并可重试。竖屏头行显示加载、重试或缓存等短状态，点按可重试准备或刷新在线目录；横屏 300dp 右栏隐藏此短状态以保留多个分类名称。完整状态仍在选择框以及来源／分类的提示和辅助描述中显示，完整框保留刷新入口。目录失败不阻断本地原图的无滤镜手动调节，已选配方读取失败不能被悄悄跳过。

横条按配方身份保留卡片，点击及异步缩略图只原位更新图像／文字／选中态，点击保持 scrollX。展开网格仅在所选目标不可见时最小滚动，恢复所在行而非承诺像素偏移。位图、配方字节及 profile 共同按 48 项淘汰，选中配方单独持有；队列满时有四次有界延后重试，真实任务完成继续优先处理当前选择。2026-10-20 的[生产分类记录](preset-category-classification-20261020.md)保留 241 份分类的历史核对，不表示本轮对实时生产目录复验。

## 直播默认预设预览稳定性（82）

直播默认预设选择页沿用浅深色布局和既有模板效果。异步预览完成只更新对应条目的已挂载卡片；同一条目及预览键保留已就绪图像，即使 LRU 淘汰并重绑也不退成占位。正常说明行固定占位，加载和就绪切换不改变常规卡片高度。更换条目／键清除旧图，账号、页面和请求过期检查保留。

保留图片不能覆盖失败状态：确认读取失败为 ERROR、profile 不支持为 UNSUPPORTED，两者都清除保留图并禁用内容；ERROR 保留“重试预览”，恢复配方后可通过实际重试再次生成并选择。该 M1 是初审唯一材料问题，修正已落地。测试通过真实选择确认读取失败与重试，以及明确注入旧 READY 缩略图夹具后的实际 profile 解析失败检查状态；注入只用于该错误路径，不是预设支持认证。“不使用预设”仅验证绑定 `performClick` 返回一次，物理指针注入未定。

## 单张与批量导出

1. 为每张照片选择滤镜、强度、手动值及构图，或套用本机配方。操作只更新预览和私有草稿，不创建永久效果或系统相册副本。
2. 单张点右上“导出”，确认滤镜／强度、手动项数（可展开非零明细）、构图、JPG 与实际输出像素尺寸，再选择保存位置。
3. 本地相册、已下载或直播项目中多选后批量导出。每张使用自己的未导出草稿；没有未导出草稿时沿用所选已保存版本，已导出记录不会覆盖后来选择的原图或旧效果。
4. 开始时冻结每张原图身份、预设、强度、全部手动值、几何、保存位置与是否另存新副本，最多 100 张。之后的新编辑或偏好变更只影响后续任务；失败仅重试失败／未处理项，并沿用该次快照，可取消后续任务。

83 的保存位置对话框显示“仅保存在 App 内／保存到手机相册”。相机单张／批量保存和直播首次默认仅 App 内，编辑导出首次默认手机相册；同步、编辑、自动效果等场景分别记忆，直播另按项目记忆明确选择。仅 App 内完成正式原图／效果和草稿关联，系统相册新增 0，不自动上传；之后仍可明确保存到手机。任务与失败重试固定已确认目标，取消不提交新偏好。

效果为完整保留像素的 JPEG 95／sRGB，加入原照片版本，裁剪按实际尺寸输出。相同原图与完整设置可复用经校验的渲染；保存到手机时，同版本已有有效公有副本经长度、SHA、URI 状态及归属核对后直接复用，重复保存不重复新增。首次新发布使用本次时间；已有有效同版本还需要额外一张时，明确勾选“另存新副本”。新发布通过冻结副本设置 JPEG 日期、时区、毫秒与 MediaStore 日期，不重复压缩像素。旧相册副本和渲染缓存字节保留，原图字节／时间不改；成功发布后更新本地效果文件修改时间。这个默认去重规则取代历史 81／82 每次显式效果导出都新增的规则。

未编辑的导入 JPEG 可通过只读来源记录和实际字节核对复用仍存在的手机原照片，不创建重复副本；来源已改变、缺失或当前不可核对时分别处理，不能以读取失败推断“已不存在”后静默新增。明确新副本可绕过来源复用。该来源证据不写成 App 自有发布回执，App 删除无权删除外部手机原图；PNG 的白底 JPEG 是新版本，不复用 PNG 原来源。Android 私有根的 `/data/user/0` 与 `/data/data` 合法别名已处理，目录穿越、越界、子路径／文件符号链接和所有权检查仍保留。

每张新副本的 URI、实际发布字节 SHA-256、长度与本次时间保存在私有回执，未完成发布使用冻结副本恢复。删除效果或整组照片前，在本地处理互斥保护下核对所有回执及发布内容；已修改或归属变化的相册副本会阻止删除，不猜测其归属。内存、写入或核对失败明确报告并保留可恢复数据，不静默缩小尺寸。

NEF 是独立原文件导出，不包含手机滤镜、手动值或构图；连接富士时不提供 Nikon NEF 获取，离线已保存 NEF 沿用原发布规则，详见[RAW 验证记录](fujifilm-raw-export-validation-20261019.md)。本轮未加入曲线／HSL、人像美容、生成式修复、RAW 显影或直播自动美化；手机预览仍是近似模型，没有新增 NX Studio 标定。

## 照片直播空项目入口修复

用户追加反馈点击“照片直播”闪退。修复前在真实点击中复现：项目预设存储已经初始化，但尚未选择项目，`AlbumPanel.updateLivePresetProgress` 将空 `selectedLiveProjectId` 传入 UUID 解析，抛出 `IllegalArgumentException`。栈保留于[修复前记录](validation/photo-composition-recipes-81/photo-live-crash/before-fix.txt)。

当前在没有选中项目时跳过配置查询，正常呈现空直播项目和未启用自动套用状态；存储层 UUID 校验继续保留。81 当轮最终三模式均先初始化实际项目存储，再通过真实点击进入空项目直播页、返回已下载，来回三次而未退出 Activity；`photo-live-no-project.png` 记录空状态。此回归没有创建用户项目、开始直播或连接真实相机。另有[真实主导航点击与冷启动恢复确认](validation/photo-composition-recipes-81/photo-live-crash/after-fix-main-navigation.json)，以稳定截图及存活进程人工确认空项目页；UIAutomator idle／文字断言未能确定，该补充证据不计入 516。

## 83 最终包、完成复核与发布状态

最终 [83 APK](../dist/huevia-0.16.0-beta-debug.apk) 为 3,528,301 字节，SHA-256 `f320eef5e6068e94fff54f37509374d09f723492f6fb3077052d727e55d79ebd`，沿用既有签名。[native-matrix](validation/photo-local-album-83/native-matrix.json)记录 phone／实际 1.3 字号／横屏／深色各 PASS 300，四模式恢复 PASS，合计 1,200 次重复断言执行，非 1,200 个独立场景。28 张原生截图与含 hero 的复核副本有 manifest／来源元数据；PNG 编辑截图是在断言后显示滤镜态，不是可见调节标签证明。

[同审阅者 FIX-VERDICT](../.impeccable/review/local-album-83/FIX-VERDICT.md)在打开全部最终 28 张及 hero 后，判 M1 紧凑横屏空间问题 resolved、remaining clear、ship，范围限该修正、用户后加的加号／授权／菜单安排及记录的回归；初审 M2 缺入口为已撤回的事实误读。旧 `fix-before-fcd0/` 每模式 273 与其他失败／诊断候选不计入最终 300。

最终[聚焦 host 来源](validation/photo-local-album-83/host-gallery-final-provenance.json)记录 69 项输入前后哈希和 389 检查（gallery 250／origin 86／deletion 36／identity 18），覆盖规范化点路径别名与失效自有回执回归；Android `/data/user/0`／`/data/data` 私有根别名由原生报告另证。较早[host 日志来源](validation/photo-local-album-83/host-provenance.json)保留各组单位及替身边界；数字循环和重叠子集不累计成独立测试场景。189 项构建／探针输入在阶段观察间保持稳定；374 文件的[构建后源码快照](validation/photo-local-album-83/source-snapshot.json)仅记录当前源码，不证明历史编译输入或可复现构建。

原生在隔离模拟器执行真实 MediaStore、JPEG／PNG 导入、编辑／批量、App 内零新增、默认复用和明确本次时间新副本。512 字节手写结构 TIFF／NEF 仅验证 RAW 保存包装、目标冻结重试与当前夹具恢复，非传感器数据。加号／授权通过实际绑定 View 点击到框架 Activity 派发，由监控返回取消且无导入；外部 DocumentsUI／权限控制器的画面、文字与完整交互未认证。通知权限预授予以排除无关弹窗。实体相机／直播传输、传感器 RAW、FPS、颜色标定及 TalkBack 未据此验证。

只读最终发布准备通过 57 runtime、22 production syntax、8 archive、2 diagnostic 检查；归档只新增 83 APK（42→43），旧五包、运行字节、模式和配置保留。实际[发布回执](validation/photo-local-album-83/publication.json)确认 beta release `5e67b2cd-d628-46c0-b80f-ad4d2c1f4af1`、revision 2；两来源公开 APK 原字节与最新元数据一致，旧发布行保留。部署 43 项成员及原模式／配置独立核验，五个旧本地包保留。实际下载 HTML、脚本及精确页面元数据查询已验证；这是 HTTP 核验，无浏览器渲染结论。任务专用 `ranchu` 模拟器 `emulator-5582` 已在复核后关闭，见[清理记录](validation/photo-local-album-83/emulator-cleanup.json)。新增生产栅格／图标为 0；原生 Views 不运行 HTML/CSS 检测器。

## 历史 82 验证与发布状态（不累计为 83）

适用版本：82 / `0.15.1-beta`，本轮只修正直播默认预设预览和滤镜分类导航。用户要求多个名称在原工具行横向滑动，保留最左侧四宫格打开完整选择框，并修正完整框分类响应。范围见[本轮需求](design-review/photo-filter-navigation-20261023/REQUIREMENTS.md)与[交接](design-review/photo-filter-navigation-20261023/README.md)。沿用 80 的[照片优先设计](design-review/photo-basic-adjustments-20261022/design-board-v2.png)、13 项基础调节，以及 81 的构图、我的配方和本次时间副本导出；无新视觉世界、生成稿、图标或生产栅格。依用户直接上传测试版授权，82 已发布 beta，release `4c9aee32-1c86-4482-a68e-9c16f583406a`、revision 2；双域名公开字节和最新元数据匹配最终包，见[82 发布回执](validation/photo-filter-navigation-82/PUBLICATION.md)和[下载测试版](https://huevia.cc/preset-library/download/?channel=beta)。81 已发布 beta 的[回执](validation/photo-composition-recipes-81/PUBLICATION.md)仍只证明 81；其[需求](design-review/photo-composition-recipes-20261023/REQUIREMENTS.md)、[交接](design-review/photo-composition-recipes-20261023/README.md)、原编辑器[首版需求](design-review/photo-filter-editor-20261019/REQUIREMENTS.md)及旧验证保留原范围。

最终本地 [82 APK](../dist/huevia-0.15.1-beta-debug.apk) 为 3,487,341 字节，SHA-256 `2543241174c2c7b6656b08b4142d3c3812d095b2ecd732836a11ab9a3c173987`，沿用已有签名。phone／实际 1.3 字号／横屏／深色 PASS 163／163／155／163，共 644 条断言，四报告均记录同一 SHA；环境和恢复记录单列。28 张最终原生状态和含 hero 的 29 张复核副本通过 manifest 关联同一包。分类、GRID 和横滑使用有效 MotionEvents，按条目身份核对分类结果及实际数量。受控真实 worker／LRU 淘汰／adapter 重绑检查既有 Drawable 身份及卡片位置。

旧 81 的缓存压力丢图已在[基线](validation/photo-filter-navigation-81/phone/report.json)复现；其离线分类触摸原已通过，没有复现用户实体设备上的分类无响应，集中分类处理与结果反馈按本轮要求验证。初审 [FINISH-REVIEW](validation/photo-filter-navigation-82/FINISH-REVIEW.md)的 `61d8ae4d…`、560 条属于 M1 修正前候选，不属于最终 644；同一审阅者 [FIX-VERDICT](validation/photo-filter-navigation-82/FIX-VERDICT.md)评分 M1 resolved、remaining clear、SHIP，仅限 M1 和已记录回归批次；重开 28 张和 hero 未发现修正引入的回归。只读 `huevia-82-reviewed` 准备完成 57 runtime/artifact、22 syntax、8 archive、2 diagnostic 检查。实际[发布回执](validation/photo-filter-navigation-82/publication.json)核对 beta release `4c9aee32-1c86-4482-a68e-9c16f583406a`、revision 2，主域名与 CloudBase 返回最终 APK 原字节及最新元数据，旧发布行保留。生产归档只新增 APK，41→42，原成员字节／Unix 模式／配置保留。下载 HTML、实际 download.js 和精确页面查询 HTTP 200 验证；CUA 初始化失败，无浏览器视觉结论。复核后已关闭本轮隔离模拟器。

[实现](../app/src/main/java/cc/huevia/ui/PhotoFilterEditor.java)与[直播选择页](../app/src/main/java/cc/huevia/ui/PresetSelectionPage.java)仅有这次 UI 修正。新增生产栅格／图标均为 0，现有 Canvas GRID 复用，用户截图仅作缺陷证据，见[素材清单](design-review/photo-filter-navigation-20261023/ASSET-INVENTORY.md)。证据限隔离只读模拟器、既有模板及离线真实研究／手写配方，不证明实体手机、相机、实时目录、FPS、TalkBack 或颜色标定。81 的 516、80 的 471 和旧主机套件未重跑，不累计进 82。

## 既有实现与历史 81 验证状态

- [PhotoFilterEditor](../app/src/main/java/cc/huevia/ui/PhotoFilterEditor.java)、[PhotoCropDialog](../app/src/main/java/cc/huevia/ui/PhotoCropDialog.java)：原高度四入口工具、全屏暂存构图、配方管理、32 步历史及输出确认。
- [AlbumPanel](../app/src/main/java/cc/huevia/ui/AlbumPanel.java)：编辑资格、稳定画布、版本、批量套用及导出入口。
- [PhotoAdjustments](../app/src/main/java/cc/huevia/ncp/PhotoAdjustments.java)、[AdjustedPreviewProfile](../app/src/main/java/cc/huevia/ncp/AdjustedPreviewProfile.java)、[SpatialPreviewFilter](../app/src/main/java/cc/huevia/ncp/SpatialPreviewFilter.java)：原有不可变参数与颜色／细节处理。
- [PhotoGeometry](../app/src/main/java/cc/huevia/ncp/PhotoGeometry.java)、[GeometryPreviewProfile](../app/src/main/java/cc/huevia/ncp/GeometryPreviewProfile.java)、[PhotoComposition](../app/src/main/java/cc/huevia/album/PhotoComposition.java)：共享几何身份、EXIF 归一与像素映射。
- [PhotoEditStore](../app/src/main/java/cc/huevia/album/PhotoEditStore.java)、[PhotoRecipeStore](../app/src/main/java/cc/huevia/album/PhotoRecipeStore.java)、[PhotoPresetBatch](../app/src/main/java/cc/huevia/album/PhotoPresetBatch.java)：PED3、旧草稿迁移、本机账号配方、逐张冻结设置及失败重试。
- [PhotoEffectGallery](../app/src/main/java/cc/huevia/album/PhotoEffectGallery.java)、[PhotoLiveGallery](../app/src/main/java/cc/huevia/album/PhotoLiveGallery.java)：本次日期的新副本、实际发布字节回执、恢复与受保护删除。
- [PhotoPresetRenderer](../app/src/main/java/cc/huevia/album/PhotoPresetRenderer.java)：完整像素效果与几何导出。[EditorPresetCatalog](../app/src/main/java/cc/huevia/library/EditorPresetCatalog.java) 继续提供同源缓存及只读真实预设；常驻工具实色，`EditorGlassDrawable` 仅用于临时选择／确认面板。

81 最终本地 APK 为 3,487,341 字节、SHA-256 `7861ae7163d17a5651a8c41b25ddf1b368e868cd326bdcc04a2bd72d5df7aa80`，沿用已有签名。phone／实际 1.3 字号／横屏各 PASS 172，共 516 条断言记录，三个报告记录同一 SHA，环境与恢复记录保留；实际字号 1.0／1.3／1.0、方向 1／1／2。覆盖构图实际手势与取消／应用／撤销、保存入口单行、内联命名错误、配方保留目标构图、逐张批量草稿、完整裁剪尺寸与 EXIF，以及新副本日期／无重压缩／回执／互斥受保护删除；另包括已初始化存储的空项目直播入口及三次来回切换。最终 30 张状态图和 hero 已重新核对。初审 M1 发现排队配方 A 会先应用并让 B 被旧快照守卫丢弃；最终增加最新请求序号，并以真实 IO 门闩验证 A→B 最后选择生效、构图保留且撤销只加一步，新增 5 项／模式包含在 172 内。[FIX-VERDICT](validation/photo-composition-recipes-81/FIX-VERDICT.md)为 M1 resolved／SHIP，范围仅该项修复及批次回归，不扩大为新全表面审核。主机分项：34,780 强度／草稿、644,888 手动、1,158 构图／配方，609 批处理与 82 来源链，48 项目自动检查，日志分别为 `host-editor.txt`、`host-batch.txt` 和 `host-project-auto.txt`。见[本轮交接](design-review/photo-composition-recipes-20261023/README.md)和[验证目录](validation/photo-composition-recipes-81/)。`before-review-fix/{phone,large-font,landscape}` 各 PASS 167、SHA-256 `f5b34441b4616c4ebd9a9fcaad135f258e0ba87d85be146c347e73d57f9bdd03` 为 M1 修复前候选；`pre-photo-live-fix/{phone,large-font,landscape}` 各 PASS 150、SHA-256 `936b31b4136cd4e305e67c90e4a587324792bf2a137c63f85486f51915d18b12`，只属于直播修复前候选；`initial/{phone,large-font,landscape}` 各 PASS 146，SHA-256 `af0b41fe33c814c6d470845290c828801934819ac4c1db78eb46ef227e31f067`，只属于初轮候选，不计为修正后 81 的最终验证。81 已发布 beta，release `8448462f-6610-4800-b057-6f045ce03cda`、revision 2，双域名 APK 字节与最新 metadata 匹配最终包；旧发布记录保留。见[精简发布回执](validation/photo-composition-recipes-81/publication.json)。下载页 HTTP、脚本及实际 metadata 查询已验证；CUA 初始化失败，没有浏览器视觉复核结论。验证范围为隔离只读模拟器、既有合成模板及真实／手写本地配方夹具上的 Android 渲染、JPEG、EXIF、草稿与 MediaStore；没有实体手机、真实相机、实时目录、FPS、TalkBack 或颜色标定结论。

新增生产栅格为 0；构图、旋转、拉直、配方、保存图标扩展现有 Canvas `AppIcon`，照片来自当前原图，见[本轮素材清单](design-review/photo-composition-recipes-20261023/ASSET-INVENTORY.md)。

## 历史 80 验证（不累计为 83、82 或 81）

最终本地产品包 80 / `0.14.0-beta` 为 3,458,669 字节，SHA-256 `4aea78f19ddc4e245e27470b29c9f111674f74c2e22b3229d03a2ec5fd6834bb`，沿用签名证书 SHA-256 `04db0f9d3d76c9831a0778cfabc1a0aaab984e05398da16eb644950ccfa0ae31`。最终证据在 [photo-basic-adjustments-80](validation/photo-basic-adjustments-80/)：phone、large-font、landscape 的 `report.json` 均 PASS 157，共 471 条断言记录，并记录同一最终 SHA；实际字号为 1.0／1.3／1.0，配置方向为 1／1／2，有环境回读和恢复记录。六种状态截图涵盖滤镜、调节、色彩、精确值错误、参数说明和导出；主图在滤镜／调节间边界不变。新增确认覆盖无操作重置／精确输入对已保存效果与暂存草稿的保留，以及参数帮助和文字衬底。

主机日志分别为 `host-editor.txt`（34,780 强度／草稿、644,888 基础行为／迁移／身份检查）、`host-spatial.txt`（16 场景、41,534,647 断言）和 `host-batch.txt`（609 批处理、82 来源链检查），各自保留单位和范围。原生使用既有合成模板照片、离线 23 条真实／手写配方夹具，执行实际 JPEG、EXIF、草稿及 MediaStore 路径。`initial/` 的早期 132 项候选与初轮 `FINISH-REVIEW.md` 仅是历史，不能计为最终 SHA 的验证或最终复核结论。没有实体手机、真实相机、FPS、TalkBack、实时生产目录或颜色标定结论；发布尚须以最终交付记录为准。

新增生产栅格为 0；图标扩展现有 Canvas `AppIcon`，运行时照片来自当前校验原图。设计图、截图和夹具不打包为新增产品内容，来源见[素材清单](design-review/photo-basic-adjustments-20261022/ASSET-INVENTORY.md)。

## 更早历史验证（不累计为 80、81、82 或 83）

历史 74 / `0.13.6-beta`，SHA-256 `bd8c0516cb8e03c370ebf2d08cf25229296588a5bed4ca9dcb5ec76940c8fa62` 的在线状态与选择稳定性见[验收记录](validation/photo-selection-polish-74/README.md)。phone／实际 1.3 字号／实际横屏各 PASS 567，共 1701 条断言记录，含逐卡重复；该轮玻璃／固定状态行几何与在线状态措辞不作为 80 常驻布局。

历史 72 最终包摘要为 `e563135f99857a19938d3a49ef7fceeab587250a843fd43dde8ed0ff161202b7`。竖屏 136、大字 136、横屏 155 项，共 427 项专项通过，涵盖实际下拉／取消／点击／长按、导出触摸和视觉尺寸，以及合成相机分页的加载／失败重试／末尾和原列表保留；横屏通过真实上滑抵达末项。范围限离线模拟器原生和合成传输，不认证真机或网络相机下载。其他候选 Wi-Fi 216 项及下方旧版数量不计入这 427 项，详见[72 验收记录](validation/photo-album-refresh-72/README.md)。

历史 71 APK 已构建签名，3,417,709 字节，SHA-256 `7507bd9556c0254395d5d50350f9d88a1cc90578ba5798ffc4d0c8517eb95265`。来源／分类样式专项在竖屏、实际 1.3 字号、横屏各通过 282 项，共 846 项，报告均记录同一最终摘要；首批审图通过，无需修复确认轮。范围为离线 23 条目录夹具和真实 Android 渲染，不认证真机或生产网络读取。见[71 专项验收](validation/photo-selection-polish-71/README.md)与[交付记录](../dist/nikon-usb-0.13.3-beta-VALIDATION.txt)，以下旧版数量不计入 71。

历史 70 APK 已构建签名，3,417,709 字节，SHA-256 `c1872bf77d9cfaa7439211fd86c3fb6a65acd0a5c73607f9325a0962d9ec7cca`。`run.ps1 -ExpectedVersion 70 -Scope viewer-boundary -Mode phone` 的专项原生确认通过 122 项，报告记录同一摘要；`remote-preview`、`local-editor` 和 `return-remote` 截图已核对。范围是查看器浏览／编辑资格、切图与预览原因／重试 UI，使用真实 JPEG 原文件及字节、有类型的合成相机照片描述、短时合成连接标志和拒绝传输替身。替身不建立网络连接、不提供相机字节，因此这不是相机原图下载、传输正确性或真实手机认证。见[70 专项验收](validation/photo-viewer-boundary-70/README.md)与[交付记录](../dist/nikon-usb-0.13.2-beta-VALIDATION.txt)。该轮未新增生产分类迁移或部署；下面的 951／4,440 项只对应 69，没有计为 70 重跑。

历史最终 69 APK 为 3,413,613 字节，SHA-256 `dc58bb8d3609f3793f4d04d337447fd00468752f04a74112d2076bd877ef943b`。同一 APK 的竖屏、实际字号 1.3 与横屏各通过 317 项，共 951 项，三个报告均记录同一摘要；环境回读字号分别为 1.0／1.3／1.0。集中修复大字号与横屏布局后仅进行一次确认轮，初轮 `*-initial` 结果保留，不作为最终通过依据。探针使用真实 Android JPEG、EXIF 和 MediaStore，在线分类 UI 使用明确标注的 23 条离线种子目录，内容来自既有真实及手工配方夹具；生产 241 条目录和六类分页另有独立公开核对。

69 主机回归共 4,440 项（524＋53＋2,144＋300＋82＋826＋33＋22＋356＋100），其中数据源覆盖缓存回退、同源隔离、配方校验、不导入本地、分类保留及目录／配方并发。该轮后台 119 项测试、56 模块语法检查及 PGlite 23 项通过。完整历史范围见[69 原生验收](validation/photo-editor-categories-69/README.md)与[69 交付记录](../dist/nikon-usb-0.13.1-beta-VALIDATION.txt)，不扩大为 70 验收。

68 的核心检查和原生模拟器证据保留于[历史验收记录](validation/photo-editor-native/README.md)，不自动扩大到 69。本轮不认证实时生产目录在用户设备的表现、真实手机或相机新增流程；硬件手势性能、TalkBack、长期性能及 NX Studio 色彩校准仍需专门验证。
