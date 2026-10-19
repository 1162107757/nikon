import test from 'node:test';
import assert from 'node:assert/strict';
import { validateCategory, validatePresetInput, buildCatalog, ValidationError } from '../server/validation.mjs';
import { normalizeCategory, categoryLabel, categoryNames, categoryOptions, categoryMatches, SUGGESTED_CATEGORIES } from '../admin/categories.js';
import { presetView } from '../server/app.mjs';

for (const [label, normalize] of [['server', validateCategory], ['admin', normalizeCategory]]) {
  test(`${label}: categories are optional NFC text with a 24 code-point limit`, () => {
    for (const empty of [undefined, null, '', '  ', '\u3000\ufeff']) assert.equal(normalize(empty), '');
    assert.equal(normalize('  风景\u3000'), '风景');
    assert.equal(normalize('Cafe\u0301'), 'Café');
    assert.equal(normalize('e\u0301'.repeat(24)), 'é'.repeat(24));
    assert.equal(normalize('🌅'.repeat(24)), '🌅'.repeat(24));
    assert.equal(normalize('__proto__'), '__proto__');
    assert.equal(normalize('category:all'), 'category:all');
    for (const value of ['字'.repeat(25), '🌅'.repeat(25), 1, [], {}, false, '全部', ' 未分类 ', '\ud800', '\udc00']) {
      assert.throws(() => normalize(value));
    }
  });
  test(`${label}: all raw C0/C1 controls are rejected even when trim would remove them`, () => {
    for (let code = 0; code <= 0x9f; code++) {
      if (code > 0x1f && code < 0x7f) continue;
      for (const value of [`${String.fromCodePoint(code)}胶片`, `胶${String.fromCodePoint(code)}片`, `胶片${String.fromCodePoint(code)}`]) {
        assert.throws(() => normalize(value));
      }
    }
    assert.equal(normalize('a\u200bb'), 'a\u200bb', 'format characters are not silently conflated with controls');
  });
}

test('category validation participates in preset metadata and uses the established error shape', () => {
  assert.equal(validatePresetInput({ name: 'Tone', category: ' 夜景 ' }).category, '夜景');
  assert.throws(() => validatePresetInput({ name: 'Tone', category: '全部' }), error => error instanceof ValidationError && error.status === 400 && error.code === 'invalid_preset');
  assert.equal(presetView({ id: 'old', draft: { name: 'Old' }, revision: 1 }).category, '');
  assert.equal(presetView({ id: 'new', draft: { name: 'New', category: '胶片' }, revision: 1 }).category, '胶片');
});

test('admin category filters do not confuse uncategorized, all, free-text, or disappeared categories', () => {
  const presets = [{ id: 'old' }, { category: '' }, { category: '胶片' }, { category: '人像' }, { category: '胶片' }, { category: 'all' }, { category: 'category:' }];
  assert.deepEqual(SUGGESTED_CATEGORIES, ['人像', '风景', '胶片', '黑白', '日常', '夜景']);
  assert.equal(categoryLabel(presets[0]), '未分类');
  assert.equal(categoryNames(presets).length, 4);
  const options = categoryOptions(presets, '已移除分类');
  assert.deepEqual(options.slice(0, 2), [['all', '全部分类'], ['category:', '未分类']]);
  assert.ok(options.some(([value, label]) => value === 'category:all' && label === 'all'));
  assert.ok(options.some(([value]) => value === 'category:category:'));
  assert.ok(options.some(([value]) => value === 'category:已移除分类'));
  assert.equal(presets.filter(item => categoryMatches(item, null)).length, presets.length);
  assert.equal(presets.filter(item => categoryMatches(item, '')).length, 2);
  assert.equal(presets.filter(item => categoryMatches(item, '胶片')).length, 2);
  assert.equal(presets.filter(item => categoryMatches(item, '已移除分类')).length, 0);
});

test('public categories come only from immutable published snapshots and legacy entries default to empty', () => {
  const published = { name: 'Tone', file: { url: 'https://files.example.com/test.ncp', sha256: 'a'.repeat(64), byteLength: 52, ncpVersion: '0100' } };
  const categorized = { id: 'a', status: 'published', published: { ...published, category: '胶片' }, draft: { category: 'SECRET DRAFT CATEGORY' } };
  const old = { id: 'b', status: 'published', published, draft: { category: 'SECRET DRAFT CATEGORY' } };
  const catalog = buildCatalog([categorized, old, { id: 'c', status: 'draft', published: { ...published, category: 'SECRET' } }]);
  assert.equal(catalog.version, 1);
  assert.deepEqual(catalog.presets.map(preset => preset.category), ['胶片', '']);
  assert.equal(catalog.presets[0].previewUrl, '');
  assert.ok(!JSON.stringify(catalog).includes('SECRET'));
  assert.equal(Object.hasOwn(old.published, 'category'), false, 'reading a legacy entry does not rewrite its saved snapshot');
});
