import {mountDeliveryHistory} from './delivery-history.js';

export function mountEvents(api, concertId, parent) {
  const section = document.createElement('section');
  const node = (tag, text, target = section) => {
    const element = document.createElement(tag);
    if (text !== undefined) element.textContent = text;
    target.append(element);
    return element;
  };
  node('h3', 'Outgoing events and deliveries');
  const refresh = node('button', 'Refresh events');
  const previous = node('button', 'Previous events');
  const next = node('button', 'Next events');
  for (const button of [refresh, previous, next]) button.type = 'button';
  const status = node('p');
  status.setAttribute('role', 'status');
  const table = node('table');
  const header = node('tr', undefined, node('thead', undefined, table));
  for (const title of ['Event ID', 'Type', 'Created', 'Published', 'Publish attempts', 'Deliveries']) node('th', title, header);
  const body = node('tbody', undefined, table);
  const detail = node('div');
  parent.append(section);
  let offset = 0;
  let nextOffset = null;
  let generation = 0;
  let disposed = false;
  let closeHistory = () => {};
  async function load() {
    const version = ++generation;
    closeHistory();
    closeHistory = () => {};
    detail.replaceChildren();
    refresh.disabled = previous.disabled = next.disabled = true;
    try {
      const page = await api.events(concertId, {limit: 20, offset});
      if (disposed || version !== generation) return;
      body.replaceChildren();
      nextOffset = page.nextOffset;
      for (const event of page.items) {
        const row = node('tr', undefined, body);
        for (const value of [event.id, event.type, event.createdAt, event.publishedAt, event.publishAttempts]) {
          node('td', value ?? '—', row);
        }
        const cell = node('td', undefined, row);
        if (event.publishedAt) {
          const open = node('button', 'View deliveries', cell);
          open.type = 'button';
          open.addEventListener('click', () => {
            if (disposed || version !== generation) return;
            closeHistory();
            detail.replaceChildren();
            closeHistory = mountDeliveryHistory(api, concertId, event.id, detail);
          });
        } else node('span', 'Awaiting publication', cell);
      }
      status.textContent = `${page.items.length} events — offset ${offset}`
        + (page.truncated ? ' — query limit reached; refresh to see newest events' : '');
      previous.disabled = offset === 0;
      next.disabled = !page.hasMore || !Number.isSafeInteger(nextOffset) || nextOffset <= offset || nextOffset > 50000;
    } catch (error) {
      if (!disposed && version === generation) status.textContent = error.message;
    } finally {
      if (!disposed && version === generation) refresh.disabled = false;
    }
  }
  refresh.addEventListener('click', () => { offset = 0; load(); });
  previous.addEventListener('click', () => { offset = Math.max(0, offset - 20); load(); });
  next.addEventListener('click', () => { offset = nextOffset; load(); });
  load();
  return () => { disposed = true; generation++; closeHistory(); section.remove(); };
}
