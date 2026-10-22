import { $, el } from './dom.js';
import { createIcon } from './icons.js';
import { easing, reducedMotion } from './motion.js';

const ICONS = { info: 'info', success: 'check', error: 'priority_high' };
const MAX = 4;
const CLOSED = 'inset(0 50% 0 50% round 999px)';
const OPEN = 'inset(-16px -16px -16px -16px round 999px)';

function stack() {
  const node = $('#snackbar-stack');
  if (!node.popover) node.popover = 'manual';
  if (node.matches(':popover-open')) node.hidePopover();
  node.showPopover();
  return node;
}

function enter(bar) {
  if (reducedMotion()) return;
  bar.animate([{ clipPath: CLOSED }, { clipPath: OPEN }], { duration: 500, easing: easing('emphasized-decelerate') });
  bar.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 100, easing: 'linear' });
  for (const part of bar.children) {
    part.animate([{ opacity: 0 }, { opacity: 0, offset: 0.4 }, { opacity: 1 }], { duration: 400, easing: 'linear' });
  }
}

function leave(bar) {
  if (bar.classList.contains('leaving')) return;
  bar.classList.add('leaving');
  clearTimeout(bar.timer);
  const done = () => {
    const host = bar.parentElement;
    if (!host) return;
    bar.remove();
    if (!host.children.length && host.matches(':popover-open')) host.hidePopover();
  };
  if (reducedMotion()) return done();
  const { height } = bar.getBoundingClientRect();
  for (const part of bar.children) part.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 120, easing: 'linear', fill: 'forwards' });
  bar.animate([{ clipPath: OPEN }, { clipPath: CLOSED }], { duration: 250, easing: easing('emphasized-accelerate'), fill: 'forwards' });
  bar.animate([
    { height: `${height}px`, minHeight: '0px', marginBottom: '8px', paddingBlock: '6px' },
    { height: '0px', minHeight: '0px', marginBottom: '0px', paddingBlock: '0px' },
  ], { duration: 250, delay: 250, easing: easing('standard'), fill: 'both' });
  setTimeout(done, 520);
}

export function showSnackbar(text, tone = 'info', duration = tone === 'error' ? 6000 : 3500) {
  const host = stack();
  const live = [...host.children].filter(node => !node.classList.contains('leaving'));
  let bar = live.find(node => node.dataset.tone === tone && node.dataset.text === text);
  if (!bar) {
    bar = el('div', 'snackbar');
    bar.dataset.tone = tone;
    bar.dataset.text = text;
    const icon = el('span', 'snackbar-icon');
    icon.append(createIcon(ICONS[tone] ?? ICONS.info));
    bar.append(icon, el('span', 'snackbar-text body-medium', text));
    host.append(bar);
    enter(bar);
    live.push(bar);
    for (const old of live.slice(0, Math.max(0, live.length - MAX))) leave(old);
  }
  clearTimeout(bar.timer);
  bar.timer = setTimeout(() => leave(bar), duration);
}
