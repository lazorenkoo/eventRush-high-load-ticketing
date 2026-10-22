export function mountResults(parent) {
  for (const resource of ['tickets', 'notifications']) {
    const section = document.createElement('section');
    parent.append(section);
    function add(tag, value, target = section) {
      const node = document.createElement(tag);
      node.textContent = value;
      target.append(node);
      return node;
    }
    add('h2', resource === 'tickets' ? 'My tickets' : 'My notifications');
    const refresh = add('button', 'Refresh');
    const previous = add('button', 'Previous');
    const next = add('button', 'Next');
    previous.disabled = next.disabled = true;
    const message = add('p', '');
    message.setAttribute('role', 'status');
    const table = add('table', '');
    let offset = 0;

    async function load() {
      refresh.disabled = previous.disabled = next.disabled = true;
      message.textContent = 'Loading…';
      try {
        const response = await fetch(`/api/v1/me/${resource}?limit=20&offset=${offset}`,
          {credentials: 'same-origin', cache: 'no-store'});
        if (!response.ok) {
          table.replaceChildren();
          throw new Error(response.status === 401 ? 'Session required.' : `Request failed (${response.status}).`);
        }
        const items = await response.json();
        table.replaceChildren();
        const heading = add('tr', '', table);
        const columns = resource === 'tickets' ? ['Ticket', 'Reservation', 'Seat', 'Issued'] : ['Event', 'Received'];
        for (const title of columns) add('th', title, heading);
        for (const item of items) {
          const row = add('tr', '', table);
          const values = resource === 'tickets'
            ? [item.ticketNumber, item.reservationId, item.seatId, new Date(item.issuedAt).toLocaleString('en-GB')]
            : [item.eventType, new Date(item.createdAt).toLocaleString('en-GB')];
          for (const value of values) add('td', value, row);
        }
        message.textContent = items.length ? '' : `No ${resource}.`;
        previous.disabled = offset === 0;
        next.disabled = items.length < 20 || offset >= 10000;
      } catch (error) { message.textContent = error.message; }
      finally { refresh.disabled = false; }
    }
    refresh.addEventListener('click', load);
    previous.addEventListener('click', () => { offset -= 20; load(); });
    next.addEventListener('click', () => { offset += 20; load(); });
  }
}
