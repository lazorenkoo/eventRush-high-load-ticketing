const form = document.querySelector('#stats');
const key = document.querySelector('#key');
const concert = document.querySelector('#concert');
const message = document.querySelector('#message');
const table = document.querySelector('#result');
const button = form.querySelector('button');

form.addEventListener('submit', async event => {
  event.preventDefault();
  button.disabled = true;
  table.replaceChildren();
  message.textContent = 'Loading…';
  const credential = key.value;
  key.value = '';
  try {
    const response = await fetch(`/api/v1/admin/concerts/${encodeURIComponent(concert.value.trim())}/stats`, {
      credentials: 'same-origin', cache: 'no-store', headers: {'X-Admin-Key': credential}});
    if (!response.ok) throw new Error(`Request failed (${response.status}).`);
    const stats = await response.json();
    const fields = {status: 'Status', totalSeats: 'Seats', available: 'Available', reserved: 'Reserved',
      sold: 'Sold', pendingPayments: 'Pending payments', successfulPayments: 'Successful payments',
      failedPayments: 'Failed payments', revenue: 'Revenue'};
    for (const [name, label] of Object.entries(fields)) {
      const row = document.createElement('tr');
      const heading = document.createElement('th');
      const value = document.createElement('td');
      heading.textContent = label;
      value.textContent = stats[name];
      row.append(heading, value);
      table.append(row);
    }
    message.textContent = '';
  } catch (error) { message.textContent = error.message; }
  finally { button.disabled = false; }
});
