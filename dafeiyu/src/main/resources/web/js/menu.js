import { el } from './dom.js';
import { createIcon } from './icons.js';

let menu;
let current = null;

function ensureMenu() {
  if (menu) return menu;
  menu = el('div', 'menu');
  menu.popover = 'auto';
  menu.setAttribute('role', 'listbox');
  menu.addEventListener('toggle', e => {
    if (e.newState === 'closed' && current && !menu.matches(':popover-open')) {
      current.anchor.closest('.text-field')?.classList.remove('open');
      current.onClose?.();
      current = null;
    }
  });
  menu.addEventListener('keydown', e => {
    const items = [...menu.querySelectorAll('.menu-item')];
    const index = items.indexOf(document.activeElement);
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const next = e.key === 'ArrowDown' ? Math.min(index + 1, items.length - 1) : Math.max(index - 1, 0);
      items[next]?.focus();
    } else if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      document.activeElement?.click();
    }
  });
  return menu;
}

function place(anchor) {
  const rect = anchor.getBoundingClientRect();
  menu.style.minWidth = `${rect.width}px`;
  menu.style.left = `${rect.left}px`;
  const below = innerHeight - rect.bottom - 8;
  const height = Math.min(menu.scrollHeight, 320);
  if (below < height && rect.top > below) {
    menu.style.top = '';
    menu.style.bottom = `${innerHeight - rect.top + 4}px`;
    menu.style.transformOrigin = 'bottom';
  } else {
    menu.style.bottom = '';
    menu.style.top = `${rect.bottom + 4}px`;
    menu.style.transformOrigin = 'top';
  }
}

export function isMenuOpen(anchor) {
  return current?.anchor === anchor;
}

export function closeMenu() {
  if (menu?.matches(':popover-open')) menu.hidePopover();
}

export function openMenu(anchor, items, { selected, onSelect, onClose, empty = '没有可选项', focus = true } = {}) {
  const m = ensureMenu();
  const host = anchor.closest('dialog') ?? document.body;
  if (m.parentElement !== host) {
    closeMenu();
    host.append(m);
  }

  const nodes = items.map(item => {
    const node = el('div', 'menu-item state-layer body-large');
    node.setAttribute('role', 'option');
    node.tabIndex = -1;
    node.setAttribute('aria-selected', String(item.value === selected));
    node.append(createIcon('check', 'menu-check'), el('span', 'menu-label', item.label));
    if (item.hint) node.append(el('span', 'menu-hint label-medium', item.hint));
    node.addEventListener('click', () => {
      closeMenu();
      onSelect?.(item.value);
    });
    return node;
  });
  m.replaceChildren(...(nodes.length ? nodes : [el('div', 'menu-empty body-medium', empty)]));

  current = { anchor, onClose };
  anchor.closest('.text-field')?.classList.add('open');
  if (!m.matches(':popover-open')) m.showPopover();
  place(anchor);
  if (focus) (m.querySelector('[aria-selected="true"]') ?? m.querySelector('.menu-item'))?.focus();
}
