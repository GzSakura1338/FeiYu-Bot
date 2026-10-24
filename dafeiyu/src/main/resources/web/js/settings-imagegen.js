import { $, $$ } from './dom.js';
import { setIcon } from './icons.js';
import { onPageChange } from './router.js';
import { showSnackbar } from './snackbar.js';

const FIELDS = ['baseUrl', 'model', 'size'];
let state = null;
let testing = false;

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

function dirty() {
  if (!state) return false;
  return input('apiKey').value.trim() !== '' || FIELDS.some(name => input(name).value.trim() !== (state[name] ?? ''));
}

function updateSave() {
  $('#imagegen-save').disabled = !dirty();
}

function render(fill = true) {
  const toggle = $('#imagegen-enabled');
  toggle.setAttribute('aria-checked', String(Boolean(state?.enabled)));
  toggle.disabled = !state;
  $('#imagegen-hint').textContent = state?.enabled ? '已开启：有人让她画图时会直接生成，发到当前聊天' : '关着时她看不到 image 工具';
  if (fill && state) {
    for (const name of FIELDS) input(name).value = state[name] ?? '';
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
  const key = input('apiKey').value.trim();
  if (key) body.apiKey = key;
  return body;
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
