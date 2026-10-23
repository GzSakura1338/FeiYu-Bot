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

const toolMeta = name => TOOLS[name] ?? MIND_TOOLS[name];

export function toolTitle(name, args) {
  const label = toolMeta(name)?.label ?? name;
  if (name === 'say_in_group') return `${label}（${args.group_id ?? ''}）：${args.text ?? ''}`;
  if (name === 'say_private') return `${label}（QQ ${args.user_id ?? ''}）：${args.text ?? ''}`;
  if (name === 'tell_group') return `${label}（${args.group_id ?? ''}）：${args.intent ?? ''}`;
  if (name === 'tell_private') return `${label}（QQ ${args.user_id ?? ''}）：${args.intent ?? ''}`;
  if (name === 'settle') return `${label} #${args.id ?? ''}${args.result ? `：${args.result}` : ''}`;
  if (name === 'find_person') return `${label}「${args.name ?? ''}」`;
  if (name === 'answer_friend') return `${label}：${args.approve ? '同意' : '拒绝'} QQ ${args.user_id ?? ''}`;
  if (name === 'answer_group') return `${label}：${args.approve ? '同意' : '拒绝'} 群 ${args.group_id ?? ''}${args.user_id ? ` · QQ ${args.user_id}` : ''}`;
  if (name === 'read_group') return `${label} ${args.group_id ?? ''}`;
  if (name === 'recall' || name === 'remember') return `${label} ${args.about ?? ''}${args.text ? `：${args.text}` : ''}`;
  if (name === 'webfetch') return `${label} ${args.url ?? ''}`;
  if (name === 'websearch' || name === 'search') return `${label}「${args.query ?? ''}」`;
  if (name === 'shell') return `${label} ${args.description || args.command || ''}`;
  if (name === 'todowrite') {
    const todos = Array.isArray(args.todos) ? args.todos : [];
    return `${label}（${todos.filter(t => t.status === 'completed').length}/${todos.length}）`;
  }
  if (name === 'patch') {
    const files = [...(args.patchText ?? '').matchAll(/^\*\*\* (?:Add|Update|Delete) File: (.+)$/gm)].map(m => m[1].trim());
    return `${label} ${files.slice(0, 3).join('、')}${files.length > 3 ? ` 等 ${files.length} 个` : ''}`;
  }
  const target = args.path ?? args.pattern ?? '';
  if (name === 'grep') return `${label}「${args.pattern ?? ''}」${args.path ? ` · ${args.path}` : ''}`;
  if (name === 'glob') return `${label} ${args.pattern ?? ''}${args.path ? ` · ${args.path}` : ''}`;
  return target ? `${label} ${target}` : label;
}

export function toolCard(call) {
  const card = el('details', 'tool-card');
  card.dataset.state = 'running';
  const summary = el('summary', 'tool-summary label-large');
  const status = el('span', 'tool-status');
  status.append(createIcon('progress_activity', 'icon-18'));
  summary.append(createIcon(toolMeta(call.name)?.icon ?? 'build', 'icon-18'),
    el('span', 'tool-title', toolTitle(call.name, parseArgs(call.arguments))), status);
  card.append(summary, el('pre', 'tool-output body-small', ''));
  return card;
}

export function settleToolCard(card, output, error) {
  card.dataset.state = error ? 'error' : 'done';
  $('.tool-status', card).replaceChildren(createIcon(error ? 'error' : 'check_circle', 'icon-18'));
  $('.tool-output', card).textContent = output;
  if (error) card.open = true;
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
const normalize = text => text.replace(TAG, (_, slash, name, rest) => `<${slash}${SIMPLIFIED[name] ?? name}${rest.trimEnd()}>`);

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
      const card = toolCard(call);
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
