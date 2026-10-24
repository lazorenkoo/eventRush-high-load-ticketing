export function mountConsoleJournal(api, concertId, parent) {
  const panel = document.createElement('aside');
  panel.className = 'console-panel';
  const node = (tag, text, target = panel) => {
    const element = document.createElement(tag);
    if (text !== undefined) element.textContent = text;
    target.append(element);
    return element;
  };
  node('h2', 'Live console');
  const popout = node('a', 'Open console in separate window ↗');
  popout.href = '/admin.html?console=1&concertId=' + encodeURIComponent(concertId);
  popout.target = '_blank';
  popout.rel = 'noopener';
  popout.addEventListener('click', event => {
    event.preventDefault();
    window.open(popout.href, '_blank', 'noopener,width=1000,height=800');
  });
  const search = node('input');
  Object.assign(search, {type: 'search', maxLength: 100, placeholder: 'Name, seat or operation ID'});
  search.setAttribute('aria-label', 'Search console');
  function select(label, values) {
    const field = node('select');
    field.setAttribute('aria-label', label);
    for (const value of ['', ...values]) {
      const option = node('option', value || 'All', field);
      option.value = value;
    }
    return field;
  }
  const source = select('Console source', ['MANUAL', 'TEST']);
  const kind = select('Console event kind', ['REGISTRATION', 'QUEUE', 'RESERVATION', 'PAYMENT', 'TICKET', 'ACCOUNTING', 'NOTIFICATION']);
  const restart = node('button', 'Apply filters');
  restart.type = 'button';
  const label = node('label', 'Follow latest ');
  const follow = node('input', undefined, label);
  Object.assign(follow, {type: 'checkbox', checked: true});
  const status = node('p');
  status.setAttribute('role', 'status');
  const output = node('div');
  output.className = 'console-output';
  output.setAttribute('role', 'log');
  output.setAttribute('aria-label', 'Purchase event console');
  output.setAttribute('aria-live', 'off');
  output.tabIndex = 0;
  parent.append(panel);
  let after = 0;
  let generation = 0;
  let timer;
  let disposed = false;
  let entries = [];
  async function load(reset = false) {
    clearTimeout(timer);
    if (reset) { after = 0; entries = []; output.replaceChildren(); }
    const version = ++generation;
    restart.disabled = true;
    try {
      const page = await api.journal(concertId, {after, limit: 50, q: search.value, source: source.value, kind: kind.value});
      if (disposed || version !== generation) return;
      if (!Array.isArray(page.items) || !Number.isSafeInteger(page.nextAfter) || page.nextAfter < after
          || (page.hasMore && page.nextAfter === after)) throw new Error('Invalid console cursor.');
      after = page.nextAfter;
      const position = output.scrollTop;
      entries = entries.concat(page.items).slice(-300);
      output.replaceChildren();
      for (const entry of entries) {
        const line = node('div', undefined, output);
        line.className = 'console-line';
        line.dataset.state = entry.state;
        node('span', `[${entry.occurredAt}] `, line).className = 'console-time';
        node('span', `${entry.source} ${entry.firstName} ${entry.lastName} `, line).className = 'console-person';
        node('span', `seat=${entry.seatNumber} ${entry.kind} → ${entry.state}`, line).className = 'console-state';
        node('small', `participant=${entry.participantId} reservation=${entry.reservationId ?? '—'} event=${entry.eventId}`, line);
      }
      output.scrollTop = follow.checked ? output.scrollHeight : position;
      status.textContent = `${entries.length}/300 retained lines · cursor ${after}`;
      timer = setTimeout(() => load(), page.hasMore ? 500 : 3000);
    } catch (error) { if (!disposed && version === generation) status.textContent = error.message; }
    finally { if (!disposed && version === generation) restart.disabled = false; }
  }
  restart.addEventListener('click', () => load(true));
  search.addEventListener('search', () => load(true));
  source.addEventListener('change', () => load(true));
  kind.addEventListener('change', () => load(true));
  follow.addEventListener('change', () => { if (follow.checked) output.scrollTop = output.scrollHeight; });
  load();
  return () => { disposed = true; generation++; clearTimeout(timer); panel.remove(); };
}
