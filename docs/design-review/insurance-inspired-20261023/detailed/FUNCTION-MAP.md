# Huevia 橙光详细稿 · 功能对照

本稿核对的是当前工作区的原生 Java 实现与已批准的 83 / 0.16.0-beta 需求，不将旧文档中的历史行为当作当前规则。交互原型使用少量明确的演示夹具，不连接真实账号、相机、手机图库、预设目录或更新服务。

## presets

| 现有能力 | 本次设计位置 | 原生接入位置 |
| --- | --- | --- |
| 在线 / 我的、分类、下载、个人预设 | 预设库；完整分类横滑；来源对应不同 ＋ 动作 | MainActivity.java:734 renderHome；PersonalPresetPanel.java |
| 在线发布 / 本地导入 | 在线 ＋ 打开投稿；我的 ＋ 选择 NCP / NP3 | MainActivity.java:678；CommunityPanel.java；PersonalPresetSync.java |
| 模板近似预览、原图对比、曲线、评论 | 预设详情；更换统一模板；只读曲线；讨论 | MainActivity.java:1596 renderDetail |
| NCP 相机导入 | 先下载，读取能力与 C1–C9，选择空槽或确认覆盖 | MainActivity.java:2249 beginImport；2834 renderConfirmation |
| NP3 支持边界 | 详情单列手机预览可用性；始终禁用 NP3 相机导入 | MainActivity.java:2254 的明确拒绝；Np3PreviewProfile.java |
| 兼容适配 | 导入确认中保留中等范围锐化 / 清晰度 | MainActivity.java:2858；PictureControlImportSession.java |
| 写入结果与回验 | 成功页列出槽位；不确定时只读核对，不再直接写入 | MainActivity.java renderImportOutcome / confirmImport |

## sync

| 现有能力 | 本次设计位置 | 原生接入位置 |
| --- | --- | --- |
| 相机照片 / 照片直播 / 已下载 | 同步页三个标签；不同来源清楚区分 | AlbumPanel.java:762–771 |
| 只有预览时纯浏览 | 相机预览页不挂载编辑工具；先下载完整原图 | AlbumPanel.java；PhotoImageView.java |
| 单张 / 多张下载、原图大小、进度 | 相机页选择、保存位置、任务页 | AlbumPanel.java；PhotoDownloadRecovery.java |
| 直播项目、默认预设、新建 | 项目列表、新建表单、默认预设选择 | AlbumPanel.java:1546 showNewLiveProject；PhotoLiveProjectStore.java |
| 接收 / 保存 / 效果各阶段 | 直播状态演练；记录页按项重试 | PhotoLiveState.java；PhotoLiveReceiptStore.java；PhotoProjectPresets.java |
| 独立 NEF 原文件保存 | 同步 NEF 入口；不把 JPEG 改名为 RAW | RawPhotoPanel.java；RawPhotoStore.java |
| 富士 X-T20 JPEG 实验适配 | Wi-Fi 品牌选择；不提供 Nikon 预设写入与 NEF 获取 | MainActivity.java:2249；FujifilmPhotoClient.java |
| 根页面切换不停止共享连接 | 导航只切换界面，直播状态继续保留 | CameraConnectionRuntime.java；AlbumPanel.java |

## album

| 现有能力 | 本次设计位置 | 原生接入位置 |
| --- | --- | --- |
| 默认 App 内 / 手机，两来源 | 本地相册；没有虚构“RAW”根相册标签 | LocalAlbumPanel.java；LocalPhotoRepository.java |
| ＋ 选择 JPEG / PNG，菜单刷新 / 多选 | 单一悬浮加号与更多菜单 | LocalAlbumPanel.java:342、409–422 |
| 未授权 / 部分授权 / 撤销 | 中央授权入口、部分范围提示、重新选择范围 | LocalPhotoRepository.java；LocalAlbumPanel.java |
| PNG 白底 JPEG 工作副本 | 进入编辑前明确确认；原 PNG、手机原图保留 | LocalAlbumPanel.java:342–344 |
| 原图 / 已保存效果 / 草稿 | 照片版本选择与编辑更多菜单 | PhotoEditStore.java；PhotoEffectGallery.java |
| 滤镜在线 / 我的、GRID、多个分类 | 固定 224dp 黑色工具中保留四宫格、来源、横滑分类 | PhotoFilterEditor.java；PresetSelectionPage.java |
| 13 项调节、精确值、帮助、撤销重做 | 调节模式；曝光 −3 至 +3 EV；数值错误留在表单 | PhotoAdjustments.java；PhotoFilterEditor.java |
| 独立构图、比例、90°、±45° | 暂存工作区；取消不提交，应用记一步撤销 | PhotoCropDialog.java；PhotoGeometry.java |
| 我的配方 | 保存 / 重命名 / 删除 / 套用；不包含构图 | PhotoRecipeStore.java；PhotoFilterEditor.java |
| 多张处理与失败重试 | 相册多选 → 效果 / 配方 → 明确批量导出 | PhotoPresetBatch.java |
| 保存位置与重复版本 | 保存对话框，默认复用；额外副本须勾选 | PhotoSaveChoice.java；PhotoEffectGallery.java |

当前 13 项顺序：亮度、曝光、对比度、高光、阴影、白色、黑色、色温、色调、饱和度、自然饱和度、清晰度、锐化。曝光 −3.0～+3.0 EV，锐化 0～100，其余 −100～+100。它们复用原生渲染器；浏览器只给可操作的视觉示意，不认证色彩准确性。

保存规则：相机 / 直播首次默认 App 内，编辑导出首次默认手机相册；按场景记忆，直播再按项目记忆。取消不提交新偏好。任务与重试冻结所选目标；同版本有效副本复用。只有“另存新副本”新增本次时间副本。既有 81/82 的“每次显式保存都新增”已被 83 当前规则取代。

## settings

| 现有能力 | 本次设计位置 | 原生接入位置 |
| --- | --- | --- |
| Wi-Fi / USB、型号、能力、断开 | 设置首页、Wi-Fi 步骤页、USB 授权衔接 | MainActivity.java:1285 renderSettings；CameraWifiSession.java |
| 后台连接保护、外观、预览模板 | 应用偏好；浅色方案详细化，深色实施待独立核验 | ConnectionProtection.java；AppAppearance.java |
| 游客、注册 / 登录、QQ、头像 | 账号页与授权 / 同步确认 | AccountPanel.java；QqLoginController.java |
| 已有本机预设同步征询 | 登录后先确认，不自动上传照片与配方 | PersonalPresetSync.java |
| 投稿、退回、待审、已发布 | 我的投稿与完整提交表单 | SubmissionFlow.java；CommunityPanel.java |
| 消息已读、回复目标、评论审核 | 消息 / 讨论面板；失败保留未读 | CommunityRepository.java；CommentReplyTarget.java |
| 更新渠道、忙时等待、系统安装 | 应用更新页与状态演练 | UpdatePanel.java；AppUpdateManager.java |

## 后续接入顺序

1. 先在原生 UiPalette / AppIcon / 公共控件中建立橙光 token，保留可见数据与事件处理。
2. 依次实现预设、同步、相册、设置四个根页，再进入详情、导入、编辑与系统衔接。现有协议、文件校验、草稿和任务模型继续复用。
3. 每完成一个流程，用相同夹具对照原型和原生截图；再检查 1.3 倍字号、窄屏横屏、错误恢复与系统返回。
4. 用户确认的界面以 IMPLEMENTATION-CONTRACT.md 为对照标准。本稿不等于原生实现、已发布版本或机型兼容认证。
