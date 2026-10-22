const PRESS_GROW_MS = 450;
const MINIMUM_PRESS_MS = 225;
const FADE_MS = 375;
const TOUCH_DELAY_MS = 150;
const INITIAL_ORIGIN_SCALE = 0.2;
const PADDING = 10;
const SOFT_EDGE_MINIMUM_SIZE = 75;
const SOFT_EDGE_CONTAINER_RATIO = 0.35;
const STANDARD = 'cubic-bezier(0.2, 0, 0, 1)';

function grow(host, x, y) {
  const { width, height, left, top } = host.getBoundingClientRect();
  const maxDim = Math.max(width, height);
  const softEdge = Math.max(SOFT_EDGE_CONTAINER_RATIO * maxDim, SOFT_EDGE_MINIMUM_SIZE);
  const initial = Math.max(1, Math.floor(maxDim * INITIAL_ORIGIN_SCALE));
  const scale = (Math.hypot(width, height) + PADDING + softEdge) / initial;
  const ripple = document.createElement('span');
  ripple.className = 'ripple';
  ripple.style.width = ripple.style.height = `${initial}px`;
  host.append(ripple);
  ripple.animate([
    { transform: `translate(${x - left - initial / 2}px, ${y - top - initial / 2}px) scale(1)` },
    { transform: `translate(${(width - initial) / 2}px, ${(height - initial) / 2}px) scale(${scale})` },
  ], { duration: PRESS_GROW_MS, easing: STANDARD, fill: 'forwards' });
  const started = performance.now();
  return () => {
    const wait = Math.max(0, MINIMUM_PRESS_MS - (performance.now() - started));
    setTimeout(() => {
      ripple.classList.add('out');
      setTimeout(() => ripple.remove(), FADE_MS);
    }, wait);
  };
}

export function initRipple() {
  document.addEventListener('pointerdown', e => {
    if (e.button !== 0) return;
    const host = e.target.closest('.state-layer');
    if (!host || host.disabled || host.getAttribute('aria-disabled') === 'true') return;
    let release = null;
    let cancelled = false;
    const start = () => {
      if (!cancelled) release = grow(host, e.clientX, e.clientY);
    };
    const touchTimer = e.pointerType === 'touch' ? setTimeout(start, TOUCH_DELAY_MS) : (start(), 0);
    const end = ev => {
      window.removeEventListener('pointerup', end);
      window.removeEventListener('pointercancel', end);
      clearTimeout(touchTimer);
      if (!release && ev.type === 'pointerup' && e.pointerType === 'touch') start();
      cancelled = true;
      release?.();
    };
    window.addEventListener('pointerup', end);
    window.addEventListener('pointercancel', end);
  });
}
