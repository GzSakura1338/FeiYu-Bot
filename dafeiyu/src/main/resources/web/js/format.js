const pad = n => String(n).padStart(2, '0');
const secondsSince = iso => Math.max(0, Math.floor((Date.now() - new Date(iso)) / 1000));

export function formatTime(iso) {
  const d = new Date(iso);
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

export function chatTime(iso) {
  const d = new Date(iso);
  const now = new Date();
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  const days = Math.round((new Date(now.getFullYear(), now.getMonth(), now.getDate()) - new Date(d.getFullYear(), d.getMonth(), d.getDate())) / 86_400_000);
  if (days <= 0) return hm;
  if (days === 1) return `昨天 ${hm}`;
  if (days < 7) return `星期${'日一二三四五六'[d.getDay()]} ${hm}`;
  const date = `${d.getMonth() + 1}月${d.getDate()}日 ${hm}`;
  return d.getFullYear() === now.getFullYear() ? date : `${d.getFullYear()}年${date}`;
}

export function ago(iso) {
  const s = secondsSince(iso);
  if (s < 5) return '刚刚';
  if (s < 60) return `${s} 秒前`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m} 分钟前`;
  const h = Math.floor(m / 60);
  return h < 24 ? `${h} 小时前` : `${Math.floor(h / 24)} 天前`;
}

export function duration(iso) {
  const s = secondsSince(iso);
  const d = Math.floor(s / 86400), h = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60);
  if (d) return `${d} 天 ${h} 小时`;
  if (h) return `${h} 小时 ${m} 分钟`;
  return m ? `${m} 分钟` : `${s} 秒`;
}
