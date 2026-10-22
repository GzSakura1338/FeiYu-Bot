const NAME = 'vt-scope';
let token = 0;
let updating = false;

function clearNames() {
  for (const node of document.querySelectorAll('[style*="view-transition-name"]')) node.style.viewTransitionName = '';
}

export const reducedMotion = () => matchMedia('(prefers-reduced-motion: reduce)').matches;

const tokens = new Map();
export function easing(name) {
  if (!tokens.has(name)) tokens.set(name, getComputedStyle(document.documentElement).getPropertyValue(`--md-sys-motion-easing-${name}`).trim());
  return tokens.get(name);
}

export function viewTransition(kind, update, { from = null, to = null } = {}) {
  if (!document.startViewTransition || reducedMotion() || document.hidden || updating) {
    update();
    return null;
  }
  const mine = ++token;
  const root = document.documentElement;
  clearNames();
  root.dataset.vt = kind;
  if (from) from.style.viewTransitionName = NAME;
  const vt = document.startViewTransition(() => {
    if (from) from.style.viewTransitionName = '';
    updating = true;
    try {
      update();
    } finally {
      updating = false;
    }
    const target = typeof to === 'function' ? to() : to;
    if (target) target.style.viewTransitionName = NAME;
  });
  vt.finished.finally(() => {
    if (mine !== token) return;
    clearNames();
    delete root.dataset.vt;
  });
  return vt;
}
