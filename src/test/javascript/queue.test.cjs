const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/queue.js', 'utf8')
  .replace('export function', 'function') + '\nthis.mount = mountQueue;';
const gate = '11111111-1111-4111-8111-111111111111';
const token = gate + ':22222222-2222-4222-8222-222222222222';
class Element {
  children = []; listeners = {};
  append(...nodes) { this.children.push(...nodes); }
  addEventListener(name, callback) { this.listeners[name] = callback; }
  dispatchEvent(event) { this.events.push(event); }
  events = [];
}
function fixture(status = 'ADMITTED', change = () => {}) {
  const parent = new Element(), calls = [], intervals = new Map();
  let timer = 0;
  const context = vm.createContext({AbortController, Date, Event,
    CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options.detail; } },
    document: {createElement: () => new Element()},
    setInterval(callback) { intervals.set(++timer, callback); return timer; },
    clearInterval(id) { intervals.delete(id); },
    async fetch(path, options) {
      calls.push({path, options});
      const response = {status: 200, body: path.endsWith('/join')
        ? {queueToken: token, status, position: 0, queueSize: 0} : {admissionToken: 'signed'}};
      change(path, response);
      return {ok: response.status === 200, status: response.status, json: async () => response.body};
    },
  });
  vm.runInContext(source, context);
  const dispose = context.mount({id: 'concert', name: 'Hall', crowdgateGateId: gate,
    status: 'ON_SALE', saleStartsAt: '2026-01-01T00:00:00Z'}, parent);
  return {parent, calls, intervals, dispose, join: () => parent.children[0].listeners.click()};
}

test('fresh admission event carries concert and queue context only after exchange', async () => {
  const value = fixture();
  await value.join();
  assert.deepEqual(value.calls.map(call => call.path), ['/api/v1/gates/' + gate + '/join',
    '/api/v1/queue/' + encodeURIComponent(token) + '/admission-token', '/api/v1/admission/exchange']);
  assert.equal(value.parent.events.length, 1);
  assert.equal(value.parent.events[0].type, 'admitted');
  assert.equal(value.parent.events[0].detail.id, 'concert');
  assert.equal(value.parent.events[0].detail.name, 'Hall');
  assert.equal(value.parent.events[0].detail.admittedQueueToken, token);
  assert.equal(value.intervals.size, 1);
  value.dispose();
  assert.equal(value.intervals.size, 0);
});

test('ACTIVE queue reuses context only after protected seat request succeeds', async () => {
  for (const denied of [false, true]) {
    const value = fixture('ACTIVE', (path, response) => {
      if (denied && path.includes('/seats?')) response.status = 401;
    });
    await value.join();
    assert.equal(value.calls.length, 2);
    assert.match(value.calls[1].path, /seats\?limit=1$/);
    assert.equal(value.calls[1].options.credentials, 'same-origin');
    assert.equal(value.parent.events[0].type, denied ? 'admissionlost' : 'admitted');
    assert.equal(value.intervals.size, denied ? 0 : 1);
    value.dispose();
  }
});

test('wrong queue identity and failed exchange never emit admitted context', async () => {
  for (const failure of ['wrong-gate', 'truncated', 'exchange']) {
    const value = fixture('ADMITTED', (path, response) => {
      if (path.endsWith('/join') && failure !== 'exchange') {
        response.body.queueToken = failure === 'truncated' ? 'bad' : token.replace('11111111', '33333333');
      }
      if (path.endsWith('/exchange') && failure === 'exchange') response.status = 403;
    });
    await value.join();
    assert.equal(value.parent.events.some(event => event.type === 'admitted'), false);
    assert.equal(value.intervals.size, 0);
    value.dispose();
  }
});
