import { el } from './dom.js';

export function lazyList(list, page = 40) {
  const end = el(list.tagName === 'UL' ? 'li' : 'div', 'lazy-end');
  end.setAttribute('aria-hidden', 'true');
  end.style.height = '1px';
  end.style.flex = 'none';
  let rows = [];
  let make = null;
  let shown = 0;
  const watcher = new IntersectionObserver(entries => {
    if (entries.some(e => e.isIntersecting)) more();
  }, { root: list, rootMargin: '0px 0px 600px 0px' });

  function recheck() {
    watcher.unobserve(end);
    watcher.observe(end);
  }

  function more() {
    if (shown >= rows.length) return;
    const next = rows.slice(shown, shown + page);
    shown += next.length;
    end.before(...next.map(make));
    if (shown < rows.length) recheck();
    else end.remove();
  }

  return {
    render(head, items, build) {
      rows = items;
      make = build;
      shown = Math.min(items.length, Math.max(page, shown));
      list.replaceChildren(...head, ...items.slice(0, shown).map(build));
      if (shown < items.length) {
        list.append(end);
        recheck();
      }
    },
    reset() {
      shown = 0;
    },
  };
}
