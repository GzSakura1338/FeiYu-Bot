export function stickToBottom(list, slack = 48) {
  let stuck = true;
  let intentUntil = 0;
  const gap = () => list.scrollHeight - list.scrollTop - list.clientHeight;
  const pin = () => {
    if (stuck && gap() > 0) list.scrollTop = list.scrollHeight;
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

  const intent = () => {
    intentUntil = Math.max(intentUntil, performance.now() + 600);
  };
  for (const type of ['wheel', 'touchmove', 'keydown']) list.addEventListener(type, intent, { passive: true });
  list.addEventListener('pointerdown', () => {
    intentUntil = Infinity;
  }, { passive: true });
  addEventListener('pointerup', () => {
    if (intentUntil === Infinity) intentUntil = performance.now() + 300;
  }, { passive: true });
  list.addEventListener('scroll', () => {
    if (performance.now() < intentUntil) stuck = gap() <= slack;
  }, { passive: true });

  return {
    get stuck() {
      return stuck;
    },
    stick() {
      stuck = true;
      intentUntil = 0;
      list.scrollTop = list.scrollHeight;
    },
  };
}
