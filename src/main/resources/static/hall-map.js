export function mountHallMap(parent, onSelect) {
  const hall = document.createElement('section');
  hall.className = 'concert-hall';
  hall.setAttribute('aria-label', 'Concert hall seat selection');
  const node = (tag, text, target = hall) => {
    const element = document.createElement(tag);
    if (text !== undefined) element.textContent = text;
    target.append(element);
    return element;
  };
  node('h2', 'Choose your seat');
  const stage = node('div', 'STAGE');
  stage.className = 'hall-stage';
  const legend = node('p', 'Gray: available · Red: occupied · Blue: selected');
  legend.className = 'seat-legend';
  const plan = node('div');
  plan.className = 'hall-plan';
  const summary = node('p', 'Select an available seat to see its price.');
  summary.setAttribute('role', 'status');
  parent.append(hall);
  let seats = [];
  let selected;
  let disabled = false;
  let disposed = false;
  const buttons = new Map();
  function choose(seat) {
    if (disposed || disabled || seat.status !== 'AVAILABLE') return;
    selected = seat.id;
    for (const [id, button] of buttons) {
      button.setAttribute('aria-pressed', String(id === selected));
      button.classList.toggle('selected', id === selected);
    }
    summary.textContent = `Section ${seat.section} · Row ${seat.row} · Seat ${seat.number} · Price ${seat.price}`;
    onSelect(seat);
  }
  function update(items) {
    if (disposed) return;
    const previous = selected;
    seats = items;
    plan.replaceChildren();
    buttons.clear();
    const groups = new Map();
    for (const seat of seats) {
      const key = JSON.stringify([seat.section, seat.row]);
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(seat);
    }
    const rows = [...groups.values()].sort((a, b) => a[0].section.localeCompare(b[0].section, 'en', {numeric: true})
      || a[0].row.localeCompare(b[0].row, 'en', {numeric: true}));
    for (const rowSeats of rows) {
      rowSeats.sort((a, b) => a.number - b.number);
      const row = node('div', undefined, plan);
      row.className = 'hall-row';
      node('span', `${rowSeats[0].section} / ${rowSeats[0].row}`, row).className = 'row-label';
      const blocks = node('div', undefined, row);
      blocks.className = 'seat-blocks';
      rowSeats.forEach((seat, index) => {
        const button = node('button', seat.number, blocks);
        button.type = 'button';
        button.className = `hall-seat ${seat.status === 'AVAILABLE' ? 'available' : 'occupied'}`;
        if (index > 0 && index % 10 === 0) button.classList.add('aisle');
        button.title = `${seat.section}, row ${seat.row}, seat ${seat.number} — ${seat.status} — ${seat.price}`;
        button.setAttribute('aria-label', button.title);
        button.setAttribute('aria-pressed', String(seat.id === selected));
        button.classList.toggle('selected', seat.id === selected);
        button.disabled = disabled || seat.status !== 'AVAILABLE';
        button.addEventListener('click', () => choose(seat));
        buttons.set(seat.id, button);
      });
    }
    if (previous && !seats.some(seat => seat.id === previous && seat.status === 'AVAILABLE')) {
      selected = undefined;
      summary.textContent = 'Your selected seat is no longer available. Please choose another.';
      onSelect(null);
    }
  }
  return {
    update,
    setBusy(value) {
      disabled = value;
      for (const seat of seats) buttons.get(seat.id).disabled = value || seat.status !== 'AVAILABLE';
    },
    dispose() { disposed = true; buttons.clear(); hall.remove(); },
  };
}
