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
  else if (status.resting) chips.push(chip('bedtime', `深夜歇着，${status.night.end} 再接着想（托她的事和 @ 她照样会醒）`));
  else {
    const seconds = Math.max(0, Math.round((status.nextAt - Date.now()) / 1000));
    chips.push(chip('schedule', seconds < 2 ? '马上接着想' : seconds < 90 ? `${seconds} 秒后接着想` : `${Math.round(seconds / 60)} 分钟后接着想`));
  }
  renderContext($('#session-stats'), status, true);
  chips.push(chip('neurology', `这次开机想了 ${status.thoughts} 个念头`));
  chips.push(chip('send', `今天主动去群里说了 ${status.spokeToday} 次`));
  const bar = $('#mind-status');
  const todo = todoChip();
  for (const node of [...bar.children]) if (node !== todo) node.remove();
  if (todo.parentElement !== bar) bar.append(todo);
  todo.before(...chips);
  if (todoMenu?.matches(':popover-open')) fillTodo();
  for (const b of $$('#mind-pace button[data-pace]')) b.setAttribute('aria-checked', String(b.dataset.pace === status.pace));
  $('#mind-pace-custom').textContent = status.pace === 'custom' ? `自定义（${paceText(status.paceSeconds)}）` : '自定义';
  if (!$('#mind-pace-form').contains(document.activeElement)) fillPaceForm();
  if (status.mood) renderMood(status.mood);
}

const paceText = seconds => (seconds >= 120 && seconds % 60 === 0 ? `${seconds / 60} 分钟` : `${seconds} 秒`);

function fillPaceForm() {
  $('#mind-pace-seconds').value = String(status.paceSeconds ?? 300);
  const night = status.night ?? { enabled: false, start: '00:00', end: '08:00', seconds: 0 };
  $('#mind-night-on').checked = night.enabled;
  $('#mind-night-start').value = night.start;
  $('#mind-night-end').value = night.end;
  $('#mind-night-seconds').value = String(night.seconds);
}

function togglePaceForm(open = $('#mind-pace-form').hidden) {
  $('#mind-pace-form').hidden = !open;
  $('#mind-pace-more').setAttribute('aria-expanded', String(open));
  if (open && status) fillPaceForm();
}

async function savePaceForm(e) {
  e.preventDefault();
  const seconds = Number($('#mind-pace-seconds').value.trim());
  const nightSeconds = Number($('#mind-night-seconds').value.trim() || '0');
  if (!Number.isInteger(seconds) || seconds < 15 || seconds > 3600) return showSnackbar('自定义间隔填 15 到 3600 秒', 'error');
  if (!Number.isInteger(nightSeconds) || nightSeconds < 0 || nightSeconds > 3600 || (nightSeconds > 0 && nightSeconds < 15)) {
    return showSnackbar('深夜间隔填 0（停下不想）或 15 到 3600 秒', 'error');
  }
  try {
    await api('PUT', '/pace', seconds !== status?.paceSeconds ? { seconds, pace: 'custom' } : { seconds });
    status = await api('PUT', '/night', {
      enabled: $('#mind-night-on').checked,
      start: $('#mind-night-start').value || '00:00',
      end: $('#mind-night-end').value || '08:00',
      seconds: nightSeconds,
    });
    renderStatus();
    showSnackbar('思考节奏已保存', 'success');
  } catch (err) {
    showSnackbar(`保存失败：${err.message}`, 'error');
  }
}

const placeOf = session => (session?.startsWith('group:') ? `群 ${session.slice(6)}` : session?.startsWith('private:') ? '私聊' : session ?? '');

let todoMenu = null;
let todoButton = null;
let todoClosedAt = 0;

function todoLists() {
  const notes = status.notes ?? [];
  const commitments = status.commitments ?? [];
  const open = commitments.filter(c => c.status === 'open');
  const closed = commitments.filter(c => c.status !== 'open').sort((a, b) => b.updatedAt - a.updatedAt).slice(0, 10);
  return { notes, open, closed };
}

function todoChip() {
  if (!todoButton) {
    todoButton = el('button', 'mind-chip mind-chip-button label-large state-layer');
    todoButton.type = 'button';
    todoButton.id = 'mind-todo-chip';
    todoButton.setAttribute('aria-haspopup', 'true');
    todoButton.setAttribute('aria-expanded', 'false');
    todoButton.addEventListener('click', () => toggleTodo(todoButton));
  }
  const { notes, open, closed } = todoLists();
  const pending = notes.length + open.length;
  todoButton.hidden = !notes.length && !open.length && !closed.length;
  todoButton.classList.toggle('has-todo', pending > 0);
  const text = pending ? `要办的事 ${pending} 件` : '要办的事都办完了';
  if (todoButton.dataset.text !== text) {
    todoButton.dataset.text = text;
    todoButton.replaceChildren(createIcon(pending ? 'schedule' : 'check_circle', 'icon-18'), el('span', null, text),
      createIcon('arrow_drop_down', 'icon-18 mind-chip-arrow'));
  }
  return todoButton;
}

function todoRow(icon, text, meta, tone = '') {
  const row = el('div', `menu-item mind-todo-row ${tone}`);
  const body = el('div', 'mind-todo-text');
  body.append(el('div', null, text), el('div', 'mind-todo-meta', meta));
  row.append(createIcon(icon, 'icon-18'), body);
  return row;
}

function fillTodo() {
  const { notes, open, closed } = todoLists();
  const rows = [
    ...open.map(c => todoRow('schedule', `#${c.id} ${c.what}`, `${c.who} · ${placeOf(c.session)} · ${formatTime(c.createdAt)}`)),
    ...notes.map(n => todoRow('send', `交给${placeOf(n.session)}去说：${n.intent}`, `等那边说 · ${formatTime(n.at)}`, 'tone-note')),
  ];
  if (!rows.length) rows.push(el('div', 'menu-empty body-medium', '现在没有要办的事'));
  if (closed.length) {
    rows.push(el('div', 'mind-todo-section', '最近办完的'));
    rows.push(...closed.map(c => todoRow(c.status === 'done' ? 'check_circle' : 'close', `#${c.id} ${c.what}${c.result ? ` → ${c.result}` : ''}`,
      `${c.status === 'done' ? '办完了' : '不办了'} · ${formatTime(c.updatedAt)}`, c.status === 'done' ? 'tone-closed' : 'tone-closed tone-dropped')));
  }
  todoMenu.replaceChildren(...rows);
}

function placeTodo(anchor) {
  const rect = anchor.getBoundingClientRect();
  const width = todoMenu.offsetWidth;
  todoMenu.style.left = `${Math.max(8, Math.min(rect.left, innerWidth - width - 8))}px`;
  const below = innerHeight - rect.bottom - 8;
  if (below < Math.min(todoMenu.scrollHeight, 240) && rect.top > below) {
    todoMenu.style.top = '';
    todoMenu.style.bottom = `${innerHeight - rect.top + 4}px`;
    todoMenu.style.transformOrigin = 'bottom';
  } else {
    todoMenu.style.bottom = '';
    todoMenu.style.top = `${rect.bottom + 4}px`;
    todoMenu.style.transformOrigin = 'top';
  }
}

function toggleTodo(anchor) {
  if (!todoMenu) {
    todoMenu = el('div', 'menu mind-todo-menu');
    todoMenu.popover = 'auto';
    todoMenu.addEventListener('beforetoggle', e => {
      const open = e.newState === 'open';
      if (!open) todoClosedAt = performance.now();
      todoButton?.classList.toggle('open', open);
      todoButton?.setAttribute('aria-expanded', String(open));
    });
    document.body.append(todoMenu);
  }
  if (todoMenu.matches(':popover-open')) return todoMenu.hidePopover();
  if (performance.now() - todoClosedAt < 250) return;
  fillTodo();
  todoMenu.showPopover();
  placeTodo(anchor);
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
  if (pace === 'custom') togglePaceForm(true);
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
  for (const button of $$('#mind-pace button[data-pace]')) button.addEventListener('click', () => setPace(button.dataset.pace));
  $('#mind-pace-more').addEventListener('click', () => togglePaceForm());
  $('#mind-pace-form').addEventListener('submit', savePaceForm);
}
