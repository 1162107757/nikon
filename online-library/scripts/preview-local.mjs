// Explicit local QA only. Not packaged or imported by production.
import { createServer } from 'node:http';
import { createHandler } from '../server/app.mjs';
import { MemoryStore, syntheticNcp } from '../test/memory-store.mjs';
import { hashPassword, sha256 } from '../server/auth.mjs';
import { validateNcp, validatePreview } from '../server/validation.mjs';
const port = Number(process.env.PORT || 8767), origin = `http://127.0.0.1:${port}`, prefix = '/preset-library';
const store = new MemoryStore(); store.publicAssetOrigin = 'https://local-qa.example.test';
store.admins.set('local-qa', { username: 'local-qa', password_hash: await hashPassword('Local-QA-Only-8246') });
const objects = new Map();
const storage = { put: async (path, data) => objects.set(path, Buffer.from(data)), get: async path => objects.get(path) };
const ncp = syntheticNcp(), ncpMeta = validateNcp(ncp);
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGOQ8KgAAAFUANnsm43hAAAAAElFTkSuQmCC', 'base64');
const imageMeta = validatePreview(png, 'image/png');
async function asset(kind, data, meta, extension) {
  const id = `${kind}-${meta.sha256}`, path = `${kind}/${meta.sha256}.${extension}`;
  objects.set(path, data);
  return store.addAsset({ id, kind, ...meta, storagePath: path, originalName: kind === 'ncp' ? 'LOCAL_FIXTURE.NCP' : 'LOCAL_COLOR.png',
    url: origin + prefix + '/files/' + id + '.' + extension, adminUrl: origin + prefix + '/api/assets/' + id + '/content' });
}
const file = await asset('ncp', ncp, ncpMeta, 'ncp'), preview = await asset('preview', png, imageMeta, 'png');
for (let index = 0; index < 3; index++) {
  const row = await store.savePreset(null, null, { name: ['本地联调 · 已上架样例', '本地联调 · 草稿样例', '本地联调 · 下架样例'][index],
    category: ['胶片', '风景', ''][index],
    description: '这是一份合成测试数据，不会上传 CloudBase，也不会作为真实预设发布。', compatibleModels: [], sortOrder: index, file, preview });
  if (index !== 1) await store.publish(row.id, 1, true);
  if (index === 2) await store.publish(row.id, 1, false);
}
// Local-only synthetic profiles exercise populated/disabled user layouts without registering QQ accounts.
for (let index = 0; index < 3; index++) {
  const user = await store.signInQq({ appId: '123456789', openid: (index + 1).toString(16).padStart(32, '0'),
    nickname: ['联调用户 · 林间', '联调用户 · 山海摄影', '联调用户 · 长昵称与账号状态检查'][index], avatarUrl: '' },
  sha256('local-only-user-' + index), new Date(Date.now() + 60000).toISOString());
  if (index === 2) await store.setUserStatus(user.id, 'disabled', user.updatedAt);
}
store.userSessions.clear();
const server = createServer(createHandler({ store, storage, environmentId: 'local-qa-only', publicBaseUrl: origin, secureCookies: false }));
server.listen(port, '127.0.0.1', () => console.log(`Local synthetic QA only: ${origin}${prefix}/admin/`));
