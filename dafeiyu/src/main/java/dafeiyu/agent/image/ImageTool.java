package dafeiyu.agent.image;

import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.safety.ContentGuard;
import dafeiyu.agent.tool.Tool;
import dafeiyu.agent.tool.ToolContext;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.agent.tool.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static dafeiyu.util.Texts.abbreviate;

public final class ImageTool {

    public static final String NAME = "image";

    private static final int MAX_PROMPT = 4000;
    private static final String FOLDER = "作品/生图";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private ImageTool() {}

    public static Tool tool(ChatAgent agent) {
        return Tool.text(NAME, "生成图片。有人让你画一张图、做个头像、出张插画、生成图片时直接用它，画好的图会自动发到当前聊天里，不用再 send_file。"
                        + "prompt 写清楚画面：主体、动作、场景、风格、构图、颜色，按对方的要求写，中文英文都行。一次一般画 1 张。", """
                        {"type": "object", "properties": {
                          "prompt": {"type": "string", "description": "画面描述"},
                          "size": {"type": "string", "description": "可选。尺寸：1024x1024 方形、1536x1024 横版、1024x1536 竖版、1792x1024 宽屏、1024x1792 长图、auto 让模型决定；不写用设置里的默认尺寸"},
                          "n": {"type": "integer", "description": "可选。画几张，1～4，默认 1"}
                        }, "required": ["prompt"]}""",
                (args, context) -> {
                    String prompt = args.string("prompt").strip();
                    if (prompt.isEmpty()) {
                        throw new ToolFailure("prompt 是空的，要写清楚画什么");
                    }
                    if (prompt.length() > MAX_PROMPT) {
                        throw new ToolFailure("prompt 太长了（超过 " + MAX_PROMPT + " 字），挑重点写");
                    }
                    ContentGuard.Verdict verdict = agent.guard().scan(prompt);
                    if (verdict == null) {
                        verdict = agent.guard().review(agent.store().active(), "", prompt);
                    }
                    if (verdict != null) {
                        throw new ToolFailure("这张不能画（涉及" + verdict.category().label() + "），换个别的吧");
                    }
                    ImageGen.Result result = agent.imageGen().generate(prompt, args.text("size"), args.integer("n", 1));
                    List<Path> saved = save(agent.imageGen(), context.workspace(), result.pictures());
                    StringBuilder out = new StringBuilder();
                    if (context.files() == null) {
                        out.append("画好了 ").append(saved.size()).append(" 张，这个对话里发不了图，存在：").append(where(context.workspace(), saved));
                    } else {
                        List<String> sent = new ArrayList<>();
                        for (Path file : saved) {
                            sent.add(context.files().send(file, file.getFileName().toString()));
                        }
                        out.append("画好了，").append(saved.size()).append(" 张图已经发到聊天里（存在 ").append(where(context.workspace(), saved)).append("）。\n")
                                .append(String.join("\n", sent));
                    }
                    if (!result.revisedPrompt().isBlank()) {
                        out.append("\n模型理解的画面：").append(abbreviate(result.revisedPrompt(), 400));
                    }
                    return out.toString();
                });
    }

    private static List<Path> save(ImageGen gen, Workspace workspace, List<ImageGen.Picture> pictures) throws ToolFailure {
        String stamp = LocalDateTime.now().format(STAMP);
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < pictures.size(); i++) {
            ImageGen.Picture p = pictures.get(i);
            String name = stamp + (pictures.size() > 1 ? "-" + (i + 1) : "") + "." + p.extension();
            if (workspace != null) {
                Path target = workspace.resolve(FOLDER + "/" + name);
                workspace.write(target, p.bytes());
                files.add(target);
            } else {
                try {
                    Files.createDirectories(gen.folder());
                    files.add(Files.write(gen.folder().resolve(name), p.bytes()));
                } catch (IOException e) {
                    throw new ToolFailure("图画好了但存不下来：" + e.getMessage());
                }
            }
        }
        return files;
    }

    private static String where(Workspace workspace, List<Path> files) {
        List<String> names = new ArrayList<>();
        for (Path f : files) {
            names.add(workspace != null ? workspace.relative(f) : f.toString());
        }
        return String.join("、", names);
    }
}
