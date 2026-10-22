const SHOW_DELAY_MS = 500;
const GAP = 4;
const EDGE = 8;

let tip;
let anchor = null;
let pending = null;
let timer = 0;

function ensureTip() {
  if (tip) return tip;
  tip = document.createElement('div');
  tip.className = 'tooltip body-small';
  tip.popover = 'manual';
  tip.setAttribute('role', 'tooltip');
  document.body.append(tip);
  return tip;
}

function adopt(node) {
  const title = node.getAttribute('title');
  if (title) {
    node.dataset.tooltip = title;
    node.removeAttribute('title');
    if (!node.hasAttribute('aria-label') && !node.textContent.trim()) node.setAttribute('aria-label', title);
  }
  return node.dataset.tooltip;
}

function place() {
  const rect = anchor.getBoundingClientRect();
  const { width, height } = tip.getBoundingClientRect();
  const left = Math.min(Math.max(EDGE, rect.left + rect.width / 2 - width / 2), innerWidth - width - EDGE);
  const below = rect.bottom + GAP + height <= innerHeight - EDGE;
  tip.style.left = `${left}px`;
  tip.style.top = `${below ? rect.bottom + GAP : rect.top - GAP - height}px`;
  tip.style.transformOrigin = below ? 'top center' : 'bottom center';
}

function show(node) {
  const text = adopt(node);
  if (!text || !node.isConnected) return;
  ensureTip();
  anchor = node;
  tip.textContent = text;
  const host = node.closest('dialog[open]') ?? document.body;
  if (tip.parentElement !== host) {
    if (tip.matches(':popover-open')) tip.hidePopover();
    host.append(tip);
  }
  if (!tip.matches(':popover-open')) tip.showPopover();
  place();
  tip.classList.add('show');
}

function hide() {
  clearTimeout(timer);
  anchor = null;
  pending = null;
  if (!tip?.matches(':popover-open')) return;
  tip.classList.remove('show');
  const closing = tip;
  setTimeout(() => {
    if (!anchor && closing.matches(':popover-open')) closing.hidePopover();
  }, 100);
}

const target = e => e.target instanceof Element ? e.target.closest('[title], [data-tooltip]') : null;

export function initTooltips() {
  document.addEventListener('pointerover', e => {
    if (e.pointerType === 'touch') return;
    const node = target(e);
    if (!node) return;
    adopt(node);
    if (node === anchor || node === pending) return;
    clearTimeout(timer);
    if (tip?.classList.contains('show')) return show(node);
    pending = node;
    timer = setTimeout(() => {
      pending = null;
      show(node);
    }, SHOW_DELAY_MS);
  });
  document.addEventListener('pointerout', e => {
    const node = target(e);
    if (node && !node.contains(e.relatedTarget)) hide();
  });
  document.addEventListener('focusin', e => {
    const node = target(e);
    if (node && node.matches(':focus-visible')) show(node);
  });
  document.addEventListener('focusout', hide);
  document.addEventListener('pointerdown', hide, true);
  document.addEventListener('scroll', hide, true);
  document.addEventListener('keydown', e => e.key === 'Escape' && hide());
}
