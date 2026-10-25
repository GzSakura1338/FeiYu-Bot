# 在 Linux 服务器上部署 FeiYu Bot

按 2026-10-25 一位用户在 Ubuntu 24.04（4 核 8 GB，NapCat 用 Docker 版）上完整部署、跑了一天后反馈的问题整理。命令以 Debian / Ubuntu 为例，其他发行版换成对应的包管理器。

## 1. 部署前

- **自动更新**：新开的云主机开机就会跑 `unattended-upgrades`，占着 dpkg 锁，`apt install` 会失败。部署前先停掉：`sudo systemctl stop unattended-upgrades && sudo systemctl disable unattended-upgrades`（部署完可以再打开）
- **时区**：云主机默认常是 UTC，她会把下午两点说成“大早上”。二选一：
  - 改系统时区：`sudo timedatectl set-timezone Asia/Shanghai`
  - 或者只改 FeiYu Bot：在 `dafeiyu/config.properties` 里加一行 `time.zone=Asia/Shanghai`，启动日志会打印 `time zone Asia/Shanghai`
- **目录属主**：用 systemd 的 `User=` 以普通账号跑时，`dafeiyu/data/` 整个目录（包括 `data/backup/`、`data/logs/`）的属主要是这个账号：`chown -R feiyu:feiyu dafeiyu/data`。`data/backup` 写不进去时，升级前的数据库备份会改存到系统临时目录，日志里有一行 `database backup folder unusable`，不会再导致启动失败
- **数据盘**：云模板经常给一块已经分好区的数据盘（比如 `vdb` 下面有 `vdb1`），`lsblk -d` 只列整块盘会漏掉分区。找要挂的分区用 `lsblk -f`，按 FSTYPE 和挂载点判断

## 2. 装依赖

- **JDK 25**：Adoptium 官方源在国内很慢（实测 15 KB/s），用清华镜像 `https://mirrors.tuna.tsinghua.edu.cn/Adoptium/25/jdk/x64/linux/` 下载 Temurin 25 解压到 `/opt/jdk-25`，把 `/opt/jdk-25/bin` 加进 PATH
- **Gradle**：`services.gradle.org` 在国内经常连不上。改 `dafeiyu/gradle/wrapper/gradle-wrapper.properties`：`distributionUrl` 换成腾讯镜像 `https://mirrors.cloud.tencent.com/gradle/gradle-<版本>-bin.zip`（版本照原来那行），再加一行 `networkTimeout=60000`
- **代码**：`git clone` 直连 GitHub 也常超时（实测 136 秒）。部署脚本里 clone 失败就换镜像重试；更新时 `git fetch` 失败就继续用现有代码，别让 `set -e` 把整个部署中断
- **命令沙盒**：`sudo apt install bubblewrap passt`。bubblewrap 是沙盒本身，passt 里的 pasta 给联网的沙盒单独一张网卡（没装时沙盒只能断网跑）。有 systemd 用户会话时会自动用 `systemd-run` 限内存和进程数
- **看视频**：`sudo apt install ffmpeg`，装好后在「设置 › Agent › 看视频」里能看到找到的路径

## 3. NapCat（Docker 版）

```bash
docker run -d --name napcat --restart unless-stopped --network host \
  -e ACCOUNT=<机器人的 QQ 号> \
  -v /data/napcat/config:/app/napcat/config \
  -v /data/napcat/qq:/app/.config/QQ \
  -v /data/feiyu/file:/data/feiyu/file:ro \
  mlikiowa/napcat-docker:latest
```

- **`-e ACCOUNT` 一定要写**：镜像的启动脚本只认这个环境变量，命令行里的 `-q <QQ号>` 会被忽略。不写的话每次 `docker restart napcat` 都要重新扫码
- **把工作目录按相同路径挂进去**（上面最后一个 `-v`，只读就够）：FeiYu Bot 发图、发文件时把本机的绝对路径交给 NapCat，容器里没有这个路径，NapCat 会报“识别URL失败”。没挂也能用：第一次发失败后 FeiYu Bot 会自动改成把图片数据直接带过去（大图先压到 2048 像素、2 MB 以内），日志里有一句 `NapCat cannot read local files`，之后每张图都这么发。但超过 30 MB 的文件就发不了了，所以还是建议挂上
- **`enableLocalFile2Url`**（NapCat 的 OneBot 设置）：它不是发本地图用的，作用是让 `get_image` / `get_file` 的结果里带上文件内容。NapCat 在容器里时可以打开：QQ 里的旧图片链接过期后，FeiYu Bot 能通过它把图重新取回来
- 反向 WebSocket 填 `ws://127.0.0.1:6199/ws`，token 和 FeiYu Bot 控制台里反向 WebSocket 设置的 token 一样

## 4. 跑起来以后怎么查

```bash
# 发图走的是哪条路：via path 是按路径发，via base64 是直接带图片数据
grep -a "sent image" dafeiyu/data/logs/dafeiyu-$(date +%F).log | tail -5

# 记忆整理有没有失败
grep -a "reflection failed\|reflected on" dafeiyu/data/logs/dafeiyu-$(date +%F).log | tail -5

# 今天的 token 用量，按用途（也可以看控制台的「Token 用量」页，或 GET /api/usage）
sqlite3 dafeiyu/data/dafeiyu.db "select purpose, count(*), sum(prompt_tokens), sum(completion_tokens)
  from token_usage where day = date('now','localtime') group by purpose order by 3 desc;"

# 今天画了几张图
sqlite3 dafeiyu/data/dafeiyu.db "select session, sum(count) from image_usage
  where at >= strftime('%s', date('now','localtime'), 'utc') * 1000 group by session;"
```

日志在 `dafeiyu/data/logs/dafeiyu-日期.log`（保留 14 天）。消息日志只记前 200 字，被截断时末尾会写 `truncated in the log, N chars in total`，完整内容在数据库 `messages` 表里。

## 5. 省 token

内心是最大的开销（那位用户一天里占了 65%～82%）：
- 「内心」页的思考节奏可以选「自定义」，填 15 秒到 60 分钟；节奏旁边的月亮按钮还能设深夜时段，深夜换一个间隔，或者填 0 直接停下（托她的事、@ 她和私聊照样会叫醒她）
- 上下文窗口在「模型」设置里配，压缩完全跟着它走：窗口越大，每次请求带的上下文越多
- 模型的「最大输出」别设太小：记忆整理一次要写不少东西，设小于 16000 时整理会自动用 16000（模型本身上限更小时用模型的上限）
- 生图可以在「设置 › Agent › 生图」里限额：每小时 / 每天最多几张，所有聊天一起算或者每个群、每个私聊各算
