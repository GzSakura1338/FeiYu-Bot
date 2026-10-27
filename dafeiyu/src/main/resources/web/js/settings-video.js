import { $, $$ } from './dom.js';
import { onPageChange } from './router.js';
import { menuSelect } from './select-menu.js';
import { showSnackbar } from './snackbar.js';

let state = null;

async function request(method, body, path = '') {
  const res = await fetch('/api/agent/video' + path, {
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
    return '没找到 ffmpeg：装好后重启 FeiYu Bot 就能用。'
      + 'Linux：sudo apt install ffmpeg ｜ Windows：winget install ffmpeg ｜ macOS：brew install ffmpeg'
      + '（也可以从 ffmpeg.org/download.html 下静态版，解压后整个目录加进 PATH）';
  }
  if (!state.vision) return '找到了 ffmpeg，但现在的模型看不了图，视频也就看不了';
  if (state.intervalMs <= 0) return `关着，她只知道有人发了视频（ffmpeg：${state.ffmpeg}）`;
  const perSecond = 1000 / state.intervalMs;
  const rate = perSecond >= 1 ? `每秒 ${Number(perSecond.toFixed(2))} 张` : `每 ${seconds(state.intervalMs)}一张`;
  return `整段视频每隔 ${seconds(state.intervalMs)}抽一帧（${rate}），不限时长和张数，一分钟的视频大约 ${Math.ceil(60000 / state.intervalMs)} 张（ffmpeg：${state.ffmpeg}）`;
}

function nativeHint() {
  if (!state) return '点一下试试现在的模型能不能直接看视频（不用抽帧）';
  if (state.native === true) return '现在的模型能直接看视频';
  if (state.native === false) return '现在的模型看不了视频，只能抽帧（点“检测”可以重新探一次）';
  return '还没检测过。点“检测”试一次（会发一段 1 秒的测试视频给模型）';
}

function render() {
  for (const b of $$('#video-interval [role="radio"]')) b.setAttribute('aria-checked', String(Number(b.dataset.interval) === state?.intervalMs));
  for (const b of $$('#video-gifframes [role="radio"]')) b.setAttribute('aria-checked', String(Number(b.dataset.gifframes) === state?.gifFrames));
  $('#video-hint').textContent = hint();
  $('#video-native-hint').textContent = nativeHint();
  $('#video-interval-ms').value = state && state.intervalMs !== undefined ? String(state.intervalMs) : '';
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

async function update(patch) {
  try {
    state = await request('PUT', patch);
    render();
  } catch (err) {
    showSnackbar(`保存失败：${err.message}`, 'error');
  }
}

async function probe() {
  const btn = $('#video-probe');
  btn.disabled = true;
  btn.textContent = '检测中…';
  try {
    const result = await request('POST', {}, '/probe');
    state = { ...(state ?? {}), native: result.native };
    render();
    showSnackbar(result.native ? '这个模型能直接看视频' : '这个模型看不了视频，只能抽帧', 'info');
  } catch (err) {
    showSnackbar(`检测失败：${err.message}`, 'error');
  } finally {
    btn.disabled = false;
    btn.textContent = '检测';
  }
}

export function initSettingsVideo() {
  for (const b of $$('#video-interval [role="radio"]')) b.addEventListener('click', () => update({ intervalMs: Number(b.dataset.interval) }));
  for (const b of $$('#video-gifframes [role="radio"]')) b.addEventListener('click', () => update({ gifFrames: Number(b.dataset.gifframes) }));
  menuSelect($('#video-interval'));
  menuSelect($('#video-gifframes'));
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
    update({ intervalMs: value });
  });
  $('#video-probe').addEventListener('click', probe);
  onPageChange(page => {
    if (page === 'settings') load();
  });
}
