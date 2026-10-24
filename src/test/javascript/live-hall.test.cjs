const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/live-hall.js', 'utf8')
  .replace(/^import .*;\r?\n/, '').replace('export function', 'function') + '\nthis.mount = mountLiveHall;';
class Element {
  children = []; listeners = {}; textContent = '';
  append(...nodes) { this.children.push(...nodes); }
  setAttribute() {}
  addEventListener(name, callback) { this.listeners[name] = callback; }
  remove() { this.removed = true; }
}
function fixture(respond) {
  const elements = [], calls = [], updates = [], selections = [], timers = new Map();
  let timerId = 0;
  const hall = {busy: false, update(items) { updates.push(items); },
    setBusy(value) { this.busy = value; }, dispose() { this.disposed = true; }};
  const context = vm.createContext({AbortController, AbortSignal, document: {createElement() {
    const element = new Element(); elements.push(element); return element;
  }}, mountHallMap() { return hall; }, async fetch(path, options) {
    calls.push({path, options});
    return respond(path, calls.length);
  }, setTimeout(callback) { timers.set(++timerId, callback); return timerId; },
  clearTimeout(id) { timers.delete(id); }});
  vm.runInContext(source, context);
  const controller = context.mount({id: 'concert'}, new Element(), value => selections.push(value));
  return {controller, hall, elements, calls, updates, selections, timers};
}
const flush = () => new Promise(setImmediate);
const seat = number => ({id: `seat-${number}`, number, status: 'AVAILABLE'});
const ok = items => ({ok: true, json: async () => items});

test('live hall assembles admitted pages before rendering and pauses polling during purchase', async () => {
  const value = fixture((path, count) => ok(count === 1 ? Array.from({length: 500}, (_, i) => seat(i)) : [seat(500)]));
  await flush();
  assert.equal(value.calls.length, 2);
  assert.match(value.calls[1].path, /offset=500$/);
  assert.equal(value.calls[0].options.credentials, 'same-origin');
  assert.equal(value.calls[0].options.cache, 'no-store');
  assert.equal(value.updates.length, 1);
  assert.equal(value.updates[0].length, 501);
  assert.equal(value.timers.size, 1);
  value.controller.setBusy(true);
  assert.equal(value.hall.busy, true);
  assert.equal(value.timers.size, 0);
  await value.controller.refresh();
  assert.equal(value.calls.length, 2);
  value.controller.dispose();
});

test('failed availability invalidates selection and manual refresh can recover', async () => {
  const value = fixture((path, count) => count === 1 ? {ok: false, status: 401} : ok([seat(1)]));
  await flush();
  assert.equal(value.hall.busy, true);
  assert.equal(value.selections.at(-1), null);
  assert.equal(value.updates.length, 0);
  assert.equal(value.timers.size, 0);
  await value.controller.refresh();
  assert.equal(value.hall.busy, false);
  assert.equal(value.updates.length, 1);
  assert.equal(value.timers.size, 1);
  value.controller.dispose();
});

test('invalid duplicate pages and oversized halls never render a partial map', async () => {
  for (const mode of ['duplicate', 'overflow']) {
    const value = fixture((path, count) => ok(Array.from({length: 500}, (_, i) =>
      seat(mode === 'duplicate' ? i : (count - 1) * 500 + i))));
    await flush();
    assert.equal(value.updates.length, 0);
    assert.equal(value.hall.busy, true);
    assert.equal(value.timers.size, 0);
    assert.equal(value.calls.length, mode === 'duplicate' ? 2 : 21);
    value.controller.dispose();
  }
});

test('pending fetch is single-flight and disposal aborts and ignores completion', async () => {
  let resolve;
  const value = fixture(() => new Promise(done => { resolve = done; }));
  await value.controller.refresh();
  assert.equal(value.calls.length, 1);
  value.controller.dispose();
  assert.equal(value.calls[0].options.signal.aborted, true);
  resolve(ok([seat(1)]));
  await flush();
  assert.equal(value.updates.length, 0);
  assert.equal(value.hall.disposed, true);
  assert.equal(value.timers.size, 0);
});
