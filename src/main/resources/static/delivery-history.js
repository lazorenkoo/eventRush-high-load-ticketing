export function mountDeliveryHistory(api, concertId, eventId, parent) {
  const section = document.createElement('section');
  const node = (tag, text, target = section) => {
    const element = document.createElement(tag);
    if (text !== undefined) element.textContent = text;
    target.append(element);
    return element;
  };
  node('h4', `RelayForge deliveries — event ${eventId}`);
  const refresh = node('button', 'Refresh deliveries');
  refresh.type = 'button';
  const status = node('p');
  status.setAttribute('role', 'status');
  const list = node('div');
  const attempts = node('pre');
  attempts.setAttribute('aria-label', 'Delivery attempts');
  parent.append(section);
  let disposed = false;
  let busy = false;
  let generation = 0;
  const controls = [refresh];
  async function action(work) {
    if (busy || disposed) return;
    busy = true;
    const version = ++generation;
    controls.forEach(control => { control.disabled = true; });
    try { await work(version); }
    catch (error) { if (!disposed && version === generation) status.textContent = error.message; }
    finally {
      if (!disposed && version === generation) {
        busy = false;
        controls.forEach(control => { control.disabled = false; });
      }
    }
  }
  async function load(version) {
    const result = await api.deliveries(concertId, eventId);
    if (disposed || version !== generation) return;
    list.replaceChildren();
    controls.splice(1);
    status.textContent = `RelayForge event ${result.relayEventId} — ${result.deliveries.length} deliveries`;
    for (const delivery of result.deliveries) {
      const row = node('p', undefined, list);
      node('span', `Delivery ${delivery.id} — endpoint ${delivery.endpointId} — ${delivery.status}`
        + ` — HTTP ${delivery.httpStatus ?? '—'} — attempts ${delivery.attemptCount}`, row);
      const history = node('button', 'View attempts', row);
      history.type = 'button';
      controls.push(history);
      history.addEventListener('click', () => action(async current => {
        const entries = await api.attempts(concertId, eventId, delivery.id);
        if (!disposed && current === generation) {
          attempts.textContent = `Delivery ${delivery.id}\n` + entries.map(entry =>
            `Attempt ${entry.attemptNumber} — ${entry.status} — HTTP ${entry.httpStatus ?? '—'} — ${entry.completedAt ?? '—'}`).join('\n');
        }
      }));
      if (delivery.status === 'FAILED') {
        const retry = node('button', 'Retry failed delivery', row);
        retry.type = 'button';
        controls.push(retry);
        retry.addEventListener('click', () => action(async current => {
          const result = await api.retry(concertId, eventId, delivery.id);
          if (disposed || current !== generation) return;
          if (!result.successful) throw new Error(`Retry rejected (${result.httpStatus}).`);
          attempts.textContent = '';
          await load(current);
          if (!disposed && current === generation) {
            status.textContent += ' — Retry requested; RelayForge creates a new delivery.';
          }
        }));
      }
    }
  }
  refresh.addEventListener('click', () => action(load));
  action(load);
  return () => { disposed = true; generation++; section.remove(); };
}
