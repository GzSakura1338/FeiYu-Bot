import { $, $$, el, poll } from './dom.js';
import { classify, renderContent, rememberName } from './message.js';
import { showSnackbar } from './snackbar.js';
import { createAvatar } from './avatar.js';
import { currentPage, onPageChange } from './router.js';

const MAX_ROWS = 1000;
const BADGES = { message: '消息', info: '信息', warn: '警告', error: '错误' };

let lastId = 0;
let loaded = false;
let filter = 'all';
let autoScroll = true;
let unread = 0;

const list = () => $('#log-list');

function matches(kind) {
  if (filter === 'all') return true;
  if (filter === 'message') return kind === 'message';
  return kind === 'warn' || kind === 'error';
}

function renderRow(entry, info) {
  const row = el('div', 'log-row');
  row.dataset.kind = info.kind;
  row.hidden = !matches(info.kind);

  const body = el('div', 'log-body');
  if (info.kind === 'message') {
    rememberName(info.qq, info.name);
    const text = el('div', 'log-text body-medium');
    text.append(renderContent(info.content));
    const meta = el('div', 'log-meta label-medium');
    meta.append(createAvatar(info.qq, info.name, 'avatar avatar-xs'), `${info.where} · ${info.name || '未知'}（${info.qq}）`);
    body.append(meta, text);
  } else {
    body.append(el('div', 'log-text body-medium', entry.text));
  }
  row.append(el('span', 'log-time label-medium', entry.time), el('span', 'log-badge label-small', BADGES[info.kind]), body);
  return row;
}

function renderBadge() {
  const badge = $('#logs-badge');
  badge.hidden = unread === 0;
  badge.textContent = unread > 99 ? '99+' : String(unread);
}

function updateMeta() {
  const visible = list().querySelectorAll('.log-row:not([hidden])').length;
  $('#log-count').textContent = `${visible} 条`;
  $('#log-empty').hidden = visible > 0;
}

function scrollToBottom() {
  list().scrollTop = list().scrollHeight;
}

function moveTabIndicator() {
  const tab = $(`#log-filters .tab[aria-selected="true"]`);
  if (!tab || !tab.offsetWidth) return;
  const indicator = $('#log-filters .tab-indicator');
  indicator.style.width = `${tab.offsetWidth}px`;
  indicator.style.transform = `translateX(${tab.offsetLeft}px)`;
}

export function setLogFilter(next) {
  filter = next;
  for (const tab of $$('#log-filters .tab')) tab.setAttribute('aria-selected', String(tab.dataset.filter === next));
  moveTabIndicator();
  for (const row of list().querySelectorAll('.log-row')) row.hidden = !matches(row.dataset.kind);
  updateMeta();
  if (autoScroll) scrollToBottom();
}

function setAutoScroll(on) {
  autoScroll = on;
  $('#btn-autoscroll').setAttribute('aria-pressed', String(on));
}

async function refresh() {
  const res = await fetch(`/api/logs?after=${lastId}`, { cache: 'no-store' });
  if (!res.ok) return;
  const { latestId, entries } = await res.json();

  if (latestId < lastId) {
    lastId = 0;
    list().append(el('div', 'log-divider label-medium', 'FeiYu Bot 后端已重启'));
    return refresh();
  }

  if (entries.length) {
    const rows = [];
    let newMessages = 0;
    for (const entry of entries) {
      const info = classify(entry);
      const row = renderRow(entry, info);
      if (loaded) row.classList.add('new');
      rows.push(row);
      if (info.kind === 'message') newMessages++;
      lastId = entry.id;
    }
    list().append(...rows);
    while (list().childElementCount > MAX_ROWS) list().firstElementChild.remove();
    updateMeta();
    if (autoScroll) scrollToBottom();
    if (loaded && newMessages && currentPage() !== 'logs') {
      unread += newMessages;
      renderBadge();
    }
  }
  loaded = true;
}

export function initLogs() {
  for (const tab of $$('#log-filters .tab')) tab.addEventListener('click', () => setLogFilter(tab.dataset.filter));
  new ResizeObserver(moveTabIndicator).observe($('#log-filters'));

  $('#btn-autoscroll').addEventListener('click', () => {
    setAutoScroll(!autoScroll);
    if (autoScroll) scrollToBottom();
  });
  list().addEventListener('scroll', () => {
    const atBottom = list().scrollTop + list().clientHeight >= list().scrollHeight - 8;
    if (atBottom !== autoScroll) setAutoScroll(atBottom);
  });

  $('#btn-clear').addEventListener('click', () => {
    list().replaceChildren();
    updateMeta();
    showSnackbar('已清空显示，新日志会继续出现', 'success');
  });

  onPageChange(page => {
    if (page !== 'logs') return;
    unread = 0;
    renderBadge();
    requestAnimationFrame(moveTabIndicator);
    if (autoScroll) requestAnimationFrame(scrollToBottom);
  });

  updateMeta();
  poll(refresh, 1000);
}
