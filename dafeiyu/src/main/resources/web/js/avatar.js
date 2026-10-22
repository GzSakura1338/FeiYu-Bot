import { el } from './dom.js';

export const avatarUrl = (qq, size = 100) => `https://q1.qlogo.cn/g?b=qq&nk=${encodeURIComponent(qq)}&s=${size}`;
export const groupAvatarUrl = (id, size = 100) => `https://p.qlogo.cn/gh/${encodeURIComponent(id)}/${encodeURIComponent(id)}/${size}`;

const loaded = new Set();

export function createAvatar(qq, name, className = 'avatar') {
  const avatar = el('span', className, [...(name || '?')][0]);
  if (qq) attach(avatar, String(qq), avatarUrl(qq));
  return avatar;
}

export function createGroupAvatar(id, name, className = 'avatar') {
  const avatar = el('span', className, [...(name || '?')][0]);
  if (id) attach(avatar, `g${id}`, groupAvatarUrl(id));
  return avatar;
}

export function setAvatar(host, qq) {
  const old = host.querySelector('img.avatar-img');
  if (old?.dataset.qq === String(qq)) return;
  old?.remove();
  host.classList.remove('has-photo');
  if (qq) attach(host, String(qq), avatarUrl(qq));
}

function attach(host, key, url) {
  if (loaded.has(url)) host.classList.add('has-photo');
  host.append(avatarImage(host, key, url));
}

function avatarImage(host, key, url) {
  const known = loaded.has(url);
  const img = document.createElement('img');
  img.className = known ? 'avatar-img loaded instant' : 'avatar-img';
  img.alt = '';
  img.loading = known ? 'eager' : 'lazy';
  img.decoding = 'async';
  img.referrerPolicy = 'no-referrer';
  img.dataset.qq = key;
  img.addEventListener('load', () => {
    loaded.add(url);
    img.classList.add('loaded');
  });
  img.addEventListener('error', () => {
    loaded.delete(url);
    host.classList.remove('has-photo');
    img.remove();
  });
  img.src = url;
  return img;
}
