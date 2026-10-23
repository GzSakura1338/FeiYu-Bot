import { $, el } from './dom.js';
import { createIcon } from './icons.js';
import { showSnackbar } from './snackbar.js';
import { confirmDialog, enhanceDialog, openDialog, closeDialog } from './dialog.js';
import { ago, formatTime } from './format.js';
import { buildSettingsForm } from './plugin-settings.js';
import { enableWithApproval, request, statusChip, load, pluginState } from './plugins.js';

const EVENTS = {
  'message.group': '群消息',
  'message.private': '私聊消息',
  'notice.join': '有人进群',
  'notice.leave': '有人退群',
  'notice.recall': '撤回',
  'notice.poke': '拍一拍',
  'bot.said': '她说的话',
  'bot.thought': '她的念头',
  'bot.connected': 'QQ 连上',
  'bot.disconnected': 'QQ 断开',
};

let dialog = null;
let current = null;
let tab = 'overview';
let logTimer = 0;
let lastLog = 0;

function group(title, ...rows) {
  const box = el('section', 'plugin-group');
  if (title) box.append(el('h3', 'label-large plugin-group-title', title));
  const list = el('div', 'plugin-list');
  list.append(...rows);
  box.append(list);
  return box;
}

function item({ icon, title, code, text, trailing }) {
  const row = el('div', 'plugin-item');
  if (icon) {
    const lead = el('span', 'plugin-item-icon');
    lead.append(createIcon(icon, 'icon-18'));
    row.append(lead);
  }
  const body = el('div', 'plugin-item-text');
  const head = el('div', 'plugin-item-head');
  if (code) head.append(el('code', 'plugin-code', code));
  if (title) head.append(el('span', 'body-large', title));
  if (code && text) head.append(el('span', 'body-medium plugin-item-note', text));
  body.append(head);
  if (text && !code) body.append(el('div', 'body-medium plugin-item-sub', text));
  row.append(body);
  if (trailing) row.append(...[trailing].flat());
  return row;
}

function tag(text, tone = '') {
  return el('span', `label-small plugin-tag ${tone}`, text);
}

function markdown(text) {
  const box = el('div', 'plugin-readme body-medium');
  for (const block of text.split(/\n{2,}/)) {
    const line = block.trim();
    if (!line) continue;
    if (line.startsWith('### ')) box.append(el('h4', 'title-small', line.slice(4)));
    else if (line.startsWith('## ')) box.append(el('h4', 'title-small', line.slice(3)));
    else if (line.startsWith('# ')) box.append(el('h3', 'title-medium', line.slice(2)));
    else if (/^[-*] /m.test(line)) {
      const list = el('ul');
      line.split('\n').forEach(entry => {
        const t = entry.replace(/^[-*] /, '').trim();
        if (t) list.append(el('li', '', t));
      });
      box.append(list);
    } else {
      box.append(el('p', '', line));
    }
  }
  return box;
}

function prefix() {
  return pluginState()?.prefix ?? '#';
}

function overview(detail) {
  const parts = [];
  if (detail.lastError) parts.push(el('pre', 'plugin-error body-small', detail.lastError));
  if (detail.problems?.length) parts.push(el('div', 'plugin-problem body-small', detail.problems.join('；')));
  if (detail.warnings?.length) parts.push(el('div', 'plugin-warning body-small', detail.warnings.join('；')));
  if (detail.description) parts.push(el('p', 'body-large plugin-lead', detail.description));
  if (detail.commandList?.length) {
    parts.push(group('命令', ...detail.commandList.map(c => item({
      code: [c.name, ...(c.aliases ?? [])].map(n => `${prefix()}${n}`).join('  '),
      text: c.description,
      trailing: c.scope?.length === 1 ? tag(c.scope[0] === 'group' ? '只在群里' : '只在私聊') : null,
    }))));
  }
  if (detail.toolList?.length) {
    parts.push(group('给她用的工具', ...detail.toolList.map(t => item({
      code: t.name,
      text: t.description,
      trailing: t.mind ? tag('内心也能用') : null,
    }))));
  }
  if (detail.schedules?.length) {
    parts.push(group('定时任务', ...detail.schedules.map(s => {
      const run = el('button', 'text-button label-large state-layer', '立刻执行');
      run.type = 'button';
      run.addEventListener('click', async () => {
        try {
          await request('POST', `/${detail.id}/schedules/${encodeURIComponent(s.id)}/run`, {});
          showSnackbar('已执行一次', 'success');
        } catch (err) {
          showSnackbar(err.message, 'error');
        }
      });
      return item({
        code: s.cron,
        text: [s.description || s.id, s.next ? `下次 ${s.next}` : ''].filter(Boolean).join(' · '),
        trailing: run,
      });
    })));
  }
  const hooks = (detail.events ?? []).map(e => EVENTS[e] ?? e);
  if (detail.intercept) hooks.push('拦截收到的消息');
  if (detail.prompt) hooks.push('补充提示词');
  if (detail.filter) hooks.push('过滤她发的话');
  if (hooks.length) {
    const chips = el('div', 'plugin-chips');
    hooks.forEach(h => chips.append(el('span', 'plugin-chip label-medium', h)));
    const row = el('div', 'plugin-item');
    row.append(chips);
    parts.push(group('会收到', row));
  }
  const facts = [];
  if (detail.status === 'running' && detail.runningSince) facts.push(`已运行 ${ago(detail.runningSince)}，启动用时 ${detail.startupMillis} 毫秒`);
  facts.push(detail.folder);
  parts.push(el('div', 'body-small plugin-detail-foot', facts.join(' · ')));
  return parts;
}

function settings(detail) {
  const parts = [];
  if (Object.keys(detail.settingsSchema?.properties ?? {}).length) {
    parts.push(el('h3', 'label-large plugin-group-title', '插件设置'), buildSettingsForm(detail, async values => {
      await request('PUT', `/${detail.id}/settings`, { values });
      showSnackbar('设置已保存', 'success');
      refresh();
    }));
  }
  parts.push(group('运行', ...options(detail)));
  return parts;
}

function options(detail) {
  const rows = [];
  if ((detail.permissions ?? []).some(p => p.key === 'qq.send')) {
    const picker = el('div', 'group-picker');
    const groups = pluginState()?.groups ?? [];
    const count = () => `只有勾上的群，这个插件才能主动发消息和读记录 · 已勾选 ${(detail.groups ?? []).length} 个`;
    groups.forEach(g => {
      const chip = el('button', 'filter-chip label-medium state-layer', g.name || g.groupId);
      chip.type = 'button';
      chip.title = `${g.name || ''} ${g.groupId}`.trim();
      const on = () => (detail.groups ?? []).includes(g.groupId);
      chip.setAttribute('aria-pressed', String(on()));
      chip.addEventListener('click', async () => {
        const next = on() ? detail.groups.filter(x => x !== g.groupId) : [...(detail.groups ?? []), g.groupId];
        try {
          const updated = await request('PUT', `/${detail.id}/options`, { groups: next });
          detail.groups = updated.groups;
          chip.setAttribute('aria-pressed', String(on()));
          row.querySelector('.plugin-item-sub').textContent = count();
        } catch (err) {
          showSnackbar(err.message, 'error');
        }
      });
      picker.append(chip);
    });
    if (!groups.length) picker.append(el('span', 'body-small plugin-meta', '还没读到群列表，连上 QQ 后再来'));
    const row = item({ icon: 'groups', title: '允许发消息的群', text: count() });
    row.classList.add('plugin-item-block');
    row.append(picker);
    rows.push(row);
  }
  if (detail.toolList?.length) {
    rows.push(switchItem('forum', 'QQ 聊天里也能用工具', '默认只在 Agent 里用，打开后她在 QQ 里聊天时也能调用', detail.qqTools, async value => {
      await request('PUT', `/${detail.id}/options`, { qqTools: value });
    }));
  }
  rows.push(switchItem('shield', '沙盒', detail.sandbox
    ? '只能读写自己的文件夹，默认断网'
    : '已关闭：它能读写你电脑上的文件、随意联网', detail.sandbox, async value => {
    if (!value) {
      const ok = await confirmDialog({
        icon: 'warning',
        title: '真的要关掉沙盒吗？',
        text: '关掉之后，这个插件就能读写你电脑上的任何文件、随便联网。只有你自己写的、完全信得过的插件才这么做。',
        confirm: '我明白，关掉',
        danger: true,
      });
      if (!ok) throw new Error('cancelled');
    }
    await request('PUT', `/${detail.id}/options`, { sandbox: value, confirm: true });
  }, !detail.sandbox));
  return rows;
}

function switchItem(icon, title, text, value, onChange, danger = false) {
  const toggle = el('button', 'switch');
  toggle.type = 'button';
  toggle.setAttribute('role', 'switch');
  toggle.setAttribute('aria-checked', String(value));
  toggle.setAttribute('aria-label', title);
  toggle.append(el('span', 'switch-handle'));
  toggle.addEventListener('click', async () => {
    const next = toggle.getAttribute('aria-checked') !== 'true';
    try {
      await onChange(next);
      toggle.setAttribute('aria-checked', String(next));
      refresh();
    } catch (err) {
      if (err.message !== 'cancelled') showSnackbar(err.message, 'error');
    }
  });
  const row = item({ icon, title, text, trailing: toggle });
  if (danger) row.classList.add('plugin-item-danger');
  return row;
}

function permissions(detail) {
  const list = detail.permissions ?? [];
  if (!list.length) return [group(null, item({ icon: 'check_circle', title: '这个插件不需要任何权限' }))];
  return [group(null, ...list.map(p => item({
    icon: p.granted ? 'check_circle' : 'shield',
    title: p.label,
    text: p.description,
    trailing: tag(p.granted ? '已同意' : '未同意', p.granted ? 'plugin-tag-ok' : 'plugin-tag-warn'),
  })))];
}

function logs(detail) {
  const box = el('div', 'plugin-logs');
  lastLog = 0;
  pollLogs(detail.id, box);
  logTimer = setInterval(() => pollLogs(detail.id, box), 2000);
  return [box];
}

async function pollLogs(id, box) {
  try {
    const list = await request('GET', `/${id}/logs?after=${lastLog}&limit=200`);
    if (!list.length) {
      if (!box.children.length) box.append(el('div', 'body-small plugin-meta plugin-logs-empty', '还没有日志'));
      return;
    }
    box.querySelector('.plugin-logs-empty')?.remove();
    const atBottom = box.scrollTop + box.clientHeight >= box.scrollHeight - 40;
    for (const entry of list) {
      lastLog = Math.max(lastLog, entry.id);
      const row = el('div', `plugin-log-line level-${entry.level}`);
      row.append(el('span', 'label-small plugin-log-time', formatTime(entry.at)),
        el('span', 'body-small plugin-log-text', entry.text));
      box.append(row);
    }
    while (box.children.length > 400) box.firstElementChild.remove();
    if (atBottom) box.scrollTop = box.scrollHeight;
  } catch {
  }
}

function tabsFor(detail) {
  const tabs = [['overview', '概览'], ['settings', '设置'], ['permissions', '权限'], ['logs', '日志']];
  if (detail.readme) tabs.push(['readme', '说明']);
  return tabs;
}

function renderTab(detail) {
  clearInterval(logTimer);
  const body = $('#plugin-detail-body');
  const parts = tab === 'settings' ? settings(detail)
    : tab === 'permissions' ? permissions(detail)
    : tab === 'logs' ? logs(detail)
    : tab === 'readme' ? [markdown(detail.readme)]
    : overview(detail);
  body.dataset.tab = tab;
  body.replaceChildren(...parts);
}

async function refresh() {
  if (!current) return;
  try {
    const detail = await request('GET', `/${current}`);
    render(detail);
  } catch (err) {
    showSnackbar(err.message, 'error');
  }
}

function render(detail) {
  $('#plugin-detail-title').textContent = detail.name;
  $('#plugin-detail-sub').textContent = [detail.version ? `v${detail.version}` : '', detail.author].filter(Boolean).join(' · ');
  $('#plugin-detail-status').replaceChildren(statusChip(detail.status, detail.statusLabel));
  $('#plugin-detail-icon').replaceChildren(detail.hasIcon ? iconImage(detail.id) : createIcon('extension'));

  const available = tabsFor(detail);
  if (!available.some(([key]) => key === tab)) tab = 'overview';
  const bar = $('#plugin-detail-tabs');
  bar.replaceChildren(...available.map(([key, label]) => {
    const button = el('button', 'state-layer', label);
    button.type = 'button';
    button.setAttribute('role', 'tab');
    button.setAttribute('aria-checked', String(key === tab));
    button.addEventListener('click', () => {
      if (tab === key) return;
      tab = key;
      bar.querySelectorAll('button').forEach(b => b.setAttribute('aria-checked', String(b === button)));
      renderTab(detail);
    });
    return button;
  }));
  if (tab !== 'logs' || !$('#plugin-detail-body .plugin-logs')) renderTab(detail);

  const toggle = $('#plugin-detail-toggle');
  toggle.setAttribute('aria-checked', String(detail.enabled));
  toggle.setAttribute('aria-label', detail.enabled ? '停用' : '启用');
  toggle.title = detail.enabled ? '停用' : '启用';
  toggle.disabled = detail.status === 'invalid' || detail.status === 'incompatible';
  toggle.onclick = async () => {
    try {
      if (detail.enabled) await request('POST', `/${detail.id}/disable`, {});
      else await enableWithApproval(detail.id);
      refresh();
      load();
    } catch (err) {
      if (err.message !== 'cancelled') showSnackbar(err.message, 'error');
    }
  };
  $('#plugin-detail-reload').onclick = async () => {
    try {
      await request('POST', `/${detail.id}/reload`, {});
      showSnackbar('已重新加载', 'success');
      refresh();
      load();
    } catch (err) {
      showSnackbar(err.message, 'error');
    }
  };
  const uninstall = $('#plugin-detail-uninstall');
  uninstall.hidden = detail.origin !== 'installed';
  uninstall.onclick = async () => {
    const check = el('label', 'confirm-check body-medium');
    const box = el('input');
    box.type = 'checkbox';
    check.append(box, '同时删掉它存的数据和设置');
    const ok = await confirmDialog({
      icon: 'delete',
      title: `卸载「${detail.name}」？`,
      text: '会停止并删除这个插件的文件。不勾选下面这项的话，数据和设置会留着，以后重装还能接着用。',
      node: check,
      confirm: '卸载',
      danger: true,
    });
    if (!ok) return;
    try {
      await request('DELETE', `/${detail.id}?data=${box.checked}`, {});
      closeDialog(dialog, 'done');
      showSnackbar('已卸载', 'success');
      load();
    } catch (err) {
      showSnackbar(err.message, 'error');
    }
  };
  const page = $('#plugin-detail-page');
  page.hidden = !detail.hasWeb;
  page.href = `/plugins/${detail.id}/`;
}

function iconImage(id) {
  const img = el('img');
  img.src = `/api/plugins/${id}/icon`;
  img.alt = '';
  return img;
}

export async function openPluginDetail(id) {
  dialog = document.getElementById('plugin-detail-dialog');
  if (!dialog.dataset.ready) {
    enhanceDialog(dialog);
    dialog.dataset.ready = '1';
  }
  current = id;
  tab = 'overview';
  await refresh();
  await openDialog(dialog);
  clearInterval(logTimer);
  current = null;
  load();
}
