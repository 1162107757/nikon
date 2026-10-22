import test from 'node:test';
import assert from 'node:assert/strict';
import { validateReviewedPlan } from '../scripts/reclassify-preset-categories.mjs';
import { ENV_ID } from '../scripts/cloud-tools.mjs';
import { STYLE_MODEL } from '../scripts/preset-style-classification.mjs';

test('reviewed change list must exactly match every backed-up identity, file, and assignment', () => {
  const row = { id: '00000000-0000-0000-0000-000000000001', draft: { category: '第三方合集', name: 'Test' },
    published: { category: '第三方合集', file: { sha256: 'a'.repeat(64) } } };
  const backup = { environment: ENV_ID, rows: [row] };
  const assignment = { id: row.id, sha256: row.published.file.sha256, oldCategory: '第三方合集', category: '人像', before: row };
  const plan = { environment: ENV_ID, model: STYLE_MODEL, total: 1, assignments: [assignment], changes: [assignment] };
  assert.doesNotThrow(() => validateReviewedPlan(plan, backup));
  for (const alter of [copy => copy.changes = [], copy => copy.assignments.push(copy.assignments[0]),
    copy => copy.assignments[0].before.draft.name = 'Tampered', copy => copy.assignments[0].sha256 = 'b'.repeat(64),
    copy => copy.assignments[0].oldCategory = '日常', copy => copy.assignments[0].id = 'missing',
    copy => copy.environment = 'another-environment']) {
    const copy = structuredClone(plan); alter(copy);
    assert.throws(() => validateReviewedPlan(copy, backup));
  }
});
