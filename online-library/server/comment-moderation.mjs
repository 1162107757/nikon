// Conservative, explainable rules. A review result queues content; it is not a ban.
// This is not an AI/content-safety certification; ambiguous cases remain human decisions.
export function classifyComment(text) {
  const review = reason => ({ version: 'rules-v1', decision: 'review', reason: '自动审核：' + reason });
  if (typeof text !== 'string' || !text.trim() || [...text].length > 500) return review('未能完成检查，请人工核查');
  const normalized = text.normalize('NFKC').toLowerCase().replace(/[\u200b-\u200d\u2060\ufeff]/g, '');
  const compact = normalized.replace(/[\s\p{P}\p{S}]+/gu, '');
  if (/(?:https?:\/\/|hxxps?:\/\/|www\.|javascript:|data:|[a-z0-9-]+\.(?:com|cn|net|org|top|xyz)(?:\b|\/))/i.test(normalized)) return review('含外部链接，请核查是否引流');
  if (/(?:加我|添加|联系|咨询|私聊|进群|入群|扫码)/.test(compact) && /(?:微信|薇信|微讯|扣扣|qq|vx|v信|wechat|q群)/.test(compact)) return review('疑似联系方式或群聊引流');
  if (/(?:手机|电话|联系|加我)/.test(compact) && /(?:\d{7,})/.test(compact) || /^(?:86)?1[3-9]\d{9}$/.test(compact)) return review('疑似公开联系方式');
  if (/(?:返利|刷单|代刷|博彩|包赚|日赚|送彩金|出售账号|有偿推广|低价代购)/.test(compact)) return review('疑似推广或交易广告');
  if (/(?:傻逼|傻比|煞笔|操你妈|草泥马|狗日的|去死吧)/.test(compact)
      || /(?:你|他|她|楼主|作者).{0,5}(?:脑残|废物|畜生)/.test(compact)) return review('疑似辱骂或人身攻击');
  if (/(?:裸聊|色情网|约炮|出售枪支|贩卖毒品)/.test(compact)) return review('疑似不当或违法内容');
  if (/(.)\1{19,}/u.test(compact) || /(.{3,16})\1{5,}/u.test(compact)) return review('疑似重复刷屏');
  return { version: 'rules-v1', decision: 'allow', reason: '' };
}
