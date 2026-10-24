import { $, el } from './dom.js';
import { createIcon, setIcon } from './icons.js';
import { MIND_TOOLS, TOOLS } from './agent-workspace.js';

export function formatTokens(n) {
  if (n >= 1_000_000) return `${+(n / 1_000_000).toFixed(n % 1_000_000 ? 1 : 0)}M`;
  return n >= 1000 ? `${(n / 1000).toFixed(n >= 10000 ? 0 : 1)}k` : String(n);
}

function statChip(icon, text, title = '') {
  const node = el('span', 'mind-chip label-large');
  node.append(createIcon(icon, 'icon-18'), el('span', null, text));
  if (title) node.title = title;
  return node;
}

export function renderContext(host, data, mind = false) {
  const tokens = data?.contextTokens ?? 0;
  const window = data?.contextWindow ?? 0;
  const at = data?.compactAt ?? 0;
  const chips = [];
  const share = at ? Math.min(1, tokens / at) : 0;
  const context = statChip('history', at ? `上下文 ${formatTokens(tokens)} / ${formatTokens(at)}（${Math.round(share * 100)}%）` : `上下文 ${formatTokens(tokens)}`,
    at ? `用到 ${formatTokens(at)} 时会自动${mind ? '把更早的念头整理成回忆' : '压缩更早的对话'}` : '模型没配上下文长度，不会自动压缩');
  if (at) {
    const meter = el('span', `mind-meter${share >= 0.85 ? ' near' : ''}`);
    const bar = el('span');
    bar.style.width = `${Math.round(share * 100)}%`;
    meter.append(bar);
    context.append(meter);
  }
  chips.push(context);
  if (at) chips.push(statChip('auto_stories', `满了自动${mind ? '整理成回忆' : '压缩'}${window ? ` · 模型窗口 ${formatTokens(window)}` : ''}`));
  if (data?.compactions > 0) chips.push(statChip('history', mind ? `已整理 ${data.compactions} 次` : `已压缩 ${data.compactions} 次`));
  host.replaceChildren(...chips);
}

export function formatSize(bytes) {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return bytes >= 1024 ? `${Math.round(bytes / 1024)} KB` : `${bytes} B`;
}

export function metaLine(result) {
  const parts = [result.model, `${(result.latencyMs / 1000).toFixed(1)} 秒`].filter(Boolean);
  if (result.promptTokens >= 0 && result.completionTokens >= 0) {
    parts.push(`输入 ${formatTokens(result.promptTokens)} · 输出 ${formatTokens(result.completionTokens)}`);
  }
  if (result.cachedTokens > 0) parts.push(`缓存命中 ${formatTokens(result.cachedTokens)}`);
  return el('div', 'bubble-meta label-small', parts.join(' · '));
}

export function parseArgs(text) {
  try {
    const value = JSON.parse(text);
    return value && typeof value === 'object' ? value : {};
  } catch {
    return {};
  }
}

const MAX_ARG = 4000;
const STEER = { tell: '转达', pause: '暂停', resume: '继续', stop: '叫停' };

function dynamicMeta(name) {
  const [kind, owner, ...rest] = name.split('__');
  if (kind === 'mcp' && owner) return { icon: 'lan', label: `MCP · ${owner}`, tool: rest.join('__') };
  if (kind === 'plugin' && owner) return { icon: 'extension', label: `插件 · ${owner}`, tool: rest.join('__') };
  return null;
}

const toolMeta = name => TOOLS[name] ?? MIND_TOOLS[name] ?? dynamicMeta(name) ?? { icon: 'build', label: name };

const join = (...parts) => parts.filter(part => part !== undefined && part !== null && part !== '').join(' · ');

function firstValues(args) {
  return Object.values(args).filter(v => ['string', 'number', 'boolean'].includes(typeof v)).slice(0, 2).map(String).join(' · ');
}

function toolDetail(name, args, meta) {
  const group = args.group_id ? `群 ${args.group_id}` : '';
  const user = args.user_id ? `QQ ${args.user_id}` : '';
  switch (name) {
    case 'say_in_group': case 'tell_group': return join(group, args.text ?? args.intent);
    case 'say_private': case 'tell_private': return join(user, args.text ?? args.intent);
    case 'settle': return join(`#${args.id ?? ''}`, args.result);
    case 'remind': return join(args.at, args.what);
    case 'find_person': return args.name ? `「${args.name}」` : '';
    case 'answer_friend': return join(args.approve ? '同意' : '拒绝', user);
    case 'answer_group': return join(args.approve ? '同意' : '拒绝', group, user);
    case 'read_group': return join(group, args.count && `${args.count} 条`);
    case 'recall': case 'remember': return join(args.about, args.term, args.text ?? args.query);
    case 'webfetch': return join(args.url, args.find && `找「${args.find}」`, args.offset && `从第 ${args.offset} 字`);
    case 'subagent': return args.continue ? `接着 #${String(args.continue).replace('#', '')}` : join(args.title, args.kind === 'build' ? '动手' : args.kind === 'research' ? '查资料' : '');
    case 'start_task': return args.title ?? '';
    case 'steer_task': return join(args.task && `#${String(args.task).replace('#', '')}`, STEER[args.action], args.message);
    case 'view_image': return join(args.image, args.area && `放大 ${args.area}`);
    case 'image': return join(args.prompt, args.size, args.n > 1 && `${args.n} 张`);
    case 'qq_api_search': return args.query ? `「${args.query}」` : args.category ?? '全部分类';
    case 'qq_api_call': return args.action ?? '';
    case 'websearch': case 'search': return args.query ? `「${args.query}」` : '';
    case 'shell': return args.description || args.command || '';
    case 'mute': return join(group, user, args.minutes && `${args.minutes} 分钟`);
    case 'unmute': case 'kick': case 'poke': return join(group, user);
    case 'recall_messages': return join(group, user || '自己', `${args.count ?? 1} 条`);
    case 'delete_friend': return user;
    case 'message_stranger': return join(user, args.text);
    case 'collect_sticker': return args.id ? `#${args.id}` : '';
    case 'read_forward': return args.id ?? '';
    case 'skill': return args.name ?? '';
    case 'mcp_read_resource': return join(args.server, args.uri);
    case 'mcp_list_resources': return args.server ?? '全部服务';
    case 'mcp_get_prompt': return join(args.server, args.name ?? '列出全部');
    case 'grep': return join(args.pattern && `「${args.pattern}」`, args.path);
    case 'glob': return join(args.pattern, args.path);
    case 'todowrite': {
      const todos = Array.isArray(args.todos) ? args.todos : [];
      return `${todos.filter(t => t.status === 'completed').length}/${todos.length} 完成`;
    }
    case 'patch': {
      const files = [...(args.patchText ?? '').matchAll(/^\*\*\* (?:Add|Update|Delete) File: (.+)$/gm)].map(m => m[1].trim());
      return `${files.slice(0, 3).join('、')}${files.length > 3 ? ` 等 ${files.length} 个` : ''}`;
    }
    default:
      if (meta.tool) return join(meta.tool, firstValues(args));
      return args.path ?? args.pattern ?? firstValues(args);
  }
}

function clip(text) {
  return text.length > MAX_ARG ? `${text.slice(0, MAX_ARG)}…（共 ${text.length} 字）` : text;
}

function pretty(text) {
  const value = text ?? '';
  const start = value.search(/(^|\n)\s*[[{]/);
  if (start < 0) return value;
  const head = value.slice(0, start);
  try {
    return `${head}${head ? '\n' : ''}${JSON.stringify(JSON.parse(value.slice(start)), null, 2)}`;
  } catch {
    return value;
  }
}

function argsView(raw) {
  const args = parseArgs(raw);
  const entries = Object.entries(args);
  if (!entries.length) {
    const text = (raw ?? '').trim();
    return text && text !== '{}' ? el('pre', 'tool-output body-small', clip(text)) : null;
  }
  const box = el('div', 'tool-args');
  for (const [key, value] of entries) {
    box.append(el('span', 'tool-arg-key label-medium', key),
      el('span', 'tool-arg-value body-small', clip(typeof value === 'string' ? value : JSON.stringify(value, null, 2))));
  }
  return box;
}

function section(label, body, className = '') {
  const node = el('div', `tool-section ${className}`.trim());
  node.append(el('span', 'tool-section-label label-medium', label), body);
  return node;
}

function setOpen(card, open) {
  card.dataset.open = String(open);
  $('.tool-head', card).setAttribute('aria-expanded', String(open));
  const fold = $('.tool-fold', card);
  fold.classList.toggle('open', open);
  fold.inert = !open;
}

function foldCard(className, icon, label, detail) {
  const card = el('div', className);
  const head = el('div', 'tool-head state-layer');
  head.setAttribute('role', 'button');
  head.setAttribute('aria-expanded', 'false');
  head.tabIndex = 0;
  const badge = el('span', 'tool-icon');
  badge.append(createIcon(icon, 'icon-18'));
  const text = el('span', 'tool-text');
  text.append(el('span', 'tool-label label-large', label));
  if (detail) text.append(el('span', 'tool-detail body-small', detail));
  head.append(badge, text);
  const fold = el('div', 'tool-fold');
  fold.inert = true;
  const inner = el('div', 'tool-fold-inner');
  fold.append(inner);
  card.append(head, fold);
  const toggle = () => setOpen(card, card.dataset.open !== 'true');
  head.addEventListener('click', toggle);
  head.addEventListener('keydown', e => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      toggle();
    }
  });
  return { card, head, inner };
}

export function noteCard(icon, label, text) {
  const { card, head, inner } = foldCard('tool-card checkpoint-card', icon, label, '');
  head.append(createIcon('chevron_right', 'icon-18 tool-chevron'));
  inner.append(el('pre', 'tool-output body-small', text));
  return card;
}

const calls = new WeakMap();
const EDITS = new Set(['edit', 'write', 'patch']);
const DIFF = /\+(\d+) -(\d+)\)/g;

function lineCount(text) {
  if (!text) return 0;
  const lines = text.split(/\r?\n/);
  return lines[lines.length - 1] === '' ? lines.length - 1 : lines.length;
}

function diffStats(name, args, output) {
  const counted = [...(output ?? '').matchAll(DIFF)];
  if (counted.length) {
    return counted.reduce((sum, m) => ({ added: sum.added + Number(m[1]), removed: sum.removed + Number(m[2]) }), { added: 0, removed: 0 });
  }
  if (name === 'write') return { added: lineCount(args.content), removed: null };
  if (name === 'edit') {
    const times = Number(/\((\d+) replacement/.exec(output ?? '')?.[1] ?? 1);
    const a = (args.oldString ?? '').split(/\r?\n/);
    const b = (args.newString ?? '').split(/\r?\n/);
    let start = 0;
    while (start < a.length && start < b.length && a[start] === b[start]) start++;
    let endA = a.length;
    let endB = b.length;
    while (endA > start && endB > start && a[endA - 1] === b[endB - 1]) {
      endA--;
      endB--;
    }
    return { added: (endB - start) * times, removed: (endA - start) * times };
  }
  const lines = (args.patchText ?? '').split(/\r?\n/).filter(line => !line.startsWith('***'));
  return { added: lines.filter(line => line.startsWith('+')).length, removed: lines.filter(line => line.startsWith('-')).length };
}

function diffBadge({ added, removed }) {
  const box = el('span', 'tool-diff label-large');
  box.append(el('span', 'tool-diff-add', `+${added}`));
  if (removed !== null) box.append(el('span', 'tool-diff-del', `-${removed}`));
  return box;
}

export function toolCard(call, live = false) {
  const meta = toolMeta(call.name);
  const { card, head, inner } = foldCard(`tool-card${live ? ' new' : ''}`, meta.icon ?? 'build', meta.label,
    toolDetail(call.name, parseArgs(call.arguments), meta));
  card.dataset.state = 'running';
  if (live) card.dataset.live = '';
  const status = el('span', 'tool-status label-medium');
  status.append(createIcon('progress_activity', 'icon-18'));
  head.append(status, createIcon('chevron_right', 'icon-18 tool-chevron'));
  const args = argsView(call.arguments);
  if (args) inner.append(section('参数', args));
  inner.append(section('结果', el('pre', 'tool-output body-small'), 'tool-result'));
  calls.set(card, call);
  return card;
}

export function settleToolCard(card, output, error) {
  card.dataset.state = error ? 'error' : 'done';
  const status = $('.tool-status', card);
  status.replaceChildren(createIcon(error ? 'error' : 'check_circle', 'icon-18'));
  const call = calls.get(card);
  $('.tool-diff', card)?.remove();
  if (!error && call && EDITS.has(call.name)) status.before(diffBadge(diffStats(call.name, parseArgs(call.arguments), output)));
  $('.tool-result .tool-section-label', card).textContent = error ? '出错了' : '结果';
  $('.tool-output', $('.tool-result', card)).textContent = pretty(output) || '（没有输出）';
  if (error) setOpen(card, true);
}

const INNER_OPEN = '<内心>';
const INNER_CLOSE = '</内心>';

const NOTE = /<记住(?:\s+关于\s*=\s*["'“”]?([^"'“”>]*)["'“”]?)?\s*>([\s\S]*?)(?:<\/记住>|$)/g;

const ERRAND = /<去做>([\s\S]*?)(?:<\/去做>|$)/g;
const PROMISE = /<承诺>([\s\S]*?)(?:<\/承诺>|$)/g;
const DONE = /<完成>([\s\S]*?)(?:<\/完成>|$)/g;
const POKE = /<拍一拍\s*\/>|<拍一拍>([\s\S]*?)(?:<\/拍一拍>|$)/g;

const TAG = /[<＜]\s*(\/?)\s*(內心|内心|記住|记住|承諾|承诺|去做|完成|拍一拍)([^>＞]*)[>＞]/g;
const SIMPLIFIED = { 內心: '内心', 記住: '记住', 承諾: '承诺' };
const THOUGHT_ALIAS = /[<＜]\s*(\/?)\s*(?!记住|記住|去做|承诺|承諾|完成|拍一拍)[^<>＜＞\n/]{0,6}(?:内心|內心|心里|心裡|心声|心聲|嘀咕|独白|獨白)[^<>＜＞\n]{0,10}[>＞]/g;
const normalize = text => text.replace(THOUGHT_ALIAS, (_, slash) => `<${slash}内心>`)
  .replace(TAG, (_, slash, name, rest) => `<${slash}${SIMPLIFIED[name] ?? name}${rest.trimEnd()}>`);

export function splitInner(raw) {
  const notes = [];
  const text = normalize(raw).replace(ERRAND, (_, body) => {
    if (body.trim()) notes.push({ errand: true, text: body.trim() });
    return '';
  }).replace(PROMISE, (_, body) => {
    if (body.trim()) notes.push({ promise: true, text: body.trim() });
    return '';
  }).replace(DONE, (_, body) => {
    if (body.trim()) notes.push({ done: true, text: body.trim() });
    return '';
  }).replace(POKE, (_, body) => {
    notes.push({ poke: true, text: (body ?? '').trim() || '对方' });
    return '';
  }).replace(NOTE, (_, about, body) => {
    if (body.trim()) notes.push({ about: about?.trim() ?? '', text: body.trim() });
    return '';
  }).replace(/<\/?记住[^>]*>?$/, '');
  const open = text.indexOf(INNER_OPEN);
  if (open < 0) return { inner: null, said: text.trim(), notes };
  const rest = text.slice(open + INNER_OPEN.length);
  const close = rest.indexOf(INNER_CLOSE);
  const end = close >= 0 ? close : rest.indexOf('\n') >= 0 ? rest.indexOf('\n') : rest.length;
  const after = close >= 0 ? rest.slice(close + INNER_CLOSE.length) : rest.slice(end);
  return { inner: rest.slice(0, end).trim(), said: (text.slice(0, open) + after).trim(), notes };
}

export function noteChips(notes) {
  const label = n => {
    if (n.poke) return `拍了拍 ${n.text}`;
    if (n.errand) return `交给内心去办：${n.text}`;
    if (n.promise) return `答应要做：${n.text}`;
    if (n.done) return `办完了：${n.text}`;
    return `记下了${n.about ? `（${n.about}）` : ''}：${n.text}`;
  };
  return notes.map(n => el('div', 'memory-note label-medium', label(n)));
}

const STICKER = /\[表情包[:：#]#?(\d+)(?:[：:]([^\]]*))?\]/g;

export function fillSaid(node, said) {
  node.replaceChildren();
  let last = 0;
  for (const m of said.matchAll(STICKER)) {
    if (m.index > last) node.append(document.createTextNode(said.slice(last, m.index)));
    const img = el('img', 'sticker-inline');
    img.src = `/api/agent/stickers/${m[1]}/image`;
    img.alt = img.title = m[2] ? `表情包 #${m[1]}：${m[2]}` : `表情包 #${m[1]}`;
    node.append(img);
    last = m.index + m[0].length;
  }
  if (last < said.length) node.append(document.createTextNode(said.slice(last)));
  node.classList.toggle('bubble-bare', last > 0 && !node.textContent.trim());
  return node;
}

export function reasoningBlock(text) {
  const block = el('details', 'thinking');
  const summary = el('summary', 'thinking-summary label-large');
  summary.append(createIcon('lightbulb', 'icon-18'), el('span', 'thinking-label', '推理过程'));
  block.append(summary, el('div', 'thinking-text body-medium', text));
  return block;
}

export function renderSaid(said, className = 'bubble bubble-assistant body-large') {
  return fillSaid(el('div', className), said);
}

function followInner(body, set) {
  let lastTop = 0;
  let touchY = 0;
  body.addEventListener('wheel', e => {
    if (e.deltaY < 0) set(false);
  }, { passive: true });
  body.addEventListener('touchstart', e => {
    touchY = e.touches[0]?.clientY ?? 0;
  }, { passive: true });
  body.addEventListener('touchmove', e => {
    const y = e.touches[0]?.clientY ?? touchY;
    if (y > touchY + 2) set(false);
    touchY = y;
  }, { passive: true });
  body.addEventListener('scroll', () => {
    const down = body.scrollTop > lastTop;
    lastTop = body.scrollTop;
    if (down && body.scrollHeight - body.scrollTop - body.clientHeight < 4) set(true);
  }, { passive: true });
}

export function createReply(list, attach) {
  const wrap = el('div', 'reply new');
  attach(wrap);
  const nearBottom = () => !list.stickyBottom && list.scrollTop + list.clientHeight >= list.scrollHeight - 48;
  const scrollDown = () => {
    if (nearBottom()) list.scrollTop = list.scrollHeight;
  };
  let pendingReasoning = '';
  let pendingText = false;
  let frame = 0;
  let thinkingFollow = true;
  const flush = () => {
    frame = 0;
    if (!pendingReasoning && !pendingText) return;
    const follow = nearBottom();
    if (pendingReasoning && thinking) {
      const body = $('.thinking-text', thinking);
      body.append(pendingReasoning);
      if (thinkingFollow) body.scrollTop = body.scrollHeight;
    }
    pendingReasoning = '';
    if (pendingText) {
      pendingText = false;
      renderText();
    }
    if (follow) list.scrollTop = list.scrollHeight;
  };
  const schedule = () => {
    if (!frame) frame = requestAnimationFrame(flush);
  };
  const flushNow = () => {
    if (frame) cancelAnimationFrame(frame);
    flush();
  };
  let typing = null;
  let text = null;
  let raw = '';
  let inner = null;
  let notesBox = null;
  let thinking = null;
  let thinkStart = 0;
  let step = [];
  let content = '';
  const tools = new Map();

  const add = node => {
    wrap.append(node);
    step.push(node);
    scrollDown();
    return node;
  };
  const showTyping = () => {
    if (typing) return;
    typing = el('div', 'bubble bubble-assistant body-large bubble-typing');
    typing.append(el('span'), el('span'), el('span'));
    wrap.append(typing);
    scrollDown();
  };
  const hideTyping = () => {
    typing?.remove();
    typing = null;
  };
  const renderText = () => {
    if (!text) return;
    const parts = splitInner(raw);
    if (parts.inner !== null && !inner) {
      inner = el('div', 'inner-voice body-medium');
      text.before(inner);
      step.push(inner);
    }
    if (inner) inner.textContent = parts.inner ?? '';
    fillSaid(text, parts.said);
    text.hidden = !parts.said;
    if (parts.notes.length && !notesBox) {
      notesBox = el('div', 'memory-notes');
      text.after(notesBox);
      step.push(notesBox);
    }
    notesBox?.replaceChildren(...noteChips(parts.notes));
  };
  const closeThinking = () => {
    flushNow();
    if (!thinking) return;
    thinking.open = false;
    thinking.classList.remove('live');
    setIcon($('.thinking-summary svg', thinking), 'lightbulb');
    $('.thinking-label', thinking).textContent = `已思考 ${((performance.now() - thinkStart) / 1000).toFixed(1)} 秒`;
    thinking = null;
  };
  const endStep = () => {
    closeThinking();
    text = null;
    inner = null;
    notesBox = null;
    step = [];
  };
  showTyping();

  const reply = {
    wrap,
    reasoning(delta) {
      if (!thinking) {
        thinking = el('details', 'thinking live');
        thinking.open = true;
        const summary = el('summary', 'thinking-summary label-large');
        summary.append(createIcon('lightbulb_fill', 'icon-18'), el('span', 'thinking-label', '思考中…'));
        const body = el('div', 'thinking-text body-medium');
        followInner(body, value => {
          thinkingFollow = value;
        });
        thinking.append(summary, body);
        thinkingFollow = true;
        thinkStart = performance.now();
        hideTyping();
        add(thinking);
      }
      pendingReasoning += delta;
      schedule();
    },
    text(delta) {
      if (!text) {
        closeThinking();
        hideTyping();
        text = add(el('div', 'bubble bubble-assistant body-large'));
        text.hidden = true;
        raw = '';
      }
      raw += delta;
      content += delta;
      pendingText = true;
      schedule();
    },
    toolCall(call) {
      endStep();
      hideTyping();
      const card = toolCard(call, true);
      tools.set(call.id, card);
      wrap.append(card);
      scrollDown();
    },
    toolResult(result) {
      const card = tools.get(result.id);
      if (!card) return;
      settleToolCard(card, result.output, result.error);
      showTyping();
    },
    file(file) {
      hideTyping();
      const item = el('a', 'file-card state-layer');
      item.href = file.url;
      item.download = file.name;
      if (file.mime.startsWith('image/')) {
        const img = el('img');
        img.src = file.url;
        img.alt = file.name;
        item.classList.add('file-image');
        item.append(img);
      } else {
        const icon = el('span', 'file-icon');
        icon.append(createIcon('description'));
        const info = el('span', 'file-info');
        info.append(el('span', 'body-large file-name', file.name), el('span', 'body-small file-size', formatSize(file.size)));
        item.append(icon, info, createIcon('download'));
      }
      wrap.append(item);
      showTyping();
    },
    retry(info) {
      for (const node of step) node.remove();
      if (text) content = content.slice(0, Math.max(0, content.length - raw.length));
      endStep();
      add(el('div', 'retry-note label-medium', `请求失败，${Math.max(1, Math.round(info.delayMs / 1000))} 秒后第 ${info.attempt} 次重试：${info.error}`));
      showTyping();
    },
    blocked(info) {
      flushNow();
      hideTyping();
      if (text) {
        text.textContent = info.replacement ?? '';
        text.hidden = !info.replacement;
      }
      const how = info.byModel ? '模型审核' : '关键词';
      add(el('div', 'retry-note label-medium', `原回复涉及${info.category}，被${how}拦下，${info.replacement ? '改发上面这句' : '没有发出'}`));
    },
    compaction(reason) {
      wrap.before(el('div', 'chat-divider label-medium', reason === 'overflow' ? '超出上下文，已压缩之前的对话' : '上下文接近上限，已压缩之前的对话'));
    },
    finish(result) {
      hideTyping();
      closeThinking();
      if (result.finishReason === 'merged') {
        wrap.remove();
        return;
      }
      if (!content.trim() && result.content) wrap.append(el('div', 'bubble bubble-assistant body-large', result.content));
      if (result.model) wrap.append(metaLine(result));
      scrollDown();
    },
    fail(message) {
      hideTyping();
      closeThinking();
      wrap.append(el('div', 'bubble bubble-error body-large', message));
      scrollDown();
    },
    abort() {
      hideTyping();
      closeThinking();
    },
    apply(type, data) {
      const handlers = {
        text: () => reply.text(data.delta ?? data),
        reasoning: () => reply.reasoning(data.delta ?? data),
        compaction: () => reply.compaction(data.reason ?? data),
        retry: () => reply.retry(data),
        'tool-call': () => reply.toolCall(data),
        'tool-result': () => reply.toolResult(data),
        file: () => reply.file(data),
        blocked: () => reply.blocked(data),
      };
      handlers[type]?.();
    },
  };
  return reply;
}
