import { $, $$, el } from './dom.js';
import { setIcon, createIcon } from './icons.js';
import { openDialog, closeDialog, confirmDialog } from './dialog.js';
import { showSnackbar } from './snackbar.js';

const API = '/api/ws-profiles';
const FIELDS = ['name', 'host', 'port', 'path', 'token'];
const DEFAULTS = { name: '', host: '127.0.0.1', port: 6199, path: '/ws', token: '' };

let data = { active: null, profiles: [] };
let editing = null;

const urlOf = p => `ws://${p.host}:${p.port}${p.path}`;
const input = name => $(`#ws-${name}`);

async function request(method, path = '', body) {
  const res = await fetch(API + path, {
    method,
    headers: body === undefined && method === 'GET' ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`);
  return json;
}

function renderList() {
  const items = data.profiles.map(profile => {
    const active = profile.id === data.active;
    const item = el('li', 'ws-item');
    item.setAttribute('role', 'radio');
    item.setAttribute('aria-checked', String(active));
    item.tabIndex = 0;

    const headline = el('div', 'list-headline body-large');
    headline.append(el('span', null, profile.name));
    if (active) headline.append(el('span', 'in-use label-small', '使用中'));
    const text = el('div', 'list-text');
    text.append(headline, el('div', 'list-supporting body-medium', `${urlOf(profile)}${profile.token ? '' : ' · 不校验 token'}`));

    const actions = el('div', 'ws-actions');
    actions.append(iconButton('edit', `编辑「${profile.name}」`, () => openEditor(profile)));
    if (!active) actions.append(iconButton('delete', `删除「${profile.name}」`, () => confirmDelete(profile)));

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
  $('#ws-list').replaceChildren(...items);
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
    data = await request('GET');
  } catch (err) {
    showSnackbar(`读取反向 WebSocket 配置失败：${err.message}`, 'error');
  }
  renderList();
}

async function activate(profile, item) {
  if (profile.id === data.active) return;
  item.classList.add('busy');
  try {
    data = await request('POST', `/${profile.id}/activate`, {});
    showSnackbar(`已切换到「${profile.name}」，正在监听 ${urlOf(profile)}`, 'success');
  } catch (err) {
    showSnackbar(`切换失败：${err.message}`, 'error');
  }
  renderList();
}

async function confirmDelete(profile) {
  const ok = await confirmDialog({
    icon: 'delete',
    title: '删除这个配置？',
    text: `「${profile.name}」（${urlOf(profile)}）会被删除，此操作不能撤销。`,
    confirm: '删除',
  });
  if (!ok) return;
  try {
    data = await request('DELETE', `/${profile.id}`, {});
    showSnackbar(`已删除「${profile.name}」`, 'success');
  } catch (err) {
    showSnackbar(`删除失败：${err.message}`, 'error');
  }
  renderList();
}

function readForm() {
  return {
    name: input('name').value.trim(),
    host: input('host').value.trim(),
    port: Number(input('port').value),
    path: input('path').value.trim(),
    token: input('token').value.trim(),
  };
}

function validate(v) {
  const errors = {};
  if (!v.name) errors.name = '名称不能为空';
  if (!v.host) errors.host = '监听地址不能为空';
  else if (!/^[A-Za-z0-9.\-:[\]]+$/.test(v.host)) errors.host = '监听地址格式不对';
  if (!Number.isInteger(v.port) || v.port < 1 || v.port > 65535) errors.port = '端口必须在 1～65535 之间';
  if (!v.path.startsWith('/')) errors.path = '路径必须以 / 开头';
  else if (/[\s?#]/.test(v.path)) errors.path = '路径不能包含空格、? 或 #';
  if (/\s/.test(v.token)) errors.token = 'token 不能包含空格';
  return errors;
}

let touched = new Set();

function renderForm() {
  const v = readForm();
  const errors = validate(v);
  for (const field of $$('#ws-form .text-field')) {
    const name = field.dataset.field;
    const hint = $('.supporting', field);
    const error = touched.has(name) ? errors[name] : null;
    const warn = name === 'token' && !v.token && /^(0\.0\.0\.0|::|\[::\])$/.test(v.host)
      ? '监听所有网卡且不校验 token，局域网内任何人都能连接' : null;
    field.classList.toggle('invalid', Boolean(error));
    field.classList.toggle('warn', !error && Boolean(warn));
    hint.textContent = error ?? warn ?? hint.dataset.hint;
  }
  $('#ws-url').textContent = errors.host || errors.port || errors.path ? '—' : urlOf(v);
  $('#ws-save').disabled = Object.keys(errors).length > 0;
}

function openEditor(profile) {
  editing = profile?.id ?? null;
  const values = profile ?? { ...DEFAULTS, name: `配置 ${data.profiles.length + 1}`, port: suggestPort() };
  for (const name of FIELDS) input(name).value = values[name] ?? '';
  touched = new Set();
  $('#ws-form-error').hidden = true;
  const token = input('token');
  token.type = 'password';
  setIcon($('#ws-token-eye svg'), 'visibility');
  $('#ws-dialog-title').textContent = profile ? `编辑「${profile.name}」` : '添加反向 WebSocket';
  renderForm();
  const snapshot = JSON.stringify(readForm());
  openDialog($('#ws-dialog'), { guard: () => JSON.stringify(readForm()) !== snapshot });
  input('name').select();
}

function suggestPort() {
  const used = new Set(data.profiles.map(p => p.port));
  let port = 6199;
  while (used.has(port)) port++;
  return port;
}

async function save(e) {
  e.preventDefault();
  const v = readForm();
  FIELDS.forEach(name => touched.add(name));
  renderForm();
  if (Object.keys(validate(v)).length) return;

  const button = $('#ws-save');
  const errorBox = $('#ws-form-error');
  button.disabled = true;
  errorBox.hidden = true;
  try {
    data = editing ? await request('PUT', `/${editing}`, v) : await request('POST', '', v);
    closeDialog($('#ws-dialog'), 'saved');
    showSnackbar(editing
      ? (editing === data.active ? `已保存并重新监听 ${urlOf(v)}` : `已保存「${v.name}」`)
      : `已添加「${v.name}」，点选它即可启用`, 'success');
    renderList();
  } catch (err) {
    errorBox.textContent = `保存失败：${err.message}`;
    errorBox.hidden = false;
    button.disabled = false;
  }
}

function randomToken() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return [...bytes].map(b => b.toString(16).padStart(2, '0')).join('');
}

export function initWsProfiles() {
  const dialog = $('#ws-dialog');
  $('#ws-add').addEventListener('click', () => openEditor(null));
  $('#ws-form').addEventListener('input', e => {
    if (e.target.name) touched.add(e.target.name);
    renderForm();
  });
  $('#ws-form').addEventListener('submit', save);
  $('#ws-cancel').addEventListener('click', () => closeDialog(dialog, 'cancel'));

  $('#ws-token-eye').addEventListener('click', e => {
    const token = input('token');
    const show = token.type === 'password';
    token.type = show ? 'text' : 'password';
    setIcon($('svg', e.currentTarget), show ? 'visibility_off' : 'visibility');
    e.currentTarget.title = e.currentTarget.ariaLabel = show ? '隐藏 token' : '显示 token';
  });
  $('#ws-token-gen').addEventListener('click', () => {
    input('token').value = randomToken();
    touched.add('token');
    renderForm();
  });
  $('#ws-copy').addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText($('#ws-url').textContent);
      showSnackbar('已复制到剪贴板', 'success');
    } catch {
      showSnackbar('复制失败，请手动选择文本', 'error');
    }
  });
  load();
}
