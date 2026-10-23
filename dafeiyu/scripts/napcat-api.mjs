import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const input = path.resolve(process.argv[2] ?? path.join(here, '../../napcat/napcat.mjs'));
const output = path.resolve(process.argv[3] ?? path.join(here, '../src/main/resources/napcat-api.json'));

const src = fs.readFileSync(input, 'utf8');
const lines = src.split('\n');
const unescape = s => JSON.parse('"' + s + '"');

function enumTable() {
  const start = lines.findIndex(l => l.startsWith('const re = {'));
  if (start < 0) throw new Error('找不到接口名枚举（const re = {）');
  const names = {};
  const sections = {};
  let section = 'napcat';
  for (let i = start + 1; i < lines.length && !lines[i].startsWith('};'); i++) {
    const l = lines[i];
    if (/\/\/ onebot 11/.test(l)) section = 'onebot11';
    else if (/\/\/ go-cqhttp/.test(l)) section = 'gocq';
    else if (/\/\/ 以下为扩展napcat扩展/.test(l)) section = 'napcat';
    const m = l.match(/^\s+([\w$]+): "([^"]+)"/);
    if (m) {
      names[m[1]] = m[2];
      sections[m[2]] = /^GoCQHTTP_/i.test(m[1]) ? 'gocq' : section;
    }
  }
  return { names, sections };
}

function classTable() {
  const head = /^(?:class ([\w$]+) extends ([\w$]+) \{|(?:let |var |const )?([\w$]+) = class extends ([\w$]+) \{)/;
  const classes = {};
  for (let i = 0; i < lines.length; i++) {
    const h = lines[i].match(head);
    if (!h) continue;
    const info = { parent: h[2] || h[4] };
    for (let j = i + 1; j < lines.length && !/^\}/.test(lines[j]); j++) {
      const f = lines[j].match(/^  (actionName|actionSummary|actionDescription|actionTags|payloadSchema) = (.*?);?$/);
      if (f) info[f[1]] = f[2];
    }
    classes[h[1] || h[3]] = info;
  }
  return classes;
}

function registered() {
  const start = lines.findIndex((l, i) => /^function [\w$]+\(t, e\) \{$/.test(l) && /^\s+return \[$/.test(lines[i + 1] ?? '')
    && /^\s+new [\w$]+\(t, e\),?$/.test(lines[i + 2] ?? ''));
  if (start < 0) throw new Error('找不到接口注册表');
  const out = [];
  for (let i = start + 2; i < lines.length && !/^\s*\];/.test(lines[i]); i++) {
    const m = lines[i].match(/^\s+new ([\w$]+)\(t, e\)/);
    if (m) out.push(m[1]);
  }
  for (const m of src.matchAll(/\$M\n\], ([\w$]+)\)/g)) out.push(m[1]);
  return out;
}

function balanced(text, open) {
  const close = { '(': ')', '[': ']', '{': '}' };
  const stack = [];
  let quote = null;
  for (let i = open; i < text.length; i++) {
    const ch = text[i];
    if (quote) {
      if (ch === '\\') i++;
      else if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"' || ch === "'" || ch === '`') quote = ch;
    else if (close[ch]) stack.push(close[ch]);
    else if (ch === ')' || ch === ']' || ch === '}') {
      stack.pop();
      if (!stack.length) return text.slice(open, i + 1);
    }
  }
  return null;
}

function stripComments(text) {
  let out = '';
  let quote = null;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (quote) {
      out += ch;
      if (ch === '\\') out += text[++i];
      else if (ch === quote) quote = null;
    } else if (ch === '"' || ch === "'" || ch === '`') {
      quote = ch;
      out += ch;
    } else if (ch === '/' && text[i + 1] === '/') {
      while (i < text.length && text[i] !== '\n') i++;
      out += '\n';
    } else if (ch === '/' && text[i + 1] === '*') {
      i = text.indexOf('*/', i + 2) + 1;
    } else {
      out += ch;
    }
  }
  return out;
}

function splitTop(body) {
  const parts = [];
  let depth = 0;
  let quote = null;
  let start = 0;
  for (let i = 0; i < body.length; i++) {
    const ch = body[i];
    if (quote) {
      if (ch === '\\') i++;
      else if (ch === quote) quote = null;
    } else if (ch === '"' || ch === "'" || ch === '`') quote = ch;
    else if ('([{'.includes(ch)) depth++;
    else if (')]}'.includes(ch)) depth--;
    else if (ch === ',' && depth === 0) {
      parts.push(body.slice(start, i));
      start = i + 1;
    }
  }
  parts.push(body.slice(start));
  return parts.map(s => s.trim()).filter(Boolean);
}

function literal(value, depth = 0) {
  if (value === undefined) return '';
  let m = value.match(/^"((?:[^"\\]|\\.)*)"$/);
  if (m) return unescape(m[1]);
  m = value.match(/^'((?:[^'\\]|\\.)*)'$/);
  if (m) return unescape(m[1].replace(/\\'/g, "'").replace(/"/g, '\\"'));
  m = value.match(/^`([\s\S]*)`$/);
  if (m) return m[1];
  if (/^[\w$]+$/.test(value) && depth < 3) {
    const d = new RegExp('(?:^|[\\s,])' + value.replace(/\$/g, '\\$') + ' = ("(?:[^"\\\\]|\\\\.)*"|\'(?:[^\'\\\\]|\\\\.)*\'|`[^`]*`)', 'm').exec(src);
    if (d) return literal(d[1], depth + 1);
  }
  return '';
}

function definition(expr) {
  if (!expr || expr === 'void 0') return null;
  if (/^p\.\w+\(/.test(expr)) return expr;
  if (!/^[\w$]+$/.test(expr)) return null;
  const m = new RegExp('(?:^|[\\s,])' + expr.replace(/\$/g, '\\$') + ' = (p\\.\\w+)\\(', 'm').exec(src);
  return m ? m[1] + balanced(src, m.index + m[0].length - 1) : null;
}

function description(expr) {
  const all = [...expr.matchAll(/description: "((?:[^"\\]|\\.)*)"/g)];
  return all.length ? unescape(all[all.length - 1][1]) : '';
}

function typeOf(expr, depth = 0) {
  let e = expr;
  let optional = false;
  if (e.startsWith('p.Optional(')) {
    optional = true;
    e = e.slice('p.Optional('.length);
  }
  const ref = e.match(/^([\w$]+)\)?$/);
  if (ref && depth < 3) {
    const def = definition(ref[1]);
    if (def) {
      const inner = typeOf(def, depth + 1);
      return { type: inner.type, optional: optional || inner.optional, note: description(def) };
    }
  }
  let type = (e.match(/^p\.(\w+)/) || [, 'any'])[1];
  if (type === 'Union') {
    const kinds = [...e.matchAll(/p\.(String|Number|Integer|Boolean|Array|Object|Literal|Null|Any)\b/g)].map(x => x[1]);
    type = [...new Set(kinds)].join('|') || 'any';
  }
  type = type.toLowerCase().replace('integer', 'number').replace('literal', 'string');
  return { type: [...new Set(type.split('|'))].join('|'), optional };
}

function objectParams(text) {
  const brace = text.indexOf('{');
  if (brace < 0) return [];
  const body = stripComments(balanced(text, brace).slice(1, -1));
  return splitTop(body).flatMap(part => {
    const k = part.match(/^(?:"([^"]+)"|([\w$]+)):\s*([\s\S]*)$/);
    if (!k) return [];
    const { type, optional, note } = typeOf(k[3]);
    return [{ name: k[1] || k[2], type, required: !optional && !/\bdefault:/.test(k[3]), description: description(k[3]) || note || '' }];
  });
}

function params(schema) {
  const def = definition(schema);
  if (!def) return { params: [] };
  if (/^p\.Object\(/.test(def)) return { params: objectParams(def) };
  if (/^p\.Union\(\s*\[/.test(def)) {
    const arr = balanced(def, def.indexOf('['));
    const variants = splitTop(stripComments(arr.slice(1, -1))).filter(v => /^p\.Object\(/.test(v)).map(objectParams);
    if (variants.length) {
      const merged = [];
      for (const v of variants) {
        for (const p of v) {
          if (!merged.some(x => x.name === p.name)) {
            merged.push({ ...p, required: variants.every(w => w.some(x => x.name === p.name && x.required)) });
          }
        }
      }
      return { params: merged, forms: variants.map(v => v.map(p => p.name)) };
    }
  }
  return { params: [] };
}

const CATEGORIES = [
  ['消息', n => /poke|_msg$|_msg_|forward|emoji_like|ptt_text|ark_share|ArkShare|inline_keyboard|_mark_all_as_read|mini_app_ark|^get_msg$|^send_msg$|^delete_msg$/.test(n)
    && !/essence|system_msg|online_file|qzone|flash/.test(n)],
  ['好友和进群申请', n => /add_request|system_msg|ignored_notifies|ignore_add_request|doubt_friends/.test(n)],
  ['群文件', n => /group_file|group_folder|group_root_files|files_by_folder/.test(n)],
  ['群相册', n => /album/.test(n)],
  ['群管理和群信息', n => /group|todo|essence|sign/.test(n) && !/ai_record/.test(n)],
  ['好友和用户', n => /friend|stranger|like|recent_contact|user_status/.test(n)],
  ['自己的资料和在线状态', n => /qq_profile|qq_avatar|longnick|online_status|input_status|model_show|online_clients/.test(n)],
  ['图片、语音和文件', n => /^get_image$|^get_record$|^get_file$|private_file|^download_file$|^can_send/.test(n)],
  ['流式传输', n => /stream/.test(n)],
  ['闪传和在线文件', n => /flash|fileset|online_file|online_folder|share_link/.test(n)],
  ['表情包和收藏', n => /custom_face|collection/.test(n)],
  ['AI 语音', n => /^get_ai_|ai_record/.test(n)],
  ['QQ 空间、频道和其他', n => /qzone|guild|ocr|translate/.test(n)],
  ['账号和系统', () => true],
];
const ONEBOT11 = new Set(['send_private_msg', 'send_group_msg', 'send_msg', 'delete_msg', 'get_msg', 'get_forward_msg', 'send_like', 'set_group_kick',
  'set_group_ban', 'set_group_anonymous_ban', 'set_group_whole_ban', 'set_group_admin', 'set_group_anonymous', 'set_group_card', 'set_group_name',
  'set_group_leave', 'set_group_special_title', 'set_friend_add_request', 'set_group_add_request', 'get_login_info', 'get_stranger_info',
  'get_friend_list', 'get_group_info', 'get_group_list', 'get_group_member_info', 'get_group_member_list', 'get_group_honor_info', 'get_cookies',
  'get_csrf_token', 'get_credentials', 'get_record', 'get_image', 'can_send_image', 'can_send_record', 'get_status', 'get_version_info',
  'set_restart', 'clean_cache']);

const { names, sections } = enumTable();
const classes = classTable();
const field = (cls, key, seen = new Set()) => {
  const c = classes[cls];
  if (!c || seen.has(cls)) return undefined;
  seen.add(cls);
  return c[key] !== undefined ? c[key] : field(c.parent, key, seen);
};

const actions = [];
for (const cls of registered()) {
  const raw = field(cls, 'actionName') ?? '';
  const ref = raw.match(/^re\.([\w$]+)$/);
  const name = ref ? names[ref[1]] : literal(raw);
  if (!name || actions.some(a => a.name === name)) continue;
  const summary = literal(field(cls, 'actionSummary'));
  let detail = literal(field(cls, 'actionDescription'));
  if (detail.includes('## ') || detail === summary) detail = '';
  const { params: list, forms } = params(field(cls, 'payloadSchema'));
  if (forms && forms.length > 1) {
    detail = (detail ? detail + '。' : '') + '参数有 ' + forms.length + ' 种写法：' + forms.map((f, i) => '写法 ' + (i + 1) + ' 用 ' + f.join('、')).join('；');
  }
  actions.push({
    name,
    summary: summary || name,
    description: detail,
    category: CATEGORIES.find(([, test]) => test(name))[0],
    origin: ONEBOT11.has(name) ? 'OneBot 11' : sections[name] === 'gocq' ? 'go-cqhttp' : 'NapCat',
    params: list,
  });
}

const version = (src.match(/"(\d+\.\d+\.\d+)" \|\| "1\.0\.0-dev"/) || [, ''])[1];
fs.writeFileSync(output, JSON.stringify({ version, categories: CATEGORIES.map(c => c[0]), actions }, null, 1) + '\n');
console.log(`NapCat ${version}: ${actions.length} 个接口 -> ${output}`);
