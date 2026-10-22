import { el } from './dom.js';
import { createIcon } from './icons.js';

const ZOOMABLE = '.chat-list img:not(.avatar-img), .file-preview-body img';

let dialog;
let image;
let caption;
let open;

function build() {
  dialog = el('dialog', 'lightbox');
  dialog.setAttribute('aria-label', '查看图片');
  image = el('img', 'lightbox-image');
  image.referrerPolicy = 'no-referrer';
  caption = el('div', 'lightbox-caption body-medium');
  open = el('a', 'lightbox-button label-large state-layer');
  open.target = '_blank';
  open.rel = 'noopener noreferrer';
  open.append(createIcon('open_in_full', 'icon-18'), el('span', null, '新标签页打开'));
  const close = el('button', 'lightbox-button label-large state-layer');
  close.type = 'button';
  close.append(createIcon('close', 'icon-18'), el('span', null, '关闭'));
  close.addEventListener('click', () => dialog.close());
  const bar = el('div', 'lightbox-bar');
  bar.append(caption, open, close);
  dialog.append(image, bar);
  dialog.addEventListener('click', e => {
    if (e.target === dialog || e.target === image) dialog.close();
  });
  dialog.addEventListener('close', () => image.removeAttribute('src'));
  document.body.append(dialog);
}

export function showImage(src, text = '') {
  if (!dialog) build();
  image.src = src;
  image.alt = text;
  caption.textContent = text;
  caption.hidden = !text;
  open.href = src;
  dialog.showModal();
}

export function initLightbox() {
  document.addEventListener('click', e => {
    const img = e.target.closest?.(ZOOMABLE);
    if (!img || !img.src || img.closest('.lightbox')) return;
    e.preventDefault();
    e.stopPropagation();
    showImage(img.currentSrc || img.src, img.title || img.dataset.tooltip || img.alt || '');
  }, true);
}
