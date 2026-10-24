const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/event-panel.js', 'utf8')
  .replace(/^import .*;\r?\n/, '').replace('export function', 'function') + '\nthis.mount = mountEvents;';
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
  const elements = [], calls = [], histories = [];
  const context = vm.createContext({document: {createElement(tag) {
    const node = new Element(); node.tag = tag; elements.push(node); return node;
  }}, mountDeliveryHistory(api, concert, event, parent) {
    const history = {concert, event, parent, closed: false};
    histories.push(history);
    return () => { history.closed = true; };
  }});
  vm.runInContext(source, context);
  const dispose = context.mount({events(concert, filters) { return new Promise((resolve, reject) => {
    calls.push({concert, filters, resolve, reject});
  }); }}, 'concert', new Element());
  return {elements, calls, histories, dispose, body: elements.find(node => node.tag === 'tbody'),
    button: text => elements.findLast(node => node.textContent === text)};
}
const flush = () => new Promise(setImmediate);
const event = (id, publishedAt = 'now') => ({id, publishedAt, type: '<script>', createdAt: 'then', publishAttempts: 1});

test('event pagination scopes reads and disposes child history on selection and page changes', async () => {
  const value = fixture();
  assert.equal(value.calls[0].concert, 'concert');
  assert.equal(value.calls[0].filters.limit, 20);
  value.calls[0].resolve({items: [event('one'), event('two', null)], hasMore: true, nextOffset: 20});
  await flush();
  assert.equal(value.body.children[0].children[1].textContent, '<script>');
  assert.equal(value.elements.filter(node => node.textContent === 'View deliveries').length, 1);
  value.button('View deliveries').listeners.click();
  assert.equal(value.histories[0].event, 'one');
  assert.equal(value.histories[0].concert, 'concert');
  value.button('View deliveries').listeners.click();
  assert.equal(value.histories[0].closed, true);
  value.button('Next events').listeners.click();
  assert.equal(value.histories[1].closed, true);
  assert.equal(value.calls[1].filters.offset, 20);
  value.calls[1].resolve({items: [], hasMore: false, nextOffset: null});
  await flush();
  assert.equal(value.button('Previous events').disabled, false);
  assert.equal(value.button('Next events').disabled, true);
  value.button('Refresh events').listeners.click();
  assert.equal(value.calls[2].filters.offset, 0);
  value.dispose();
});

test('stale event responses and detached selection handlers cannot reopen history', async () => {
  const value = fixture();
  value.calls[0].resolve({items: [event('old')], hasMore: false});
  await flush();
  const old = value.button('View deliveries');
  value.button('Refresh events').listeners.click();
  value.button('Refresh events').listeners.click();
  value.calls[2].resolve({items: [event('new')], hasMore: false});
  await flush();
  value.calls[1].resolve({items: [], hasMore: false});
  await flush();
  old.listeners.click();
  assert.equal(value.histories.length, 0);
  assert.equal(value.body.children[0].children[0].textContent, 'new');
  value.button('View deliveries').listeners.click();
  value.dispose();
  assert.equal(value.histories[0].closed, true);
  assert.equal(value.elements[0].removed, true);
});

test('event query bounds disable invalid next cursors and errors permit refresh', async () => {
  const value = fixture();
  for (const offset of [null, 0, 50001, 1.5]) {
    value.calls.at(-1).resolve({items: [], hasMore: true, nextOffset: offset, truncated: true});
    await flush();
    assert.equal(value.button('Next events').disabled, true);
    value.button('Refresh events').listeners.click();
  }
  value.calls.at(-1).reject(new Error('HTTP 503'));
  await flush();
  assert.equal(value.elements.some(node => node.textContent === 'HTTP 503'), true);
  assert.equal(value.button('Refresh events').disabled, false);
  value.dispose();
});
