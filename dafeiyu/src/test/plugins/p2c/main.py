import time

from feiyu_plugin import Plugin

p = Plugin()


@p.on("message.private")
def slow(ctx):
    time.sleep(10)
    p.kv_set("done", ctx.text)


p.run()
