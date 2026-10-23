import { $, el, poll } from './dom.js';
import { plainContent, rememberName } from './message.js';
import { createAvatar } from './avatar.js';
import { setLogFilter } from './logs.js';

let lastTopId = null;
let lastToday = null;

const pad = n => String(n).padStart(2, '0');

function timeLabel(ms) {
  const d = new Date(ms);
  const now = new Date();
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  return d.toDateString() === now.toDateString() ? hm : `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${hm}`;
}

function whereLabel(m) {
  if (m.messageType === 'group') return `群 ${m.groupId}`;
  return m.direction === 'out' ? `私聊 ${m.groupId}` : '私聊';
}

function renderItem(m, fresh) {
  if (m.direction !== 'out') rememberName(m.userId, m.senderName);
  const item = el('li', fresh ? 'list-item recent-item new' : 'list-item recent-item');
  if (m.direction === 'out') item.classList.add('outgoing');
  const text = el('div', 'list-text');
  const headline = el('div', 'list-headline body-large');
  headline.append(el('span', null, m.senderName || m.userId), el('span', 'where body-small', whereLabel(m)));
  if (m.direction === 'out') headline.append(el('span', 'out-tag label-small', 'Agent 回复'));
  text.append(headline, el('div', 'list-supporting body-medium', plainContent(m.content)));
  item.append(createAvatar(m.userId, m.senderName, 'avatar title-medium'), text,
              el('span', 'list-trailing label-small', timeLabel(m.time)));
  return item;
}

function bump(node, text) {
  if (node.textContent === text) return;
  node.textContent = text;
  node.classList.remove('bump');
  void node.offsetWidth;
  node.classList.add('bump');
}

async function refresh() {
  const res = await fetch('/api/messages?limit=6', { cache: 'no-store' });
  if (!res.ok) return;
  const { stats, messages } = await res.json();

  const topId = messages[0]?.id ?? 0;
  if (topId !== lastTopId) {
    const known = lastTopId ?? topId;
    $('#recent-list').replaceChildren(...messages.map(m => renderItem(m, lastTopId !== null && m.id > known)));
    $('#recent-empty').hidden = messages.length > 0;
    lastTopId = topId;
  }
  if (stats.today !== lastToday) {
    bump($('#stat-messages'), String(stats.today));
    lastToday = stats.today;
  }
  $('#stat-messages-total').textContent = `累计 ${stats.total} 条`;
}

export function initMessages() {
  $('#recent-more').addEventListener('click', () => setLogFilter('message'));
  poll(refresh, 3000);
}
