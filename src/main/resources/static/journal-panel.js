export function mountJournal(api, concertId, parent) {
  const section = document.createElement('section');
  const node = (tag, text, target = section) => {
    const element = document.createElement(tag);
    if (text !== undefined) element.textContent = text;
    target.append(element);
    return element;
  };
  node('h3', 'Live purchase journal');
  const search = node('input');
  Object.assign(search, {type: 'search', maxLength: 100, placeholder: 'Name, seat or operation ID'});
  search.setAttribute('aria-label', 'Search journal');
  function select(label, values) {
    const field = node('select');
    field.setAttribute('aria-label', label);
    for (const value of ['', ...values]) {
      const option = node('option', value || 'All', field);
      option.value = value;
    }
    return field;
  }
  const source = select('Journal source', ['MANUAL', 'TEST']);
  const kind = select('Journal event kind', ['REGISTRATION', 'QUEUE', 'RESERVATION', 'PAYMENT', 'TICKET', 'ACCOUNTING', 'NOTIFICATION']);
  const refresh = node('button', 'Search / Restart journal');
  const more = node('button', 'Read next entries');
  refresh.type = more.type = 'button';
  const status = node('p');
  status.setAttribute('role', 'status');
  const table = node('table');
  const header = node('tr', undefined, node('thead', undefined, table));
  for (const title of ['Time', 'Name', 'Source', 'Kind', 'State', 'Seat', 'Participant ID', 'Reservation ID', 'Event ID']) {
    node('th', title, header);
  }
  const body = node('tbody', undefined, table);
  parent.append(section);
  let after = 0;
  let generation = 0;
  let timer;
  let disposed = false;
  let retained = [];
  async function load(reset = false) {
    clearTimeout(timer);
    if (reset) { after = 0; retained = []; body.replaceChildren(); }
    const version = ++generation;
    refresh.disabled = more.disabled = true;
    try {
      const page = await api.journal(concertId, {after, limit: 50, q: search.value, source: source.value, kind: kind.value});
      if (disposed || version !== generation) return;
      if (!Array.isArray(page.items) || !Number.isSafeInteger(page.nextAfter) || page.nextAfter < after) {
        throw new Error('Invalid journal cursor.');
      }
      after = page.nextAfter;
      retained = retained.concat(page.items).slice(-100);
      body.replaceChildren();
      for (const item of retained) {
        const row = node('tr', undefined, body);
        for (const value of [item.occurredAt, `${item.firstName} ${item.lastName}`, item.source, item.kind,
          item.state, item.seatNumber, item.participantId, item.reservationId, item.eventId]) node('td', value ?? '—', row);
      }
      status.textContent = `${retained.length} retained entries (maximum 100) — cursor ${after}`;
      more.disabled = !page.hasMore;
      if (!page.hasMore) timer = setTimeout(() => load(), 3000);
    } catch (error) {
      if (!disposed && version === generation) status.textContent = error.message;
    } finally {
      if (!disposed && version === generation) refresh.disabled = false;
    }
  }
  refresh.addEventListener('click', () => load(true));
  more.addEventListener('click', () => load());
  search.addEventListener('search', () => load(true));
  source.addEventListener('change', () => load(true));
  kind.addEventListener('change', () => load(true));
  load();
  return () => { disposed = true; generation++; clearTimeout(timer); section.remove(); };
}
