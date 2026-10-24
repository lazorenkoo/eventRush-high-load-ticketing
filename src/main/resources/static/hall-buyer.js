import {mountLiveHall} from './live-hall.js';
import {buyNamed} from './purchase-flow.js';

export function mountHallBuyer(concert, parent, onPurchased = () => {}) {
  const panel = document.createElement('div');
  panel.className = 'hall-buyer';
  const mapArea = document.createElement('div');
  const form = document.createElement('form');
  form.className = 'seat-checkout';
  const title = document.createElement('h3');
  title.textContent = 'Your ticket';
  const selected = document.createElement('p');
  selected.textContent = 'Choose a gray seat on the hall plan.';
  form.append(title, selected);
  function field(text, autocomplete) {
    const label = document.createElement('label');
    label.textContent = text;
    const input = document.createElement('input');
    Object.assign(input, {type: 'text', required: true, maxLength: 100, autocomplete});
    label.append(input);
    form.append(label);
    return input;
  }
  const first = field('First name', 'given-name');
  const last = field('Last name', 'family-name');
  const buy = document.createElement('button');
  buy.type = 'submit';
  buy.textContent = 'Buy selected seat';
  buy.disabled = true;
  const status = document.createElement('p');
  status.setAttribute('role', 'status');
  const recovery = document.createElement('a');
  recovery.textContent = 'Open reservation';
  recovery.hidden = true;
  form.append(buy, status, recovery);
  panel.append(mapArea, form);
  parent.append(panel);
  let seat;
  let controller;
  let disposed = false;
  let saved;
  const hall = mountLiveHall(concert, mapArea, value => {
    seat = value;
    buy.disabled = !value || !!controller;
    selected.textContent = value ? `${value.section} · Row ${value.row} · Seat ${value.number} · Price ${value.price}`
      : 'Choose an available seat on the hall plan.';
  });
  recovery.addEventListener('click', event => {
    event.preventDefault();
    if (saved && !disposed) parent.dispatchEvent(new CustomEvent('reserved', {detail: saved}));
  });
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (disposed || controller || !seat || !form.reportValidity()) return;
    controller = new AbortController();
    buy.disabled = first.disabled = last.disabled = true;
    hall.setBusy(true);
    try {
      const result = await buyNamed(concert, seat.id, {firstName: first.value.trim(), lastName: last.value.trim()}, {
        admittedQueueToken: concert.admittedQueueToken, signal: controller.signal,
        onStatus: text => { if (!disposed) status.textContent = text; },
        onReserved: reservation => {
          if (disposed) return;
          saved = reservation;
          recovery.href = '#reservation=' + encodeURIComponent(reservation.id);
          recovery.hidden = false;
          history.replaceState(null, '', recovery.href);
        },
      });
      if (!disposed) onPurchased(result);
    } catch (error) { if (!disposed) status.textContent = error.message; }
    finally {
      controller = undefined;
      if (!disposed) {
        first.disabled = last.disabled = false;
        seat = undefined;
        buy.disabled = true;
        hall.setBusy(false);
      }
    }
  });
  return () => { disposed = true; controller?.abort(); hall.dispose(); panel.remove(); };
}
