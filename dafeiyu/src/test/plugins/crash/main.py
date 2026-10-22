import os
import sys

from feiyu_plugin import Plugin

p = Plugin()


@p.on_init
def started():
    if p.settings.get("die_on_start"):
        print("crash plugin: dying on start as configured", file=sys.stderr, flush=True)
        os._exit(1)


@p.command("die")
def die(ctx):
    print("crash plugin: bye", file=sys.stderr, flush=True)
    os._exit(3)


@p.tool("die", "立刻退出", {})
def die_tool():
    os._exit(4)


p.run()
