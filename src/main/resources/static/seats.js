export function mountSeats(concert, parent) {
  const panel = document.createElement('section');
  parent.append(panel);
  const abort = new AbortController();
  let offset = 0;
  let conflicts = 0;
  function add(tag, value, target = panel) {
    const node = document.createElement(tag);
    node.textContent = value;
    target.append(node);
    return node;
  }
  add('h3', 'Seats');
  const refresh = add('button', 'Refresh seats');
  const previous = add('button', 'Previous');
  const next = add('button', 'Next');
  const counter = add('p', 'Conflicts: 0');
  const message = add('p', '');
  message.setAttribute('role', 'status');
  const table = add('table', '');

  async function request(path, method = 'GET') {
    const response = await fetch(path, {method, credentials: 'same-origin',
      cache: 'no-store', signal: abort.signal});
    if (response.status === 409) counter.textContent = `Conflicts: ${++conflicts}`;
    if (!response.ok) throw new Error(`Request failed (${response.status}).`);
    return response.json();
  }
  function fail(error) {
    if (!abort.signal.aborted) message.textContent = error.message;
  }
  async function reserve(seat, button) {
    button.disabled = true;
    try {
      const result = await request(`/api/v1/seats/${seat.id}/reserve`, 'POST');
      parent.dispatchEvent(new CustomEvent('reserved', {detail: result}));
      await load();
    } catch (error) {
      fail(error);
      button.disabled = false;
    }
  }
  async function load() {
    refresh.disabled = previous.disabled = next.disabled = true;
    message.textContent = 'Loading seats…';
    try {
      const items = await request(`/api/v1/concerts/${concert.id}/seats?limit=100&offset=${offset}`);
      table.replaceChildren();
      const heading = add('tr', '', table);
      for (const title of ['Section', 'Row', 'Seat', 'Price', 'Status', 'Action']) add('th', title, heading);
      for (const seat of items) {
        const row = add('tr', '', table);
        for (const value of [seat.section, seat.row, seat.number, seat.price, seat.status]) add('td', value, row);
        const cell = add('td', '', row);
        const button = add('button', 'Reserve', cell);
        button.disabled = seat.status !== 'AVAILABLE';
        button.addEventListener('click', () => reserve(seat, button));
      }
      message.textContent = items.length ? '' : 'No seats.';
      previous.disabled = offset === 0;
      next.disabled = items.length < 100 || offset >= 100000;
    } catch (error) { fail(error); }
    finally { refresh.disabled = false; }
  }
  refresh.addEventListener('click', load);
  parent.addEventListener('reservationchanged', load);
  previous.addEventListener('click', () => { offset -= 100; load(); });
  next.addEventListener('click', () => { offset += 100; load(); });
  load();
  return () => { abort.abort(); parent.removeEventListener('reservationchanged', load); panel.remove(); };
}
