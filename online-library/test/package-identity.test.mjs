import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { APK_PACKAGE, LEGACY_APK_PACKAGE, inspectApk } from '../server/apk.mjs';
import { createReleaseRoutes } from '../server/releases.mjs';
import { ReleaseMemoryStore } from './release-memory-store.mjs';

let apk;
try { apk = await readFile(new URL('../../dist/huevia-0.13.8-beta-debug.apk', import.meta.url)); } catch {}
test('current APK uses the Huevia install ID with the existing pinned signing certificate', { skip: !apk }, () => {
  const metadata = inspectApk(apk);
  assert.equal(metadata.packageName, APK_PACKAGE);
  assert.equal(metadata.versionCode, 76);
  assert.equal(metadata.versionName, '0.13.8-beta');
  assert.deepEqual(metadata.signatureSchemes, [2, 3]);
  assert.throws(() => inspectApk(apk, { packageName: LEGACY_APK_PACKAGE }), /包名/);
});

test('legacy and Huevia update queries preserve their own release and withdrawal boundaries', async t => {
  const store = new ReleaseMemoryStore();
  store.releaseRows = [
    { id: 'old', channel: 'beta', package_name: LEGACY_APK_PACKAGE, status: 'published', version_code: 74, min_sdk: 26 },
    { id: 'new', channel: 'beta', package_name: APK_PACKAGE, status: 'published', version_code: 75, min_sdk: 26 }
  ];
  const routes = createReleaseRoutes({ store, storage: {}, publicBaseUrl: 'https://huevia.cc' });
  const server = createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://localhost');
      await routes.publicRoute(req, res, { path: url.pathname, url, json(response, value) {
        response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify(value));
      }});
    } catch (error) { res.writeHead(error.status || 500); res.end(error.message); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.closeAllConnections(); server.close(); });
  const endpoint = `http://127.0.0.1:${server.address().port}/releases/latest?channel=beta`;
  const query = async suffix => (await fetch(endpoint + suffix)).json();
  assert.equal((await query('&versionCode=73')).release.id, 'old');
  assert.equal((await query('&packageName=cc.huevia&versionCode=74')).release.id, 'new');
  assert.equal((await query('&packageName=com.nikonpreset.usbprobe')).release.id, 'old');
  store.releaseRows[1].status = 'withdrawn';
  assert.equal((await query('&packageName=cc.huevia')).reason, 'withdrawn');
  assert.equal((await query('')).release.id, 'old');
  assert.equal((await fetch(endpoint + '&packageName=another.application')).status, 400);
});
