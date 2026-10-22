import { el } from './dom.js';
import { createIcon } from './icons.js';
import { enhanceDialog, openDialog } from './dialog.js';
import { showSnackbar } from './snackbar.js';
import { formatTime } from './format.js';
import { attachmentNodes, parseAttachments, stripPlaceholders } from './msg-attachments.js';

let dialog = null;
const trail = [];

function ensureDialog() {
  if (dialog) return dialog;
  dialog = el('dialog', 'dialog dialog-wide forward-dialog');
  dialog.setAttribute('aria-labelledby', 'forward-title');
  const form = el('form', 'dialog-form');
  form.method = 'dialog';
  const head = el('header', 'forward-head');
  const back = el('button', 'icon-button state-layer forward-back');
  back.type = 'button';
  back.title = back.ariaLabel = '返回上一层';
  back.append(createIcon('arrow_forward'));
  back.addEventListener('click', () => {
    trail.pop();
    const previous = trail.pop();
    if (previous) show(previous);
  });
  const title = el('h2', 'headline-small forward-title', '合并转发的聊天记录');
  title.id = 'forward-title';
  head.append(back, title, el('span', 'body-small forward-count'));
  const body = el('div', 'dialog-body forward-body');
  const actions = el('div', 'dialog-actions');
  const close = el('button', 'text-button label-large state-layer', '关闭');
  close.value = 'close';
  actions.append(close);
  form.append(head, body, actions);
  dialog.append(form);
  document.body.append(dialog);
  enhanceDialog(dialog);
  dialog.addEventListener('close', () => {
    trail.length = 0;
  });
  return dialog;
}

function line(l) {
  const row = el('div', 'forward-line');
  const meta = el('div', 'forward-meta');
  meta.append(el('span', 'title-small forward-name', l.name));
  if (l.time) meta.append(el('span', 'label-small forward-time', formatTime(l.time)));
  row.append(meta);
  const attachments = parseAttachments(l.attachments);
  const text = stripPlaceholders(l.text || '', attachments);
  if (text) row.append(el('div', 'body-medium forward-text', text));
  row.append(...attachmentNodes(attachments));
  return row;
}

async function show(id) {
  const box = ensureDialog();
  const body = box.querySelector('.forward-body');
  body.replaceChildren(el('div', 'body-medium forward-empty', '读取中…'));
  trail.push(id);
  box.querySelector('.forward-back').hidden = trail.length < 2;
  try {
    const res = await fetch(`/api/agent/sessions/forward?id=${encodeURIComponent(id)}`, { cache: 'no-store' });
    const json = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`);
    box.querySelector('.forward-count').textContent = `共 ${json.lines.length} 条`;
    body.replaceChildren(...(json.lines.length ? json.lines.map(line) : [el('div', 'body-medium forward-empty', '里面没有消息')]));
  } catch (err) {
    body.replaceChildren(el('div', 'body-medium forward-empty', `读不到：${err.message}`));
  }
}

export async function openForward(id) {
  const box = ensureDialog();
  if (box.open) {
    show(id);
    return;
  }
  trail.length = 0;
  show(id).catch(err => showSnackbar(err.message, 'error'));
  await openDialog(box);
}
