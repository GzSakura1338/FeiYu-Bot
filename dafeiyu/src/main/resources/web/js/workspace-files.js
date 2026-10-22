import { $, el } from './dom.js';
import { createIcon } from './icons.js';
import { showSnackbar } from './snackbar.js';
import { confirmDialog, openDialog } from './dialog.js';
import { ago } from './format.js';
import { onAgentViewChange } from './agent-page.js';

const TRASH = '.trash';
let current = '.';

function formatSize(bytes) {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return bytes >= 1024 ? `${Math.round(bytes / 1024)} KB` : `${bytes} B`;
}

const encode = path => encodeURIComponent(path);

async function request(method, path) {
  const res = await fetch(`/api/agent/files?path=${encode(path)}`, {
    method,
    headers: method === 'GET' ? {} : { 'Content-Type': 'application/json' },
    cache: 'no-store',
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw Object.assign(new Error(json.error ?? `HTTP ${res.status}`), { status: res.status });
  return json;
}

function renderCrumbs(path, root) {
  const parts = path === '.' ? [] : path.split('/');
  const nodes = [crumb(root, '.')];
  parts.forEach((part, i) => {
    nodes.push(el('span', 'files-sep', '/'));
    nodes.push(crumb(part === TRASH ? '回收站' : part, parts.slice(0, i + 1).join('/')));
  });
  $('#files-crumbs').replaceChildren(...nodes);
}

function crumb(label, path) {
  const button = el('button', 'files-crumb state-layer', label);
  button.type = 'button';
  button.addEventListener('click', () => load(path));
  return button;
}

const IMAGE = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp', 'ico', 'avif']);
const HTML = new Set(['html', 'htm']);
const TEXT = new Set(['txt', 'md', 'markdown', 'json', 'js', 'mjs', 'ts', 'css', 'py', 'java', 'kt', 'c', 'cpp', 'h', 'cs', 'go', 'rs',
  'rb', 'php', 'sh', 'bat', 'ps1', 'xml', 'yml', 'yaml', 'toml', 'ini', 'csv', 'log', 'sql', 'lua', 'vue', 'jsx', 'tsx', 'properties']);
const MAX_TEXT_PREVIEW = 400_000;

const extOf = name => (name.includes('.') ? name.split('.').pop().toLowerCase() : '');
const rawUrl = path => `/api/agent/files/raw?path=${encode(path)}`;
const downloadUrl = path => `/api/agent/files/download?path=${encode(path)}`;

function kindOf(entry) {
  if (entry.path === TRASH) return 'trash';
  if (entry.directory) return 'folder';
  const ext = extOf(entry.name);
  if (IMAGE.has(ext)) return 'image';
  if (HTML.has(ext)) return 'html';
  if (TEXT.has(ext) || !ext) return 'text';
  return 'other';
}

const KIND_ICON = { trash: 'delete', folder: 'folder_open', html: 'dashboard', text: 'description', other: 'receipt_long' };

function entryIcon(entry, kind) {
  const icon = el('span', `list-icon kind-${kind}`);
  if (kind === 'image') {
    const img = el('img', 'files-thumb');
    img.loading = 'lazy';
    img.alt = '';
    img.src = rawUrl(entry.path);
    img.addEventListener('error', () => img.replaceWith(createIcon('image_search')));
    icon.append(img);
  } else {
    icon.append(createIcon(KIND_ICON[kind]));
  }
  return icon;
}

async function preview(entry, kind) {
  const body = $('#file-dialog-body');
  $('#file-dialog-title').textContent = entry.name;
  $('#file-dialog-meta').textContent = `${formatSize(entry.size)} · ${ago(entry.modified)}`;
  $('#file-dialog-open').href = rawUrl(entry.path);
  $('#file-dialog-open').hidden = !(kind === 'html' || kind === 'image');
  $('#file-dialog-download').href = downloadUrl(entry.path);
  if (kind === 'image') {
    const img = el('img');
    img.alt = entry.name;
    img.src = rawUrl(entry.path);
    body.replaceChildren(img);
  } else if (kind === 'html') {
    const frame = document.createElement('iframe');
    frame.sandbox = 'allow-scripts allow-modals allow-forms';
    frame.src = rawUrl(entry.path);
    body.replaceChildren(frame);
  } else if (kind === 'text' && entry.size <= MAX_TEXT_PREVIEW) {
    const pre = el('pre', null, '读取中…');
    body.replaceChildren(pre);
    fetch(rawUrl(entry.path), { cache: 'no-store' })
      .then(res => (res.ok ? res.text() : Promise.reject(new Error(`HTTP ${res.status}`))))
      .then(text => { pre.textContent = text || '（空文件）'; })
      .catch(err => { pre.textContent = `读取失败：${err.message}`; });
  } else {
    body.replaceChildren(el('div', 'empty-state body-medium', kind === 'text' ? '文件太大，下载后查看' : '这种文件没法预览，下载后查看'));
  }
  openDialog($('#file-dialog')).then(() => body.replaceChildren());
}

function row(entry) {
  const item = el('li', 'list-item files-item state-layer');
  const kind = kindOf(entry);
  const trash = kind === 'trash';
  const ext = extOf(entry.name);
  const headline = el('div', 'list-headline body-large', trash ? '回收站' : entry.name);
  if (!entry.directory && ext) headline.append(el('span', 'files-ext label-small', ext.toUpperCase()));
  const text = el('div', 'list-text');
  const sub = entry.directory ? `${entry.items ? `${entry.items} 项` : '空的'} · ${ago(entry.modified)}` : `${formatSize(entry.size)} · ${ago(entry.modified)}`;
  text.append(headline, el('div', 'list-supporting body-medium', sub));
  item.append(entryIcon(entry, kind), text);
  item.tabIndex = 0;
  const open = () => (entry.directory ? load(entry.path) : preview(entry, kind));
  item.addEventListener('click', open);
  item.addEventListener('keydown', e => {
    if (e.key === 'Enter') open();
  });
  if (!entry.directory) {
    const download = el('a', 'icon-button state-layer');
    download.href = downloadUrl(entry.path);
    download.addEventListener('click', e => e.stopPropagation());
    download.title = download.ariaLabel = '下载';
    download.append(createIcon('download'));
    item.append(download);
  }
  if (!trash) {
    const remove = el('button', 'icon-button state-layer');
    remove.type = 'button';
    const inTrash = entry.path.startsWith(`${TRASH}/`);
    remove.title = remove.ariaLabel = inTrash ? '彻底删除' : '移到回收站';
    remove.append(createIcon('delete'));
    remove.addEventListener('click', e => {
      e.stopPropagation();
      removeEntry(entry, inTrash);
    });
    item.append(remove);
  }
  return item;
}

async function removeEntry(entry, forever) {
  if (forever) {
    const ok = await confirmDialog({ icon: 'delete', title: '彻底删除？', text: `「${entry.name}」删除后就找不回来了。`, confirm: '彻底删除' });
    if (!ok) return;
  }
  try {
    const { message } = await request('DELETE', entry.path);
    showSnackbar(message, 'success');
  } catch (err) {
    showSnackbar(`删除失败：${err.message}`, 'error');
  }
  load(current);
}

async function load(path = current) {
  try {
    const data = await request('GET', path);
    current = data.path;
    renderCrumbs(data.path, data.root);
    $('#files-list').replaceChildren(...data.entries.map(row));
    $('#files-empty').hidden = data.entries.length > 0;
    const folders = data.entries.filter(e => e.directory && e.path !== TRASH).length;
    const files = data.entries.filter(e => !e.directory);
    const total = files.reduce((sum, e) => sum + e.size, 0);
    $('#files-summary').textContent = data.entries.length
      ? [folders && `${folders} 个文件夹`, files.length && `${files.length} 个文件，共 ${formatSize(total)}`].filter(Boolean).join(' · ') + '　点文件可以预览'
      : '';
    $('#files-empty-text').textContent = '这里是空的';
  } catch (err) {
    $('#files-list').replaceChildren();
    $('#files-crumbs').replaceChildren();
    $('#files-summary').textContent = '';
    $('#files-empty').hidden = false;
    $('#files-empty-text').textContent = err.status === 409 ? '还没有设置工作目录' : `读取失败：${err.message}`;
    if (err.status !== 409 && path !== '.') load('.');
  }
}

export function refreshWorkspaceFiles() {
  load('.');
}

export function initWorkspaceFiles() {
  onAgentViewChange(view => {
    if (view === 'workspace') load(current);
  });
  $('#files-refresh').addEventListener('click', () => load(current));
}
