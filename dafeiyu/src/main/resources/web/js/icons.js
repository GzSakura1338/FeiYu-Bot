import { ICONS } from './icon-data.js';

const SVG_NS = 'http://www.w3.org/2000/svg';

export const hasIcon = name => name in ICONS;

export function setIcon(svg, name) {
  const data = ICONS[name] ?? '';
  const legacy = data.startsWith('24:');
  svg.setAttribute('viewBox', legacy ? '0 0 24 24' : '0 -960 960 960');
  svg.setAttribute('aria-hidden', 'true');
  svg.classList.add('icon');
  let path = svg.firstElementChild;
  if (!path) {
    path = document.createElementNS(SVG_NS, 'path');
    svg.append(path);
  }
  path.setAttribute('d', legacy ? data.slice(3) : data);
  svg.dataset.icon = name;
}

export function createIcon(name, className) {
  const svg = document.createElementNS(SVG_NS, 'svg');
  if (className) svg.setAttribute('class', className);
  setIcon(svg, name);
  return svg;
}

export function hydrateIcons(root = document) {
  for (const svg of root.querySelectorAll('svg[data-icon]')) setIcon(svg, svg.dataset.icon);
}
