// One editorial category, not a compatibility or photographic-quality claim.
// Classification is opt-in through the reviewed migration tool, never implicit on catalog reads.
export const STYLE_MODEL = 'editorial-style-v2';
export const STYLE_CATEGORIES = ['人像', '风景', '胶片', '黑白', '日常', '夜景'];
const result = (category, reason, confidence) => ({ category, reason, confidence });

export const DEFAULT_STYLE_RULES = {
  // These imported labels describe provenance, not photographic use. All other existing categories are preserved.
  replaceCategories: ['', 'NikonPC', '云创创作者', '云创调色', '第三方合集'],
  thresholds: { brightLift: .055, gentleContrast: .88, darkLift: -.06, strongContrast: 1.25, strongChroma: 1.35,
    supportingContrast: 1.08, supportingChroma: 1.1, supportingDarkLift: -.015 },
  overrides: {}
};

export function normalizeStyleRules(input = {}) {
  const rules = { ...DEFAULT_STYLE_RULES, ...input, thresholds: { ...DEFAULT_STYLE_RULES.thresholds, ...input.thresholds } };
  if (!Array.isArray(rules.replaceCategories) || rules.replaceCategories.some(value => typeof value !== 'string')
      || !rules.overrides || typeof rules.overrides !== 'object' || Array.isArray(rules.overrides)
      || Object.values(rules.overrides).some(value => !STYLE_CATEGORIES.includes(value))
      || Object.entries(rules.thresholds).some(([key, value]) => !(key in DEFAULT_STYLE_RULES.thresholds) || !Number.isFinite(value) || value < -1 || value > 5))
    throw Error('Invalid editorial classification rules.');
  return rules;
}

export function classifyPresetStyle({ name = '', description = '', metrics = null, native = {} }, inputRules = {}) {
  const { thresholds: threshold } = normalizeStyleRules(inputRules);
  // Attribution links are not descriptions of the look (for example a URL containing "portrait").
  const text = (name + '\n' + description.replace(/https?:\/\/\S+/g, '')).normalize('NFKC').toLowerCase();
  if (/黑白|单色|monochrom|achromic|mono|b&w|b\s*&\s*w|film\s*bw|filmbw|(?:^|[ _-])bw(?:$|[ _-])|trix|tri-x|t-max|ilford|acros/.test(text)
      || native.monochrome === true || metrics?.supported === true && metrics.chromaRatio <= .08)
    return result('黑白', '名称或色彩参数明确为黑白／单色风格', 'high');
  if (/夜|霓虹|night|neon|bluehour|dimlight|at dusk|goldendusk|cinenig/.test(text))
    return result('夜景', '名称明确指向夜景、暮色或霓虹氛围', 'high');
  if (/人像|婚礼|portrait|wedding|warmtonedptt|vintageport/.test(text) || native.base === 'portrait')
    return result('人像', '名称或基础 Picture Control 明确面向人像', 'high');
  if (/风景|自然|森林|海岸|蓝天|landscape|nature|forest|coastal|bluesky|sunset|autumn/.test(text) || native.base === 'landscape')
    return result('风景', '名称或基础 Picture Control 明确面向自然与风景', 'high');
  if (/柔|浅|淡|粉|softglow|pastel|pale tale|tenderclear|painterlylight|hidamari|glowup/.test(text))
    return result('人像', '浅色、柔和或通透的命名线索，适合人物色调', 'medium');

  if (metrics?.supported === true && ['lift', 'contrast', 'chromaRatio'].every(key => Number.isFinite(metrics[key]))) {
    const { lift, contrast, chromaRatio } = metrics;
    if (lift >= threshold.brightLift && contrast <= 1.1 && chromaRatio <= 1.15 || contrast <= threshold.gentleContrast && chromaRatio <= 1.02 && lift >= -.025)
      return result('人像', '手机预览模型呈现较明亮、低反差或柔和色彩', 'medium');
    if (contrast >= threshold.strongContrast && (chromaRatio >= threshold.supportingChroma || lift <= threshold.supportingDarkLift)
        || chromaRatio >= threshold.strongChroma && (contrast >= threshold.supportingContrast || lift <= threshold.supportingDarkLift)
        || lift <= threshold.darkLift && contrast >= 1.02)
      return result('风景', '手机预览模型的深色调、反差或饱和度有至少两项一致依据', 'medium');
  }
  if (/胶片|复古|电影|film|vintage|cine|negative|nostal|retro|kodak|koda|kodachrome|(?:^|\W)gold\s*\d|portra|ektar|ektr|provia|velvia|astia|chrome|khrome|leica|lofi|oldschool|oldfilm|bleach|kchrome|^ag[u0-9]|^e100(?:$|\n)|^f400h(?:$|\n)|^fsol100(?:$|\n)|^kg\d|^kp\d|^l100(?:$|\n)|optic(o|ou)lo(u)?r/.test(text))
    return result('胶片', '名称包含胶片、电影、复古或胶片配方线索', 'medium');
  if (native.base === 'vivid') return result('风景', '基础 Picture Control 为鲜艳风格', 'medium');
  return result('日常', metrics?.supported ? '色调相对均衡，归入日常通用风格' : '缺少明确题材线索，暂归日常，保留人工调整空间', 'low');
}

export function readClassificationHints(bytes) {
  // Values here are metadata hints only. Rendering and format validation use production parsers.
  if (bytes.length >= 48 && bytes.readUInt32BE(4) === 1) {
    const base = { 0x0486: 'portrait', 0x04c7: 'landscape', 0x00c3: 'vivid' }[bytes.readUInt16BE(36)];
    return { base: base || null };
  }
  return {};
}
