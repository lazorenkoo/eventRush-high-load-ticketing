const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/admin-api.js', 'utf8')
  .replace('export function', 'function') + '\nthis.create = adminApi;';
const id = '00000000-0000-0000-0000-000000000001';
const key = 'fixture-admin-key-' + 'a'.repeat(32);
function fixture(status = 200, bytes = new TextEncoder().encode('{"status":"READY"}')) {
  const calls = [];
  let cancelled = 0;
  const context = vm.createContext({AbortController, AbortSignal, Blob, FormData, URLSearchParams,
    async fetch(path, options) {
      calls.push({path, options});
      let read = false;
      return {ok: status === 200, status, body: {getReader: () => ({
        async read() { if (read) return {done: true}; read = true; return {value: bytes, done: false}; },
        async cancel() { cancelled++; },
      })}};
    },
  });
  vm.runInContext(source, context);
  return {api: context.create(key), create: context.create, calls, cancelled: () => cancelled};
}

test('scoped reads use closure key no-store credentials and encoded filters', async () => {
  const value = fixture();
  await value.api.activity(id, {q: 'A&B', limit: 20, source: 'MANUAL'});
  assert.equal(value.calls[0].path, `/api/v1/admin/concerts/${id}/activity?q=A%26B&limit=20&source=MANUAL`);
  assert.equal(value.calls[0].options.headers['X-Admin-Key'], key);
  assert.equal(value.calls[0].options.cache, 'no-store');
  assert.equal(value.calls[0].options.credentials, 'same-origin');
  assert.ok(value.calls[0].options.signal instanceof AbortSignal);
  assert.equal(value.cancelled(), 1);
});
test('CSV import uses multipart without starting a run', async () => {
  const value = fixture();
  await value.api.upload(id, new Blob(['firstName,lastName,seatNumber\nA,B,1']), 2);
  assert.equal(value.calls.length, 1);
  assert.match(value.calls[0].path, /load-runs\?concurrency=2$/);
  assert.ok(value.calls[0].options.body instanceof FormData);
  assert.equal(value.calls[0].options.body.get('file').name, 'clients.csv');
  assert.equal(value.calls[0].options.headers['Content-Type'], undefined);
  await value.api.run(id, id, 'start');
  assert.equal(value.calls[1].options.method, 'POST');
  assert.match(value.calls[1].path, /\/start$/);
});
test('invalid identifiers filters files and actions send nothing', () => {
  const value = fixture();
  for (const call of [() => value.api.stats('../escape'), () => value.api.retry(id, id, 0),
    () => value.api.activity(id, {unsafe: 'x'}), () => value.api.run(id, id, 'delete'),
    () => value.api.upload(id, new Blob([]), 2), () => value.api.upload(id, new Blob(['x']), 101)]) {
    assert.throws(call);
  }
  for (const invalid of ['', 'short', 'a'.repeat(513), 'a'.repeat(32) + '\n']) assert.throws(() => value.create(invalid));
  assert.equal(value.calls.length, 0);
});
test('close aborts pending requests and prevents reuse', async () => {
  const value = fixture();
  await value.api.stats(id);
  value.api.close();
  assert.equal(value.calls[0].options.signal.aborted, true);
  await assert.rejects(value.api.stats(id));
  assert.equal(value.calls.length, 1);
});
test('unauthorized response invalidates key and does not expose remote content', async () => {
  const value = fixture(401, new TextEncoder().encode('private details'));
  await assert.rejects(value.api.stats(id), /Administrative request failed \(401\)/);
  await assert.rejects(value.api.stats(id));
  assert.equal(value.calls.length, 1);
});
test('oversized body is cancelled before parsing', async () => {
  const value = fixture(200, new Uint8Array(262145));
  await assert.rejects(value.api.queue(), /exceeds the limit/);
  assert.equal(value.cancelled(), 1);
});
