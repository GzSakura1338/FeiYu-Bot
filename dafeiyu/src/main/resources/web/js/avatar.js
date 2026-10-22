import { el } from './dom.js';

const REFRESH_MS = 10 * 60_000;
const version = () => Math.floor(Date.now() / REFRESH_MS);

export const avatarUrl = (qq, size = 100) => `https://q1.qlogo.cn/g?b=qq&nk=${encodeURIComponent(qq)}&s=${size}&v=${version()}`;
export const groupAvatarUrl = (id, size = 100) => `https://p.qlogo.cn/gh/${encodeURIComponent(id)}/${encodeURIComponent(id)}/${size}?v=${version()}`;

const loaded = new Set();
const seen = new Set();

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
  const url = qq ? avatarUrl(qq) : '';
  if (old?.dataset.qq === String(qq) && old.dataset.src === url) return;
  if (old?.dataset.qq === String(qq) && old.classList.contains('loaded')) {
    const next = avatarImage(host, String(qq), url);
    next.style.display = 'none';
    next.addEventListener('load', () => {
      next.style.display = '';
      next.style.mask = next.style.webkitMask = old.style.mask;
      old.remove();
    }, { once: true });
    old.dataset.src = url;
    host.append(next);
    return;
  }
  old?.remove();
  host.classList.remove('has-photo');
  if (qq) attach(host, String(qq), url);
}

function attach(host, key, url) {
  if (loaded.has(url) || seen.has(key)) host.classList.add('has-photo');
  host.append(avatarImage(host, key, url));
}

function avatarImage(host, key, url) {
  const known = loaded.has(url) || seen.has(key);
  const img = document.createElement('img');
  img.className = known ? 'avatar-img loaded instant' : 'avatar-img';
  img.alt = '';
  img.loading = known ? 'eager' : 'lazy';
  img.decoding = 'async';
  img.referrerPolicy = 'no-referrer';
  img.dataset.qq = key;
  img.dataset.src = url;
  img.addEventListener('load', () => {
    loaded.add(url);
    seen.add(key);
    host.classList.add('has-photo');
    img.classList.add('loaded');
  });
  img.addEventListener('error', () => {
    loaded.delete(url);
    seen.delete(key);
    host.classList.remove('has-photo');
    img.remove();
  });
  img.src = url;
  return img;
}
