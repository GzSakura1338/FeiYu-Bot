import { $, $$ } from './dom.js';
import { onPageChange } from './router.js';
import { menuSelect } from './select-menu.js';
import { showSnackbar } from './snackbar.js';

let state = null;

async function request(method, body) {
  const res = await fetch('/api/agent/video', {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`);
  return json;
}

function seconds(ms) {
  return `${Number((ms / 1000).toFixed(3))} 秒`;
}

function hint() {
  if (!state) return '读取失败';
  if (!state.ffmpeg) {
    return '没找到 ffmpeg：装好后放进 PATH 再重启 FeiYu Bot（Windows：winget install ffmpeg；Linux：sudo apt install ffmpeg；macOS：brew install ffmpeg）';
  }
  if (!state.vision) return `找到了 ffmpeg，但现在的模型看不了图，视频也就看不了`;
  if (state.intervalMs <= 0) return `关着，她只知道有人发了视频（ffmpeg：${state.ffmpeg}）`;
  const perSecond = 1000 / state.intervalMs;
  const rate = perSecond >= 1 ? `每秒 ${Number(perSecond.toFixed(2))} 张` : `每 ${seconds(state.intervalMs)}一张`;
  return `整段视频每隔 ${seconds(state.intervalMs)}抽一帧（${rate}），不限时长和张数，一分钟的视频大约 ${Math.ceil(60000 / state.intervalMs)} 张（ffmpeg：${state.ffmpeg}）`;
}

function render() {
  for (const b of $$('#video-interval [role="radio"]')) b.setAttribute('aria-checked', String(Number(b.dataset.interval) === state?.intervalMs));
  $('#video-hint').textContent = hint();
  $('#video-interval-ms').value = state ? String(state.intervalMs) : '';
  $('#video-save').disabled = true;
}

function typed() {
  const raw = $('#video-interval-ms').value.trim();
  return /^\d+$/.test(raw) ? Number(raw) : null;
}

async function load() {
  try {
    state = await request('GET');
    render();
  } catch (err) {
    showSnackbar(`读取看视频设置失败：${err.message}`, 'error');
  }
}

async function update(intervalMs) {
  try {
    state = await request('PUT', { intervalMs });
    render();
  } catch (err) {
    showSnackbar(`保存失败：${err.message}`, 'error');
  }
}

export function initSettingsVideo() {
  for (const b of $$('#video-interval [role="radio"]')) b.addEventListener('click', () => update(Number(b.dataset.interval)));
  menuSelect($('#video-interval'));
  $('#video-interval-ms').addEventListener('input', () => {
    const value = typed();
    $('#video-save').disabled = value === null || value === state?.intervalMs;
  });
  $('#video-custom').addEventListener('submit', event => {
    event.preventDefault();
    const value = typed();
    if (value === null) {
      showSnackbar('间隔要填整数毫秒，0 是不看', 'error');
      return;
    }
    update(value);
  });
  onPageChange(page => {
    if (page === 'settings') load();
  });
}
