import { $ } from './dom.js';
import { setIcon } from './icons.js';

const ICONS = { info: 'info', success: 'check', error: 'priority_high' };
let timer;

export function showSnackbar(text, tone = 'info', duration = tone === 'error' ? 6000 : 3500) {
  const bar = $('#snackbar');
  bar.dataset.tone = tone;
  setIcon($('.snackbar-icon svg', bar), ICONS[tone] ?? ICONS.info);
  $('.snackbar-text', bar).textContent = text;
  bar.classList.remove('show');
  void bar.offsetWidth;
  bar.classList.add('show');
  clearTimeout(timer);
  timer = setTimeout(() => bar.classList.remove('show'), duration);
}
