package dafeiyu.plugin.manifest;

import java.util.Arrays;
import java.util.Optional;

public enum Permission {
    QQ_REPLY("qq.reply", "回复触发它的消息", "只能回复触发它的那条命令或消息，最常用、最安全"),
    QQ_SEND("qq.send", "主动往群里发消息", "可以主动往你勾选的群发消息，走内容审核和频率限制"),
    QQ_PRIVATE("qq.private", "私聊和私聊记录", "可以给人发私聊、读私聊记录"),
    QQ_READ("qq.read", "读群聊记录", "可以读你勾选的群的聊天记录、群列表和群成员"),
    QQ_FILTER("qq.filter", "过滤她发出去的话", "她要发出去的话会先交给它过一遍，它可以改写或拦下"),
    QQ_API("qq.api", "直接调 QQ 接口", "可以调协议端的原始接口（发语音、戳一戳、群文件等，锁死的除外；带群号的只能用在勾选的群），能力最强、风险最高"),
    MEMORY_READ("memory.read", "读她的记忆", "可以读她的记忆（私聊里记下的不给）"),
    MEMORY_WRITE("memory.write", "写她的记忆", "可以往她的记忆里添一条"),
    MIND_READ("mind.read", "看她的内心", "能收到她内心的念头"),
    LLM("llm", "调用大模型", "可以用当前的模型做对话，token 记在这个插件名下"),
    NETWORK("network", "上网", "插件程序可以访问互联网（本机和局域网仍然不行）"),
    FILES("files", "读写工作目录", "插件程序可以读写你设置的工作目录");

    private final String key;
    private final String label;
    private final String description;

    Permission(String key, String label, String description) {
        this.key = key;
        this.label = label;
        this.description = description;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public static Optional<Permission> of(String key) {
        return Arrays.stream(values()).filter(p -> p.key.equals(key)).findFirst();
    }
}
