import { el } from './dom.js';
import { createIcon } from './icons.js';
import { formatSize } from './chat-render.js';

const PLACEHOLDER = /\[(?:图片|动画表情|表情|视频|语音|文件(?:：[^\]]*)?|合并转发的聊天记录|卡片(?:：[^\]]*)?|卡片消息|链接(?:：[^\]]*)?|位置(?:：[^\]]*)?|音乐分享|戳一戳|推荐联系人|掷骰子(?:：[^\]]*)?|猜拳)\]/g;

const CARD = {
  file: { icon: 'description', label: '文件' },
  video: { icon: 'monitor_heart', label: '视频' },
  record: { icon: 'send', label: '语音' },
  forward: { icon: 'forum', label: '合并转发的聊天记录' },
  card: { icon: 'dashboard', label: '卡片消息' },
  link: { icon: 'link', label: '链接' },
  music: { icon: 'mood', label: '音乐分享' },
  location: { icon: 'lan', label: '位置' },
  dice: { icon: 'auto_awesome', label: '掷骰子' },
  rps: { icon: 'auto_awesome', label: '猜拳' },
  contact: { icon: 'person', label: '推荐联系人' },
};

export function parseAttachments(raw) {
  if (!raw) return [];
  try {
    const list = JSON.parse(raw);
    return Array.isArray(list) ? list : [];
  } catch {
    return [];
  }
}

export function stripPlaceholders(text, attachments) {
  if (!attachments.some(a => a.kind !== 'poke')) return text;
  let out = text.replace(PLACEHOLDER, '');
  for (const a of attachments) {
    if (a.kind === 'card' && a.name) out = out.replace(a.name, '');
  }
  return out.trim();
}

function image(a) {
  const img = el('img', 'msg-image');
  img.alt = a.name || '图片';
  img.loading = 'lazy';
  img.referrerPolicy = 'no-referrer';
  img.src = a.url;
  img.title = a.name || '图片';
  img.addEventListener('error', () => img.replaceWith(card({ kind: 'image', name: '图片已过期，看不到了' }, 'image_search', '图片')));
  return img;
}

function card(a, icon, label) {
  const node = el(a.url ? 'a' : 'div', 'msg-card state-layer');
  if (a.url) {
    node.href = a.url;
    node.target = '_blank';
    node.rel = 'noopener noreferrer';
  }
  const text = el('span', 'msg-card-text');
  text.append(el('span', 'body-medium msg-card-name', a.name || label), el('span', 'label-small msg-card-meta', [label, a.size ? formatSize(a.size) : ''].filter(Boolean).join(' · ')));
  node.append(createIcon(icon, 'icon-18'), text);
  return node;
}

function media(a) {
  if (a.kind === 'image' || a.kind === 'sticker') return a.url ? image(a) : null;
  if (a.kind === 'video' && a.url) {
    const video = el('video', 'msg-video');
    video.controls = true;
    video.preload = 'none';
    video.src = a.url;
    return video;
  }
  if (a.kind === 'record' && a.url) {
    const audio = el('audio', 'msg-audio');
    audio.controls = true;
    audio.preload = 'none';
    audio.src = a.url;
    return audio;
  }
  const meta = CARD[a.kind];
  return meta ? card(a, meta.icon, meta.label) : null;
}

export function attachmentNodes(attachments) {
  const nodes = attachments.filter(a => a.kind !== 'poke').map(media).filter(Boolean);
  if (!nodes.length) return [];
  const box = el('div', 'msg-attachments');
  box.append(...nodes);
  return [box];
}

export function isPoke(content, attachments) {
  return attachments.some(a => a.kind === 'poke') || /^(【主人】)?【拍一拍】/.test(content);
}

export function pokeNote(content, time, formatTime) {
  const node = el('div', 'poke-note label-medium');
  node.append(createIcon('mood', 'icon-18'), el('span', null, content.replace(/【主人】|【拍一拍】/g, '').trim()));
  if (time) node.append(el('span', 'poke-time', formatTime(time)));
  return node;
}
