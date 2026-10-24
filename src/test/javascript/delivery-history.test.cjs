const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/delivery-history.js', 'utf8')
  .replace('export function', 'function') + '\nthis.mount = mountDeliveryHistory;';
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
  const elements = [], calls = [];
  const api = Object.fromEntries(['deliveries', 'attempts', 'retry'].map(method => [method,
    (...args) => new Promise((resolve, reject) => { calls.push({method, args, resolve, reject}); })]));
  const context = vm.createContext({document: {createElement(tag) {
    const node = new Element(); node.tag = tag; elements.push(node); return node;
  }}});
  vm.runInContext(source, context);
  const dispose = context.mount(api, 'concert', 'event', new Element());
  return {elements, calls, dispose, buttons: text => elements.filter(node => node.textContent === text),
    attempts: elements.find(node => node.tag === 'pre')};
}
const flush = () => new Promise(setImmediate);
const delivery = (id, status) => ({id, status, endpointId: id + 10, httpStatus: 500, attemptCount: 2});
const history = {relayEventId: 5, deliveries: [delivery(1, 'FAILED'), delivery(2, 'SUCCESS'), delivery(3, 'PENDING')]};

test('delivery history scopes reads and only failed deliveries offer explicit retry', async () => {
  const value = fixture();
  assert.deepEqual(value.calls[0].args, ['concert', 'event']);
  value.calls[0].resolve(history);
  await flush();
  assert.equal(value.buttons('Retry failed delivery').length, 1);
  value.buttons('View attempts')[1].listeners.click();
  assert.equal(value.calls[1].method, 'attempts');
  assert.deepEqual(value.calls[1].args, ['concert', 'event', 2]);
  value.calls[1].resolve([{attemptNumber: 1, status: '<script>', httpStatus: 200, completedAt: 'now'}]);
  await flush();
  assert.match(value.attempts.textContent, /Delivery 2\nAttempt 1 — <script> — HTTP 200/);
  value.buttons('Retry failed delivery')[0].listeners.click();
  value.buttons('Retry failed delivery')[0].listeners.click();
  assert.equal(value.calls.length, 3);
  assert.equal(value.calls[2].method, 'retry');
  assert.deepEqual(value.calls[2].args, ['concert', 'event', 1]);
  value.calls[2].resolve({successful: true, httpStatus: 200});
  await flush();
  assert.equal(value.calls[3].method, 'deliveries');
  value.calls[3].resolve({...history, deliveries: [...history.deliveries, delivery(4, 'PENDING')]});
  await flush();
  assert.equal(value.attempts.textContent, '');
  assert.equal(value.elements.some(node => node.textContent.includes('creates a new delivery')), true);
  value.dispose();
});

test('failed retry is reported once without automatic retry and controls recover', async () => {
  const value = fixture();
  value.calls[0].resolve(history);
  await flush();
  value.buttons('Retry failed delivery')[0].listeners.click();
  value.calls[1].resolve({successful: false, httpStatus: 409});
  await flush();
  assert.equal(value.calls.length, 2);
  assert.equal(value.elements.some(node => node.textContent === 'Retry rejected (409).'), true);
  assert.equal(value.buttons('Refresh deliveries')[0].disabled, false);
  value.buttons('Refresh deliveries')[0].listeners.click();
  value.calls[2].reject(new Error('HTTP 503'));
  await flush();
  assert.equal(value.elements.some(node => node.textContent === 'HTTP 503'), true);
  value.dispose();
});

test('disposal fences pending retry response and prevents further actions', async () => {
  const value = fixture();
  value.calls[0].resolve(history);
  await flush();
  value.buttons('Retry failed delivery')[0].listeners.click();
  value.dispose();
  value.calls[1].resolve({successful: true, httpStatus: 200});
  await flush();
  value.buttons('Refresh deliveries')[0].listeners.click();
  assert.equal(value.calls.length, 2);
  assert.equal(value.elements[0].removed, true);
  assert.equal(value.elements.some(node => node.textContent.includes('Retry requested')), false);
});
