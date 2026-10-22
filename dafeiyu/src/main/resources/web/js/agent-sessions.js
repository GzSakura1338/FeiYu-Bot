import { $, $$, el } from './dom.js';
import { createIcon } from './icons.js';
import { showSnackbar } from './snackbar.js';
import { confirmDialog } from './dialog.js';
import { createAvatar, createGroupAvatar } from './avatar.js';
import { ago, formatTime } from './format.js';
import { createReply, noteChips, reasoningBlock, renderContext, renderSaid, toolCard, settleToolCard, splitInner } from './chat-render.js';
import { onAgentViewChange, renderMood } from './agent-page.js';
import { COMMANDS, attachCommandHints, commandNote, helpText, parseCommand, runCommand } from './commands.js';
import { attachmentCount, clearAttachments, initAttachments, streamTurn, takeAttachments, userBubble } from './web-chat.js';
import { isMind, loadMindStatus, mindEntry, mindMoment, showMindBar } from './agent-mind.js';
import { attachmentNodes, isPoke, parseAttachments, pokeNote, stripPlaceholders } from './msg-attachments.js';

const KIND_LABEL = { group: '群聊', private: '私聊', web: '测试', mind: '内心' };
const TIME_MARK = /^【\d{1,2}月\d{1,2}日 [^】\n]{1,20}】$/;
const MIND_EMPTY = '她的念头还没开始转。打开页头的「自由活动」就会一直想下去';
const MAX_MIND_NODES = 400;
const OWNER_TAG = '【主人】';
const CONSOLE_TAG = '【控制台】';
const AMBIENT_TAG = '【旁听】';
const SEEN_MARK = '（以上是你刚才看过的）';
const IDLE_TAG = '【独处】';
const WEB_EMPTY = '在这里试聊几句，可以粘贴或添加图片测试看图；设置了工作目录后还能让它读写文件。没问题再开启 QQ 自动回复';

let sessions = [];
let filter = 'all';
let selected = null;
let detail = null;
let speakerIds = {};

const qqOf = name => (name ? speakerIds[name] ?? speakerIds[name.replace(/#\d{4}$/, '')] ?? null : null);
let live = new Map();
let running = new Map();
let source = null;
let listTimer = 0;
let started = false;
const drafts = new Map();
const streaming = new Map();

const encode = session => encodeURIComponent(session);
const isWeb = session => Boolean(session?.startsWith('web:'));

async function request(method, path = '', body) {
  const res = await fetch(`/api/agent/sessions${path}`, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`);
  return json;
}

function sessionAvatar(s, className = 'avatar') {
  if (s.kind === 'group') return createGroupAvatar(s.target, s.name, className);
  if (s.kind === 'private') return createAvatar(s.target, s.name, className);
  const mind = s.kind === 'mind';
  const node = el('span', `${className} avatar-icon${mind ? ' avatar-mind' : ''}`);
  node.append(createIcon(mind ? 'neurology' : 'forum', 'icon-18'));
  return node;
}

function visible(s) {
  if (s.kind === 'mind') return filter === 'all' || (filter === 'busy' && s.busy);
  if (filter === 'busy') return s.busy;
  if (filter === 'group' || filter === 'private' || filter === 'web') return s.kind === filter;
  return true;
}

function previewText(s) {
  if (s.kind === 'mind') {
    if (!s.preview) return s.busy ? '正在想…' : '她的念头还没开始转';
    const text = s.preview.replace(/^【[^】]*】\s*/, '').replace(/\s+/g, ' ');
    return s.previewRole === 'assistant' ? text : `感觉到：${text === '没什么新动静。' || !text ? '外面没什么动静' : text}`;
  }
  if (!s.preview) return s.busy ? '正在回复…' : '暂无记录';
  const text = splitInner(s.preview).said.replace(/\s+/g, ' ');
  return s.previewRole === 'assistant' ? `机器人：${text}` : text;
}

function renderList() {
  const list = $('#session-list');
  const shown = sessions.filter(visible);
  $('#session-list-empty').hidden = shown.length > 0;
  list.replaceChildren(...shown.map(s => {
    const mind = s.kind === 'mind';
    const item = el('li', `session-item state-layer${mind ? ' is-mind' : ''}`);
    item.setAttribute('role', 'option');
    item.setAttribute('aria-selected', String(s.session === selected));
    item.tabIndex = 0;
    const text = el('div', 'session-item-text');
    const head = el('div', 'session-item-head');
    head.append(el('span', 'title-small session-item-name', s.name));
    if (!mind) head.append(el('span', `kind-chip label-small kind-${s.kind}`, KIND_LABEL[s.kind] ?? s.kind));
    if (s.busy) {
      const busy = el('span', 'busy-chip label-small');
      busy.append(el('span', 'busy-dot'), el('span', null, mind ? '在想' : '回复中'));
      head.append(busy);
    }
    head.append(el('span', 'label-small session-item-time', s.lastActive ? ago(s.lastActive) : ''));
    text.append(head, el('div', 'body-small session-item-preview', previewText(s)));
    item.append(sessionAvatar(s), text);
    const open = () => select(s.session);
    item.addEventListener('click', open);
    item.addEventListener('keydown', e => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        open();
      }
    });
    return item;
  }));
}

async function loadList() {
  try {
    const data = await request('GET');
    sessions = data.sessions;
    for (const id of drafts.keys()) {
      if (sessions.some(s => s.session === id)) drafts.delete(id);
    }
    sessions.unshift(...drafts.values());
    running = new Map();
    for (const turn of data.active) {
      running.set(turn.session, (running.get(turn.session) ?? new Set()).add(turn.turnId));
    }
    for (const s of sessions) s.busy = running.has(s.session);
    renderMood(data.mood);
  } catch (err) {
    showSnackbar(`读取会话失败：${err.message}`, 'error');
  }
  renderList();
  if (selected) {
    const current = sessions.find(s => s.session === selected);
    if (current) renderHeader(current);
  }
}

function scheduleList() {
  clearTimeout(listTimer);
  listTimer = setTimeout(loadList, 400);
}

function renderHeader(s) {
  const web = s.kind === 'web';
  const mind = s.kind === 'mind';
  const avatar = $('#session-avatar');
  const key = `${s.kind}:${s.target}`;
  if (avatar.dataset.key !== key) {
    avatar.dataset.key = key;
    avatar.replaceChildren(sessionAvatar(s, 'avatar session-avatar-img'));
  }
  $('#session-name').textContent = s.name;
  const parts = [web ? '网页测试对话' : mind ? '她脑子里一直在转的念头' : KIND_LABEL[s.kind] ?? s.kind];
  if (!web && !mind) parts.push(s.target);
  if (detail) parts.push(`${detail.totalTurns} 条记录`);
  $('#session-sub').textContent = parts.join(' · ');
  if (!mind && detail?.session === s.session) renderContext($('#session-stats'), detail);
  else if (!mind && !detail) $('#session-stats').replaceChildren();
  $('#session-stop').hidden = !running.has(s.session) && !streaming.has(s.session);
  $('#session-deliver').hidden = web;
  $('#session-chips').hidden = !web;
  $('#chat-attach').hidden = !web;
  $('#session-form').hidden = mind;
  $('#session-timeline').classList.toggle('is-web', web);
  $('#session-timeline').classList.toggle('is-mind', mind);
  const clear = $('#session-clear');
  clear.title = clear.ariaLabel = web ? '删除这个测试对话' : mind ? '清空她的思绪' : '清空这个会话的记忆';
  $('#session-input').placeholder = web
    ? '输入消息，Enter 发送，可粘贴图片，输入 / 使用命令'
    : '以控制台身份发消息，输入 / 使用命令（清空、压缩、回顾…）';
}

const GROUP_LINE = /^(【主人】)?([^：（\n]{1,40})(（回复 [^\n]*?）)?：(.*)$/;

function parseUser(content, kind) {
  if (content.startsWith(CONSOLE_TAG)) {
    return [{ console: true, text: content.slice(CONSOLE_TAG.length) }];
  }
  if (kind !== 'group') {
    const owner = content.startsWith(OWNER_TAG);
    return [{ tag: owner ? '主人' : null, name: null, text: owner ? content.slice(OWNER_TAG.length) : content }];
  }
  const messages = [];
  for (const line of content.split('\n')) {
    if (line === SEEN_MARK) {
      messages.push({ seen: true });
      continue;
    }
    if (TIME_MARK.test(line)) {
      messages.push({ time: line.slice(1, -1) });
      continue;
    }
    if (line.startsWith(AMBIENT_TAG) || line.startsWith(IDLE_TAG) || (line.startsWith('（') && line.includes('和你重名的别人'))) continue;
    const match = GROUP_LINE.exec(line);
    if (match) {
      messages.push({ tag: match[1] ? '主人' : null, name: match[2], quote: match[3]?.slice(1, -1) ?? null, text: match[4] });
    } else if (messages.length) {
      messages.at(-1).text += `\n${line}`;
    } else {
      messages.push({ tag: null, name: null, text: line });
    }
  }
  return messages;
}

function userEntries(content, time, s, rawAttachments = '') {
  if (TIME_MARK.test(content.trim())) return [el('div', 'chat-divider label-small', content.trim().slice(1, -1))];
  if (s.kind === 'mind') return [mindMoment(content, time)];
  const attachments = parseAttachments(rawAttachments);
  if (s.kind !== 'web' && isPoke(content, attachments)) return [pokeNote(content, time, formatTime)];
  if (s.kind === 'web') {
    const node = el('div', 'msg-console new');
    node.append(el('div', 'bubble bubble-user body-large', content));
    if (time) node.append(el('div', 'msg-label label-small', formatTime(time)));
    return [node];
  }
  const parsedList = parseUser(content, s.kind);
  const carrier = parsedList.findLast(p => !p.seen && !p.time && !p.console);
  return parsedList.map(parsed => {
    if (parsed.seen) return el('div', 'chat-divider label-small seen-divider', '她看过了，没接话');
    if (parsed.time) return el('div', 'chat-divider label-small', parsed.time);
    if (parsed.console) {
      const row = el('div', 'msg msg-console new');
      const label = el('div', 'msg-label label-small');
      label.append(createIcon('terminal', 'icon-18'), el('span', null, '控制台'), el('span', 'msg-time', time ? formatTime(time) : ''));
      row.append(label, el('div', 'bubble bubble-console body-large', parsed.text));
      return row;
    }
    const name = parsed.name ?? (s.kind === 'private' ? s.name : '群友');
    const row = el('div', 'msg msg-in new');
    row.append(s.kind === 'private' ? createAvatar(s.target, name) : createAvatar(qqOf(parsed.name), name));
    const body = el('div', 'msg-body');
    const label = el('div', 'msg-label label-small');
    label.append(el('span', 'msg-name', name));
    if (parsed.tag) label.append(el('span', 'owner-chip label-small', parsed.tag));
    if (parsed.quote) label.append(el('span', 'msg-quote', parsed.quote));
    label.append(el('span', 'msg-time', time ? formatTime(time) : ''));
    body.append(label);
    const own = parsed === carrier ? attachments : [];
    const text = stripPlaceholders(parsed.text, own);
    if (text) body.append(renderSaid(text, 'bubble bubble-in body-large'));
    body.append(...attachmentNodes(own));
    row.append(body);
    return row;
  });
}

function botEntry(turn, cards) {
  const wrap = el('div', 'reply reply-bot new');
  if (turn.reasoning) wrap.append(reasoningBlock(turn.reasoning));
  const { inner, said, notes } = splitInner(turn.content ?? '');
  if (inner) wrap.append(el('div', 'inner-voice body-medium', inner));
  if (said) wrap.append(renderSaid(said));
  wrap.append(...noteChips(notes));
  for (const call of turn.toolCalls ?? []) {
    const card = toolCard(call);
    cards.set(call.id, card);
    wrap.append(card);
  }
  wrap.append(el('div', 'bubble-meta label-small', formatTime(turn.createdAt)));
  return wrap;
}

function currentSession() {
  return sessions.find(x => x.session === selected) ?? { session: selected, kind: detail?.kind, target: detail?.target, name: detail?.name };
}

function renderTimeline() {
  const s = currentSession();
  const timeline = $('#session-timeline');
  const nodes = [];
  const mind = s.kind === 'mind';
  if (detail.summary) {
    const card = el('details', 'checkpoint-card');
    const summary = el('summary', 'tool-summary label-large');
    summary.append(createIcon('history', 'icon-18'), el('span', 'tool-title', mind
      ? `更早的念头已整理成一段回忆（${detail.compactions} 次）`
      : `更早的对话已压缩成摘要（${detail.compactions} 次）`));
    const body = el('pre', 'tool-output body-small', detail.summary + (detail.recent ? `\n\n—— 保留的最近原文 ——\n${detail.recent}` : ''));
    card.append(summary, body);
    nodes.push(card);
  }
  if (detail.totalTurns > detail.turns.length) {
    nodes.push(el('div', 'chat-divider label-medium', `只显示最近 ${detail.turns.length} 条，共 ${detail.totalTurns} 条`));
  }
  const cards = new Map();
  for (const turn of detail.turns) {
    if (turn.role === 'user') nodes.push(...userEntries(turn.content, turn.createdAt, s, turn.attachments));
    else if (turn.role === 'assistant') nodes.push(mind ? mindEntry(turn, cards) : botEntry(turn, cards));
    else if (turn.role === 'tool') {
      const card = cards.get(turn.toolCallId);
      if (card) settleToolCard(card, turn.content, turn.toolError);
    }
  }
  if (!nodes.length) nodes.push(el('div', 'empty-state body-medium', s.kind === 'web' ? WEB_EMPTY : mind ? MIND_EMPTY : '这个会话还没有记录'));
  timeline.replaceChildren(...nodes);
  live = new Map();
  for (const turn of detail.active ?? []) {
    if (!ownsLive(turn.session)) startLive(turn, true);
  }
  pinBottom(timeline);
}

function pinBottom(timeline) {
  let pinned = true;
  const pin = () => {
    if (pinned) timeline.scrollTop = timeline.scrollHeight;
  };
  const release = () => {
    pinned = false;
  };
  pin();
  requestAnimationFrame(pin);
  for (const type of ['wheel', 'touchstart', 'keydown', 'mousedown']) timeline.addEventListener(type, release, { once: true, passive: true });
  for (const img of timeline.querySelectorAll('img')) {
    if (!img.complete) img.addEventListener('load', pin, { once: true });
  }
  setTimeout(release, 4_000);
}

function ownsLive(session) {
  return Boolean(streaming.get(session)?.reply.wrap.isConnected);
}

function appendToTimeline(node) {
  const timeline = $('#session-timeline');
  const follow = !isMind(selected) || timeline.scrollTop + timeline.clientHeight >= timeline.scrollHeight - 64;
  timeline.querySelector(':scope > .empty-state')?.remove();
  timeline.append(node);
  if (follow) timeline.scrollTop = timeline.scrollHeight;
  return node;
}

function pruneMind() {
  const timeline = $('#session-timeline');
  while (timeline.children.length > MAX_MIND_NODES) timeline.firstElementChild.remove();
}

function startLive(turn, replay = false) {
  const timeline = $('#session-timeline');
  timeline.querySelector(':scope > .empty-state')?.remove();
  if (turn.source === 'command') {
    note(commandNote('正在压缩上下文…', 'info', 'history'));
    return;
  }
  if (turn.prompt) timeline.append(...userEntries(turn.prompt, turn.startedAt, currentSession()));
  const reply = createReply(timeline, node => {
    node.classList.add(isMind(turn.session) ? 'mind-step' : 'reply-bot');
    return appendToTimeline(node);
  });
  live.set(turn.turnId, reply);
  if (replay) for (const part of turn.parts ?? []) reply.apply(part.type, part);
}

async function loadDetail() {
  if (!selected) return;
  const session = selected;
  try {
    const data = await request('GET', `/${encode(session)}`);
    if (session !== selected) return;
    detail = data;
    speakerIds = data.speakers ?? {};
  } catch (err) {
    showSnackbar(`读取会话失败：${err.message}`, 'error');
    return;
  }
  for (const turn of detail.active ?? []) {
    running.set(turn.session, (running.get(turn.session) ?? new Set()).add(turn.turnId));
  }
  renderHeader(currentSession());
  renderTimeline();
}

async function select(session) {
  if (isWeb(selected) !== isWeb(session)) clearAttachments();
  selected = session;
  detail = null;
  live = new Map();
  $('#session-placeholder').hidden = true;
  $('#session-view').hidden = false;
  $('#session-timeline').replaceChildren();
  showMindBar(isMind(session));
  renderList();
  renderHeader(currentSession());
  await loadDetail();
  updateSend();
  if (!isMind(session)) $('#session-input').focus();
}

function newChat() {
  const id = `web:${Math.random().toString(36).slice(2, 10)}`;
  const draft = { session: id, kind: 'web', target: id.slice(4), name: '新对话', turns: 0, lastActive: Date.now(), preview: '', busy: false };
  drafts.set(id, draft);
  sessions.unshift(draft);
  if (filter !== 'all' && filter !== 'web') setFilter('all');
  select(id);
}

function setFilter(value) {
  filter = value;
  for (const b of $$('#session-filter button')) b.setAttribute('aria-checked', String(b.dataset.filter === value));
  renderList();
}

function track(session, turnId, active) {
  const turns = running.get(session) ?? new Set();
  if (active) turns.add(turnId);
  else turns.delete(turnId);
  if (turns.size) running.set(session, turns);
  else running.delete(session);
  const s = sessions.find(x => x.session === session);
  if (s) {
    s.busy = running.has(session);
    if (active) s.lastActive = Date.now();
  }
}

function onHeard(data) {
  const s = sessions.find(x => x.session === data.session);
  if (s) {
    s.preview = data.text;
    s.previewRole = 'user';
    s.lastActive = data.time;
    renderList();
  } else {
    scheduleList();
  }
  if (data.session === selected) {
    const name = data.userId ? parseUser(data.text, 'group')[0]?.name : null;
    if (name) speakerIds[name] = data.userId;
    const nodes = userEntries(data.text, data.time, currentSession(), data.attachments);
    const timeline = $('#session-timeline');
    const near = timeline.scrollTop + timeline.clientHeight >= timeline.scrollHeight - 48;
    timeline.querySelector(':scope > .empty-state')?.remove();
    timeline.append(...nodes);
    if (isMind(data.session)) pruneMind();
    if (near) timeline.scrollTop = timeline.scrollHeight;
  }
}

function onEvent(type, data) {
  if (type === 'heard') {
    onHeard(data);
    return;
  }
  if (type === 'turn-start' || type === 'turn-end') {
    track(data.session, data.turnId, type === 'turn-start');
    renderList();
    if (data.session === selected) renderHeader(currentSession());
    scheduleList();
  }
  if (data.session !== selected || ownsLive(data.session)) return;
  if (type === 'turn-start') {
    startLive(data);
    if (isMind(data.session)) loadMindStatus();
    return;
  }
  const reply = live.get(data.turnId);
  if (type === 'compaction' && isMind(data.session)) {
    const divider = el('div', 'chat-divider label-medium', '念头攒得太多了，正在把更早的整理成一段回忆');
    if (reply) reply.wrap.before(divider);
    else appendToTimeline(divider);
    return;
  }
  if (type === 'turn-end' && isMind(data.session)) {
    reply?.abort();
    live.delete(data.turnId);
    if (data.status === 'error') appendToTimeline(el('div', 'bubble bubble-error body-medium', `这个念头没想下去：${data.error ?? ''}`));
    pruneMind();
    loadMindStatus();
    return;
  }
  if (type === 'turn-end') {
    live.delete(data.turnId);
    if (data.source === 'command') return;
    loadDetail().then(() => {
      if (data.status === 'stopped') showSnackbar('这次回复已停止');
      else if (data.status === 'error') {
        appendToTimeline(el('div', 'bubble bubble-error body-large', `回复失败：${data.error ?? ''}`));
      }
    });
    return;
  }
  reply?.apply(type, data);
}

function connect() {
  source?.close();
  source = new EventSource('/api/agent/sessions/stream');
  const types = ['turn-start', 'turn-running', 'turn-end', 'text', 'reasoning', 'tool-call', 'tool-result', 'retry', 'compaction', 'blocked', 'heard'];
  for (const type of types) {
    source.addEventListener(type, e => onEvent(type, JSON.parse(e.data)));
  }
  source.addEventListener('ready', () => {
    if (started) {
      loadList();
      loadDetail();
    }
  });
}

function updateSend() {
  const text = $('#session-input').value.trim();
  const web = isWeb(selected);
  $('#session-send').disabled = !selected || isMind(selected) || (!text && !(web && attachmentCount()))
    || (web && streaming.has(selected) && !text.startsWith('/'));
}

function note(node) {
  appendToTimeline(node);
}

function resetInput() {
  const input = $('#session-input');
  input.value = '';
  input.style.height = 'auto';
  updateSend();
}

async function command(text) {
  const { name, args } = parseCommand(text);
  resetInput();
  const s = sessions.find(x => x.session === selected);
  if (name === 'help') {
    note(commandNote(helpText(s?.kind !== 'web'), 'info', 'help'));
    return;
  }
  const command = COMMANDS.find(c => c.name === name);
  if (name === 'reflect') note(commandNote('正在回顾这段聊天…', 'info', 'psychology'));
  try {
    const message = await runCommand(selected, name, args);
    if (['clear', 'compact', 'say'].includes(name)) {
      await loadDetail();
      await loadList();
    }
    note(commandNote(message, 'success', command?.icon ?? 'terminal'));
  } catch (err) {
    note(commandNote(err.message, 'error', 'error'));
  }
}

async function sendWeb(text) {
  const session = selected;
  if (streaming.has(session)) return;
  const images = takeAttachments();
  if (!text && !images.length) return;
  resetInput();
  appendToTimeline(userBubbleRow(text, images));
  const reply = createReply($('#session-timeline'), node => {
    node.classList.add('reply-bot');
    return appendToTimeline(node);
  });
  const controller = new AbortController();
  streaming.set(session, { controller, reply });
  renderHeader(currentSession());
  updateSend();
  try {
    await streamTurn(session, text, images, reply, controller.signal);
  } finally {
    streaming.delete(session);
    if (session === selected) {
      renderHeader(currentSession());
      updateSend();
    }
    scheduleList();
  }
}

function userBubbleRow(text, images) {
  const row = el('div', 'msg-console new');
  row.append(userBubble(text, images));
  return row;
}

async function send(e) {
  e?.preventDefault();
  const message = $('#session-input').value.trim();
  if (!selected) return;
  if (message.startsWith('/') && !(isWeb(selected) && attachmentCount())) {
    await command(message);
    return;
  }
  if (isWeb(selected)) {
    await sendWeb(message);
    return;
  }
  if (!message) return;
  const deliver = $('#session-deliver').getAttribute('aria-pressed') === 'true';
  $('#session-send').disabled = true;
  try {
    await request('POST', `/${encode(selected)}/send`, { message, deliver });
    resetInput();
  } catch (err) {
    showSnackbar(`发送失败：${err.message}`, 'error');
  }
  updateSend();
}

async function stop() {
  if (!selected) return;
  streaming.get(selected)?.controller.abort();
  try {
    const { stopped } = await request('POST', `/${encode(selected)}/stop`, {});
    showSnackbar(stopped || isWeb(selected) ? '已停止' : '这个会话现在没有在回复', stopped || isWeb(selected) ? 'success' : 'info');
  } catch (err) {
    showSnackbar(`停止失败：${err.message}`, 'error');
  }
}

async function clearSession() {
  if (!selected) return;
  const s = currentSession();
  const web = isWeb(selected);
  const mind = isMind(selected);
  const ok = await confirmDialog({
    icon: 'delete_sweep',
    title: web ? '删除这个测试对话？' : mind ? '清空她的思绪？' : '清空这个会话的记忆？',
    text: web
      ? `「${s?.name ?? selected}」的聊天记录会删除。`
      : mind
        ? '她这段时间转过的念头和整理好的回忆都会清掉，从一片空白接着想（已经记进「记忆」的不受影响）。'
        : `「${s?.name ?? selected}」的对话记录和压缩摘要都会删除，机器人会忘掉之前聊过的内容（已经记进「记忆」的不受影响）。正在进行的回复也会停止。`,
    confirm: web ? '删除' : '清空',
  });
  if (!ok) return;
  streaming.get(selected)?.controller.abort();
  try {
    await request('DELETE', `/${encode(selected)}`, {});
    showSnackbar(web ? '已删除' : '已清空', 'success');
  } catch (err) {
    showSnackbar(`操作失败：${err.message}`, 'error');
  }
  if (web) {
    drafts.delete(selected);
    selected = null;
    detail = null;
    $('#session-view').hidden = true;
    $('#session-placeholder').hidden = false;
    await loadList();
    return;
  }
  await loadList();
  await loadDetail();
}

function start() {
  if (started) return;
  started = true;
  connect();
  loadList();
  setInterval(renderList, 30_000);
}

export function initAgentSessions() {
  onAgentViewChange(view => {
    if (view === 'sessions') start();
  });
  for (const button of $$('#session-filter button')) {
    button.addEventListener('click', () => setFilter(button.dataset.filter));
  }
  $('#sessions-refresh').addEventListener('click', () => {
    loadList();
    loadDetail();
  });
  $('#session-new').addEventListener('click', newChat);
  const input = $('#session-input');
  attachCommandHints(input, {
    qq: () => !isWeb(selected),
    onPick: () => send(),
  });
  input.addEventListener('input', () => {
    input.style.height = 'auto';
    input.style.height = `${Math.min(input.scrollHeight, 160)}px`;
    updateSend();
  });
  input.addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) send(e);
  });
  initAttachments({ panel: $('#session-detail'), input, enabled: () => isWeb(selected), onChange: updateSend });
  $('#session-form').addEventListener('submit', send);
  $('#session-stop').addEventListener('click', stop);
  $('#session-clear').addEventListener('click', clearSession);
  const deliver = $('#session-deliver');
  deliver.addEventListener('click', () => {
    const on = deliver.getAttribute('aria-pressed') !== 'true';
    deliver.setAttribute('aria-pressed', String(on));
    $('span', deliver).textContent = on ? '发到 QQ' : '只在控制台';
  });
}
