import test from 'node:test';import assert from 'node:assert/strict';import {classifyComment} from '../server/comment-moderation.mjs';
test('normal photography comments including criticism and QQ support are allowed',()=>{
 for(const text of ['这个预设拍人像肤色自然','尼康 Z6II，ISO 1600，1/125秒，f/2.8','这个预设不好看，暗部太死黑了','QQ登录失败了，能帮忙看看吗？','哈哈哈，拍得太好了！','能不能支持RAW和NP3？'])assert.equal(classifyComment(text).decision,'allow',text);
});
test('contact obfuscation, links, ads, strong insults and flooding queue for human review',()=>{
 for(const text of ['加我微信abc123','加我ＱＱ１２３４５６','联系微\u200b信abc123','看看 https://spam.invalid/a','www.example.com','返利包赚，日赚一千','你就是傻逼','你是个脑残','裸聊请联系','联系手机13800138000','哈哈'.repeat(40)]){
  const result=classifyComment(text);assert.equal(result.decision,'review',text);assert.match(result.reason,/^自动审核：/);assert.equal(result.version,'rules-v1');
 }
});
test('invalid input defaults to human review and source text is never rewritten',()=>{
 for(const value of [null,{},'', 'x'.repeat(501)])assert.equal(classifyComment(value).decision,'review');
 const source='加我ＱＱ１２３４５６';classifyComment(source);assert.equal(source,'加我ＱＱ１２３４５６');
});
