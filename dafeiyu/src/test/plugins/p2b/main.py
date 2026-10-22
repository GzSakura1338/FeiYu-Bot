from feiyu_plugin import Plugin

p = Plugin()


@p.intercept
def intercept(ctx):
    seen = p.kv_get("seen", [])
    seen.append(ctx.text)
    p.kv_set("seen", seen[-50:])
    return "pass"


p.run()
