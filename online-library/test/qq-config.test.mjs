import test from 'node:test';
import assert from 'node:assert/strict';
import { withQqAppId } from '../scripts/qq-config.mjs';

test('QQ configuration preserves every existing opaque variable and does not mutate its input', () => {
  const original = [{ Key: 'UNRELATED_PROVIDER', Value: 'synthetic-opaque-value' }, { Key: 'QQ_APP_ID', Value: '101234567' }];
  const snapshot = structuredClone(original);
  const result = withQqAppId(original, '1105984855');
  assert.deepEqual(result, { variables: [snapshot[0], { Key: 'QQ_APP_ID', Value: '1105984855' }], changed: true });
  assert.deepEqual(original, snapshot);
  assert.notEqual(result.variables[0], original[0]);
  assert.deepEqual(withQqAppId(result.variables, '1105984855'), { variables: result.variables, changed: false });
  assert.deepEqual(withQqAppId([snapshot[0]], '1105984855'), { variables: [snapshot[0], { Key: 'QQ_APP_ID', Value: '1105984855' }], changed: true });
});

test('QQ configuration refuses malformed AppIDs or ambiguous environments without exposing values', () => {
  for (const appId of ['', undefined, 1105984855, 'bad-id', '1234', '1'.repeat(33), '1105984855\n']) {
    assert.throws(() => withQqAppId([], appId), /有效的 QQ AppID/);
  }
  for (const variables of [undefined, {}, [null], [{ Key: 'QQ_APP_ID', Value: 1105984855 }],
    [{ Key: 'DUPLICATE', Value: 'synthetic-opaque-value' }, { Key: 'DUPLICATE', Value: 'other' }]]) {
    assert.throws(() => withQqAppId(variables, '1105984855'), error => !error.message.includes('synthetic-opaque-value'));
  }
});
