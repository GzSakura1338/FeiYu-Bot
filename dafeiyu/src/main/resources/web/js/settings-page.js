import { $, $$ } from './dom.js';
import { navigate, onSubPageChange } from './router.js';
import { initTabBar } from './tabs.js';
import { viewTransition } from './motion.js';

const VIEWS = ['connection', 'agent', 'appearance', 'archive', 'about'];
let tabs;

function show(view) {
  const nodes = $$('#page-settings .settings-view');
  const from = nodes.find(node => !node.hidden);
  const to = nodes.find(node => node.dataset.view === view);
  tabs.select(view);
  const swap = () => {
    for (const node of nodes) node.hidden = node !== to;
  };
  if (!from || from === to || $('#page-settings').hidden) return swap();
  const forward = VIEWS.indexOf(view) > VIEWS.indexOf(from.dataset.view);
  viewTransition(forward ? 'axis-forward' : 'axis-backward', swap, { from, to });
}

export function initSettingsPage() {
  tabs = initTabBar($('#settings-tabs'), view => navigate('settings', view));
  let last = 'connection';
  onSubPageChange((page, sub) => {
    if (page !== 'settings') return;
    last = VIEWS.includes(sub) ? sub : last;
    show(last);
    requestAnimationFrame(tabs.move);
  });
}
