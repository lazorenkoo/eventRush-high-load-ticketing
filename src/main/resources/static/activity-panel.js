export function mountActivity(api, concertId, parent) {
  const section = document.createElement('section');
  const node = (tag, text, target = section) => {
    const value = document.createElement(tag);
    if (text !== undefined) value.textContent = text;
    target.append(value);
    return value;
  };
  node('h3', 'Purchase activity');
  const search = node('input');
  Object.assign(search, {type: 'search', maxLength: 100, placeholder: 'Name, seat or operation ID'});
  search.setAttribute('aria-label', 'Search purchase activity');
  function select(label, values) {
    const field = node('select');
    field.setAttribute('aria-label', label);
    for (const [value, text] of values) {
      const option = node('option', text, field);
      option.value = value;
    }
    return field;
  }
  const source = select('Participant source', [['', 'All sources'], ['MANUAL', 'Manual'], ['TEST', 'Test']]);
  const state = select('Purchase state', [['', 'All states'], ...['REGISTERED', 'RESERVED', 'PAYING',
    'PAYMENT_FAILED', 'PURCHASED', 'EXPIRED', 'CANCELLED'].map(value => [value, value])]);
  const refresh = node('button', 'Search / Refresh');
  const previous = node('button', 'Previous');
  const next = node('button', 'Next');
  for (const button of [refresh, previous, next]) button.type = 'button';
  const status = node('p');
  status.setAttribute('role', 'status');
  const table = node('table');
  const header = node('tr', undefined, node('thead', undefined, table));
  for (const title of ['Name', 'Source', 'Seat', 'State', 'Payment', 'Ticket', 'Participant ID', 'Reservation ID']) {
    node('th', title, header);
  }
  const body = node('tbody', undefined, table);
  parent.append(section);
  let offset = 0;
  let generation = 0;
  let timer;
  let disposed = false;
  async function load(reset = false) {
    clearTimeout(timer);
    if (reset) offset = 0;
    const version = ++generation;
    refresh.disabled = previous.disabled = next.disabled = true;
    let repeat = false;
    try {
      const items = await api.activity(concertId, {q: search.value, source: source.value, state: state.value, limit: 20, offset});
      if (disposed || version !== generation) return;
      body.replaceChildren();
      for (const item of items) {
        const row = node('tr', undefined, body);
        for (const value of [`${item.firstName} ${item.lastName}`, item.source, item.seatNumber, item.state,
          item.paymentStatus, item.ticketNumber, item.id, item.reservationId]) node('td', value ?? '—', row);
      }
      status.textContent = `${items.length} participants — offset ${offset} — updated ${new Date().toLocaleTimeString('en-GB')}`;
      repeat = true;
      previous.disabled = offset === 0;
      next.disabled = items.length < 20 || offset >= 50000;
    } catch (error) { if (!disposed && version === generation) status.textContent = error.message; }
    finally {
      if (!disposed && version === generation) {
        refresh.disabled = false;
        if (repeat) timer = setTimeout(() => load(), 3000);
      }
    }
  }
  refresh.addEventListener('click', () => load(true));
  search.addEventListener('search', () => load(true));
  source.addEventListener('change', () => load(true));
  state.addEventListener('change', () => load(true));
  previous.addEventListener('click', () => { offset = Math.max(0, offset - 20); load(); });
  next.addEventListener('click', () => { offset += 20; load(); });
  load();
  return () => { disposed = true; generation++; clearTimeout(timer); section.remove(); };
}
