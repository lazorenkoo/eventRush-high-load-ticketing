export function mountReservation(reservation, parent) {
  const panel = document.createElement('section');
  parent.append(panel);
  const abort = new AbortController();
  let current = reservation;
  let busy = false;
  let pending = false;
  let reading = false;
  function add(tag, value) {
    const node = document.createElement(tag);
    node.textContent = value;
    panel.append(node);
    return node;
  }
  add('h3', 'Reservation');
  add('p', reservation.id);
  const state = add('p', '');
  const remaining = add('p', '');
  const mode = add('select', '');
  mode.setAttribute('aria-label', 'Payment mode');
  for (const value of ['SUCCESS', 'FAILED', 'DELAYED_SUCCESS']) {
    const option = document.createElement('option');
    option.value = option.textContent = value;
    mode.append(option);
  }
  const pay = add('button', 'Pay');
  const cancel = add('button', 'Cancel');
  const refresh = add('button', 'Refresh reservation');
  const message = add('p', '');
  message.setAttribute('role', 'status');

  function render() {
    const seconds = Math.max(0, Math.ceil((Date.parse(current.expiresAt) - Date.now()) / 1000));
    state.textContent = `Status: ${current.status}`;
    remaining.textContent = current.status === 'ACTIVE' ? `Remaining: ${seconds}s` : '';
    pay.disabled = busy || pending || current.status !== 'ACTIVE' || seconds === 0;
    cancel.disabled = busy || current.status !== 'ACTIVE';
    mode.disabled = busy || pending || current.status !== 'ACTIVE';
  }
  async function request(action = '', body) {
    const response = await fetch(`/api/v1/reservations/${reservation.id}${action}`, {
      method: action ? 'POST' : 'GET', credentials: 'same-origin', cache: 'no-store',
      signal: abort.signal, headers: {'Content-Type': 'application/json'},
      body: body === undefined ? undefined : JSON.stringify(body)});
    if (!response.ok) throw new Error(`Request failed (${response.status}).`);
    return response.status === 204 ? null : response.json();
  }
  function fail(error) {
    if (!abort.signal.aborted) message.textContent = error.message;
  }
  async function read() {
    if (reading || busy || abort.signal.aborted) return;
    reading = true;
    try {
      const value = await request();
      if (value.status !== current.status) {
        message.textContent = '';
        parent.dispatchEvent(new Event('reservationchanged'));
      }
      current = value;
      render();
    } catch (error) { fail(error); }
    finally { reading = false; }
  }
  async function act(action, body) {
    if (busy) return;
    busy = true;
    render();
    try {
      const result = await request(action, body);
      pending = result?.status === 'PENDING';
      message.textContent = result ? `Payment: ${result.status}` : 'Cancelled';
    } catch (error) { fail(error); }
    finally { busy = false; render(); }
    await read();
  }
  pay.addEventListener('click', () => act('/pay', {mode: mode.value}));
  cancel.addEventListener('click', () => act('/cancel'));
  refresh.addEventListener('click', read);
  const clock = setInterval(render, 1000);
  const poll = setInterval(read, 2000);
  render();
  return () => { abort.abort(); clearInterval(clock); clearInterval(poll); panel.remove(); };
}
