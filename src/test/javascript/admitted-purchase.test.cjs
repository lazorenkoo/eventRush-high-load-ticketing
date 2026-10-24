const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/purchase-flow.js', 'utf8')
  .replace('export async function', 'async function') + '\nthis.buy = buyNamed;';
const gate = '11111111-1111-4111-8111-111111111111';
const token = gate + ':22222222-2222-4222-8222-222222222222';
function fixture(change = () => {}) {
  const calls = [], reserved = [];
  const context = vm.createContext({AbortSignal, Date, setTimeout, clearTimeout,
    async fetch(path, options) {
      calls.push({path, options});
      const response = {status: 200, body: {}};
      if (path.startsWith('/api/v1/queue/')) response.body = {queueToken: token, status: 'ACTIVE'};
      if (path.endsWith('/participants')) response.body = {id: 'participant'};
      if (path.includes('/reserve?')) Object.assign(response,
        {status: 201, body: {id: 'reservation', seatId: 'seat', status: 'ACTIVE'}});
      if (path.endsWith('/pay')) Object.assign(response, {status: 202, body: {reservationId: 'reservation'}});
      if (path.endsWith('/reservations/reservation')) response.body = {id: 'reservation', seatId: 'seat', status: 'CONFIRMED'};
      change(path, response);
      return {status: response.status, json: async () => response.body};
    },
  });
  vm.runInContext(source, context);
  return {calls, reserved, run: (admittedQueueToken = token) => context.buy({id: 'concert', crowdgateGateId: gate},
    'seat', {firstName: 'Ada', lastName: 'Buyer'}, {admittedQueueToken, onReserved: value => reserved.push(value)})};
}

test('active admission is checked before registering and never joins or exchanges twice', async () => {
  const value = fixture();
  assert.equal((await value.run()).status, 'CONFIRMED');
  assert.equal(value.calls.length, 5);
  assert.equal(value.calls[0].options.method, 'GET');
  assert.equal(value.calls[0].path, '/api/v1/queue/' + encodeURIComponent(token));
  assert.equal(value.calls.some(call => /join|exchange|admission-token/.test(call.path)), false);
  const participant = JSON.parse(value.calls[1].options.body);
  assert.equal(participant.queueToken, token);
  assert.equal(participant.seatId, 'seat');
  assert.equal(participant.firstName, 'Ada');
  assert.equal(value.reserved[0].id, 'reservation');
  for (const call of value.calls) assert.equal(call.options.credentials, 'same-origin');
});

test('malformed and wrong-gate admission tokens send no requests', async () => {
  for (const invalid of ['', null, 123, 'gate:token', token.replace('11111111', '33333333')]) {
    const value = fixture();
    await assert.rejects(value.run(invalid), /Invalid admitted queue token/);
    assert.equal(value.calls.length, 0);
  }
});

test('nonactive changed or inaccessible queue proof cannot register reserve or charge', async () => {
  for (const invalid of ['WAITING', 'ADMITTED', 'EXPIRED', 'different-token', 'HTTP 401']) {
    const value = fixture((path, response) => {
      if (!path.startsWith('/api/v1/queue/')) return;
      if (invalid === 'different-token') response.body.queueToken = token.replace(/2/g, '3');
      else if (invalid === 'HTTP 401') response.status = 401;
      else response.body.status = invalid;
    });
    await assert.rejects(value.run());
    assert.equal(value.calls.length, 1);
    assert.equal(value.reserved.length, 0);
  }
});

test('active queue proof still requires backend participant and reservation authorization', async () => {
  for (const denied of ['/participants', '/reserve?participantId=participant']) {
    const value = fixture((path, response) => { if (path.endsWith(denied)) response.status = 403; });
    await assert.rejects(value.run(), /403/);
    assert.equal(value.calls.some(call => call.path.endsWith('/pay')), false);
  }
});
