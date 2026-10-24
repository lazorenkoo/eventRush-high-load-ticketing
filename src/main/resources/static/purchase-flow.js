export async function buyNamed(concert, seatId, names, {
  signal, onStatus = () => {}, onReserved = () => {}, admittedQueueToken,
} = {}) {
  const deadline = Date.now() + 600000;
  async function request(method, path, body, accepted = [200, 201, 204]) {
    signal?.throwIfAborted();
    const timeout = AbortSignal.timeout(5000);
    const response = await fetch(path, {method, credentials: 'same-origin', cache: 'no-store',
      signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
      headers: {'Content-Type': 'application/json'}, body: body === undefined ? undefined : JSON.stringify(body)});
    if (!accepted.includes(response.status)) {
      let code;
      try { code = (await response.json()).code; } catch { /* Use the HTTP status for non-JSON errors. */ }
      throw new Error(code === 'SEAT_ALREADY_RESERVED' ? 'Seat unavailable.' : `Request failed (${response.status}).`);
    }
    return response.status === 204 ? null : response.json();
  }
  async function pause() {
    signal?.throwIfAborted();
    await new Promise((resolve, reject) => {
      const done = () => { signal?.removeEventListener('abort', abort); resolve(); };
      const timer = setTimeout(done, 1000);
      const abort = () => { clearTimeout(timer); reject(signal.reason); };
      signal?.addEventListener('abort', abort, {once: true});
    });
  }
  if (admittedQueueToken !== undefined && (typeof admittedQueueToken !== 'string'
      || !/^[0-9a-f-]{36}:[0-9a-f-]{36}$/i.test(admittedQueueToken)
      || !admittedQueueToken.startsWith(concert.crowdgateGateId + ':'))) {
    throw new Error('Invalid admitted queue token.');
  }
  onStatus(admittedQueueToken ? 'Checking admission' : 'Joining queue');
  const entry = admittedQueueToken
    ? await request('GET', `/api/v1/queue/${encodeURIComponent(admittedQueueToken)}`)
    : await request('POST', `/api/v1/gates/${concert.crowdgateGateId}/join`);
  const token = entry.queueToken;
  if (typeof token !== 'string' || !token.startsWith(concert.crowdgateGateId + ':')) {
    throw new Error('Invalid queue response.');
  }
  if (admittedQueueToken && (token !== admittedQueueToken || entry.status !== 'ACTIVE')) {
    throw new Error('Admission expired. Re-enter the sale before buying.');
  }
  const register = () => request('POST', '/api/v1/participants', {
    concertId: concert.id, seatId, queueToken: token, firstName: names.firstName, lastName: names.lastName,
  });
  let participant = typeof seatId === 'function' ? null : await register();
  let current = entry;
  while (current.status === 'WAITING' && Date.now() < deadline) {
    onStatus(`Waiting in queue — position ${current.position}`);
    await pause();
    current = await request('GET', `/api/v1/queue/${encodeURIComponent(token)}`);
  }
  if (!admittedQueueToken) {
    if (current.status !== 'ADMITTED') throw new Error('Queue admission unavailable.');
    onStatus('Entering sale');
    const admission = await request('POST', `/api/v1/queue/${encodeURIComponent(token)}/admission-token`);
    await request('POST', '/api/v1/admission/exchange', {concertId: concert.id, admissionToken: admission.admissionToken});
  }
  if (typeof seatId === 'function') {
    onStatus('Selecting seat');
    seatId = await seatId(request);
    if (typeof seatId !== 'string' || !seatId) throw new Error('Seat unavailable.');
    participant = await register();
  }
  onStatus('Reserving seat');
  const reservation = await request('POST', `/api/v1/seats/${seatId}/reserve?participantId=${participant.id}`);
  if (reservation.seatId !== seatId || reservation.status !== 'ACTIVE') throw new Error('Invalid reservation response.');
  onReserved(reservation);
  onStatus('Processing payment');
  const path = `/api/v1/reservations/${reservation.id}`;
  const payment = await request('POST', path + '/pay', {mode: 'SUCCESS'}, [202]);
  if (payment.reservationId !== reservation.id) throw new Error('Invalid payment response.');
  const paymentDeadline = Date.now() + 90000;
  let heartbeatAt = Date.now() + 30000;
  while (Date.now() < paymentDeadline) {
    const result = await request('GET', path);
    if (result.id !== reservation.id || result.seatId !== seatId) throw new Error('Reservation identity changed.');
    if (result.status === 'CONFIRMED') {
      onStatus('Purchased');
      return result;
    }
    if (result.status !== 'ACTIVE') throw new Error(`Reservation ${result.status.toLowerCase()}.`);
    if (Date.now() >= heartbeatAt) {
      await request('POST', '/api/v1/admission/heartbeat');
      heartbeatAt = Date.now() + 30000;
    }
    await pause();
  }
  throw new Error('Payment still pending. Open the reservation to check its result.');
}
