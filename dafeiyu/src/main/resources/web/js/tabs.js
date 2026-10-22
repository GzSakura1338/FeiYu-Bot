import { $, $$ } from './dom.js';

export function initTabBar(bar, onSelect) {
  const indicator = $('.tab-indicator', bar);

  const move = () => {
    const tab = $('.tab[aria-selected="true"]', bar);
    if (!tab || !tab.offsetWidth) return;
    indicator.style.width = `${tab.offsetWidth}px`;
    indicator.style.transform = `translateX(${tab.offsetLeft}px)`;
  };

  const select = value => {
    for (const tab of $$('.tab', bar)) tab.setAttribute('aria-selected', String(tab.dataset.tab === value));
    move();
  };

  for (const tab of $$('.tab', bar)) tab.addEventListener('click', () => onSelect(tab.dataset.tab));
  new ResizeObserver(move).observe(bar);
  return { select, move };
}
