# 预设分类与编辑界面：Dot 接续记录

用户于 2026-10-20 要求将当前长任务交给 Dot 接续。本记录保留当时工作检查点及后续完成状态，69 的完成结论见文末“2026-10-20 接续完成说明”；2026-10-21 恢复核验及最新本地 74 交付见[接续审计](preset-editor-audit-20261021.md)。全部改动仍在共享工作目录，保留未提交内容，不执行 reset、clean 或覆盖其他任务代码。

**接续已完成：** 下方原检查点按历史保留；生产分类、69 实现与同一最终安装包的三个原生场景确认均已完成。最终版本／摘要／范围见文末，原检查点、旧 68 和本轮 `*-initial` 均不作为最终通过依据。

## 原任务与目标

- 本地目录：`D:\dev\ai-project\nikon`
- 当前 Codex 任务 ID：`01a152c0-e506-7bb3-8174-5a08a33ce82d`
- 任务标题：`能加入富士相机的照片下载和照片直播吗？机型是富士xt20`
- 最新目标：按预设真实色彩特征整理数据库分类；编辑器直接加载在线预设并按分类展示；修复选中预设后贴右及闪动；参考已有像素蛋糕式设计稿，补齐 Apple 风格玻璃材质、圆角与布局细节。

用户已授权实施分类与界面改动。转交时尚未实施生产分类写入。继续时先复核具体分类计划和备份，再应用；无需为已有授权的例行工作重新询问。

## 已完成：数据库分类预览，生产库尚未修改

生产库 241 份预设全部已发布，没有未发布草稿。已备份当前记录及 242 条历史版本，下载并 SHA-256 核对全部 241 份预设文件。拟分类为：人像 27、风景 85、胶片 53、黑白 21、日常 47、夜景 8；共 240 份分类变化，Bluesky 保持风景。

219 份通过现有手机色彩模型测量亮度／对比度／饱和度并结合明确名称线索；22 份模型不支持，基于名称及明确基础类型判断。逐条理由在预览内，不能宣称这些分类已经人工确认或已经上线。

本机备份及计划（目录被 git 忽略，迁移机器时必须另行保留）：

`online-library/.local/category-reclassification-20261020/`

- `before.json`：当前记录及历史快照。
- `catalog-before.json`：原公开目录。
- `files/`：已核对哈希的真实文件。
- `measurements.jsonl`：色彩测量。
- `plan.json`：拟写入计划。
- `preview.md`：逐条分类及理由。

新增实现：

- `online-library/scripts/snapshot-preset-categories.mjs`
- `online-library/scripts/fetch-classification-files.mjs`
- `online-library/scripts/preset-style-classification.mjs`
- `online-library/scripts/reclassify-preset-categories.mjs`
- `online-library/test/preset-style-classification.test.mjs`
- `online-library/test/preset-category-migration-postgres.mjs`
- `tests/tools/PresetClassificationProbe.java`

已通过 3 项分类单测及独立 PGlite PostgreSQL 集成 14 项断言，覆盖仅修改分类、保留历史、幂等、引号及并发冲突整体回滚。尚未运行后台完整 npm test/check，尚未部署或写入生产库。

复核后，在 `online-library` 工作目录应用：

```powershell
node scripts/reclassify-preset-categories.mjs --apply .local/category-reclassification-20261020
```

脚本同时核对公开目录、非分类字段及历史保留，另有 `--verify`。凭据沿用本机私有配置，勿打印、写入交接文件或提交 Git。先读脚本确认其真实 CLI 和冲突处理，再运行。

现有 API 无需改 schema：`catalog.json` 的 `preset.category` 为单一字符串，空串表示未分类；`community/feed?category=人像` 支持分类筛选并返回 categories。

## 已完成：在线编辑器数据源

新增 `app/src/main/java/com/nikonpreset/usbprobe/library/EditorPresetCatalog.java`：worker-only 构造 `(File appFilesDir, PresetLibrary local)`；`load()` 返回 `OnlinePresetRepository.CatalogResult`；`read(OnlinePreset)` 按需读取真实配方。目录失败自动回退经过验证的同源缓存，保留清楚提示；配方保留 SHA／长度／格式校验和有界缓存；浏览或套用不会自动加入“我的”，不会覆盖同 SHA 本地自定义分类。

`OnlinePresetRepository` 新增 `MANAGED_CATALOG_URL` 公有常量，值与 MainActivity 原私有 DEFAULT_CATALOG_URL 相同。**待整合：MainActivity 仍有重复私有常量，应替换引用并移除重复地址。**

新增 `tests/library-java/.../EditorPresetCatalogTest.java`，更新 `tests/run-library.ps1`。已执行：

```powershell
.\tests\run-library.ps1 -JdkHome D:\dev\Java\jdk-17.0.19 -AndroidJar D:\dev\android-sdk\platforms\android-35\android.jar
```

原有 524 项及新增 53 项通过。未进行网络写入。

## 已保存：编辑器 UI，尚未完整验收

修改／新增：

- `app/src/main/java/com/nikonpreset/usbprobe/ui/PhotoFilterEditor.java`
- `app/src/main/java/com/nikonpreset/usbprobe/ui/EditorGlassDrawable.java`

直接原因：原 choose/refresh/buildStrip 及缩略图完成会清空重建整条横条，且 revealSelection 滚到右边。当前改为以稳定 ID 保留 FilterCard，选中态和缩略图原位更新，横条点击不修改 scrollX。展开网格选择后仅在条目不可见时做最小滚动。

新增“在线 / 我的”，默认在线。目录用独立线程，不阻塞照片准备与草稿写入；5 分钟会话目录复用；配方仅在可见缩略图／选择时按需读取；照片／账号 generation 隔离，选中身份以 SHA 保持。分类按人像、风景、胶片、黑白、日常、夜景排序，保留额外自定义类别及未分类筛选；已有草稿、强度、批量导出与 RAW 门控保留。

材质：当前原图的 64px 实际预模糊样本、深色底／顶部高光、26dp 圆角；Android 31+ 展开 sheet 可请求系统背景模糊，低版本回退。没有新增装饰栅格资源。

UI 两文件已做过 Android 35 javac 局部编译，输出在 `tests/.build/editor-ui-compile`；之后少量类别、loading 与 source reset 收尾未再次整体编译。未构建新 APK，未产生本次新原生截图，不能宣称 UI 完成。

待审边界：thumbnail 16 项队列满时，展开网格内 pendingChoice 可能需要下次滚动才重新请求。可为 pendingChoice 在 RejectedExecutionException 分支补带 generation 守卫的延后重试，避免无限重试或旧照片请求回流。

## 接续顺序

1. 先检查 `git status`，保留既有富士／RAW 修复及前一任务的照片编辑未提交改动。
2. 复核分类 preview 及少量代表性配方，检查 22 份回退分类，运行适当后台检查；再应用生产分类并验证公开 API。
3. 完成 MainActivity 默认地址整合，检查在线／我的、缓存错误恢复、分类和搜索；处理 pendingChoice 队列边界。
4. 在 AlbumPanel 侧对照设计图核查顶部栏／主图／工具栏／底部 dock 几何和安全区，避免破坏每图草稿、已有版本、RAW 能力门控。
5. 统一新版本与 build-apk，避免与其他线程同时占用 `app/build/manual`。现有 app 版本仍为 68 / 0.13.0-beta，本次没有更改。
6. 更新原生探针：默认源改为在线后，旧本地 59 配方测试需先明确切换“我的”（setOnlineSource(false) 或对应 UI）。补断言：记录横条子 View 引用与 scrollX，点击并等其他缩略图返回后仍同引用、同 scrollX；切照片不残留旧 View。
7. 用确定性在线缓存样本验证在线分类／无缓存失败／重试／选中，真实线上目录验证数据库分类已到客户端。保持测试账号与实际个人预设隔离。
8. 做一轮手机竖屏、大字体、横屏批量原生截图／交互验收，集中修复，再最多一轮确认。保存构建、签名、哈希与实际验证边界，交付新 APK。

## 运行环境与设计依据

- JDK：`D:\dev\Java\jdk-17.0.19`
- Android 编译 SDK：`D:\dev\android-sdk`
- 既有原生 probe 使用 adb：`D:\dev\Android\Sdk\platform-tools\adb.exe`，专用模拟器 `emulator-5582`，脚本会校验 ranchu/goldfish，不能操作真实手机。
- 本次尝试只读 `adb devices -l` 时启动 daemon 失败，尚未申请沙箱升级或完成模拟器连接；不要把旧截图冒充本次证据。
- `tests/photo-editor-device-probe/`，`docs/validation/photo-editor-native/` 保留旧 68 验证方法及证据。
- 设计图：`docs/design-review/photo-filter-editor-20261019/design-board.png`。
- 上下文：`PRODUCT.md`、`DESIGN.md`、`.impeccable/surfaces/photo-filter-editor.md`。
- 根线程本次已运行一次 impeccable context.mjs；使用 apple-design、impeccable，按原生 Android Views 实施；用户明确要 Apple 玻璃风格，同时保留系统 Back、48dp 触控和安全区。
- 像素蛋糕官方预设参考：https://www.pixcakeai.com/guide/124480 和 https://www.pixcakeai.com/guide/175604 。借鉴“我的／推荐＋分类＋点击应用”的流程，不增加 AI／美颜等未请求功能。

## 停止位置

所有子任务均已保存并停止，没有后台数据库迁移或 APK 构建仍在运行。转交时线上 DB 未修改；不能报告分类已上线，也不能报告新界面已真机验证。Dot 可在连接的当前电脑上继续这条既有本地 Codex 任务，保留电脑在线和桌面应用打开。

## 2026-10-20 接续完成说明（69 交付完成）

以下为接续后的实际状态，替代原检查点中“尚未写入生产”“仍为 68”“尚未构建”和“未连接模拟器”等待办描述；保留上文只为记录当时交接边界。

### 生产分类已完成

经代表性配方和规则复核后采用 `editorial-style-v2`，对 241 份预设应用 240 份分类修改，Bluesky 保留风景。最终上线数量为：人像 27、风景 79、胶片 52、黑白 21、日常 54、夜景 8。上文最初的 85／53／47 等数字是旧预览，不是上线结果。

219 份有现有手机模型测量，22 份不支持模型时仅依据明确名称／元数据线索，否则归入日常，不虚构测量。事务保持预设文件、非分类元数据、发布时间及原 242 条历史不变，新增 240 条发布历史。生产独立复核和匿名公开目录／六类 feed 核对已完成，另核对每类一份真实文件的长度与 SHA。完整依据、数量、备份摘要及验证命令见[分类整理记录](../preset-category-classification-20261020.md)。

实际备份与执行计划在本机 Git 忽略目录 `online-library/.local/category-reclassification-20261020-resumed/`。本轮已完成写入，不需再执行上文初版计划的 `--apply`。后台完整检查已形成 119 项测试、56 模块语法检查及独立 PGlite 23 项断言，边界按分类记录保留。

### 编辑器实现已整合

最终版本为 **69 / 0.13.1-beta**，已构建并签名，[安装包](../../dist/nikon-usb-0.13.1-beta-debug.apk)为 **3,413,613 字节**，SHA-256 `dc58bb8d3609f3793f4d04d337447fd00468752f04a74112d2076bd877ef943b`。MainActivity 与编辑器使用 `OnlinePresetRepository.MANAGED_CATALOG_URL` 统一地址；默认“在线”，顶部可切“我的”。分类按人像、风景、胶片、黑白、日常、夜景稳定排序，仅列当前来源的实际分类，并保留额外自定义值及未分类；展开面板沿用相同来源、分类和搜索。

可见缩略图／选择按需读取真实配方，继续验证长度、SHA-256、实际容器。浏览或套用只产生照片预览／草稿，不自动导入“我的”，不覆盖同 SHA 本地自定义分类。目录与照片准备分线程，失败可回退经过验证的同源缓存；未缓存配方需要联网，无缓存或配方失败都提供清楚状态和点击重试。数据源主机检查为原有 524 项加新增 53 项通过。

横条保留稳定卡片 View，选择和缩略图回调原位更新，横条点击保持滚动位置；展开选择仅在目标不可见时作最小滚动。切照片重建条目，并以照片／账号代次防止旧结果回流。队列满时当前选择最多四次有界延后尝试，实际任务完成后继续优先处理当前选择，不无限定时轮询。

玻璃使用当前照片预模糊取样、深色衬底和顶部高光，外圆角 26dp。横条卡片为 84×110dp（图像 78dp、名称 26dp）；目录状态行固定 28dp、编辑提示固定 24dp，长字单行省略并可查看完整提示，避免撑高底栏。选择器竖屏高约 72%、横屏约 90%；大字号来源按钮单行，横屏把来源放到标题行、搜索与可横滑分类合为 48dp 行，保留完整图像和标签；来源切换不带旧滚动位。既有每图草稿、版本、完整尺寸导出及富士 RAW 能力门控保持。

### 最终原生确认已完成

已连接专用 `emulator-5582` 并核对 `ro.hardware=ranchu`。新版探针已构建，路径为 `.tooling/photo-editor-categories-69/editor-probe.apk`；`tests/photo-editor-device-probe/run.ps1` 默认版本参数改为 69，证据隔离到 `docs/validation/photo-editor-categories-69/{phone,large-font,landscape}`。旧 `docs/validation/photo-editor-native/` 的 68 截图、报告和限定 SHIP 原样保留，不能当作 69 的通过结果。

新探针明确把旧 59 配方流程切到“我的”，另覆盖默认在线、实际分类、无缓存错误及恢复重试、同 SHA 本地分类保留、满队列选择有界重试、点击／异步回调前后的子 View 引用及 scrollX、切照片无旧卡片。在线目录采用明确标注的离线 QA 种子，内容来自既有真实和手工配方夹具，使用真实格式／长度／摘要及管理端同源缓存合同；不冒充实时生产响应，不给产品添加测试后门。

一次集中修复后的一轮确认已完成：同一最终 APK 在手机竖屏、实际 1.3 倍字号、横屏各 **317 项 PASS，共 951 项**；三个最终 `report.json` 的 APK SHA 均为上述 `dc58…943b`，环境字号实际回读分别 **1.0／1.3／1.0**。历史初轮及 `*-initial` 截图保留，不作为最终通过依据。最终证据见[69 原生验收](../validation/photo-editor-categories-69/README.md)和[交付记录](../../dist/nikon-usb-0.13.1-beta-VALIDATION.txt)。本轮主机回归共 **4,440 项**：524＋53＋2,144＋300＋82＋826＋33＋22＋356＋100。

本轮确认覆盖真实 Android JPEG／EXIF／MediaStore 以及明确标注的离线 23 条目录夹具；真实生产 241 条目录及六类 feed 已另行独立公开核对。范围不包含实时生产目录在用户设备的表现、真实手机或相机新流程认证、TalkBack、长期硬件性能或 NX Studio 色彩标定。此文档更新本身未启动模拟器、运行验收或构建新包；后续不需重复生产分类迁移，也不改写旧 68 证据。
