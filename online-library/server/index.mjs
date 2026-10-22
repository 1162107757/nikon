import { createServer } from 'node:http';
import { CloudBaseStore, CloudBaseStorage } from './cloudbase.mjs';
import { createHandler } from './app.mjs';
import { createQqVerifier } from './qq.mjs';

const environmentId = process.env.CLOUDBASE_ENV_ID || 'nikon-d8girdjsr252fd90e';
const publicBaseUrl = process.env.PUBLIC_BASE_URL || 'https://nikon-d8girdjsr252fd90e-1252602548.ap-shanghai.app.tcloudbase.com';
const store = new CloudBaseStore({ environmentId, serviceKey: process.env.CLOUDBASE_SERVICE_KEY, apiOrigin: process.env.CLOUDBASE_API_ORIGIN });
const storage = new CloudBaseStorage(store, process.env.CLOUDBASE_STORAGE_BUCKET || 'nikon-presets');
const handler = createHandler({ store, storage, environmentId, publicBaseUrl,
  allowedBrowserOrigins: process.env.ALLOWED_BROWSER_ORIGINS ? process.env.ALLOWED_BROWSER_ORIGINS.split(',').map(value => value.trim()) : [],
  basePath: process.env.BASE_PATH || '/preset-library', secureCookies: new URL(publicBaseUrl).protocol === 'https:',
  challengeSecret: process.env.CHALLENGE_SECRET, trustedProxyHops: Number(process.env.TRUSTED_PROXY_HOPS || 0),
  trustedProxyAddresses: process.env.TRUSTED_PROXY_ADDRESSES ? process.env.TRUSTED_PROXY_ADDRESSES.split(',').map(value=>value.trim()).filter(Boolean) : undefined,
  qqVerifier: createQqVerifier({ appId: process.env.QQ_APP_ID }) });
const server = createServer(handler);
server.headersTimeout = 15000;
server.requestTimeout = 45000;
server.keepAliveTimeout = 5000;
server.listen(Number(process.env.PORT || 9000), '0.0.0.0', () => {
  console.log(`Nikon preset library listening on port ${Number(process.env.PORT || 9000)} (${store.configured ? 'configured' : 'setup required'})`);
});
