import assert from 'node:assert/strict';
import { test } from 'node:test';
import { formatDay, groupByDay } from '../src/history/days';
import { initials, shortId, splitAmount } from '../src/utils/amountParts';

const now = new Date(2026, 10, 6, 10, 0); // 6 Nov 2026, 10:00 local
const at = (y: number, m: number, d: number, h = 12) => new Date(y, m, d, h).toISOString();

test('groupByDay: today, yesterday, older; keeps server order and merges split days', () => {
  const items = [
    { id: 1, t: at(2026, 10, 6, 9) },
    { id: 2, t: at(2026, 10, 5, 23) },
    { id: 3, t: at(2026, 10, 2) },
    { id: 4, t: at(2026, 10, 5, 1) }, // out of order: joins yesterday, no second header
  ];
  const groups = groupByDay(items, (item) => item.t, now);
  assert.deepEqual(
    groups.map((group) => [group.relative, group.items.map((item) => item.id)]),
    [
      ['today', [1]],
      ['yesterday', [2, 4]],
      [null, [3]],
    ],
  );
});

test('groupByDay: empty list and unparseable timestamps', () => {
  assert.deepEqual(
    groupByDay([], (item: string) => item, now),
    [],
  );
  const group = groupByDay(['not-a-date'], (item) => item, now)[0]!;
  assert.equal(group.key, 'not-a-date');
  assert.equal(group.relative, null);
  assert.equal(formatDay(group, 'en', now), 'not-a-date');
});

test('formatDay: year only when it differs from the current year', () => {
  const thisYear = groupByDay([at(2026, 10, 2)], (item) => item, now)[0]!;
  const lastYear = groupByDay([at(2025, 10, 2)], (item) => item, now)[0]!;
  assert.match(formatDay(thisYear, 'en', now), /November/);
  assert.doesNotMatch(formatDay(thisYear, 'en', now), /2026/);
  assert.match(formatDay(lastYear, 'en', now), /2025/);
});

test('splitAmount: minor units split off, string otherwise unchanged', () => {
  const cases: [string, string, string][] = [
    ['€72.90', '€72', '.90'],
    ['MUR 1,000.00', 'MUR 1,000', '.00'],
    ['TND 0.000', 'TND 0', '.000'],
    ['−€1.10', '−€1', '.10'],
    ['••••', '••••', ''],
    ['Setting up…', 'Setting up…', ''],
    ['—', '—', ''],
  ];
  for (const [text, main, minor] of cases) {
    const parts = splitAmount(text, 'en');
    assert.deepEqual(parts, { main, minor }, text);
    assert.equal(parts.main + parts.minor, text);
  }
});

test('initials and shortId', () => {
  assert.equal(initials('Amira Ben Salah'), 'AB');
  assert.equal(initials('élodie'), 'É');
  assert.equal(initials('  '), '');
  assert.equal(shortId('7bf8f8ad-1133-419d-9a13-9c3883690de0'), '7bf8f8ad…690de0');
  assert.equal(shortId('short-id'), 'short-id');
});
