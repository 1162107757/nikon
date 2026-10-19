// Guest GETs only: no accounts, comments, messages or other production mutations.
import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';
import {PUBLIC_ORIGIN,ENV_ID} from './cloud-tools.mjs';
const base=PUBLIC_ORIGIN+'/preset-library',checks=[];
async function get(path){const response=await fetch(base+path,{signal:AbortSignal.timeout(45000),headers:{Accept:'application/json'}});assert.equal(response.status,200);checks.push(path);return response.json();}
const health=await get('/health');assert.equal(health.ok,true);assert.equal(health.environmentId,ENV_ID);
const catalog=await get('/catalog.json');assert.ok(Array.isArray(catalog.presets));
const feed=await get('/community/feed?page=1&pageSize=1');assert.ok(Array.isArray(feed.items));
let roots=0,replies=0;
if(feed.items.length){
 const comments=await get('/community/presets/'+feed.items[0].id+'/comments?page=1&pageSize=20');assert.ok(Array.isArray(comments.items));
 for(const root of comments.items){roots++;assert.equal(root.replyTo,null);for(const reply of root.replies||[]){replies++;assert.equal(typeof reply.replyTo?.id,'string');assert.equal(typeof reply.replyTo?.available,'boolean');}}
}
await writeFile(new URL('../build/comment-reply-live.json',import.meta.url),JSON.stringify({verifiedAt:new Date().toISOString(),environment:ENV_ID,passed:true,checks,roots,replies,productionMutations:false},null,2));
console.log(JSON.stringify({passed:true,guestChecks:checks.length,roots,replies,productionMutations:false}));
