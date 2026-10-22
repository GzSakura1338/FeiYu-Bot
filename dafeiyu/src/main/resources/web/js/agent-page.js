import { $, $$ } from './dom.js';
import { initTabBar } from './tabs.js';
import { navigate, onSubPageChange } from './router.js';
import { ago } from './format.js';

const VIEWS = ['models', 'personas', 'workspace', 'sessions', 'memory', 'stickers'];
const listeners = [];
let tabs;

export function onAgentViewChange(fn) {
  listeners.push(fn);
}

export function renderMood(mood) {
  const node = $('#agent-mood');
  const text = mood?.text ? `此刻的心情：${mood.text} · ${ago(mood.at)}` : '';
  node.classList.toggle('empty', !text);
  node.textContent = text;
  node.title = text;
}

function show(view) {
  for (const node of $$('#page-agent .agent-view')) {
    const active = node.dataset.view === view;
    if (active && node.hidden) {
      node.hidden = false;
      node.classList.remove('view-enter');
      void node.offsetWidth;
      node.classList.add('view-enter');
    } else if (!active) {
      node.hidden = true;
    }
  }
  tabs.select(view);
  for (const fn of listeners) fn(view);
}

export function initAgentPage() {
  tabs = initTabBar($('#agent-tabs'), view => navigate('agent', view));
  let last = 'models';
  onSubPageChange((page, sub) => {
    if (page !== 'agent') return;
    last = VIEWS.includes(sub) ? sub : sub === 'chat' || sub === 'mind' ? 'sessions' : last;
    show(last);
    requestAnimationFrame(tabs.move);
  });
}
