const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/journal-panel.js', 'utf8')
  .replace('export function', 'function') + '\nthis.mount = mountJournal;';
class Element {
  children = [];
  listeners = {};
  textContent = '';
  value = '';
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
  const dispose = context.mount({journal(concert, filters) { return new Promise((resolve, reject) => {
    calls.push({concert, filters, resolve, reject});
  }); }}, 'concert', new Element());
  return {elements, calls, timers, dispose, body: elements.find(node => node.tag === 'tbody'),
    button: text => elements.find(node => node.textContent === text)};
}
const flush = () => new Promise(setImmediate);
const entry = id => ({id, firstName: '<script>', lastName: String(id), source: 'TEST', kind: 'PAYMENT',
  state: 'SUCCESS', occurredAt: '2026-10-23T17:00:00Z', participantId: 'participant', eventId: 'event'});
const page = (start, count, hasMore) => ({items: Array.from({length: count}, (_, i) => entry(start + i)),
  nextAfter: start + count - 1, hasMore});

test('journal advances cursors explicitly through backlog and retains only 100 rows', async () => {
  const value = fixture();
  assert.equal(value.calls[0].filters.after, 0);
  assert.equal(value.calls[0].filters.limit, 50);
  for (let index = 0; index < 3; index++) {
    value.calls[index].resolve(page(index * 50 + 1, 50, index < 2));
    await flush();
    if (index < 2) {
      assert.equal(value.timers.size, 0);
      assert.equal(value.button('Read next entries').disabled, false);
      value.button('Read next entries').listeners.click();
      assert.equal(value.calls[index + 1].filters.after, (index + 1) * 50);
    }
  }
  assert.equal(value.body.children.length, 100);
  assert.equal(value.body.children[0].children[1].textContent, '<script> 51');
  assert.equal(value.button('Read next entries').disabled, true);
  assert.equal(value.timers.size, 1);
  const [id, poll] = value.timers.entries().next().value;
  value.timers.delete(id);
  poll();
  assert.equal(value.calls[3].filters.after, 150);
  value.dispose();
  value.calls[3].resolve(page(151, 1, false));
  await flush();
  assert.equal(value.timers.size, 0);
  assert.equal(value.body.children.length, 100);
});

test('journal filters reset cursor and late responses cannot restore old entries', async () => {
  const value = fixture();
  const fields = value.elements.filter(node => node.tag === 'select');
  fields[0].value = 'MANUAL';
  fields[1].value = 'QUEUE';
  value.elements.find(node => node.type === 'search').value = 'Ada';
  fields[1].listeners.change();
  assert.equal(value.calls[1].filters.after, 0);
  assert.equal(value.calls[1].filters.source, 'MANUAL');
  assert.equal(value.calls[1].filters.kind, 'QUEUE');
  assert.equal(value.calls[1].filters.q, 'Ada');
  value.calls[1].resolve(page(20, 1, false));
  await flush();
  value.calls[0].resolve(page(1, 50, true));
  await flush();
  assert.equal(value.body.children.length, 1);
  assert.equal(value.body.children[0].children[1].textContent, '<script> 20');
  value.dispose();
  assert.equal(value.timers.size, 0);
  assert.equal(value.elements[0].removed, true);
});

test('journal rejects backward cursors and stops polling on errors', async () => {
  const value = fixture();
  value.calls[0].resolve(page(10, 1, true));
  await flush();
  value.button('Read next entries').listeners.click();
  value.calls[1].resolve({items: [], nextAfter: 9, hasMore: false});
  await flush();
  assert.equal(value.timers.size, 0);
  assert.equal(value.elements.some(node => node.textContent === 'Invalid journal cursor.'), true);
  assert.equal(value.button('Search / Restart journal').disabled, false);
  value.button('Search / Restart journal').listeners.click();
  value.calls[2].reject(new Error('HTTP 503'));
  await flush();
  assert.equal(value.body.children.length, 0);
  assert.equal(value.timers.size, 0);
  value.dispose();
});
