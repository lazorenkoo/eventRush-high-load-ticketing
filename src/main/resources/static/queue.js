export function mountQueue(concert, parent) {
  const button = document.createElement('button');
  button.textContent = 'Join queue';
  button.type = 'button';
  button.disabled = concert.status !== 'ON_SALE' || Date.parse(concert.saleStartsAt) > Date.now();
  const status = document.createElement('p');
  parent.append(button, status);
  const abort = new AbortController();
  let stream;
  let heartbeat;
  let exchanging = false;

  async function request(path, body) {
    const response = await fetch(path, {method: 'POST', credentials: 'same-origin',
      signal: abort.signal, headers: {'Content-Type': 'application/json'},
      body: body === undefined ? undefined : JSON.stringify(body)});
    if (!response.ok) throw new Error(`Request failed (${response.status}).`);
    return response.status === 204 ? null : response.json();
  }

  function fail(error) {
    if (abort.signal.aborted) return;
    stream?.close();
    clearInterval(heartbeat);
    status.textContent = error.message;
    button.disabled = false;
    exchanging = false;
  }

  function admitted() {
    status.textContent = 'Admitted';
    stream?.close();
    clearInterval(heartbeat);
    heartbeat = setInterval(() => request('/api/v1/admission/heartbeat').catch(fail), 30_000);
    parent.dispatchEvent(new CustomEvent('admitted', {detail: concert}));
  }

  async function update(entry, token) {
    if (abort.signal.aborted || exchanging) return;
    status.textContent = `${entry.status} — position ${entry.position}, queue ${entry.queueSize}`;
    if (entry.status === 'ADMITTED') {
      exchanging = true;
      stream?.close();
      if (!token) {
        const issued = await request(`/api/v1/queue/${encodeURIComponent(entry.queueToken)}/admission-token`);
        token = issued.admissionToken;
      }
      await request('/api/v1/admission/exchange', {concertId: concert.id, admissionToken: token});
      if (!abort.signal.aborted) admitted();
    } else if (entry.status === 'ACTIVE') {
      const response = await fetch(`/api/v1/concerts/${concert.id}/seats?limit=1`,
        {credentials: 'same-origin', cache: 'no-store', signal: abort.signal});
      if (!response.ok) throw new Error('Session unavailable. Wait for the gate session to expire, then retry.');
      if (!abort.signal.aborted) admitted();
    } else if (entry.status !== 'WAITING') {
      throw new Error(`Queue status: ${entry.status}`);
    }
  }

  button.addEventListener('click', async () => {
    button.disabled = true;
    status.textContent = 'Joining…';
    try {
      const entry = await request(`/api/v1/gates/${concert.crowdgateGateId}/join`);
      await update(entry);
      if (entry.status === 'WAITING' && !abort.signal.aborted) {
        stream = new EventSource(`/api/v1/queue/${encodeURIComponent(entry.queueToken)}/events`);
        stream.addEventListener('queue', event => {
          try {
            const value = JSON.parse(event.data);
            update(value.entry, value.admissionToken).catch(fail);
          } catch (error) { fail(error); }
        });
        stream.onerror = () => fail(new Error('Queue disconnected. Retry.'));
      }
    } catch (error) { fail(error); }
  });

  return () => {
    abort.abort();
    stream?.close();
    clearInterval(heartbeat);
  };
}
