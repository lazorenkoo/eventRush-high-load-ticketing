const message = document.querySelector('#message');
const concerts = document.querySelector('#concerts');
const details = document.querySelector('#details');
const reload = document.querySelector('#reload');
const date = value => new Date(value).toLocaleString('en-GB');

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
  try {
    const concert = await read(`/api/v1/concerts/${encodeURIComponent(id)}`);
    details.replaceChildren();
    text(details, 'h2', concert.name);
    text(details, 'p', `ID: ${concert.id}`);
    text(details, 'p', `Starts: ${date(concert.startsAt)}`);
    text(details, 'p', `Sales open: ${date(concert.saleStartsAt)}`);
    text(details, 'p', `Status: ${concert.status}`);
    details.hidden = false;
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
load();
