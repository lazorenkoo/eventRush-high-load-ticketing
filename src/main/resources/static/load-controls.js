export function mountLoadControls(api, concertId, parent) {
  const section = document.createElement('section');
  function node(tag, text) {
    const element = document.createElement(tag);
    if (text) element.textContent = text;
    section.append(element);
    return element;
  }
  node('h3', 'CSV test run');
  const fileLabel = node('label', 'CSV file ');
  const file = document.createElement('input');
  Object.assign(file, {type: 'file', accept: '.csv,text/csv'});
  fileLabel.append(file);
  const concurrencyLabel = node('label', 'Concurrent buyers ');
  const concurrency = document.createElement('input');
  Object.assign(concurrency, {type: 'number', min: '1', max: '100', step: '1', value: '20'});
  concurrencyLabel.append(concurrency);
  const upload = node('button', 'Upload CSV');
  const start = node('button', 'Start test');
  const stop = node('button', 'Stop test');
  const refresh = node('button', 'Refresh progress');
  for (const button of [upload, start, stop, refresh]) button.type = 'button';
  const status = node('p');
  status.setAttribute('role', 'status');
  const progress = node('pre');
  parent.append(section);
  let run;
  let state;
  let timer;
  let busy = false;
  let disposed = false;
  let generation = 0;
  function controls() {
    upload.disabled = busy || ['RUNNING', 'STOPPING'].includes(state);
    file.disabled = upload.disabled;
    concurrency.disabled = upload.disabled;
    refresh.disabled = busy || !run;
    start.disabled = busy || !run || state !== 'READY';
    stop.disabled = busy || !run || !['READY', 'RUNNING'].includes(state);
  }
  function render(value) {
    state = value.status;
    status.textContent = `Run ${run} — ${state} — ${value.participantCount} participants`;
    progress.textContent = value.scope === 'TEST_RUN'
      ? `Test run only\nPending: ${value.pending}\nRunning: ${value.running}\nPurchased: ${value.succeeded}\nUnavailable: ${value.unavailable}\nFailed: ${value.failed}\nCancelled: ${value.cancelled}`
      : 'Uploaded. Start test runs the buyers; uploading alone does not start them.';
    controls();
  }
  async function poll(version) {
    try {
      const value = await api.run(concertId, run);
      if (disposed || version !== generation) return;
      render(value);
      if (['RUNNING', 'STOPPING'].includes(state)) timer = setTimeout(() => poll(version), 2000);
    } catch (error) { if (!disposed && version === generation) status.textContent = error.message; }
  }
  async function act(action) {
    if (busy || disposed) return;
    busy = true;
    controls();
    clearTimeout(timer);
    const version = ++generation;
    try {
      if (action === 'upload') {
        const value = await api.upload(concertId, file.files[0], Number(concurrency.value));
        if (disposed) return;
        run = value.id;
        render(value);
      } else {
        await api.run(concertId, run, action);
        if (!disposed) await poll(version);
      }
    } catch (error) { if (!disposed) status.textContent = error.message; }
    finally { busy = false; if (!disposed) controls(); }
  }
  upload.addEventListener('click', () => act('upload'));
  start.addEventListener('click', () => act('start'));
  stop.addEventListener('click', () => act('stop'));
  refresh.addEventListener('click', () => { clearTimeout(timer); poll(++generation); });
  controls();
  return () => { disposed = true; generation++; clearTimeout(timer); section.remove(); };
}
