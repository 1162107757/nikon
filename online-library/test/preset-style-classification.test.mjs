import test from 'node:test';
import assert from 'node:assert/strict';
import { classifyPresetStyle, STYLE_CATEGORIES, normalizeStyleRules, STYLE_MODEL } from '../scripts/preset-style-classification.mjs';

test('explicit monochrome, night and portrait subjects beat general film labels', () => {
  assert.equal(classifyPresetStyle({ name: 'MGA BW Portrait FC' }).category, '黑白');
  assert.equal(classifyPresetStyle({ name: 'EmotionalB&W_Wazika' }).category, '黑白');
  assert.equal(classifyPresetStyle({ name: 'BritFilmBW_Vincent' }).category, '黑白');
  assert.equal(classifyPresetStyle({ name: 'MNightBlush_Preston' }).category, '夜景');
  assert.equal(classifyPresetStyle({ name: 'WarmPortrait_Y2' }).category, '人像');
  assert.equal(classifyPresetStyle({ name: 'SunsetFilm_JKBoy' }).category, '风景');
  assert.equal(classifyPresetStyle({ name: 'Pastelpink_Yuki' }).category, '人像');
});
test('measured bright gentle styles and dark punchy styles follow the requested grouping', () => {
  const classify = metrics => classifyPresetStyle({ name: 'Style', metrics: { supported: true, ...metrics } }).category;
  assert.equal(classify({ lift: .06, contrast: .94, chromaRatio: .98 }), '人像');
  assert.equal(classify({ lift: -.06, contrast: 1.1, chromaRatio: 1.1 }), '风景');
  assert.equal(classify({ lift: 0, contrast: 1.3, chromaRatio: 1.1 }), '风景');
  assert.equal(classify({ lift: 0, contrast: 1.08, chromaRatio: 1.4 }), '风景');
  assert.equal(classify({ lift: 0, contrast: 1, chromaRatio: 1 }), '日常');
  assert.equal(classify({ lift: NaN, contrast: Infinity, chromaRatio: 1.22 }), '日常');
});
test('v2 does not infer landscape from high saturation or high contrast alone', () => {
  assert.equal(STYLE_MODEL, 'editorial-style-v2');
  const classify = metrics => classifyPresetStyle({ name: 'Style', metrics: { supported: true, ...metrics } }).category;
  assert.equal(classify({ lift: 0, contrast: 1, chromaRatio: 1.4 }), '日常');
  assert.equal(classify({ lift: 0, contrast: 1.3, chromaRatio: 1 }), '日常');
  assert.equal(classify({ lift: -.02, contrast: 1, chromaRatio: 1.4 }), '风景');
  assert.equal(classify({ lift: -.02, contrast: 1.3, chromaRatio: 1 }), '风景');
  assert.equal(classifyPresetStyle({ name: 'Kodak Gold 100', metrics: { supported: true, lift: .02, contrast: 1.1, chromaRatio: 1.5 } }).category, '风景');
  assert.equal(classifyPresetStyle({ name: 'Kodak Gold 100', metrics: { supported: true, lift: .02, contrast: 1, chromaRatio: 1.5 } }).category, '胶片');
});
test('semantic intent can use descriptions but attribution URLs never become a subject', () => {
  assert.equal(classifyPresetStyle({ name: 'Untitled', description: '适合人像和柔和肤色' }).category, '人像');
  assert.equal(classifyPresetStyle({ name: 'Untitled', description: '来源：https://example.invalid/portrait/monochrome' }).category, '日常');
  assert.equal(classifyPresetStyle({ name: 'GoldenHourGlow_DAnn' }).category, '日常', 'golden light is not a Kodak Gold stock name');
});
test('rule configuration rejects unknown or non-finite thresholds and invalid category overrides', () => {
  assert.equal(normalizeStyleRules({ thresholds: { strongChroma: 1.5 } }).thresholds.strongContrast, 1.25);
  for (const rules of [{ thresholds: { invented: 1 } }, { thresholds: { darkLift: NaN } }, { replaceCategories: ['NikonPC', 1] }, { overrides: { id: '全部' } }])
    assert.throws(() => normalizeStyleRules(rules));
});
test('unsupported colour models never fabricate measurements and source directories are not categories', () => {
  assert.equal(classifyPresetStyle({ name: 'Unknown', metrics: { supported: false, lift: .2, contrast: .5, chromaRatio: .5 } }).category, '日常');
  assert.equal(classifyPresetStyle({ name: 'Kodak Gold 100' }).category, '胶片');
  assert.equal(classifyPresetStyle({ name: 'Kodachrome' }).category, '胶片', 'achromic matching must not capture Kodachrome');
  assert.equal(classifyPresetStyle({ name: 'Unknown', native: { base: 'portrait' } }).category, '人像');
  for (const name of ['ＡｉｒGreen', 'Unknown', 'Premium Vivid Norm', 'Kodak Gold 100']) assert.ok(STYLE_CATEGORIES.includes(classifyPresetStyle({ name }).category));
});
