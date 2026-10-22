import { $, $$, el } from './dom.js';
import { createIcon } from './icons.js';
import { showSnackbar } from './snackbar.js';
import { confirmDialog } from './dialog.js';
import { openMenu, closeMenu, isMenuOpen } from './menu.js';
import { onAgentViewChange } from './agent-page.js';

const STATUS = { new: '下载中…', saved: '等她认图…', failed: '下载失败', unsafe: '不合适，她不会用' };

let filter = 'all';
let stickers = [];
let vision = true;

async function request(method, path = '', body) {
  const res = await fetch(`/api/agent/stickers${path}`, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`);
  return json;
}

function caption(s) {
  if (s.status === 'ready' && s.description) return s.description;
  if (s.status === 'saved' && !vision) return '模型不支持看图，可以手动写说明';
  return STATUS[s.status] ?? s.description ?? '';
}

function card(s) {
  const node = el('button', 'sticker-card state-layer');
  node.type = 'button';
  node.dataset.status = s.hidden ? 'hidden' : s.status;
  const frame = el('span', 'sticker-frame');
  if (s.file) {
    const img = el('img');
    img.src = `/api/agent/stickers/${s.id}/image`;
    img.alt = s.description || `表情包 ${s.id}`;
    img.loading = 'lazy';
    frame.append(img);
  } else {
    frame.append(createIcon('mood'));
  }
  const meta = [];
  meta.push(s.source === 'favorite' ? 'QQ 收藏' : s.source === 'collected' ? '她收藏的' : `见过 ${s.seenCount} 次`);
  if (s.sentCount) meta.push(`发过 ${s.sentCount} 次`);
  node.append(frame, el('span', 'sticker-caption label-large', caption(s)), el('span', 'sticker-meta label-small', `#${s.id} · ${meta.join(' · ')}`));
  node.addEventListener('click', () => openActions(node, s));
  return node;
}

function render() {
  const grid = $('#sticker-grid');
  grid.replaceChildren(...stickers.map(card));
  $('#sticker-empty').hidden = stickers.length > 0;
}

async function load() {
  try {
    const data = await request('GET', `?filter=${filter}`);
    stickers = data.stickers;
    vision = data.vision;
  } catch (err) {
    showSnackbar(`读取表情包失败：${err.message}`, 'error');
  }
  render();
}

async function qqSessions() {
  const res = await fetch('/api/agent/sessions', { cache: 'no-store' });
  const data = await res.json();
  return data.sessions.filter(s => s.kind === 'group' || s.kind === 'private')
    .map(s => ({ value: s.session, label: s.name, hint: s.kind === 'group' ? '群聊' : '私聊' }));
}

function openActions(anchor, s) {
  if (isMenuOpen(anchor)) return closeMenu();
  const items = [];
  if (s.file) items.push({ value: 'send', label: '发送到…' });
  items.push({ value: 'describe', label: '改说明' });
  items.push({ value: 'hide', label: s.hidden ? '取消隐藏（她又可以用了）' : '隐藏（她不会再用）' });
  items.push({ value: 'delete', label: '删除' });
  openMenu(anchor, items, {
    onSelect: action => {
      if (action === 'send') setTimeout(() => chooseTarget(anchor, s), 0);
      if (action === 'describe') describe(anchor, s);
      if (action === 'hide') update(s, { hidden: !s.hidden }, s.hidden ? '已取消隐藏' : '已隐藏');
      if (action === 'delete') remove(s);
    },
  });
}

async function chooseTarget(anchor, s) {
  openMenu(anchor, await qqSessions(), {
    empty: '还没有 QQ 会话',
    onSelect: async session => {
      try {
        await request('POST', `/${s.id}/send`, { session });
        showSnackbar('已发送', 'success');
        load();
      } catch (err) {
        showSnackbar(`发送失败：${err.message}`, 'error');
      }
    },
  });
}

function describe(anchor, s) {
  const input = el('input', 'sticker-input body-medium');
  input.value = s.description ?? '';
  input.maxLength = 16;
  input.placeholder = '一句话说明，比如 猫猫震惊';
  const caption = $('.sticker-caption', anchor);
  caption.replaceChildren(input);
  input.focus();
  input.addEventListener('click', e => e.stopPropagation());
  input.addEventListener('keydown', e => {
    e.stopPropagation();
    if (e.key === 'Enter') update(s, { description: input.value.trim() }, '已保存');
    if (e.key === 'Escape') render();
  });
  input.addEventListener('blur', () => setTimeout(render, 150));
}

async function update(s, patch, message) {
  try {
    await request('PUT', `/${s.id}`, patch);
    showSnackbar(message, 'success');
  } catch (err) {
    showSnackbar(`保存失败：${err.message}`, 'error');
  }
  load();
}

async function remove(s) {
  const ok = await confirmDialog({ icon: 'delete', title: '删除这张表情包？', text: '删掉后她就不会再用了；群里有人再发时会重新收进来。', confirm: '删除' });
  if (!ok) return;
  try {
    await request('DELETE', `/${s.id}`, {});
    showSnackbar('已删除', 'success');
  } catch (err) {
    showSnackbar(`删除失败：${err.message}`, 'error');
  }
  load();
}

async function sync() {
  const button = $('#stickers-sync');
  button.disabled = true;
  $('span', button).textContent = '同步中…';
  try {
    const { count } = await request('POST', '/sync', {});
    showSnackbar(`收藏里有 ${count} 张表情包，新的会在后台下载、认图`, 'success');
  } catch (err) {
    showSnackbar(`同步失败：${err.message}`, 'error');
  } finally {
    button.disabled = false;
    $('span', button).textContent = '同步收藏';
  }
  load();
}

export function initAgentStickers() {
  onAgentViewChange(view => {
    if (view === 'stickers') load();
  });
  for (const button of $$('#sticker-filter button')) {
    button.addEventListener('click', () => {
      filter = button.dataset.filter;
      for (const b of $$('#sticker-filter button')) b.setAttribute('aria-checked', String(b === button));
      load();
    });
  }
  $('#stickers-refresh').addEventListener('click', load);
  $('#stickers-sync').addEventListener('click', sync);
}
