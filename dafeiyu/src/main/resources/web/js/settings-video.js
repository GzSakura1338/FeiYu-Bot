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

function hint() {
  if (!state) return '读取失败';
  if (!state.ffmpeg) {
    return '没找到 ffmpeg：装好后放进 PATH 再重启 FeiYu Bot（Windows：winget install ffmpeg；Linux：sudo apt install ffmpeg；macOS：brew install ffmpeg）';
  }
  if (!state.vision) return `找到了 ffmpeg，但现在的模型看不了图，视频也就看不了`;
  return state.frames > 0 ? `每个视频截 ${state.frames} 张给她看（ffmpeg：${state.ffmpeg}）` : `关着，她只知道有人发了视频（ffmpeg：${state.ffmpeg}）`;
}

function render() {
  for (const b of $$('#video-frames [role="radio"]')) b.setAttribute('aria-checked', String(Number(b.dataset.frames) === state?.frames));
  for (const b of $$('#video-seconds [role="radio"]')) b.setAttribute('aria-checked', String(Number(b.dataset.seconds) === state?.seconds));
  $('#video-hint').textContent = hint();
}

async function load() {
  try {
    state = await request('GET');
    render();
  } catch (err) {
    showSnackbar(`读取看视频设置失败：${err.message}`, 'error');
  }
}

async function update(patch) {
  try {
    state = await request('PUT', patch);
    render();
  } catch (err) {
    showSnackbar(`保存失败：${err.message}`, 'error');
  }
}

export function initSettingsVideo() {
  for (const b of $$('#video-frames [role="radio"]')) b.addEventListener('click', () => update({ frames: Number(b.dataset.frames) }));
  for (const b of $$('#video-seconds [role="radio"]')) b.addEventListener('click', () => update({ seconds: Number(b.dataset.seconds) }));
  menuSelect($('#video-frames'));
  menuSelect($('#video-seconds'));
  onPageChange(page => {
    if (page === 'settings') load();
  });
}
