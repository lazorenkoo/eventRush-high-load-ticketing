const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/numbered-seat.js', 'utf8')
  .replace('export function', 'function') + '\nthis.selector = numberedSeat;';
const concert = '00000000-0000-0000-0000-000000000001';
const row = number => ({id: `00000000-0000-0000-0000-${String(number).padStart(12, '0')}`,
  number, status: 'AVAILABLE'});
function selector(number, clock = () => 0) {
  const context = vm.createContext({Date: {now: clock}});
  vm.runInContext(source, context);
  return context.selector(concert, number);
}

test('finds a uniquely numbered seat across bounded pages', async () => {
  const calls = [];
  const selected = await selector(700)(async (method, path) => {
    calls.push(path);
    const offset = Number(new URL('http://local' + path).searchParams.get('offset'));
    return offset < 1000 ? Array.from({length: 500}, (_, index) => row(offset + index + 1)) : [];
  });
  assert.equal(selected, row(700).id);
  assert.equal(calls.length, 3);
  assert.match(calls[1], /limit=500&offset=500$/);
});
for (const state of ['RESERVED', 'SOLD', 'missing']) {
  test('rejects unavailable selection ' + state, async () => {
    await assert.rejects(selector(1)(async () => state === 'missing' ? [] : [{...row(1), status: state}]), /unavailable/);
  });
}
test('rejects duplicate seat numbers even on a later page', async () => {
  let page = 0;
  await assert.rejects(selector(1)(async () => page++ === 0
    ? Array.from({length: 500}, (_, index) => row(index + 1)) : [row(1)]), /ambiguous/);
});
test('rejects invalid configuration and malformed server rows', async () => {
  for (const number of [0, -1, 1.5, 10001, '1']) assert.throws(() => selector(number));
  for (const rows of [null, {}, [null], [{...row(1), id: 'bad'}], [{...row(1), number: '1'}],
    [{...row(1), status: 'UNKNOWN'}], Array(501).fill(row(1))]) {
    await assert.rejects(selector(1)(async () => rows), /Invalid seat response/);
  }
});
test('bounded scan rejects overflow and renews active admission', async () => {
  let time = 0;
  let pages = 0;
  let heartbeats = 0;
  await assert.rejects(selector(1, () => time)(async method => {
    if (method === 'POST') { heartbeats++; return null; }
    time += 31000;
    const offset = pages++ * 500;
    return Array.from({length: 500}, (_, index) => row(offset + index + 1));
  }), /supported limit/);
  assert.equal(pages, 21);
  assert.equal(heartbeats, 20);
});
