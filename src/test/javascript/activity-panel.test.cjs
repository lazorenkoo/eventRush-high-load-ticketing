const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/activity-panel.js', 'utf8')
  .replace('export function', 'function') + '\nthis.mount = mountActivity;';
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
  const elements = [];
  const calls = [];
  const timers = new Map();
  let timerId = 0;
  const context = vm.createContext({document: {createElement(tag) {
    const node = new Element(); node.tag = tag; elements.push(node); return node;
  }}, setTimeout(callback) { timers.set(++timerId, callback); return timerId; },
  clearTimeout(id) { timers.delete(id); }});
  vm.runInContext(source, context);
  const api = {activity(concert, filters) { return new Promise((resolve, reject) => {
    calls.push({concert, filters, resolve, reject});
  }); }};
  const dispose = context.mount(api, 'concert', new Element());
  return {calls, timers, dispose, elements, body: elements.find(node => node.tag === 'tbody'),
    button: text => elements.find(node => node.textContent === text)};
}
const flush = () => new Promise(setImmediate);
const participant = {firstName: '<img src=x>', lastName: 'Buyer', source: 'MANUAL', seatNumber: 7,
  state: 'PURCHASED', paymentStatus: 'SUCCESS', ticketNumber: 'T-7', id: 'participant', reservationId: 'reservation'};

test('activity pages use bounded offsets and filter changes reset pagination', async () => {
  const value = fixture();
  assert.equal(value.calls[0].filters.limit, 20);
  value.calls[0].resolve(Array(20).fill(participant));
  await flush();
  assert.equal(value.timers.size, 1);
  assert.equal(value.button('Previous').disabled, true);
  assert.equal(value.body.children[0].children[0].textContent, '<img src=x> Buyer');
  value.button('Next').listeners.click();
  assert.equal(value.timers.size, 0);
  assert.equal(value.calls[1].filters.offset, 20);
  value.calls[1].resolve([participant]);
  await flush();
  assert.equal(value.button('Next').disabled, true);
  assert.equal(value.button('Previous').disabled, false);
  const selects = value.elements.filter(node => node.tag === 'select');
  selects[0].value = 'TEST';
  selects[1].value = 'PURCHASED';
  value.elements.find(node => node.type === 'search').value = 'Buyer';
  selects[0].listeners.change();
  assert.equal(value.calls[2].filters.offset, 0);
  assert.equal(value.calls[2].filters.source, 'TEST');
  assert.equal(value.calls[2].filters.state, 'PURCHASED');
  assert.equal(value.calls[2].filters.q, 'Buyer');
  value.calls[2].resolve([]);
  await flush();
  value.dispose();
  assert.equal(value.timers.size, 0);
});

test('stale activity responses and disposed requests cannot repaint or restart polling', async () => {
  const value = fixture();
  value.button('Search / Refresh').listeners.click();
  value.calls[1].resolve([participant]);
  await flush();
  value.calls[0].resolve([]);
  await flush();
  assert.equal(value.body.children.length, 1);
  assert.equal(value.timers.size, 1);
  value.button('Search / Refresh').listeners.click();
  value.dispose();
  value.calls[2].resolve([]);
  await flush();
  assert.equal(value.body.children.length, 1);
  assert.equal(value.timers.size, 0);
  assert.equal(value.elements[0].removed, true);
});

test('activity errors stop automatic polling but allow explicit refresh', async () => {
  const value = fixture();
  value.calls[0].reject(new Error('HTTP 503'));
  await flush();
  assert.equal(value.timers.size, 0);
  assert.equal(value.button('Search / Refresh').disabled, false);
  assert.equal(value.elements.some(node => node.textContent === 'HTTP 503'), true);
  value.button('Search / Refresh').listeners.click();
  value.calls[1].resolve([]);
  await flush();
  assert.equal(value.timers.size, 1);
  value.dispose();
});
