import { $, el } from './dom.js';
import { createIcon } from './icons.js';
import { ago } from './format.js';
import { onPageChange } from './router.js';
import { showSnackbar } from './snackbar.js';
import { isArchivedTask, openSession, taskStatusKey, taskStatusLabel, taskSummary } from './agent-sessions.js';

const ORDER = ['done', 'failed', 'interrupted', 'ended'];
let timer = 0;
let archived = [];
let filter = 'all';

function row(s) {
  const item = el('li', 'list-item archive-item state-layer');
  item.tabIndex = 0;
  const icon = el('span', 'list-icon');
  icon.append(createIcon('build'));
  const text = el('div', 'list-text');
  const head = el('div', 'list-headline body-large archive-head');
  head.append(el('span', 'archive-name', s.name), el('span', `kind-chip label-small task-${taskStatusKey(s)}`, taskStatusLabel(s)));
  text.append(head, el('div', 'list-supporting body-medium archive-summary', taskSummary(s)));
  item.append(icon, text, el('span', 'list-trailing label-medium', s.lastActive ? ago(s.lastActive) : ''));
  const open = () => openSession(s.session);
  item.addEventListener('click', open);
  item.addEventListener('keydown', e => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      open();
    }
  });
  return item;
}

function chip(key, text) {
  const button = el('button', 'state-layer', text);
  button.type = 'button';
  button.setAttribute('role', 'radio');
  button.setAttribute('aria-checked', String(filter === key));
  button.addEventListener('click', () => {
    filter = key;
    render();
  });
  return button;
}

function render() {
  const groups = new Map();
  for (const s of archived) {
    const key = taskStatusKey(s);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(s);
  }
  if (!groups.has(filter)) filter = 'all';
  const rank = key => (ORDER.includes(key) ? ORDER.indexOf(key) : ORDER.length);
  const keys = [...groups.keys()].sort((a, b) => rank(a) - rank(b));
  const box = $('#archive-filter');
  box.replaceChildren(chip('all', `全部 ${archived.length}`), ...keys.map(k => chip(k, `${taskStatusLabel(groups.get(k)[0])} ${groups.get(k).length}`)));
  box.hidden = archived.length === 0;
  $('#archive-count').textContent = archived.length ? `${archived.length} 个` : '';
  $('#archive-list').replaceChildren(...(filter === 'all' ? archived : groups.get(filter)).map(row));
  $('#archive-empty').hidden = archived.length > 0;
}

async function load() {
  try {
    const res = await fetch('/api/agent/sessions', { cache: 'no-store' });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error ?? `HTTP ${res.status}`);
    const busy = new Set(data.active.map(t => t.session));
    archived = data.sessions.map(s => ({ ...s, busy: busy.has(s.session) })).filter(isArchivedTask)
      .sort((a, b) => (b.lastActive ?? 0) - (a.lastActive ?? 0));
  } catch (err) {
    showSnackbar(`读取归档会话失败：${err.message}`, 'error');
  }
  render();
}

export function initSettingsArchive() {
  $('#archive-refresh').addEventListener('click', load);
  onPageChange(page => {
    clearInterval(timer);
    if (page !== 'settings') return;
    load();
    timer = setInterval(load, 60_000);
  });
}
