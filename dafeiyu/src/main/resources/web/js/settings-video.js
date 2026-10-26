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

/** 用当前设置算一段视频实际会截几张，给主人一个直观预览。 */
function preview() {
  if (!state || !state.secondsPerFrame) return '';
  const spf = state.secondsPerFrame;
  const cap = state.maxFrames || 24;
  const sample = state.maxSeconds === 0 ? 60 : state.maxSeconds;   // 整段时按 1 分钟举例
  const eff = Math.max(spf, sample / cap);
  const count = Math.max(1, Math.min(Math.ceil(sample / eff), cap));
  const where = state.maxSeconds === 0 ? '1 分钟的视频' : `前 ${fmt(state.maxSeconds)}`;
  if (eff > spf + 0.01) {
    return `按现在的设置，${where}会截 ${count} 张（本来每 ${spf} 秒一张太多，自动拉到每 ${fmt(eff)} 一张）`;
  }
  return `按现在的设置，${where}会截 ${count} 张`;
}

function fmt(sec) {
  if (sec >= 60 && sec % 60 === 0) return `${sec / 60} 分钟`;
  return `${Math.round(sec * 10) / 10} 秒`;
}

function hint() {
  if (!state) return '读取失败';
  if (!state.ffmpeg) {
    return '没找到 ffmpeg：装好后重启 FeiYu Bot 就能用。'
      + 'Linux：sudo apt install ffmpeg ｜ Windows：winget install ffmpeg ｜ macOS：brew install ffmpeg'
      + '（也可以从 ffmpeg.org/download.html 下静态版，解压后整个目录加进 PATH）';
  }
  if (!state.vision) return '找到了 ffmpeg，但现在的模型看不了图，视频抽了也白抽';
  if (!state.secondsPerFrame) return `关着，她只知道有人发了视频（ffmpeg：${state.ffmpeg}）`;
  return `${preview()}（ffmpeg：${state.ffmpeg}）`;
}

function nativeHint() {
  if (!state) return '点一下试试现在的模型能不能直接看视频（不用抽帧）';
  if (state.native === true) return '现在的模型能直接看视频，不用抽帧了';
  if (state.native === false) return '现在的模型看不了视频，只能抽帧（点“检测”可以重新探一次）';
  return '还没检测过。点“检测”试一次（会发一段 1 秒的测试视频给模型）';
}

function render() {
  const groups = [
    ['#video-spf', 'spf', 'secondsPerFrame'],
    ['#video-maxsec', 'maxsec', 'maxSeconds'],
    ['#video-maxframes', 'maxframes', 'maxFrames'],
    ['#video-gifframes', 'gifframes', 'gifFrames'],
  ];
  for (const [sel, key, field] of groups) {
    for (const b of $$(`${sel} [role="radio"]`)) {
      b.setAttribute('aria-checked', String(Number(b.dataset[key]) === state?.[field]));
    }
  }
  $('#video-hint').textContent = hint();
  const nh = $('#video-native-hint');
  if (nh) nh.textContent = nativeHint();
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
  if (btn) {
    btn.disabled = true;
    btn.textContent = '检测中…';
  }
  try {
    const result = await request('POST', {}, '/probe');
    if (state) state.native = result.native;
    else state = { native: result.native };
    render();
    showSnackbar(result.native ? '这个模型能直接看视频' : '这个模型看不了视频，只能抽帧', 'info');
  } catch (err) {
    showSnackbar(`检测失败：${err.message}`, 'error');
  } finally {
    if (btn) {
      btn.disabled = false;
      btn.textContent = '检测';
    }
  }
}

export function initSettingsVideo() {
  const bind = (sel, key, field) => {
    for (const b of $$(`${sel} [role="radio"]`)) {
      b.addEventListener('click', () => update({ [field]: Number(b.dataset[key]) }));
    }
    menuSelect($(sel));
  };
  bind('#video-spf', 'spf', 'secondsPerFrame');
  bind('#video-maxsec', 'maxsec', 'maxSeconds');
  bind('#video-maxframes', 'maxframes', 'maxFrames');
  bind('#video-gifframes', 'gifframes', 'gifFrames');
  const probeBtn = $('#video-probe');
  if (probeBtn) probeBtn.addEventListener('click', probe);
  onPageChange(page => {
    if (page === 'settings') load();
  });
}
