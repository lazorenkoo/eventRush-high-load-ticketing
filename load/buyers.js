import http from 'k6/http';
import {check, fail, sleep} from 'k6';
import {Counter} from 'k6/metrics';

const base = (__ENV.BASE_URL || 'http://localhost:8082').replace(/\/$/, '');
const concertId = __ENV.CONCERT_ID;
const clients = Number(__ENV.CLIENTS || 1000);
const vus = Number(__ENV.VUS || 10);
if (!concertId || ![1, 10, 1000, 10000, 50000].includes(clients)) throw new Error('CONCERT_ID and valid CLIENTS required');
if (!Number.isInteger(vus) || vus < 1 || vus > 100) throw new Error('VUS must be 1..100');
if (clients >= 10000 && __ENV.ALLOW_LARGE !== 'YES') throw new Error('Large runs require ALLOW_LARGE=YES');
const purchased = new Counter('purchases');
const unavailable = new Counter('seats_unavailable');
export const options = {
  scenarios: {buyers: {executor: 'shared-iterations', vus: Math.min(vus, clients),
    iterations: clients, maxDuration: __ENV.MAX_DURATION || '30m', gracefulStop: '30s'}},
  thresholds: {checks: ['rate==1']},
};

function request(method, path, body, expected = [200, 201, 204]) {
  const response = http.request(method, base + path, body === undefined ? null : JSON.stringify(body), {
    headers: {'Origin': __ENV.ORIGIN || base, 'Content-Type': 'application/json'},
    redirects: 0, timeout: '5s', tags: {name: path.replace(/[0-9a-f-]{36}/g, ':id')},
  });
  if (!check(response, {'expected HTTP outcome': r => expected.includes(r.status)})) fail(`HTTP ${response.status}`);
  return response;
}

export function setup() {
  const concert = request('GET', `/api/v1/concerts/${concertId}`).json();
  if (concert.status !== 'ON_SALE') fail('Concert must be ON_SALE');
  if (!__ENV.SEAT_IDS) fail('SEAT_IDS must contain comma-separated seat UUIDs from the prepared concert');
  const seats = __ENV.SEAT_IDS.split(',');
  if (!seats.length || seats.some(id => !/^[0-9a-f-]{36}$/.test(id))) fail('Invalid SEAT_IDS');
  return {gate: concert.crowdgateGateId, seats};
}

export default function (data) {
  const seat = data.seats[(__VU + __ITER) % data.seats.length];
  try {
    const entry = request('POST', `/api/v1/gates/${data.gate}/join`).json();
    const token = entry.queueToken;
    if (typeof token !== 'string' || !token.startsWith(data.gate + ':')) fail('Invalid queue token');
    const participant = request('POST', '/api/v1/participants', {
      concertId, seatId: seat, queueToken: token, firstName: 'Load', lastName: `Buyer ${__VU} ${__ITER}`,
    }).json();
    let current = entry;
    const deadline = Date.now() + 600000;
    while (current.status === 'WAITING' && Date.now() < deadline) {
      sleep(1);
      current = request('GET', `/api/v1/queue/${token}`).json();
    }
    if (current.status !== 'ADMITTED') fail('Admission timeout or unexpected queue state');
    const admission = request('POST', `/api/v1/queue/${token}/admission-token`).json();
    request('POST', '/api/v1/admission/exchange', {concertId, admissionToken: admission.admissionToken});
    const reserved = request('POST', `/api/v1/seats/${seat}/reserve?participantId=${participant.id}`, undefined, [201, 409]);
    if (reserved.status === 409) {
      if (reserved.json().code !== 'SEAT_ALREADY_RESERVED') fail('Unexpected reservation conflict');
      unavailable.add(1);
      return;
    }
    const reservation = reserved.json();
    if (reservation.seatId !== seat || reservation.status !== 'ACTIVE') fail('Invalid reservation');
    const path = `/api/v1/reservations/${reservation.id}`;
    request('POST', path + '/pay', {mode: 'SUCCESS'}, [202]);
    const paymentDeadline = Date.now() + 90000;
    let confirmed = false;
    let heartbeatAt = Date.now() + 30000;
    while (Date.now() < paymentDeadline) {
      const value = request('GET', path).json();
      if (value.id !== reservation.id || value.seatId !== seat) fail('Reservation identity changed');
      if (value.status === 'CONFIRMED') { confirmed = true; break; }
      if (value.status !== 'ACTIVE') fail('Reservation was not confirmed');
      if (Date.now() >= heartbeatAt) {
        request('POST', '/api/v1/admission/heartbeat');
        heartbeatAt = Date.now() + 30000;
      }
      sleep(1);
    }
    if (!check(confirmed, {'purchase confirmed': value => value})) fail('Payment timeout');
    purchased.add(1);
  } finally {
    http.post(base + '/api/v1/admission/logout', null, {headers: {Origin: __ENV.ORIGIN || base}, timeout: '5s'});
  }
}
