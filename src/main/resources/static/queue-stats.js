export function mountQueueStats(parent) {
  const section = document.createElement('section');
  parent.append(section);
  function add(tag, value, target = section) {
    const node = document.createElement(tag);
    node.textContent = value;
    target.append(node);
    return node;
  }
  add('h2', 'Queue — all gates');
  const form = add('form', '');
  const label = add('label', 'Admin key ', form);
  const key = add('input', '', label);
  key.type = 'password';
  key.autocomplete = 'off';
  key.required = true;
  const refresh = add('button', 'Refresh queue', form);
  const message = add('p', '');
  message.setAttribute('role', 'status');
  const table = add('table', '');

  form.addEventListener('submit', async event => {
    event.preventDefault();
    refresh.disabled = true;
    table.replaceChildren();
    message.textContent = 'Loading…';
    const credential = key.value;
    key.value = '';
    try {
      const response = await fetch('/api/v1/admin/queue-stats', {
        credentials: 'same-origin', cache: 'no-store', headers: {'X-Admin-Key': credential}});
      if (!response.ok) throw new Error(`Queue unavailable (${response.status}).`);
      const stats = await response.json();
      if (stats.scope !== 'ALL_GATES') throw new Error('Unexpected queue scope.');
      const fields = {waiting: 'Waiting', admitted: 'Admitted', active: 'Active sessions',
        snapshotAgeSeconds: 'Snapshot age (s)'};
      for (const [name, title] of Object.entries(fields)) {
        const row = add('tr', '', table);
        add('th', title, row);
        add('td', stats[name], row);
      }
      message.textContent = `Fetched: ${new Date(stats.fetchedAt).toLocaleString('en-GB')}`;
    } catch (error) { message.textContent = error.message; }
    finally { refresh.disabled = false; }
  });
}

mountQueueStats(document.querySelector('main'));
