import time

from feiyu_plugin import Plugin, RpcError

p = Plugin()


def delay():
    return float(p.settings.get("delay", 10))


@p.command("slow")
def slow(ctx):
    time.sleep(delay())
    try:
        ctx.reply("late reply")
        p.kv_set("late_reply", "sent")
    except RpcError as e:
        p.kv_set("late_reply", "rejected: " + e.message)


@p.intercept
def intercept(ctx):
    time.sleep(delay())
    return "drop"


@p.tool("wait", "睡 seconds 秒", {"seconds": {"type": "number"}})
def wait(seconds):
    time.sleep(seconds)
    return "done"


@p.prompt
def prompt(session, people):
    time.sleep(delay())
    return "too late"


@p.filter
def filter_text(session, text):
    time.sleep(delay())
    return "drop"


p.run()
