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
  const autoLabel = add('label', ' Auto-refresh (5s) ', form);
  const auto = add('input', '', autoLabel);
  auto.type = 'checkbox';
  const stop = add('button', 'Stop', form);
  stop.type = 'button';
  stop.disabled = true;
  let timer;
  let credential = '';
  let generation = 0;
  function halt() {
    clearTimeout(timer);
    credential = '';
    generation++;
    stop.disabled = true;
    refresh.disabled = false;
    key.value = '';
  }
  stop.addEventListener('click', halt);
  globalThis.addEventListener?.('pagehide', halt);
  const message = add('p', '');
  message.setAttribute('role', 'status');
  const table = add('table', '');

  form.addEventListener('submit', event => {
    event.preventDefault();
    const entered = key.value;
    halt();
    credential = entered;
    key.value = '';
    load(generation);
  });
  async function load(current) {
    refresh.disabled = true;
    table.replaceChildren();
    message.textContent = 'Loading…';
    stop.disabled = false;
    try {
      const response = await fetch('/api/v1/admin/queue-stats', {
        credentials: 'same-origin', cache: 'no-store', headers: {'X-Admin-Key': credential}});
      if (!response.ok) throw new Error(`Queue unavailable (${response.status}).`);
      const stats = await response.json();
      if (current !== generation) return;
      if (stats.scope !== 'ALL_GATES') throw new Error('Unexpected queue scope.');
      const fields = {waiting: 'Waiting', admitted: 'Admitted', active: 'Active sessions',
        snapshotAgeSeconds: 'Snapshot age (s)'};
      for (const [name, title] of Object.entries(fields)) {
        const row = add('tr', '', table);
        add('th', title, row);
        add('td', stats[name], row);
      }
      message.textContent = `Fetched: ${new Date(stats.fetchedAt).toLocaleString('en-GB')}`;
    } catch (error) {
      if (current === generation) { message.textContent = error.message; halt(); }
    } finally {
      if (current === generation) {
        refresh.disabled = false;
        if (auto.checked) timer = setTimeout(() => load(current), 5000);
        else halt();
      }
    }
  }
}

mountQueueStats(document.querySelector('main'));
