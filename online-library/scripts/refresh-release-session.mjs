// Use the existing, authorized local administrator credential once; never print secrets.
import {readFile,writeFile} from 'node:fs/promises';
import {PUBLIC_ORIGIN} from './cloud-tools.mjs';
if(process.argv[2]!=='--login')throw Error('Pass --login to refresh the existing release administrator session.');
const initial=JSON.parse(await readFile(new URL('../.local/initial-admin.json',import.meta.url),'utf8'));
const response=await fetch(PUBLIC_ORIGIN+'/preset-library/api/login',{method:'POST',signal:AbortSignal.timeout(45000),headers:{Origin:PUBLIC_ORIGIN,'Content-Type':'application/json'},body:JSON.stringify({username:initial.username,password:initial.password})});
if(!response.ok)throw Error('Administrator login was not accepted (HTTP '+response.status+'); existing credentials were not changed.');
const value=await response.json(),match=response.headers.get('set-cookie')?.match(/__Host-nikon_session=([A-Za-z0-9_-]{43})/);
if(!match||!/[A-Za-z0-9_-]{43}/.test(value.csrfToken||''))throw Error('Administrator session response invalid.');
await writeFile(new URL('../.local/community-admin-session.json',import.meta.url),JSON.stringify({cookie:'__Host-nikon_session='+match[1],csrfToken:value.csrfToken},null,2),{mode:0o600});
console.log('Existing release administrator session refreshed; no credentials printed.');
