import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(new URL('../../main/resources/static/queue-stats.js', import.meta.url), 'utf8');
const settle = () => new Promise(resolve => setImmediate(resolve));

async function page(respond, timers = {}) {
  class Node extends EventTarget {
    children = [];
    textContent = '';
    value = '';
    append(node) { this.children.push(node); }
    replaceChildren() { this.children = []; }
    setAttribute(name, value) { this[name] = value; }
  }
  const parent = new Node();
  const requests = [];
  const context = vm.createContext({
    document: {querySelector: () => parent, createElement: () => new Node()}, Date, setTimeout, clearTimeout, ...timers,
    fetch: async (path, options) => { requests.push({path, options}); return respond(); }
  });
  const module = new vm.SourceTextModule(source, {context});
  await module.link(() => { throw new Error('Unexpected dependency'); });
  await module.evaluate();
  const section = parent.children[0];
  const [heading, form, message, table] = section.children;
  const key = form.children[0].children[0];
  const button = form.children[1];
  key.value = 'test-only-key';
  function submit() {
    const event = new Event('submit', {cancelable: true});
    form.dispatchEvent(event);
    assert.equal(event.defaultPrevented, true);
  }
  return {heading, key, button, message, table, requests, submit,
    auto: form.children[2].children[0], stop: form.children[3]};
}

test('renders global counts, age and timestamp without retaining key', async () => {
  const stats = {scope: 'ALL_GATES', waiting: 50000, admitted: 20, active: 15,
    snapshotAgeSeconds: 2.5, fetchedAt: '2026-10-21T01:00:00Z'};
  const view = await page(async () => ({ok: true, json: async () => stats}));
  assert.equal(view.heading.textContent, 'Queue — all gates');
  assert.equal(view.key.type, 'password');
  assert.equal(view.key.autocomplete, 'off');
  view.submit();
  assert.equal(view.key.value, '');
  assert.equal(view.button.disabled, true);
  await settle();
  assert.deepEqual(view.table.children.map(row => row.children[1].textContent), [50000, 20, 15, 2.5]);
  assert.match(view.message.textContent, /^Fetched: /);
  assert.equal(view.requests[0].path, '/api/v1/admin/queue-stats');
  assert.equal(view.requests[0].options.headers['X-Admin-Key'], 'test-only-key');
  assert.equal(view.requests[0].options.credentials, 'same-origin');
  assert.equal(view.requests[0].options.cache, 'no-store');
  assert.equal(view.button.disabled, false);
});

for (const status of [401, 503]) {
  test(`HTTP ${status} removes stale results and permits retry`, async () => {
    const view = await page(async () => ({ok: false, status}));
    view.table.children.push('stale');
    view.submit();
    await settle();
    assert.equal(view.table.children.length, 0);
    assert.equal(view.message.textContent, `Queue unavailable (${status}).`);
    assert.equal(view.key.value, '');
    assert.equal(view.button.disabled, false);
  });
}

test('rejects unexpected scope rather than mislabelling counts', async () => {
  const view = await page(async () => ({ok: true, json: async () => ({scope: 'ONE_GATE'})}));
  view.submit();
  await settle();
  assert.equal(view.message.textContent, 'Unexpected queue scope.');
  assert.equal(view.table.children.length, 0);
});

test('network error releases controls and clears credential', async () => {
  const view = await page(async () => { throw new Error('Network unavailable'); });
  view.submit();
  await settle();
  assert.equal(view.message.textContent, 'Network unavailable');
  assert.equal(view.button.disabled, false);
  assert.equal(view.key.value, '');
});

async function automatic(respond) {
  const scheduled = new Map();
  let nextId = 0;
  let onHide;
  const view = await page(respond, {
    setTimeout: (callback, delay) => {
      assert.equal(delay, 5000);
      scheduled.set(++nextId, callback);
      return nextId;
    },
    clearTimeout: id => scheduled.delete(id),
    addEventListener: (name, callback) => { assert.equal(name, 'pagehide'); onHide = callback; }
  });
  view.auto.checked = true;
  view.stopNow = () => view.stop.dispatchEvent(new Event('click'));
  view.hide = () => onHide();
  view.tick = async () => {
    const [id, callback] = scheduled.entries().next().value;
    scheduled.delete(id);
    await callback();
    await settle();
  };
  return {...view, scheduled};
}
const snapshot = () => ({ok: true, json: async () => ({scope: 'ALL_GATES',
  waiting: 1, admitted: 2, active: 3, snapshotAgeSeconds: 0, fetchedAt: '2026-10-21T11:00:00Z'})});

test('auto-refresh schedules after completion and Stop removes timer', async () => {
  const view = await automatic(async () => snapshot());
  view.submit();
  await settle();
  assert.equal(view.scheduled.size, 1);
  await view.tick();
  assert.equal(view.requests.length, 2);
  assert.equal(view.requests[1].options.headers['X-Admin-Key'], 'test-only-key');
  view.stopNow();
  assert.equal(view.scheduled.size, 0);
  assert.equal(view.stop.disabled, true);
  assert.equal(view.key.value, '');
});

test('Stop ignores an in-flight response and never schedules another request', async () => {
  let finish;
  const view = await automatic(() => new Promise(resolve => { finish = resolve; }));
  view.submit();
  assert.equal(view.scheduled.size, 0);
  view.stopNow();
  finish(snapshot());
  await settle();
  assert.equal(view.table.children.length, 0);
  assert.equal(view.scheduled.size, 0);
  assert.equal(view.button.disabled, false);
});

test('pagehide cancels refresh and restart uses only a new key', async () => {
  const view = await automatic(async () => snapshot());
  view.submit();
  await settle();
  view.hide();
  assert.equal(view.scheduled.size, 0);
  view.key.value = 'new-test-key';
  view.submit();
  await settle();
  assert.equal(view.requests[1].options.headers['X-Admin-Key'], 'new-test-key');
  view.stopNow();
});

test('upstream failure halts auto-refresh without a retry storm', async () => {
  const view = await automatic(async () => ({ok: false, status: 503}));
  view.submit();
  await settle();
  assert.equal(view.message.textContent, 'Queue unavailable (503).');
  assert.equal(view.scheduled.size, 0);
  assert.equal(view.requests.length, 1);
  assert.equal(view.stop.disabled, true);
});