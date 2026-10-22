import { $, el } from './dom.js';
import { createIcon } from './icons.js';
import { showSnackbar } from './snackbar.js';
import { confirmDialog, enhanceDialog, openDialog, closeDialog } from './dialog.js';
import { ago, formatTime } from './format.js';
import { buildSettingsForm } from './plugin-settings.js';
import { enableWithApproval, request, statusChip, load, pluginState } from './plugins.js';

let dialog = null;
let current = null;
let logTimer = 0;
let lastLog = 0;

function section(title, body) {
  const box = el('section', 'plugin-section');
  box.append(el('h3', 'title-small', title), body);
  return box;
}

function chips(items, className = 'plugin-chip') {
  const row = el('div', 'plugin-chips');
  items.forEach(t => row.append(el('span', `${className} label-small`, t)));
  return row;
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
      line.split('\n').forEach(item => {
        const t = item.replace(/^[-*] /, '').trim();
        if (t) list.append(el('li', '', t));
      });
      box.append(list);
    } else {
      box.append(el('p', '', line));
    }
  }
  return box;
}

function permissions(detail) {
  const list = el('ul', 'permission-list');
  (detail.permissions ?? []).forEach(p => {
    const item = el('li');
    const head = el('div', 'permission-head');
    head.append(el('span', 'title-small', p.label));
    head.append(el('span', `label-small permission-state ${p.granted ? 'ok' : 'warn'}`, p.granted ? '已同意' : '未同意'));
    item.append(head, el('div', 'body-small', p.description));
    list.append(item);
  });
  if (!list.children.length) list.append(el('li', 'body-small', '这个插件不需要任何权限'));
  return list;
}

function commands(detail) {
  const list = el('div', 'plugin-rows');
  (detail.commandList ?? []).forEach(c => {
    const row = el('div', 'plugin-row');
    const names = [c.name, ...(c.aliases ?? [])].map(n => `${pluginPrefix()}${n}`).join('、');
    row.append(el('code', 'plugin-code', names), el('span', 'body-small', c.description || ''));
    if (c.scope?.length === 1) row.append(el('span', 'label-small plugin-tag', c.scope[0] === 'group' ? '只在群里' : '只在私聊'));
    list.append(row);
  });
  return list;
}

function pluginPrefix() {
  return $('#plugin-prefix')?.value || '#';
}

function tools(detail) {
  const list = el('div', 'plugin-rows');
  (detail.toolList ?? []).forEach(t => {
    const row = el('div', 'plugin-row');
    row.append(el('code', 'plugin-code', t.name), el('span', 'body-small', t.description));
    if (t.mind) row.append(el('span', 'label-small plugin-tag', '内心也能用'));
    list.append(row);
  });
  return list;
}

function schedules(detail) {
  const list = el('div', 'plugin-rows');
  (detail.schedules ?? []).forEach(s => {
    const row = el('div', 'plugin-row');
    row.append(el('code', 'plugin-code', s.cron), el('span', 'body-small', s.description || s.id));
    if (s.next) row.append(el('span', 'label-small plugin-next', `下次 ${s.next}`));
    const run = el('button', 'text-button label-medium state-layer', '立刻执行');
    run.type = 'button';
    run.addEventListener('click', async () => {
      try {
        await request('POST', `/${detail.id}/schedules/${encodeURIComponent(s.id)}/run`, {});
        showSnackbar('已执行一次', 'success');
      } catch (err) {
        showSnackbar(err.message, 'error');
      }
    });
    row.append(run);
    list.append(row);
  });
  return list;
}

function options(detail) {
  const box = el('div', 'plugin-options');
  const needsGroups = (detail.permissions ?? []).some(p => p.key === 'qq.send');
  if (needsGroups) {
    const field = el('label', 'field');
    field.append(el('span', 'label-medium', '允许发消息的群'));
    const picker = el('div', 'group-picker');
    const groups = pluginState()?.groups ?? [];
    groups.forEach(g => {
      const chip = el('button', 'filter-chip label-medium state-layer', g.name || g.groupId);
      chip.type = 'button';
      const on = () => (detail.groups ?? []).includes(g.groupId);
      chip.setAttribute('aria-pressed', String(on()));
      chip.addEventListener('click', async () => {
        const next = on() ? detail.groups.filter(x => x !== g.groupId) : [...(detail.groups ?? []), g.groupId];
        try {
          const updated = await request('PUT', `/${detail.id}/options`, { groups: next });
          detail.groups = updated.groups;
          chip.setAttribute('aria-pressed', String(on()));
        } catch (err) {
          showSnackbar(err.message, 'error');
        }
      });
      picker.append(chip);
    });
    if (!groups.length) picker.append(el('span', 'body-small', '还没读到群列表，连上 QQ 后再来'));
    field.append(picker, el('span', 'body-small field-hint', '只有勾上的群，这个插件才能主动发消息和读记录'));
    box.append(field);
  }
  if (detail.toolList?.length) {
    box.append(switchField('QQ 聊天里也能用这些工具', '打开后，她在 QQ 里聊天时也能调用这个插件的工具', detail.qqTools, async value => {
      await request('PUT', `/${detail.id}/options`, { qqTools: value });
    }));
  }
  if ((detail.permissions ?? []).some(p => p.key === 'llm')) {
    const field = el('label', 'field');
    field.append(el('span', 'label-medium', '每天的大模型额度（token）'));
    const input = el('input', 'body-large');
    input.type = 'number';
    input.value = detail.llmBudget;
    input.addEventListener('change', async () => {
      try {
        await request('PUT', `/${detail.id}/options`, { llmBudget: Number(input.value) });
        showSnackbar('额度已保存', 'success');
      } catch (err) {
        showSnackbar(err.message, 'error');
      }
    });
    field.append(input, el('span', 'body-small field-hint', `今天已经用了 ${detail.llmToday} token`));
    box.append(field);
  }
  box.append(switchField('沙盒', detail.sandbox
    ? '插件关在沙盒里：只能读写自己的文件夹，默认断网'
    : '⚠ 这个插件没有关在沙盒里，它能读写你电脑上的文件、随意联网', detail.sandbox, async value => {
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
  }));
  return box;
}

function switchField(title, hint, value, onChange) {
  const field = el('div', 'switch-field');
  const text = el('div');
  text.append(el('div', 'title-small', title), el('div', 'body-small switch-hint', hint));
  const toggle = el('button', 'switch');
  toggle.type = 'button';
  toggle.setAttribute('role', 'switch');
  toggle.setAttribute('aria-checked', String(value));
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
  field.append(text, toggle);
  return field;
}

function logs() {
  return el('div', 'plugin-logs');
}

async function pollLogs(id, box) {
  try {
    const list = await request('GET', `/${id}/logs?after=${lastLog}&limit=200`);
    if (!list.length) return;
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
  const sub = [detail.version ? `v${detail.version}` : '', detail.author, detail.origin === 'example' ? '示例插件' : ''].filter(Boolean);
  $('#plugin-detail-sub').textContent = sub.join(' · ');
  const icon = $('#plugin-detail-icon');
  icon.replaceChildren(detail.hasIcon ? iconImage(detail.id) : createIcon('extension'));
  const body = $('#plugin-detail-body');
  const parts = [];
  const head = el('div', 'plugin-detail-head');
  head.append(statusChip(detail.status, detail.statusLabel));
  if (detail.status === 'running' && detail.runningSince) {
    head.append(el('span', 'label-small plugin-meta', `已运行 ${ago(detail.runningSince)}，启动用时 ${detail.startupMillis} 毫秒`));
  }
  parts.push(head);
  if (detail.lastError) {
    parts.push(el('pre', 'plugin-error body-small', detail.lastError));
  }
  if (detail.problems?.length) parts.push(el('div', 'plugin-problem body-small', detail.problems.join('；')));
  if (detail.warnings?.length) parts.push(el('div', 'plugin-warning body-small', detail.warnings.join('；')));
  if (detail.description) parts.push(el('p', 'body-medium', detail.description));
  if (detail.commandList?.length) parts.push(section('命令', commands(detail)));
  if (detail.toolList?.length) parts.push(section('工具', tools(detail)));
  if (detail.schedules?.length) parts.push(section('定时任务', schedules(detail)));
  if (detail.events?.length || detail.intercept || detail.prompt || detail.filter) {
    const tags = [...(detail.events ?? [])];
    if (detail.intercept) tags.push('拦截消息');
    if (detail.prompt) tags.push('提示词补充');
    if (detail.filter) tags.push('出站过滤');
    parts.push(section('事件', chips(tags)));
  }
  parts.push(section('权限', permissions(detail)));
  if (Object.keys(detail.settingsSchema?.properties ?? {}).length) {
    parts.push(section('设置', buildSettingsForm(detail, async values => {
      await request('PUT', `/${detail.id}/settings`, { values });
      showSnackbar('设置已保存', 'success');
      refresh();
    })));
  }
  parts.push(section('选项', options(detail)));
  if (detail.readme) parts.push(section('说明', markdown(detail.readme)));
  const logBox = logs();
  parts.push(section('日志', logBox));
  const footer = el('div', 'plugin-detail-foot');
  footer.append(el('span', 'label-small plugin-meta', detail.folder));
  parts.push(footer);
  body.replaceChildren(...parts);
  clearInterval(logTimer);
  lastLog = 0;
  pollLogs(detail.id, logBox);
  logTimer = setInterval(() => pollLogs(detail.id, logBox), 2000);

  const toggle = $('#plugin-detail-toggle');
  toggle.textContent = detail.enabled ? '停用' : '启用';
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
    const ok = await confirmDialog({
      icon: 'delete',
      title: `卸载「${detail.name}」？`,
      text: '会停止并删除这个插件的文件。它存的数据和设置也一起删掉。',
      confirm: '卸载',
      danger: true,
    });
    if (!ok) return;
    try {
      await request('DELETE', `/${detail.id}?data=true`, {});
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
  await refresh();
  await openDialog(dialog);
  clearInterval(logTimer);
  current = null;
  load();
}
