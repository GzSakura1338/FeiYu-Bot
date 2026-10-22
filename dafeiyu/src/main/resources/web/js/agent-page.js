import { $, $$ } from './dom.js';
import { initTabBar } from './tabs.js';
import { navigate, onSubPageChange } from './router.js';
import { ago } from './format.js';
import { viewTransition } from './motion.js';

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
  const nodes = $$('#page-agent .agent-view');
  const from = nodes.find(node => !node.hidden);
  const to = nodes.find(node => node.dataset.view === view);
  tabs.select(view);
  const swap = () => {
    for (const node of nodes) node.hidden = node !== to;
    for (const fn of listeners) fn(view);
  };
  if (!from || from === to || $('#page-agent').hidden) return swap();
  const forward = VIEWS.indexOf(view) > VIEWS.indexOf(from.dataset.view);
  viewTransition(forward ? 'axis-forward' : 'axis-backward', swap, { from, to });
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
