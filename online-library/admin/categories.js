/** Category UI helpers mirror the service contract; no credentials or server-only dependency. */
export const SUGGESTED_CATEGORIES = Object.freeze(['人像', '风景', '胶片', '黑白', '日常', '夜景']);

export function normalizeCategory(value) {
  if (value === undefined || value === null) return '';
  if (typeof value !== 'string' || /[\x00-\x1f\x7f-\x9f]/u.test(value)
      || /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(value)) {
    throw new Error('分类必须是无控制字符的有效文字。');
  }
  const category = value.normalize('NFC').trim();
  if ([...category].length > 24) throw new Error('分类不能超过 24 个字符。');
  if (category === '全部' || category === '未分类') throw new Error('请填写具体分类，留空表示未分类。');
  return category;
}

export function categoryValue(preset) { return typeof preset?.category === 'string' ? preset.category : ''; }
export function categoryLabel(preset) { return categoryValue(preset) || '未分类'; }
export function categoryNames(items) {
  return [...new Set(items.map(categoryValue).filter(Boolean))].sort((a, b) => a.localeCompare(b, 'zh-CN'));
}
export function categoryOptions(items, selected = null) {
  const names = categoryNames(items);
  // A refreshed/edited list may no longer contain the chosen category; retain its filter until changed.
  if (selected && !names.includes(selected)) names.push(selected);
  return [['all', '全部分类'], ['category:', '未分类'], ...names.map(name => [`category:${name}`, name])];
}
export function categoryMatches(preset, selected) { return selected === null || categoryValue(preset) === selected; }
