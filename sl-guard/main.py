import re

from feiyu_plugin import Plugin

p = Plugin()

STATUS_REPLY = re.compile(r"SnowLuma\s*状态\s+版本\s*[:：]", re.IGNORECASE)


def triggers():
    words = p.settings.get("triggers") or ["#sl"]
    return {w.strip().lower() for w in words if isinstance(w, str) and w.strip()}


@p.intercept
def guard(ctx):
    text = (ctx.text or "").strip()
    if text.lower() in triggers():
        return "hide"
    if p.settings.get("hideReplies", True) and STATUS_REPLY.match(text):
        return "hide"
    return "pass"


p.run()
