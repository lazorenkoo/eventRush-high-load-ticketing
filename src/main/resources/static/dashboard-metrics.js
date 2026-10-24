export function mountMetrics(api, concertId, parent) {
  const section = document.createElement('section');
  const node = (tag, text, target = section) => {
    const element = document.createElement(tag);
    if (text !== undefined) element.textContent = text;
    target.append(element);
    return element;
  };
  node('h3', 'Live metrics');
  const refresh = node('button', 'Refresh metrics');
  refresh.type = 'button';
  const status = node('p');
  status.setAttribute('role', 'status');
  node('h4', 'Selected concert');
  const inventory = node('table');
  node('h4', 'CrowdGate — all gates');
  const queue = node('table');
  const queueStatus = node('p');
  parent.append(section);
  let disposed = false;
  let busy = false;
  let timer;
  function render(target, data, fields) {
    target.replaceChildren();
    for (const [key, label] of Object.entries(fields)) {
      const row = node('tr', undefined, target);
      node('th', label, row);
      node('td', data[key] ?? '—', row);
    }
  }
  async function load() {
    if (disposed || busy) return;
    clearTimeout(timer);
    busy = true;
    refresh.disabled = true;
    let repeat = false;
    try {
      const [concert, gates] = await Promise.allSettled([api.stats(concertId), api.queue()]);
      if (disposed) return;
      inventory.replaceChildren();
      queue.replaceChildren();
      status.textContent = concert.status === 'rejected' ? concert.reason.message : '';
      queueStatus.textContent = gates.status === 'rejected' ? gates.reason.message : '';
      if (concert.status === 'fulfilled') {
        if (concert.value.concertId !== concertId) throw new Error('Unexpected concert scope.');
        render(inventory, concert.value, {status: 'Status', totalSeats: 'Seats', available: 'Available',
          reserved: 'Reserved', sold: 'Sold', pendingPayments: 'Pending payments', successfulPayments: 'Successful payments',
          failedPayments: 'Failed payments', revenue: 'Sales revenue', purchasesLastMinute: 'Purchases in last 60 seconds',
          purchasesPerSecond: 'Purchases / second (60-second window)'});
        status.textContent = `Updated ${new Date().toLocaleTimeString('en-GB')}`;
      }
      if (gates.status === 'fulfilled') {
        if (gates.value.scope !== 'ALL_GATES') throw new Error('Unexpected queue scope.');
        render(queue, gates.value, {waiting: 'Waiting', admitted: 'Admitted', active: 'Active sessions',
          snapshotAgeSeconds: 'Snapshot age (seconds)'});
        queueStatus.textContent = `Fetched ${gates.value.fetchedAt}`;
      }
      repeat = concert.status === 'fulfilled' && gates.status === 'fulfilled';
    } catch (error) { if (!disposed) status.textContent = error.message; }
    finally {
      if (!disposed) {
        busy = false;
        refresh.disabled = false;
        if (repeat) timer = setTimeout(load, 3000);
      }
    }
  }
  refresh.addEventListener('click', load);
  load();
  return () => { disposed = true; clearTimeout(timer); section.remove(); };
}
