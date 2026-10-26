import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const version = (process.argv[2] ?? '2.2.0').replace(/^v/, '');
const input = process.argv[3];
const output = path.join(here, '../src/main/resources/snowluma-api.json');
const url = `https://raw.githubusercontent.com/SnowLuma/SnowLuma/v${version}/packages/mcp/src/generated/catalog.json`;

const text = input ? fs.readFileSync(path.resolve(input), 'utf8') : await (await fetch(url)).text();
const catalog = JSON.parse(text);

const CATEGORIES = [
  ['消息', n => /poke|_msg$|_msg_|forward|foward|emoji_like|reaction|ptt_text|(^|_)ark(_|$)|share_peer|share_group|inline_keyboard|^get_msg$|^send_msg$|^delete_msg$/.test(n)
    && !/essence|system_msg|qzone|flash|todo/.test(n)],
  ['好友和进群申请', n => /add_request|system_msg|ignored_notifies|doubt_friends/.test(n)],
  ['群文件', n => /group_file|group_folder|group_root_files|files_by_folder/.test(n)],
  ['群相册', n => /album/.test(n)],
  ['QQ 空间和其他', n => /qzone|ocr|translate|word_slices/.test(n)],
  ['群管理和群信息', n => /group|todo|essence|sign/.test(n) && !/ai_record/.test(n)],
  ['好友和用户', n => /friend|stranger|like|recent_contact|user_status/.test(n)],
  ['自己的资料和在线状态', n => /qq_profile|qq_avatar|longnick|online_status|input_status|model_show|online_clients/.test(n)],
  ['图片、语音和文件', n => /^get_image$|^get_record$|^get_file$|private_file|^download_file$|^can_send/.test(n)],
  ['流式传输', n => /stream/.test(n)],
  ['闪传', n => /flash|fileset|share_link/.test(n)],
  ['表情包和收藏', n => /custom_face|collection|sys_faces|face_entity|super_face/.test(n)],
  ['AI 语音', n => /^get_ai_|ai_record/.test(n)],
  ['账号和系统', () => true],
];
const ONEBOT11 = new Set(['send_private_msg', 'send_group_msg', 'send_msg', 'delete_msg', 'get_msg', 'get_forward_msg', 'send_like', 'set_group_kick',
  'set_group_ban', 'set_group_anonymous_ban', 'set_group_whole_ban', 'set_group_admin', 'set_group_anonymous', 'set_group_card', 'set_group_name',
  'set_group_leave', 'set_group_special_title', 'set_friend_add_request', 'set_group_add_request', 'get_login_info', 'get_stranger_info',
  'get_friend_list', 'get_group_info', 'get_group_list', 'get_group_member_info', 'get_group_member_list', 'get_group_honor_info', 'get_cookies',
  'get_csrf_token', 'get_credentials', 'get_record', 'get_image', 'can_send_image', 'can_send_record', 'get_status', 'get_version_info',
  'set_restart', 'clean_cache']);
const TYPES = { uint: 'number', int: 'number', number: 'number', messageId: 'number', bool: 'boolean', 'string[]': 'array', 'uint[]': 'array',
  message: '消息段数组或文本', raw: 'any', enum: 'string' };
const ALIASES = { upload_foward_msg: 'upload_forward_msg' };

const actions = [];
for (const a of catalog.actions) {
  if (ALIASES[a.name]) continue;
  const aliases = [...a.aliases, ...Object.keys(ALIASES).filter(k => ALIASES[k] === a.name)];
  const params = a.params.map(p => ({
    name: p.name,
    type: TYPES[p.type] ?? p.type,
    required: p.required,
    description: [p.desc, p.values ? '可选值 ' + p.values.join(' / ') : '', p.default !== undefined && p.default !== '' && !p.required ?'默认 ' + JSON.stringify(p.default) : '']
      .filter(Boolean).join('；'),
  }));
  actions.push({
    name: a.name,
    aliases,
    summary: a.summary || a.name,
    description: a.returns ? '返回：' + a.returns : '',
    category: CATEGORIES.find(([, test]) => test(a.name))[0],
    origin: ONEBOT11.has(a.name) ? 'OneBot 11' : 'SnowLuma',
    params,
  });
}

fs.writeFileSync(output, JSON.stringify({ version, categories: CATEGORIES.map(c => c[0]), actions }, null, 1) + '\n');
console.log(`SnowLuma ${version}: ${actions.length} 个接口 -> ${output}`);
