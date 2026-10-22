import urllib.request
import xml.etree.ElementTree as ET

from feiyu_plugin import Plugin, RpcError

p = Plugin()
ATOM = "{http://www.w3.org/2005/Atom}"


def entries(url):
    with urllib.request.urlopen(url, timeout=15) as response:
        root = ET.fromstring(response.read())
    items = []
    for item in root.iter("item"):
        items.append((item.findtext("guid") or item.findtext("link") or "", item.findtext("title") or "", item.findtext("link") or ""))
    for entry in root.iter(ATOM + "entry"):
        link = entry.find(ATOM + "link")
        href = link.get("href", "") if link is not None else ""
        items.append((entry.findtext(ATOM + "id") or href, entry.findtext(ATOM + "title") or "", href))
    return items


def check():
    sent = 0
    for url in p.settings.get("feeds", []):
        key = "seen:" + url
        seen = set(p.kv_get(key, []))
        first = not seen
        try:
            items = entries(url)
        except Exception as e:
            p.log(f"读不到 {url}：{e}", "warn")
            continue
        fresh = [it for it in items if it[0] and it[0] not in seen]
        if not first:
            for _, title, link in fresh[:3]:
                for group in p.settings.get("group_ids", []):
                    try:
                        p.send(group_id=group, text=f"📰 {title.strip()}\n{link.strip()}")
                        sent += 1
                    except RpcError as e:
                        p.log(f"推送到群 {group} 失败：{e.message}", "warn")
        seen.update(it[0] for it in items if it[0])
        p.kv_set(key, sorted(seen)[-500:])
    return sent


@p.schedule("check")
def scheduled():
    check()


@p.command("rss")
def now(ctx):
    sent = check()
    ctx.reply(f"检查完了，推送了 {sent} 条新文章" if sent else "检查完了，没有新文章（第一次检查只记下已有的文章，不推送）")


p.run()
