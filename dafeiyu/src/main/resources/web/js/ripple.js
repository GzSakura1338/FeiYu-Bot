export function initRipple() {
  document.addEventListener('pointerdown', e => {
    if (e.button !== 0) return;
    const host = e.target.closest('.state-layer');
    if (!host || host.disabled) return;

    const rect = host.getBoundingClientRect();
    const size = Math.hypot(rect.width, rect.height) * 2;
    const ripple = document.createElement('span');
    ripple.className = 'ripple';
    Object.assign(ripple.style, {
      width: `${size}px`,
      height: `${size}px`,
      left: `${e.clientX - rect.left - size / 2}px`,
      top: `${e.clientY - rect.top - size / 2}px`,
    });
    host.append(ripple);

    const release = () => {
      window.removeEventListener('pointerup', release);
      window.removeEventListener('pointercancel', release);
      ripple.classList.add('out');
      setTimeout(() => ripple.remove(), 300);
    };
    window.addEventListener('pointerup', release);
    window.addEventListener('pointercancel', release);
  });
}
