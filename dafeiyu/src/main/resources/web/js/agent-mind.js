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
  if (!status.awake) chips.push(chip('bedtime', '半夜了，半睡半醒，念头很慢'));
  renderContext($('#session-stats'), status, true);
  chips.push(chip('neurology', `这次开机想了 ${status.thoughts} 个念头`));
  chips.push(chip('send', `今天主动去群里说了 ${status.spokeToday} 次`));
  $('#mind-status').replaceChildren(...chips);
  for (const b of $$('#mind-pace button')) b.setAttribute('aria-checked', String(b.dataset.pace === status.pace));
  if (status.mood) renderMood(status.mood);
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

export function mindMoment(content, time) {
  const text = content.replace(/^【[^】]*】\s*/, '').trim();
  const quiet = !text || text === '没什么新动静。';
  const node = el('div', `mind-moment new${quiet ? ' quiet' : ''}`);
  node.append(createIcon(quiet ? 'schedule' : 'neurology', 'icon-18'), el('span', 'body-medium mind-moment-text', quiet ? '外面没什么动静' : text));
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
