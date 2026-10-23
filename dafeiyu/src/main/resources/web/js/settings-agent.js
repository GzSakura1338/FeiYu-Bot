import { $, $$, el } from './dom.js';
import { createIcon } from './icons.js';
import { menuSelect } from './select-menu.js';
import { onPageChange } from './router.js';
import { showSnackbar } from './snackbar.js';
import { curateSummary } from './agent-memory.js';

let state = null;
let curate = null;
let modelsBuilt = false;
let saveTimer = 0;
const steppers = new Map();

async function request(method, body) {
  const res = await fetch('/api/agent/subagents', {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`);
  return json;
}

function config() {
  const { profiles, workspace, today, ...rest } = state;
  return rest;
}

function renderModels() {
  const group = $('#subagent-model');
  if (!modelsBuilt) {
    const options = [{ id: '', name: '跟主模型' }, ...state.profiles.map(p => ({ id: p.id, name: p.name, hint: p.model }))];
    group.replaceChildren(...options.map(option => {
      const button = el('button', 'state-layer');
      button.type = 'button';
      button.setAttribute('role', 'radio');
      button.dataset.profile = option.id;
      if (option.hint) button.dataset.hint = option.hint;
      button.append(createIcon('check', 'icon-18 chip-check'), document.createTextNode(option.name));
      button.addEventListener('click', () => save({ profileId: option.id }));
      return button;
    }));
    menuSelect(group);
    modelsBuilt = true;
  }
  const known = state.profiles.some(p => p.id === state.profileId) ? state.profileId : '';
  for (const button of $$('button[role="radio"]', group)) button.setAttribute('aria-checked', String(button.dataset.profile === known));
}

function buildStepper(box) {
  const min = Number(box.dataset.min);
  const max = Number(box.dataset.max);
  const step = Number(box.dataset.step);
  const minus = el('button', 'icon-button state-layer');
  const plus = el('button', 'icon-button state-layer');
  const input = el('input');
  const unit = el('span', 'stepper-unit body-medium', box.dataset.unit ?? '');
  minus.type = plus.type = 'button';
  minus.ariaLabel = '减少';
  plus.ariaLabel = '增加';
  minus.append(createIcon('remove'));
  plus.append(createIcon('add'));
  input.inputMode = 'numeric';
  input.ariaLabel = box.closest('.list-item')?.querySelector('.list-headline')?.textContent ?? '';
  box.append(minus, input, unit, plus);
  const stepper = { value: min, show: null };
  stepper.show = value => {
    stepper.value = value;
    const zero = box.dataset.zero && value === 0;
    input.value = zero ? box.dataset.zero : String(value);
    unit.hidden = zero;
    minus.disabled = value <= min;
    plus.disabled = value >= max;
  };
  const change = value => {
    const next = Math.max(min, Math.min(max, value));
    stepper.show(next);
    clearTimeout(saveTimer);
    saveTimer = setTimeout(saveLimits, 500);
  };
  const nudge = dir => {
    const v = stepper.value;
    change(step > 1 ? (dir > 0 ? Math.floor(v / step) * step + step : Math.ceil(v / step) * step - step) : v + dir);
  };
  minus.addEventListener('click', () => nudge(-1));
  plus.addEventListener('click', () => nudge(1));
  const commit = () => {
    const raw = input.value.trim();
    const n = raw === box.dataset.zero ? 0 : /^\d{1,4}$/.test(raw) ? Number(raw) : NaN;
    if (Number.isNaN(n)) stepper.show(stepper.value);
    else if (n !== stepper.value) change(n);
    else stepper.show(n);
  };
  input.addEventListener('blur', commit);
  input.addEventListener('keydown', event => {
    if (event.key === 'Enter') input.blur();
    if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
      event.preventDefault();
      nudge(event.key === 'ArrowUp' ? 1 : -1);
    }
  });
  steppers.set(box.dataset.field, stepper);
}

function saveLimits() {
  const values = {};
  for (const [key, stepper] of steppers) values[key] = stepper.value;
  if ([...steppers.keys()].some(key => values[key] !== state?.[key])) save(values);
}

function render() {
  $('#subagent-enabled').setAttribute('aria-checked', String(state.enabled));
  $('#subagent-research').setAttribute('aria-checked', String(state.research));
  $('#subagent-build').setAttribute('aria-checked', String(state.build && state.workspace));
  $('#subagent-build').disabled = !state.workspace;
  $('#subagent-enabled-hint').textContent = state.enabled ? '已开启：内心可以把要花时间的事交给子 agent' : '关着时内心不会开子 agent';
  $('#subagent-build-hint').textContent = state.workspace
    ? '在工作目录里读写文件、跑命令（命令总在沙盒里），和后台任务一样'
    : '要先在「Agent › 工作目录」里设好目录才能用';
  $('#subagent-daily-hint').textContent = `减到 0 就是不限，今天已经开了 ${state.today} 个`;
  renderModels();
  for (const [key, stepper] of steppers) {
    if (document.activeElement?.closest('.stepper')?.dataset.field !== key) stepper.show(state[key]);
  }
}

async function save(patch) {
  try {
    state = await request('PUT', { ...config(), ...patch });
    render();
  } catch (err) {
    showSnackbar(`保存失败：${err.message}`, 'error');
    render();
  }
}

async function load() {
  try {
    state = await request('GET');
    render();
  } catch (err) {
    showSnackbar(`读取子 agent 设置失败：${err.message}`, 'error');
  }
}

async function memoryRequest(method, path, body) {
  const res = await fetch(`/api/agent/memory${path}`, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`);
  return json;
}

function renderCurate() {
  const toggle = $('#curate-enabled');
  toggle.disabled = !curate;
  toggle.setAttribute('aria-checked', String(Boolean(curate?.enabled)));
  $('#curate-hint').textContent = !curate ? '先在「Agent › 人格」里选一个人格'
    : `${curate.enabled ? '' : '已关闭，只在「记忆」页手动整理时做。'}${curateSummary(curate)}`;
}

async function loadCurate() {
  try {
    curate = (await memoryRequest('GET', '')).curate ?? null;
  } catch (err) {
    showSnackbar(`读取记忆整理设置失败：${err.message}`, 'error');
  }
  renderCurate();
}

async function toggleCurate() {
  if (!curate) return;
  try {
    curate = (await memoryRequest('PUT', '/curate', { enabled: !curate.enabled })).curate ?? null;
  } catch (err) {
    showSnackbar(`切换失败：${err.message}`, 'error');
  }
  renderCurate();
}

export function initSettingsAgent() {
  $('#curate-enabled').addEventListener('click', toggleCurate);
  $('#subagent-enabled').addEventListener('click', () => state && save({ enabled: !state.enabled }));
  $('#subagent-research').addEventListener('click', () => state && save({ research: !state.research }));
  $('#subagent-build').addEventListener('click', () => state && state.workspace && save({ build: !state.build }));
  for (const box of $$('#subagent-limits .stepper')) buildStepper(box);
  onPageChange(page => {
    if (page === 'settings') {
      load();
      loadCurate();
    }
  });
}
