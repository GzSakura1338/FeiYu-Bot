import { $, $$, el } from './dom.js';
import { createIcon } from './icons.js';
import { showSnackbar } from './snackbar.js';
import { formatTime } from './format.js';
import { reasoningBlock, renderContext, toolCard } from './chat-render.js';
import { renderMood } from './agent-page.js';

export const MIND = 'mind:self';
export const isMind = session => session === MIND;

let status = null;
let timer = 0;

async function api(method, path = '', body) {
  const res = await fetch(`/api/agent/mind${path}`, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`);
  return json;
}

function chip(icon, text, tone = '') {
  const node = el('span', `mind-chip label-large ${tone}`);
  node.append(createIcon(icon, 'icon-18'), el('span', null, text));
  return node;
}

function renderStatus() {
  if (!status || $('#mind-bar').hidden) return;
  const chips = [];
  if (status.why) chips.push(chip('info', status.why));
  else if (!status.enabled) chips.push(chip('neurology', '暂停了：页头「自由活动」关着'));
  else if (status.thinking) chips.push(chip('progress_activity', '正在想…', 'tone-active'));
  else {
    const seconds = Math.max(0, Math.round((status.nextAt - Date.now()) / 1000));
    chips.push(chip('schedule', seconds < 2 ? '马上接着想' : seconds < 90 ? `${seconds} 秒后接着想` : `${Math.round(seconds / 60)} 分钟后接着想`));
  }
  renderContext($('#session-stats'), status, true);
  chips.push(chip('neurology', `这次开机想了 ${status.thoughts} 个念头`));
  chips.push(chip('send', `今天主动去群里说了 ${status.spokeToday} 次`));
  $('#mind-status').replaceChildren(...chips);
  renderHung();
  for (const b of $$('#mind-pace button')) b.setAttribute('aria-checked', String(b.dataset.pace === status.pace));
  if (status.mood) renderMood(status.mood);
}

const placeOf = session => (session?.startsWith('group:') ? `群 ${session.slice(6)}` : session?.startsWith('private:') ? '私聊' : session ?? '');

function hungItem(icon, text, meta, tone = '') {
  const item = el('li', `mind-hung-item ${tone}`);
  item.append(createIcon(icon, 'icon-18'), el('span', 'mind-hung-text', text), el('span', 'mind-hung-meta label-small', meta));
  return item;
}

let closedOpen = false;

function closedFold(count) {
  const item = el('li', 'mind-hung-item mind-hung-fold state-layer');
  item.tabIndex = 0;
  item.setAttribute('role', 'button');
  item.setAttribute('aria-expanded', String(closedOpen));
  item.append(createIcon('check_circle', 'icon-18'), el('span', 'mind-hung-text', `办完的事 ${count} 件`),
    createIcon('arrow_drop_down', 'icon-18 mind-hung-chevron'));
  const toggle = () => {
    closedOpen = !closedOpen;
    renderHung();
  };
  item.addEventListener('click', toggle);
  item.addEventListener('keydown', e => (e.key === 'Enter' || e.key === ' ') && (e.preventDefault(), toggle()));
  return item;
}

function renderHung() {
  const list = $('#mind-hung');
  const notes = status.notes ?? [];
  const commitments = status.commitments ?? [];
  const open = commitments.filter(c => c.status === 'open');
  const closed = commitments.filter(c => c.status !== 'open').sort((a, b) => b.updatedAt - a.updatedAt).slice(0, 10);
  const items = [
    ...notes.map(n => hungItem('send', `交给${placeOf(n.session)}去说：${n.intent}`, `等那边说 · ${formatTime(n.at)}`, 'tone-note')),
    ...open.map(c => hungItem('schedule', `#${c.id} ${c.what}`, `${c.who} · ${placeOf(c.session)} · ${formatTime(c.createdAt)}`)),
  ];
  if (closed.length) {
    items.push(closedFold(closed.length));
    if (closedOpen) {
      items.push(...closed.map(c => hungItem(c.status === 'done' ? 'check_circle' : 'close', `#${c.id} ${c.what}${c.result ? ` → ${c.result}` : ''}`,
        `${c.status === 'done' ? '办完了' : '不办了'} · ${formatTime(c.updatedAt)}`, 'tone-closed')));
    }
  }
  const focused = document.activeElement?.classList.contains('mind-hung-fold');
  list.hidden = !items.length;
  list.replaceChildren(...items);
  if (focused) $('.mind-hung-fold', list)?.focus();
}

export async function loadMindStatus() {
  try {
    status = await api('GET');
    renderStatus();
  } catch (err) {
    showSnackbar(`读取内心状态失败：${err.message}`, 'error');
  }
}

export function showMindBar(on) {
  $('#mind-bar').hidden = !on;
  $('#mind-think').hidden = !on;
  clearInterval(timer);
  if (!on) return;
  renderStatus();
  loadMindStatus();
  timer = setInterval(() => {
    if (!$('#mind-bar').closest('[hidden]')) loadMindStatus();
  }, 5_000);
}

export const QUIET = /^(没什么新动静。|四周安静。)/;

export function mindMoment(content, time) {
  const text = content.replace(/^【[^】]*】\s*/, '').trim();
  const wander = text.replace(QUIET, '').trim();
  const quiet = !text || QUIET.test(text);
  const node = el('div', `mind-moment new${quiet && !wander ? ' quiet' : ''}`);
  const icon = !quiet ? 'neurology' : wander ? 'psychology' : 'schedule';
  node.append(createIcon(icon, 'icon-18'), el('span', 'body-medium mind-moment-text', quiet ? wander || '外面没什么动静' : text));
  if (time) node.append(el('span', 'label-small mind-moment-time', formatTime(time)));
  return node;
}

export function mindEntry(turn, cards) {
  const wrap = el('div', 'reply mind-step new');
  if (turn.reasoning) wrap.append(reasoningBlock(turn.reasoning));
  if (turn.content) wrap.append(el('div', 'mind-thought body-large', turn.content));
  for (const call of turn.toolCalls ?? []) {
    const card = toolCard(call);
    cards.set(call.id, card);
    wrap.append(card);
  }
  if (turn.createdAt) wrap.append(el('div', 'bubble-meta label-small', formatTime(turn.createdAt)));
  return wrap;
}

async function setPace(pace) {
  try {
    status = await api('PUT', '/pace', { pace });
    renderStatus();
  } catch (err) {
    showSnackbar(`保存失败：${err.message}`, 'error');
  }
}

async function think() {
  try {
    status = await api('POST', '/think', {});
    renderStatus();
    showSnackbar('她马上接着想', 'success');
  } catch (err) {
    showSnackbar(err.message, 'error');
  }
}

export function initAgentMind() {
  $('#mind-think').addEventListener('click', think);
  for (const button of $$('#mind-pace button')) button.addEventListener('click', () => setPace(button.dataset.pace));
}
