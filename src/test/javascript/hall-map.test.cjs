const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/hall-map.js', 'utf8')
  .replace('export function', 'function') + '\nthis.mount = mountHallMap;';
class Element {
  children = [];
  listeners = {};
  attributes = {};
  textContent = '';
  className = '';
  classList = {
    add: value => this.classList.toggle(value, true),
    toggle: (value, present) => {
      const names = new Set(this.className.split(' ').filter(Boolean));
      if (present) names.add(value); else names.delete(value);
      this.className = [...names].join(' ');
    },
  };
  append(...nodes) { this.children.push(...nodes); }
  replaceChildren(...nodes) { this.children = nodes; }
  setAttribute(name, value) { this.attributes[name] = value; }
  addEventListener(name, callback) { this.listeners[name] = callback; }
  remove() { this.removed = true; }
}
function fixture() {
  const elements = [], selections = [];
  const context = vm.createContext({document: {createElement(tag) {
    const element = new Element(); element.tag = tag; elements.push(element); return element;
  }}});
  vm.runInContext(source, context);
  const map = context.mount(new Element(), value => selections.push(value));
  return {map, elements, selections, button: number => elements.findLast(node => node.tag === 'button' && node.textContent === number)};
}
const seat = (number, status = 'AVAILABLE', row = 'A') => ({id: `seat-${number}`, section: 'Main', row, number, price: 25, status});

test('hall orders numeric section and row labels naturally rather than lexicographically', () => {
  const value = fixture();
  value.map.update([{...seat(1, 'AVAILABLE', '10'), section: '2'},
    {...seat(2, 'AVAILABLE', '2'), section: '2'}, {...seat(3, 'AVAILABLE', '1'), section: '10'},
    {...seat(4, 'AVAILABLE', '1'), section: '2'}]);
  const labels = value.elements.filter(node => node.className === 'row-label').map(node => node.textContent);
  assert.deepEqual(labels, ['2 / 1', '2 / 2', '2 / 10', '10 / 1']);
  value.map.dispose();
});

test('hall groups rows, sorts seats, marks aisles and exposes accessible status and price', () => {
  const value = fixture();
  value.map.update([seat(2), seat(1), ...Array.from({length: 9}, (_, i) => seat(i + 3)), seat(20, 'SOLD', 'B')]);
  const rows = value.elements.filter(node => node.className === 'hall-row');
  assert.equal(rows.length, 2);
  assert.equal(rows[0].children[1].children[0].textContent, 1);
  assert.match(value.button(11).className, /aisle/);
  assert.equal(value.button(20).disabled, true);
  assert.match(value.button(20).className, /occupied/);
  assert.match(value.button(1).attributes['aria-label'], /row A, seat 1 — AVAILABLE — 25/);
  value.button(1).listeners.click();
  assert.equal(value.selections[0].id, 'seat-1');
  assert.equal(value.button(1).attributes['aria-pressed'], 'true');
  value.button(2).listeners.click();
  assert.equal(value.button(1).attributes['aria-pressed'], 'false');
  assert.equal(value.button(2).attributes['aria-pressed'], 'true');
});

test('occupied and busy seats cannot select and server updates invalidate a lost selection', () => {
  const value = fixture();
  value.map.update([seat(1), seat(2, 'RESERVED')]);
  value.button(2).listeners.click();
  assert.equal(value.selections.length, 0);
  value.map.setBusy(true);
  value.button(1).listeners.click();
  assert.equal(value.selections.length, 0);
  assert.equal(value.button(1).disabled, true);
  value.map.setBusy(false);
  assert.equal(value.button(2).disabled, true);
  value.button(1).listeners.click();
  value.map.update([seat(1)]);
  assert.equal(value.button(1).attributes['aria-pressed'], 'true');
  value.map.update([seat(1, 'SOLD')]);
  assert.equal(value.selections.at(-1), null);
  assert.equal(value.elements.some(node => node.textContent.includes('no longer available')), true);
});

test('removed seat invalidates selection and disposed hall ignores updates and old handlers', () => {
  const value = fixture();
  value.map.update([seat(1)]);
  value.button(1).listeners.click();
  value.map.update([]);
  assert.equal(value.selections.at(-1), null);
  value.map.dispose();
  const count = value.elements.length;
  value.map.update([seat(2)]);
  value.button(1).listeners.click();
  assert.equal(value.elements.length, count);
  assert.equal(value.selections.length, 2);
  assert.equal(value.elements[0].removed, true);
});
