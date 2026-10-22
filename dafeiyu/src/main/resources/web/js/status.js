import { $, el, poll } from './dom.js';
import { setIcon, createIcon } from './icons.js';
import { setShape, shapeMask } from './shapes.js';
import { setAvatar } from './avatar.js';
import { formatTime, ago, duration } from './format.js';
import { showSnackbar } from './snackbar.js';

const STATES = {
  loading:   { tone: 'neutral', icon: 'sync',      shape: 'circle', title: '读取中…' },
  down:      { tone: 'error',   icon: 'cloud_off', shape: 'flower', title: 'FeiYu Bot 后端无响应' },
  waiting:   { tone: 'warning', icon: 'link_off',  shape: 'clover', title: '等待 NapCat 连接' },
  connected: { tone: 'success', icon: 'link',      shape: 'burst',  title: 'NapCat 已连接' },
};

const INFO_ROWS = [
  { key: 'listen',      icon: 'lan',      label: '监听地址', copy: true },
  { key: 'remote',      icon: 'router',   label: '来源' },
  { key: 'connectedAt', icon: 'schedule', label: '连接时间' },
];

let status = null;
let state = 'loading';
const infoCells = {};

function buildInfoList() {
  const list = $('#info-list');
  for (const row of INFO_ROWS) {
    const item = el('li', 'list-item');
    const icon = el('span', 'list-icon');
    icon.append(createIcon(row.icon));
    const text = el('div', 'list-text');
    const value = el('div', 'list-supporting body-medium', '—');
    text.append(el('div', 'list-headline body-large', row.label), value);
    item.append(icon, text);
    if (row.copy) {
      const button = el('button', 'icon-button state-layer');
      button.title = button.ariaLabel = `复制${row.label}`;
      button.append(createIcon('content_copy'));
      button.addEventListener('click', () => copy(value.textContent));
      item.append(button);
    }
    infoCells[row.key] = value;
    list.append(item);
  }
}

async function copy(text) {
  try {
    await navigator.clipboard.writeText(text);
    showSnackbar('已复制到剪贴板', 'success');
  } catch {
    showSnackbar('复制失败，请手动选择文本', 'error');
  }
}

function setText(node, text, bump = false) {
  if (node.textContent === text) return;
  node.textContent = text;
  if (bump) {
    node.classList.remove('bump');
    void node.offsetWidth;
    node.classList.add('bump');
  }
}

function render() {
  const s = STATES[state];
  const connected = state === 'connected';

  const card = $('#status-card');
  if (card.dataset.tone !== s.tone) {
    card.dataset.tone = s.tone;
    setShape($('#status-shape'), s.shape);
  }
  const icon = $('#status-icon');
  setIcon(icon, s.icon);
  icon.classList.toggle('spin', state === 'loading');
  setText($('#status-title'), s.title);
  setText($('#status-sub'), {
    loading: '',
    down: '请确认 FeiYu Bot 正在运行（start-dafeiyu.bat），恢复后会自动刷新',
    waiting: `NapCat 登录后会自动连接到 ${status?.listen ?? ''}`,
    connected: `${status?.nickname ?? '获取昵称中…'} · ${status?.selfId ?? ''}`,
  }[state]);

  $('#status-chips').hidden = !connected;
  if (connected) {
    setText($('#chip-heartbeat span'), status.lastHeartbeat ? `心跳 ${ago(status.lastHeartbeat)}` : '等待首次心跳');
    setText($('#chip-uptime span'), `已连接 ${duration(status.connectedAt)}`);
  }

  setText($('#stat-qq'), connected ? status.selfId ?? '—' : '—', true);
  setText($('#stat-nickname'), connected ? status.nickname ?? '—' : '—', true);
  const avatarHost = $('#stat-avatar');
  setAvatar(avatarHost, connected ? status.selfId : null);
  const img = avatarHost.querySelector('.avatar-img');
  if (img && !img.style.mask) img.style.mask = img.style.webkitMask = shapeMask(avatarHost.dataset.shape);
  setText($('#stat-heartbeat'), connected && status.lastHeartbeat ? ago(status.lastHeartbeat) : '—');

  for (const row of INFO_ROWS) {
    const value = status?.[row.key];
    setText(infoCells[row.key], !value ? '—' : row.key === 'connectedAt' ? formatTime(value) : value);
  }
  setText($('#about-listen'), status?.listen ?? '—');

  const rail = $('#rail-status');
  if (rail.dataset.tone !== s.tone) {
    rail.dataset.tone = s.tone;
    setIcon($('svg', rail), s.icon);
  }
  rail.title = s.title;
}

function notify(prev, next) {
  if (prev === 'loading') return;
  if (next === 'connected') showSnackbar('NapCat 已连接', 'success');
  else if (next === 'down') showSnackbar('与 FeiYu Bot 后端的连接中断', 'error');
  else if (prev === 'connected') showSnackbar('NapCat 已断开，等待重连', 'error');
  else showSnackbar('已重新连上 FeiYu Bot 后端', 'success');
}

async function refresh() {
  let next;
  try {
    const res = await fetch('/api/status', { cache: 'no-store' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    status = await res.json();
    next = status.connected ? 'connected' : 'waiting';
  } catch {
    status = status && { listen: status.listen };
    next = 'down';
  }
  if (next !== state) {
    notify(state, next);
    state = next;
  }
  render();
}

export function initStatus() {
  buildInfoList();
  setShape($('#status-shape'), STATES.loading.shape);
  render();
  poll(refresh, 2000);
  setInterval(render, 1000);
}
