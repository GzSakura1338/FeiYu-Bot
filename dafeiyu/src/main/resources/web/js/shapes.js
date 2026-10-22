import { $$ } from './dom.js';

const POINTS = 240;

export function wavyCircle(lobes, depth, rotate = 0) {
  const parts = [];
  for (let k = 0; k < POINTS; k++) {
    const t = (k / POINTS) * Math.PI * 2;
    const r = 1 - depth + depth * Math.cos(lobes * (t + rotate));
    parts.push(`${(r * Math.cos(t)).toFixed(4)} ${(r * Math.sin(t)).toFixed(4)}`);
  }
  return `M${parts.join('L')}Z`;
}

export const SHAPES = {
  circle: wavyCircle(1, 0),
  cookie: wavyCircle(9, 0.07),
  clover: wavyCircle(4, 0.2, Math.PI / 4),
  sunny: wavyCircle(8, 0.08),
  gem: wavyCircle(6, 0.1, Math.PI / 6),
  burst: wavyCircle(12, 0.07),
  flower: wavyCircle(5, 0.14),
};

export function setShape(svg, name) {
  svg.querySelector('path').style.d = `path("${SHAPES[name]}")`;
}

export function shapeMask(name) {
  const svg = `<svg xmlns='http://www.w3.org/2000/svg' viewBox='-1 -1 2 2'><path d='${SHAPES[name]}'/></svg>`;
  return `url("data:image/svg+xml,${encodeURIComponent(svg)}") center / contain no-repeat`;
}

export function hydrateShapes(root = document) {
  for (const host of $$('[data-shape]', root)) {
    host.insertAdjacentHTML('afterbegin', '<svg class="shape" viewBox="-1 -1 2 2" aria-hidden="true"><path/></svg>');
    setShape(host.firstElementChild, host.dataset.shape);
  }
}
