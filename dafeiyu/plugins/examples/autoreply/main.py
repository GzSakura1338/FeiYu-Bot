from feiyu_plugin import Plugin

p = Plugin()


def rules():
    for rule in p.settings.get("rules", []):
        if "=" in rule:
            keyword, reply = rule.split("=", 1)
            if keyword.strip() and reply.strip():
                yield keyword.strip(), reply.strip()


@p.intercept
def on_message(ctx):
    text = ctx.text.strip()
    for keyword, reply in rules():
        hit = text == keyword if p.settings.get("exact") else keyword in text
        if hit:
            ctx.reply(reply)
            return "drop"
    return "pass"


p.run()
