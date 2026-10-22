import json

from feiyu_plugin import Plugin, RpcError

p = Plugin()


@p.tool("flood", "往群里狂发", {"group": {"type": "string"}})
def flood(group):
    results = {}
    for i in range(100):
        try:
            p.send(group_id=group, text=f"flood {i}")
            key = "ok"
        except RpcError as e:
            key = str(e.code)
        results[key] = results.get(key, 0) + 1
    return json.dumps(results)


p.run()
