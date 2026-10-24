const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/hall-buyer.js', 'utf8')
  .replace(/^import .*;\r?\n/gm, '').replace('export function', 'function') + '\nthis.mount = mountHallBuyer;';
class Element {
  children = []; listeners = {}; textContent = ''; value = '';
  append(...nodes) { this.children.push(...nodes); }
  setAttribute() {}
  addEventListener(name, callback) { this.listeners[name] = callback; }
  reportValidity() { return true; }
  dispatchEvent(event) { this.event = event; }
  remove() { this.removed = true; }
}
function fixture() {
  const elements = [], calls = [], purchased = [], hashes = [];
  let select;
  const hall = {setBusy(value) { this.busy = value; }, dispose() { this.disposed = true; }};
  const context = vm.createContext({AbortController,
    history: {replaceState(a, b, hash) { hashes.push(hash); }},
    CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options.detail; } },
    document: {createElement(tag) { const node = new Element(); node.tag = tag; elements.push(node); return node; }},
    mountLiveHall(concert, parent, callback) { select = callback; return hall; },
    buyNamed(...args) { return new Promise((resolve, reject) => { calls.push({args, resolve, reject}); }); },
  });
  vm.runInContext(source, context);
  const parent = new Element();
  const dispose = context.mount({id: 'concert', admittedQueueToken: 'queue-context'}, parent, value => purchased.push(value));
  const inputs = elements.filter(node => node.tag === 'input');
  inputs[0].value = ' Ada '; inputs[1].value = ' Buyer ';
  return {elements, calls, purchased, hashes, hall, parent, dispose, select: value => select(value),
    buy: elements.find(node => node.tag === 'button'), recovery: elements.find(node => node.tag === 'a'),
    submit: () => elements.find(node => node.tag === 'form').listeners.submit({preventDefault() {}})};
}
const seat = {id: 'seat', section: 'Main', row: 'A', number: 7, price: 25};
const reservation = {id: 'reservation', seatId: 'seat', status: 'ACTIVE'};

test('selected seat drives purchase with trimmed names and freezes controls against double submit', async () => {
  const value = fixture();
  assert.equal(value.buy.disabled, true);
  await value.submit();
  assert.equal(value.calls.length, 0);
  value.select(seat);
  assert.equal(value.buy.disabled, false);
  const pending = value.submit();
  await value.submit();
  assert.equal(value.calls.length, 1);
  const [concert, id, names, options] = value.calls[0].args;
  assert.equal(id, 'seat');
  assert.equal(names.firstName, 'Ada');
  assert.equal(names.lastName, 'Buyer');
  assert.equal(options.admittedQueueToken, concert.admittedQueueToken);
  assert.equal(value.hall.busy, true);
  assert.equal(value.buy.disabled, true);
  options.onReserved(reservation);
  assert.equal(value.hashes[0], '#reservation=reservation');
  assert.equal(value.recovery.hidden, false);
  value.calls[0].resolve({...reservation, status: 'CONFIRMED'});
  await pending;
  assert.equal(value.purchased[0].status, 'CONFIRMED');
  assert.equal(value.hall.busy, false);
  assert.equal(value.buy.disabled, true);
  value.dispose();
});

test('lost availability disables purchase and payment failure retains reservation recovery', async () => {
  const value = fixture();
  value.select(seat);
  value.select(null);
  await value.submit();
  assert.equal(value.calls.length, 0);
  value.select(seat);
  const pending = value.submit();
  value.calls[0].args[3].onReserved(reservation);
  value.calls[0].reject(new Error('Payment still pending'));
  await pending;
  value.recovery.listeners.click({preventDefault() {}});
  assert.equal(value.parent.event.detail.id, 'reservation');
  assert.equal(value.purchased.length, 0);
  assert.equal(value.elements.some(node => node.textContent === 'Payment still pending'), true);
  value.dispose();
});

test('disposal aborts pending purchase and ignores late callbacks and completion', async () => {
  const value = fixture();
  value.select(seat);
  const pending = value.submit();
  const options = value.calls[0].args[3];
  value.dispose();
  assert.equal(options.signal.aborted, true);
  assert.equal(value.hall.disposed, true);
  options.onReserved(reservation);
  options.onStatus('Purchased');
  value.calls[0].resolve(reservation);
  await pending;
  assert.equal(value.purchased.length, 0);
  assert.equal(value.hashes.length, 0);
  assert.equal(value.elements[0].removed, true);
});
