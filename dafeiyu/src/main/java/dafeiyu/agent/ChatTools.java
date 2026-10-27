package dafeiyu.agent;

import dafeiyu.agent.maintenance.Housekeeping;
import dafeiyu.agent.memory.MemoryStore;
import dafeiyu.agent.tool.Tool;
import dafeiyu.agent.tool.ToolFailure;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;

public final class ChatTools {

    public static final String READ_FORWARD = "read_forward";
    public static final String RECALL = "recall";
    public static final String SKILL = "skill";
    public static final String START_TASK = "start_task";
    public static final String HOUSEKEEPING = "housekeeping";

    private ChatTools() {}

    public static Tool housekeeping(ChatAgent agent) {
        return Tool.text(HOUSEKEEPING,
                "看看/清理历史记录（消息归档和用量日志）。默认只演练：告诉你按现在的设置会删多少条，不动任何数据。"
                        + "真要删就传 dryRun=false。保留天数由主人在「设置 › Agent › 记录清理」里定，最近 7 天的永远不删。"
                        + "别自己主动清 —— 除非主人问起存储、或者到了定期检查的点。",
                """
                {"type": "object", "properties": {
                  "dryRun": {"type": "boolean", "description": "true（默认）只报告会删多少条，false 才真删"}
                }}""",
                (args, context) -> {
                    boolean dry = !args.has("dryRun") || args.bool("dryRun", true);
                    if (dry) {
                        Housekeeping.Plan plan = agent.housekeeping().plan();
                        return "演练（没动数据）：按保留 " + plan.messagesDays() + " 天的消息、"
                                + plan.usageDays() + " 天的用量记录，会删 " + plan.messages() + " 条消息、"
                                + plan.usage() + " 条用量记录。" + plan.note();
                    }
                    Housekeeping.Result r = agent.housekeeping().run();
                    return "清理完了：删掉 " + r.messages() + " 条消息、" + r.usage() + " 条用量记录"
                            + "（保留最近 " + r.messagesDays() + "/" + r.usageDays() + " 天）。";
                });
    }

    public static Tool readForward(ChatAgent agent) {
        return Tool.text(READ_FORWARD, "读别人分享的合并转发聊天记录：聊天里或 read_group 里的“[合并转发的聊天记录：编号]”就是，把冒号后面的编号原样填进来，"
                        + "会返回里面每条消息是谁说的、说了什么。别人发来让你看的、问你“你看看这个”的，先读了再回。", """
                        {"type": "object", "properties": {
                          "id": {"type": "string", "description": "合并转发的编号"}
                        }, "required": ["id"]}""",
                (args, context) -> agent.readForward(args.text("id").replace("合并转发的聊天记录：", "").replace("[", "").replace("]", "").strip()));
    }

    public static Tool skill(ChatAgent agent, String personaId) {
        return Tool.text(SKILL, "查看你学会的某个技能的完整做法。“【你学会的技能】”里只列了名字，碰到对应的事先用它看清楚怎么做；记不清名字就写关键词。", """
                        {"type": "object", "properties": {
                          "name": {"type": "string", "description": "技能的名字"}
                        }, "required": ["name"]}""",
                (args, context) -> {
                    String detail = agent.skills().detail(personaId, args.optString("name"));
                    if (detail == null) {
                        throw new ToolFailure("没有叫「" + args.text("name") + "」的技能，看看【你学会的技能】里的名字");
                    }
                    return detail;
                });
    }

    static Tool recall(ChatAgent agent, String personaId, Set<String> owners, UnaryOperator<String> scopeOf) {
        return Tool.text(RECALL, "想想你记得的事。“<你记得>”里只放了跟现在相关的一部分；想起更多关于某个人、这个群、你自己的事，翻日记或查过的词，就用它，要多少查多少。", """
                        {"type": "object", "properties": {
                          "about": {"type": "string", "description": "想谁或想什么：名字、QQ 号、self（你自己）、group（这个群）、knowledge（查过的词）、diary（日记）"},
                          "query": {"type": "string", "description": "只想找跟某件事有关的就写关键词，不写就是全部"},
                          "offset": {"type": "integer", "description": "接着上次往后看时填，结果里会告诉你填几"}
                        }, "required": ["about"]}""",
                (args, context) -> {
                    String about = args.text("about");
                    List<String> scopes = scopes(agent, personaId, about, scopeOf);
                    if (scopes.isEmpty()) {
                        throw new ToolFailure("不知道「" + about + "」是谁或什么。about 写名字、QQ 号、self、group、knowledge 或 diary。");
                    }
                    String text = agent.memories().lookup(personaId, scopes.getFirst(), context.session(), owners, args.text("query"),
                            Math.max(0, args.integer("offset", 0)));
                    return scopes.size() == 1 ? text : text + "\n（叫这个名字的不止一个人，这是 " + scopes.getFirst()
                            + "；其他的：" + String.join("、", scopes.subList(1, scopes.size())) + "，可以填 QQ 号查）";
                });
    }

    private static List<String> scopes(ChatAgent agent, String personaId, String about, UnaryOperator<String> scopeOf) {
        String lower = about.toLowerCase(Locale.ROOT);
        if (about.isEmpty()) {
            return List.of();
        }
        if (lower.equals(MemoryStore.DIARY) || about.equals("日记")) {
            return List.of(MemoryStore.DIARY);
        }
        if (lower.startsWith("user:") || about.matches("\\d{5,12}")) {
            return List.of("user:" + about.replaceFirst("(?i)^user:", "").strip());
        }
        if (lower.startsWith("group:") && about.substring(6).strip().matches("\\d{5,12}")) {
            return List.of("group:" + about.substring(6).strip());
        }
        String scope = scopeOf.apply(about);
        if (scope != null) {
            return List.of(scope);
        }
        return agent.memories().scopesNamed(personaId, about);
    }

    static Tool startTask(ChatAgent agent, String requester) {
        return Tool.text(START_TASK, "把要动手的活交给后台去做：写网页、做小游戏、做或改文件、改代码、跑命令、查一堆资料整理成文档这类要好几步的事。"
                        + "交出去以后聊天这边马上就能接着聊，后台做好了会让你把结果告诉对方、把文件发出去。这类活不要在聊天里自己一步步做。", """
                        {"type": "object", "properties": {
                          "title": {"type": "string", "description": "一句话的任务名，比如“网页版狼人杀”"},
                          "instructions": {"type": "string", "description": "完整的要求：要做成什么样、给谁用、有什么细节，改已有文件就写清楚文件路径和要改什么"}
                        }, "required": ["title", "instructions"]}""",
                (args, context) -> {
                    String title = args.text("title");
                    String instructions = args.text("instructions");
                    if (title.isEmpty() || instructions.isEmpty()) {
                        throw new ToolFailure("title 和 instructions 都要写");
                    }
                    agent.tasks().start(context.session(), requester, context.userId(), title, instructions);
                    return "「" + title + "」已经交给后台在做了。现在先回对方一句在做了（别说已经做好）；做好后会让你把结果告诉对方、把文件发出去。"
                            + "别在这里自己再动手做一遍。";
                });
    }
}
