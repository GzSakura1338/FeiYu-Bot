# FeiYu Bot

自己写的 QQ 机器人，代码目录叫 `dafeiyu`。用大模型在 QQ 群聊和私聊里聊天：有人格、长期记忆和日记，有一直在自己想事情的「内心」，能用工具做事（读写文件、上网搜、跑命令、管群、处理好友和进群申请），也能装插件和 MCP 服务扩展。所有设置都在本机的网页控制台里管理。

- 后端 Java 25 + Gradle，依赖只有 Java-WebSocket、gson、sqlite-jdbc，数据存在 SQLite
- 网页控制台按 Material Design 3 设计：http://127.0.0.1:8090/
- 登录 QQ 交给 [SnowLuma](https://github.com/SnowLuma/SnowLuma)：它把钩子注入本机装的 QQNT，通过 OneBot v11 反向 WebSocket 把消息推给 FeiYu Bot（2026-10-26 起替换了原来的 NapCat）。SnowLuma 是源码可见、非商业许可，发行包不进本仓库

功能和代码结构的完整说明见 [dafeiyu/README.md](dafeiyu/README.md)，写插件看 [dafeiyu/插件开发.md](dafeiyu/插件开发.md)，在 Linux 服务器上部署看 [dafeiyu/Linux部署.md](dafeiyu/Linux部署.md)。

## 启动

1. 双击 `start-dafeiyu.bat`（`launch-dafeiyu.ps1` 是同一个，只是窗口最小化），打开 http://127.0.0.1:8090/
2. 第一次用：在「Agent」里添加模型、建人格（角色卡见 `personas/`）、填主人的 QQ 号，再打开 QQ 自动回复
3. 第一次用先装 SnowLuma：从 [Releases](https://github.com/SnowLuma/SnowLuma/releases) 下载 `SnowLuma-版本-win-x64.zip`（带 Node 的完整版），解压到 `snowluma/`；再把 `snowluma/config/onebot.json` 写成只有一个反向 WebSocket（`wsClients`：地址 `ws://127.0.0.1:6199/ws`，token 和控制台里的一样，`messageFormat` 填 `array`），`httpServers` 和 `wsServers` 留空，`statusCommand.enabled` 设成 `false`（不然群里有人发 `#sl` 它会自己回一条状态）；`snowluma/config/runtime.json` 写 `{"webuiHost": "127.0.0.1", "hookAutoLoad": false}`
4. 双击 `start-snowluma.bat`：它启动 SnowLuma（最小化的窗口），另开一个 QQ，并且只往这个 QQ 里注入钩子，你自己开着的 QQ 不会被接管。在新开的 QQ 窗口里登录机器人的号（扫码或者点快速登录），登录成功后脚本会记下 QQ 号（`snowluma/bot-qq.txt`）。下次再运行时，机器人的 QQ 还开着就直接接上，不会再开一个
   - 第一次运行会生成 SnowLuma 网页后台（http://127.0.0.1:5099）的密码，存在 `snowluma/webui-password.txt`。脚本提示要同意协议时，用这个密码登录后台，读完并同意 SnowLuma 的用户协议和隐私政策，脚本会自己接着往下走
   - 机器人的 QQ 窗口登录后可以最小化到托盘，别退出；退出了重新运行 `start-snowluma.bat` 就行
   - 接上以后 `start-snowluma.bat` 的窗口不会结束，每 15 秒看一眼：机器人的 QQ 自己重启（退出登录再登、被挤下线后重登、QQ 更新都会这样，新进程带 `--relaunch`，钩子不会跟过去）后，只要登录的还是机器人的号，就自动重新注入；SnowLuma 的网页后台连续 5 分钟没反应（卡死或者退出了），就把 SnowLuma 停掉重开，机器人的 QQ 不动，钩子会自己接回来。关掉这个窗口只是不再自动处理这些，QQ 重启后要自己再运行一次。`-NoWatch` 参数可以只接一次就退出
   - SnowLuma 跑在标题为「SnowLuma」的 PowerShell 窗口里（`snowluma-console.ps1`），这个窗口关掉了快速编辑模式：以前在窗口里点一下就会进入选择文本状态，SnowLuma 往窗口输出时被挂住，整个停下不动（2026-10-28 出过一次，机器人断了 50 分钟）
5. 控制台概览页显示「SnowLuma 已连接」就好了

关闭：关掉 FeiYu Bot 和 SnowLuma 的命令行窗口；机器人的 QQ 在托盘里退出。改了 Java 代码要重启 FeiYu Bot，改前端只要刷新浏览器。

### Linux 和 macOS

FeiYu Bot 本身跨平台，需要 JDK 25（`java -version` 能看到 25 就行）：

1. 执行 `./start-dafeiyu.sh`，打开 http://127.0.0.1:8090/
2. SnowLuma 用它的 Linux 版（x64 / arm64）或 Docker 版（`start-snowluma.bat` 只能在 Windows 上用），在 SnowLuma 的 OneBot 设置里加一个反向 WebSocket，地址 `ws://127.0.0.1:6199/ws`，token 和控制台里填的一样。Docker 版要用主机网络、加 `--cap-add SYS_PTRACE --security-opt seccomp=unconfined`，并把工作目录按相同路径挂进容器，见 [dafeiyu/Linux部署.md](dafeiyu/Linux部署.md)。SnowLuma 没有 macOS 版，macOS 上要换一个 OneBot v11 实现
3. 想让她跑命令（「工作目录 › 命令行执行」），Linux 要先装 bubblewrap，要沙盒联网再装 passt：`sudo apt install bubblewrap passt`（Fedora 用 dnf，Arch 用 pacman）；macOS 自带 sandbox-exec，不用装。各系统沙盒的区别见 [dafeiyu/README.md](dafeiyu/README.md#多平台和命令沙盒)

Linux 和 macOS 上的命令沙盒和 Windows 不完全一样：联网时本机连不上，但挡不住局域网；内存和进程数上限 Linux 要靠 systemd（`systemd-run`），macOS 和没有 systemd 的环境只能降优先级。

在云服务器上部署（国内镜像、时区、SnowLuma Docker 怎么配、怎么查日志和用量）看 [dafeiyu/Linux部署.md](dafeiyu/Linux部署.md)。

## 目录

| 路径 | 内容 | 进仓库 |
| --- | --- | --- |
| `dafeiyu/` | FeiYu Bot 本体：后端、网页、插件 SDK、说明文档 | 是（`data/`、`config.properties`、`build/` 除外） |
| `personas/` | 人格角色卡，正文复制到控制台「人格」里用 | 是 |
| `start-*.bat`、`launch-*.ps1`、`snowluma-console.ps1`、`start-dafeiyu.sh` | 启动脚本（`.sh` 给 Linux 和 macOS） | 是 |
| `snowluma/` | SnowLuma v2.2.0 Windows 完整版，加上 `config/`、WebUI 密码和机器人 QQ 号 | 否 |
| `napcat/` | 换掉之前用的 NapCatQQ v4.18.41，已经不用 | 否 |
| `file/` | 她的工作目录：写的网页、画的图、跑的命令都在这里 | 否 |

## 连接和配置

- SnowLuma 主动连 `ws://127.0.0.1:6199/ws`。token 存在 `.ws_token`，SnowLuma 那边写在 `snowluma/config/onebot.json`（所有账号共用；登录后它会再建一个 `onebot_<QQ号>.json`，只放和共用配置不同的部分）
- 换 QQ 端后消息编号也换了：换之前收到的消息不能再引用回复、撤回或贴表情，之后收到的不受影响
- FeiYu Bot 的端口、前端目录、数据库位置，以及反向 WebSocket 的初始值在 `dafeiyu/config.properties`；其余设置（模型和 API Key、人格、工作目录、反向 WebSocket、插件……）都在网页上改，存在 `dafeiyu/data/dafeiyu.db`
- 升级表结构前会自动备份数据库到 `dafeiyu/data/backup/`；这个目录写不进去时改存到系统临时目录，再不行就跳过备份照常启动，日志里会写明原因

## 注意

- 同一个 QQ 号同一时间只能有一个电脑端在线，机器人最好用小号；机器人的号在 SnowLuma 接管的那个 QQ 里登录，别在自己的 QQ 里再登
- `#sl` 状态指令已禁用：SnowLuma 的 `statusCommand.enabled` 设成了 `false`（别在它的网页后台重新打开），群里有人发 `#sl` 她的号不会回状态；不让 `#sl` 和别的机器人回的「SnowLuma 状态」进会话、把她叫出来接话，靠的是插件「#sl 防召唤」：它在本仓库的 `plugin` 分支（`sl-guard/`），在控制台「插件 › 已安装 › 安装插件」里填 `https://github.com/GzSakura1338/FeiYu-Bot/archive/refs/heads/plugin.zip` 装上并打开。细节见 [dafeiyu/README.md](dafeiyu/README.md) 的群聊部分和插件里的 README
- SnowLuma 需要和 QQ 用同一个 Windows 账号运行；QQ 升级后钩子可能对不上，SnowLuma 也要跟着升级（版本兼容表在它仓库的 `compat/qq.json`）
- 控制台（8090）、反向 WebSocket（6199）和 SnowLuma 网页后台（5099）都只监听 127.0.0.1
- `dafeiyu/data/` 里有 API Key 和全部聊天记录，不进仓库，也别发给别人
