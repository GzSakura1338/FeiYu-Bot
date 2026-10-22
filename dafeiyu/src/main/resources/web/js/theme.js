import { $, $$ } from './dom.js';
import { setIcon } from './icons.js';
import { showSnackbar } from './snackbar.js';

const KEY = 'dafeiyu.theme';
const MODES = ['auto', 'light', 'dark'];
const META = {
  auto: { icon: 'brightness_auto', label: '跟随系统' },
  light: { icon: 'light_mode', label: '浅色' },
  dark: { icon: 'dark_mode', label: '深色' },
};
const systemDark = matchMedia('(prefers-color-scheme: dark)');

let mode = MODES.includes(localStorage.getItem(KEY)) ? localStorage.getItem(KEY) : 'auto';

function apply() {
  const dark = mode === 'dark' || (mode === 'auto' && systemDark.matches);
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  for (const button of $$('#theme-segmented [data-mode]')) {
    button.setAttribute('aria-checked', String(button.dataset.mode === mode));
  }
  const toggle = $('#theme-toggle');
  setIcon($('svg', toggle), META[mode].icon);
  toggle.title = `主题：${META[mode].label}（点击切换）`;
}

function switchTo(next, origin) {
  if (next === mode) return;
  mode = next;
  localStorage.setItem(KEY, mode);
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (!document.startViewTransition || reduce || !origin) {
    apply();
    return;
  }
  const rect = origin.getBoundingClientRect();
  const x = rect.left + rect.width / 2, y = rect.top + rect.height / 2;
  const radius = Math.hypot(Math.max(x, innerWidth - x), Math.max(y, innerHeight - y));
  const root = document.documentElement.style;
  root.setProperty('--reveal-x', `${x}px`);
  root.setProperty('--reveal-y', `${y}px`);
  root.setProperty('--reveal-r', `${radius}px`);
  document.startViewTransition(apply);
}

export function initTheme() {
  for (const button of $$('#theme-segmented [data-mode]')) {
    button.addEventListener('click', () => switchTo(button.dataset.mode, button));
  }
  $('#theme-toggle').addEventListener('click', e => {
    const next = MODES[(MODES.indexOf(mode) + 1) % MODES.length];
    switchTo(next, e.currentTarget);
    showSnackbar(`主题：${META[next].label}`);
  });
  systemDark.addEventListener('change', () => mode === 'auto' && apply());
  apply();
}
