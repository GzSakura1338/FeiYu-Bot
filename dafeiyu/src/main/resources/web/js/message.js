const MESSAGE_RE = /^message \[(group \d+|private)\] (.*)\((\d+)\): ([\s\S]*)$/;

const whereLabel = where => where === 'private' ? '私聊' : where.replace('group ', '群 ');

export function classify(entry) {
  if (entry.level === 'ERROR') return { kind: 'error' };
  if (entry.level === 'WARN') return { kind: 'warn' };
  const m = MESSAGE_RE.exec(entry.text);
  return m ? { kind: 'message', where: whereLabel(m[1]), name: m[2], qq: m[3], content: m[4] } : { kind: 'info' };
}

const CQ_LABELS = {
  image: '图片', face: '表情', mface: '表情', record: '语音', video: '视频', file: '文件',
  reply: '回复', forward: '合并转发', json: '卡片', xml: '卡片', markdown: 'Markdown',
  dice: '骰子', rps: '猜拳', poke: '戳一戳', music: '音乐', location: '位置', contact: '名片', share: '链接',
};
const CQ_RE = /\[CQ:([a-z_]+)((?:,[^\]]*)?)(?:\]|$)/g;

const unescapeCq = s => s.replace(/&#91;/g, '[').replace(/&#93;/g, ']').replace(/&#44;/g, ',').replace(/&amp;/g, '&');

function cqLabel(type, params) {
  if (type === 'at') {
    const qq = /(?:^|,)qq=([^,]*)/.exec(params)?.[1];
    return qq === 'all' ? '@全体成员' : `@${qq ?? ''}`;
  }
  return CQ_LABELS[type] ?? type;
}

export function renderContent(raw) {
  const frag = document.createDocumentFragment();
  let last = 0;
  for (const m of raw.matchAll(CQ_RE)) {
    if (m.index > last) frag.append(unescapeCq(raw.slice(last, m.index)));
    const tag = document.createElement('span');
    tag.className = 'cq';
    tag.textContent = cqLabel(m[1], m[2]);
    frag.append(tag);
    last = m.index + m[0].length;
  }
  if (last < raw.length) frag.append(unescapeCq(raw.slice(last)));
  return frag;
}

export function plainContent(raw) {
  return unescapeCq(raw.replace(CQ_RE, (_, type, params) => `[${cqLabel(type, params)}]`));
}
