import { $, $$, el } from './dom.js';
import { createIcon } from './icons.js';
import { openDialog, closeDialog, confirmDialog } from './dialog.js';
import { showSnackbar } from './snackbar.js';
import { openMenu, closeMenu, isMenuOpen } from './menu.js';
const PROMPT_MAX = 20000;
const AVATAR_SIZE = 256;
const FIELDS = ['name', 'prompt'];

let state = { active: null, personas: [] };
let editing = null;
let touched = new Set();
let snapshot = '';
let avatarData = '';

const input = name => $(`#persona-${name}`);
const activePersona = () => state.personas.find(p => p.id === state.active) ?? null;
const firstChar = text => [...(text || '?')][0];

async function request(method, path = '', body) {
  const res = await fetch(`/api/personas${path}`, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`);
  return json;
}

function avatar(persona, className = 'persona-avatar') {
  const node = el('span', className);
  if (!persona) {
    node.append(createIcon('person_off'));
  } else if (persona.avatar) {
    const img = el('img');
    img.src = persona.avatar;
    img.alt = '';
    node.append(img);
  } else {
    node.textContent = firstChar(persona.name);
  }
  return node;
}

function card(persona) {
  const active = persona ? persona.id === state.active : state.active === null;
  const node = el('div', persona ? 'persona-card state-layer' : 'persona-card persona-none state-layer');
  node.setAttribute('role', 'radio');
  node.setAttribute('aria-checked', String(active));
  node.tabIndex = 0;

  const head = el('div', 'persona-head');
  const title = el('div', 'persona-title');
  title.append(el('span', 'title-medium', persona ? persona.name : '不使用人格'));
  if (active) title.append(el('span', 'in-use label-small', '使用中'));
  head.append(avatar(persona), title);
  node.append(head);

  if (!persona) {
    node.append(el('p', 'persona-desc body-medium', '不发送系统提示词，模型用它自己的默认风格回答'));
  } else {
    node.append(el('p', 'persona-prompt body-medium', persona.prompt));
    const actions = el('div', 'persona-actions');
    actions.append(iconButton('edit', `编辑「${persona.name}」`, () => openEditor(persona)),
                   iconButton('delete', `删除「${persona.name}」`, () => remove(persona)));
    node.append(actions);
  }
  const choose = () => activate(persona?.id ?? null);
  node.addEventListener('click', choose);
  node.addEventListener('keydown', e => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      choose();
    }
  });
  return node;
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

function render() {
  $('#persona-grid').replaceChildren(card(null), ...state.personas.map(card));
  const active = activePersona();
  $('#chat-persona span').textContent = active ? active.name : '不使用人格';  if (document.activeElement !== $('#owners-input')) $('#owners-input').value = (state.owners ?? []).join(', ');
  syncOwners();
  loadBlocklist().catch(() => {});
}

const parseOwners = text => text.split(/[\s,，、;；]+/).map(s => s.trim()).filter(Boolean);

function syncOwners() {
  $('#owners-save').disabled = parseOwners($('#owners-input').value).join(',') === (state.owners ?? []).join(',');
}

async function saveOwners(e) {
  e.preventDefault();
  try {
    state = await request('PUT', '/owners', { owners: parseOwners($('#owners-input').value) });
    $('#owners-input').blur();
    showSnackbar(state.owners.length ? `已设置主人：${state.owners.join('、')}` : '已清除主人', 'success');
    render();
  } catch (err) {
    showSnackbar(`保存失败：${err.message}`, 'error');
  }
}

async function loadBlocklist() {
  const b = await request('GET', '/blocklist');
  $('#block-enabled').checked = !!b.enabled;
  $('#block-threshold').value = String(b.threshold ?? 0);
  const items = Object.entries(b.list ?? {});
  const strikes = b.strikes ?? {};
  const box = $('#block-list');
  box.replaceChildren(...(items.length ? items.map(([id, e]) => {
    const row = el('div', null, `🚫 ${id}（${e?.name || '未知'}）${e?.reason ?? ''}${strikes[id] ? ` · 已触发 ${strikes[id]} 次` : ''} `);
    const button = el('button', 'text-button', '解除');
    button.type = 'button';
    button.dataset.unblock = id;
    row.append(button);
    return row;
  }) : [el('span', null, '（没拉黑任何人）')]));
}

async function saveBlocklist() {
  try {
    const b = await request('PUT', '/blocklist', {
      enabled: $('#block-enabled').checked,
      threshold: Number($('#block-threshold').value || 0)
    });
    await loadBlocklist();
    showSnackbar(`拉黑设置已保存（${b.enabled ? '开' : '关'}，阈值 ${b.threshold}）`, 'success');
  } catch (err) {
    showSnackbar(`保存失败：${err.message}`, 'error');
  }
}

async function load() {
  try {
    state = await request('GET');
  } catch (err) {
    showSnackbar(`读取人格失败：${err.message}`, 'error');
  }
  render();
}

async function activate(id) {
  if (id === state.active) return;
  try {
    state = await request('POST', `/${id ?? 'none'}/activate`, {});
    const persona = activePersona();
    showSnackbar(persona ? `已切换到人格「${persona.name}」` : '已关闭人格', 'success');
  } catch (err) {
    showSnackbar(`切换失败：${err.message}`, 'error');
  }
  render();
}

async function remove(persona) {
  const ok = await confirmDialog({
    icon: 'delete',
    title: '删除这个人格？',
    text: `「${persona.name}」会被删除，此操作不能撤销。`,
    confirm: '删除',
  });
  if (!ok) return;
  try {
    state = await request('DELETE', `/${persona.id}`, {});
    showSnackbar(`已删除「${persona.name}」`, 'success');
  } catch (err) {
    showSnackbar(`删除失败：${err.message}`, 'error');
  }
  render();
}

function readForm() {
  return {
    name: input('name').value.trim(),
    avatar: avatarData,
    prompt: input('prompt').value.trim(),
  };
}

function validate(v) {
  const errors = {};
  if (!v.name) errors.name = '名称不能为空';
  if (!v.prompt) errors.prompt = '系统提示词不能为空';
  else if (v.prompt.length > PROMPT_MAX) errors.prompt = `不能超过 ${PROMPT_MAX} 字`;
  return errors;
}

function renderAvatar() {
  const preview = $('#persona-avatar-preview');
  if (avatarData) {
    const img = el('img');
    img.src = avatarData;
    img.alt = '';
    preview.replaceChildren(img);
  } else {
    preview.replaceChildren(input('name').value.trim() ? firstChar(input('name').value.trim()) : createIcon('theater_comedy'));
  }
  $('#persona-avatar-clear').hidden = !avatarData;
}

function cropAvatar(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(url);
      const side = Math.min(img.naturalWidth, img.naturalHeight);
      const canvas = document.createElement('canvas');
      canvas.width = canvas.height = AVATAR_SIZE;
      canvas.getContext('2d').drawImage(img, (img.naturalWidth - side) / 2, (img.naturalHeight - side) / 2, side, side,
        0, 0, AVATAR_SIZE, AVATAR_SIZE);
      const webp = canvas.toDataURL('image/webp', 0.9);
      resolve(webp.startsWith('data:image/webp') ? webp : canvas.toDataURL('image/png'));
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('无法读取这张图片'));
    };
    img.src = url;
  });
}

async function pickAvatar(file) {
  if (!file) return;
  if (!file.type.startsWith('image/')) {
    showSnackbar('请选择图片文件', 'error');
    return;
  }
  try {
    avatarData = await cropAvatar(file);
    renderAvatar();
    renderForm();
  } catch (err) {
    showSnackbar(err.message, 'error');
  }
}

function renderForm() {
  renderAvatar();
  const errors = validate(readForm());
  for (const field of $$('#persona-form [data-field]')) {
    const name = field.dataset.field;
    const hint = $('.supporting', field);
    if (!hint) continue;
    const error = touched.has(name) ? errors[name] : null;
    field.classList.toggle('invalid', Boolean(error));
    hint.textContent = error ?? hint.dataset.hint;
  }
  const area = input('prompt');
  area.style.height = 'auto';
  area.style.height = `${Math.max(area.scrollHeight, Math.round(innerHeight * 0.38))}px`;
  const count = $('#persona-prompt-count');
  count.textContent = `${area.value.length} / ${PROMPT_MAX}`;
  count.classList.toggle('over', area.value.length > PROMPT_MAX);
  $('#persona-save').disabled = Object.keys(errors).length > 0;
}

function openEditor(persona) {
  editing = persona?.id ?? null;
  for (const name of FIELDS) input(name).value = persona?.[name] ?? '';
  avatarData = persona?.avatar ?? '';
  $('#persona-form-error').hidden = true;
  $('#persona-dialog-title').textContent = persona ? `编辑「${persona.name}」` : '添加人格';
  touched = new Set();
  snapshot = JSON.stringify(readForm());
  openDialog($('#persona-dialog'), { guard: () => JSON.stringify(readForm()) !== snapshot });
  renderForm();
  input(persona ? 'prompt' : 'name').focus();
}

async function save(e) {
  e.preventDefault();
  const v = readForm();
  FIELDS.forEach(name => touched.add(name));
  renderForm();
  if (Object.keys(validate(v)).length) return;
  const button = $('#persona-save');
  const errorBox = $('#persona-form-error');
  button.disabled = true;
  errorBox.hidden = true;
  try {
    state = editing ? await request('PUT', `/${editing}`, v) : await request('POST', '', v);
    closeDialog($('#persona-dialog'), 'saved');
    showSnackbar(editing ? `已保存「${v.name}」` : `已添加「${v.name}」`, 'success');
    render();
  } catch (err) {
    errorBox.textContent = `保存失败：${err.message}`;
    errorBox.hidden = false;
    button.disabled = false;
  }
}

function openChatPersonaMenu() {
  const anchor = $('#chat-persona');
  if (isMenuOpen(anchor)) return closeMenu();
  const items = [{ value: '', label: '不使用人格' }, ...state.personas.map(p => ({ value: p.id, label: p.name }))];
  openMenu(anchor, items, {
    selected: state.active ?? '',
    onSelect: id => activate(id || null),
  });
}

export function initAgentPersonas() {
  $('#persona-add').addEventListener('click', () => openEditor(null));
  $('#persona-form').addEventListener('input', e => {
    if (e.target.name) touched.add(e.target.name);
    renderForm();
  });
  $('#persona-avatar-pick').addEventListener('click', () => $('#persona-avatar-file').click());
  $('#persona-avatar-file').addEventListener('change', e => {
    pickAvatar(e.target.files[0]);
    e.target.value = '';
  });
  $('#persona-avatar-clear').addEventListener('click', () => {
    avatarData = '';
    renderForm();
  });
  const pick = $('#persona-avatar-pick');
  pick.addEventListener('dragover', e => {
    e.preventDefault();
    pick.classList.add('dragging');
  });
  pick.addEventListener('dragleave', () => pick.classList.remove('dragging'));
  pick.addEventListener('drop', e => {
    e.preventDefault();
    pick.classList.remove('dragging');
    pickAvatar(e.dataTransfer?.files?.[0]);
  });
  $('#persona-form').addEventListener('submit', save);
  $('#persona-cancel').addEventListener('click', () => closeDialog($('#persona-dialog'), 'cancel'));
  $('#chat-persona').addEventListener('click', openChatPersonaMenu);
  const hint = $('#owners-form .supporting');
  hint.textContent = hint.dataset.hint;
  $('#owners-form').addEventListener('input', syncOwners);
  $('#owners-form').addEventListener('submit', saveOwners);
  $('#block-save').addEventListener('click', saveBlocklist);
  $('#block-list').addEventListener('click', async e => {
    const id = e.target?.dataset?.unblock;
    if (!id) return;
    try {
      await request('PUT', '/blocklist', { remove: [id] });
      await loadBlocklist();
      showSnackbar(`已解除 ${id} 的拉黑`, 'success');
    } catch (err) {
      showSnackbar(`解除失败：${err.message}`, 'error');
    }
  });
  load();
}
