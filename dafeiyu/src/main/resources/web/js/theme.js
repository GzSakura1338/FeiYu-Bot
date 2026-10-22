import { $, $$, el } from './dom.js';
import { setIcon, createIcon } from './icons.js';
import { showSnackbar } from './snackbar.js';
import { viewTransition } from './motion.js';

const KEY = 'dafeiyu.theme';
const PALETTE_KEY = 'dafeiyu.palette';
const MODES = ['auto', 'light', 'dark'];
const META = {
  auto: { icon: 'brightness_auto', label: '跟随系统' },
  light: { icon: 'light_mode', label: '浅色' },
  dark: { icon: 'dark_mode', label: '深色' },
};
const PALETTES = [
  { id: 'lavender', name: '薰衣草', note: '默认', light: ['#65558f', '#e8def8', '#ffd9e3'], dark: ['#cfbdfe', '#cbc2db', '#efb8c8'] },
  { id: 'ocean', name: '深海', light: ['#415f91', '#dae2f9', '#f9d8fd'], dark: ['#aac7ff', '#bec6dc', '#dcbce0'] },
  { id: 'sky', name: '晴空', note: '鲜艳', light: ['#0060a9', '#dbe1ff', '#e2dfff'], dark: ['#a2c9ff', '#bbc5ea', '#c3c1f9'] },
  { id: 'seasalt', name: '海盐', light: ['#006972', '#cde7eb', '#d9e2ff'], dark: ['#81d3df', '#b1cbcf', '#b9c6ea'] },
  { id: 'mint', name: '薄荷', light: ['#236a4c', '#d0e8d8', '#c0e9fa'], dark: ['#8fd5b0', '#b4ccbc', '#a5cdde'] },
  { id: 'matcha', name: '抹茶', light: ['#516527', '#dee6c6', '#bcece4'], dark: ['#b7d085', '#c2caab', '#a0d0c8'] },
  { id: 'aurora', name: '极光', note: '多彩', light: ['#226b41', '#f9d8fe', '#e7deff'], dark: ['#8ed6a3', '#dcbce1', '#ccbff7'] },
  { id: 'sakura', name: '樱花', light: ['#8a4a64', '#ffd9e4', '#ffdcc4'], dark: ['#ffb0cc', '#e2bdc8', '#f0bc96'] },
  { id: 'berry', name: '莓果', note: '鲜艳', light: ['#bd0048', '#ffdad4', '#ffdbcc'], dark: ['#ffb2bd', '#f6b8ae', '#ffb693'] },
  { id: 'sunset', name: '落日', note: '鲜艳', light: ['#a04100', '#ffdcc2', '#ffddb4'], dark: ['#ffb693', '#efbc94', '#f0be79'] },
  { id: 'amber', name: '琥珀', light: ['#775a0b', '#f5e0bb', '#ccebc5'], dark: ['#e8c26c', '#d8c4a0', '#b0cfaa'] },
  { id: 'paper', name: '暖纸', note: '素雅', light: ['#6c5b48', '#f1e0d0', '#fcddbc'], dark: ['#d9c3ab', '#d4c4b5', '#dfc2a2'] },
  { id: 'graphite', name: '石墨', note: '黑白', light: ['#000000', '#d4d4d4', '#747474'], dark: ['#ffffff', '#c6c6c6', '#919191'] },
];
const systemDark = matchMedia('(prefers-color-scheme: dark)');

let mode = MODES.includes(localStorage.getItem(KEY)) ? localStorage.getItem(KEY) : 'auto';
let palette = PALETTES.find(p => p.id === localStorage.getItem(PALETTE_KEY)) ?? PALETTES[0];

function apply() {
  const dark = mode === 'dark' || (mode === 'auto' && systemDark.matches);
  const root = document.documentElement;
  root.dataset.theme = dark ? 'dark' : 'light';
  root.dataset.palette = palette.id;
  for (const button of $$('#theme-segmented [data-mode]')) {
    button.setAttribute('aria-checked', String(button.dataset.mode === mode));
  }
  for (const swatch of $$('#palette-grid [data-palette]')) {
    const p = PALETTES.find(x => x.id === swatch.dataset.palette);
    const [a, b, c] = dark ? p.dark : p.light;
    swatch.style.cssText = `--a:${a};--b:${b};--c:${c}`;
    swatch.setAttribute('aria-checked', String(p === palette));
    swatch.tabIndex = p === palette ? 0 : -1;
  }
  $('#palette-current').textContent = palette.note ? `${palette.name} · ${palette.note}` : palette.name;
  const toggle = $('#theme-toggle');
  setIcon($('svg', toggle), META[mode].icon);
  toggle.title = `主题：${META[mode].label}（点击切换）`;
}

function reveal(origin) {
  if (!origin) {
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
  viewTransition('reveal', apply);
}

function switchTo(next, origin) {
  if (next === mode) return;
  mode = next;
  localStorage.setItem(KEY, mode);
  reveal(origin);
}

function switchPalette(next, origin) {
  if (next === palette) return;
  palette = next;
  localStorage.setItem(PALETTE_KEY, palette.id);
  reveal(origin);
}

function renderSwatches() {
  const grid = $('#palette-grid');
  grid.replaceChildren(...PALETTES.map(p => {
    const swatch = el('button', 'palette-swatch state-layer');
    swatch.setAttribute('role', 'radio');
    swatch.title = p.note ? `${p.name}（${p.note}）` : p.name;
    swatch.dataset.palette = p.id;
    const dot = el('span', 'palette-dot');
    dot.append(createIcon('check', 'palette-check'));
    swatch.append(dot, el('span', 'palette-name label-medium', p.name));
    swatch.addEventListener('click', () => switchPalette(p, dot));
    return swatch;
  }));
  grid.addEventListener('keydown', e => {
    const step = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 }[e.key];
    if (!step) return;
    e.preventDefault();
    const next = PALETTES[(PALETTES.indexOf(palette) + step + PALETTES.length) % PALETTES.length];
    const target = $(`[data-palette="${next.id}"]`, grid);
    switchPalette(next, $('.palette-dot', target));
    target.focus();
  });
}

export function initTheme() {
  renderSwatches();
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
