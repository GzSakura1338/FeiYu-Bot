# FeiYu Bot

- `napcat/`：[NapCatQQ](https://github.com/NapNeko/NapCatQQ) v4.18.41 Shell 版，用本机已装的 QQNT 登录 QQ 账号，对外提供 OneBot v11 协议。
- `dafeiyu/`：**FeiYu Bot**，自己实现的机器人控制台（Java 25 + Gradle，MD3 网页界面），当前在用。说明见 `dafeiyu/README.md`。
- `astrbot/`：[AstrBot](https://github.com/AstrBotDevs/AstrBot) v4.29.1，之前试用的现成框架（带 LLM），已停用但保留。

连接方式：NapCat 用反向 WebSocket 主动连到 `ws://127.0.0.1:6199/ws`，token 存放在 `.ws_token`，写在
`napcat/config/onebot11.json`（所有账号共用的默认模板）里，大肥鱼的 `dafeiyu/config.properties` 和 AstrBot 的配置用的是同一个 token。
大肥鱼和 AstrBot 都占用 6199 端口，同一时间只能开一个。

## 启动

1. 双击 `start-dafeiyu.bat`，打开控制台：http://127.0.0.1:8090/
2. 双击 `start-napcat.bat`，用手机 QQ 扫窗口里的二维码，或者扫 `napcat/cache/qrcode.png`。
   - 窗口里会列出本机登录过的账号，以后可以用 `start-napcat.bat -q QQ号` 快速登录，免扫码。
   - NapCat 的管理面板地址：http://127.0.0.1:6099/webui ，token 见 NapCat 窗口日志。

## 改回 AstrBot（可选）

先关掉大肥鱼，再双击 `start-astrbot.bat`，WebUI 在 http://127.0.0.1:6185 。
初始账号 `astrbot`，初始密码在 `astrbot_first.log` 里（搜 “Initial password”）。模型已配置为 GINKA 的 `deepseek-v4.1-flash`。

## 注意

- 同一个 QQ 号同一时间只能在一台“电脑端”在线：NapCat 登录后会把桌面 QQ 上的同一账号挤下线，建议机器人用小号。
- 所有服务都只监听 127.0.0.1，只能从本机访问。
- 关闭：直接关掉对应的命令行窗口即可。
