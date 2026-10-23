const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/dashboard-metrics.js', 'utf8')
  .replace('export function', 'function') + '\nthis.mount = mountMetrics;';
class Element {
  children = [];
  listeners = {};
  textContent = '';
  append(...nodes) { this.children.push(...nodes); }
  replaceChildren(...nodes) { this.children = nodes; }
  setAttribute() {}
  addEventListener(name, callback) { this.listeners[name] = callback; }
  remove() { this.removed = true; }
}
function fixture() {
  const elements = [], calls = [], timers = new Map();
  let timerId = 0;
  const context = vm.createContext({document: {createElement(tag) {
    const node = new Element(); node.tag = tag; elements.push(node); return node;
  }}, setTimeout(callback) { timers.set(++timerId, callback); return timerId; },
  clearTimeout(id) { timers.delete(id); }});
  vm.runInContext(source, context);
  const api = Object.fromEntries(['stats', 'queue'].map(method => [method,
    (...args) => new Promise((resolve, reject) => { calls.push({method, args, resolve, reject}); })]));
  const dispose = context.mount(api, 'concert', new Element());
  return {elements, calls, timers, dispose, tables: elements.filter(node => node.tag === 'table'),
    refresh: elements.find(node => node.textContent === 'Refresh metrics')};
}
const flush = () => new Promise(setImmediate);
const concert = {concertId: 'concert', status: '<script>', totalSeats: 300, available: 299, sold: 1,
  purchasesLastMinute: 1, purchasesPerSecond: 1 / 60};
const queue = {scope: 'ALL_GATES', waiting: 8, admitted: 2, active: 4, fetchedAt: 'now', snapshotAgeSeconds: 1};

test('metrics display separately scoped snapshots without overlapping refresh', async () => {
  const value = fixture();
  assert.deepEqual(value.calls[0].args, ['concert']);
  assert.deepEqual(value.calls[1].args, []);
  await value.refresh.listeners.click();
  assert.equal(value.calls.length, 2);
  value.calls[0].resolve(concert);
  value.calls[1].resolve(queue);
  await flush();
  assert.equal(value.tables[0].children[0].children[1].textContent, '<script>');
  assert.equal(value.tables[1].children[0].children[1].textContent, 8);
  assert.equal(value.elements.some(node => node.textContent === 'Purchases / second (60-second window)'), true);
  assert.equal(value.timers.size, 1);
  value.dispose();
  assert.equal(value.timers.size, 0);
});

test('partial metrics failure clears failed snapshot and stops automatic polling', async () => {
  const value = fixture();
  value.calls[0].resolve(concert);
  value.calls[1].resolve(queue);
  await flush();
  const pending = value.refresh.listeners.click();
  value.calls[2].resolve(concert);
  value.calls[3].reject(new Error('Queue HTTP 503'));
  await pending;
  assert.equal(value.tables[0].children.length > 0, true);
  assert.equal(value.tables[1].children.length, 0);
  assert.equal(value.elements.some(node => node.textContent === 'Queue HTTP 503'), true);
  assert.equal(value.timers.size, 0);
  assert.equal(value.refresh.disabled, false);
  value.dispose();
});

test('unexpected scopes cannot render misleading metrics or restart polling', async () => {
  for (const wrong of ['concert', 'queue']) {
    const value = fixture();
    value.calls[0].resolve({...concert, concertId: wrong === 'concert' ? 'other' : 'concert'});
    value.calls[1].resolve({...queue, scope: wrong === 'queue' ? 'ONE_GATE' : 'ALL_GATES'});
    await flush();
    assert.equal(value.timers.size, 0);
    assert.equal(value.tables[wrong === 'concert' ? 0 : 1].children.length, 0);
    assert.equal(value.elements.some(node => node.textContent === `Unexpected ${wrong} scope.`), true);
    value.dispose();
  }
});

test('disposed metrics ignore pending results and cannot schedule another refresh', async () => {
  const value = fixture();
  value.dispose();
  value.calls[0].resolve(concert);
  value.calls[1].resolve(queue);
  await flush();
  await value.refresh.listeners.click();
  assert.equal(value.calls.length, 2);
  assert.equal(value.tables[0].children.length, 0);
  assert.equal(value.timers.size, 0);
  assert.equal(value.elements[0].removed, true);
});
