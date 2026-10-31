import { $, $$, el } from './dom.js';
import { createIcon } from './icons.js';
import { showSnackbar } from './snackbar.js';
import { confirmDialog } from './dialog.js';
import { createAvatar, createGroupAvatar } from './avatar.js';
import { ago, chatTime, formatTime } from './format.js';
import { createReply, noteCard, noteChips, reasoningBlock, renderContext, renderSaid, toolCard, settleToolCard, splitInner } from './chat-render.js';
import { onAgentViewChange, renderMood } from './agent-page.js';
import { navigate } from './router.js';
import { COMMANDS, attachCommandHints, commandNote, helpText, parseCommand, runCommand } from './commands.js';
import { attachmentCount, clearAttachments, initAttachments, streamTurn, takeAttachments, userBubble } from './web-chat.js';
import { QUIET, isMind, loadMindStatus, mindEntry, mindMoment, showMindBar } from './agent-mind.js';
import { attachmentNodes, isPoke, parseAttachments, pokeNote, stripPlaceholders } from './msg-attachments.js';
import { stickToBottom } from './stick-bottom.js';
import { setChatSession } from './agent-personas.js';
import { lazyList } from './lazy-list.js';

const KIND_LABEL = { group: '群聊', private: '私聊', web: '测试', mind: '内心', task: '后台任务' };
const TASK_STATUS = { queued: '排队中', running: '在做', paused: '暂停了', done: '做好了', failed: '没做成', interrupted: '中断了', ended: '已结束' };
const TASK_GROUPS = {
  qq: { label: '后台任务', key: 'sessions.tasksOpen' },
  mind: { label: '内心的子 agent', key: 'sessions.subagentsOpen' },
};
const ARCHIVE_AFTER_MS = 15 * 60_000;
const TIME_MARK = /^【\d{1,2}月\d{1,2}日 [^】\n]{1,20}】$/;
const MIND_EMPTY = '她的念头还没开始转。打开页头的「自由活动」就会一直想下去';
const MAX_MIND_NODES = 400;
const PAGE = 60;
const OLDER_PAGE = 80;
const STAMP_GAP = 5 * 60_000;
const OWNER_TAG = '【主人】';
const CONSOLE_TAG = '【控制台】';
const AMBIENT_TAG = '【旁听】';
const SEEN_MARK = '（以上是你刚才看过的）';
const IDLE_TAG = '【独处】';
const WEB_EMPTY = '在这里试聊几句，可以粘贴或添加图片测试看图；设置了工作目录后还能让它读写文件。没问题再开启 QQ 自动回复';

let sessions = [];
let filter = 'all';
const groupOpen = Object.fromEntries(Object.entries(TASK_GROUPS).map(([id, g]) => [id, localStorage.getItem(g.key) === '1']));
let selected = null;
let detail = null;
let shown = PAGE;
let loadingOlder = false;
let speakerIds = {};
let fallbackSelf = { qq: '', name: '' };
let self = fallbackSelf;
let lastAt = 0;

const selfName = () => self.name || fallbackSelf.name || '我';
const meText = text => text.replace(/@你(?=\s|$|[，。,.!！?？~])/g, `@${selfName()}`);
const isQq = kind => kind === 'group' || kind === 'private';

async function loadSelf() {
  try {
    const res = await fetch('/api/status', { cache: 'no-store' });
    const status = await res.json();
    if (status.selfId) fallbackSelf = { qq: status.selfId, name: status.nickname ?? '' };
  } catch {
  }
}

function selfAvatar(time) {
  const avatar = createAvatar(self.qq || fallbackSelf.qq, selfName(), 'avatar reply-avatar');
  if (time) avatar.title = formatTime(time);
  return avatar;
}

function timeStamp(time) {
  const at = time ? new Date(time).getTime() : 0;
  if (!at) return [];
  const gap = at - lastAt;
  lastAt = Math.max(lastAt, at);
  if (gap < STAMP_GAP) return [];
  const node = el('div', 'chat-time label-small', chatTime(at));
  node.dataset.at = String(at);
  return [node];
}

const stamped = (time, nodes, s) => (isQq(s?.kind) && nodes.length ? [...timeStamp(time), ...nodes] : nodes);

const qqOf = name => (name ? speakerIds[name] ?? speakerIds[name.replace(/#\d{4}$/, '')] ?? null : null);
const QQ_NAME = /^(?:QQ\s*)?(\d{5,12})$/;

function nameOfQq(qq) {
  for (const [label, id] of Object.entries(speakerIds)) {
    if (String(id) === qq && label.trim() && !/#\d{4}$/.test(label) && !QQ_NAME.test(label.trim())) return label;
  }
  return null;
}

function speaker(name) {
  const m = name ? QQ_NAME.exec(name.trim()) : null;
  if (m) return { qq: m[1], name: nameOfQq(m[1]) ?? `QQ ${m[1]}` };
  return { qq: qqOf(name), name };
}

const mentionNames = text => text.replace(/@(\d{5,12})(?!\d)/g, (all, qq) => {
  const name = nameOfQq(qq);
  return name ? `@${name}` : all;
});
let live = new Map();
let running = new Map();
let source = null;
let listTimer = 0;
let listFrame = 0;
let listRows = null;
let started = false;
let bottom = null;
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
  const task = s.kind === 'task';
  const node = el('span', `${className} avatar-icon${mind ? ' avatar-mind' : task ? ' avatar-task' : ''}`);
  node.append(createIcon(mind ? 'neurology' : task ? 'build' : 'forum', 'icon-18'));
  return node;
}

function visible(s) {
  if (s.kind === 'mind') return filter === 'all' || (filter === 'busy' && s.busy);
  if (s.kind === 'task') return filter === 'all' || (filter === 'busy' && s.busy);
  if (filter === 'busy') return s.busy;
  if (filter === 'group' || filter === 'private' || filter === 'web') return s.kind === filter;
  return true;
}

function previewText(s) {
  if (s.kind === 'mind') {
    if (!s.preview) return s.busy ? '正在想…' : '她的念头还没开始转';
    const text = s.preview.replace(/^【[^】]*】\s*/, '').replace(/\s+/g, ' ');
    if (s.previewRole === 'assistant') return text;
    const wander = text.replace(QUIET, '').trim();
    return QUIET.test(text) || !text ? wander || '外面没什么动静' : `感觉到：${text}`;
  }
  if (s.kind === 'task') return taskPreview(s);
  if (!s.preview) return s.busy ? '正在回复…' : '暂无记录';
  if (s.preview.trim() === SEEN_MARK) return '';
  const text = meText(splitInner(s.preview).said.replace(/\s+/g, ' '));
  if (s.previewRole !== 'assistant') return text;
  return s.kind === 'web' ? `机器人：${text}` : `${selfName()}：${text}`;
}

const taskStatus = s => (s.task?.status === 'paused' ? 'paused' : s.busy ? 'running' : s.task?.status ?? 'ended');

const SUBAGENT_KIND = { research: '查资料', build: '动手' };

function taskSource(info) {
  if (info?.originKind === 'mind') return `内心的子 agent${SUBAGENT_KIND[info.kind] ? ` · ${SUBAGENT_KIND[info.kind]}` : ''}`;
  return info?.originName ? `帮${info.requester ? ` ${info.requester}` : ''}（${info.originName}）` : '';
}

function taskPreview(s) {
  const from = taskSource(s.task);
  const status = taskStatus(s);
  const outcome = ['running', 'queued', 'paused'].includes(status) ? '' : s.task?.result || (s.previewRole === 'assistant' ? s.preview : '');
  return [from, outcome.replace(/\s+/g, ' ')].filter(Boolean).join(' · ') || TASK_STATUS[status];
}

function onActivate(item, action) {
  item.tabIndex = 0;
  item.addEventListener('click', action);
  item.addEventListener('keydown', e => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      action();
    }
  });
}

function sessionItem(s) {
  const mind = s.kind === 'mind';
  const task = s.kind === 'task';
  const item = el('li', `session-item state-layer${mind ? ' is-mind' : ''}${task ? ' is-sub' : ''}`);
  item.setAttribute('role', 'option');
  item.setAttribute('aria-selected', String(s.session === selected));
  const text = el('div', 'session-item-text');
  const head = el('div', 'session-item-head');
  head.append(el('span', 'title-small session-item-name', s.name));
  if (task) {
    const status = taskStatus(s);
    if (status !== 'running') head.append(el('span', `kind-chip label-small task-${status}`, TASK_STATUS[status]));
  } else if (!mind) {
    head.append(el('span', `kind-chip label-small kind-${s.kind}`, KIND_LABEL[s.kind] ?? s.kind));
  }
  if (s.busy && !(task && taskStatus(s) === 'paused')) {
    const busy = el('span', 'busy-chip label-small');
    busy.append(el('span', 'busy-dot'), el('span', null, mind ? '在想' : task ? '在做' : '回复中'));
    head.append(busy);
  }
  head.append(el('span', 'label-small session-item-time', s.lastActive ? ago(s.lastActive) : ''));
  text.append(head, el('div', 'body-small session-item-preview', previewText(s)));
  item.append(sessionAvatar(s, task ? 'avatar avatar-sm' : 'avatar'), text);
  onActivate(item, () => select(s.session));
  return item;
}

const taskGroupOf = s => (s.task?.originKind === 'mind' ? 'mind' : 'qq');

function openGroup(id, open) {
  groupOpen[id] = open;
  localStorage.setItem(TASK_GROUPS[id].key, open ? '1' : '0');
}

function taskGroup(id, tasks) {
  const item = el('li', 'session-item session-group state-layer');
  item.setAttribute('role', 'option');
  item.setAttribute('aria-expanded', String(groupOpen[id]));
  item.setAttribute('aria-selected', 'false');
  const text = el('div', 'session-item-text');
  const head = el('div', 'session-item-head');
  head.append(el('span', 'title-small session-item-name', TASK_GROUPS[id].label), el('span', 'kind-chip label-small kind-task', String(tasks.length)));
  const working = tasks.filter(s => taskStatus(s) === 'running').length;
  if (working) {
    const busy = el('span', 'busy-chip label-small');
    busy.append(el('span', 'busy-dot'), el('span', null, `${working} 个在做`));
    head.append(busy);
  }
  const latest = Math.max(...tasks.map(s => s.lastActive ?? 0));
  head.append(el('span', 'label-small session-item-time', latest ? ago(latest) : ''));
  const queued = tasks.filter(s => taskStatus(s) === 'queued').length;
  const summary = working || queued
    ? [working && `${working} 个在做`, queued && `${queued} 个排队`].filter(Boolean).join('，')
    : `最近：${tasks[0].name}`;
  text.append(head, el('div', 'body-small session-item-preview', summary));
  const chevron = createIcon('chevron_right', 'icon-18 session-group-chevron');
  item.append(sessionAvatar({ kind: 'task' }), text, chevron);
  const sub = fold(groupOpen[id], () => tasks.map(sessionItem));
  onActivate(item, () => {
    openGroup(id, !groupOpen[id]);
    toggleFold(item, sub, groupOpen[id]);
  });
  return [item, sub];
}

export function isArchivedTask(s) {
  if (s.kind !== 'task') return false;
  const status = taskStatus(s);
  return !['running', 'queued', 'paused'].includes(status) && Date.now() - (s.lastActive ?? 0) > ARCHIVE_AFTER_MS;
}

export const taskStatusLabel = s => TASK_STATUS[taskStatus(s)];
export const taskStatusKey = taskStatus;
export const taskSummary = taskPreview;

export function openSession(session) {
  navigate('agent', 'sessions');
  start();
  select(session);
}

function fold(open, children) {
  const sub = el('li', `session-subgroup${open ? ' open' : ''}`);
  sub.setAttribute('role', 'presentation');
  sub.inert = !open;
  const inner = el('ul', 'session-subgroup-list');
  inner.setAttribute('role', 'group');
  if (open) inner.append(...children());
  else sub.fill = () => inner.append(...children());
  sub.append(inner);
  return sub;
}

function toggleFold(header, sub, open) {
  header.setAttribute('aria-expanded', String(open));
  if (open && sub.fill) {
    sub.fill();
    sub.fill = null;
    void sub.offsetHeight;
  }
  sub.classList.toggle('open', open);
  sub.inert = !open;
}

function renderList() {
  if (!listFrame) listFrame = requestAnimationFrame(drawList);
}

function drawList() {
  listFrame = 0;
  listRows ??= lazyList($('#session-list'));
  const shown = sessions.filter(visible);
  $('#session-list-empty').hidden = shown.length > 0;
  const tasks = shown.filter(s => s.kind === 'task' && (!isArchivedTask(s) || s.session === selected))
    .sort((a, b) => (b.lastActive ?? 0) - (a.lastActive ?? 0));
  const others = shown.filter(s => s.kind !== 'task');
  const head = others.filter(s => s.kind === 'mind').map(sessionItem);
  for (const id of ['mind', 'qq']) {
    const group = tasks.filter(s => taskGroupOf(s) === id);
    if (group.length) head.push(...taskGroup(id, group));
  }
  listRows.render(head, others.filter(s => s.kind !== 'mind'), sessionItem);
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
  const task = s.kind === 'task';
  const avatar = $('#session-avatar');
  const key = `${s.kind}:${s.target}`;
  if (avatar.dataset.key !== key) {
    avatar.dataset.key = key;
    avatar.replaceChildren(sessionAvatar(s, 'avatar session-avatar-img'));
  }
  $('#session-name').textContent = s.name;
  const parts = [web ? '网页测试对话' : mind ? '她脑子里一直在转的念头' : KIND_LABEL[s.kind] ?? s.kind];
  const info = s.task ?? detail?.task;
  if (task) {
    parts.push(TASK_STATUS[info?.status === 'paused' ? 'paused' : s.busy ? 'running' : info?.status ?? 'ended']);
    const source = taskSource(info);
    if (source) parts.push(source);
  } else if (!web && !mind) parts.push(s.target);
  if (detail) parts.push(`${detail.totalTurns} 条记录`);
  $('#session-sub').textContent = parts.join(' · ');
  if (!mind && detail?.session === s.session) renderContext($('#session-stats'), detail);
  else if (!mind && !detail) $('#session-stats').replaceChildren();
  $('#session-stop').hidden = !running.has(s.session) && !streaming.has(s.session);
  $('#session-deliver').hidden = web;
  const qq = isQq(s.kind) && /^(group|private):/.test(s.session ?? '');
  $('#session-chips').hidden = !web && !qq;
  $('#chat-model').hidden = !web;
  setChatSession(qq ? s.session : null);
  $('#chat-attach').hidden = !web;
  $('#session-form').hidden = mind || task;
  $('#session-timeline').classList.toggle('is-web', web);
  $('#session-timeline').classList.toggle('is-mind', mind);
  $('#session-timeline').classList.toggle('is-qq', isQq(s.kind));
  const clear = $('#session-clear');
  clear.title = clear.ariaLabel = web ? '删除这个测试对话' : mind ? '清空她的思绪' : '清空这个会话的记忆';
  $('#session-input').placeholder = web
    ? '输入消息，Enter 发送，可粘贴图片，输入 / 使用命令'
    : '以控制台身份发消息，输入 / 使用命令（清空、压缩、回顾…）';
}

const GROUP_LINE = /^(【主人】)?([^：（\n]{1,40})(（回复 [^\n]*?）)?：(.*)$/;
const PRIVATE_QUOTE = /^（(回复 [^\n]*)）\n([\s\S]*)$/;
const IMAGE_REF = /\[图片#(\d+)\]/;

function parseUser(content, kind) {
  if (content.startsWith(CONSOLE_TAG)) {
    return [{ console: true, text: content.slice(CONSOLE_TAG.length) }];
  }
  if (kind !== 'group') {
    const owner = content.startsWith(OWNER_TAG);
    const body = owner ? content.slice(OWNER_TAG.length) : content;
    const quoted = PRIVATE_QUOTE.exec(body);
    return [{ tag: owner ? '主人' : null, name: null, quote: quoted ? quoted[1] : null, text: quoted ? quoted[2] : body }];
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
  if (TIME_MARK.test(content.trim())) return isQq(s.kind) ? [] : [el('div', 'chat-divider label-small', content.trim().slice(1, -1))];
  if (s.kind === 'mind') return [mindMoment(content, time)];
  if (s.kind === 'task') {
    const row = el('div', 'msg msg-console new');
    const label = el('div', 'msg-label label-small');
    const first = content.startsWith('你在后台帮');
    label.append(createIcon(first ? 'build' : 'send', 'icon-18'), el('span', null, first ? '交代的任务' : '新的指示'),
      el('span', 'msg-time', time ? formatTime(time) : ''));
    row.append(label, el('div', 'bubble bubble-console body-large', content));
    return [row];
  }
  const attachments = parseAttachments(rawAttachments);
  if (s.kind !== 'web' && isPoke(content, attachments)) return [pokeNote(content)];
  if (s.kind === 'web') {
    const node = el('div', 'msg-console new');
    node.append(el('div', 'bubble bubble-user body-large', content));
    if (time) node.append(el('div', 'msg-label label-small', formatTime(time)));
    return [node];
  }
  const parsedList = parseUser(content, s.kind);
  const carrier = parsedList.findLast(p => !p.seen && !p.time && !p.console);
  return parsedList.filter(parsed => !parsed.seen && !parsed.time).map(parsed => {
    if (parsed.console) {
      const row = el('div', 'msg msg-console new');
      const label = el('div', 'msg-label label-small');
      label.append(createIcon('terminal', 'icon-18'), el('span', null, '控制台'));
      row.append(label, el('div', 'bubble bubble-console body-large', parsed.text));
      if (time) row.title = formatTime(time);
      return row;
    }
    const who = s.kind === 'private' ? { qq: s.target, name: parsed.name ?? s.name } : speaker(parsed.name);
    const name = who.name ?? (s.kind === 'private' ? s.name : '群友');
    const row = el('div', 'msg msg-in new');
    const avatar = createAvatar(who.qq, name);
    if (time) avatar.title = `${name} · ${formatTime(time)}`;
    row.append(avatar);
    const body = el('div', 'msg-body');
    if (s.kind === 'group') {
      const label = el('div', 'msg-label label-small');
      if (parsed.tag) label.append(el('span', 'owner-chip label-small', parsed.tag));
      label.append(el('span', 'msg-name', name));
      body.append(label);
    }
    const own = parsed === carrier ? attachments : [];
    const text = stripPlaceholders(parsed.text, own);
    const inline = text || parsed.quote ? own.filter(a => a.kind === 'image' || a.kind === 'sticker') : [];
    if (text || parsed.quote) {
      const bubble = renderSaid(meText(mentionNames(text)), 'bubble bubble-in body-large');
      if (parsed.quote) bubble.prepend(quoteBlock(parsed.quote, row));
      bubble.append(...attachmentNodes(inline));
      body.append(bubble);
    }
    body.append(...attachmentNodes(own.filter(a => !inline.includes(a))));
    row.append(body);
    return row;
  });
}

function quoteParts(quote) {
  const body = quote.replace(/^回复 /, '');
  const colon = body.indexOf('：');
  if (colon < 0) return { who: null, text: body };
  const who = body.slice(0, colon).replace(/（.*$/, '');
  const rest = body.slice(colon + 1);
  const nested = rest.lastIndexOf('）：');
  return { who, text: nested >= 0 && rest.startsWith('（') ? rest.slice(nested + 2) : rest };
}

function quoteBlock(quote, row) {
  const { who, text } = quoteParts(quote);
  const block = el('button', 'msg-quote state-layer');
  block.type = 'button';
  block.title = '点一下跳到原消息';
  const shown = who ? `${who === '你' ? selfName() : who}：${text}` : text;
  block.append(el('span', 'msg-quote-text body-small', shown));
  const image = IMAGE_REF.exec(text);
  if (image) {
    const img = el('img', 'msg-quote-image');
    img.src = `/api/media/image?id=${image[1]}`;
    img.alt = '被回复的图片';
    img.loading = 'lazy';
    img.addEventListener('error', () => img.remove());
    block.append(img);
  }
  block.addEventListener('click', () => locateQuoted(row, who, text));
  return block;
}

function locateQuoted(row, who, text) {
  const snippet = text.replace(/…$/, '').replace(/\[(图片|表情包)[^\]]*\]/g, '').replace(/\s+/g, ' ').trim().slice(0, 14);
  const wantsImage = !snippet && IMAGE_REF.test(text);
  const mine = who === '你';
  const rows = [...row.parentElement.children];
  const above = rows.slice(0, rows.indexOf(row)).reverse()
    .filter(node => node.classList.contains(mine ? 'reply-bot' : 'msg-in'));
  const matches = node => (snippet ? node.textContent.replace(/\s+/g, ' ').includes(snippet) : wantsImage && node.querySelector('img'));
  const byName = node => mine || !who || $('.msg-name', node)?.textContent === who;
  const found = (snippet || wantsImage) && (above.find(node => byName(node) && matches(node)) ?? above.find(matches));
  if (found) {
    found.scrollIntoView({ block: 'center', behavior: 'smooth' });
    found.classList.remove('msg-flash', 'new');
    requestAnimationFrame(() => found.classList.add('msg-flash'));
    return;
  }
  showSnackbar(who ? '原消息不在已经加载的这段记录里，往上翻翻看' : '找不到被回复的那条消息');
}

function botEntry(turn, cards, s) {
  const wrap = el('div', 'reply reply-bot new');
  const qq = isQq(s?.kind);
  if (qq) wrap.append(selfAvatar(turn.createdAt));
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
  if (!qq) wrap.append(el('div', 'bubble-meta label-small', formatTime(turn.createdAt)));
  return wrap;
}

function currentSession() {
  return sessions.find(x => x.session === selected) ?? { session: selected, kind: detail?.kind, target: detail?.target, name: detail?.name };
}

function summaryCard(s) {
  return noteCard('history', s.kind === 'mind'
    ? `更早的念头已整理成一段回忆（${detail.compactions} 次）`
    : `更早的对话已压缩成摘要（${detail.compactions} 次）`,
  detail.summary + (detail.recent ? `\n\n—— 保留的最近原文 ——\n${detail.recent}` : ''));
}

function olderButton() {
  const button = el('button', 'text-button label-large state-layer timeline-older',
    `加载更早的消息（还有 ${detail.totalTurns - detail.turns.length} 条）`);
  button.type = 'button';
  button.addEventListener('click', loadOlder);
  return button;
}

function timelineHead(s) {
  if (detail.totalTurns > detail.turns.length) return [olderButton()];
  return detail.summary ? [summaryCard(s)] : [];
}

function turnNodes(turns, s) {
  const mind = s.kind === 'mind';
  const nodes = [];
  const cards = new Map();
  for (const turn of turns) {
    if (turn.role === 'user') nodes.push(...stamped(turn.createdAt, userEntries(turn.content, turn.createdAt, s, turn.attachments), s));
    else if (turn.role === 'assistant') nodes.push(...(mind ? [mindEntry(turn, cards)] : stamped(turn.createdAt, [botEntry(turn, cards, s)], s)));
    else if (turn.role === 'tool') {
      const card = cards.get(turn.toolCallId);
      if (card) settleToolCard(card, turn.content, turn.toolError);
    }
  }
  return nodes;
}

async function loadOlder() {
  if (loadingOlder || !detail?.turns.length || detail.totalTurns <= detail.turns.length) return;
  const session = selected;
  loadingOlder = true;
  try {
    const data = await request('GET', `/${encode(session)}?limit=${OLDER_PAGE}&before=${detail.turns[0].id}`);
    if (session !== selected || !detail) return;
    detail.turns = [...data.turns, ...detail.turns];
    shown = detail.turns.length;
    const s = currentSession();
    const timeline = $('#session-timeline');
    const height = timeline.scrollHeight;
    const top = timeline.scrollTop;
    timeline.querySelector(':scope > .timeline-older')?.remove();
    const latest = lastAt;
    lastAt = 0;
    const older = turnNodes(data.turns, s);
    const first = timeline.firstElementChild;
    if (first?.classList.contains('chat-time') && Number(first.dataset.at) - lastAt < STAMP_GAP) first.remove();
    lastAt = latest;
    timeline.prepend(...timelineHead(s), ...older);
    timeline.scrollTop = top + (timeline.scrollHeight - height);
  } catch (err) {
    showSnackbar(`读取更早的消息失败：${err.message}`, 'error');
  } finally {
    loadingOlder = false;
  }
}

function renderTimeline() {
  const s = currentSession();
  const timeline = $('#session-timeline');
  const mind = s.kind === 'mind';
  lastAt = 0;
  const nodes = [...timelineHead(s), ...turnNodes(detail.turns, s)];
  if (!nodes.length) nodes.push(el('div', 'empty-state body-medium', s.kind === 'web' ? WEB_EMPTY : mind ? MIND_EMPTY : '这个会话还没有记录'));
  timeline.replaceChildren(...nodes);
  live = new Map();
  for (const turn of detail.active ?? []) {
    if (!ownsLive(turn.session)) startLive(turn, true);
  }
  bottom.stick();
}

function ownsLive(session) {
  return Boolean(streaming.get(session)?.reply.wrap.isConnected);
}

function appendToTimeline(node) {
  const timeline = $('#session-timeline');
  if (!isMind(selected)) bottom.stick();
  timeline.querySelector(':scope > .empty-state')?.remove();
  timeline.append(node);
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
  const s = currentSession();
  if (turn.prompt) timeline.append(...stamped(turn.startedAt, userEntries(turn.prompt, turn.startedAt, s), s));
  const reply = createReply(timeline, node => {
    node.classList.add(isMind(turn.session) ? 'mind-step' : 'reply-bot');
    if (!isQq(s.kind)) return appendToTimeline(node);
    const at = turn.startedAt ?? Date.now();
    node.prepend(selfAvatar(at));
    for (const stamp of timeStamp(at)) appendToTimeline(stamp);
    return appendToTimeline(node);
  });
  live.set(turn.turnId, reply);
  if (replay) for (const part of turn.parts ?? []) reply.apply(part.type, part);
}

async function loadDetail() {
  if (!selected) return;
  const session = selected;
  try {
    const data = await request('GET', `/${encode(session)}?limit=${Math.max(PAGE, shown)}`);
    if (session !== selected) return;
    detail = data;
    speakerIds = data.speakers ?? {};
    self = data.self?.name ? data.self : fallbackSelf;
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
  if (session.startsWith('task:')) {
    const found = sessions.find(s => s.session === session);
    const id = found ? taskGroupOf(found) : 'qq';
    if (!groupOpen[id]) openGroup(id, true);
  }
  selected = session;
  detail = null;
  shown = PAGE;
  live = new Map();
  lastAt = 0;
  $('#session-placeholder').hidden = true;
  $('#session-view').hidden = false;
  $('#session-timeline').replaceChildren();
  bottom.stick();
  showMindBar(isMind(session));
  renderList();
  renderHeader(currentSession());
  await loadDetail();
  updateSend();
  if (!isMind(session) && !session.startsWith('task:')) $('#session-input').focus();
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
  listRows?.reset();
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
    const current = currentSession();
    const nodes = stamped(data.time, userEntries(data.text, data.time, current, data.attachments), current);
    const timeline = $('#session-timeline');
    timeline.querySelector(':scope > .empty-state')?.remove();
    timeline.append(...nodes);
    if (isMind(data.session)) pruneMind();
  }
}

function onSaid(data) {
  const s = sessions.find(x => x.session === data.session);
  if (s) {
    s.preview = data.content;
    s.previewRole = 'assistant';
    s.lastActive = data.time;
    renderList();
  } else {
    scheduleList();
  }
  if (data.session !== selected || isMind(data.session)) return;
  const timeline = $('#session-timeline');
  const near = timeline.scrollTop + timeline.clientHeight >= timeline.scrollHeight - 64;
  timeline.querySelector(':scope > .empty-state')?.remove();
  const current = currentSession();
  timeline.append(...stamped(data.time, [botEntry({ role: 'assistant', content: data.content, createdAt: data.time }, new Map(), current)], current));
  if (near) timeline.scrollTop = timeline.scrollHeight;
}

function onEvent(type, data) {
  if (type === 'heard') {
    onHeard(data);
    return;
  }
  if (type === 'said') {
    onSaid(data);
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
  const types = ['turn-start', 'turn-running', 'turn-end', 'text', 'reasoning', 'tool-call', 'tool-result', 'retry', 'compaction', 'blocked', 'heard', 'said'];
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
  $('#session-send').disabled = !selected || isMind(selected) || selected.startsWith('task:') || (!text && !(web && attachmentCount()));
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
    if (['clear', 'compact'].includes(name)) {
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
  const images = takeAttachments();
  if (!text && !images.length) return;
  const interjecting = streaming.has(session) && !images.length;
  resetInput();
  if (!interjecting) appendToTimeline(userBubbleRow(text, images));
  const reply = createReply($('#session-timeline'), node => {
    node.classList.add('reply-bot');
    return appendToTimeline(node);
  });
  const controller = new AbortController();
  const entry = { controller, reply };
  if (!streaming.has(session)) streaming.set(session, entry);
  renderHeader(currentSession());
  updateSend();
  try {
    await streamTurn(session, text, images, reply, controller.signal);
  } finally {
    if (streaming.get(session) === entry) streaming.delete(session);
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
  loadSelf().then(() => {
    if (detail) renderTimeline();
  });
  connect();
  loadList();
  setInterval(renderList, 30_000);
}

export function initAgentSessions() {
  bottom = stickToBottom($('#session-timeline'));
  $('#session-timeline').addEventListener('scroll', e => {
    if (e.currentTarget.scrollTop < 80 && !isMind(selected)) loadOlder();
  }, { passive: true });
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
