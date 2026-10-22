package dafeiyu.agent.persona;

public record Persona(String id, String name, String avatar, String prompt) {

    public static final int PROMPT_MAX = 20_000;
    public static final int AVATAR_MAX = 400_000;

    public Persona {
        name = name == null ? "" : name.trim();
        avatar = avatar == null ? "" : avatar.trim();
        prompt = prompt == null ? "" : prompt.strip();
    }

    public Persona withId(String newId) {
        return new Persona(newId, name, avatar, prompt);
    }

    public String validate() {
        if (name.isEmpty()) return "名称不能为空";
        if (name.length() > 30) return "名称不能超过 30 个字";
        if (!avatar.isEmpty() && !avatar.matches("data:image/(png|jpeg|webp|gif);base64,[A-Za-z0-9+/=]+")) return "头像必须是图片";
        if (avatar.length() > AVATAR_MAX) return "头像图片太大";
        if (prompt.isEmpty()) return "系统提示词不能为空";
        if (prompt.length() > PROMPT_MAX) return "系统提示词不能超过 " + PROMPT_MAX + " 字";
        return null;
    }
}
