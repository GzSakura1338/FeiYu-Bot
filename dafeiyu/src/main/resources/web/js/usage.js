import { $, $$, el } from './dom.js';
import { createIcon } from './icons.js';
import { showSnackbar } from './snackbar.js';
import { onPageChange, currentPage } from './router.js';

const WEEKDAYS = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];
const EFFORTS = { default: '默认', none: '关闭', low: '低', medium: '中', high: '高', max: '最高' };
const PURPOSE_ICONS = { chat: 'forum', mind: 'neurology', compact: 'history', guard: 'shield', sticker: 'mood', reflect: 'psychology', probe: 'build' };
const DAY_MS = 86_400_000;
const RATIOS = [0.35, 0.8, 1.6];

let data = null;
let mode = 'daily';
let rendered = null;
let timer = 0;
let days = [];
let base = 0;
let activeDays = 0;
let activeWeeks = 0;

function fmt(n) {
  if (n >= 1e9) return `${+(n / 1e9).toFixed(1)}B`;
  if (n >= 1e6) return `${+(n / 1e6).toFixed(1)}M`;
  if (n >= 1e3) return `${+(n / 1e3).toFixed(1)}k`;
  return String(Math.round(n));
}

function duration(ms) {
  if (!ms) return '—';
  const s = Math.round(ms / 1000);
  if (s < 60) return `${(ms / 1000).toFixed(1)} 秒`;
  const m = Math.floor(s / 60);
  return m < 60 ? `${m} 分 ${s % 60} 秒` : `${Math.floor(m / 60)} 小时 ${m % 60} 分`;
}

const parse = day => new Date(`${day}T00:00:00`);
const key = date => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
const label = date => `${date.getMonth() + 1}月${date.getDate()}日 ${WEEKDAYS[date.getDay()]}`;
const short = date => `${date.getMonth() + 1}月${date.getDate()}日`;

function renderProfile() {
  const { profile } = data;
  const avatar = $('#usage-avatar');
  if (profile.avatar) {
    const img = el('img');
    img.src = profile.avatar;
    img.alt = '';
    avatar.replaceChildren(img);
  } else {
    avatar.replaceChildren(el('span', null, [...(profile.name || '鱼')][0]));
  }
  $('#usage-name').textContent = profile.name;
  $('#usage-handle').textContent = profile.qq ? `@${profile.nickname || profile.qq} · QQ ${profile.qq}` : '@未登录 QQ';
  $('#usage-model').textContent = profile.model || '未选择模型';
  $('#usage-model').hidden = !profile.model;
}

function renderStats() {
  const s = data.stats;
  const cells = [
    [fmt(s.lifetime), '累计 token', `${s.lifetime.toLocaleString()} token`],
    [fmt(s.peak), '单日峰值', s.peakDay ? label(parse(s.peakDay)) : ''],
    [duration(s.longestMs), '最长一次调用', '单次模型调用从发出到收完的时间'],
    [`${s.currentStreak} 天`, '当前连续', '连续每天都有调用的天数'],
    [`${s.longestStreak} 天`, '最长连续', ''],
  ];
  $('#usage-stats').replaceChildren(...cells.map(([value, name, title]) => {
    const cell = el('div', 'usage-stat');
    cell.title = title;
    cell.append(el('dd', 'usage-stat-value', value), el('dt', 'usage-stat-label', name));
    return cell;
  }));
}

function median(values) {
  const sorted = values.filter(v => v > 0).sort((a, b) => a - b);
  if (!sorted.length) return 0;
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

const recentMedian = (values, recent) => median(values.slice(-recent)) || median(values);

function niceMax(value) {
  if (value <= 0) return 1;
  const power = 10 ** Math.floor(Math.log10(value));
  const step = [1, 2, 2.5, 5, 10].find(s => s * power >= value);
  return step * power;
}

function versus(value, periods) {
  if (!value || !base || periods < 2) return '';
  const ratio = value / base;
  if (ratio >= 0.9 && ratio <= 1.1) return '，和平常差不多';
  return ratio > 1 ? `，约是平常的 ${+ratio.toFixed(1)} 倍` : `，约是平常的 ${Math.round(ratio * 100)}%`;
}

function buildDays() {
  const byDay = new Map(data.days.map(d => [d.day, d]));
  const start = parse(data.start);
  const today = parse(data.today);
  const list = [];
  let total = 0;
  for (let t = start.getTime(); t <= today.getTime(); t += DAY_MS) {
    const date = new Date(t);
    const d = byDay.get(key(date));
    total += d?.tokens ?? 0;
    list.push({ date, tokens: d?.tokens ?? 0, calls: d?.calls ?? 0, total });
  }
  return list;
}

function monthMarks(count, dateAt) {
  const marks = [];
  for (let i = 0; i < count; i++) {
    const first = dateAt(i);
    if (first) marks.push({ index: i, month: first.getMonth() });
  }
  return marks;
}

function axes(className, yLabels, plot, xLabels) {
  const frame = el('div', `usage-axes ${className}`);
  const y = el('div', 'usage-y label-small');
  y.setAttribute('aria-hidden', 'true');
  y.append(...yLabels);
  const x = el('div', 'usage-x label-medium');
  x.setAttribute('aria-hidden', 'true');
  x.append(...xLabels);
  frame.append(y, plot, el('span'), x);
  return frame;
}

function restart(node) {
  node.classList.remove('entering');
  void node.offsetWidth;
  node.classList.add('entering');
  clearTimeout(node.enterTimer);
  node.enterTimer = setTimeout(() => node.classList.remove('entering'), 1800);
}

function daily(animate) {
  const values = days.map(d => d.tokens);
  base = recentMedian(values, 30);
  activeDays = values.filter(v => v > 0).length;
  const cuts = base ? RATIOS.map(r => base * r) : null;
  const level = v => (v <= 0 || !cuts ? 0 : v <= cuts[0] ? 1 : v <= cuts[1] ? 2 : v <= cuts[2] ? 3 : 4);
  const weeks = Math.ceil(days.length / 7);
  let heat = $('#usage-chart .usage-heat');
  const reuse = heat && rendered === 'daily' && heat.children.length === days.length && !animate;
  if (!reuse) {
    $('#usage-chart').replaceChildren();
    heat = el('div', 'usage-heat');
    heat.setAttribute('role', 'grid');
    heat.setAttribute('aria-label', '每天的 token 用量');
    heat.style.setProperty('--weeks', weeks);
    heat.append(...days.map((_, i) => {
      const cell = el('span', 'usage-cell');
      cell.setAttribute('role', 'gridcell');
      cell.style.setProperty('--col', Math.floor(i / 7));
      cell.style.setProperty('--row', i % 7);
      return cell;
    }));
    const yLabels = ['', '一', '', '三', '', '五', ''].map(t => el('span', null, t));
    const marks = monthMarks(weeks, w => days.slice(w * 7, w * 7 + 7).find(d => d.date.getDate() === 1)?.date);
    if (!marks.length || marks[0].index >= 3) marks.unshift({ index: 0, month: days[0].date.getMonth() });
    const xLabels = marks.map(({ index, month }) => {
      const node = el('span', null, `${month + 1}月`);
      node.style.gridColumn = `${index + 1} / span 3`;
      return node;
    });
    const frame = axes('usage-axes-heat', yLabels, heat, xLabels);
    frame.style.setProperty('--weeks', weeks);
    $('#usage-chart').replaceChildren(frame);
    restart(heat);
  }
  [...heat.children].forEach((cell, i) => {
    const d = days[i];
    cell.className = `usage-cell level-${level(d.tokens)}${i === days.length - 1 ? ' today' : ''}`;
    cell.dataset.index = i;
    cell.setAttribute('aria-label', `${label(d.date)}，${d.tokens.toLocaleString()} token`);
  });
  legend(cuts);
}

function legend(cuts) {
  const ranges = cuts
    ? ['没有调用', `≤ ${fmt(cuts[0])}`, `${fmt(cuts[0])} – ${fmt(cuts[1])}`, `${fmt(cuts[1])} – ${fmt(cuts[2])}`, `> ${fmt(cuts[2])}`]
    : ['没有调用', '', '', '', ''];
  $('#usage-legend').replaceChildren(el('span', null, '少'), ...[0, 1, 2, 3, 4].map(i => {
    const swatch = el('span', `usage-cell level-${i}`);
    swatch.title = ranges[i];
    return swatch;
  }), el('span', null, '多'));
}

const YEARS = 7;
const COLS = 53;

function yearBlocks() {
  const today = parse(data.today);
  const byDay = new Map(data.days.map(d => [d.day, d]));
  const years = Array.from({ length: YEARS }, (_, i) => today.getFullYear() - YEARS + 1 + i);
  const rows = years.map(year => {
    const blocks = [];
    let ytd = 0;
    for (let b = 0; b < COLS; b++) {
      const start = new Date(year, 0, 1 + b * 7);
      if (start.getFullYear() !== year) {
        blocks.push(null);
        continue;
      }
      const last = new Date(year, 0, 7 + b * 7);
      const end = last.getFullYear() !== year ? new Date(year, 11, 31) : last;
      let tokens = 0;
      let calls = 0;
      for (let d = new Date(start); d <= end && d <= today; d.setDate(d.getDate() + 1)) {
        const hit = byDay.get(key(d));
        if (hit) {
          tokens += hit.tokens;
          calls += hit.calls;
        }
      }
      ytd += tokens;
      blocks.push({ year, start, end: end > today ? today : end, tokens, calls, ytd, future: start > today, current: start <= today && today <= end });
    }
    return blocks;
  });
  return { years, rows };
}

function yearGrid(kind, play) {
  const { years, rows } = yearBlocks();
  const past = rows.flat().filter(b => b && !b.future);
  let cuts;
  let item;
  if (kind === 'weekly') {
    base = recentMedian(past.map(b => b.tokens), 12);
    activeWeeks = past.filter(b => b.tokens > 0).length;
    cuts = base ? RATIOS.map(r => base * r) : null;
    item = b => ({
      value: b.tokens,
      title: `${b.year}年 ${short(b.start)}${b.end > b.start ? ` – ${short(b.end)}` : ''}`,
      sub: b.tokens ? `${b.calls.toLocaleString()} 次调用${versus(b.tokens, activeWeeks)}` : '这周没有调用',
    });
  } else {
    const max = Math.max(0, ...past.map(b => b.ytd));
    cuts = max ? [max * 0.25, max * 0.5, max * 0.75] : null;
    item = b => ({
      value: b.ytd,
      title: `${b.year}年累计 · 截至 ${short(b.end)}`,
      sub: b.tokens ? `这周 +${b.tokens.toLocaleString()} token` : '这周没有新增',
    });
  }
  const level = v => (v <= 0 || !cuts ? 0 : v <= cuts[0] ? 1 : v <= cuts[1] ? 2 : v <= cuts[2] ? 3 : 4);
  const plot = el('div', 'usage-heat');
  plot.setAttribute('role', 'grid');
  plot.setAttribute('aria-label', kind === 'weekly' ? '每周的 token 用量' : '每年累计的 token 用量');
  plot.style.setProperty('--weeks', COLS);
  plot.items = [];
  for (let col = 0; col < COLS; col++) {
    years.forEach((_, row) => {
      const b = rows[row][col];
      const cell = el('span', 'usage-cell');
      cell.style.setProperty('--col', col);
      cell.style.setProperty('--row', row);
      if (!b) {
        cell.classList.add('blank');
      } else if (b.future) {
        cell.classList.add('level-0', 'future');
      } else {
        const it = item(b);
        cell.classList.add(`level-${level(it.value)}`);
        if (b.current) cell.classList.add('today');
        cell.setAttribute('role', 'gridcell');
        cell.setAttribute('aria-label', `${it.title}，${it.value.toLocaleString()} token`);
        cell.dataset.item = plot.items.length;
        plot.items.push(it);
      }
      plot.append(cell);
    });
  }
  const reference = new Date(years.at(-1), 0, 1);
  const xLabels = Array.from({ length: 12 }, (_, m) => {
    const col = Math.floor((new Date(years.at(-1), m, 1) - reference) / DAY_MS / 7);
    const node = el('span', null, `${m + 1}月`);
    node.style.gridColumn = `${col + 1} / span 4`;
    return node;
  });
  const frame = axes('usage-axes-heat', years.map(y => el('span', null, String(y))), plot, xLabels);
  frame.style.setProperty('--weeks', COLS);
  $('#usage-chart').replaceChildren(frame);
  if (play) restart(plot);
  legend(cuts);
}

function weekly(play) {
  yearGrid('weekly', play);
}

function cumulative(play) {
  yearGrid('cumulative', play);
}

function renderChart(animate = false) {
  days = buildDays();
  const play = animate || rendered !== mode;
  if (mode === 'weekly') weekly(play);
  else if (mode === 'cumulative') cumulative(play);
  else daily(play);
  rendered = mode;
}

function renderInsights() {
  const s = data.stats;
  const efforts = data.efforts ?? [];
  const totalCalls = efforts.reduce((sum, e) => sum + e.calls, 0);
  const top = efforts.toSorted((a, b) => b.calls - a.calls)[0];
  const rows = [
    ['输入 token', fmt(s.prompt)],
    ['输出 token', fmt(s.completion)],
    ['缓存命中', s.prompt ? `${Math.round((s.cached / s.prompt) * 100)}%` : '—'],
    ['最常用思考强度', top ? `${EFFORTS[top.label] ?? top.label} · ${Math.round((top.calls / totalCalls) * 100)}%` : '—'],
    ['平均每次调用', duration(s.avgMs)],
    ['总调用次数', s.calls.toLocaleString()],
  ];
  $('#usage-insights').replaceChildren(...rows.map(([name, value]) => {
    const row = el('div', 'usage-row');
    row.append(el('dt', null, name), el('dd', null, value));
    return row;
  }));
}

function ranks(host, items, icon, empty) {
  if (!items.length) {
    host.replaceChildren(el('li', 'usage-empty body-medium', empty));
    return;
  }
  host.replaceChildren(...items.map(item => {
    const row = el('li', 'usage-rank');
    const badge = el('span', 'usage-rank-icon');
    badge.append(createIcon(icon(item), 'icon-18'));
    row.append(badge, el('span', 'usage-rank-name', item.label || '（未知）'), el('span', 'usage-rank-value', `${fmt(item.tokens)} token · ${item.calls.toLocaleString()} 次`));
    return row;
  }));
}

function render(animate) {
  renderProfile();
  renderStats();
  renderChart(animate);
  renderInsights();
  ranks($('#usage-purposes'), data.purposes, item => PURPOSE_ICONS[item.key] ?? 'build', '还没有调用记录');
  ranks($('#usage-sessions'), data.sessions, item => (item.key?.startsWith('mind:') ? 'neurology' : item.key?.startsWith('group:') ? 'groups' : item.key?.startsWith('web:') ? 'forum' : 'person'), '还没有调用记录');
  ranks($('#usage-models'), data.models, () => 'memory', '还没有调用记录');
  $('#usage-note').textContent = data.stats.since
    ? `从 ${label(parse(data.stats.since))}开始统计，之前的调用没有记录。token 数以模型接口返回的为准，包括聊天、内心和审核、识图、整理记忆这些后台调用。`
    : '还没有调用记录，模型被调用后这里就会有数据。';
}

async function load(animate = false) {
  try {
    const res = await fetch('/api/usage', { cache: 'no-store' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    data = await res.json();
    render(animate);
  } catch (err) {
    showSnackbar(`读取用量失败：${err.message}`, 'error');
  }
}

function movePill() {
  const active = $('#usage-modes button[aria-checked="true"]');
  const pill = $('.usage-mode-pill');
  if (!active || !active.offsetWidth) return;
  pill.style.width = `${active.offsetWidth}px`;
  pill.style.transform = `translateX(${active.offsetLeft}px)`;
}

function setMode(next) {
  if (next === mode) return;
  mode = next;
  for (const b of $$('#usage-modes button')) b.setAttribute('aria-checked', String(b.dataset.mode === mode));
  movePill();
  hideTip();
  if (data) renderChart(true);
}


function hideTip() {
  $('#usage-tooltip').classList.remove('visible');
}

function showTip(anchor, title, value, sub) {
  const box = $('#usage-tooltip');
  box.replaceChildren(el('div', 'usage-tip-title label-medium', title), el('div', 'usage-tip-value', `${value.toLocaleString()} token`),
    el('div', 'usage-tip-sub label-medium', sub));
  const wrap = box.parentElement.getBoundingClientRect();
  const half = 110;
  box.style.left = `${Math.min(Math.max(anchor.x - wrap.left, half), wrap.width - half)}px`;
  box.style.top = `${anchor.y - wrap.top - 10}px`;
  box.classList.add('visible');
}

function onHover(e) {
  const chart = $('#usage-chart');
  const cell = e.target.closest?.('.usage-cell');
  if (!cell || !chart.contains(cell) || cell.classList.contains('blank') || cell.classList.contains('future')) {
    hideTip();
    return;
  }
  const rect = cell.getBoundingClientRect();
  if (cell.dataset.item !== undefined) {
    const item = cell.parentElement.items[cell.dataset.item];
    showTip({ x: rect.left + rect.width / 2, y: rect.top }, item.title, item.value, item.sub);
    return;
  }
  const d = days[cell.dataset.index];
  showTip({ x: rect.left + rect.width / 2, y: rect.top }, label(d.date), d.tokens,
    d.tokens ? `${d.calls.toLocaleString()} 次调用${versus(d.tokens, activeDays)}` : '没有调用');
}

export function initUsage() {
  for (const b of $$('#usage-modes button')) b.addEventListener('click', () => setMode(b.dataset.mode));
  const chart = $('#usage-chart');
  chart.addEventListener('pointermove', onHover);
  chart.addEventListener('pointerleave', hideTip);
  window.addEventListener('resize', movePill);
  onPageChange(page => {
    clearInterval(timer);
    if (page !== 'usage') return;
    requestAnimationFrame(movePill);
    rendered = null;
    load(true);
    timer = setInterval(() => {
      if (currentPage() === 'usage' && !document.hidden) load();
    }, 30_000);
  });
}
