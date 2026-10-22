from feiyu_plugin import Plugin

p = Plugin()


@p.on("notice.join")
def joined(ctx):
    if ctx.session.startswith("group:"):
        ctx.reply(p.settings.get("welcome", "欢迎新朋友 {qq} 进群～").replace("{qq}", str(ctx.user_id)))


@p.on("notice.recall")
def recalled(ctx):
    if p.settings.get("recall_joke") and ctx.session.startswith("group:"):
        ctx.reply("咦，刚刚是不是有人撤回了什么？我看到了哦～")


p.run()
