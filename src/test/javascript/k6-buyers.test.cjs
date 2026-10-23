const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('load/buyers.js', 'utf8').replace(/^import .*;\r?\n/gm, '')
  .replace('export const options', 'const options').replace('export function setup', 'function setup')
  .replace('export default function', 'function buyer') + '\nthis.api = {options, setup, buyer};';
const seat = '00000000-0000-0000-0000-000000000001';

function load(env = {}, reply = () => ({})) {
  const calls = [];
  const counters = {};
  const context = vm.createContext({__ENV: {CONCERT_ID: seat, ...env}, __VU: 1, __ITER: 0,
    http: {request(method, url, body, options) {
      calls.push({method, url, body, options});
      const value = reply(method, url, body);
      return {status: value.status || 200, json: () => value.body || {}};
    }, post(url) { calls.push({method: 'POST', url}); }},
    check: (value, checks) => Object.values(checks).every(check => check(value)),
    fail: message => { throw new Error(message); }, sleep() {},
    Counter: function (name) { this.add = value => { counters[name] = (counters[name] || 0) + value; }; },
  });
  vm.runInContext(source, context);
  return {api: context.api, calls, counters};
}

for (const env of [{CLIENTS: '2'}, {CLIENTS: '50000'}, {VUS: '101'}, {VUS: '0'}, {VUS: '1.5'}]) {
  test('invalid or unapproved load config ' + JSON.stringify(env), () => assert.throws(() => load(env)));
}
test('approved large preset bounds concurrency without running requests', () => {
  const value = load({CLIENTS: '50000', VUS: '100', ALLOW_LARGE: 'YES'});
  assert.equal(value.api.options.scenarios.buyers.iterations, 50000);
  assert.equal(value.api.options.scenarios.buyers.vus, 100);
  assert.equal(value.calls.length, 0);
});
test('setup rejects closed concert and absent seat fixture', () => {
  assert.throws(() => load({}, () => ({body: {status: 'DRAFT'}})).api.setup(), /ON_SALE/);
  assert.throws(() => load({}, () => ({body: {status: 'ON_SALE'}})).api.setup(), /SEAT_IDS/);
});

function flow(conflict, wrongIdentity = false, paymentStatus = 202) {
  return load({}, (method, url) => {
    if (url.endsWith('/join')) return {body: {queueToken: 'gate:token', status: 'ADMITTED'}};
    if (url.endsWith('/pay')) return {status: paymentStatus};
    if (url.endsWith('/participants')) return {status: 201, body: {id: 'participant'}};
    if (url.endsWith('/admission-token')) return {body: {admissionToken: 'signed'}};
    if (url.includes('/reserve?')) return conflict
      ? {status: 409, body: {code: conflict}}
      : {status: 201, body: {id: 'reservation', seatId: seat, status: 'ACTIVE'}};
    if (url.endsWith('/reservations/reservation')) return {
      body: {id: wrongIdentity ? 'other' : 'reservation', seatId: seat, status: 'CONFIRMED'}};
    return {};
  });
}
test('confirmed purchase uses real flow order and always logs out', () => {
  const value = flow();
  value.api.buyer({gate: 'gate', seats: [seat]});
  assert.equal(value.counters.purchases, 1);
  assert.equal(value.calls.at(-1).url.endsWith('/admission/logout'), true);
  const reserve = value.calls.find(call => call.url.includes('/reserve?'));
  assert.match(reserve.url, /participantId=participant/);
  assert.equal(reserve.options.redirects, 0);
  assert.equal(reserve.options.timeout, '5s');
  assert.equal(value.calls.filter(call => call.url.endsWith('/pay')).length, 1);
});
test('expected seat contention is counted and never charged', () => {
  const value = flow('SEAT_ALREADY_RESERVED');
  value.api.buyer({gate: 'gate', seats: [seat]});
  assert.equal(value.counters.seats_unavailable, 1);
  assert.equal(value.calls.some(call => call.url.endsWith('/pay')), false);
  assert.equal(value.calls.at(-1).url.endsWith('/admission/logout'), true);
});
test('unknown conflict and changed reservation proof fail but clean up', () => {
  for (const value of [flow('OTHER_CONFLICT'), flow(null, true)]) {
    assert.throws(() => value.api.buyer({gate: 'gate', seats: [seat]}));
    assert.equal(value.counters.purchases, undefined);
    assert.equal(value.calls.at(-1).url.endsWith('/admission/logout'), true);
  }
});

test('payment initiation requires 202 before confirming purchase', () => {
  for (const status of [200, 201, 204, 500]) {
    const value = flow(null, false, status);
    assert.throws(() => value.api.buyer({gate: 'gate', seats: [seat]}), /HTTP/);
    assert.equal(value.counters.purchases, undefined);
    assert.equal(value.calls.at(-1).url.endsWith('/admission/logout'), true);
  }
});
