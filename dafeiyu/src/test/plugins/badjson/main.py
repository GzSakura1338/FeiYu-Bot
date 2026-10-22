from feiyu_plugin import Plugin

p = Plugin()


def raw(data):
    with p._lock:
        p._out.write(data)
        p._out.flush()


@p.tool("garbage", "写一堆坏数据", {})
def garbage():
    raw(b"hello, this is not json\n")
    raw(b'{"jsonrpc": "2.0", "id": \n')
    raw(b"[1, 2, 3]\n")
    raw(b'{"jsonrpc": "2.0", "id": 999999, "result": "unknown id"}\n')
    raw(b'{"jsonrpc": "2.0", "id": 999999, "result": "duplicate unknown id"}\n')
    raw(b'{"weird": true}\n')
    raw(b"a" * (11 * 1024 * 1024) + b"\n")
    return "garbage written"


@p.tool("ok", "正常返回", {})
def ok():
    return "still fine"


p.run()
