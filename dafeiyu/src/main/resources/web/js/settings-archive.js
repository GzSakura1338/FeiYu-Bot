import { $, el } from './dom.js';
import { createIcon } from './icons.js';
import { ago } from './format.js';
import { onPageChange } from './router.js';
import { showSnackbar } from './snackbar.js';
import { isArchivedTask, openSession, taskStatusKey, taskStatusLabel, taskSummary } from './agent-sessions.js';

let timer = 0;

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

async function load() {
  let archived = [];
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
  $('#archive-list').replaceChildren(...archived.map(row));
  $('#archive-empty').hidden = archived.length > 0;
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
