import { el } from './dom.js';
import { createIcon } from './icons.js';

export const COMMANDS = [
  { name: 'clear', icon: 'delete_sweep', desc: '清空上下文，机器人会忘掉之前聊过的内容' },
  { name: 'compact', icon: 'history', desc: '立即把之前的对话压缩成摘要，腾出上下文' },
  { name: 'stop', icon: 'stop_circle', desc: '停止正在进行的回复' },
  { name: 'status', icon: 'info', desc: '查看记录条数、上下文大小、模型和人格' },
  { name: 'say', icon: 'send', args: '要发的话', desc: '用机器人的身份直接发到 QQ，不经过模型', qqOnly: true },
  { name: 'reflect', icon: 'psychology', desc: '让她现在回顾这段聊天：更新长期记忆、写日记', qqOnly: true },
  { name: 'help', icon: 'help', desc: '列出所有命令' },
];

export function parseCommand(text) {
  const match = /^\/(\S+)\s*([\s\S]*)$/.exec(text.trim());
  return match ? { name: match[1].toLowerCase(), args: match[2].trim() } : null;
}

export function helpText(qq) {
  return COMMANDS.filter(c => qq || !c.qqOnly)
    .map(c => `/${c.name}${c.args ? ` <${c.args}>` : ''}　${c.desc}`)
    .join('\n');
}

export async function runCommand(session, name, args) {
  const res = await fetch(`/api/agent/sessions/${encodeURIComponent(session)}/command`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ command: name, args }),
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`);
  return json.message ?? '';
}

export function commandNote(text, tone = 'info', icon = 'terminal') {
  const note = el('div', `command-note body-medium new tone-${tone}`);
  note.append(createIcon(icon, 'icon-18'), el('span', 'command-note-text', text));
  return note;
}

export function attachCommandHints(input, { qq = () => true, onPick }) {
  const form = input.closest('form');
  const menu = el('ul', 'command-menu');
  menu.setAttribute('role', 'listbox');
  menu.hidden = true;
  form.append(menu);
  let items = [];
  let active = 0;

  const close = () => {
    menu.hidden = true;
    items = [];
  };
  const pick = command => {
    input.value = `/${command.name}${command.args ? ' ' : ''}`;
    input.dispatchEvent(new Event('input'));
    close();
    input.focus();
    if (!command.args) onPick?.();
  };
  const render = () => {
    menu.replaceChildren(...items.map((c, i) => {
      const item = el('li', 'command-item state-layer');
      item.setAttribute('role', 'option');
      item.setAttribute('aria-selected', String(i === active));
      const text = el('div', 'command-item-text');
      text.append(el('span', 'label-large command-name', `/${c.name}${c.args ? ` <${c.args}>` : ''}`), el('span', 'body-small command-desc', c.desc));
      item.append(createIcon(c.icon, 'icon-18'), text);
      item.addEventListener('mousedown', e => {
        e.preventDefault();
        pick(c);
      });
      return item;
    }));
  };
  const update = () => {
    const value = input.value;
    if (!value.startsWith('/') || /\s/.test(value)) return close();
    const query = value.slice(1).toLowerCase();
    items = COMMANDS.filter(c => (qq() || !c.qqOnly) && c.name.startsWith(query));
    if (!items.length) return close();
    active = Math.min(active, items.length - 1);
    menu.hidden = false;
    render();
  };

  input.addEventListener('input', update);
  input.addEventListener('blur', () => setTimeout(close, 100));
  input.addEventListener('keydown', e => {
    if (menu.hidden) return;
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      active = (active + (e.key === 'ArrowDown' ? 1 : items.length - 1)) % items.length;
      render();
    } else if (e.key === 'Tab' || (e.key === 'Enter' && !e.shiftKey && !e.isComposing && input.value.slice(1).toLowerCase() !== items[active].name)) {
      e.preventDefault();
      e.stopImmediatePropagation();
      pick(items[active]);
    } else if (e.key === 'Escape') {
      close();
    }
  }, true);
  return { close };
}
