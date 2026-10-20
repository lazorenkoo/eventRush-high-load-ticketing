import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(new URL('../../main/resources/static/app.js', import.meta.url), 'utf8');
const id = '82eade53-de8a-4668-906b-6a59ede98deb';
const settle = () => new Promise(resolve => setImmediate(resolve));

async function page(hash, respond) {
  class Node extends EventTarget {
    children = [];
    textContent = '';
    hidden = true;
    append(node) { this.children.push(node); }
    replaceChildren() { this.children = []; }
  }
  const nodes = Object.fromEntries(['message', 'concerts', 'details', 'reload', 'main']
    .map(key => [key, new Node()]));
  const requests = [];
  const mounted = [];
  const urls = [];
  const context = vm.createContext({
    document: {querySelector: selector => nodes[selector.replace('#', '')], createElement: () => new Node()},
    location: {hash}, history: {replaceState: (_, __, url) => urls.push(url)},
    URLSearchParams, Date, encodeURIComponent,
    CustomEvent: class extends Event { constructor(type, options) { super(type); this.detail = options.detail; } },
    fetch: async (path, options) => {
      requests.push({path, options});
      const result = await respond(path);
      return {ok: result.status === undefined || result.status === 200,
        status: result.status ?? 200, json: async () => result.body};
    }
  });
  const module = new vm.SourceTextModule(source, {context});
  await module.link(specifier => {
    const name = {'./queue.js': 'mountQueue', './seats.js': 'mountSeats',
      './reservation.js': 'mountReservation', './results.js': 'mountResults'}[specifier];
    return new vm.SyntheticModule([name], function () {
      this.setExport(name, value => {
        if (name === 'mountReservation') mounted.push(value);
        return () => {};
      });
    }, {context});
  });
  await module.evaluate();
  await settle();
  return {nodes, requests, mounted, urls};
}

for (const status of ['ACTIVE', 'CONFIRMED', 'EXPIRED']) {
  test(`reload restores server ${status} with session credentials`, async () => {
    const reservation = {id, status};
    const result = await page(`#reservation=${id}`, async path => ({
      body: path.includes('/reservations/') ? reservation : []}));
    assert.deepEqual(result.mounted, [reservation]);
    assert.equal(result.nodes.details.hidden, false);
    assert.deepEqual(result.urls, [`#reservation=${id}`]);
    const request = result.requests.find(item => item.path.includes('/reservations/'));
    assert.equal(request.options.credentials, 'same-origin');
    assert.equal(request.options.cache, 'no-store');
  });
}

test('invalid and absent IDs never request a reservation', async () => {
  for (const hash of ['', '#reservation=../../admin', '#reservation=%3Cscript%3E']) {
    const result = await page(hash, async () => ({body: []}));
    assert.equal(result.requests.length, 1);
    assert.equal(result.mounted.length, 0);
    assert.equal(result.nodes.message.textContent, hash ? 'Invalid reservation ID.' : 'No concerts.');
  }
});

for (const status of [401, 404, 500]) {
  test(`recovery reports ${status} without mounting or rewriting URL`, async () => {
    const result = await page(`#reservation=${id}`, async path =>
      path.includes('/reservations/') ? {status} : {body: []});
    assert.equal(result.nodes.message.textContent, `Request failed (${status}).`);
    assert.equal(result.mounted.length, 0);
    assert.equal(result.urls.length, 0);
  });
}

test('late recovery cannot replace a newly selected concert', async () => {
  let complete;
  const pending = new Promise(resolve => { complete = resolve; });
  const concert = {id: 'concert', name: 'Concert', startsAt: 0, saleStartsAt: 0};
  const result = await page(`#reservation=${id}`, path => {
    if (path.includes('/reservations/')) return pending;
    return Promise.resolve({body: path.includes('?') ? [concert] : concert});
  });
  result.nodes.concerts.children[0].children[1].dispatchEvent(new Event('click'));
  await settle();
  complete({body: {id, status: 'ACTIVE'}});
  await settle();
  assert.equal(result.mounted.length, 0);
  assert.equal(result.urls.length, 0);
  assert.equal(result.nodes.details.children[0].textContent, 'Concert');
});
