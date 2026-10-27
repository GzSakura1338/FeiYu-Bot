package dafeiyu.agent.qq;

import dafeiyu.agent.tool.ToolFailure;

public interface MessageGate {

    record Message(String type, String groupId, String userId, String sender, boolean owner, String text, String attachments,
                   boolean mentionedMe, String messageId, long time, boolean live) {

        public String session() {
            return type.equals("group") ? "group:" + groupId : "private:" + userId;
        }
    }

    record Verdict(boolean drop, String text, String by, boolean hide) {

        public static final Verdict PASS = new Verdict(false, null, "", false);
    }

    MessageGate NONE = new MessageGate() {
        @Override
        public Verdict incoming(Message message) {
            return Verdict.PASS;
        }

        @Override
        public String outgoing(String session, String text) {
            return text;
        }

        @Override
        public void said(String session, String text) {
        }
    };

    Verdict incoming(Message message);

    String outgoing(String session, String text) throws ToolFailure;

    void said(String session, String text);
}
