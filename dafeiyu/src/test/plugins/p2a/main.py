import json
import threading

from feiyu_plugin import Plugin, RpcError

p = Plugin()
lock = threading.Lock()


def record(kind, data):
    with lock:
        seen = p.kv_get("events", [])
        seen.append({"kind": kind, "data": data})
        p.kv_set("events", seen[-80:])


@p.intercept
def intercept(ctx):
    record("intercept", ctx.data)
    if ctx.text.startswith("drop"):
        return "drop"
    if ctx.text.startswith("rw"):
        return ("rewrite", "改写后:" + ctx.text)
    return "pass"


@p.on("*")
def observe(ctx):
    record(ctx.type, ctx.data)


@p.filter
def outgoing(session, text):
    if "拦我" in text:
        return "drop"
    if "改红线" in text:
        return ("rewrite", "改写成了红线测试词")
    if "改我" in text:
        return ("rewrite", "改好了")
    return "pass"


@p.schedule("tick")
def tick():
    record("schedule", {"id": "tick"})
    try:
        p.send(group_id="900001", text="定时任务触发了")
    except RpcError as e:
        p.log("tick send failed: " + e.message, "warn")


def attempt(fn):
    try:
        return json.dumps(fn(), ensure_ascii=False)
    except RpcError as e:
        return f"error {e.code}: {e.message}"


@p.tool("send", "发群消息", {"group": {"type": "string"}, "text": {"type": "string"}})
def send(group, text):
    return attempt(lambda: p.send(group_id=group, text=text))


@p.tool("history", "读记录", {"group": {"type": "string"}, "user": {"type": "string"}}, required=[])
def history(group=None, user=None):
    return attempt(lambda: p.history(group_id=group, user_id=user))


@p.tool("members", "读群成员", {"group": {"type": "string"}})
def members(group):
    return attempt(lambda: p.members(group))


p.run()
