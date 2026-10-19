# Web 管理后台完成复核 · 2026-10-18

本轮将既有在线预设后台扩展为左侧菜单、右侧内容的管理工作区，增加普通 App 用户列表、详情与启停确认。用户确认尚未申请 QQ 互联，因此界面保留真实“待接入”与无用户状态。方向沿用云白、石墨和蓝色；Android 视觉系统及根部 sidecar 保持原有范围。实现来源为 `online-library/admin/index.html`、`app.js`、`styles.css` 与先加载的 `tokens.css`。

独立完成复核结论为 **SHIP，仅限已审阅的 Web UI**。桌面固定侧栏为 232px，中等宽度为 204px；760px 及以下改为顶部横向导航，列表重排。用户详情采用右侧抽屉，启用和停用均需确认；管理员登录与普通 App 用户独立。切换导航已恢复回顶部行为，关闭详情保留列表滚动位置。

本地合成截图保存在下列位置。JPEG 来自本轮截图 API 的输出格式；这些图片不代表真实注册用户、真实手机或生产环境。

| 视口 / 主题 | 内容 | 本地证据 |
| --- | --- | --- |
| 桌面 / 浅色 | 预设列表 | [desktop-presets.jpg](../../.impeccable/review/online-admin-2026-10-18/desktop-presets.jpg) |
| 桌面 / 浅色 | 用户列表 | [desktop-users.jpg](../../.impeccable/review/online-admin-2026-10-18/desktop-users.jpg) |
| 桌面 / 浅色 | 用户详情 | [desktop-user-detail.jpg](../../.impeccable/review/online-admin-2026-10-18/desktop-user-detail.jpg) |
| 手机视口 / 深色 | 预设列表 | [mobile-presets-dark.jpg](../../.impeccable/review/online-admin-2026-10-18/mobile-presets-dark.jpg) |
| 手机视口 / 深色 | 用户列表 | [mobile-users-dark.jpg](../../.impeccable/review/online-admin-2026-10-18/mobile-users-dark.jpg) |
| 手机视口 / 深色 | 用户详情 | [mobile-user-detail-dark.jpg](../../.impeccable/review/online-admin-2026-10-18/mobile-user-detail-dark.jpg) |

最终样式将浅色成功文字调整为 `#217A43`，占位文字显式使用 `opacity: 1`。复核记录的占位文字对比度为 5.39:1，成功状态文字为 4.67:1。静态 detector 为 **DEGRADED**，仅执行正则模式扫描，没有完整对比度检查；上述数值不来自该 detector，也不构成整站无障碍认证。

手机视口 DOM 核对为 `clientWidth = scrollWidth = 360`，内容左右留白 14px。全页截图右缘额外约 15px 为截图输出 artifact；该证据未显示页面水平溢出。

本地功能验证记录为 50 项 Node 测试通过，真实用户表 PostgreSQL 事务回滚断言 24 项通过；这些是对应实现的测试证据，本次文档更新未重跑。真实数据库断言不代表真实 QQ 登录，合成 UI 用户也不代表生产用户资料。

本复核不包含新版线上部署验收、QQ 互联申请 / 配置和真实授权登录、Android 新登录、真实手机、相机流程、屏幕阅读器、动画流畅度或性能认证。线上部署与后续验收应另行记录，不能从本次 Web UI 的 SHIP 推导完成。
