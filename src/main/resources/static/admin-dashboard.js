import {adminApi} from './admin-api.js';
import {mountLoadControls} from './load-controls.js';
import {mountActivity} from './activity-panel.js';
import {mountJournal} from './journal-panel.js';
import {mountEvents} from './event-panel.js';

export function mountAdminDashboard(parent, mountOverview = () => () => {}, mountLog = mountJournal) {
  const section = document.createElement('section');
  const node = (tag, text, target = section) => {
    const element = document.createElement(tag);
    if (text !== undefined) element.textContent = text;
    target.append(element);
    return element;
  };
  node('h2', 'Administration');
  const form = node('form');
  const keyLabel = node('label', 'Administrator key ', form);
  const key = node('input', undefined, keyLabel);
  Object.assign(key, {type: 'password', required: true, minLength: 32, maxLength: 512, autocomplete: 'off'});
  const concertLabel = node('label', 'Concert ID ', form);
  const concert = node('input', undefined, concertLabel);
  Object.assign(concert, {required: true, maxLength: 36});
  const open = node('button', 'Open dashboard', form);
  open.type = 'submit';
  const close = node('button', 'Close dashboard');
  close.type = 'button';
  close.disabled = true;
  const message = node('p');
  message.setAttribute('role', 'status');
  const content = node('div');
  content.className = 'admin-layout';
  parent.append(section);
  let api;
  let cleanup = [];
  let generation = 0;
  let disposed = false;
  function clear() {
    generation++;
    api?.close();
    api = undefined;
    for (const dispose of cleanup) dispose();
    cleanup = [];
    content.replaceChildren();
    key.value = '';
    open.disabled = false;
    close.disabled = true;
  }
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (disposed || open.disabled) return;
    const credential = key.value;
    clear();
    const version = generation;
    open.disabled = true;
    close.disabled = false;
    message.textContent = 'Authenticating…';
    try {
      api = adminApi(credential);
      const id = concert.value.trim();
      await api.stats(id);
      if (disposed || version !== generation) return;
      cleanup.push(mountOverview(api, id, content));
      cleanup.push(mountLoadControls(api, id, content));
      cleanup.push(mountActivity(api, id, content));
      cleanup.push(mountLog(api, id, content));
      cleanup.push(mountEvents(api, id, content));
      message.textContent = `Concert ${id} — administrator session open`;
    } catch (error) {
      if (!disposed && version === generation) {
        clear();
        message.textContent = error.message;
      }
    }
  });
  close.addEventListener('click', () => { clear(); message.textContent = 'Dashboard closed.'; });
  return () => { disposed = true; clear(); section.remove(); };
}
