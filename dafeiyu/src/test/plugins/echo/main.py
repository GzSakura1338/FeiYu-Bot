import time

from feiyu_plugin import Plugin

p = Plugin()
events = []


@p.command("echo")
def echo(ctx):
    ctx.reply(ctx.args or "(空)")


@p.command("groupecho")
def group_echo(ctx):
    ctx.reply("group:" + ctx.args)


@p.command("pass")
def passing(ctx):
    return False


@p.on("*")
def record(ctx):
    events.append(ctx.data)
    p.kv_set("last_event", ctx.data)
    p.kv_set("event_count", len(events))


@p.tool("echo", "原样返回 text", {"text": {"type": "string"}}, mind=True)
def tool_echo(text):
    return text


@p.tool("add", "两数相加", {"a": {"type": "integer"}, "b": {"type": "integer"}})
def add(a, b):
    return str(a + b)


@p.tool("big", "返回 1 MB 文本", {})
def big():
    return "x" * (1024 * 1024)


@p.tool("settings", "返回当前设置", {})
def settings():
    return p.settings


@p.tool("sleep_ms", "睡一会儿再返回", {"ms": {"type": "integer"}})
def sleep_ms(ms):
    time.sleep(ms / 1000)
    return f"slept {ms}"


@p.prompt
def prompt(session, people):
    return "回声插件的提示词补充：" + session


@p.on_settings
def changed(values):
    p.kv_set("settings_changed", values)


p.run()
