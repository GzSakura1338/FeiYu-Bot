import { $, $$ } from './dom.js';
import { setIcon } from './icons.js';
import { closeMenu, openMenu } from './menu.js';
import { onPageChange } from './router.js';
import { menuSelect } from './select-menu.js';
import { showSnackbar } from './snackbar.js';

const FIELDS = ['baseUrl', 'model'];
let state = null;
let testing = false;
let models = [];

async function request(method, path = '', body) {
  const res = await fetch(`/api/agent/image-gen${path}`, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`);
  return json;
}

const input = name => $(`#imagegen-form [name="${name}"]`);
const QUOTA_FIELDS = ['perHour', 'perDay'];
const quotaText = n => (n > 0 ? String(n) : '');

function dirty() {
  if (!state) return false;
  return input('apiKey').value.trim() !== '' || FIELDS.some(name => input(name).value.trim() !== (state[name] ?? ''))
    || QUOTA_FIELDS.some(name => input(name).value.trim() !== quotaText(state[name]));
}

function quotaHint() {
  if (!state || (!state.perHour && !state.perDay)) return `不限。最近一小时画了 ${state?.usedHour ?? 0} 张，今天 ${state?.usedToday ?? 0} 张`;
  const limits = [state.perHour && `每小时 ${state.perHour} 张`, state.perDay && `每天 ${state.perDay} 张`].filter(Boolean).join('、');
  return `${state.scope === 'session' ? '每个群、每个私聊各自' : '所有聊天加起来'}最多${limits}。最近一小时一共画了 ${state.usedHour} 张，今天 ${state.usedToday} 张`;
}

function updateSave() {
  $('#imagegen-save').disabled = !dirty();
}

function render(fill = true) {
  const toggle = $('#imagegen-enabled');
  toggle.setAttribute('aria-checked', String(Boolean(state?.enabled)));
  toggle.disabled = !state;
  $('#imagegen-hint').textContent = state?.enabled ? '已开启：有人让她画图时会直接生成，发到当前聊天' : '关着时她看不到 image 工具';
  for (const button of $$('#imagegen-size [role="radio"]')) button.setAttribute('aria-checked', String(button.dataset.size === state?.size));
  for (const button of $$('#imagegen-scope [role="radio"]')) button.setAttribute('aria-checked', String(button.dataset.scope === (state?.scope ?? 'global')));
  $('#imagegen-quota-hint').textContent = quotaHint();
  if (fill && state) {
    for (const name of FIELDS) input(name).value = state[name] ?? '';
    for (const name of QUOTA_FIELDS) input(name).value = quotaText(state[name]);
    input('apiKey').value = '';
  }
  const keyHint = $('#imagegen-form [data-field="apiKey"] .supporting');
  keyHint.textContent = state?.hasKey ? `已保存（${state.keyHint}），留空就不改` : keyHint.dataset.hint;
  for (const hint of $$('#imagegen-form [data-field]:not([data-field="apiKey"]) .supporting')) hint.textContent = hint.dataset.hint;
  updateSave();
}

async function load() {
  try {
    state = await request('GET');
    render();
  } catch (err) {
    showSnackbar(`读取生图设置失败：${err.message}`, 'error');
  }
}

function formBody() {
  const body = {};
  for (const name of FIELDS) body[name] = input(name).value.trim();
  for (const name of QUOTA_FIELDS) {
    const text = input(name).value.trim();
    if (text !== '' && !/^\d{1,5}$/.test(text)) throw new Error('限额要填 0 到 10000 的整数，留空就是不限');
    body[name] = text === '' ? 0 : Number(text);
  }
  const key = input('apiKey').value.trim();
  if (key) body.apiKey = key;
  return body;
}

async function setScope(scope) {
  if (!state || scope === state.scope) return;
  try {
    state = await request('PUT', '', { scope });
    render(false);
  } catch (err) {
    showSnackbar(`保存失败：${err.message}`, 'error');
  }
}

async function save(e) {
  e?.preventDefault();
  if (!dirty()) return true;
  $('#imagegen-save').disabled = true;
  try {
    state = await request('PUT', '', { ...formBody(), enabled: state.enabled });
    render();
    if (e) showSnackbar('生图设置已保存');
    return true;
  } catch (err) {
    showSnackbar(`保存失败：${err.message}`, 'error');
    updateSave();
    return false;
  }
}

async function toggle() {
  if (!state) return;
  try {
    state = await request('PUT', '', { ...formBody(), enabled: !state.enabled });
    render();
  } catch (err) {
    showSnackbar(err.message, 'error');
    render(false);
  }
}

async function setSize(size) {
  if (!state || size === state.size) return;
  try {
    state = await request('PUT', '', { size });
    render(false);
  } catch (err) {
    showSnackbar(`保存失败：${err.message}`, 'error');
  }
}

function showModels(focus = false) {
  const field = input('model');
  if (!models.length) return closeMenu();
  const value = field.value.trim();
  const query = value.toLowerCase();
  const matches = models.some(m => m.id === value) ? models : models.filter(m => m.id.toLowerCase().includes(query));
  openMenu(field.parentElement, (matches.length ? matches : models).map(m => ({ value: m.id, label: m.id, hint: m.image ? '生图' : undefined })), {
    selected: value,
    focus,
    onSelect: id => {
      field.value = id;
      updateSave();
      field.focus();
    },
  });
}

async function fetchModels() {
  const button = $('#imagegen-fetch');
  const hint = $('#imagegen-form [data-field="model"] .supporting');
  button.disabled = true;
  $('svg', button).classList.add('spin');
  try {
    ({ models } = await request('POST', '/models', { baseUrl: input('baseUrl').value.trim(), apiKey: input('apiKey').value.trim() }));
    const images = models.filter(m => m.image).length;
    hint.textContent = models.length
      ? `获取到 ${models.length} 个模型${images ? `，其中 ${images} 个像生图模型排在前面` : ''}，点输入框选择或直接输入`
      : '接口没有返回模型';
    input('model').focus();
    showModels(false);
  } catch (err) {
    showSnackbar(`获取模型列表失败：${err.message}`, 'error');
  } finally {
    button.disabled = false;
    $('svg', button).classList.remove('spin');
  }
}

async function test() {
  if (testing || !(await save())) return;
  const button = $('#imagegen-test');
  const preview = $('#imagegen-preview');
  testing = true;
  button.disabled = true;
  button.lastChild.textContent = '正在画…';
  try {
    const result = await request('POST', '/test', {});
    $('img', preview).src = result.image;
    $('figcaption', preview).textContent = `画好了，用了 ${Math.round(result.seconds)} 秒`;
    preview.hidden = false;
  } catch (err) {
    showSnackbar(`试画失败：${err.message}`, 'error');
  } finally {
    testing = false;
    button.disabled = false;
    button.lastChild.textContent = '试画一张';
  }
}

export function initSettingsImageGen() {
  $('#imagegen-enabled').addEventListener('click', toggle);
  $('#imagegen-form').addEventListener('submit', save);
  $('#imagegen-form').addEventListener('input', updateSave);
  $('#imagegen-test').addEventListener('click', test);
  $('#imagegen-fetch').addEventListener('click', fetchModels);
  for (const button of $$('#imagegen-size [role="radio"]')) button.addEventListener('click', () => setSize(button.dataset.size));
  menuSelect($('#imagegen-size'));
  for (const button of $$('#imagegen-scope [role="radio"]')) button.addEventListener('click', () => setScope(button.dataset.scope));
  menuSelect($('#imagegen-scope'));
  const model = input('model');
  model.addEventListener('input', () => showModels(false));
  model.addEventListener('click', () => showModels(false));
  model.addEventListener('keydown', e => {
    if (e.key === 'ArrowDown' && models.length) {
      e.preventDefault();
      showModels(true);
    }
  });
  $('#imagegen-key-eye').addEventListener('click', e => {
    const key = input('apiKey');
    const show = key.type === 'password';
    key.type = show ? 'text' : 'password';
    setIcon($('svg', e.currentTarget), show ? 'visibility_off' : 'visibility');
  });
  onPageChange(page => {
    if (page === 'settings') load();
  });
}
