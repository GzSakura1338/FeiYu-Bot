# FeiYu Bot

自己写的 QQ 机器人，代码目录叫 `dafeiyu`。用大模型在 QQ 群聊和私聊里聊天：有人格、长期记忆和日记，有一直在自己想事情的「内心」，能用工具做事（读写文件、上网搜、跑命令、管群、处理好友和进群申请），也能装插件和 MCP 服务扩展。所有设置都在本机的网页控制台里管理。

- 后端 Java 25 + Gradle，依赖只有 Java-WebSocket、gson、sqlite-jdbc，数据存在 SQLite
- 网页控制台按 Material Design 3 设计：http://127.0.0.1:8090/
- 登录 QQ 交给 [NapCatQQ](https://github.com/NapNeko/NapCatQQ)：它用本机装的 QQNT 登录账号，通过 OneBot v11 反向 WebSocket 把消息推给 FeiYu Bot

功能和代码结构的完整说明见 [dafeiyu/README.md](dafeiyu/README.md)，写插件看 [dafeiyu/插件开发.md](dafeiyu/插件开发.md)，在 Linux 服务器上部署看 [dafeiyu/Linux部署.md](dafeiyu/Linux部署.md)。

## 启动

1. 双击 `start-dafeiyu.bat`（`launch-dafeiyu.ps1` 是同一个，只是窗口最小化），打开 http://127.0.0.1:8090/
2. 第一次用：在「Agent」里添加模型、建人格（角色卡见 `personas/`）、填主人的 QQ 号，再打开 QQ 自动回复
3. 双击 `start-napcat.bat`，用手机 QQ 扫窗口里的二维码（也在 `napcat/cache/qrcode.png`）。以后可以用 `start-napcat.bat -q QQ号` 免扫码登录
4. 控制台概览页显示已连接就好了

关闭：直接关掉对应的命令行窗口。改了 Java 代码要重启 FeiYu Bot，改前端只要刷新浏览器。

### Linux 和 macOS

FeiYu Bot 本身跨平台，需要 JDK 25（`java -version` 能看到 25 就行）：

1. 执行 `./start-dafeiyu.sh`，打开 http://127.0.0.1:8090/
2. NapCat 用它官方的 Linux 安装脚本或 Docker 版（仓库里的 `napcat/` 和 `start-napcat.bat` 只能在 Windows 上用），在 NapCat 的 OneBot 设置里加一个反向 WebSocket，地址 `ws://127.0.0.1:6199/ws`，token 和控制台里填的一样。Docker 版记得加 `-e ACCOUNT=<QQ号>`（不然每次重启都要扫码），并把工作目录按相同路径挂进容器
3. 想让她跑命令（「工作目录 › 命令行执行」），Linux 要先装 bubblewrap，要沙盒联网再装 passt：`sudo apt install bubblewrap passt`（Fedora 用 dnf，Arch 用 pacman）；macOS 自带 sandbox-exec，不用装。各系统沙盒的区别见 [dafeiyu/README.md](dafeiyu/README.md#多平台和命令沙盒)

Linux 和 macOS 上的命令沙盒和 Windows 不完全一样：联网时本机连不上，但挡不住局域网；内存和进程数上限 Linux 要靠 systemd（`systemd-run`），macOS 和没有 systemd 的环境只能降优先级。

在云服务器上部署（国内镜像、时区、NapCat Docker 怎么配、怎么查日志和用量）看 [dafeiyu/Linux部署.md](dafeiyu/Linux部署.md)。

## 目录

| 路径 | 内容 | 进仓库 |
| --- | --- | --- |
| `dafeiyu/` | FeiYu Bot 本体：后端、网页、插件 SDK、说明文档 | 是（`data/`、`config.properties`、`build/` 除外） |
| `personas/` | 人格角色卡，正文复制到控制台「人格」里用 | 是 |
| `start-*.bat`、`launch-*.ps1`、`start-dafeiyu.sh` | 启动脚本（`.sh` 给 Linux 和 macOS） | 是 |
| `napcat/` | NapCatQQ v4.18.41 Shell 版 | 否 |
| `file/` | 她的工作目录：写的网页、画的图、跑的命令都在这里 | 否 |

## 连接和配置

- NapCat 主动连 `ws://127.0.0.1:6199/ws`。token 存在 `.ws_token`，NapCat 那边写在 `napcat/config/onebot11.json`（新账号用的模板）和 `onebot11_<QQ号>.json`（已登录账号）
- FeiYu Bot 的端口、前端目录、数据库位置，以及反向 WebSocket 的初始值在 `dafeiyu/config.properties`；其余设置（模型和 API Key、人格、工作目录、反向 WebSocket、插件……）都在网页上改，存在 `dafeiyu/data/dafeiyu.db`
- 升级表结构前会自动备份数据库到 `dafeiyu/data/backup/`；这个目录写不进去时改存到系统临时目录，再不行就跳过备份照常启动，日志里会写明原因

## 注意

- 同一个 QQ 号同一时间只能有一个电脑端在线：NapCat 登录后会把桌面 QQ 上的同一账号挤下线，机器人最好用小号
- 控制台（8090）和反向 WebSocket（6199）只监听 127.0.0.1。NapCat 的管理面板（6099）默认监听所有网卡，局域网里能打开（要 token 登录）；只想本机访问，就把 `napcat/config/webui.json` 的 `host` 改成 `127.0.0.1`
- `dafeiyu/data/` 里有 API Key 和全部聊天记录，不进仓库，也别发给别人
