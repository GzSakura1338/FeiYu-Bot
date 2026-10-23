import { $, $$, el } from './dom.js';
import { createIcon } from './icons.js';
import { showSnackbar } from './snackbar.js';
import { refreshWorkspaceFiles } from './workspace-files.js';

export const TOOLS = {
  read: { icon: 'description', label: '读取', desc: '读取文件内容，或列出目录里的文件' },
  write: { icon: 'note_add', label: '写入', desc: '新建文件，或整体覆盖文件内容' },
  edit: { icon: 'edit_document', label: '编辑', desc: '按原文查找并替换，只改文件的一部分' },
  patch: { icon: 'receipt_long', label: '批量修改', desc: '用补丁一次新建、修改、改名或删除多个文件，全部检查通过才动手' },
  list: { icon: 'folder_open', label: '列目录', desc: '按树形列出目录里的文件夹和文件，整理前先看一眼' },
  glob: { icon: 'manage_search', label: '查找文件', desc: '按文件名模式查找，例如 **/*.txt' },
  grep: { icon: 'search', label: '搜索内容', desc: '在文件内容里搜索文字或正则表达式' },
  move: { icon: 'drive_file_move', label: '移动 / 重命名', desc: '把文件或文件夹挪到别处、改名，用来整理' },
  delete: { icon: 'delete', label: '删除', desc: '把文件挪进工作目录里的 .trash 回收站，可以恢复' },
  todowrite: { icon: 'check', label: '任务清单', desc: '步骤多的活先列清单，做完一项勾一项' },
  websearch: { icon: 'travel_explore', label: '网络检索', desc: '上网搜索新闻、资料、教程和不认识的词，中文走必应、英文走 Exa，需要细节时再打开网页读' },
  webfetch: { icon: 'link', label: '读网页', desc: '打开公网网页转成文字来读；本机和内网地址一律拒绝' },
  shell: { icon: 'terminal', label: 'Shell 命令', desc: '让她在工作目录里跑命令（cmd / python / node），默认关闭' },
  send_file: { icon: 'upload_file', label: '发送文件', desc: '把目录里的文件发给对方，图片直接显示' },
};

export const MIND_TOOLS = {
  look_around: { icon: 'groups', label: '看看各个群' },
  read_group: { icon: 'forum', label: '翻聊天记录' },
  recall: { icon: 'psychology', label: '回想' },
  search: { icon: 'travel_explore', label: '上网搜' },
  stickers: { icon: 'mood', label: '看看表情包' },
  collect_sticker: { icon: 'add_photo_alternate', label: '收藏表情包' },
  say_in_group: { icon: 'send', label: '去群里说' },
  say_private: { icon: 'person', label: '私聊' },
  tell_group: { icon: 'send', label: '交给群里去说' },
  tell_private: { icon: 'person', label: '交给私聊去说' },
  settle: { icon: 'check_circle', label: '了结一件事' },
  find_person: { icon: 'manage_search', label: '找人' },
  poke: { icon: 'mood', label: '拍一拍' },
  remember: { icon: 'note_add', label: '记下来' },
  requests: { icon: 'person', label: '看看申请' },
  answer_friend: { icon: 'person', label: '处理好友申请' },
  answer_group: { icon: 'groups', label: '处理进群' },
};

const ACCESS_HINT = {
  OFF: '只有网页测试对话可以使用工具',
  LIST: '只有下面这些 QQ 号可以让机器人读写文件',
  ALL: '所有能给机器人发消息的人都可以让它读写文件，有风险',
};

const SHELL_ON = { icon: 'terminal', label: '沙盒执行', desc: '开启后她能跑命令，默认都在 Windows 应用容器沙盒里：只能读写工作目录，限时、限内存、限进程数，断网' };
const NETWORK = { icon: 'travel_explore', label: '沙盒联网', desc: '让沙盒里的命令能上公网：下载文件、pip / npm 装包、调接口；本机和局域网仍然连不上' };
const DIRECT = { icon: 'shield', label: '沙盒外执行', desc: '不套沙盒，直接以你的身份在电脑上跑命令，整台机器的文件都能碰。只给信得过的人开' };
const TESTS = {
  sandbox: { body: { network: false }, hint: '在沙盒里跑 ver、cd、python --version，再试着上网（没开联网时应该连不上）' },
  network: { body: { network: true }, hint: '打开联网跑一遍：百度和 PyPI 应该是 200，本机地址应该连不上（000）' },
  direct: { body: { direct: true }, hint: '在沙盒外跑一遍：能看到真实用户名和主目录条目数，公网和本机都应该通' },
};
const testOpen = new Set();
const testResult = new Map();

let state = {
  dir: '', enabled: [], qqAccess: 'OFF', qqUsers: [], shellNetwork: false, shellDirect: false, directUsers: [], tools: [], problem: null,
};

async function request(method, body) {
  const res = await fetch('/api/agent/workspace', {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`);
  return json;
}

const parseUsers = text => text.split(/[\s,，、;；]+/).map(s => s.trim()).filter(Boolean);

function config(patch = {}) {
  return {
    dir: state.dir, enabled: state.enabled, qqAccess: state.qqAccess, qqUsers: state.qqUsers,
    shellNetwork: state.shellNetwork, shellDirect: state.shellDirect, directUsers: state.directUsers, ...patch,
  };
}

async function save(patch, success) {
  try {
    state = await request('PUT', config(patch));
    showSnackbar(success, 'success');
    render({ dir: 'dir' in patch, users: 'qqUsers' in patch || 'directUsers' in patch });
    if ('dir' in patch) refreshWorkspaceFiles();
  } catch (err) {
    showSnackbar(`保存失败：${err.message}`, 'error');
    render({ dir: false, users: false });
  }
}

function renderStatus() {
  const box = $('#workspace-status');
  box.replaceChildren();
  box.dataset.tone = state.problem ? 'error' : state.dir ? 'success' : 'idle';
  if (state.problem) {
    box.append(createIcon('error', 'icon-18'), el('span', null, `目录不可用，文件工具已暂停：${state.problem}`));
  } else if (state.dir) {
    box.append(createIcon('check_circle', 'icon-18'), el('span', null, `已启用：${state.dir}`));
  } else {
    box.append(createIcon('info', 'icon-18'), el('span', null, '未设置，智能体只能普通聊天'));
  }
}

function renderTools() {
  const list = $('#workspace-tools');
  const extra = Object.fromEntries((state.extraTools ?? []).map(t => [t.name, {
    icon: t.source.startsWith('mcp:') ? 'lan' : 'extension',
    label: t.label,
    desc: `${t.description}${t.qq ? '' : '（QQ 里没开，在插件页打开）'}`,
  }]));
  list.replaceChildren(...state.tools.filter(name => name !== 'shell').map(name => {
    const meta = TOOLS[name] ?? extra[name] ?? { icon: 'build', label: name, desc: '' };
    const on = state.enabled.includes(name);
    return toolItem(meta, name, on, () => {
      const enabled = on ? state.enabled.filter(n => n !== name) : [...state.enabled, name];
      save({ enabled }, `已${on ? '关闭' : '开启'}「${meta.label}」`);
    });
  }));
  renderShell();
}

function renderShell() {
  const list = $('#workspace-shell');
  if (!state.tools.includes('shell')) {
    list.replaceChildren();
    $('#shell-direct-form').hidden = true;
    $('#shell-direct-warning').hidden = true;
    return;
  }
  const on = state.enabled.includes('shell');
  const rows = testable(toolItem(SHELL_ON, 'shell', on, () => {
    const enabled = on ? state.enabled.filter(n => n !== 'shell') : [...state.enabled, 'shell'];
    save({ enabled }, `已${on ? '关闭' : '开启'}「${SHELL_ON.label}」`);
  }), 'sandbox', !on);
  if (on) {
    const online = state.shellNetwork;
    rows.push(...testable(sub(toolItem(NETWORK, null, online, () => {
      save({ shellNetwork: !online }, `已${online ? '关闭' : '开启'}「${NETWORK.label}」`);
    })), 'network', !online));
    const direct = state.shellDirect;
    rows.push(...testable(sub(toolItem(DIRECT, null, direct, () => {
      save({ shellDirect: !direct }, direct ? '已关闭「沙盒外执行」' : '已开启「沙盒外执行」，只有主人和名单里的人能用');
    })), 'direct', !direct));
  }
  list.replaceChildren(...rows);
  const warn = $('#shell-direct-warning');
  warn.hidden = !(on && state.shellDirect);
  if (!warn.hidden) {
    warn.replaceChildren(createIcon('warning', 'icon-18'),
      el('span', null, '沙盒外执行开着：主人和下面名单里的人，可以让她在你电脑上直接跑命令，碰到整台机器的文件'));
  }
  $('#shell-direct-form').hidden = !(on && state.shellDirect);
}

function sub(item) {
  item.classList.add('tool-sub');
  return item;
}

function testable(item, kind, disabled = false) {
  const panel = el('li', `tool-test${testOpen.has(kind) ? ' open' : ''}`);
  panel.inert = !testOpen.has(kind);
  const inner = el('div', 'tool-test-inner');
  const run = el('button', 'tonal-button label-large state-layer', '运行测试');
  run.type = 'button';
  run.disabled = disabled;
  const output = el('pre', 'tool-test-output body-small', testResult.get(kind) ?? TESTS[kind].hint);
  run.addEventListener('click', async () => {
    run.disabled = true;
    output.textContent = '测试中…';
    try {
      const res = await fetch('/api/agent/workspace/test', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(TESTS[kind].body),
      });
      const data = await res.json();
      output.textContent = data.output ?? data.error ?? `HTTP ${res.status}`;
    } catch (err) {
      output.textContent = `测试失败：${err.message}`;
    }
    testResult.set(kind, output.textContent);
    run.disabled = disabled;
  });
  inner.append(run, output);
  panel.append(inner);
  const expand = el('button', 'icon-button state-layer tool-test-toggle');
  expand.type = 'button';
  expand.title = expand.ariaLabel = '展开测试';
  expand.setAttribute('aria-expanded', String(testOpen.has(kind)));
  expand.append(createIcon('chevron_right'));
  expand.addEventListener('click', () => {
    const open = !testOpen.has(kind);
    if (open) testOpen.add(kind);
    else testOpen.delete(kind);
    expand.setAttribute('aria-expanded', String(open));
    panel.classList.toggle('open', open);
    panel.inert = !open;
  });
  const toggle = item.querySelector('.switch');
  if (toggle) toggle.before(expand);
  else item.append(expand);
  return [item, panel];
}

function toolItem(meta, name, on, onToggle) {
  const item = el('li', 'list-item');
  const icon = el('span', 'list-icon');
  icon.append(createIcon(meta.icon));
  const text = el('div', 'list-text');
  const headline = el('div', 'list-headline body-large');
  headline.append(el('span', null, meta.label));
  if (name) headline.append(el('code', 'tool-name label-small', name));
  text.append(headline, el('div', 'list-supporting body-medium', meta.desc));
  item.append(icon, text);
  if (!onToggle) return item;
  const toggle = el('button', 'switch');
  toggle.type = 'button';
  toggle.setAttribute('role', 'switch');
  toggle.setAttribute('aria-checked', String(on));
  toggle.ariaLabel = meta.label;
  toggle.append(el('span', 'switch-handle'));
  toggle.addEventListener('click', () => {
    toggle.setAttribute('aria-checked', String(!on));
    onToggle();
  });
  item.append(toggle);
  return item;
}

function renderAccess() {
  for (const button of $$('#workspace-access button')) {
    button.setAttribute('aria-checked', String(button.dataset.access === state.qqAccess));
  }
  $('#workspace-access-hint').textContent = ACCESS_HINT[state.qqAccess] ?? '';
  $('#workspace-users-form').hidden = state.qqAccess !== 'LIST';
}

function syncDirty() {
  $('#workspace-dir-save').disabled = $('#workspace-dir').value.trim() === state.dir;
  $('#workspace-users-save').disabled = parseUsers($('#workspace-users').value).join(',') === state.qqUsers.join(',');
  $('#shell-direct-save').disabled = parseUsers($('#shell-direct-users').value).join(',') === (state.directUsers ?? []).join(',');
}

function render(reset = { dir: true, users: true }) {
  if (reset.dir) $('#workspace-dir').value = state.dir;
  if (reset.users) $('#workspace-users').value = state.qqUsers.join(', ');
  if (reset.users) $('#shell-direct-users').value = (state.directUsers ?? []).join(', ');
  renderStatus();
  renderTools();
  renderAccess();
  syncDirty();
}

async function load() {
  try {
    state = await request('GET');
  } catch (err) {
    showSnackbar(`读取工作目录设置失败：${err.message}`, 'error');
  }
  render();
}

export function initAgentWorkspace() {
  for (const hint of $$('.workspace-row .supporting')) hint.textContent = hint.dataset.hint;
  $('#workspace-dir-form').addEventListener('input', syncDirty);
  $('#workspace-users-form').addEventListener('input', syncDirty);
  $('#workspace-dir-form').addEventListener('submit', async e => {
    e.preventDefault();
    const dir = $('#workspace-dir').value.trim();
    await save({ dir }, dir ? '已设置工作目录' : '已关闭文件工具');
  });
  $('#workspace-users-form').addEventListener('submit', async e => {
    e.preventDefault();
    await save({ qqUsers: parseUsers($('#workspace-users').value) }, '已保存允许的 QQ 号');
  });
  $('#shell-direct-form').addEventListener('input', syncDirty);
  $('#shell-direct-form').addEventListener('submit', async e => {
    e.preventDefault();
    await save({ directUsers: parseUsers($('#shell-direct-users').value) }, '已保存可以跑沙盒外命令的 QQ 号');
  });
  for (const button of $$('#workspace-access button')) {
    button.addEventListener('click', () => {
      if (button.dataset.access === state.qqAccess) return;
      save({ qqAccess: button.dataset.access }, '已更新 QQ 使用权限');
    });
  }
  load();
}
