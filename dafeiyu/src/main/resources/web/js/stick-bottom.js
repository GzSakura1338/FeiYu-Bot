const UP_KEYS = new Set(['ArrowUp', 'PageUp', 'Home']);

function innerScrolls(target, list, up) {
  for (let node = target; node && node !== list; node = node.parentElement) {
    if (node.scrollHeight > node.clientHeight + 1 && /auto|scroll/.test(getComputedStyle(node).overflowY)) {
      if (up ? node.scrollTop > 0 : node.scrollTop + node.clientHeight < node.scrollHeight - 1) return true;
    }
  }
  return false;
}

export function stickToBottom(list, slack = 8) {
  let stuck = true;
  let dragging = false;
  let touchY = 0;
  const gap = () => list.scrollHeight - list.scrollTop - list.clientHeight;
  const pin = () => {
    if (stuck && !dragging && gap() > 0) list.scrollTop = list.scrollHeight;
  };
  const release = () => {
    stuck = false;
  };
  const sizes = new ResizeObserver(pin);
  const watch = node => {
    if (node.nodeType === Node.ELEMENT_NODE) sizes.observe(node);
  };
  sizes.observe(list);
  [...list.children].forEach(watch);
  new MutationObserver(records => {
    for (const record of records) {
      record.addedNodes.forEach(watch);
      record.removedNodes.forEach(node => node.nodeType === Node.ELEMENT_NODE && sizes.unobserve(node));
    }
    pin();
  }).observe(list, { childList: true });

  list.addEventListener('wheel', e => {
    if (e.deltaY < 0 && !innerScrolls(e.target, list, true)) release();
  }, { passive: true });
  list.addEventListener('touchstart', e => {
    touchY = e.touches[0]?.clientY ?? 0;
  }, { passive: true });
  list.addEventListener('touchmove', e => {
    const y = e.touches[0]?.clientY ?? touchY;
    if (y > touchY + 2 && !innerScrolls(e.target, list, true)) release();
    touchY = y;
  }, { passive: true });
  list.addEventListener('keydown', e => {
    if (UP_KEYS.has(e.key)) release();
  });
  list.addEventListener('pointerdown', e => {
    if (e.target === list) dragging = true;
  }, { passive: true });
  addEventListener('pointerup', () => {
    dragging = false;
  }, { passive: true });
  let lastTop = list.scrollTop;
  list.addEventListener('scroll', () => {
    const down = list.scrollTop > lastTop;
    lastTop = list.scrollTop;
    if (dragging && !down && gap() > slack) release();
    else if (!stuck && down && gap() <= slack) stuck = true;
  }, { passive: true });

  const api = {
    get stuck() {
      return stuck;
    },
    stick() {
      stuck = true;
      list.scrollTop = list.scrollHeight;
      lastTop = list.scrollTop;
    },
  };
  list.stickyBottom = api;
  return api;
}
