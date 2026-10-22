from feiyu_plugin import Plugin

p = Plugin()


@p.web
def page_api(method, path, query, body):
    p.kv_set("hits", (p.kv_get("hits", 0) or 0) + 1)
    return {"method": method, "path": path, "query": query, "body": body}


p.run()
