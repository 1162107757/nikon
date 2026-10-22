// Read-only checks for the deployed Lighthouse -> CloudBase proxy; no credentials.
import assert from 'node:assert/strict';
import { request } from 'node:https';
import { createHash } from 'node:crypto';
import { writeFile, mkdir } from 'node:fs/promises';

const origin = 'https://huevia.cc', prefix = '/preset-library';
const edge = 'lighthouse-118.24.164.117', checks = [];
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
function get(path, headers = {}) {
  return new Promise((resolve, reject) => {
    const req = request(origin + path, { headers, timeout: 45000 }, res => {
      const chunks = []; let size = 0;
      res.on('data', data => {
        size += data.length;
        if (size > 8 * 1024 * 1024) { res.destroy(new Error('Unexpected response size')); return; }
        chunks.push(data);
      });
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) }));
      res.on('error', reject);
    });
    req.on('timeout', () => req.destroy(new Error('Request timed out')));
    req.on('error', reject); req.end();
  });
}
function passed(name, details = {}) { checks.push({ name, passed: true, ...details }); }
function throughEdge(response) { assert.equal(response.headers['x-huevia-edge'], edge); }

let failure;
try {
  const root = await get('/'); throughEdge(root);
  assert.equal(root.status, 302); assert.equal(root.headers.location, prefix + '/admin/');
  passed('Root redirects within huevia.cc');

  for (const path of ['/admin/', '/admin/app.js', '/admin/styles.css', '/download/', '/health', '/catalog.json']) {
    const response = await get(prefix + path, { 'Sec-Fetch-Mode': 'navigate', 'User-Agent': 'Mozilla/5.0' });
    throughEdge(response); assert.equal(response.status, 200);
    if (path === '/admin/' || path === '/download/') {
      const html = response.body.toString('utf8');
      assert.match(html, /<!doctype html>/i); assert.doesNotMatch(html, /id=["']submitBtn["']/);
      assert.equal(response.headers['content-disposition'], undefined);
      if (path === '/download/') assert.ok(html.includes('蜀ICP备2026041231号-4'), 'Download page ICP footer is missing');
    }
    if (path === '/admin/app.js') assert.ok(response.body.toString('utf8').includes('蜀ICP备2026041231号-4'), 'Login ICP footer is missing');
    if (path === '/health') {
      const health = JSON.parse(response.body); assert.equal(health.ok, true);
      assert.equal(health.environmentId, 'nikon-d8girdjsr252fd90e');
    }
    if (path === '/catalog.json') {
      const catalog = JSON.parse(response.body); assert.equal(catalog.version, 1); assert.ok(catalog.presets.length > 0);
      passed('Public catalog is available', { presets: catalog.presets.length });
    }
    passed('Browser navigation reaches application through Lighthouse', { path, bytes: response.body.length });
  }

  const anonymous = await get(prefix + '/api/presets'); throughEdge(anonymous); assert.equal(anonymous.status, 401);
  passed('Unauthenticated administration remains denied');

  for (const packageName of ['cc.huevia', 'com.nikonpreset.usbprobe']) {
    const metadata = await get(prefix + '/releases/latest?channel=beta&packageName=' + encodeURIComponent(packageName));
    throughEdge(metadata); assert.equal(metadata.status, 200);
    const { release } = JSON.parse(metadata.body);
    if (!release) { passed('Release metadata is accessible', { packageName, release: null }); continue; }
    assert.match(release.id, /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/);
    const file = await get(`${prefix}/releases/${release.id}/file`); throughEdge(file); assert.equal(file.status, 200);
    assert.equal(file.body.length, release.byteLength); assert.equal(digest(file.body), release.sha256);
    passed('Published APK download retains its exact bytes', {
      packageName, versionCode: release.versionCode, bytes: file.body.length, sha256: release.sha256
    });
  }
} catch (error) { failure = error; }
const report = { origin, edge, verifiedAt: new Date().toISOString(), passed: !failure, checks,
  ...(failure ? { failure: { message: failure.message.slice(0, 500), code: failure.code } } : {}) };
await mkdir(new URL('../build/', import.meta.url), { recursive: true });
await writeFile(new URL('../build/lighthouse-verification.json', import.meta.url), JSON.stringify(report, null, 2) + '\n');
if (failure) throw failure;
console.log(`Lighthouse verification passed: ${checks.length} checks.`);
