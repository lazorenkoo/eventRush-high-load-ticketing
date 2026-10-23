export function adminApi(apiKey) {
  if (typeof apiKey !== 'string' || apiKey.length < 32 || apiKey.length > 512 || /[\r\n]/.test(apiKey)) {
    throw new Error('Enter a valid administrator key.');
  }
  const abort = new AbortController();
  const id = value => {
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)) throw new Error('Invalid ID.');
    return value;
  };
  const delivery = value => {
    if (!Number.isSafeInteger(value) || value < 1) throw new Error('Invalid delivery ID.');
    return value;
  };
  const concert = value => `/api/v1/admin/concerts/${id(value)}`;
  const query = values => {
    const result = new URLSearchParams();
    for (const [name, value] of Object.entries(values || {})) {
      if (!['q', 'source', 'state', 'kind', 'limit', 'offset', 'after'].includes(name)
          || String(value).length > 100) throw new Error('Invalid search filter.');
      result.set(name, String(value));
    }
    return '?' + result;
  };
  async function request(method, path, body, timeout = 10000) {
    abort.signal.throwIfAborted();
    const response = await fetch(path, {method, body, credentials: 'same-origin', cache: 'no-store',
      headers: {'X-Admin-Key': apiKey}, signal: AbortSignal.any([abort.signal, AbortSignal.timeout(timeout)])});
    if (!response.ok) {
      if (response.status === 401) close();
      throw new Error(`Administrative request failed (${response.status}).`);
    }
    const reader = response.body.getReader();
    const chunks = [];
    let bytes = 0;
    try {
      for (;;) {
        const {value, done} = await reader.read();
        if (done) break;
        bytes += value.byteLength;
        if (bytes > 262144) throw new Error('Administrative response exceeds the limit.');
        chunks.push(value);
      }
    } finally { await reader.cancel(); }
    return JSON.parse(await new Blob(chunks).text());
  }
  function close() { apiKey = ''; abort.abort(); }
  return Object.freeze({
    close,
    stats: value => request('GET', concert(value) + '/stats'),
    queue: () => request('GET', '/api/v1/admin/queue-stats'),
    activity: (value, filters) => request('GET', concert(value) + '/activity' + query(filters)),
    journal: (value, filters) => request('GET', concert(value) + '/journal' + query(filters)),
    events: (value, filters) => request('GET', concert(value) + '/events' + query(filters)),
    deliveries: (value, event) => request('GET', concert(value) + `/events/${id(event)}/deliveries`),
    attempts: (value, event, target) => request('GET', concert(value) + `/events/${id(event)}/deliveries/${delivery(target)}/attempts`),
    retry: (value, event, target) => request('POST', concert(value) + `/events/${id(event)}/deliveries/${delivery(target)}/retry`),
    run: (value, runId, action = 'progress') => {
      if (!['progress', 'start', 'stop'].includes(action)) throw new Error('Invalid run action.');
      return request(action === 'progress' ? 'GET' : 'POST', concert(value) + `/load-runs/${id(runId)}/${action}`);
    },
    upload: (value, file, concurrency) => {
      if (!file || file.size < 1 || file.size > 16777216 || !Number.isInteger(concurrency)
          || concurrency < 1 || concurrency > 100) throw new Error('Choose a CSV and concurrency from 1 to 100.');
      const form = new FormData();
      form.append('file', file, 'clients.csv');
      return request('POST', concert(value) + `/load-runs?concurrency=${concurrency}`, form, 45000);
    },
  });
}
