const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/load-controls.js', 'utf8')
  .replace('export function', 'function') + '\nthis.mount = mountLoadControls;';
class Element {
  children = [];
  listeners = {};
  textContent = '';
  append(...nodes) { this.children.push(...nodes); }
  setAttribute() {}
  addEventListener(name, callback) { this.listeners[name] = callback; }
  remove() { this.removed = true; }
}
function fixture(upload) {
  const elements = [];
  const timers = new Map();
  const calls = [];
  let state = 'READY';
  const api = {
    async upload(...args) { calls.push(['upload', ...args]); return upload ? upload() : {id: 'run', status: 'READY', participantCount: 10}; },
    async run(concert, run, action = 'progress') {
      calls.push([action, concert, run]);
      if (action === 'start') state = 'RUNNING';
      if (action === 'stop') state = 'STOPPED';
      return {status: state, scope: 'TEST_RUN', participantCount: 10, pending: 8, running: 2,
        succeeded: 0, unavailable: 0, failed: 0, cancelled: state === 'STOPPED' ? 10 : 0};
    },
  };
  let timerId = 0;
  const context = vm.createContext({document: {createElement() { const node = new Element(); elements.push(node); return node; }},
    setTimeout(callback) { timers.set(++timerId, callback); return timerId; }, clearTimeout(id) { timers.delete(id); }});
  vm.runInContext(source, context);
  const dispose = context.mount(api, 'concert', new Element());
  const button = text => elements.find(node => node.textContent === text);
  elements.find(node => node.type === 'file').files = [{size: 100}];
  return {calls, timers, elements, dispose, button};
}

test('upload stays READY until explicit start and stop clears polling', async () => {
  const value = fixture();
  assert.equal(value.button('Start test').disabled, true);
  await value.button('Upload CSV').listeners.click();
  assert.deepEqual(value.calls.map(call => call[0]), ['upload']);
  assert.equal(value.button('Start test').disabled, false);
  await value.button('Start test').listeners.click();
  assert.deepEqual(value.calls.map(call => call[0]), ['upload', 'start', 'progress']);
  assert.equal(value.timers.size, 1);
  assert.equal(value.button('Stop test').disabled, false);
  await value.button('Stop test').listeners.click();
  assert.equal(value.calls.at(-2)[0], 'stop');
  assert.equal(value.timers.size, 0);
  assert.equal(value.button('Start test').disabled, true);
});
test('double upload is fenced and disposed component ignores late completion', async () => {
  let resolve;
  const value = fixture(() => new Promise(done => { resolve = done; }));
  const pending = value.button('Upload CSV').listeners.click();
  await value.button('Upload CSV').listeners.click();
  assert.equal(value.calls.length, 1);
  value.dispose();
  resolve({id: 'run', status: 'READY', participantCount: 10});
  await pending;
  assert.equal(value.timers.size, 0);
  assert.equal(value.elements[0].removed, true);
  assert.equal(value.elements.some(node => node.textContent.includes('Run run')), false);
});
