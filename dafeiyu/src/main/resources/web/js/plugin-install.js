import { $, $$, el } from './dom.js';
import { showSnackbar } from './snackbar.js';
import { closeDialog, enhanceDialog, openDialog } from './dialog.js';
import { request } from './plugins.js';

let dialog = null;
let staged = null;
let onDone = null;
let source = 'upload';

function setStep(step) {
  $('#install-pick').hidden = step !== 'pick';
  $('#install-preview').hidden = step !== 'preview';
  $('#install-back').hidden = step !== 'preview';
  $('#install-go').hidden = step !== 'pick';
  $('#install-confirm').hidden = step !== 'preview';
  $('#install-only').hidden = step !== 'preview';
}

function setSource(value) {
  source = value;
  for (const b of $$('#install-source button')) b.setAttribute('aria-checked', String(b.dataset.source === value));
  $('#install-upload-field').hidden = value !== 'upload';
  $('#install-url-field').hidden = value === 'upload';
  $('#install-url-hint').textContent = value === 'git'
    ? 'Git 仓库地址，比如 https://github.com/someone/feiyu-weather'
    : 'zip 压缩包的下载地址（GitHub 仓库地址也可以）';
}

function preview(s) {
  const box = $('#install-preview');
  const parts = [];
  const head = el('div', 'install-head');
  head.append(el('span', 'title-large', s.name), el('span', 'label-medium plugin-version', `v${s.version}`));
  parts.push(head);
  if (s.author) parts.push(el('div', 'body-small plugin-meta', `作者：${s.author}`));
  if (s.description) parts.push(el('p', 'body-medium', s.description));
  if (s.installedVersion) {
    parts.push(el('div', `body-small ${s.downgrade ? 'plugin-problem' : 'plugin-warning'}`, s.downgrade
      ? `⚠ 已经装了 v${s.installedVersion}，这次是降级到 v${s.version}`
      : `已经装了 v${s.installedVersion}，会升级到 v${s.version}，设置和数据会保留`));
  }
  const counts = [];
  if (s.commands) counts.push(`${s.commands} 个命令`);
  if (s.tools) counts.push(`${s.tools} 个工具`);
  if (s.schedules) counts.push(`${s.schedules} 个定时任务`);
  if (s.events?.length) counts.push(`订阅 ${s.events.length} 种事件`);
  if (counts.length) parts.push(el('div', 'body-small plugin-meta', counts.join(' · ')));
  parts.push(el('h3', 'title-small', '需要的权限'));
  const list = el('ul', 'permission-list');
  for (const p of s.permissions) {
    const item = el('li');
    const line = el('div', 'permission-head');
    line.append(el('span', 'title-small', p.label));
    if (p.new && s.installedVersion) line.append(el('span', 'label-small permission-state warn', '新增'));
    item.append(line, el('div', 'body-small', p.description));
    list.append(item);
  }
  if (!s.permissions.length) list.append(el('li', 'body-small', '不需要任何权限'));
  parts.push(list);
  parts.push(el('div', 'body-small field-hint', `来源：${s.source}。插件会关在沙盒里运行。只在你信得过它时才安装。`));
  box.replaceChildren(...parts);
}

async function stage() {
  const go = $('#install-go');
  go.disabled = true;
  go.textContent = '读取中…';
  try {
    if (source === 'upload') {
      const file = $('#install-file').files[0];
      if (!file) throw new Error('先选一个 zip 文件');
      const res = await fetch(`/api/plugins/install/upload?name=${encodeURIComponent(file.name)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/zip' },
        body: file,
      });
      const json = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`);
      staged = json;
    } else {
      const url = $('#install-url').value.trim();
      if (!url) throw new Error('先填地址');
      staged = await request('POST', `/install/${source === 'git' ? 'git' : 'url'}`, { url });
    }
    preview(staged);
    setStep('preview');
  } catch (err) {
    showSnackbar(err.message, 'error');
  } finally {
    go.disabled = false;
    go.textContent = '下一步';
  }
}

async function confirm(enable) {
  try {
    const approve = staged.permissions.map(p => p.key);
    await request('POST', `/install/${staged.token}/confirm`, { approve, enable });
    showSnackbar(`「${staged.name}」装好了${enable ? '，已经启用' : ''}`, 'success');
    staged = null;
    closeDialog(dialog, 'done');
    onDone?.();
  } catch (err) {
    showSnackbar(err.message, 'error');
  }
}

export async function openInstallDialog(done) {
  dialog = document.getElementById('plugin-install-dialog');
  onDone = done;
  if (!dialog.dataset.ready) {
    enhanceDialog(dialog);
    dialog.dataset.ready = '1';
    for (const b of $$('#install-source button')) b.addEventListener('click', () => setSource(b.dataset.source));
    $('#install-go').addEventListener('click', stage);
    $('#install-back').addEventListener('click', () => {
      if (staged) request('DELETE', `/install/${staged.token}`, {}).catch(() => {});
      staged = null;
      setStep('pick');
    });
    $('#install-confirm').addEventListener('click', () => confirm(true));
    $('#install-only').addEventListener('click', () => confirm(false));
  }
  staged = null;
  $('#install-file').value = '';
  $('#install-url').value = '';
  setSource('upload');
  setStep('pick');
  await openDialog(dialog);
  if (staged) request('DELETE', `/install/${staged.token}`, {}).catch(() => {});
  staged = null;
}
