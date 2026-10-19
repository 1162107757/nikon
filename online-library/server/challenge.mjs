import { randomBytes, randomUUID } from 'node:crypto';
import { deflateSync } from 'node:zlib';
import { ServiceError } from './cloudbase.mjs';
import { createAbuseContext } from './abuse.mjs';

// Fixed raster glyphs; response contains PNG pixels only, with no answer metadata or vector text.
const glyphs = {
  A:['01110','10001','10001','11111','10001','10001','10001'], B:['11110','10001','10001','11110','10001','10001','11110'],
  C:['01111','10000','10000','10000','10000','10000','01111'], D:['11110','10001','10001','10001','10001','10001','11110'],
  E:['11111','10000','10000','11110','10000','10000','11111'], F:['11111','10000','10000','11110','10000','10000','10000'],
  G:['01111','10000','10000','10111','10001','10001','01110'], H:['10001','10001','10001','11111','10001','10001','10001'],
  J:['00111','00010','00010','00010','10010','10010','01100'], K:['10001','10010','10100','11000','10100','10010','10001'],
  M:['10001','11011','10101','10101','10001','10001','10001'], N:['10001','11001','10101','10011','10001','10001','10001'],
  P:['11110','10001','10001','11110','10000','10000','10000'], R:['11110','10001','10001','11110','10100','10010','10001'],
  S:['01111','10000','10000','01110','00001','00001','11110'], T:['11111','00100','00100','00100','00100','00100','00100'],
  U:['10001','10001','10001','10001','10001','10001','01110'], V:['10001','10001','10001','10001','10001','01010','00100'],
  W:['10001','10001','10001','10101','10101','11011','10001'], X:['10001','10001','01010','00100','01010','10001','10001'],
  Y:['10001','10001','01010','00100','00100','00100','00100'], Z:['11111','00001','00010','00100','01000','10000','11111'],
  2:['01110','10001','00001','00010','00100','01000','11111'], 3:['11110','00001','00001','01110','00001','00001','11110'],
  4:['00010','00110','01010','10010','11111','00010','00010'], 5:['11111','10000','10000','11110','00001','00001','11110'],
  6:['01110','10000','10000','11110','10001','10001','01110'], 7:['11111','00001','00010','00100','01000','01000','01000'],
  8:['01110','10001','10001','01110','10001','10001','01110'], 9:['01110','10001','10001','01111','00001','00001','01110']
};
function crc(data) { let n = 0xffffffff; for (const b of data) { n ^= b; for (let i=0;i<8;i++) n=(n>>>1)^((n&1)?0xedb88320:0); } return (n ^ 0xffffffff) >>> 0; }
function chunk(type, data) { const name=Buffer.from(type), size=Buffer.alloc(4), sum=Buffer.alloc(4); size.writeUInt32BE(data.length); sum.writeUInt32BE(crc(Buffer.concat([name,data]))); return Buffer.concat([size,name,data,sum]); }
export function rasterChallenge(answer) {
  const width=180,height=62, raw=Buffer.alloc(height*(1+width*3),245), entropy=randomBytes(2000); let cursor=0;
  const rand = n => entropy[cursor++ % entropy.length] % n;
  for(let y=0;y<height;y++)raw[y*(width*3+1)]=0;
  function dot(x,y,c){if(x<0||y<0||x>=width||y>=height)return; const at=y*(width*3+1)+1+x*3; raw[at]=c[0];raw[at+1]=c[1];raw[at+2]=c[2];}
  for(let i=0;i<220;i++)dot(rand(width),rand(height),[100+rand(130),100+rand(130),100+rand(130)]);
  [...answer].forEach((letter,index)=>{const glyph=glyphs[letter],ox=10+index*33+rand(5),oy=10+rand(10),color=[25+rand(65),30+rand(65),35+rand(65)],skew=rand(3)-1;
    glyph.forEach((row,y)=>[...row].forEach((bit,x)=>{if(bit==='1')for(let a=0;a<4;a++)for(let b=0;b<4;b++)dot(ox+x*4+a+Math.round((y-3)*skew/2),oy+y*5+b,color);}));});
  const ihdr=Buffer.alloc(13);ihdr.writeUInt32BE(width);ihdr.writeUInt32BE(height,4);ihdr[8]=8;ihdr[9]=2;
  return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',ihdr),chunk('IDAT',deflateSync(raw)),chunk('IEND',Buffer.alloc(0))]);
}
export function createChallengeService(options) {
  const {store,provider}=options,abuse=createAbuseContext(options);
  return { ...abuse, async issue(req, installationId) {
    const values=abuse.context(req,installationId), id=randomUUID(), expiresAt=new Date(Date.now()+120000).toISOString();
    if(provider){await abuse.protect(req,installationId,'challenge-issue');return provider.issue(req,{...values,id,expiresAt});}
    const alphabet=Object.keys(glyphs), random=randomBytes(5), answer=[...random].map(n=>alphabet[n%alphabet.length]).join('');
    const answerHash=abuse.hash(`challenge:${id}:${answer}`);
    if(!await store.issueChallenge({id,answerHash,...values,expiresAt}))throw new ServiceError(429,'CHALLENGE_THROTTLED','验证请求较多，请稍后再试。');
    return {id,image:`data:image/png;base64,${rasterChallenge(answer).toString('base64')}`,expiresAt};
  }, async consume(req,body,purpose='register') {
    const {challengeId,challengeAnswer,installationId}=body;
    if(typeof challengeId!=='string'||!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(challengeId)||typeof challengeAnswer!=='string'||!String(installationId||'').match(/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i))throw new ServiceError(428,'CHALLENGE_REQUIRED','请完成图形验证后重试。');
    const values=abuse.context(req,installationId);
    if(provider){await abuse.protect(req,installationId,'challenge-consume');return provider.consume(req,body,{...values,purpose});}
    const answerHash=abuse.hash(`challenge:${challengeId.toLowerCase()}:${challengeAnswer.trim().toUpperCase().slice(0,32)}`);
    if(!await store.consumeChallenge({id:challengeId.toLowerCase(),answerHash,...values,purpose}))throw new ServiceError(400,'CHALLENGE_INVALID','验证码错误、已使用或已过期，请刷新验证码。');
    return true;
  }};
}
