from feiyu_plugin import Plugin

p = Plugin()


def make(i):
    @p.tool(f"d{i}", ("很长的说明" * 300) if i == 0 else f"动态工具 {i}", {})
    def tool():
        return f"d{i}"
    return tool


for index in range(30):
    make(index)


@p.prompt
def prompt(session, people):
    return "补" * 1000


p.run()
