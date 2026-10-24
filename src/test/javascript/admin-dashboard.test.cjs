const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/admin-dashboard.js', 'utf8')
  .replace(/^import .*;\r?\n/gm, '').replace('export function', 'function') + '\nthis.mount = mountAdminDashboard;';
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
  const elements = [], sessions = [], children = [];
  const mounts = Object.fromEntries(['mountLoadControls', 'mountActivity', 'mountJournal', 'mountEvents'].map(name =>
    [name, (api, concert) => {
      const child = {name, api, concert, closed: false}; children.push(child);
      return () => { child.closed = true; };
    }]));
  const context = vm.createContext({...mounts, document: {createElement(tag) {
    const node = new Element(); node.tag = tag; elements.push(node); return node;
  }}, adminApi(key) {
    const session = {key, closed: false, close() { this.closed = true; },
      stats(concert) { this.concert = concert; return new Promise((resolve, reject) => {
        this.resolve = resolve; this.reject = reject;
      }); }};
    sessions.push(session);
    return session;
  }});
  vm.runInContext(source, context);
  const dispose = context.mount(new Element());
  const key = elements.find(node => node.type === 'password');
  const concert = elements.find(node => node.tag === 'input' && node !== key);
  const submit = () => elements.find(node => node.tag === 'form').listeners.submit({preventDefault() {}});
  return {elements, sessions, children, key, concert, submit, dispose,
    button: text => elements.find(node => node.textContent === text)};
}

test('dashboard clears key immediately and mounts scoped controls only after authentication', async () => {
  const value = fixture();
  value.key.value = 'secret-test-key';
  value.concert.value = ' concert ';
  const pending = value.submit();
  assert.equal(value.key.value, '');
  assert.equal(value.sessions[0].key, 'secret-test-key');
  assert.equal(value.sessions[0].concert, 'concert');
  assert.equal(value.children.length, 0);
  await value.submit();
  assert.equal(value.sessions.length, 1);
  value.sessions[0].resolve({});
  await pending;
  assert.equal(value.children.length, 4);
  assert.equal(value.children.every(child => child.concert === 'concert' && child.api === value.sessions[0]), true);
  value.button('Close dashboard').listeners.click();
  assert.equal(value.sessions[0].closed, true);
  assert.equal(value.children.every(child => child.closed), true);
  assert.equal(value.button('Open dashboard').disabled, false);
  value.dispose();
});

test('closing pending login fences old result while a new session opens', async () => {
  const value = fixture();
  value.key.value = 'first';
  const old = value.submit();
  value.button('Close dashboard').listeners.click();
  value.key.value = 'second';
  value.concert.value = 'new-concert';
  const current = value.submit();
  value.sessions[1].resolve({});
  await current;
  value.sessions[0].resolve({});
  await old;
  assert.equal(value.children.length, 4);
  assert.equal(value.children.every(child => child.api === value.sessions[1]), true);
  assert.equal(value.sessions[0].closed, true);
  value.dispose();
  assert.equal(value.sessions[1].closed, true);
});

test('authentication failure and disposal never mount privileged controls', async () => {
  const value = fixture();
  const pending = value.submit();
  value.sessions[0].reject(new Error('HTTP 401'));
  await pending;
  assert.equal(value.sessions[0].closed, true);
  assert.equal(value.elements.some(node => node.textContent === 'HTTP 401'), true);
  assert.equal(value.button('Open dashboard').disabled, false);
  const late = value.submit();
  value.dispose();
  value.sessions[1].resolve({});
  await late;
  assert.equal(value.children.length, 0);
  assert.equal(value.elements[0].removed, true);
});
