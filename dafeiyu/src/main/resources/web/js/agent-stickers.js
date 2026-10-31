import { $, $$, el } from './dom.js';
import { createIcon } from './icons.js';
import { showSnackbar } from './snackbar.js';
import { confirmDialog } from './dialog.js';
import { openMenu, closeMenu, isMenuOpen } from './menu.js';
import { onAgentViewChange } from './agent-page.js';

const STATUS = { new: '下载中…', saved: '等她认图…', failed: '下载失败', unsafe: '不合适，她不会用' };

const PAGE = 120;

let filter = 'all';
let stickers = [];
let vision = true;
let shown = 0;
let watcher = null;
let hasMore = false;
let fetching = false;
let generation = 0;
let skew = 0;

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
  node.dataset.id = s.id;
  node.dataset.status = s.hidden ? 'hidden' : s.status;
  const frame = el('span', 'sticker-frame');
  if (s.file) {
    const img = el('img');
    const thumb = `/api/agent/stickers/${s.id}/thumb`;
    img.src = thumb;
    img.alt = s.description || `表情包 ${s.id}`;
    img.loading = 'lazy';
    img.decoding = 'async';
    if ((s.mime ?? '').includes('gif')) {
      node.addEventListener('pointerenter', () => { img.src = `/api/agent/stickers/${s.id}/image`; });
      node.addEventListener('pointerleave', () => { img.src = thumb; });
    }
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

function inFilter(s) {
  if (filter === 'hidden') return s.hidden || s.status === 'unsafe';
  if (s.hidden) return false;
  return filter === 'all' ? s.status !== 'unsafe' : s.source === filter;
}

async function fetchMore() {
  if (fetching || !hasMore) return;
  const run = generation;
  fetching = true;
  let data;
  try {
    data = await request('GET', `?filter=${filter}&offset=${stickers.length - skew}&limit=${PAGE}`);
  } catch (err) {
    showSnackbar(`读取表情包失败：${err.message}`, 'error');
    return;
  } finally {
    fetching = false;
  }
  if (run !== generation) return;
  const known = new Set(stickers.map(s => s.id));
  stickers.push(...data.stickers.filter(s => !known.has(s.id)));
  hasMore = data.more;
  more();
}

function more() {
  if (shown >= stickers.length) {
    fetchMore();
    return;
  }
  const next = stickers.slice(shown, shown + PAGE);
  shown += next.length;
  $('#sticker-grid').append(...next.map(card));
  requestAnimationFrame(() => {
    const end = $('#sticker-more');
    if (end && end.offsetParent !== null && end.getBoundingClientRect().top < window.innerHeight + 600) more();
  });
}

function render() {
  $('#sticker-grid').replaceChildren();
  shown = 0;
  more();
  $('#sticker-empty').hidden = stickers.length > 0;
}

function replaceCard(s) {
  const index = stickers.findIndex(x => x.id === s.id);
  if (index >= 0) {
    skew += inFilter(stickers[index]) - inFilter(s);
    stickers[index] = s;
  }
  $(`#sticker-grid .sticker-card[data-id="${s.id}"]`)?.replaceWith(card(s));
}

function dropCard(id) {
  const index = stickers.findIndex(x => x.id === id);
  if (index < 0) return;
  if (!inFilter(stickers[index])) skew--;
  stickers.splice(index, 1);
  const node = $(`#sticker-grid .sticker-card[data-id="${id}"]`);
  if (node) {
    node.remove();
    shown--;
  }
  $('#sticker-empty').hidden = stickers.length > 0;
}

function watchBottom() {
  const grid = $('#sticker-grid');
  let end = $('#sticker-more');
  if (!end) {
    end = el('div');
    end.id = 'sticker-more';
    end.style.height = '1px';
    grid.after(end);
  }
  watcher = new IntersectionObserver(entries => {
    if (entries.some(e => e.isIntersecting)) more();
  }, { root: grid.closest('.content'), rootMargin: '600px 0px' });
  watcher.observe(end);
}

async function load() {
  const run = ++generation;
  try {
    const data = await request('GET', `?filter=${filter}&offset=0&limit=${PAGE}`);
    if (run !== generation) return;
    stickers = data.stickers;
    vision = data.vision;
    hasMore = data.more;
  } catch (err) {
    if (run !== generation) return;
    showSnackbar(`读取表情包失败：${err.message}`, 'error');
    hasMore = false;
  }
  skew = 0;
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
        replaceCard({ ...s, sentCount: (s.sentCount ?? 0) + 1 });
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
    if (e.key === 'Escape') replaceCard(s);
  });
  input.addEventListener('blur', () => setTimeout(() => {
    if (anchor.isConnected) replaceCard(stickers.find(x => x.id === s.id) ?? s);
  }, 150));
}

async function update(s, patch, message) {
  try {
    replaceCard(await request('PUT', `/${s.id}`, patch));
    showSnackbar(message, 'success');
  } catch (err) {
    showSnackbar(`保存失败：${err.message}`, 'error');
    replaceCard(s);
  }
}

async function remove(s) {
  const ok = await confirmDialog({ icon: 'delete', title: '删除这张表情包？', text: '删掉后她就不会再用了；群里有人再发时会重新收进来。', confirm: '删除' });
  if (!ok) return;
  try {
    await request('DELETE', `/${s.id}`, {});
    showSnackbar('已删除', 'success');
    dropCard(s.id);
  } catch (err) {
    showSnackbar(`删除失败：${err.message}`, 'error');
  }
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
  watchBottom();
}
