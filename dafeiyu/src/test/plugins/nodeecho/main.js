const { Plugin } = require('feiyu_plugin');

const p = new Plugin();

p.command('necho', ctx => ctx.reply(ctx.args || '(空)'));
p.command('npass', () => false);
p.on('message.group', async ctx => {
  await p.kvSet('last', ctx.text);
});
p.tool('echo', '原样返回 text', { text: { type: 'string' } }, args => args.text);
p.tool('add', '两数相加', { a: { type: 'integer' }, b: { type: 'integer' } }, args => String(args.a + args.b));
p.tool('big', '返回 1 MB 文本', {}, () => 'x'.repeat(1024 * 1024));

p.run();
