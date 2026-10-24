import {mountHallMap} from './hall-map.js';

export function mountLiveHall(concert, parent, onSelect) {
  const wrapper = document.createElement('div');
  const refresh = document.createElement('button');
  refresh.type = 'button';
  refresh.textContent = 'Refresh availability';
  const message = document.createElement('p');
  message.setAttribute('role', 'status');
  wrapper.append(refresh, message);
  parent.append(wrapper);
  const hall = mountHallMap(wrapper, onSelect);
  const abort = new AbortController();
  let timer;
  let loading = false;
  let busy = false;
  let disposed = false;
  let authoritative = false;
  async function read(offset) {
    const response = await fetch(`/api/v1/concerts/${concert.id}/seats?limit=500&offset=${offset}`, {
      credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.any([abort.signal, AbortSignal.timeout(5000)]),
    });
    if (!response.ok) throw new Error(`Seat availability unavailable (${response.status}).`);
    const items = await response.json();
    if (!Array.isArray(items) || items.length > 500) throw new Error('Invalid seat response.');
    return items;
  }
  async function load() {
    if (disposed || loading || busy) return;
    clearTimeout(timer);
    loading = true;
    refresh.disabled = true;
    let repeat = false;
    try {
      const seats = [];
      const ids = new Set();
      for (let offset = 0; offset <= 10000; offset += 500) {
        const page = await read(offset);
        if (disposed) return;
        if (offset === 10000 && page.length) throw new Error('Hall exceeds the 10000-seat display limit.');
        for (const seat of page) {
          if (typeof seat.id !== 'string' || ids.has(seat.id) || !Number.isInteger(seat.number)
              || !['AVAILABLE', 'RESERVED', 'SOLD'].includes(seat.status)) throw new Error('Invalid seat response.');
          ids.add(seat.id);
          seats.push(seat);
        }
        if (page.length < 500) break;
      }
      authoritative = true;
      hall.update(seats);
      hall.setBusy(busy);
      message.textContent = `${seats.filter(seat => seat.status === 'AVAILABLE').length} available / ${seats.length} seats`
        + ` — updated ${new Date().toLocaleTimeString('en-GB')}`;
      repeat = true;
    } catch (error) {
      if (!disposed) {
        authoritative = false;
        hall.setBusy(true);
        onSelect(null);
        message.textContent = error.message;
      }
    } finally {
      loading = false;
      if (!disposed) {
        refresh.disabled = busy;
        if (repeat && !busy) timer = setTimeout(load, 3000);
      }
    }
  }
  refresh.addEventListener('click', load);
  hall.setBusy(true);
  load();
  return {
    refresh: load,
    setBusy(value) {
      busy = value;
      hall.setBusy(value || !authoritative);
      refresh.disabled = value || loading;
      if (value) clearTimeout(timer); else load();
    },
    dispose() { disposed = true; abort.abort(); clearTimeout(timer); hall.dispose(); wrapper.remove(); },
  };
}
