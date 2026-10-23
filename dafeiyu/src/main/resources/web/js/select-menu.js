import { el } from './dom.js';
import { createIcon } from './icons.js';
import { closeMenu, isMenuOpen, openMenu } from './menu.js';

export function menuSelect(group) {
  const radios = [...group.querySelectorAll('[role="radio"]')];
  const trigger = el('button', 'select-trigger label-large state-layer');
  trigger.type = 'button';
  trigger.setAttribute('aria-haspopup', 'listbox');
  trigger.ariaLabel = group.getAttribute('aria-label') ?? '';
  const text = el('span', 'select-trigger-label');
  trigger.append(text, createIcon('arrow_drop_down', 'icon-18 select-trigger-arrow'));
  const label = radio => radio.textContent.trim();
  const checked = () => radios.findIndex(r => r.getAttribute('aria-checked') === 'true');
  const sync = () => {
    const index = checked();
    text.textContent = index >= 0 ? label(radios[index]) : '请选择';
  };
  trigger.addEventListener('click', () => {
    if (isMenuOpen(trigger)) return closeMenu();
    trigger.classList.add('open');
    openMenu(trigger, radios.map((r, i) => ({ value: String(i), label: label(r), hint: r.dataset.hint })), {
      selected: String(checked()),
      onSelect: value => radios[Number(value)].click(),
      onClose: () => trigger.classList.remove('open'),
    });
  });
  new MutationObserver(sync).observe(group, { subtree: true, attributes: true, attributeFilter: ['aria-checked'] });
  group.hidden = true;
  group.after(trigger);
  sync();
  return trigger;
}
