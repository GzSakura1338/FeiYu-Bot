import { $, el } from './dom.js';
import { createIcon } from './icons.js';
import { showSnackbar } from './snackbar.js';
import { confirmDialog } from './dialog.js';
import { createAvatar, createGroupAvatar } from './avatar.js';
import { ago, formatTime } from './format.js';
import { onAgentViewChange } from './agent-page.js';
import { lazyList } from './lazy-list.js';

const GROUPS = [
  { title: '她自己', test: s => s.kind === 'self' || s.kind === 'diary' || s.kind === 'knowledge' || s.kind === 'style' || s.kind === 'skill' || s.kind === 'trash' },
  { title: '主人', test: s => s.owner },
  { title: '认识的人', test: s => s.kind === 'user' && !s.owner },
  { title: '群', test: s => s.kind === 'group' },
];
const HINTS = {
  self: '她对自己的认识，按性格、喜好、习惯、经历、心愿和约定整理，零散的想法回顾时会并进去',
  diary: '每天一篇，回顾时把当天的事合进去，聊天时会想起最近两天',
  knowledge: '她遇到不懂的词上网查明白后记下的，一条一个词；聊天里再有人提到这个词，就会想起来，不用再查',
  style: '她在群里跟大家聊天慢慢学来的说话方式：一句话多长、怎么接梗吐槽、常用说法，还有她自己哪里说得不像真人；每个群攒够一百多条新消息就学一次，聊天时会想起来',
  skill: '她在聊天里学会的本事：不会的、被人教会的、自己摸索出来的，回顾时自动总结，以后聊天照着做',
  trash: '每天整理时合并掉、丢掉的知识、技能和说话方式先放在这里，保留 30 天，可以恢复',
  user: '她记得的关于这个人的事，聊天时对方在场就会想起来',
  group: '这个群的梗和氛围，在这个群聊天时会想起来',
};

let state = null;
let selected = 'self';
let items = [];
let scopeRows = null;
let itemRows = null;
const clamps = [];

async function request(method, path = '', body) {
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

function scopeAvatar(s, className = 'avatar') {
  if (s.kind === 'user') return createAvatar(s.target, s.name, className);
  if (s.kind === 'group') return createGroupAvatar(s.target, s.name, className);
  const node = el('span', `${className} avatar-icon`);
  if (s.kind === 'self' && state?.persona?.avatar) {
    const img = el('img');
    img.src = state.persona.avatar;
    img.alt = '';
    node.append(img);
  } else {
    node.append(createIcon({ diary: 'auto_stories', knowledge: 'lightbulb', style: 'forum', skill: 'build', trash: 'delete_sweep' }[s.kind] ?? 'psychology', 'icon-18'));
  }
  return node;
}

function scopeName(s) {
  if (s.kind === 'self') return `关于${state?.persona?.name ?? '她'}自己`;
  return s.name;
}

function renderScopes() {
  const list = $('#memory-scopes');
  const none = !state?.persona;
  $('#memory-none').hidden = !none;
  list.hidden = none;
  $('#memory-form').hidden = none;
  if (none) {
    list.replaceChildren();
    return;
  }
  const rows = [];
  for (const group of GROUPS) {
    const scopes = state.scopes.filter(group.test);
    if (!scopes.length) continue;
    rows.push(group.title, ...scopes);
  }
  scopeRows ??= lazyList(list, 60);
  scopeRows.render([], rows, scopeItem);
}

function scopeItem(s) {
  if (typeof s === 'string') return el('li', 'scope-heading label-medium', s);
  const item = el('li', 'session-item state-layer');
  item.setAttribute('role', 'option');
  item.setAttribute('aria-selected', String(s.scope === selected));
  item.tabIndex = 0;
  const text = el('div', 'session-item-text');
  const head = el('div', 'session-item-head');
  head.append(el('span', 'title-small session-item-name', scopeName(s)));
  head.append(el('span', 'label-small session-item-time', s.updatedAt ? ago(s.updatedAt) : ''));
  const unit = { diary: ' 天', knowledge: ' 个词', skill: ' 个' }[s.kind] ?? ' 条';
  text.append(head, el('div', 'body-small session-item-preview', s.count ? `${s.count}${unit}` : '还没有'));
  item.append(scopeAvatar(s), text);
  const open = () => select(s.scope);
  item.addEventListener('click', open);
  item.addEventListener('keydown', e => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      open();
    }
  });
  return item;
}

function renderControls() {
  $('#memory-auto').setAttribute('aria-checked', String(Boolean(state?.auto)));
  $('#memory-auto-hint').textContent = state?.auto ? '聊过一阵后自己总结、写日记、学技能' : '已关闭，只在手动回顾时记';
  const pending = state?.pending ?? [];
  const messages = pending.reduce((sum, p) => sum + p.count, 0);
  const busy = pending.some(p => p.running);
  $('#memory-pending').textContent = pending.length
    ? `${pending.length} 个会话里有 ${messages} 条新消息还没回顾${busy ? '（正在回顾…）' : ''}`
    : '最近的聊天都回顾过了';
  const button = $('#memory-reflect');
  if (!button.dataset.loading) button.disabled = !state?.persona || !pending.length;
  const curate = state?.curate ?? {};
  $('#memory-curated').textContent = curateSummary(curate) + (curate.enabled === false ? '（每天 0 点整理已在设置里关掉）' : '');
  const run = $('#memory-curate-run');
  if (!run.dataset.loading) {
    run.disabled = !state?.persona || Boolean(curate.running);
    $('span', run).textContent = curate.running ? '整理中…' : '现在整理';
  }
}

export function curateSummary(curate) {
  const last = curate.last;
  if (!last) return '还没整理过';
  const parts = (last.sections ?? []).map(s => `${s.label} ${s.before}→${s.after}`);
  return `上次整理 ${formatTime(last.at)}${parts.length ? `：${parts.join('，')}` : '，东西还不多，没动'}${last.error ? `（${last.error}）` : ''}`;
}

function rankMeta(m) {
  const meta = [];
  if (m.importance) meta.push(`重要度 ${m.importance}/5`);
  if (m.usedCount) meta.push(`用到 ${m.usedCount} 次`);
  return meta;
}

function currentScope() {
  return state?.scopes.find(s => s.scope === selected) ?? { scope: selected, kind: selected.split(':')[0], target: selected.split(':')[1] ?? '', name: selected };
}

function renderHeader() {
  const s = currentScope();
  $('#memory-avatar').replaceChildren(scopeAvatar(s, 'avatar session-avatar-img'));
  $('#memory-title').textContent = scopeName(s);
  const parts = [HINTS[s.kind] ?? ''];
  if (s.kind === 'user' || s.kind === 'group') parts.push(`${s.kind === 'user' ? 'QQ' : '群号'} ${s.target}`);
  $('#memory-sub').textContent = parts.filter(Boolean).join(' · ');
  $('#memory-input').placeholder = {
    diary: '替她往今天的日记里补一段，Enter 保存',
    knowledge: '教她一个词：写成「词」：意思，Enter 保存',
    style: '教她一种说法：写成「什么时候」：怎么说，Enter 保存',
    skill: '教她一个技能：第一行写名字，Shift+Enter 换行写怎么做，Enter 保存',
  }[s.kind] ?? '手动加一条记忆，Enter 保存';
  $('#memory-download-all').hidden = s.kind !== 'skill' || !items.length;
  $('#memory-import').hidden = s.kind !== 'skill' || !state?.persona;
  $('#memory-form').hidden = s.kind === 'trash';
}

async function upload(file, mode) {
  const res = await fetch(`/api/agent/memory/skills/import?name=${encodeURIComponent(file.name)}&mode=${mode}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/octet-stream' },
    body: file,
    cache: 'no-store',
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`);
  return json;
}

function importSummary({ added, overwritten, skipped, truncated }) {
  const parts = [];
  if (added.length) parts.push(`新学会 ${added.length} 个`);
  if (overwritten.length) parts.push(`覆盖了 ${overwritten.length} 个`);
  if (skipped.length) parts.push(`跳过同名的 ${skipped.length} 个`);
  const head = parts.length ? `导入完成：${parts.join('，')}` : '没有导入新技能';
  return truncated.length ? `${head}。「${truncated.join('」「')}」超过 3000 字，只保留了前面的部分` : head;
}

async function importSkills() {
  const input = $('#memory-import-file');
  const file = input.files[0];
  input.value = '';
  if (!file) return;
  try {
    let result = await upload(file, 'ask');
    if (result.needsConfirm) {
      const fresh = result.names.length - result.conflicts.length;
      const check = el('label', 'confirm-check body-medium');
      const box = el('input');
      box.type = 'checkbox';
      box.checked = true;
      check.append(box, `用导入的内容覆盖这 ${result.conflicts.length} 个`);
      const ok = await confirmDialog({
        icon: 'upload_file',
        title: '有同名的技能',
        text: `「${result.conflicts.join('」「')}」她已经会了。勾选就用导入的内容替换；不勾选就只导入其余 ${fresh} 个新技能。`,
        node: check,
        confirm: '导入',
      });
      if (!ok) return;
      result = await upload(file, box.checked ? 'overwrite' : 'skip');
    }
    items = result.items;
    renderItems();
    showSnackbar(importSummary(result.imported), result.imported.truncated.length ? 'info' : 'success');
    await loadState();
  } catch (err) {
    showSnackbar(`导入失败：${err.message}`, 'error');
  }
}

function sourceMeta(m) {
  if (!m.sourceName) return [];
  return [m.source === 'manual' ? m.sourceName : `来自 ${m.sourceName}`];
}

function clampable(content) {
  content.classList.add('clamped');
  const more = el('button', 'memory-item-more label-medium', '展开');
  more.type = 'button';
  more.hidden = true;
  if (!clamps.length) requestAnimationFrame(settleClamps);
  clamps.push([content, more]);
  more.addEventListener('click', () => {
    const clamped = content.classList.toggle('clamped');
    more.textContent = clamped ? '展开' : '收起';
  });
  return more;
}

function settleClamps() {
  const batch = clamps.splice(0).filter(([content]) => content.isConnected);
  const long = batch.map(([content]) => content.scrollHeight > content.clientHeight + 2);
  batch.forEach(([, more], i) => { more.hidden = !long[i]; });
}

function memoryItem(m) {
  const row = el('div', 'memory-item new');
  const text = el('div', 'memory-item-text');
  if (m.title) text.append(el('div', 'memory-item-title title-small', m.title));
  const content = el('div', 'memory-item-content body-large', m.content);
  text.append(content);
  if (selected === 'diary') text.append(clampable(content));
  const meta = [...rankMeta(m), selected === 'diary' ? `更新于 ${ago(m.updatedAt)}` : ago(m.updatedAt), ...sourceMeta(m)];
  text.append(el('div', 'memory-item-meta body-small', meta.join(' · ')));
  const actions = el('div', 'memory-item-actions');
  actions.append(iconButton('edit', '修改', () => edit(m, row)), iconButton('delete', '删除', () => remove(m)));
  row.append(text, actions);
  return row;
}

function skillItem(s) {
  const row = el('div', 'memory-item new');
  const text = el('div', 'memory-item-text');
  text.append(el('div', 'memory-item-title title-small', s.name));
  if (s.description) text.append(el('div', 'memory-item-desc body-medium', `什么时候用：${s.description}`));
  const content = el('div', 'memory-item-content body-large', s.content);
  text.append(content, clampable(content));
  const meta = [...rankMeta(s), `学会于 ${formatTime(s.createdAt)}`];
  if (s.updatedAt - s.createdAt > 60_000) meta.push(`改进于 ${ago(s.updatedAt)}`);
  text.append(el('div', 'memory-item-meta body-small', [...meta, ...sourceMeta(s)].join(' · ')));
  const actions = el('div', 'memory-item-actions');
  const download = el('a', 'icon-button state-layer');
  download.href = `/api/agent/memory/skills/${s.id}/download`;
  download.setAttribute('download', '');
  download.title = download.ariaLabel = '下载';
  download.append(createIcon('download'));
  actions.append(iconButton('edit', '修改', () => editSkill(s, row)), download, iconButton('delete', '删除', () => removeSkill(s)));
  row.append(text, actions);
  return row;
}

function trashItem(t) {
  const row = el('div', 'memory-item new');
  const text = el('div', 'memory-item-text');
  text.append(el('div', 'memory-item-title title-small', `${t.kindName}：${t.title || '（没有标题）'}`));
  if (t.description) text.append(el('div', 'memory-item-desc body-medium', `什么时候用：${t.description}`));
  const content = el('div', 'memory-item-content body-large', t.content);
  text.append(content, clampable(content));
  text.append(el('div', 'memory-item-meta body-small', [`${ago(t.removedAt)}去掉`, t.reason].filter(Boolean).join(' · ')));
  const actions = el('div', 'memory-item-actions');
  actions.append(iconButton('restart_alt', '恢复', () => restore(t)), iconButton('delete', '彻底删除', () => purge(t)));
  row.append(text, actions);
  return row;
}

async function restore(t) {
  try {
    items = (await request('POST', `/trash/${t.id}/restore`, {})).items;
    renderItems();
    showSnackbar(`「${t.title}」放回${t.kindName}了`, 'success');
    await loadState();
  } catch (err) {
    showSnackbar(`恢复失败：${err.message}`, 'error');
  }
}

async function purge(t) {
  if (!await confirmDialog({ icon: 'delete', title: `彻底删除「${t.title}」？`, text: '删了就找不回来了', confirm: '删除' })) return;
  try {
    items = (await request('DELETE', `/trash/${t.id}`, {})).items;
    renderItems();
    await loadState();
  } catch (err) {
    showSnackbar(`删除失败：${err.message}`, 'error');
  }
}

function iconButton(icon, label, onClick) {
  const button = el('button', 'icon-button state-layer');
  button.type = 'button';
  button.title = button.ariaLabel = label;
  button.append(createIcon(icon));
  button.addEventListener('click', onClick);
  return button;
}

function renderItems() {
  const list = $('#memory-list');
  if (!state?.persona) {
    list.replaceChildren();
    return;
  }
  const empty = {
    diary: '还没有日记，回顾一次就会写',
    knowledge: '还没查过什么词。聊天里遇到不懂的，她会上网查明白再记到这里',
    style: '还没学到。群里聊得多了，她会自己从群友那里学怎么说话',
    trash: '回收站是空的',
    skill: '还没学会什么技能。聊天里遇到不会的事、被人教会了，回顾时就会总结成技能',
  }[selected] ?? '这里还没有记忆';
  itemRows ??= lazyList(list, 30);
  itemRows.render(items.length ? [] : [el('div', 'empty-state body-medium', empty)], items,
    selected === 'skill' ? skillItem : selected === 'trash' ? trashItem : memoryItem);
  $('#memory-download-all').hidden = selected !== 'skill' || !items.length;
}

function editor(row, fields, onSave) {
  const save = el('button', 'filled-button filled-button-medium label-large state-layer', '保存');
  const cancel = el('button', 'text-button label-large state-layer', '取消');
  save.type = cancel.type = 'button';
  const bar = el('div', 'dialog-actions');
  bar.append(cancel, save);
  const box = el('div', 'memory-item-text');
  box.append(...fields, bar);
  row.replaceChildren(box);
  fields[0].focus();
  cancel.addEventListener('click', () => renderItems());
  save.addEventListener('click', async () => {
    try {
      items = (await onSave()).items;
      renderItems();
      await loadState();
    } catch (err) {
      showSnackbar(`保存失败：${err.message}`, 'error');
    }
  });
}

function field(tag, value, placeholder) {
  const node = el(tag, 'body-large');
  node.value = value ?? '';
  node.placeholder = placeholder;
  return node;
}

function edit(m, row) {
  const area = field('textarea', m.content, '内容');
  if (selected === 'diary' || m.title) area.style.minHeight = '240px';
  editor(row, [area], () => request('PUT', `/items/${m.id}`, { content: area.value }));
}

function editSkill(s, row) {
  const name = field('input', s.name, '技能名字');
  const description = field('input', s.description, '什么时候用');
  const content = field('textarea', s.content, '具体怎么做');
  content.style.minHeight = '240px';
  editor(row, [name, description, content], () => request('PUT', `/skills/${s.id}`, {
    name: name.value, description: description.value, content: content.value,
  }));
}

async function removeSkill(s) {
  const ok = await confirmDialog({ icon: 'delete', title: `忘掉「${s.name}」这个技能？`, text: s.description || s.content, confirm: '删除' });
  if (!ok) return;
  try {
    items = (await request('DELETE', `/skills/${s.id}`, {})).items;
    renderItems();
    await loadState();
  } catch (err) {
    showSnackbar(`删除失败：${err.message}`, 'error');
  }
}

async function remove(m) {
  const ok = await confirmDialog({ icon: 'delete', title: '删除这条记忆？', text: m.content, confirm: '删除' });
  if (!ok) return;
  try {
    items = (await request('DELETE', `/items/${m.id}`, {})).items;
    renderItems();
    await loadState();
  } catch (err) {
    showSnackbar(`删除失败：${err.message}`, 'error');
  }
}

async function loadItems() {
  if (!state?.persona) return;
  const scope = selected;
  try {
    const data = await request('GET', scope === 'skill' ? '/skills' : `/items?scope=${encodeURIComponent(scope)}`);
    if (scope !== selected) return;
    items = data.items;
  } catch (err) {
    showSnackbar(`读取记忆失败：${err.message}`, 'error');
    items = [];
  }
  renderItems();
}

async function loadState() {
  try {
    state = await request('GET');
  } catch (err) {
    showSnackbar(`读取记忆失败：${err.message}`, 'error');
    return;
  }
  if (state.persona && !state.scopes.some(s => s.scope === selected)) selected = 'self';
  renderScopes();
  renderControls();
  renderHeader();
}

async function select(scope) {
  selected = scope;
  items = [];
  itemRows?.reset();
  $('#memory-list').scrollTop = 0;
  renderScopes();
  renderHeader();
  await loadItems();
}

async function add(e) {
  e?.preventDefault();
  const input = $('#memory-input');
  const content = input.value.trim();
  if (!content) return;
  try {
    if (selected === 'skill') {
      const [name, ...rest] = content.split('\n');
      if (!rest.join('\n').trim()) throw new Error('第一行写名字，下面换行写具体怎么做');
      items = (await request('POST', '/skills', { name, description: '', content: rest.join('\n') })).items;
    } else {
      items = (await request('POST', '/items', { scope: selected, content })).items;
    }
    input.value = '';
    input.style.height = 'auto';
    $('#memory-add').disabled = true;
    renderItems();
    await loadState();
  } catch (err) {
    showSnackbar(`添加失败：${err.message}`, 'error');
  }
}

async function reflect() {
  const button = $('#memory-reflect');
  button.dataset.loading = '1';
  button.disabled = true;
  $('span', button).textContent = '回顾中…';
  try {
    const { outcomes } = await request('POST', '/reflect', {});
    const changed = outcomes.reduce((n, o) => n + o.added + o.updated + o.deleted, 0);
    showSnackbar(outcomes.length ? `回顾了 ${outcomes.length} 个会话，记忆变动 ${changed} 条` : '没有需要回顾的聊天', 'success');
  } catch (err) {
    showSnackbar(err.message, 'error');
  } finally {
    delete button.dataset.loading;
    $('span', button).textContent = '现在回顾';
  }
  await loadState();
  await loadItems();
}

async function curateNow() {
  const button = $('#memory-curate-run');
  button.dataset.loading = '1';
  button.disabled = true;
  $('span', button).textContent = '整理中…';
  try {
    state = await request('POST', '/curate', {});
    showSnackbar(state.message, 'success');
  } catch (err) {
    showSnackbar(err.message, 'error');
  } finally {
    delete button.dataset.loading;
  }
  await loadState();
  await loadItems();
}

async function toggleAuto() {
  try {
    state = await request('PUT', '/auto', { enabled: !state?.auto });
    renderControls();
  } catch (err) {
    showSnackbar(`切换失败：${err.message}`, 'error');
  }
}

async function refresh() {
  const scope = selected;
  const known = Boolean(state?.persona);
  await Promise.all([loadState(), known && loadItems()]);
  if (!known || scope !== selected) await loadItems();
}

export function initAgentMemory() {
  onAgentViewChange(view => {
    if (view === 'memory') refresh();
  });
  $('#memory-refresh').addEventListener('click', refresh);
  $('#memory-reflect').addEventListener('click', reflect);
  $('#memory-auto').addEventListener('click', toggleAuto);
  $('#memory-curate-run').addEventListener('click', curateNow);
  $('#memory-import').addEventListener('click', () => $('#memory-import-file').click());
  $('#memory-import-file').addEventListener('change', importSkills);
  const input = $('#memory-input');
  input.addEventListener('input', () => {
    input.style.height = 'auto';
    input.style.height = `${Math.min(input.scrollHeight, 160)}px`;
    $('#memory-add').disabled = !input.value.trim();
  });
  input.addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) add(e);
  });
  $('#memory-form').addEventListener('submit', add);
}
