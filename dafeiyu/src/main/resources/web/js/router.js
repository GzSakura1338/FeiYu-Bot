import { $, $$ } from './dom.js';
import { setIcon, hasIcon } from './icons.js';
import { viewTransition } from './motion.js';

const PAGES = ['overview', 'logs', 'agent', 'plugins', 'usage', 'settings'];
const listeners = [];
const subListeners = [];
let current = null;
let currentSub = null;

export const currentPage = () => current;

export function onPageChange(fn) {
  listeners.push(fn);
}

export function onSubPageChange(fn) {
  subListeners.push(fn);
}

export function navigate(page, sub) {
  const hash = sub ? `${page}/${sub}` : page;
  if (location.hash.slice(1) !== hash) location.hash = hash;
}

function markRail(page) {
  for (const item of $$('.rail-item')) {
    const active = item.dataset.page === page;
    if (active) item.setAttribute('aria-current', 'page');
    else item.removeAttribute('aria-current');
    const svg = $('.rail-indicator svg', item);
    const base = (svg.dataset.base ??= svg.dataset.icon);
    setIcon(svg, active && hasIcon(`${base}_fill`) ? `${base}_fill` : base);
  }
}

function route() {
  const [name, sub = null] = location.hash.slice(1).split('/');
  const page = PAGES.includes(name) ? name : 'overview';
  if (page === current) {
    if (sub !== currentSub) {
      currentSub = sub;
      for (const fn of subListeners) fn(page, sub);
    }
    return;
  }
  const first = current === null;
  current = page;
  currentSub = sub;
  markRail(page);
  document.title = `${$(`#page-${page}`).dataset.title} · FeiYu Bot`;

  const swap = () => {
    for (const p of PAGES) $(`#page-${p}`).hidden = p !== page;
    $('.content').scrollTop = 0;
    for (const fn of listeners) fn(page);
    for (const fn of subListeners) fn(page, sub);
  };
  if (first) swap();
  else viewTransition('fade-through', swap, { from: $('.content'), to: $('.content') });
}

export function initRouter() {
  window.addEventListener('hashchange', route);
  route();
}
