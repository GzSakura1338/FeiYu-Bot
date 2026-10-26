package dafeiyu.onebot;

public record OneBotStatus(
        boolean connected,
        String listen,
        String selfId,
        String nickname,
        String remote,
        String connectedAt,
        String lastHeartbeat) {}
