# 在线预设分类整理（2026-10-20）

已在用户明确要求“处理预设在数据库的存储分类”的范围内，对 `nikon-d8girdjsr252fd90e` 的官方预设库应用经复核的 `editorial-style-v2` 计划。241 份预设中更改 240 份分类，保留原有 `Bluesky` 风景分类。未部署服务，未更改 schema、用户、社区投稿、凭据或预设二进制。

| 分类 | 上线数量 |
| --- | ---: |
| 人像 | 27 |
| 风景 | 79 |
| 胶片 | 52 |
| 黑白 | 21 |
| 日常 | 54 |
| 夜景 | 8 |

分类用于选择预设，不限制实际拍摄题材。优先考虑名称、简介与明确基础类型中的黑白、夜景、人像、自然风景等线索，再参考现有手机预览模型对亮度、反差和色彩的测量。明亮柔和的色调优先人像；风景指标判断需要至少两项一致依据，不凭单一高饱和度或反差阈值下结论。明确胶片、电影或复古线索归入胶片，缺少一致题材依据时归入日常。来源目录标签不是摄影题材。

219 份通过当前手机预览模型测量。22 份模型暂不支持，只使用明确名称／元数据线索，否则低信心归入日常；没有伪造色彩测量。其中人像 2、风景 1、胶片 4、黑白 4、日常 9、夜景 2。`Kodachrome` 不会因 `achromic` 子串误归黑白；`GoldenHourGlow` 不会因 `gold` 误认为 Kodak Gold；已有手工题材分类不会被来源标签整理覆盖。

## 数据与事务核对

应用前备份位于本机 Git 忽略目录 `online-library/.local/category-reclassification-20261020-resumed/`：`before.json`（241 条完整预设、242 条原历史）、`catalog-before.json`、`files/`、`measurements.jsonl`、`plan.json`、`preview.md`、`apply.sql` 和 `rollback.sql`。

更新使用单个 PostgreSQL `DO` 事务、原有发布 advisory lock、逐行修订号与完整草稿／已发布快照比对。通过原 `nikon_save_preset` / `nikon_publish_preset` 追加 240 条新历史，同时保留原 `published_at`，避免分类整理重排社区发布时间。原 242 条历史逐条保持不变，总历史为 482 条。预设文件、名称、简介、来源、兼容信息、排序与预览资产等非分类元数据均核对一致。

独立 `--verify` 于 2026-10-20 14:51:31（Asia/Shanghai）通过，报告 `verification.json`。匿名公网验证于 14:50:56 通过：目录共 241 份；按六类分页查询社区 feed，241 份全部在对应类别内，数量与上表完全一致；另对每类 1 份公开文件核对长度与 SHA-256，共 6 份，报告 `public-verification.json`。

| 文件 | SHA-256 |
| --- | --- |
| before.json | `50314ac48c2c0677c8196b529e5f77067b76f15093b569eaa5b6a390107161e5` |
| plan.json | `5af17c471f8486224c8423a177140d1770b8eacaa0832cbb0b0c6f04ebf6a459` |
| apply.sql | `0c05fe70b24953c3fde4c978b60928ac2d173198721e8815317613f3af8e4136` |
| rollback.sql | `9e745eee0d004fb6f83b2e7501abe91e5ac3234c351ff0972821f18e3c3e22df` |
| verification.json | `341a05aa5a18ce7673e6874497fc9e22c5e6928928b2117007f9bc41228e6be7` |
| public-verification.json | `97f88d437d2a794491ea1edb255576ee764c3ff162b3bf9d8d542f119f4a70a9` |

## 验证与维护

真实完成的本地验证：完整 Node 回归 `npm test` 为 119/119；源码／语法检查 `npm run check` 为 56 模块；独立 PostgreSQL 集成为 23 断言，覆盖分类限定修改、历史不变、保持发布时间、幂等重跑、恢复操作、安全引号与并发冲突整体回滚。前次沙箱内 HTTP 测试受网络限制的失败未计为通过，使用允许本机 HTTP 测试服务的执行重跑后才形成上述结果。

现有接口合同保持不变：`catalog.json` 的 `version: 1` 和每条 `category`；社区 `/community/feed?category=人像` 按分类返回并包含 `categories`。Android 不需访问数据库或使用服务密钥。

可独立重验（工作目录 `online-library`）：

```powershell
node scripts/reclassify-preset-categories.mjs --verify .local/category-reclassification-20261020-resumed
node scripts/verify-style-categories-public.mjs .local/category-reclassification-20261020-resumed
```

如明确需要恢复旧分类，可使用下列命令。它追加新的审计版本，不删历史；发现之后的人工修改会整批拒绝，不自动覆盖。未执行此恢复操作：

```powershell
node scripts/reclassify-preset-categories.mjs --rollback .local/category-reclassification-20261020-resumed
node scripts/reclassify-preset-categories.mjs --verify-rollback .local/category-reclassification-20261020-resumed
```

## 2026-10-21 接续核验

没有重复生产迁移或恢复。当前源码可完整复现 resumed 计划、apply／rollback SQL 及所有 241 份配方摘要；六份记录文件摘要仍与上表一致。匿名只读目录的 241 个 ID、分类和文件摘要全部匹配本计划，数量不变。本轮未重取六类 feed 或网络配方字节。

分类／计划测试 7／7、源码检查 56 模块通过。内存 PostgreSQL 测试补齐到 46 断言，验证恢复后完整旧历史保留、追加快照与数量正确、恢复幂等，以及后续人工草稿或已发布修改使整批恢复撤回且保留所有记录／历史。只在内存测试库执行，没有运行生产恢复。新证据在 `tests/.build/resume-audit-20261021/`，原生产验证文件未覆盖。
