import { $, $$ } from './dom.js';
import { setIcon, hasIcon } from './icons.js';

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

function playEnter(page) {
  page.classList.remove('entering');
  [...page.children].forEach((child, i) => child.style.setProperty('--i', i));
  $$('.hero > *', page).forEach((child, j) => child.style.setProperty('--j', j));
  void page.offsetWidth;
  page.classList.add('entering');
  clearTimeout(page.enterTimer);
  page.enterTimer = setTimeout(() => page.classList.remove('entering'), 1200);
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
  current = page;
  currentSub = sub;

  for (const p of PAGES) $(`#page-${p}`).hidden = p !== page;
  for (const item of $$('.rail-item')) {
    const active = item.dataset.page === page;
    if (active) item.setAttribute('aria-current', 'page');
    else item.removeAttribute('aria-current');
    const svg = $('.rail-indicator svg', item);
    const base = (svg.dataset.base ??= svg.dataset.icon);
    setIcon(svg, active && hasIcon(`${base}_fill`) ? `${base}_fill` : base);
  }
  document.title = `${$(`#page-${page}`).dataset.title} · FeiYu Bot`;
  $('.content').scrollTop = 0;
  playEnter($(`#page-${page}`));
  for (const fn of listeners) fn(page);
  for (const fn of subListeners) fn(page, sub);
}

export function initRouter() {
  window.addEventListener('hashchange', route);
  route();
}
