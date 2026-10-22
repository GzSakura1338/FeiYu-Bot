import { $, $$, el } from './dom.js';
import { setIcon, createIcon } from './icons.js';
import { openDialog, closeDialog, confirmDialog } from './dialog.js';
import { showSnackbar } from './snackbar.js';
import { openMenu, closeMenu, isMenuOpen } from './menu.js';
import { renderMood } from './agent-page.js';

const PROTOCOLS = {
  'openai-chat': { label: 'OpenAI Chat Completions', baseUrl: '例如 https://api.openai.com/v1，只填域名会自动补上 /v1' },
  'openai-responses': { label: 'OpenAI Responses', baseUrl: '例如 https://api.openai.com/v1，只填域名会自动补上 /v1' },
  anthropic: { label: 'Anthropic Messages', baseUrl: '例如 https://api.anthropic.com，会自动补上 /v1，请求 /v1/messages' },
};
const TEXT_FIELDS = ['name', 'baseUrl', 'apiKey', 'model', 'temperature', 'maxTokens', 'contextWindow'];
const VISION = {
  true: { label: '支持看图', className: 'vision-yes' },
  false: { label: '不支持看图', className: 'vision-no' },
  null: { label: '看图未检测', className: 'vision-unknown' },
};

let state = { qqReply: false, active: null, profiles: [] };
let editing = null;
let models = [];
let touched = new Set();
let snapshot = '';
let limitsTimer;

const input = name => $(`#model-${name}`);
const activeProfile = () => state.profiles.find(p => p.id === state.active) ?? null;
const hostOf = url => {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
};

export function formatTokens(n) {
  if (!n) return '';
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1).replace(/\.0$/, '')}M`;
  if (n >= 1000) return `${Math.round(n / 1000)}K`;
  return String(n);
}

async function request(method, path = '', body) {
  const res = await fetch(`/api/agent${path}`, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`);
  return json;
}

function renderAll() {
  renderList();
  renderSwitch();
  const active = activeProfile();
  $('#chat-model span').textContent = active ? active.name : '未选择模型';
  $('#chat-model').title = active ? `${active.model}，点击切换` : '切换模型';
}

function openChatModelMenu() {
  const anchor = $('#chat-model');
  if (isMenuOpen(anchor)) return closeMenu();
  openMenu(anchor, state.profiles.map(p => ({ value: p.id, label: p.name, hint: p.model })), {
    selected: state.active,
    empty: '还没有模型',
    onSelect: id => {
      const profile = state.profiles.find(p => p.id === id);
      if (profile) activate(profile, null);
    },
  });
}

async function probeVision(profile, button) {
  button.disabled = true;
  $('svg', button).classList.add('spin');
  try {
    state = await request('POST', `/profiles/${profile.id}/probe-vision`, {});
    const vision = state.profiles.find(p => p.id === profile.id)?.vision;
    showSnackbar(`「${profile.name}」${vision ? '支持看图' : '不支持看图'}`, vision ? 'success' : 'info');
  } catch (err) {
    showSnackbar(`检测失败：${err.message}`, 'error');
  }
  renderAll();
}

function renderList() {
  const items = state.profiles.map(profile => {
    const active = profile.id === state.active;
    const item = el('li', 'ws-item');
    item.setAttribute('role', 'radio');
    item.setAttribute('aria-checked', String(active));
    item.tabIndex = 0;

    const headline = el('div', 'list-headline body-large');
    headline.append(el('span', null, profile.name));
    if (active) headline.append(el('span', 'in-use label-small', '使用中'));
    const vision = VISION[profile.vision ?? null];
    headline.append(el('span', `vision-chip label-small ${vision.className}`, vision.label));
    if (profile.systemRole === false) {
      const chip = el('span', 'system-chip label-small', '已改用消息注入人格');
      chip.title = '这个接口会忽略 system 角色的系统提示词，人格改为在对话开头注入';
      headline.append(chip);
    }
    if (profile.toolCall === false) {
      const chip = el('span', 'system-chip label-small', '不支持工具调用');
      chip.title = '接口拒绝了工具调用，文件工具已对这个模型关闭；修改协议、地址、模型或 Key 后会重新尝试';
      headline.append(chip);
    }
    const text = el('div', 'list-text');
    const label = PROTOCOLS[profile.protocol]?.label ?? profile.protocol;
    const context = profile.limits?.context ? ` · ${formatTokens(profile.limits.context)} 上下文` : '';
    text.append(headline, el('div', 'list-supporting body-medium', `${label} · ${profile.model} · ${hostOf(profile.baseUrl)}${context}`));

    const actions = el('div', 'ws-actions');
    const probe = iconButton('image_search', `检测「${profile.name}」能否看图`, () => probeVision(profile, probe));
    actions.append(probe, iconButton('edit', `编辑「${profile.name}」`, () => openEditor(profile)));
    if (!active || !state.qqReply) actions.append(iconButton('delete', `删除「${profile.name}」`, () => remove(profile)));

    item.append(el('span', 'radio'), text, actions);
    item.addEventListener('click', () => activate(profile, item));
    item.addEventListener('keydown', e => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        activate(profile, item);
      }
    });
    return item;
  });
  $('#model-list').replaceChildren(...items);
  $('#model-empty').hidden = items.length > 0;
  renderEffort();
  renderPacing(document.activeElement !== $('#pacing-interval'));
}

const EFFORT_HINT = {
  default: '跟随模型默认，不额外指定',
  none: '不思考，直接回复，最快',
  low: '稍微想一下再回',
  medium: '正常思考',
  high: '认真想清楚再回，慢一些',
  max: '想到最深，最慢也最费 token',
};

function renderEffort() {
  const effort = state.reasoningEffort ?? 'default';
  for (const button of $$('#effort-choice button')) button.setAttribute('aria-checked', String(button.dataset.effort === effort));
  $('#effort-hint').textContent = EFFORT_HINT[effort] ?? '';
}

async function setEffort(effort) {
  try {
    state = await request('PUT', '/effort', { effort });
    renderEffort();
    showSnackbar(`思考强度：${EFFORT_HINT[effort]}`, 'success');
  } catch (err) {
    showSnackbar(`保存失败：${err.message}`, 'error');
  }
}

const pacing = () => state.pacing ?? { intervalMs: 2000, maxPieces: 4 };
const seconds = ms => String(Math.round(ms / 100) / 10);

function renderPacing(resetInput = true) {
  const { intervalMs, maxPieces } = pacing();
  for (const button of $$('#pacing-pieces button')) button.setAttribute('aria-checked', String(Number(button.dataset.pieces) === maxPieces));
  $('#pacing-pieces-hint').textContent = maxPieces <= 1 ? '一次回复整段发出，不拆开' : `一次回复最多拆成 ${maxPieces} 条`;
  if (resetInput) $('#pacing-interval').value = seconds(intervalMs);
  syncPacing();
}

function readInterval() {
  const value = Number($('#pacing-interval').value.trim());
  return Number.isFinite(value) && value >= 0.5 && value <= 30 ? Math.round(value * 1000) : null;
}

function syncPacing() {
  const ms = readInterval();
  const field = $('#pacing-interval').closest('.text-field');
  field.classList.toggle('invalid', ms === null && $('#pacing-interval').value.trim() !== '');
  $('#pacing-save').disabled = ms === null || ms === pacing().intervalMs;
}

async function savePacing(patch, success) {
  try {
    state = await request('PUT', '/pacing', { ...pacing(), ...patch });
    renderPacing();
    showSnackbar(success, 'success');
  } catch (err) {
    showSnackbar(`保存失败：${err.message}`, 'error');
    renderPacing();
  }
}

function renderSwitch() {
  const active = activeProfile();
  $('#agent-qq-switch').setAttribute('aria-checked', String(state.qqReply));
  $('#agent-switch-hint').textContent = state.qqReply
    ? `已开启 · ${active?.name ?? ''}`
    : active ? '已关闭' : '先添加一个模型';
  $('#agent-group-switch').setAttribute('aria-checked', String(Boolean(state.groupMode)));
  $('#agent-group-hint').textContent = state.groupMode
    ? `已开启 · 被 @、回复、叫“${(state.wakeWords ?? []).join('/')}”时回，其他看心情`
    : '已关闭 · 群里只回复 @';
  $('#agent-free-switch').setAttribute('aria-checked', String(Boolean(state.freeWill)));
  $('#agent-free-hint').textContent = state.freeWill ? '已开启 · 一直自己想事情，想聊天就去群里说、去私聊找人' : '已关闭 · 念头暂停，只在有人找她时说话';
  renderMood(state.mood);
}

async function toggleFreeWill() {
  try {
    const res = await fetch('/api/agent/mind', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled: !state.freeWill }),
    });
    const json = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`);
    state.freeWill = json.enabled;
    const name = json.name || '她';
    if (state.freeWill && json.why) {
      showSnackbar(`自由活动开了，但${json.why}，${name}暂时还想不了事情`, 'error');
    } else if (state.freeWill) {
      showSnackbar(`${name}开始自由活动了：会一直自己想事情，有人叫她马上就会注意到，想聊天了会自己去群里说、去私聊找人。在「会话 › 内心」能看她在想什么`, 'success', 6000);
    } else {
      showSnackbar(`${name}的自由活动关了：念头先停下来，有人找她时照常回复`, 'success');
    }
  } catch (err) {
    showSnackbar(`切换失败：${err.message}`, 'error');
  }
  renderAll();
}

async function toggleGroupMode() {
  const next = !state.groupMode;
  if (next) {
    const ok = await confirmDialog({
      icon: 'groups',
      title: '开启群聊模式？',
      text: `开启后，机器人在所有群里会像群友一样聊天：被 @、有人回复它、或者有人叫“${(state.wakeWords ?? []).join('”“')}”时一定会回；没人叫它时，它会自己看群里在聊什么，按心情和兴趣决定接不接话。每次看群都会调用一次模型，发送间隔在「模型」页的「发送节奏」里调。消息都以当前登录的 QQ 号发出。`,
      confirm: '开启',
    });
    if (!ok) return;
  }
  try {
    state = await request('PUT', '/group-mode', { enabled: next });
    showSnackbar(next ? '群聊模式已开启' : '群聊模式已关闭', 'success');
  } catch (err) {
    showSnackbar(`切换失败：${err.message}`, 'error');
  }
  renderAll();
}

function iconButton(icon, label, onClick) {
  const button = el('button', 'icon-button state-layer');
  button.type = 'button';
  button.title = button.ariaLabel = label;
  button.append(createIcon(icon));
  button.addEventListener('click', e => {
    e.stopPropagation();
    onClick();
  });
  return button;
}

async function load() {
  try {
    state = await request('GET');
  } catch (err) {
    showSnackbar(`读取模型配置失败：${err.message}`, 'error');
  }
  renderAll();
}

async function activate(profile, item) {
  if (profile.id === state.active) return;
  item?.classList.add('busy');
  try {
    state = await request('POST', `/profiles/${profile.id}/activate`, {});
    showSnackbar(`已切换到「${profile.name}」`, 'success');
  } catch (err) {
    showSnackbar(`切换失败：${err.message}`, 'error');
  }
  renderAll();
}

async function remove(profile) {
  const ok = await confirmDialog({
    icon: 'delete',
    title: '删除这个模型？',
    text: `「${profile.name}」（${profile.model}）会被删除，此操作不能撤销。`,
    confirm: '删除',
  });
  if (!ok) return;
  try {
    state = await request('DELETE', `/profiles/${profile.id}`, {});
    showSnackbar(`已删除「${profile.name}」`, 'success');
  } catch (err) {
    showSnackbar(`删除失败：${err.message}`, 'error');
  }
  renderAll();
}

async function toggleQq() {
  const next = !state.qqReply;
  const active = activeProfile();
  if (next) {
    if (!active) {
      showSnackbar('请先添加一个模型');
      return;
    }
    const ok = await confirmDialog({
      icon: 'lightbulb',
      title: '开启 QQ 自动回复？',
      text: `开启后，私聊消息和群里 @ 这个账号的消息都会由「${active.name}」（${active.model}）自动回复，回复会以当前登录的 QQ 号发出。`,
      confirm: '开启',
    });
    if (!ok) return;
  }
  try {
    state = await request('PUT', '/qq', { enabled: next });
    showSnackbar(next ? 'QQ 自动回复已开启' : 'QQ 自动回复已关闭', 'success');
  } catch (err) {
    showSnackbar(`操作失败：${err.message}`, 'error');
  }
  renderAll();
}

const optionalNumber = name => {
  const raw = input(name).value.trim();
  return raw === '' ? null : Number(raw);
};

function readForm() {
  return {
    name: input('name').value.trim(),
    protocol: input('protocol').dataset.value || null,
    baseUrl: input('baseUrl').value.trim(),
    apiKey: input('apiKey').value.trim(),
    model: input('model').value.trim(),
    temperature: optionalNumber('temperature'),
    maxTokens: optionalNumber('maxTokens'),
    contextWindow: optionalNumber('contextWindow'),
  };
}

const isInt = (v, min, max) => Number.isInteger(v) && v >= min && v <= max;

function validate(v) {
  const errors = {};
  if (!v.name) errors.name = '名称不能为空';
  if (!PROTOCOLS[v.protocol]) errors.protocol = '请选择协议';
  if (!/^https?:\/\/\S+$/.test(v.baseUrl)) errors.baseUrl = 'Base URL 要以 http:// 或 https:// 开头';
  if (!v.model) errors.model = '模型不能为空';
  if (v.temperature !== null && !(v.temperature >= 0 && v.temperature <= 2)) errors.temperature = '0～2 之间';
  if (v.maxTokens !== null && !isInt(v.maxTokens, 0, 1_000_000)) errors.maxTokens = '0～1000000 的整数';
  if (v.contextWindow !== null && !isInt(v.contextWindow, 4096, 10_000_000)) errors.contextWindow = '4096～10000000 的整数';
  return errors;
}

function setProtocol(next) {
  const trigger = input('protocol');
  trigger.dataset.value = next ?? '';
  $('.select-value', trigger).textContent = PROTOCOLS[next]?.label ?? '';
  trigger.closest('.text-field').classList.toggle('has-value', Boolean(PROTOCOLS[next]));
  $('#model-form [data-field="baseUrl"] .supporting').dataset.hint = PROTOCOLS[next]?.baseUrl ?? '先选择 API 协议';
  models = [];
}

function openProtocolMenu() {
  const trigger = input('protocol');
  if (isMenuOpen(trigger.parentElement)) return closeMenu();
  openMenu(trigger.parentElement, Object.entries(PROTOCOLS).map(([value, p]) => ({ value, label: p.label })), {
    selected: trigger.dataset.value,
    onSelect: value => {
      setProtocol(value);
      touched.add('protocol');
      renderForm();
      trigger.focus();
    },
  });
}

function showModelMenu(focus = false) {
  const field = input('model');
  if (!models.length) return closeMenu();
  const value = field.value.trim();
  const query = value.toLowerCase();
  const matches = models.includes(value) ? models : models.filter(id => id.toLowerCase().includes(query));
  openMenu(field.parentElement, (matches.length ? matches : models).map(id => ({ value: id, label: id })), {
    selected: value,
    focus,
    onSelect: id => {
      field.value = id;
      touched.add('model');
      renderForm();
      refreshLimits();
      field.focus();
    },
  });
}

function refreshLimits() {
  clearTimeout(limitsTimer);
  limitsTimer = setTimeout(async () => {
    const model = input('model').value.trim();
    const contextHint = $('#model-form [data-field="contextWindow"] .supporting');
    const outputHint = $('#model-form [data-field="maxTokens"] .supporting');
    if (!model) {
      contextHint.dataset.hint = '留空自动';
      outputHint.dataset.hint = '留空自动';
    } else {
      try {
        const limits = await request('GET', `/limits?model=${encodeURIComponent(model)}`);
        contextHint.dataset.hint = limits.context ? `留空自动：${formatTokens(limits.context)}` : '未识别，留空则不自动压缩';
        outputHint.dataset.hint = limits.output ? `留空自动（上限 ${formatTokens(limits.output)}）` : '留空用默认值';
      } catch {
        return;
      }
    }
    renderForm();
  }, 250);
}

function renderForm() {
  const errors = validate(readForm());
  const profile = state.profiles.find(p => p.id === editing);
  for (const field of $$('#model-form [data-field]')) {
    const name = field.dataset.field;
    const hint = $('.supporting', field);
    const error = touched.has(name) ? errors[name] : null;
    field.classList.toggle('invalid', Boolean(error));
    const keyHint = name === 'apiKey' && profile?.hasApiKey ? `已保存（${profile.apiKeyHint}），留空表示不修改` : null;
    hint.textContent = error ?? keyHint ?? hint.dataset.hint;
  }
  $('#model-save').disabled = Object.keys(errors).length > 0;
}

const isDirty = () => JSON.stringify(readForm()) !== snapshot;

function openEditor(profile) {
  editing = profile?.id ?? null;
  for (const name of TEXT_FIELDS) input(name).value = '';
  if (profile) {
    input('name').value = profile.name;
    input('baseUrl').value = profile.baseUrl;
    input('model').value = profile.model;
    input('temperature').value = profile.temperature ?? '';
    input('maxTokens').value = profile.maxTokens > 0 ? profile.maxTokens : '';
    input('contextWindow').value = profile.contextWindow ?? '';
  }
  setProtocol(profile?.protocol ?? null);
  input('apiKey').type = 'password';
  setIcon($('#model-key-eye svg'), 'visibility');
  $('#model-form-error').hidden = true;
  $('#model-dialog-title').textContent = profile ? `编辑「${profile.name}」` : '添加模型';
  touched = new Set();
  snapshot = JSON.stringify(readForm());
  refreshLimits();
  renderForm();
  openDialog($('#model-dialog'), { guard: isDirty });
  input('name').focus();
}

async function save(e) {
  e.preventDefault();
  const v = readForm();
  [...TEXT_FIELDS, 'protocol'].forEach(name => touched.add(name));
  renderForm();
  if (Object.keys(validate(v)).length) return;

  const button = $('#model-save');
  const errorBox = $('#model-form-error');
  button.disabled = true;
  errorBox.hidden = true;
  try {
    state = editing ? await request('PUT', `/profiles/${editing}`, v) : await request('POST', '/profiles', v);
    closeDialog($('#model-dialog'), 'saved');
    showSnackbar(editing ? `已保存「${v.name}」` : `已添加「${v.name}」`, 'success');
    renderAll();
  } catch (err) {
    errorBox.textContent = `保存失败：${err.message}`;
    errorBox.hidden = false;
    button.disabled = false;
  }
}

async function fetchModels() {
  const v = readForm();
  const problems = validate(v);
  if (problems.protocol || problems.baseUrl) {
    touched.add('protocol');
    touched.add('baseUrl');
    renderForm();
    return;
  }
  const button = $('#model-fetch');
  const errorBox = $('#model-form-error');
  button.disabled = true;
  $('svg', button).classList.add('spin');
  errorBox.hidden = true;
  try {
    ({ models } = await request('POST', '/models', { protocol: v.protocol, baseUrl: v.baseUrl, apiKey: v.apiKey, profileId: editing }));
    $('#model-form [data-field="model"] .supporting').textContent = models.length
      ? `获取到 ${models.length} 个模型，点输入框选择或直接输入`
      : '接口没有返回模型';
    input('model').focus();
    showModelMenu(false);
  } catch (err) {
    errorBox.textContent = `获取模型列表失败：${err.message}`;
    errorBox.hidden = false;
  } finally {
    button.disabled = false;
    $('svg', button).classList.remove('spin');
  }
}

export function initAgentModels() {
  $('#model-add').addEventListener('click', () => openEditor(null));
  for (const button of $$('#effort-choice button')) button.addEventListener('click', () => setEffort(button.dataset.effort));
  for (const button of $$('#pacing-pieces button')) {
    button.addEventListener('click', () => {
      const maxPieces = Number(button.dataset.pieces);
      if (maxPieces === pacing().maxPieces) return;
      savePacing({ maxPieces }, maxPieces <= 1 ? '回复不再拆开发' : `回复最多拆成 ${maxPieces} 条`);
    });
  }
  $('#pacing-form').addEventListener('input', syncPacing);
  $('#pacing-form').addEventListener('submit', e => {
    e.preventDefault();
    const intervalMs = readInterval();
    if (intervalMs !== null) savePacing({ intervalMs }, `每条之间间隔 ${seconds(intervalMs)} 秒`);
  });
  $('#model-form').addEventListener('input', e => {
    if (e.target.name) touched.add(e.target.name);
    renderForm();
  });
  input('protocol').addEventListener('click', openProtocolMenu);
  input('protocol').addEventListener('keydown', e => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      openProtocolMenu();
    }
  });
  input('model').addEventListener('input', () => {
    showModelMenu(false);
    refreshLimits();
  });
  input('model').addEventListener('click', () => showModelMenu(false));
  input('model').addEventListener('keydown', e => {
    if (e.key === 'ArrowDown' && models.length) {
      e.preventDefault();
      showModelMenu(true);
    }
  });
  $('#chat-model').addEventListener('click', openChatModelMenu);
  $('#model-form').addEventListener('submit', save);
  $('#model-cancel').addEventListener('click', () => closeDialog($('#model-dialog'), 'cancel'));
  $('#model-key-eye').addEventListener('click', e => {
    const key = input('apiKey');
    const show = key.type === 'password';
    key.type = show ? 'text' : 'password';
    setIcon($('svg', e.currentTarget), show ? 'visibility_off' : 'visibility');
  });
  $('#model-fetch').addEventListener('click', fetchModels);
  $('#agent-qq-switch').addEventListener('click', toggleQq);
  $('#agent-group-switch').addEventListener('click', toggleGroupMode);
  $('#agent-free-switch').addEventListener('click', toggleFreeWill);
  load();
}
