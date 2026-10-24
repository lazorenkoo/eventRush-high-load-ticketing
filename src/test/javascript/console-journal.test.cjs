const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/console-journal.js', 'utf8')
  .replace('export function', 'function') + '\nthis.mount = mountConsoleJournal;';
class Element {
  children = []; listeners = {}; dataset = {}; textContent = ''; value = ''; scrollTop = 0; scrollHeight = 1000;
  append(...nodes) { this.children.push(...nodes); }
  replaceChildren(...nodes) { this.children = nodes; }
  setAttribute() {}
  addEventListener(name, callback) { this.listeners[name] = callback; }
  remove() { this.removed = true; }
}
function fixture() {
  const elements = [], calls = [], windows = [], timers = new Map();
  let timer = 0;
  const context = vm.createContext({window: {open(...args) { windows.push(args); }}, document: {createElement(tag) {
    const element = new Element(); element.tag = tag; elements.push(element); return element;
  }}, setTimeout(callback, delay) { timers.set(++timer, {callback, delay}); return timer; },
  clearTimeout(id) { timers.delete(id); }});
  vm.runInContext(source, context);
  const dispose = context.mount({journal(concert, filters) { return new Promise((resolve, reject) => {
    calls.push({concert, filters, resolve, reject});
  }); }}, 'concert', new Element());
  return {elements, calls, windows, timers, dispose, output: elements.find(node => node.className === 'console-output'),
    follow: elements.find(node => node.type === 'checkbox'),
    poll() { const [id, pending] = timers.entries().next().value; timers.delete(id); pending.callback(); }};
}
const flush = () => new Promise(setImmediate);
const entry = id => ({id, occurredAt: 'now', source: 'MANUAL', firstName: '<script>', lastName: 'Buyer',
  seatNumber: 7, kind: 'PAYMENT', state: 'SUCCESS', participantId: 'p', eventId: 'e'});

test('console popout scopes the concert without transferring administrator credentials', () => {
  const value = fixture();
  const link = value.elements.find(node => node.tag === 'a');
  let prevented = false;
  link.listeners.click({preventDefault() { prevented = true; }});
  assert.equal(prevented, true);
  assert.deepEqual(value.windows[0], ['/admin.html?console=1&concertId=concert', '_blank', 'noopener,width=1000,height=800']);
  assert.equal(link.rel, 'noopener');
  assert.equal(link.target, '_blank');
  value.dispose();
});

test('console bounds retention and follows backlog then normal polling without HTML interpretation', async () => {
  const value = fixture();
  assert.equal(value.calls[0].filters.limit, 50);
  for (let page = 0; page < 7; page++) {
    value.calls[page].resolve({items: Array.from({length: 50}, (_, i) => entry(page * 50 + i + 1)),
      nextAfter: (page + 1) * 50, hasMore: page < 6});
    await flush();
    assert.equal(value.output.scrollTop, 1000);
    assert.equal([...value.timers.values()][0].delay, page < 6 ? 500 : 3000);
    if (page < 6) value.poll();
  }
  assert.equal(value.output.children.length, 300);
  assert.equal(value.output.children[0].children[1].textContent, 'MANUAL <script> Buyer ');
  assert.equal(value.calls[6].filters.after, 300);
  value.follow.checked = false;
  value.output.scrollTop = 120;
  value.poll();
  value.calls[7].resolve({items: [], nextAfter: 350, hasMore: false});
  await flush();
  assert.equal(value.output.scrollTop, 120);
  value.dispose();
  assert.equal(value.timers.size, 0);
});

test('filter resets cursor and old responses cannot repaint the new console', async () => {
  const value = fixture();
  const fields = value.elements.filter(node => node.tag === 'select');
  fields[0].value = 'TEST'; fields[1].value = 'TICKET';
  value.elements.find(node => node.type === 'search').value = 'Ada';
  fields[0].listeners.change();
  assert.equal(value.calls[1].filters.after, 0);
  assert.equal(value.calls[1].filters.source, 'TEST');
  assert.equal(value.calls[1].filters.kind, 'TICKET');
  assert.equal(value.calls[1].filters.q, 'Ada');
  value.calls[1].resolve({items: [entry(10)], nextAfter: 10, hasMore: false});
  await flush();
  value.calls[0].resolve({items: [], nextAfter: 0, hasMore: false});
  await flush();
  assert.equal(value.output.children.length, 1);
  value.dispose();
});

test('stationary backlog cursor stops polling and disposed responses remain ignored', async () => {
  const value = fixture();
  value.calls[0].resolve({items: [], nextAfter: 0, hasMore: true});
  await flush();
  assert.equal(value.timers.size, 0);
  assert.equal(value.elements.some(node => node.textContent === 'Invalid console cursor.'), true);
  value.elements.find(node => node.textContent === 'Apply filters').listeners.click();
  value.dispose();
  value.calls[1].resolve({items: [entry(1)], nextAfter: 1, hasMore: false});
  await flush();
  assert.equal(value.output.children.length, 0);
  assert.equal(value.timers.size, 0);
  assert.equal(value.elements[0].removed, true);
});
