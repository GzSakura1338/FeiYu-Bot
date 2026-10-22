import datetime
import hashlib

from feiyu_plugin import Plugin

p = Plugin()


@p.command("今日人品")
def jrrp(ctx):
    seed = f"{ctx.user_id}{datetime.date.today()}".encode()
    score = int(hashlib.md5(seed).hexdigest(), 16) % 101
    text = p.settings.get("lucky_text", "今天无敌了！") if score == 100 else f"{ctx.sender}，你今天的人品是 {score}"
    ctx.reply(text)


p.run()
