// Reviewed, category-only migration of the curated online library. No schema or credential changes.
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { ENV_ID, executeSql, quoteSql, sqlRows, PUBLIC_ORIGIN } from './cloud-tools.mjs';
import { STYLE_MODEL, STYLE_CATEGORIES, classifyPresetStyle, readClassificationHints, normalizeStyleRules } from './preset-style-classification.mjs';

export function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
  return value;
}

export function validateReviewedPlan(plan, backup) {
  const stable = value => JSON.stringify(canonical(value));
  if (plan.environment !== ENV_ID || backup.environment !== ENV_ID || plan.model !== STYLE_MODEL
      || !Array.isArray(backup.rows) || !Array.isArray(plan.assignments) || !Array.isArray(plan.changes)
      || plan.total !== backup.rows.length || plan.assignments.length !== backup.rows.length)
    throw Error('Reviewed plan does not match the backed-up library.');
  const expected = new Map(backup.rows.map(row => [row.id, row]));
  const seen = new Set();
  for (const item of plan.assignments) {
    const before = expected.get(item.id);
    if (seen.has(item.id) || !before || stable(item.before) !== stable(before)
        || item.sha256 !== before.published?.file?.sha256 || item.oldCategory !== (before.published?.category || '')
        || typeof item.category !== 'string' || item.oldCategory !== item.category && !STYLE_CATEGORIES.includes(item.category))
      throw Error('Reviewed assignment does not match its original preset.');
    seen.add(item.id);
  }
  if (stable(plan.changes) !== stable(plan.assignments.filter(item => item.oldCategory !== item.category)))
    throw Error('Reviewed change list differs from its assignments.');
}

function categorySql(plan, rollback = false) {
  if (plan.environment !== ENV_ID || plan.model !== STYLE_MODEL || !Array.isArray(plan.changes)) throw Error('Unexpected migration target.');
  for (const change of plan.changes) {
    if (!/^[a-f0-9-]{36}$/.test(change.id) || !STYLE_CATEGORIES.includes(change.category)
        || change.before.id !== change.id || change.before.status !== 'published' || !Number.isInteger(Number(change.before.revision))
        || JSON.stringify(canonical(change.before.draft)) !== JSON.stringify(canonical(change.before.published))) throw Error('Unsafe migration row.');
  }
  const payload = plan.changes.map(item => {
    if (!rollback) return { id: item.id, category: item.category, before: item.before };
    const originalCategory = item.before.draft.category || '';
    if (typeof originalCategory !== 'string' || Array.from(originalCategory).length > 24 || /[\x00-\x1f\x7f-\x9f]/.test(originalCategory)) throw Error('Unsafe restoration category.');
    const expected = { ...item.before.draft, category: item.category };
    return { id: item.id, category: originalCategory,
      before: { ...item.before, draft: expected, published: expected, revision: Number(item.before.revision) + 1,
        published_revision: Number(item.before.revision) + 1 } };
  });
  return `DO $category_migration$
DECLARE entry jsonb; old_row jsonb; current_row nikon_presets%ROWTYPE; new_draft jsonb; saved jsonb; manifest jsonb;
BEGIN
  PERFORM pg_advisory_xact_lock(1852403310, 1);
  FOR entry IN SELECT value FROM jsonb_array_elements(${quoteSql(JSON.stringify(payload))}::jsonb) LOOP
    old_row := entry->'before';
    SELECT * INTO current_row FROM nikon_presets WHERE id=(entry->>'id')::uuid FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'CATEGORY_TARGET_MISSING'; END IF;
    new_draft := jsonb_set(old_row->'draft','{category}',entry->'category',true);
    -- Re-running a fully applied plan is a no-op, including version history and publication dates.
    IF current_row.revision=(old_row->>'revision')::integer+1 AND current_row.status='published'
      AND current_row.draft=new_draft AND current_row.published=new_draft
      AND current_row.published_revision=current_row.revision THEN CONTINUE; END IF;
    IF current_row.revision<>(old_row->>'revision')::integer OR current_row.status<>old_row->>'status'
      OR current_row.draft IS DISTINCT FROM old_row->'draft' OR current_row.published IS DISTINCT FROM old_row->'published'
      OR current_row.published_revision IS DISTINCT FROM (old_row->>'published_revision')::integer
      THEN RAISE EXCEPTION 'CATEGORY_REVISION_CONFLICT'; END IF;
    -- Existing save/publish transactions append the new version; historical snapshots stay intact.
    saved := nikon_save_preset(current_row.id,current_row.revision,new_draft);
    PERFORM nikon_publish_preset(current_row.id,(saved->>'revision')::integer,true);
    -- Classification is an editorial update; retain the original feed publication order.
    UPDATE nikon_presets SET published_at=current_row.published_at WHERE id=current_row.id;
  END LOOP;
  SELECT nikon_catalog_manifest() INTO manifest;
  IF jsonb_array_length(manifest->'presets')>256 OR octet_length(convert_to(manifest::text,'UTF8'))>524288
    THEN RAISE EXCEPTION 'CATEGORY_CATALOG_LIMIT'; END IF;
END $category_migration$;`;
}

export const categoryMigrationSql = plan => categorySql(plan);
// Restoration appends a new audited version. It never deletes presets, history, or another editor's later work.
export const categoryRollbackSql = plan => categorySql(plan, true);

export async function createPlan(directory, inputRules = {}) {
  const rules = normalizeStyleRules(inputRules);
  const beforeText = await readFile(resolve(directory, 'before.json'), 'utf8');
  const backup = JSON.parse(beforeText);
  if (backup.environment !== ENV_ID || backup.rows.some(row => row.status !== 'published' || JSON.stringify(row.draft) !== JSON.stringify(row.published)))
    throw Error('Review unpublished or changed drafts before reclassification.');
  const measurements = (await readFile(resolve(directory, 'measurements.jsonl'), 'utf8')).trim().split(/\r?\n/).map(line => JSON.parse(line));
  const byHash = new Map(measurements.map(metric => [metric.sha256, metric]));
  const assignments = [];
  for (const row of backup.rows) {
    const sha256 = row.published.file.sha256, metrics = byHash.get(sha256);
    if (!metrics) throw Error('A preset has no measured or explicitly unsupported model result.');
    const format = row.published.file.fileFormat || 'NCP';
    const bytes = await readFile(resolve(directory, 'files', sha256 + (format === 'NP3' ? '.np3' : '.ncp')));
    if (createHash('sha256').update(bytes).digest('hex') !== sha256) throw Error('Classification file changed.');
    const suggested = classifyPresetStyle({ name: row.published.name, description: row.published.description || '', metrics, native: readClassificationHints(bytes) }, rules);
    const oldCategory = row.published.category || '';
    const classification = rules.overrides[row.id]
      ? { category: rules.overrides[row.id], reason: '人工覆盖规则指定；保留自动建议供复核', confidence: 'reviewed-override' }
      : !rules.replaceCategories.includes(oldCategory)
        ? { category: oldCategory, reason: '保留已有题材或手工分类；自动建议不覆盖', confidence: 'preserved' }
        : suggested;
    assignments.push({ id: row.id, name: row.published.name, sha256, oldCategory: row.published.category || '',
      ...classification, suggested, metrics, before: row });
  }
  if (Object.keys(rules.overrides).some(id => !assignments.some(row => row.id === id))) throw Error('An override does not match a backed-up preset.');
  const countBy = key => Object.fromEntries([...new Set(assignments.map(row => row[key] || '未分类'))].sort().map(value => [value, assignments.filter(row => (row[key] || '未分类') === value).length]));
  const plan = { version: 1, environment: ENV_ID, model: STYLE_MODEL, createdAt: new Date().toISOString(),
    backupSha256: createHash('sha256').update(beforeText).digest('hex'), total: assignments.length,
    measured: measurements.filter(metric => metric.supported).length, beforeCounts: countBy('oldCategory'), afterCounts: countBy('category'),
    rules, preservedManual: assignments.filter(row => row.confidence === 'preserved').length,
    assignments, changes: assignments.filter(row => row.oldCategory !== row.category) };
  await writeFile(resolve(directory, 'plan.json'), JSON.stringify(plan, null, 2), { mode: 0o600 });
  const md = ['# 在线预设色彩分类预览', '', `环境：${ENV_ID}。共 ${plan.total} 份，拟更改 ${plan.changes.length} 份；其中 ${plan.measured} 份由当前手机预览模型测量。`, '',
    '分类属于便于挑选预设的编辑整理，不代表只能用于该题材，也不保证相机原生色彩。保留全部预设文件、名称、简介、作者来源、兼容信息与历史版本。', '',
    `保留 ${plan.preservedManual} 份已有题材／手工分类。仅替换规则中列出的来源标签；风景指标判断需要至少两项一致依据。未模拟成功的条目只使用明确名称／元数据线索，否则低信心归入日常。当前来源说明不等于样片人工确认。`, '',
    '| 预设 | 原分类 | 新分类 | 依据 |', '| --- | --- | --- | --- |',
    ...assignments.map(row => `| ${row.name.replaceAll('|', '\\|')} | ${row.oldCategory || '未分类'} | ${row.category} | ${row.reason} |`)];
  await writeFile(resolve(directory, 'preview.md'), md.join('\n') + '\n', { mode: 0o600 });
  return plan;
}

async function main() {
  const mode = process.argv[2], directory = resolve(process.argv[3] || '.local/category-reclassification-20261020');
  if (mode === '--plan') {
    const rulesPath = process.argv[4];
    const plan = await createPlan(directory, rulesPath ? JSON.parse(await readFile(resolve(rulesPath), 'utf8')) : {});
    console.log(JSON.stringify({ environment: plan.environment, total: plan.total, changes: plan.changes.length, measured: plan.measured,
      before: plan.beforeCounts, after: plan.afterCounts, preview: resolve(directory, 'preview.md') }));
    return;
  }
  if (!['--apply', '--verify', '--export', '--rollback', '--verify-rollback'].includes(mode)) throw Error('Usage: reclassify-preset-categories.mjs --plan <backup-directory> [rules.json] | --export|--apply|--verify|--rollback|--verify-rollback <reviewed-backup-directory>');
  const plan = JSON.parse(await readFile(resolve(directory, 'plan.json'), 'utf8'));
  const before = await readFile(resolve(directory, 'before.json'), 'utf8');
  if (createHash('sha256').update(before).digest('hex') !== plan.backupSha256) throw Error('Reviewed backup no longer matches the plan.');
  validateReviewedPlan(plan, JSON.parse(before));
  const sql = categoryMigrationSql(plan); // Validate plan also for verification.
  if (mode === '--export') {
    for (const [file, content] of [['apply.sql', sql], ['rollback.sql', categoryRollbackSql(plan)]])
      await writeFile(resolve(directory, file), content + '\n', { mode: 0o600 });
    console.log(JSON.stringify({ exported: ['apply.sql', 'rollback.sql'], directory, networkAccessed: false }));
    return;
  }
  if (mode === '--apply') executeSql(sql);
  const rollback = mode === '--rollback' || mode === '--verify-rollback';
  if (mode === '--rollback') executeSql(categoryRollbackSql(plan));
  const current = sqlRows(executeSql('SELECT id::text,revision,draft,published,published_revision,status,published_at::text FROM nikon_presets ORDER BY id'));
  for (const row of current) for (const field of ['draft', 'published']) if (typeof row[field] === 'string') row[field] = JSON.parse(row[field]);
  if (current.length !== plan.total) throw Error('Preset count changed since classification.');
  const response = await fetch(PUBLIC_ORIGIN + '/preset-library/catalog.json', { signal: AbortSignal.timeout(45000) });
  if (!response.ok) throw Error('Public category verification unavailable.');
  const catalog = await response.json();
  for (const item of plan.assignments) {
    const row = current.find(row => row.id === item.id), publicRow = catalog.presets?.find(row => row.id === item.id);
    const expectedCategory = rollback ? item.oldCategory : item.category;
    const expected = { ...item.before.published, category: expectedCategory };
    // jsonb's key order is canonical but comparison across JavaScript object construction needs normalization.
    const stable = value => JSON.stringify(canonical(value));
    if (!row || row.status !== item.before.status || row.published_at !== item.before.published_at || row.published?.category !== expectedCategory || row.draft?.category !== expectedCategory
      || stable(row.draft) !== stable(expected) || stable(row.published) !== stable(expected)
      || Number(row.revision) !== Number(item.before.revision) + (item.oldCategory === item.category ? 0 : rollback ? 2 : 1)
      || (publicRow?.category || '') !== expectedCategory || publicRow?.sha256 !== item.sha256) throw Error('Category-only verification failed for ' + item.name);
  }
  // Verify all historical versions, not just their count.
  const versions = sqlRows(executeSql('SELECT preset_id::text,revision,snapshot FROM nikon_preset_versions ORDER BY preset_id,revision'));
  const oldVersions = JSON.parse(before).versions;
  for (const old of oldVersions) {
    const saved = versions.find(row => row.preset_id === old.preset_id && Number(row.revision) === Number(old.revision));
    if (saved && typeof saved.snapshot === 'string') saved.snapshot = JSON.parse(saved.snapshot);
    if (!saved || JSON.stringify(canonical(saved.snapshot)) !== JSON.stringify(canonical(old.snapshot))) throw Error('Historical version changed.');
  }
  if (versions.length !== oldVersions.length + plan.changes.length * (rollback ? 2 : 1)) throw Error('Unexpected historical version count.');
  const report = { environment: ENV_ID, verifiedAt: new Date().toISOString(), total: current.length,
    changes: plan.changes.length, restored: rollback, categories: rollback ? plan.beforeCounts : plan.afterCounts, historicalVersionsPreserved: oldVersions.length, categoryOnly: true,
    publicCatalogVerified: true, filesUnchanged: true };
  await writeFile(resolve(directory, rollback ? 'rollback-verification.json' : 'verification.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
  console.log(JSON.stringify(report));
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
