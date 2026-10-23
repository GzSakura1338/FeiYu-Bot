import { $, el } from './dom.js';
import { createIcon } from './icons.js';
import { onPageChange } from './router.js';
import { showSnackbar } from './snackbar.js';

const ACCESS = {
  READ: { label: '只读', icon: 'visibility', tip: '只读：直接调用。点一下改成「会改动」' },
  WRITE: { label: '会改动', icon: 'edit', tip: '会改动：调用前先过内容审核、每分钟限 30 次，群里留一行记录。点一下改成「只读」' },
};

let state = null;
let catalog = '';
const opened = new Set();
const rows = new Map();
const counts = new Map();

async function request(method, body) {
  const res = await fetch('/api/agent/qqapi', {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`);
  return json;
}

const find = name => state.actions.find(a => a.name === name);

function onActivate(node, action) {
  node.tabIndex = 0;
  node.addEventListener('click', action);
  node.addEventListener('keydown', e => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      action();
    }
  });
}

function row(action) {
  const text = el('div', 'list-text');
  const headline = el('div', 'list-headline body-medium', action.summary);
  headline.append(' ', el('code', '', action.name));
  text.append(headline);
  const tip = [action.description, action.lockReason && `锁着的原因：${action.lockReason}`].filter(Boolean).join('；');
  if (tip) text.append(el('div', 'list-supporting body-small', tip));
  const item = el('div', 'qqapi-row');
  item.append(text);
  if (action.locked) item.append(el('span', 'kind-chip label-small qqapi-locked', '默认锁着'));
  const access = el('button', 'chip-button qqapi-access label-large state-layer');
  access.type = 'button';
  access.addEventListener('click', () => {
    const current = find(action.name);
    save({ access: { [action.name]: current.access === 'READ' ? 'WRITE' : 'READ' } });
  });
  const toggle = el('button', 'switch');
  toggle.type = 'button';
  toggle.setAttribute('role', 'switch');
  toggle.ariaLabel = `允许 ${action.name}`;
  toggle.append(el('span', 'switch-handle'));
  toggle.addEventListener('click', () => save({ actions: { [action.name]: !find(action.name).on } }));
  item.append(access, toggle);
  rows.set(action.name, { access, toggle });
  return item;
}

function group(category) {
  const list = state.actions.filter(a => a.category === category);
  const open = opened.has(category);
  const head = el('div', 'qqapi-group state-layer');
  head.setAttribute('role', 'button');
  head.setAttribute('aria-expanded', String(open));
  const count = el('span', 'qqapi-count body-medium');
  head.append(createIcon('chevron_right', 'icon-18 qqapi-chevron'), el('span', 'body-large qqapi-group-name', category), count);
  const fold = el('div', `qqapi-fold${open ? ' open' : ''}`);
  fold.inert = !open;
  const inner = el('div', 'qqapi-fold-list');
  inner.append(...list.map(row));
  fold.append(inner);
  onActivate(head, () => {
    const next = !opened.has(category);
    if (next) opened.add(category);
    else opened.delete(category);
    head.setAttribute('aria-expanded', String(next));
    fold.classList.toggle('open', next);
    fold.inert = !next;
  });
  counts.set(category, { count, names: list.map(a => a.name) });
  return [head, fold];
}

function build() {
  rows.clear();
  counts.clear();
  $('#qqapi-groups').replaceChildren(...state.categories.flatMap(group));
  catalog = `${state.version}/${state.actions.length}`;
}

function update() {
  const enabled = Boolean(state.enabled);
  $('#qqapi-enabled').setAttribute('aria-checked', String(enabled));
  const on = state.actions.filter(a => a.on).length;
  $('#qqapi-hint').textContent = enabled
    ? `NapCat ${state.version} 一共 ${state.actions.length} 个接口，开着 ${on} 个`
    : '关着时她看不到这两个工具';
  $('#qqapi-groups').classList.toggle('off', !enabled);
  for (const action of state.actions) {
    const r = rows.get(action.name);
    if (!r) continue;
    r.toggle.setAttribute('aria-checked', String(action.on));
    const kind = ACCESS[action.access] ?? ACCESS.WRITE;
    if (r.access.dataset.access !== action.access) {
      r.access.dataset.access = action.access;
      r.access.dataset.tooltip = kind.tip;
      r.access.ariaLabel = `${action.name}：${kind.label}`;
      r.access.replaceChildren(createIcon(kind.icon, 'icon-18'), el('span', null, kind.label));
    }
  }
  for (const { count, names } of counts.values()) {
    count.textContent = `${names.filter(n => find(n)?.on).length}/${names.length}`;
  }
}

function render() {
  if (!state) return;
  if (catalog !== `${state.version}/${state.actions.length}`) build();
  update();
}

async function save(patch) {
  try {
    state = await request('PUT', { enabled: state?.enabled ?? true, ...patch });
  } catch (err) {
    showSnackbar(`保存失败：${err.message}`, 'error');
  }
  render();
}

async function load() {
  try {
    state = await request('GET');
    render();
  } catch (err) {
    showSnackbar(`读取 QQ 接口设置失败：${err.message}`, 'error');
  }
}

export function initSettingsQqApi() {
  $('#qqapi-enabled').addEventListener('click', () => state && save({ enabled: !state.enabled }));
  onPageChange(page => {
    if (page === 'settings') load();
  });
}
