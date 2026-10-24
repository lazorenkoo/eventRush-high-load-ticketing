import {buyNamed} from './purchase-flow.js';
import {numberedSeat} from './numbered-seat.js';

export function mountNamedPurchase(concert, parent, onPurchased = () => {}) {
  const section = document.createElement('section');
  const heading = document.createElement('h3');
  heading.textContent = 'Buy a ticket';
  const form = document.createElement('form');
  const inputs = [];
  function field(labelText, type, name) {
    const label = document.createElement('label');
    label.textContent = labelText + ' ';
    const input = document.createElement('input');
    Object.assign(input, {type, name, required: true});
    label.append(input);
    form.append(label);
    inputs.push(input);
    return input;
  }
  const first = field('First name', 'text', 'firstName');
  const last = field('Last name', 'text', 'lastName');
  first.maxLength = last.maxLength = 100;
  first.autocomplete = 'given-name';
  last.autocomplete = 'family-name';
  const number = field('Seat number', 'number', 'seatNumber');
  Object.assign(number, {min: '1', max: '10000', step: '1'});
  const buy = document.createElement('button');
  buy.type = 'submit';
  buy.textContent = 'Buy';
  const stop = document.createElement('button');
  stop.type = 'button';
  stop.textContent = 'Stop waiting';
  stop.disabled = true;
  const status = document.createElement('p');
  status.setAttribute('role', 'status');
  const recovery = document.createElement('a');
  recovery.textContent = 'Open reservation';
  recovery.hidden = true;
  form.append(buy, stop);
  section.append(heading, form, status, recovery);
  parent.append(section);
  let savedReservation;
  recovery.addEventListener('click', event => {
    event.preventDefault();
    if (savedReservation) parent.dispatchEvent(new CustomEvent('reserved', {detail: savedReservation}));
  });
  let controller;
  let disposed = false;
  function busy(value) {
    for (const input of inputs) input.disabled = value;
    buy.disabled = value || concert.status !== 'ON_SALE';
    stop.disabled = !value;
  }
  stop.addEventListener('click', () => controller?.abort(new Error('Waiting stopped.')));
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (controller || !form.reportValidity()) return;
    controller = new AbortController();
    busy(true);
    recovery.hidden = true;
    try {
      const selected = numberedSeat(concert.id, Number(number.value));
      const result = await buyNamed(concert, selected, {firstName: first.value.trim(), lastName: last.value.trim()}, {
        signal: controller.signal,
        onStatus: value => { if (!disposed) status.textContent = value; },
        onReserved: value => {
          savedReservation = value;
          recovery.href = '#reservation=' + encodeURIComponent(value.id);
          recovery.hidden = false;
          history.replaceState(null, '', recovery.href);
        },
      });
      if (!disposed) onPurchased(result);
    } catch (error) { if (!disposed) status.textContent = error.message || 'Purchase stopped.'; }
    finally { controller = null; if (!disposed) busy(false); }
  });
  busy(false);
  return () => { disposed = true; controller?.abort(); section.remove(); };
}
