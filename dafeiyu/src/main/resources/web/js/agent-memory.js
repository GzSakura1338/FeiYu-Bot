import { $, el } from './dom.js';
import { createIcon } from './icons.js';
import { showSnackbar } from './snackbar.js';
import { confirmDialog } from './dialog.js';
import { createAvatar, createGroupAvatar } from './avatar.js';
import { ago, formatTime } from './format.js';
import { onAgentViewChange } from './agent-page.js';

const GROUPS = [
  { title: '她自己', test: s => s.kind === 'self' || s.kind === 'diary' },
  { title: '主人', test: s => s.owner },
  { title: '认识的人', test: s => s.kind === 'user' && !s.owner },
  { title: '群', test: s => s.kind === 'group' },
];
const HINTS = {
  self: '她对自己的认识：喜好、经历、心情变化',
  diary: '每次回顾时写的小日记，聊天时会想起最近两篇',
  user: '她记得的关于这个人的事，聊天时对方在场就会想起来',
  group: '这个群的梗和氛围，在这个群聊天时会想起来',
};

let state = null;
let selected = 'self';
let items = [];

async function request(method, path = '', body) {
  const res = await fetch(`/api/agent/memory${path}`, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`);
  return json;
}

function scopeAvatar(s, className = 'avatar') {
  if (s.kind === 'user') return createAvatar(s.target, s.name, className);
  if (s.kind === 'group') return createGroupAvatar(s.target, s.name, className);
  const node = el('span', `${className} avatar-icon`);
  if (s.kind === 'self' && state?.persona?.avatar) {
    const img = el('img');
    img.src = state.persona.avatar;
    img.alt = '';
    node.append(img);
  } else {
    node.append(createIcon(s.kind === 'diary' ? 'auto_stories' : 'psychology', 'icon-18'));
  }
  return node;
}

function scopeName(s) {
  if (s.kind === 'self') return `关于${state?.persona?.name ?? '她'}自己`;
  return s.name;
}

function renderScopes() {
  const list = $('#memory-scopes');
  const none = !state?.persona;
  $('#memory-none').hidden = !none;
  list.hidden = none;
  $('#memory-form').hidden = none;
  if (none) {
    list.replaceChildren();
    return;
  }
  const nodes = [];
  for (const group of GROUPS) {
    const scopes = state.scopes.filter(group.test);
    if (!scopes.length) continue;
    nodes.push(el('li', 'scope-heading label-medium', group.title));
    for (const s of scopes) {
      const item = el('li', 'session-item state-layer');
      item.setAttribute('role', 'option');
      item.setAttribute('aria-selected', String(s.scope === selected));
      item.tabIndex = 0;
      const text = el('div', 'session-item-text');
      const head = el('div', 'session-item-head');
      head.append(el('span', 'title-small session-item-name', scopeName(s)));
      head.append(el('span', 'label-small session-item-time', s.updatedAt ? ago(s.updatedAt) : ''));
      text.append(head, el('div', 'body-small session-item-preview', s.count ? `${s.count} 条` : '还没有'));
      item.append(scopeAvatar(s), text);
      const open = () => select(s.scope);
      item.addEventListener('click', open);
      item.addEventListener('keydown', e => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          open();
        }
      });
      nodes.push(item);
    }
  }
  list.replaceChildren(...nodes);
}

function renderControls() {
  $('#memory-auto').setAttribute('aria-checked', String(Boolean(state?.auto)));
  $('#memory-auto-hint').textContent = state?.auto ? '聊过一阵后自己总结、记日记' : '已关闭，只在手动回顾时记';
  const pending = state?.pending ?? [];
  const messages = pending.reduce((sum, p) => sum + p.count, 0);
  const busy = pending.some(p => p.running);
  $('#memory-pending').textContent = pending.length
    ? `${pending.length} 个会话里有 ${messages} 条新消息还没回顾${busy ? '（正在回顾…）' : ''}`
    : '最近的聊天都回顾过了';
  const button = $('#memory-reflect');
  if (!button.dataset.loading) button.disabled = !state?.persona || !pending.length;
}

function currentScope() {
  return state?.scopes.find(s => s.scope === selected) ?? { scope: selected, kind: selected.split(':')[0], target: selected.split(':')[1] ?? '', name: selected };
}

function renderHeader() {
  const s = currentScope();
  $('#memory-avatar').replaceChildren(scopeAvatar(s, 'avatar session-avatar-img'));
  $('#memory-title').textContent = scopeName(s);
  const parts = [HINTS[s.kind] ?? ''];
  if (s.kind === 'user' || s.kind === 'group') parts.push(`${s.kind === 'user' ? 'QQ' : '群号'} ${s.target}`);
  $('#memory-sub').textContent = parts.filter(Boolean).join(' · ');
  $('#memory-input').placeholder = s.kind === 'diary' ? '替她补一篇日记，Enter 保存' : '手动加一条记忆，Enter 保存';
}

function memoryItem(m) {
  const row = el('div', 'memory-item new');
  const text = el('div', 'memory-item-text');
  const content = el('div', 'memory-item-content body-large', m.content);
  const meta = [selected === 'diary' ? formatTime(m.createdAt) : ago(m.updatedAt)];
  if (m.sourceName) meta.push(m.source === 'manual' ? m.sourceName : `来自 ${m.sourceName}`);
  text.append(content, el('div', 'memory-item-meta body-small', meta.join(' · ')));
  const actions = el('div', 'memory-item-actions');
  actions.append(iconButton('edit', '修改', () => edit(m, row, text)), iconButton('delete', '删除', () => remove(m)));
  row.append(text, actions);
  return row;
}

function iconButton(icon, label, onClick) {
  const button = el('button', 'icon-button state-layer');
  button.type = 'button';
  button.title = button.ariaLabel = label;
  button.append(createIcon(icon));
  button.addEventListener('click', onClick);
  return button;
}

function renderItems() {
  const list = $('#memory-list');
  if (!state?.persona) {
    list.replaceChildren();
    return;
  }
  list.replaceChildren(...(items.length
    ? items.map(memoryItem)
    : [el('div', 'empty-state body-medium', selected === 'diary' ? '还没有日记，回顾一次就会写' : '这里还没有记忆')]));
}

function edit(m, row, text) {
  const area = el('textarea', 'body-large');
  area.value = m.content;
  const save = el('button', 'filled-button filled-button-medium label-large state-layer', '保存');
  const cancel = el('button', 'text-button label-large state-layer', '取消');
  save.type = cancel.type = 'button';
  const bar = el('div', 'dialog-actions');
  bar.append(cancel, save);
  const editor = el('div', 'memory-item-text');
  editor.append(area, bar);
  row.replaceChildren(editor);
  area.focus();
  cancel.addEventListener('click', () => renderItems());
  save.addEventListener('click', async () => {
    try {
      items = (await request('PUT', `/items/${m.id}`, { content: area.value })).items;
      renderItems();
      await loadState();
    } catch (err) {
      showSnackbar(`保存失败：${err.message}`, 'error');
    }
  });
}

async function remove(m) {
  const ok = await confirmDialog({ icon: 'delete', title: '删除这条记忆？', text: m.content, confirm: '删除' });
  if (!ok) return;
  try {
    items = (await request('DELETE', `/items/${m.id}`, {})).items;
    renderItems();
    await loadState();
  } catch (err) {
    showSnackbar(`删除失败：${err.message}`, 'error');
  }
}

async function loadItems() {
  if (!state?.persona) return;
  const scope = selected;
  try {
    const data = await request('GET', `/items?scope=${encodeURIComponent(scope)}`);
    if (scope !== selected) return;
    items = data.items;
  } catch (err) {
    showSnackbar(`读取记忆失败：${err.message}`, 'error');
    items = [];
  }
  renderItems();
}

async function loadState() {
  try {
    state = await request('GET');
  } catch (err) {
    showSnackbar(`读取记忆失败：${err.message}`, 'error');
    return;
  }
  if (state.persona && !state.scopes.some(s => s.scope === selected)) selected = 'self';
  renderScopes();
  renderControls();
  renderHeader();
}

async function select(scope) {
  selected = scope;
  items = [];
  renderScopes();
  renderHeader();
  await loadItems();
}

async function add(e) {
  e?.preventDefault();
  const input = $('#memory-input');
  const content = input.value.trim();
  if (!content) return;
  try {
    items = (await request('POST', '/items', { scope: selected, content })).items;
    input.value = '';
    input.style.height = 'auto';
    $('#memory-add').disabled = true;
    renderItems();
    await loadState();
  } catch (err) {
    showSnackbar(`添加失败：${err.message}`, 'error');
  }
}

async function reflect() {
  const button = $('#memory-reflect');
  button.dataset.loading = '1';
  button.disabled = true;
  $('span', button).textContent = '回顾中…';
  try {
    const { outcomes } = await request('POST', '/reflect', {});
    const changed = outcomes.reduce((n, o) => n + o.added + o.updated + o.deleted, 0);
    showSnackbar(outcomes.length ? `回顾了 ${outcomes.length} 个会话，记忆变动 ${changed} 条` : '没有需要回顾的聊天', 'success');
  } catch (err) {
    showSnackbar(err.message, 'error');
  } finally {
    delete button.dataset.loading;
    $('span', button).textContent = '现在回顾';
  }
  await loadState();
  await loadItems();
}

async function toggleAuto() {
  try {
    state = await request('PUT', '/auto', { enabled: !state?.auto });
    renderControls();
  } catch (err) {
    showSnackbar(`切换失败：${err.message}`, 'error');
  }
}

async function refresh() {
  await loadState();
  await loadItems();
}

export function initAgentMemory() {
  onAgentViewChange(view => {
    if (view === 'memory') refresh();
  });
  $('#memory-refresh').addEventListener('click', refresh);
  $('#memory-reflect').addEventListener('click', reflect);
  $('#memory-auto').addEventListener('click', toggleAuto);
  const input = $('#memory-input');
  input.addEventListener('input', () => {
    input.style.height = 'auto';
    input.style.height = `${Math.min(input.scrollHeight, 160)}px`;
    $('#memory-add').disabled = !input.value.trim();
  });
  input.addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) add(e);
  });
  $('#memory-form').addEventListener('submit', add);
}
