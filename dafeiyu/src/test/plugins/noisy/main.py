import sys
import time

from feiyu_plugin import Plugin

p = Plugin()


@p.tool("spam", "刷 3 秒日志", {})
def spam():
    end = time.time() + 3
    n = 0
    while time.time() < end:
        for _ in range(100):
            print(f"noise line {n}", file=sys.stderr)
            p.log(f"log call {n}")
            n += 1
        sys.stderr.flush()
        time.sleep(0.05)
    return f"wrote {n} lines"


p.run()
