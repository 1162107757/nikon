// Explicit live synthetic workflow. Never imported by production or the default test runner.
// Credentials remain in memory; only this run's marked records/objects may be removed.
import { createHash, randomUUID } from 'node:crypto';
import { readFile, writeFile, mkdir, rename } from 'node:fs/promises';
import { ENV_ID, PUBLIC_ORIGIN, executeSql, sqlRows, quoteSql } from './cloud-tools.mjs';
import { validateNcp, validatePreview } from '../server/validation.mjs';

const ROOT = new URL('../', import.meta.url);
const LOCAL = new URL('.local/', ROOT), BUILD = new URL('build/', ROOT);
const STATE_FILE = new URL('workflow-verification-state.json', LOCAL);
const REPORT_FILE = new URL('public-workflow-verification.json', BUILD);
const BASE = PUBLIC_ORIGIN + '/preset-library';
const API_ORIGIN = `https://${ENV_ID}.api.tcloudbasegateway.com`;
const BUCKET = 'nikon-presets';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const ANY_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const options = new Set(process.argv.slice(2));
if (options.has('--help')) {
  console.log('node scripts/verify-public-workflow.mjs [--cleanup-only]\nExplicit live synthetic upload/publish/download verification with owned-fixture cleanup. No credentials are printed.');
  process.exit(0);
}
if ([...options].some(option => option !== '--cleanup-only')) {
  console.error('Unsupported verification option. Use --help.'); process.exit(1);
}

class WorkflowError extends Error { }
const checks = [];
const publicFileResponses = [];
let state, fixtures, nativeProbe = null, cookie = '', csrfToken = '', stage = 'initialization';
const errors = [];
function ensure(condition, name) { if (!condition) throw new WorkflowError(name); }
function passed(name) { checks.push({ name, passed: true }); }
function check(condition, name) { ensure(condition, name); passed(name); }
function digest(data) { return createHash('sha256').update(data).digest('hex'); }
async function readJsonFile(path) { return JSON.parse(await readFile(path, 'utf8')); }
async function saveState() {
  await mkdir(LOCAL, { recursive: true });
  const temporary = new URL('workflow-verification-state.json.new', LOCAL);
  await writeFile(temporary, JSON.stringify(state, null, 2) + '\n', { mode: 0o600 });
  await rename(temporary, STATE_FILE);
}
async function writeReport(report) {
  await mkdir(BUILD, { recursive: true });
  const temporary = new URL('public-workflow-verification.json.new', BUILD);
  await writeFile(temporary, JSON.stringify(report, null, 2) + '\n'); await rename(temporary, REPORT_FILE);
}
async function startReport() {
  await writeReport({ startedAt: state.startedAt, finishedAt: null, passed: false,
    checks: [{ name: options.has('--cleanup-only') ? 'Cleanup-only recovery; this does not certify a complete workflow' : 'Workflow verification started; not yet passed', passed: false }],
    sha256: Object.fromEntries(fixtures.map(file => [file.kind, file.details.sha256])), cleanup: state.cleanup, nativeProbe, publicFileResponses });
}
function recordFailure(error, label) {
  const name = error instanceof WorkflowError ? error.message : `${label}: unexpected operation failure; sensitive details suppressed`;
  errors.push({ name, passed: false });
}

function ncpRecord(id, payload) {
  const header = Buffer.alloc(8); header.writeUInt32BE(id); header.writeUInt32BE(payload.length, 4);
  return Buffer.concat([header, payload]);
}
function pngChunk(type, payload) {
  const data = Buffer.alloc(payload.length + 12); data.writeUInt32BE(payload.length); data.write(type, 4); payload.copy(data, 8);
  let crc = 0xffffffff;
  for (const byte of data.subarray(4, data.length - 4)) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  data.writeUInt32BE((crc ^ 0xffffffff) >>> 0, data.length - 4); return data;
}
function makeFixtures(runId) {
  ensure(UUID.test(runId), 'Recovery marker is not a valid owned run identifier');
  const preset = Buffer.alloc(36); preset.write('0100');
  const ncp = Buffer.concat([Buffer.from([78, 67, 80, 0]), ncpRecord(1, preset),
    ncpRecord(0x7fff0001, Buffer.from(`NIKON-PUBLIC-WORKFLOW:${runId}`, 'ascii')), Buffer.alloc(4)]);
  const pixel = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGOQ8KgAAAFUANnsm43hAAAAAElFTkSuQmCC', 'base64');
  const png = Buffer.concat([pixel.subarray(0, -12), pngChunk('tEXt', Buffer.from(`Verification\0NIKON-PUBLIC-WORKFLOW:${runId}`, 'ascii')), pixel.subarray(-12)]);
  const ncpMetadata = validateNcp(ncp), pngMetadata = validatePreview(png, 'image/png');
  return [
    { kind: 'ncp', extension: 'ncp', mimeType: 'application/octet-stream', data: ncp, details: ncpMetadata },
    { kind: 'preview', extension: 'png', mimeType: 'image/png', data: png, details: pngMetadata },
  ].map(item => ({ ...item, id: `${item.kind}-${item.details.sha256}`,
    originalName: `nikon-verification-${runId}.${item.kind === 'ncp' ? 'NCP' : 'png'}`,
    storagePath: `${item.kind}/${item.details.sha256}.${item.extension}`,
    url: `${BASE}/files/${item.kind}-${item.details.sha256}.${item.extension}`,
    adminUrl: `${BASE}/api/assets/${item.kind}-${item.details.sha256}/content` }));
}
function assetMetadata(file) {
  return { id: file.id, kind: file.kind, ...file.details, originalName: file.originalName,
    storagePath: file.storagePath, url: file.url, adminUrl: file.adminUrl };
}
function initialState(runId) {
  const files = makeFixtures(runId);
  return { schemaVersion: 2, environmentId: ENV_ID, runId, startedAt: new Date().toISOString(),
    initialName: `上线合成验证·将清理·${runId}`, updatedName: `上线合成验证·将清理·已更新·${runId}`,
    initialCategory: '胶片', updatedCategory: '夜景',
    description: `临时合成验收数据，不用于真实相机导入；verification-run=${runId}`,
    assets: files.map(assetMetadata), uploadAttempted: [], createAttempted: false, presetId: null,
    databaseCleaned: false, objectsCleaned: [], workflowVerified: false, cleanupComplete: false,
    nativeProbe: null,
    cleanup: { presets: 0, versions: 0, assets: 0, objects: 0, missingObjects: 0 } };
}
function validateRecoveryState(value) {
  ensure([1, 2].includes(value?.schemaVersion) && value.environmentId === ENV_ID && UUID.test(value.runId), 'Recovery state does not belong to this environment and verifier');
  const expected = initialState(value.runId);
  ensure(value.initialName === expected.initialName && value.updatedName === expected.updatedName && value.description === expected.description,
    'Recovery marker metadata does not match this verifier');
  if (value.schemaVersion === 2) ensure(value.initialCategory === expected.initialCategory && value.updatedCategory === expected.updatedCategory,
    'Recovery category metadata does not match this verifier');
  ensure(JSON.stringify(value.assets) === JSON.stringify(expected.assets), 'Recovery asset IDs, hashes, paths or filenames do not match synthetic bytes');
  ensure(value.presetId === null || typeof value.presetId === 'string' && ANY_UUID.test(value.presetId), 'Recovery preset ID is invalid');
  const ids = new Set(expected.assets.map(asset => asset.id));
  ensure(Array.isArray(value.uploadAttempted) && value.uploadAttempted.every(id => ids.has(id))
    && Array.isArray(value.objectsCleaned) && value.objectsCleaned.every(id => ids.has(id)), 'Recovery contains an unowned object identifier');
  return value;
}
function nativeMetadata(value) {
  ensure(Number.isSafeInteger(value?.checks) && value.checks > 0 && value.checks < 10000
    && value.runtime === 'host JDK 17 with production Android Java sources' && value.imageDecoding === 'not exercised',
  'Native interoperability probe did not return its expected non-sensitive result');
  return { checks: value.checks, runtime: value.runtime, imageDecoding: value.imageDecoding };
}

async function request(path, { method = 'GET', body, raw = false, anonymous = false, headers = {} } = {}) {
  const mutating = !['GET', 'HEAD'].includes(method);
  return fetch(BASE + path, { method, redirect: 'manual', signal: AbortSignal.timeout(45000),
    headers: { Accept: 'application/json', 'Accept-Encoding': 'identity',
      ...(!anonymous && cookie ? { Cookie: cookie } : {}),
      ...(mutating ? { Origin: PUBLIC_ORIGIN, 'Content-Type': raw ? 'application/octet-stream' : 'application/json', 'X-CSRF-Token': csrfToken } : {}),
      ...headers }, body: body === undefined ? undefined : raw ? body : JSON.stringify(body) });
}
async function jsonResponse(response, expectedStatus, name) {
  check(response.status === expectedStatus, `${name}: expected HTTP status`);
  return response.json();
}
async function manifestItem() {
  const response = await request('/catalog.json', { anonymous: true });
  ensure(response.status === 200, 'Anonymous catalog request failed');
  const manifest = await response.json();
  ensure(manifest?.version === 1 && Array.isArray(manifest.presets), 'Public catalog is not native-compatible');
  const matching = manifest.presets.filter(item => item.id === state.presetId);
  ensure(matching.length <= 1, 'Public catalog contains duplicate verification IDs');
  return matching[0] ?? null;
}
function expectedDraft(updated = false) {
  return { name: updated ? state.updatedName : state.initialName, description: state.description,
    ...(state.schemaVersion === 2 ? { category: updated ? state.updatedCategory : state.initialCategory } : {}),
    compatibleModels: ['SYNTHETIC-VALIDATION'], sortOrder: updated ? 1 : 0,
    fileAssetId: fixtures[0].id, previewAssetId: fixtures[1].id };
}
function verifyCatalogItem(item, updated = false) {
  const metadata = expectedDraft(updated), file = fixtures[0], preview = fixtures[1];
  ensure(item !== null && item.name === metadata.name && item.description === metadata.description
    && item.category === (metadata.category ?? '')
    && item.fileUrl === file.url && item.previewUrl === preview.url && item.sha256 === file.details.sha256
    && item.byteLength === file.data.length && JSON.stringify(item.compatibleModels) === JSON.stringify(metadata.compatibleModels)
    && Object.keys(item).sort().join(',') === 'byteLength,category,compatibleModels,description,fileUrl,id,name,previewUrl,sha256',
  'Published catalog fields do not match the explicit owned snapshot');
}
async function boundedBytes(response, maximum, name) {
  const chunks = []; let length = 0;
  for await (const chunk of response.body) {
    length += chunk.length; ensure(length <= maximum, `${name}: response exceeds owned fixture size`); chunks.push(chunk);
  }
  return Buffer.concat(chunks, length);
}
async function verifyAsset(file, { publicRead = true, head = false } = {}) {
  const path = publicRead ? `/files/${file.id}.${file.extension}` : `/api/assets/${file.id}/content`;
  const response = await request(path, { method: head ? 'HEAD' : 'GET', anonymous: publicRead, headers: { Accept: file.mimeType } });
  const label = `${publicRead ? 'anonymous published' : 'authenticated private'} ${file.kind} ${head ? 'HEAD' : 'GET'}`;
  if (publicRead) publicFileResponses.push({ kind: file.kind, method: head ? 'HEAD' : 'GET', status: response.status,
    cacheControl: response.headers.get('cache-control') });
  if (head) {
    ensure(publicRead, 'The unsupported HEAD probe must target the public file route');
    check(response.status === 405, `${label}: unsupported method returns HTTP 405`);
    check(response.headers.get('allow')?.trim() === 'GET', `${label}: Allow advertises GET only`);
    check((await response.arrayBuffer()).byteLength === 0, `${label}: unsupported HEAD has no response body`);
    return;
  }
  check(response.status === 200, `${label}: HTTP 200`);
  const contentLength = response.headers.get('content-length');
  check(contentLength === String(file.data.length), `${label}: required entity length matches (${contentLength ?? 'omitted'})`);
  check(response.headers.get('content-type')?.split(';')[0].trim().toLowerCase() === file.mimeType, `${label}: correct MIME`);
  check(response.headers.get('etag') === `"${file.details.sha256}"`, `${label}: exact SHA-256 ETag`);
  // Public cache policy is observed, not a content-integrity requirement: gateways may tighten it.
  if (!publicRead) check(/\bno-store\b/i.test(response.headers.get('cache-control') || ''), `${label}: private response is not cached publicly`);
  const data = await boundedBytes(response, file.data.length, label);
  check(data.length === file.data.length && digest(data) === file.details.sha256 && data.equals(file.data), `${label}: complete synthetic bytes and SHA-256`);
}

async function runWorkflow() {
  stage = 'administrator login';
  const initial = await readJsonFile(new URL('initial-admin.json', LOCAL));
  ensure(typeof initial.username === 'string' && typeof initial.password === 'string', 'Initial administrator handoff is unavailable');
  const login = await request('/api/login', { method: 'POST', body: { username: initial.username, password: initial.password } });
  ensure(login.status === 200, 'Administrator login failed');
  const header = login.headers.get('set-cookie') || '', token = header.match(/(?:^|,\s*)__Host-nikon_session=([A-Za-z0-9_-]{43})(?:;|$)/)?.[1];
  ensure(Boolean(token), 'Administrator session Cookie is missing');
  cookie = `__Host-nikon_session=${token}`; // Retain only in memory before any attribute assertions can fail.
  check(/(?:^|;)\s*Path=\/(?:;|$)/i.test(header) && /(?:^|;)\s*HttpOnly(?:;|$)/i.test(header)
    && /(?:^|;)\s*Secure(?:;|$)/i.test(header) && /(?:^|;)\s*SameSite=Strict(?:;|$)/i.test(header)
    && !/(?:^|;)\s*Domain=/i.test(header), 'Real session Cookie has required host-only security attributes');
  const session = await login.json();
  ensure(session.authenticated === true && /^[A-Za-z0-9_-]{43}$/.test(session.csrfToken || ''), 'Administrator session or CSRF token is invalid');
  csrfToken = session.csrfToken;
  const confirmed = await jsonResponse(await request('/api/session'), 200, 'Cookie-backed session lookup');
  check(confirmed.authenticated === true && confirmed.csrfToken === csrfToken, 'Real Cookie is accepted by the persistent session store');

  stage = 'raw synthetic uploads';
  for (const file of fixtures) {
    state.uploadAttempted.push(file.id); await saveState();
    const response = await request(`/api/uploads?kind=${file.kind}&filename=${encodeURIComponent(file.originalName)}`, {
      method: 'POST', raw: true, body: file.data, headers: { 'Content-Type': file.mimeType } });
    const uploaded = await jsonResponse(response, 201, `${file.kind} raw upload`);
    const expected = assetMetadata(file);
    check(uploaded.asset !== null && typeof uploaded.asset === 'object' && Object.entries(expected).every(([key, value]) => uploaded.asset[key] === value),
      `${file.kind} upload metadata matches owned hash, filename and immutable paths`);
    const anonymous = await request(`/files/${file.id}.${file.extension}`, { anonymous: true });
    check(anonymous.status === 404, `Unpublished ${file.kind} file is not anonymously readable`);
    const direct = await fetch(`${API_ORIGIN}/v1/storages/object/public/${BUCKET}/${file.storagePath}`, {
      redirect: 'manual', signal: AbortSignal.timeout(30000), headers: { 'Accept-Encoding': 'identity' } });
    check([400, 401, 403, 404].includes(direct.status), `${file.kind} private bucket does not expose a direct anonymous object URL`);
    await verifyAsset(file, { publicRead: false });
  }

  stage = 'create synthetic draft';
  state.createAttempted = true; await saveState();
  const created = await jsonResponse(await request('/api/presets', { method: 'POST', body: expectedDraft() }), 201, 'Create marked verification draft');
  ensure(ANY_UUID.test(created.preset?.id || ''), 'Created verification preset has an invalid ID');
  state.presetId = created.preset.id; await saveState();
  check(created.preset.status === 'draft' && created.preset.revision === 1, 'New preset remains an unpublished revision-one draft');
  check(created.preset.category === state.initialCategory, 'The initial category is stored in the owned draft');
  check(await manifestItem() === null, 'Draft is absent from the anonymous catalog');
  for (const file of fixtures) check((await request(`/files/${file.id}.${file.extension}`, { anonymous: true })).status === 404, `Draft ${file.kind} URL stays private before explicit publication`);

  stage = 'first explicit publication';
  const first = await jsonResponse(await request(`/api/presets/${state.presetId}/publish`, { method: 'POST', body: { expectedRevision: 1 } }), 200, 'Explicit first publication');
  check(first.preset.status === 'published' && first.preset.publishedRevision === 1, 'Publication captures revision one');
  verifyCatalogItem(await manifestItem()); passed('Anonymous catalog exposes only the explicit first snapshot');
  for (const file of fixtures) { await verifyAsset(file); await verifyAsset(file, { head: true }); }

  stage = 'real Android catalog interoperability';
  const { verifyAndroidCatalog } = await import('./verify-android-catalog.mjs');
  const android = await verifyAndroidCatalog({ catalogUrl: BASE + '/catalog.json', presetId: state.presetId,
    expectedSha256: fixtures[0].details.sha256, expectedPreviewSha256: fixtures[1].details.sha256 });
  nativeProbe = nativeMetadata(android); state.nativeProbe = nativeProbe; await saveState();
  passed('Production Java repository and HTTPS downloads interoperate; Android Bitmap decoding is not exercised');

  stage = 'published snapshot isolation';
  const edited = await jsonResponse(await request(`/api/presets/${state.presetId}`, { method: 'PUT', body: { ...expectedDraft(true), expectedRevision: 1 } }), 200, 'Save metadata revision two');
  check(edited.preset.revision === 2 && edited.preset.hasUnpublishedChanges === true, 'Metadata edit creates an unpublished revision two');
  check(edited.preset.category === state.updatedCategory && (await manifestItem()).category === state.initialCategory,
    'Draft category changes stay private until explicit publication');
  verifyCatalogItem(await manifestItem()); passed('Saving revision two does not change the public snapshot');
  const stale = await request(`/api/presets/${state.presetId}/publish`, { method: 'POST', body: { expectedRevision: 1 } });
  check(stale.status === 409, 'Stale publication is rejected through the real HTTP and database path');
  await jsonResponse(await request(`/api/presets/${state.presetId}/publish`, { method: 'POST', body: { expectedRevision: 2 } }), 200, 'Explicit revision-two publication');
  verifyCatalogItem(await manifestItem(), true); passed('Explicit republication switches the public metadata snapshot');
  const history = await jsonResponse(await request(`/api/presets/${state.presetId}/versions`), 200, 'Persistent version history');
  check(Array.isArray(history.items) && history.items.length === 2
    && history.items.some(item => item.revision === 1 && item.name === state.initialName && item.isPublished === false)
    && history.items.some(item => item.revision === 2 && item.name === state.updatedName && item.isPublished === true), 'Both draft versions survive and the current published revision is identified');
  check(history.items.some(item => item.revision === 1 && item.category === state.initialCategory)
    && history.items.some(item => item.revision === 2 && item.category === state.updatedCategory), 'Historical category snapshots remain unchanged after republication');

  stage = 'explicit unpublication';
  const unpublished = await jsonResponse(await request(`/api/presets/${state.presetId}/unpublish`, { method: 'POST', body: { expectedRevision: 2 } }), 200, 'Explicit unpublication');
  check(unpublished.preset.status === 'unpublished', 'Unpublication changes preset visibility');
  check(await manifestItem() === null, 'Unpublished verification preset leaves the anonymous catalog');
  for (const file of fixtures) await verifyAsset(file);
  state.workflowVerified = true; await saveState();
}

function listSql(values) { return `ARRAY[${values.map(quoteSql).join(',')}]::text[]`; }
function refs(snapshot, ids, hashes, paths) {
  return `(${snapshot}->'file'->>'id'=ANY(${ids}) OR ${snapshot}->'preview'->>'id'=ANY(${ids})
    OR ${snapshot}->'file'->>'sha256'=ANY(${hashes}) OR ${snapshot}->'preview'->>'sha256'=ANY(${hashes})
    OR ${snapshot}->'file'->>'storagePath'=ANY(${paths}) OR ${snapshot}->'preview'->>'storagePath'=ANY(${paths}))`;
}
function sqlScope() {
  const ids = listSql(fixtures.map(file => file.id)), hashes = listSql(fixtures.map(file => file.details.sha256)), paths = listSql(fixtures.map(file => file.storagePath));
  const own = state.presetId ? `p.id::text=${quoteSql(state.presetId)}` : 'false';
  const candidates = `(${own} OR p.draft->>'description'=${quoteSql(state.description)} OR ${refs('p.draft', ids, hashes, paths)} OR ${refs('p.published', ids, hashes, paths)})`;
  return { ids, hashes, paths, candidates };
}
function oneJson(sql, field) {
  const rows = sqlRows(executeSql(sql));
  ensure(rows.length === 1, 'Cleanup control-plane query did not return a single bounded result');
  const value = rows[0][field]; return typeof value === 'string' ? JSON.parse(value) : value;
}
function snapshotOwnership() {
  const { ids, hashes, paths, candidates } = sqlScope();
  return oneJson(`SELECT jsonb_build_object(
    'assets',coalesce((SELECT jsonb_agg(jsonb_build_object('id',a.id,'payload',a.payload)) FROM public.nikon_assets a WHERE a.id=ANY(${ids})),'[]'::jsonb),
    'presets',coalesce((SELECT jsonb_agg(jsonb_build_object('id',p.id,'revision',p.revision,'status',p.status,'draft',p.draft,'published',p.published)) FROM public.nikon_presets p WHERE ${candidates}),'[]'::jsonb),
    'versions',coalesce((SELECT jsonb_agg(jsonb_build_object('preset_id',v.preset_id,'revision',v.revision,'snapshot',v.snapshot)) FROM public.nikon_preset_versions v
      WHERE v.preset_id IN(SELECT p.id FROM public.nikon_presets p WHERE ${candidates}) ${state.presetId ? `OR v.preset_id::text=${quoteSql(state.presetId)}` : ''}),'[]'::jsonb),
    'foreignAssets',(SELECT count(*) FROM public.nikon_assets a WHERE NOT(a.id=ANY(${ids})) AND (a.payload->>'sha256'=ANY(${hashes}) OR a.payload->>'storagePath'=ANY(${paths})))
  ) AS ownership;`, 'ownership');
}
function validAsset(payload, expected) {
  return payload !== null && typeof payload === 'object' && Object.entries(expected).every(([key, value]) => payload[key] === value);
}
function validDraft(draft, revision) {
  return draft !== null && typeof draft === 'object' && draft.name === (revision === 1 ? state.initialName : state.updatedName)
    && (state.schemaVersion === 2 ? draft.category === (revision === 1 ? state.initialCategory : state.updatedCategory) : draft.category === undefined || draft.category === '')
    && draft.description === state.description && JSON.stringify(draft.compatibleModels) === JSON.stringify(['SYNTHETIC-VALIDATION'])
    && draft.sortOrder === (revision === 1 ? 0 : 1) && validAsset(draft.file, assetMetadata(fixtures[0])) && validAsset(draft.preview, assetMetadata(fixtures[1]));
}
function verifyOwnership(ownership) {
  ensure(ownership && Array.isArray(ownership.assets) && Array.isArray(ownership.presets) && Array.isArray(ownership.versions)
    && Number(ownership.foreignAssets) === 0, 'Cleanup refused: unexpected asset identity or shared content reference');
  ensure(ownership.assets.length <= 2 && ownership.presets.length <= 1 && ownership.versions.length <= 2, 'Cleanup refused: shared or ambiguous verification records');
  for (const row of ownership.assets) {
    const file = fixtures.find(file => file.id === row.id);
    ensure(Boolean(file) && validAsset(row.payload, assetMetadata(file)), 'Cleanup refused: asset hash, filename or ownership marker changed');
  }
  for (const row of ownership.presets) {
    ensure(state.createAttempted === true && ANY_UUID.test(row.id) && (!state.presetId || row.id === state.presetId)
      && [1, 2].includes(row.revision) && ['draft', 'published', 'unpublished'].includes(row.status)
      && validDraft(row.draft, row.revision) && (row.published === null || validDraft(row.published, 1) || validDraft(row.published, 2)),
    'Cleanup refused: preset is not the exact marked verification draft');
    state.presetId = row.id;
  }
  for (const row of ownership.versions) ensure(row.preset_id === state.presetId && [1, 2].includes(row.revision) && validDraft(row.snapshot, row.revision),
    'Cleanup refused: version does not belong to this verification run');
}
function draftSql(expression) {
  const first = { ...expectedDraft(), file: assetMetadata(fixtures[0]), preview: assetMetadata(fixtures[1]) };
  const second = { ...expectedDraft(true), file: assetMetadata(fixtures[0]), preview: assetMetadata(fixtures[1]) };
  delete first.fileAssetId; delete first.previewAssetId; delete second.fileAssetId; delete second.previewAssetId;
  // Version-one recovery may predate categories or have been saved by the new service with ''.
  const variants = state.schemaVersion === 1 ? [first, second, { ...first, category: '' }, { ...second, category: '' }] : [first, second];
  return `(${variants.map(draft => `${expression}=${quoteSql(JSON.stringify(draft))}::jsonb`).join(' OR ')})`;
}
function assertNoReferences() {
  const { ids, hashes, paths } = sqlScope();
  const counts = oneJson(`SELECT jsonb_build_object(
    'assets',(SELECT count(*) FROM public.nikon_assets a WHERE a.id=ANY(${ids}) OR a.payload->>'sha256'=ANY(${hashes}) OR a.payload->>'storagePath'=ANY(${paths})),
    'presets',(SELECT count(*) FROM public.nikon_presets p WHERE ${refs('p.draft', ids, hashes, paths)} OR ${refs('p.published', ids, hashes, paths)} ${state.presetId ? `OR p.id::text=${quoteSql(state.presetId)}` : ''}),
    'versions',(SELECT count(*) FROM public.nikon_preset_versions v WHERE ${refs('v.snapshot', ids, hashes, paths)} ${state.presetId ? `OR v.preset_id::text=${quoteSql(state.presetId)}` : ''})
  ) AS counts;`, 'counts');
  ensure(counts && ['assets', 'presets', 'versions'].every(key => Number(counts[key]) === 0), 'Cleanup refused: database records or shared references still exist');
}
async function cleanupDatabase() {
  const ownership = snapshotOwnership(); verifyOwnership(ownership); await saveState();
  if (state.databaseCleaned) {
    ensure(!ownership.assets.length && !ownership.presets.length && !ownership.versions.length, 'Cleanup refused: records reappeared after a completed database cleanup');
    assertNoReferences(); return;
  }
  const { ids, hashes, paths } = sqlScope(), preset = state.presetId ? `${quoteSql(state.presetId)}::uuid` : 'NULL::uuid';
  const assetConditions = fixtures.map(file => `(a.id=${quoteSql(file.id)} AND a.payload=${quoteSql(JSON.stringify(assetMetadata(file)))}::jsonb)`).join(' OR ');
  executeSql(`DO $workflow_cleanup$
  DECLARE removed integer;
  BEGIN
    PERFORM 1 FROM public.nikon_assets a WHERE a.id=ANY(${ids}) FOR UPDATE;
    PERFORM 1 FROM public.nikon_presets p WHERE p.id=${preset} FOR UPDATE;
    PERFORM 1 FROM public.nikon_preset_versions v WHERE v.preset_id=${preset} FOR UPDATE;
    IF EXISTS(SELECT 1 FROM public.nikon_assets a WHERE a.id=ANY(${ids}) AND NOT(${assetConditions})) THEN RAISE EXCEPTION 'OWNED_ASSET_CHANGED'; END IF;
    IF EXISTS(SELECT 1 FROM public.nikon_presets p WHERE p.id=${preset} AND NOT(coalesce(${draftSql('p.draft')},false)
      AND (p.published IS NULL OR coalesce(${draftSql('p.published')},false)))) THEN RAISE EXCEPTION 'OWNED_PRESET_CHANGED'; END IF;
    IF EXISTS(SELECT 1 FROM public.nikon_preset_versions v WHERE v.preset_id=${preset} AND NOT(coalesce(${draftSql('v.snapshot')},false))) THEN RAISE EXCEPTION 'OWNED_VERSION_CHANGED'; END IF;
    IF EXISTS(SELECT 1 FROM public.nikon_assets a WHERE NOT(a.id=ANY(${ids})) AND (a.payload->>'sha256'=ANY(${hashes}) OR a.payload->>'storagePath'=ANY(${paths}))) THEN RAISE EXCEPTION 'SHARED_ASSET'; END IF;
    IF EXISTS(SELECT 1 FROM public.nikon_presets p WHERE p.id IS DISTINCT FROM ${preset}
      AND (${refs('p.draft', ids, hashes, paths)} OR ${refs('p.published', ids, hashes, paths)})) THEN RAISE EXCEPTION 'SHARED_PRESET_REFERENCE'; END IF;
    IF EXISTS(SELECT 1 FROM public.nikon_preset_versions v WHERE v.preset_id IS DISTINCT FROM ${preset}
      AND ${refs('v.snapshot', ids, hashes, paths)}) THEN RAISE EXCEPTION 'SHARED_VERSION_REFERENCE'; END IF;
    DELETE FROM public.nikon_preset_versions v WHERE v.preset_id=${preset} AND ${draftSql('v.snapshot')};
    GET DIAGNOSTICS removed=ROW_COUNT; IF removed<>${ownership.versions.length} THEN RAISE EXCEPTION 'VERSION_COUNT_CHANGED'; END IF;
    DELETE FROM public.nikon_presets p WHERE p.id=${preset} AND ${draftSql('p.draft')};
    GET DIAGNOSTICS removed=ROW_COUNT; IF removed<>${ownership.presets.length} THEN RAISE EXCEPTION 'PRESET_COUNT_CHANGED'; END IF;
    DELETE FROM public.nikon_assets a WHERE ${assetConditions};
    GET DIAGNOSTICS removed=ROW_COUNT; IF removed<>${ownership.assets.length} THEN RAISE EXCEPTION 'ASSET_COUNT_CHANGED'; END IF;
  END;
  $workflow_cleanup$;`);
  assertNoReferences();
  state.cleanup.presets += ownership.presets.length; state.cleanup.versions += ownership.versions.length; state.cleanup.assets += ownership.assets.length;
  state.databaseCleaned = true; await saveState(); passed('Exact owned database records removed atomically; shared references absent');
}
async function objectResponse(file, serviceKey, method) {
  ensure(fixtures.some(owned => owned.id === file.id && owned.storagePath === file.storagePath), 'Object cleanup path is not owned by this verifier');
  const path = file.storagePath.split('/').map(encodeURIComponent).join('/');
  const endpoint = method === 'GET' ? `/v1/storages/object/authenticated/${BUCKET}/${path}` : `/v1/storages/object/${BUCKET}/${path}`;
  return fetch(API_ORIGIN + endpoint, { method, redirect: 'error', headers: { Authorization: `Bearer ${serviceKey}`, 'Accept-Encoding': 'identity' }, signal: AbortSignal.timeout(30000) });
}
async function cleanupObjects() {
  if (!state.uploadAttempted.length) return;
  const credential = await readJsonFile(new URL('service-credential.json', LOCAL));
  ensure(credential.environmentId === ENV_ID && typeof credential.serviceKey === 'string' && credential.serviceKey.length > 0, 'Object cleanup credential is unavailable for the exact environment');
  for (const file of fixtures.filter(file => state.uploadAttempted.includes(file.id))) {
    assertNoReferences();
    const current = await objectResponse(file, credential.serviceKey, 'GET');
    if (state.objectsCleaned.includes(file.id)) {
      ensure(current.status === 404, 'Cleanup refused: private object reappeared after confirmed removal'); continue;
    }
    if (current.status === 404) {
      state.cleanup.missingObjects++; state.objectsCleaned.push(file.id); await saveState(); continue;
    }
    ensure(current.status === 200, 'Private synthetic object could not be verified before cleanup');
    const data = await boundedBytes(current, file.data.length, 'Private object cleanup verification');
    ensure(data.length === file.data.length && digest(data) === file.details.sha256 && data.equals(file.data), 'Cleanup refused: private object is not the exact marked synthetic content');
    assertNoReferences(); // Recheck immediately before the single, exact object deletion.
    const removed = await objectResponse(file, credential.serviceKey, 'DELETE');
    ensure(removed.status === 200 || removed.status === 204, 'Exact private synthetic object deletion was not confirmed');
    const after = await objectResponse(file, credential.serviceKey, 'GET');
    ensure(after.status === 404, 'Private synthetic object remains after cleanup');
    state.cleanup.objects++; state.objectsCleaned.push(file.id); await saveState();
  }
  passed('Only byte-verified owned private objects removed; authenticated reads confirm absence');
}
async function cleanupOwned() {
  stage = 'owned fixture cleanup';
  validateRecoveryState(state); await saveState();
  await cleanupDatabase(); await cleanupObjects(); assertNoReferences();
  state.cleanupComplete = true; await saveState(); passed('All owned fixture records and attempted private objects are cleared');
}
async function logoutAndReplay() {
  if (!cookie) return;
  stage = 'session logout and replay';
  if (!csrfToken) {
    const session = await request('/api/session');
    ensure(session.status === 200, 'Session recovery before logout failed');
    const body = await session.json(); ensure(body.authenticated === true && /^[A-Za-z0-9_-]{43}$/.test(body.csrfToken || ''), 'Session recovery did not provide a valid logout token'); csrfToken = body.csrfToken;
  }
  const logout = await request('/api/logout', { method: 'POST', body: {} });
  check(logout.status === 200, 'Verification session logged out through the live database path');
  const replay = await request('/api/presets');
  check(replay.status === 401, 'Replaying the former real Cookie cannot read management data');
  const session = await jsonResponse(await request('/api/session'), 200, 'Former Cookie session lookup');
  check(session.authenticated === false && session.csrfToken === '', 'Persistent session revocation is confirmed with the former Cookie');
  cookie = ''; csrfToken = '';
}

try {
  await mkdir(BUILD, { recursive: true });
  if (options.has('--cleanup-only')) {
    state = validateRecoveryState(await readJsonFile(STATE_FILE)); fixtures = makeFixtures(state.runId);
    nativeProbe = state.nativeProbe ? nativeMetadata(state.nativeProbe) : null; await startReport();
  } else {
    let prior;
    try { prior = await readJsonFile(STATE_FILE); } catch (error) { if (error.code !== 'ENOENT') throw error; }
    ensure(!prior || prior.cleanupComplete === true, 'An unfinished verification state exists; review it and use --cleanup-only before starting another run');
    state = initialState(randomUUID()); fixtures = makeFixtures(state.runId); await saveState(); await startReport();
    await runWorkflow();
  }
} catch (error) { recordFailure(error, stage); }
finally {
  if (state && fixtures) {
    try { await cleanupOwned(); } catch (error) { recordFailure(error, 'owned fixture cleanup'); }
  }
  try { await logoutAndReplay(); } catch (error) { recordFailure(error, 'session logout and replay'); }
  const complete = errors.length === 0 && state?.cleanupComplete === true && (options.has('--cleanup-only') || state?.workflowVerified === true);
  const report = { startedAt: state?.startedAt ?? new Date().toISOString(), finishedAt: new Date().toISOString(), passed: complete && !options.has('--cleanup-only'),
    checks: [...(options.has('--cleanup-only') ? [{ name: 'Cleanup-only recovery; a complete public workflow is not certified by this run', passed: false }] : []), ...checks, ...errors], sha256: fixtures ? Object.fromEntries(fixtures.map(file => [file.kind, file.details.sha256])) : {},
    cleanup: state?.cleanup ?? { presets: 0, versions: 0, assets: 0, objects: 0, missingObjects: 0 }, nativeProbe, publicFileResponses };
  try {
    await writeReport(report);
  } catch { errors.push({ name: 'Non-sensitive verification report could not be saved', passed: false }); }
  if (complete && errors.length === 0) console.log(`${options.has('--cleanup-only') ? 'Owned-fixture cleanup' : 'Public synthetic workflow'} passed: ${checks.length} checks; ${state.cleanup.presets} presets, ${state.cleanup.versions} versions, ${state.cleanup.assets} assets and ${state.cleanup.objects} private objects removed.`);
  else { console.error('Verification incomplete. Inspect the non-sensitive report and ignored recovery state; do not treat this run as passed.'); process.exitCode = 1; }
}
