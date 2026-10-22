import { $, $$, el } from './dom.js';
import { createIcon } from './icons.js';
import { showSnackbar } from './snackbar.js';
import { confirmDialog } from './dialog.js';
import { onPageChange } from './router.js';
import { initTabBar } from './tabs.js';
import { viewTransition } from './motion.js';
import { openPluginDetail } from './plugin-detail.js';
import { openInstallDialog } from './plugin-install.js';
import { openMcpDialog, mcpCard } from './plugin-mcp.js';

const STATUS_TONE = {
  running: 'ok',
  starting: 'busy',
  restarting: 'warn',
  stopped: 'idle',
  crashed: 'error',
  invalid: 'error',
  incompatible: 'error',
  'needs-approval': 'warn',
};

let state = null;
let mcp = null;
let timer = 0;
let lastNotification = 0;

export async function request(method, path = '', body) {
  const res = await fetch(`/api/plugins${path}`, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) {
    const error = new Error(json.error ?? `HTTP ${res.status}`);
    error.status = res.status;
    error.body = json;
    throw error;
  }
  return json;
}

export async function mcpRequest(method, path = '', body) {
  const res = await fetch(`/api/mcp${path}`, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`);
  return json;
}

export function statusChip(status, label) {
  const chip = el('span', `plugin-status label-medium tone-${STATUS_TONE[status] ?? 'idle'}`);
  chip.append(el('span', 'plugin-dot'), label);
  return chip;
}

export function pluginState() {
  return state;
}

function card(p) {
  const node = el('article', 'plugin-card state-layer');
  node.tabIndex = 0;
  node.setAttribute('role', 'button');
  const icon = el('span', 'plugin-icon');
  if (p.hasIcon) {
    const img = el('img');
    img.src = `/api/plugins/${p.id}/icon`;
    img.alt = '';
    icon.append(img);
  } else {
    icon.append(createIcon('extension'));
  }
  const head = el('div', 'plugin-card-head');
  head.append(el('span', 'title-medium plugin-name', p.name));
  if (p.version) head.append(el('span', 'label-small plugin-version', `v${p.version}`));
  if (p.origin === 'example') head.append(el('span', 'label-small plugin-tag', '示例'));
  const text = el('div', 'plugin-card-text');
  text.append(head);
  if (p.author) text.append(el('div', 'label-small plugin-meta', p.author));
  const desc = el('p', 'body-small plugin-desc', p.description || '（没有说明）');
  const foot = el('div', 'plugin-card-foot');
  const left = el('div', 'plugin-card-status');
  left.append(statusChip(p.status, p.statusLabel));
  const meta = [];
  if (p.commands) meta.push(`${p.commands} 个命令`);
  if (p.tools) meta.push(`${p.tools} 个工具`);
  if (meta.length) left.append(el('span', 'label-small plugin-meta', meta.join(' · ')));
  if (!p.sandbox) left.append(el('span', 'label-small plugin-tag plugin-tag-danger', '没开沙盒'));
  foot.append(left);
  const toggle = el('button', 'switch', '');
  toggle.type = 'button';
  toggle.setAttribute('role', 'switch');
  toggle.setAttribute('aria-checked', String(p.enabled));
  toggle.setAttribute('aria-label', p.enabled ? '停用' : '启用');
  toggle.append(el('span', 'switch-handle'));
  toggle.disabled = p.status === 'invalid' || p.status === 'incompatible';
  toggle.addEventListener('click', e => {
    e.stopPropagation();
    toggleEnabled(p);
  });
  foot.append(toggle);
  node.append(icon, text, desc, foot);
  if (p.problems?.length) node.append(el('div', 'body-small plugin-problem', p.problems.join('；')));
  else if (p.lastError) node.append(el('div', 'body-small plugin-problem', p.lastError.split('\n')[0]));
  else if (p.warnings?.length) node.append(el('div', 'body-small plugin-warning', p.warnings.join('；')));
  const open = () => openPluginDetail(p.id);
  node.addEventListener('click', open);
  node.addEventListener('keydown', e => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      open();
    }
  });
  return node;
}

export async function toggleEnabled(p) {
  try {
    if (p.enabled) {
      await request('POST', `/${p.id}/disable`, {});
    } else {
      await enableWithApproval(p.id);
    }
    await load();
  } catch (err) {
    if (err.message !== 'cancelled') showSnackbar(err.message, 'error');
  }
}

export async function enableWithApproval(id) {
  try {
    return await request('POST', `/${id}/enable`, {});
  } catch (err) {
    if (err.status !== 409) throw err;
    const missing = err.body?.missing ?? [];
    const list = el('ul', 'permission-list');
    missing.forEach(p => {
      const item = el('li');
      item.append(el('span', 'title-small', p.label), el('span', 'body-small', p.description));
      list.append(item);
    });
    const ok = await confirmDialog({
      icon: 'shield',
      title: '这个插件要用这些权限',
      text: '只在你信得过这个插件时才同意。',
      node: list,
      confirm: '同意并启用',
    });
    if (!ok) throw new Error('cancelled');
    return await request('POST', `/${id}/enable`, { approve: missing.map(p => p.key) });
  }
}

function render() {
  if (!state) return;
  $('#plugin-dir').textContent = state.pluginsDir;
  const prefix = $('#plugin-prefix');
  if (document.activeElement !== prefix) prefix.value = state.prefix;
  $('#plugin-prefix-example').textContent = `${state.prefix}今日人品`;
  $('#plugin-dev').setAttribute('aria-checked', String(state.devMode));
  $('#plugin-dir-path').textContent = state.pluginsDir;
  $('#plugin-host-version').textContent = `${state.hostVersion}（插件清单里的 host 字段对照这个版本）`;
  const list = $('#plugin-list');
  list.replaceChildren(...state.plugins.map(card));
  $('#plugin-empty').hidden = state.plugins.length > 0;
  const broken = state.plugins.filter(p => p.status === 'crashed' || p.status === 'invalid' || p.status === 'incompatible').length;
  const badge = $('#plugins-badge');
  badge.hidden = !broken;
  badge.textContent = String(broken);
}

function renderMcp() {
  if (!mcp) return;
  const list = $('#mcp-list');
  list.replaceChildren(...mcp.servers.map(s => mcpCard(s, loadMcp)));
  $('#mcp-empty').hidden = mcp.servers.length > 0;
}

export async function load() {
  try {
    state = await request('GET');
    render();
  } catch (err) {
    showSnackbar(`读取插件失败：${err.message}`, 'error');
  }
}

export async function loadMcp() {
  try {
    mcp = await mcpRequest('GET');
    renderMcp();
  } catch (err) {
    showSnackbar(`读取 MCP 服务失败：${err.message}`, 'error');
  }
}

async function notifications() {
  try {
    const list = await request('GET', `/notifications?after=${lastNotification}`);
    for (const n of list) {
      lastNotification = Math.max(lastNotification, n.id);
      showSnackbar(`${n.name}：${n.text}`, 'info');
    }
  } catch {
  }
}

export function initPlugins() {
  const tabs = initTabBar($('#plugin-tabs'), tab => {
    const to = $(`.plugin-view[data-view="${tab}"]`);
    if (!to) return;
    const views = $$('.plugin-view');
    const from = views.find(view => !view.hidden);
    tabs.select(tab);
    const swap = () => {
      for (const view of views) view.hidden = view !== to;
    };
    if (!from || from === to) swap();
    else viewTransition(views.indexOf(to) > views.indexOf(from) ? 'axis-forward' : 'axis-backward', swap, { from, to });
    if (tab === 'mcp') loadMcp();
  });
  onPageChange(page => {
    if (page === 'plugins') tabs.move();
  });
  onPageChange(page => {
    clearInterval(timer);
    if (page !== 'plugins') return;
    load();
    loadMcp();
    timer = setInterval(() => {
      load();
      if (!$('.plugin-view[data-view="mcp"]').hidden) loadMcp();
    }, 5000);
  });
  setInterval(notifications, 5000);
  $('#plugin-refresh').addEventListener('click', async () => {
    try {
      state = await request('POST', '/refresh', {});
      render();
      showSnackbar('重新扫描完成', 'success');
    } catch (err) {
      showSnackbar(err.message, 'error');
    }
  });
  $('#plugin-install').addEventListener('click', () => openInstallDialog(load));
  $('#mcp-add').addEventListener('click', () => openMcpDialog(null, loadMcp));
  const prefix = $('#plugin-prefix');
  prefix.addEventListener('change', async () => {
    try {
      state = await request('PUT', '/config', { prefix: prefix.value });
      render();
      showSnackbar(`命令前缀改成了 ${state.prefix}`, 'success');
    } catch (err) {
      showSnackbar(err.message, 'error');
      render();
    }
  });
  $('#plugin-dir-copy').addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(state?.pluginsDir ?? '');
      showSnackbar('路径已复制', 'success');
    } catch {
      showSnackbar('复制失败，手动选中路径复制吧', 'error');
    }
  });
  $('#plugin-dev').addEventListener('click', async () => {
    try {
      state = await request('PUT', '/config', { devMode: !state.devMode });
      render();
    } catch (err) {
      showSnackbar(err.message, 'error');
    }
  });
}
