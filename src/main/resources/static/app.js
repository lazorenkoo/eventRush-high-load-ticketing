import {mountQueue} from './queue.js';
import {mountSeats} from './seats.js';
import {mountReservation} from './reservation.js';
import {mountResults} from './results.js';

const message = document.querySelector('#message');
const concerts = document.querySelector('#concerts');
const details = document.querySelector('#details');
const reload = document.querySelector('#reload');
mountResults(document.querySelector('main'));
const date = value => new Date(value).toLocaleString('en-GB');
let stopQueue = () => {};
let selection = 0;
let stopSeats = () => {};
let stopReservation = () => {};
details.addEventListener('reserved', event => {
  stopReservation();
  stopReservation = mountReservation(event.detail, details);
  history.replaceState(null, '', '#reservation=' + encodeURIComponent(event.detail.id));
});
details.addEventListener('admitted', event => {
  stopSeats();
  stopSeats = mountSeats(event.detail, details);
});
details.addEventListener('admissionlost', () => stopSeats());

function text(parent, tag, value) {
  const node = document.createElement(tag);
  node.textContent = value;
  parent.append(node);
  return node;
}

async function read(path) {
  const response = await fetch(path, {cache: 'no-store', credentials: 'same-origin'});
  if (!response.ok) throw new Error(`Request failed (${response.status}).`);
  return response.json();
}

async function show(id) {
  stopQueue();
  stopSeats();
  const current = ++selection;
  stopReservation();
  try {
    const concert = await read(`/api/v1/concerts/${encodeURIComponent(id)}`);
    if (current !== selection) return;
    details.replaceChildren();
    text(details, 'h2', concert.name);
    text(details, 'p', `ID: ${concert.id}`);
    text(details, 'p', `Starts: ${date(concert.startsAt)}`);
    text(details, 'p', `Sales open: ${date(concert.saleStartsAt)}`);
    text(details, 'p', `Status: ${concert.status}`);
    details.hidden = false;
    stopQueue = mountQueue(concert, details);
  } catch (error) { message.textContent = error.message; }
}

async function load() {
  reload.disabled = true;
  message.textContent = 'Loading…';
  try {
    const items = await read('/api/v1/concerts?limit=100');
    concerts.replaceChildren();
    for (const concert of items) {
      const item = text(concerts, 'li', '');
      text(item, 'p', `${concert.name} — ${concert.status}`);
      const button = text(item, 'button', 'Details');
      button.type = 'button';
      button.addEventListener('click', () => show(concert.id));
    }
    message.textContent = items.length ? '' : 'No concerts.';
  } catch (error) { message.textContent = error.message; }
  finally { reload.disabled = false; }
}

reload.addEventListener('click', load);
load().then(restoreReservation);

async function restoreReservation() {
  const id = new URLSearchParams(location.hash.slice(1)).get('reservation');
  if (!id) return;
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)) {
    message.textContent = 'Invalid reservation ID.';
    return;
  }
  const current = selection;
  try {
    const reservation = await read(`/api/v1/reservations/${encodeURIComponent(id)}`);
    if (current !== selection) return;
    details.hidden = false;
    details.dispatchEvent(new CustomEvent('reserved', {detail: reservation}));
  } catch (error) {
    if (current === selection) message.textContent = error.message;
  }
}
