const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/purchase-flow.js', 'utf8')
  .replace('export async function', 'async function') + '\nthis.buy = buyNamed;';

function fixture(change = () => {}) {
  let clock = 0;
  const calls = [];
  const reserved = [];
  const statuses = [];
  const context = vm.createContext({AbortSignal, Date: {now: () => clock},
    setTimeout(callback) { clock += 1000; queueMicrotask(callback); return 1; }, clearTimeout() {},
    async fetch(path, options) {
      calls.push({path, options});
      let response = {status: 200, body: {}};
      if (path.endsWith('/join')) response.body = {queueToken: 'gate:token', status: 'ADMITTED'};
      if (path.endsWith('/participants')) response.body = {id: 'participant'};
      if (path.endsWith('/admission-token')) response.body = {admissionToken: 'signed'};
      if (path.includes('/reserve?')) response = {status: 201, body: {id: 'reservation', seatId: 'seat', status: 'ACTIVE'}};
      if (path.endsWith('/pay')) response = {status: 202, body: {reservationId: 'reservation'}};
      if (path.endsWith('/reservations/reservation')) response.body = {id: 'reservation', seatId: 'seat', status: 'CONFIRMED'};
      change(path, response);
      return {status: response.status, json: async () => response.body};
    },
  });
  vm.runInContext(source, context);
  return {calls, reserved, statuses, run: signal => context.buy({id: 'concert', crowdgateGateId: 'gate'}, 'seat',
    {firstName: 'First', lastName: 'Last'}, {signal, onReserved: value => reserved.push(value),
      onStatus: value => statuses.push(value)})};
}

test('named purchase preserves credentials order names and recovery callback', async () => {
  const value = fixture();
  assert.equal((await value.run()).status, 'CONFIRMED');
  assert.equal(value.calls.length, 7);
  assert.equal(value.reserved[0].id, 'reservation');
  assert.equal(value.statuses.at(-1), 'Purchased');
  const participant = JSON.parse(value.calls[1].options.body);
  assert.equal(participant.firstName, 'First');
  assert.equal(participant.seatId, 'seat');
  for (const call of value.calls) {
    assert.equal(call.options.credentials, 'same-origin');
    assert.equal(call.options.cache, 'no-store');
    assert.ok(call.options.signal instanceof AbortSignal);
  }
});
for (const status of [409, 429, 503]) {
  test('reservation rejection ' + status + ' cannot start payment', async () => {
    const value = fixture((path, response) => {
      if (path.includes('/reserve?')) Object.assign(response, {status, body: {code: 'SEAT_ALREADY_RESERVED'}});
    });
    await assert.rejects(value.run(), /Seat unavailable/);
    assert.equal(value.calls.some(call => call.path.endsWith('/pay')), false);
    assert.equal(value.reserved.length, 0);
  });
}
test('wrong gate and changing reservation/payment identities fail safely', async () => {
  for (const [suffix, field] of [['/join', 'queueToken'], ['/pay', 'reservationId'],
    ['/reservations/reservation', 'id']]) {
    const value = fixture((path, response) => { if (path.endsWith(suffix)) response.body[field] = 'other'; });
    await assert.rejects(value.run());
    assert.notEqual(value.statuses.at(-1), 'Purchased');
  }
});
test('aborted purchase sends no request', async () => {
  const controller = new AbortController();
  controller.abort(new Error('Stopped'));
  const value = fixture();
  await assert.rejects(value.run(controller.signal), /Stopped/);
  assert.equal(value.calls.length, 0);
});
test('pending payment keeps reservation recovery and bounded heartbeats', async () => {
  const value = fixture((path, response) => {
    if (path.endsWith('/reservations/reservation')) response.body.status = 'ACTIVE';
  });
  await assert.rejects(value.run(), /Payment still pending/);
  assert.equal(value.reserved.length, 1);
  assert.equal(value.calls.filter(call => call.path.endsWith('/heartbeat')).length, 2);
  assert.equal(value.calls.filter(call => call.path.endsWith('/pay')).length, 1);
});
