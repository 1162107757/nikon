// Real PostgreSQL semantics in an isolated WASM database; never accesses CloudBase.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { PGlite } from '../../.community-pg-test/node_modules/@electric-sql/pglite/dist/index.js';
import { categoryMigrationSql, categoryRollbackSql, canonical } from '../scripts/reclassify-preset-categories.mjs';
import { ENV_ID } from '../scripts/cloud-tools.mjs';
import { STYLE_MODEL } from '../scripts/preset-style-classification.mjs';

const db = new PGlite();
let checks = 0;
try {
  await db.exec('CREATE ROLE anon; CREATE ROLE authenticated; CREATE ROLE service_role BYPASSRLS;');
  for (const file of ['001-library.sql', '004-preset-categories.sql']) await db.exec(await readFile(new URL('../database/' + file, import.meta.url), 'utf8'));
  async function fixture(name) {
    const hash = String(name.length).padStart(64, '0');
    const asset = { id: 'ncp-' + hash, kind: 'ncp', sha256: hash, byteLength: 638, ncpVersion: '0100',
      url: 'https://acceptance.invalid/file.ncp', source: { name: "kept ' source", tags: ['a', 'b'] } };
    await db.query('INSERT INTO nikon_assets(id,payload) VALUES ($1,$2) ON CONFLICT DO NOTHING', [asset.id, asset]);
    const draft = { name, description: "Author's description", compatibleModels: ['Z f'], category: '第三方合集', file: asset,
      preview: null, sortOrder: 42 };
    const saved = (await db.query('SELECT nikon_save_preset(NULL,NULL,$1) AS row', [draft])).rows[0].row;
    return (await db.query('SELECT nikon_publish_preset($1,1,true) AS row', [saved.id])).rows[0].row;
  }
  const first = await fixture("Bright '); DROP TABLE nikon_presets; --"), second = await fixture('Punchy');
  const changes = [{ id: first.id, before: first, category: '人像' }, { id: second.id, before: second, category: '风景' }];
  const plan = { environment: ENV_ID, model: STYLE_MODEL, changes };
  const originalVersions = (await db.query('SELECT * FROM nikon_preset_versions ORDER BY preset_id,revision')).rows;
  await db.exec(categoryMigrationSql(plan));
  const appliedVersions = (await db.query('SELECT * FROM nikon_preset_versions ORDER BY preset_id,revision')).rows;
  assert.equal(appliedVersions.length, originalVersions.length + changes.length); checks++;
  for (const original of originalVersions) {
    const preserved = appliedVersions.find(row => row.preset_id === original.preset_id && row.revision === original.revision);
    assert.deepEqual(preserved, original); checks++;
  }
  for (const item of changes) {
    const current = (await db.query('SELECT * FROM nikon_presets WHERE id=$1', [item.id])).rows[0];
    assert.deepEqual(canonical(current.published), canonical({ ...item.before.published, category: item.category })); checks++;
    assert.deepEqual(current.draft, current.published); checks++;
    assert.equal(current.revision, 2); checks++;
    assert.equal(current.published_at.toISOString(), new Date(item.before.published_at).toISOString()); checks++;
    const original = (await db.query('SELECT snapshot FROM nikon_preset_versions WHERE preset_id=$1 AND revision=1', [item.id])).rows[0].snapshot;
    assert.deepEqual(original, item.before.draft); checks++;
    const appended = appliedVersions.find(row => row.preset_id === item.id && row.revision === 2);
    assert.deepEqual(appended?.snapshot, { ...item.before.draft, category: item.category }); checks++;
  }
  const versionsBefore = (await db.query('SELECT count(*)::integer AS n FROM nikon_preset_versions')).rows[0].n;
  const rowsBefore = (await db.query('SELECT * FROM nikon_presets ORDER BY id')).rows;
  await db.exec(categoryMigrationSql(plan));
  assert.equal((await db.query('SELECT count(*)::integer AS n FROM nikon_preset_versions')).rows[0].n, versionsBefore); checks++;
  assert.deepEqual((await db.query('SELECT * FROM nikon_presets ORDER BY id')).rows, rowsBefore); checks++;
  await db.exec(categoryRollbackSql(plan));
  const restoredVersions = (await db.query('SELECT * FROM nikon_preset_versions ORDER BY preset_id,revision')).rows;
  assert.equal(restoredVersions.length, appliedVersions.length + changes.length); checks++;
  // Compare every old audit row, including its original timestamp, after restoration.
  for (const oldVersion of appliedVersions) {
    const preserved = restoredVersions.find(row => row.preset_id === oldVersion.preset_id && row.revision === oldVersion.revision);
    assert.deepEqual(preserved, oldVersion); checks++;
  }
  for (const item of changes) {
    const current = (await db.query('SELECT * FROM nikon_presets WHERE id=$1', [item.id])).rows[0];
    assert.deepEqual(canonical(current.published), canonical(item.before.published)); checks++;
    assert.equal(current.revision, 3); checks++;
    assert.equal(current.published_at.toISOString(), new Date(item.before.published_at).toISOString()); checks++;
    const appended = restoredVersions.find(row => row.preset_id === item.id && row.revision === 3);
    assert.deepEqual(appended?.snapshot, item.before.draft); checks++;
  }
  const restoredRows = (await db.query('SELECT * FROM nikon_presets ORDER BY id')).rows;
  await db.exec(categoryRollbackSql(plan));
  assert.deepEqual((await db.query('SELECT * FROM nikon_presets ORDER BY id')).rows, restoredRows); checks++;
  assert.deepEqual((await db.query('SELECT * FROM nikon_preset_versions ORDER BY preset_id,revision')).rows, restoredVersions); checks++;

  const third = await fixture('Fresh first'), fourth = await fixture('Changed second');
  await db.query('SELECT nikon_save_preset($1,1,$2)', [fourth.id, { ...fourth.draft, description: 'Concurrent editor has new content' }]);
  const conflictPlan = { environment: ENV_ID, model: STYLE_MODEL, changes: [
    { id: third.id, before: third, category: '胶片' }, { id: fourth.id, before: fourth, category: '人像' }] };
  await assert.rejects(db.exec(categoryMigrationSql(conflictPlan)), /CATEGORY_REVISION_CONFLICT/); checks++;
  assert.deepEqual((await db.query('SELECT published FROM nikon_presets WHERE id=$1', [third.id])).rows[0].published, third.published); checks++;
  assert.equal((await db.query('SELECT revision FROM nikon_presets WHERE id=$1', [third.id])).rows[0].revision, 1); checks++;
  assert.equal((await db.query('SELECT draft FROM nikon_presets WHERE id=$1', [fourth.id])).rows[0].draft.description, 'Concurrent editor has new content'); checks++;

  // The first item restores successfully inside the transaction, then the second
  // conflicts with a later manual edit. Both draft-only and published edits must
  // abort the whole restoration, including any audit entry made for the first.
  for (const manualStage of ['draft', 'published']) {
    const earlier = await fixture('Restore first ' + manualStage), later = await fixture('Restore edited ' + manualStage);
    const restorePlan = { environment: ENV_ID, model: STYLE_MODEL, changes: [
      { id: earlier.id, before: earlier, category: '风景' }, { id: later.id, before: later, category: '人像' }] };
    await db.exec(categoryMigrationSql(restorePlan));
    const manualDescription = 'Manual ' + manualStage + ' correction after classification';
    const manualDraft = { ...later.draft, category: '人像', description: manualDescription };
    const saved = (await db.query('SELECT nikon_save_preset($1,2,$2) AS row', [later.id, manualDraft])).rows[0].row;
    if (manualStage === 'published') await db.query('SELECT nikon_publish_preset($1,$2,true)', [later.id, saved.revision]);
    const beforeConflictRows = (await db.query('SELECT * FROM nikon_presets ORDER BY id')).rows;
    const beforeConflictVersions = (await db.query('SELECT * FROM nikon_preset_versions ORDER BY preset_id,revision')).rows;

    await assert.rejects(db.exec(categoryRollbackSql(restorePlan)), /CATEGORY_REVISION_CONFLICT/); checks++;
    assert.deepEqual((await db.query('SELECT * FROM nikon_presets ORDER BY id')).rows, beforeConflictRows); checks++;
    assert.deepEqual((await db.query('SELECT * FROM nikon_preset_versions ORDER BY preset_id,revision')).rows, beforeConflictVersions); checks++;
    const kept = (await db.query('SELECT draft,published FROM nikon_presets WHERE id=$1', [later.id])).rows[0];
    assert.deepEqual(kept.draft, manualDraft); checks++;
    assert.deepEqual(kept.published, manualStage === 'published' ? manualDraft : { ...later.published, category: '人像' }); checks++;
  }
  console.log(`Preset category PostgreSQL: ${checks} assertions passed (category-only changes, complete immutable history, append-only restoration, no-op rerun, SQL quoting and atomic apply/restore conflicts preserving manual drafts and publications).`);
} finally { await db.close(); }
