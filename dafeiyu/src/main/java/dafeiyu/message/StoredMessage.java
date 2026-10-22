package dafeiyu.message;

public record StoredMessage(
        long id,
        long time,
        String direction,
        String messageType,
        String groupId,
        String userId,
        String senderName,
        String messageId,
        String content) {}
