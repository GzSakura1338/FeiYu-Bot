import datetime

from feiyu_plugin import Plugin, RpcError

p = Plugin()
WEEKDAYS = "一二三四五六日"


def message():
    today = datetime.date.today()
    weekday = today.weekday()
    weekend = "今天就是周末！" if weekday >= 5 else f"离周末还有 {5 - weekday} 天"
    return f"☀ {today.month} 月 {today.day} 日 星期{WEEKDAYS[weekday]}，{weekend}\n{p.settings.get('greeting', '')}".strip()


@p.schedule("morning")
def morning():
    for group in p.settings.get("group_ids", []):
        try:
            p.send(group_id=group, text=message())
        except RpcError as e:
            p.log(f"发到群 {group} 失败：{e.message}", "warn")


p.run()
