import { $, $$, el } from './dom.js';
import { createIcon } from './icons.js';
import { showSnackbar } from './snackbar.js';
import { closeDialog, confirmDialog, enhanceDialog, openDialog } from './dialog.js';
import { ago } from './format.js';
import { mcpRequest, statusChip } from './plugins.js';

const STATUS = { connected: ['running', '已连接'], connecting: ['starting', '连接中'], error: ['crashed', '出错了'], stopped: ['stopped', '已停用'] };

let dialog = null;
let editing = null;
let onDone = null;

export function mcpCard(s, reload) {
  const node = el('article', 'plugin-card');
  const icon = el('span', 'plugin-icon');
  icon.append(createIcon('lan'));
  const head = el('div', 'plugin-card-head');
  head.append(el('span', 'title-medium plugin-name', s.name), el('span', 'label-small plugin-tag', s.type === 'http' ? 'HTTP' : 'stdio'));
  const text = el('div', 'plugin-card-text');
  text.append(head, el('p', 'body-small plugin-desc', s.type === 'http' ? s.url : [s.command, ...(s.args ?? [])].join(' ')));
  const meta = [];
  if (s.serverName) meta.push(s.serverName);
  meta.push(`${s.tools.length} 个工具`);
  if (s.resources?.length) meta.push(`${s.resources.length} 个资源`);
  if (s.prompts?.length) meta.push(`${s.prompts.length} 个提示词`);
  if (s.status === 'connected' && s.connectedAt) meta.push(`${ago(s.connectedAt)}连上`);
  if (s.type === 'stdio' && !s.sandbox) meta.push('没开沙盒');
  if (s.qq) meta.push('QQ 里可用');
  text.append(el('div', 'label-small plugin-meta', meta.join(' · ')));
  const foot = el('div', 'plugin-card-foot');
  const [tone, label] = STATUS[s.status] ?? ['stopped', s.status];
  foot.append(statusChip(tone, label));
  const actions = el('div', 'plugin-card-actions');
  actions.append(button('refresh', '重连', async () => {
    await mcpRequest('POST', `/${s.name}/reconnect`, {});
    reload();
  }));
  actions.append(button('edit', '编辑', () => openMcpDialog(s, reload)));
  actions.append(button('delete', '删除', async () => {
    const ok = await confirmDialog({ icon: 'delete', title: `删除 MCP 服务「${s.name}」？`, text: '它提供的工具会一起消失。', confirm: '删除', danger: true });
    if (!ok) return;
    await mcpRequest('DELETE', `/${s.name}`, {});
    reload();
  }));
  const toggle = el('button', 'switch');
  toggle.type = 'button';
  toggle.setAttribute('role', 'switch');
  toggle.setAttribute('aria-checked', String(s.enabled));
  toggle.append(el('span', 'switch-handle'));
  toggle.addEventListener('click', async () => {
    try {
      await mcpRequest('POST', `/${s.name}/${s.enabled ? 'disable' : 'enable'}`, {});
      reload();
    } catch (err) {
      showSnackbar(err.message, 'error');
    }
  });
  actions.append(toggle);
  foot.append(actions);
  node.append(icon, text, foot);
  if (s.error) node.append(el('div', 'body-small plugin-problem', s.error));
  const lists = [
    listing(`${s.tools.length} 个工具`, s.tools.map(t => [t.original, t.description])),
    listing(`${s.resources?.length ?? 0} 个资源`, (s.resources ?? []).map(r => [r.target, [r.name, r.description].filter(Boolean).join('：')])),
    listing(`${s.prompts?.length ?? 0} 个提示词`, (s.prompts ?? []).map(p => [p.name, [p.description, p.target && `参数：${p.target}`].filter(Boolean).join('；')])),
  ].filter(Boolean);
  node.append(...lists);
  return node;
}

function listing(label, rows) {
  if (!rows.length) return null;
  const box = el('details', 'plugin-tools');
  box.append(el('summary', 'label-medium', `查看 ${label}`));
  for (const [code, text] of rows) {
    const row = el('div', 'plugin-row');
    row.append(el('code', 'plugin-code', code), el('span', 'body-small', text));
    box.append(row);
  }
  return box;
}

function button(icon, label, onClick) {
  const b = el('button', 'icon-button state-layer');
  b.type = 'button';
  b.title = b.ariaLabel = label;
  b.append(createIcon(icon));
  b.addEventListener('click', async e => {
    e.stopPropagation();
    try {
      await onClick();
    } catch (err) {
      showSnackbar(err.message, 'error');
    }
  });
  return b;
}

function pairRows(container, pairs) {
  container.replaceChildren();
  (pairs ?? []).forEach(p => container.append(pairRow(p)));
}

function pairRow(p = { key: '', value: '', secret: false }) {
  const row = el('div', 'pair-row');
  const key = el('input', 'body-medium pair-key');
  key.placeholder = '名字';
  key.value = p.key;
  const value = el('input', 'body-medium pair-value');
  value.placeholder = p.secret && p.value === '__keep__' ? '已设置，留空就不改' : '值';
  value.type = p.secret ? 'password' : 'text';
  value.value = p.value === '__keep__' ? '' : p.value;
  value.dataset.keep = p.value === '__keep__' ? '1' : '';
  const secret = el('label', 'pair-secret label-small');
  const box = el('input');
  box.type = 'checkbox';
  box.checked = p.secret;
  box.addEventListener('change', () => {
    value.type = box.checked ? 'password' : 'text';
  });
  secret.append(box, '密钥');
  const remove = el('button', 'icon-button state-layer');
  remove.type = 'button';
  remove.append(createIcon('close'));
  remove.addEventListener('click', () => row.remove());
  row.append(key, value, secret, remove);
  return row;
}

function readPairs(container) {
  return $$('.pair-row', container).map(row => {
    const key = $('.pair-key', row).value.trim();
    const input = $('.pair-value', row);
    const secret = $('.pair-secret input', row).checked;
    const value = secret && input.dataset.keep && !input.value ? '__keep__' : input.value;
    return { key, value, secret };
  }).filter(p => p.key);
}

function setType(type) {
  for (const b of $$('#mcp-type button')) b.setAttribute('aria-checked', String(b.dataset.type === type));
  $('#mcp-stdio').hidden = type !== 'stdio';
  $('#mcp-http').hidden = type !== 'http';
}

function currentType() {
  return $('#mcp-type button[aria-checked="true"]').dataset.type;
}

export async function openMcpDialog(server, done) {
  dialog = document.getElementById('mcp-dialog');
  onDone = done;
  editing = server;
  if (!dialog.dataset.ready) {
    enhanceDialog(dialog);
    dialog.dataset.ready = '1';
    for (const b of $$('#mcp-type button')) b.addEventListener('click', () => setType(b.dataset.type));
    $('#mcp-env-add').addEventListener('click', () => $('#mcp-env').append(pairRow()));
    $('#mcp-header-add').addEventListener('click', () => $('#mcp-headers').append(pairRow()));
    $('#mcp-save').addEventListener('click', save);
    for (const id of ['mcp-sandbox', 'mcp-network', 'mcp-qq', 'mcp-mind']) {
      $(`#${id}`).addEventListener('click', e => {
        const t = e.currentTarget;
        t.setAttribute('aria-checked', String(t.getAttribute('aria-checked') !== 'true'));
      });
    }
  }
  const s = server ?? { name: '', type: 'stdio', command: '', args: [], env: [], url: '', headers: [], sandbox: true, network: false, timeout: 60, qq: false, mind: false };
  $('#mcp-title').textContent = server ? `编辑 MCP 服务「${server.name}」` : '添加 MCP 服务';
  $('#mcp-name').value = s.name;
  $('#mcp-command').value = s.command ?? '';
  $('#mcp-args').value = (s.args ?? []).join('\n');
  $('#mcp-url').value = s.url ?? '';
  $('#mcp-timeout').value = s.timeout ?? 60;
  pairRows($('#mcp-env'), s.env);
  pairRows($('#mcp-headers'), s.headers);
  $('#mcp-sandbox').setAttribute('aria-checked', String(s.sandbox !== false));
  $('#mcp-network').setAttribute('aria-checked', String(Boolean(s.network)));
  $('#mcp-qq').setAttribute('aria-checked', String(Boolean(s.qq)));
  $('#mcp-mind').setAttribute('aria-checked', String(Boolean(s.mind)));
  setType(s.type ?? 'stdio');
  await openDialog(dialog);
}

async function save() {
  const on = id => $(`#${id}`).getAttribute('aria-checked') === 'true';
  const body = {
    name: $('#mcp-name').value.trim(),
    type: currentType(),
    command: $('#mcp-command').value.trim(),
    args: $('#mcp-args').value.split('\n').map(s => s.trim()).filter(Boolean),
    env: readPairs($('#mcp-env')),
    url: $('#mcp-url').value.trim(),
    headers: readPairs($('#mcp-headers')),
    enabled: editing ? editing.enabled : true,
    sandbox: on('mcp-sandbox'),
    network: on('mcp-network'),
    timeout: Number($('#mcp-timeout').value) || 60,
    qq: on('mcp-qq'),
    mind: on('mcp-mind'),
  };
  if (!body.sandbox && currentType() === 'stdio') {
    const ok = await confirmDialog({
      icon: 'warning',
      title: '不开沙盒运行这个服务？',
      text: '不开沙盒时，这个程序能读写你电脑上的任何文件。只对你信得过的服务这么做。',
      confirm: '我明白',
      danger: true,
    });
    if (!ok) return;
  }
  try {
    if (editing) await mcpRequest('PUT', `/${editing.name}`, body);
    else await mcpRequest('POST', '', body);
    showSnackbar('已保存，正在连接', 'success');
    closeDialog(dialog, 'done');
    onDone?.();
  } catch (err) {
    showSnackbar(err.message, 'error');
  }
}
