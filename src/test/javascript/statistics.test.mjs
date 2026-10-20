import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(new URL('../../main/resources/static/admin.js', import.meta.url), 'utf8');
const settle = () => new Promise(resolve => setImmediate(resolve));

function page(respond) {
  class Node extends EventTarget {
    children = [];
    value = '';
    textContent = '';
    disabled = false;
    append(...nodes) { this.children.push(...nodes); }
    replaceChildren() { this.children = []; }
    querySelector() { return button; }
  }
  const button = new Node();
  const nodes = Object.fromEntries(['stats', 'key', 'concert', 'message', 'result']
    .map(name => [name, new Node()]));
  const requests = [];
  vm.runInNewContext(source, {
    document: {querySelector: selector => nodes[selector.slice(1)], createElement: () => new Node()},
    encodeURIComponent,
    fetch: async (path, options) => {
      requests.push({path, options});
      return respond(path, options);
    }
  });
  nodes.key.value = 'test-only-key';
  nodes.concert.value = ' concert/id ';
  function submit() {
    const event = new Event('submit', {cancelable: true});
    nodes.stats.dispatchEvent(event);
    assert.equal(event.defaultPrevented, true);
  }
  return {nodes, button, requests, submit};
}

test('renders server statistics and confines key to request header', async () => {
  const stats = {status: '<b>ON_SALE</b>', totalSeats: 50, available: 42, reserved: 5,
    sold: 3, pendingPayments: 2, successfulPayments: 3, failedPayments: 1, revenue: 75};
  const view = page(async () => ({ok: true, json: async () => stats}));
  view.submit();
  assert.equal(view.nodes.key.value, '');
  assert.equal(view.button.disabled, true);
  await settle();
  assert.equal(view.button.disabled, false);
  assert.equal(view.nodes.message.textContent, '');
  assert.equal(view.requests[0].path, '/api/v1/admin/concerts/concert%2Fid/stats');
  assert.equal(view.requests[0].options.headers['X-Admin-Key'], 'test-only-key');
  assert.equal(view.requests[0].options.credentials, 'same-origin');
  assert.equal(view.requests[0].options.cache, 'no-store');
  assert.deepEqual(view.nodes.result.children.map(row => row.children[1].textContent), Object.values(stats));
  assert.equal(view.nodes.result.children[0].children[1].children.length, 0);
});

for (const status of [401, 404, 500]) {
  test(`HTTP ${status} clears old results and releases refresh`, async () => {
    const view = page(async () => ({ok: false, status}));
    view.nodes.result.children.push('stale');
    view.submit();
    await settle();
    assert.equal(view.nodes.key.value, '');
    assert.equal(view.nodes.result.children.length, 0);
    assert.equal(view.nodes.message.textContent, `Request failed (${status}).`);
    assert.equal(view.button.disabled, false);
  });
}

test('network failure clears key and allows retry with a fresh key', async () => {
  let attempts = 0;
  const view = page(async () => {
    if (++attempts === 1) throw new Error('Network unavailable');
    return {ok: true, json: async () => ({status: 'ON_SALE'})};
  });
  view.submit();
  await settle();
  assert.equal(view.nodes.message.textContent, 'Network unavailable');
  assert.equal(view.nodes.key.value, '');
  assert.equal(view.button.disabled, false);
  view.nodes.key.value = 'replacement-test-key';
  view.submit();
  await settle();
  assert.equal(view.requests[1].options.headers['X-Admin-Key'], 'replacement-test-key');
  assert.equal(view.nodes.message.textContent, '');
});
