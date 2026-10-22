import { $, el } from './dom.js';
import { createIcon } from './icons.js';
import { showSnackbar } from './snackbar.js';
import { readSse } from './sse.js';

const MAX_IMAGES = 4;
const MAX_SIDE = 1568;
let attachments = [];
let changed = () => {};

function readImage(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(url);
      const scale = Math.min(1, MAX_SIDE / Math.max(img.naturalWidth, img.naturalHeight));
      const canvas = document.createElement('canvas');
      canvas.width = Math.max(1, Math.round(img.naturalWidth * scale));
      canvas.height = Math.max(1, Math.round(img.naturalHeight * scale));
      const ctx = canvas.getContext('2d');
      ctx.fillStyle = '#fff';
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
      resolve(canvas.toDataURL('image/jpeg', 0.9));
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('无法读取图片'));
    };
    img.src = url;
  });
}

async function addImages(files) {
  const images = [...files].filter(f => f.type.startsWith('image/'));
  for (const file of images) {
    if (attachments.length >= MAX_IMAGES) {
      showSnackbar(`一次最多 ${MAX_IMAGES} 张图片`);
      break;
    }
    try {
      attachments.push(await readImage(file));
    } catch (err) {
      showSnackbar(err.message, 'error');
    }
  }
  render();
}

function render() {
  const box = $('#chat-attachments');
  box.hidden = attachments.length === 0;
  box.replaceChildren(...attachments.map((src, i) => {
    const item = el('div', 'attachment');
    const img = el('img');
    img.src = src;
    img.alt = '';
    const remove = el('button', 'attachment-remove state-layer');
    remove.type = 'button';
    remove.title = remove.ariaLabel = '移除图片';
    remove.append(createIcon('close', 'icon-18'));
    remove.addEventListener('click', () => {
      attachments.splice(i, 1);
      render();
    });
    item.append(img, remove);
    return item;
  }));
  changed();
}

export function attachmentCount() {
  return attachments.length;
}

export function takeAttachments() {
  const taken = attachments;
  attachments = [];
  render();
  return taken;
}

export function clearAttachments() {
  attachments = [];
  render();
}

export function initAttachments({ panel, input, enabled, onChange }) {
  changed = onChange;
  input.addEventListener('paste', e => {
    const files = [...(e.clipboardData?.files ?? [])];
    if (enabled() && files.some(f => f.type.startsWith('image/'))) {
      e.preventDefault();
      addImages(files);
    }
  });
  panel.addEventListener('dragover', e => {
    if (enabled() && [...(e.dataTransfer?.items ?? [])].some(i => i.type.startsWith('image/'))) {
      e.preventDefault();
      panel.classList.add('dragging');
    }
  });
  panel.addEventListener('dragleave', e => {
    if (!panel.contains(e.relatedTarget)) panel.classList.remove('dragging');
  });
  panel.addEventListener('drop', e => {
    panel.classList.remove('dragging');
    if (enabled() && e.dataTransfer?.files?.length) {
      e.preventDefault();
      addImages(e.dataTransfer.files);
    }
  });
  $('#chat-attach').addEventListener('click', () => $('#chat-file').click());
  $('#chat-file').addEventListener('change', e => {
    addImages(e.target.files);
    e.target.value = '';
  });
}

export function userBubble(text, images) {
  const node = el('div', 'bubble bubble-user body-large new');
  if (images.length) {
    const grid = el('div', 'bubble-images');
    for (const src of images) {
      const img = el('img');
      img.src = src;
      img.alt = '';
      grid.append(img);
    }
    node.append(grid);
  }
  if (text) node.append(el('div', null, text));
  return node;
}

export async function streamTurn(session, text, images, reply, signal) {
  let finished = false;
  try {
    const res = await fetch('/api/agent/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ session: session.replace(/^web:/, ''), message: text, images }),
      signal,
    });
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      throw new Error(body.error ?? `HTTP ${res.status}`);
    }
    await readSse(res, (type, data) => {
      if (type === 'done') {
        finished = true;
        reply.finish(data);
      } else if (type === 'error') {
        finished = true;
        reply.fail(`出错了：${data}`);
      } else {
        reply.apply(type, data);
      }
    });
    if (!finished) reply.fail('出错了：连接中断');
  } catch (err) {
    if (err.name === 'AbortError') reply.abort();
    else reply.fail(`出错了：${err.message}`);
  }
}
