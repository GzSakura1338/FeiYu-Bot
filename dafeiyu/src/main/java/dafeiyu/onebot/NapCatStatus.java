package dafeiyu.onebot;

public record NapCatStatus(
        boolean connected,
        String listen,
        String selfId,
        String nickname,
        String remote,
        String connectedAt,
        String lastHeartbeat) {}
