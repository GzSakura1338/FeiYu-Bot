package dafeiyu.agent;

import com.google.gson.JsonObject;
import dafeiyu.agent.model.ImageLoader;
import dafeiyu.agent.sticker.StickerStore;
import dafeiyu.agent.tool.Args;
import dafeiyu.agent.tool.PublicWeb;
import dafeiyu.agent.tool.Tool;
import dafeiyu.agent.tool.ToolContext;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.agent.tool.ToolResult;
import dafeiyu.agent.tool.Workspace;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ImageViewer implements Tool {

    public static final String NAME = "view_image";
    private static final String DESCRIPTION = """
            看一张图，看不清的地方可以放大。图可以是：聊天里的 [图片#12]（填 12）、[表情包#5]（填 表情包#5）、网页上的图片网址，或者工作目录里的图片文件。
            - 不写 area 就看整张图，图上会画好 4×4 的格子：列 A–D 从左到右，行 1–4 从上到下。
            - 哪块字太小、细节看不清，就用 area 填格子放大，比如 B2，或者 B2:C3 这样一大块；也可以写 左上、右下、上半、下半、中间 这样的方位，\
            或者用百分比框 30,40,60,55（左、上、右、下，占整张图的百分比）。
            - 放大是从原图裁出来的，比聊天里直接看到的清楚得多。截图、长图、表格、小字，先看整张，再放大要读的那块。""";
    private static final JsonObject PARAMETERS = Args.schema("""
            {"type": "object", "properties": {
              "image": {"type": "string", "description": "哪张图：图片编号（[图片#12] 填 12）、表情包#编号、图片网址，或工作目录里的图片路径"},
              "area": {"type": "string", "description": "要放大的地方：格子（B2、B2:C3）、方位（左上、下半、中间……）或百分比框（30,40,60,55）；不写就看整张"},
              "grid": {"type": "boolean", "description": "看整张图时要不要画格子，默认画"}
            }, "required": ["image"]}""");

    private static final int MAX_BYTES = 15 * 1024 * 1024;
    private static final int MAX_SIDE = 1568;
    private static final int MIN_SIDE = 768;
    private static final int GRID = 4;
    private static final double MAX_ZOOM = 4.0;
    private static final double MARGIN = 0.02;
    private static final Pattern NUMBER = Pattern.compile("^\\[?(?:图片|动画表情|表情|图)?\\s*#?\\s*(\\d{1,12})\\s*]?$");
    private static final Pattern STICKER = Pattern.compile("^\\[?表情包\\s*#?\\s*(\\d{1,12})");
    private static final Pattern CELLS = Pattern.compile("(?i)^([A-D])\\s*([1-4])(?:\\s*[:：~\\-—到至]\\s*([A-D])\\s*([1-4]))?$");
    private static final Pattern BOX = Pattern.compile("^(\\d{1,3})\\s*[,，]\\s*(\\d{1,3})\\s*[,，]\\s*(\\d{1,3})\\s*[,，]\\s*(\\d{1,3})$");
    private static final Map<String, double[]> PLACES = Map.ofEntries(
            Map.entry("左上", new double[] {0, 0, .5, .5}), Map.entry("右上", new double[] {.5, 0, 1, .5}),
            Map.entry("左下", new double[] {0, .5, .5, 1}), Map.entry("右下", new double[] {.5, .5, 1, 1}),
            Map.entry("上半", new double[] {0, 0, 1, .5}), Map.entry("下半", new double[] {0, .5, 1, 1}),
            Map.entry("左半", new double[] {0, 0, .5, 1}), Map.entry("右半", new double[] {.5, 0, 1, 1}),
            Map.entry("中间", new double[] {.25, .25, .75, .75}), Map.entry("顶部", new double[] {0, 0, 1, .34}),
            Map.entry("底部", new double[] {0, .66, 1, 1}), Map.entry("中部", new double[] {0, .33, 1, .67}));

    private final ChatAgent agent;
    private final boolean workspaceFiles;

    public ImageViewer(ChatAgent agent, boolean workspaceFiles) {
        this.agent = agent;
        this.workspaceFiles = workspaceFiles;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description(ToolContext context) {
        return DESCRIPTION;
    }

    @Override
    public JsonObject parameters() {
        return PARAMETERS;
    }

    @Override
    public ToolResult run(Args args, ToolContext context) throws ToolFailure {
        String ref = args.text("image");
        String area = args.text("area");
        boolean grid = args.bool("grid", true);
        Workspace workspace = !workspaceFiles ? null : context.workspace() != null ? context.workspace() : agent.workspaces().workspace();
        try {
            if (ref.isEmpty()) {
                throw new ToolFailure("要看哪张图？聊天里的图填编号（[图片#12] 就填 12），网页上的图填网址");
            }
            String[] name = new String[1];
            byte[] bytes = load(agent, ref, workspace, name);
            BufferedImage image = decode(bytes);
            if (image == null) {
                if (!area.isEmpty()) {
                    throw new ToolFailure(name[0] + " 是这台电脑解不开的格式，没法放大，只能不写 area 看整张");
                }
                return ToolResult.withImages(name[0] + "（这种格式没法画格子和放大，下面是整张图）", List.of(ImageLoader.fromBytes(bytes)));
            }
            int w = image.getWidth();
            int h = image.getHeight();
            if (area.isEmpty()) {
                int longest = Math.max(w, h);
                BufferedImage view = scale(image, longest > MAX_SIDE ? (double) MAX_SIDE / longest : Math.min(2.0, Math.max(1.0, (double) MIN_SIDE / longest)));
                if (grid) {
                    drawGrid(view);
                }
                return ToolResult.withImages(name[0] + "（原图 " + w + "×" + h + "）" + (grid
                        ? "。图上画了 4×4 的格子：列 A–D 从左到右，行 1–4 从上到下；哪块看不清，就用 area 填格子放大（比如 B2 或 B2:C3）。" : "。"),
                        List.of(encode(view)));
            }
            double[] r = region(area);
            int x1 = clamp((int) Math.floor((r[0] - MARGIN) * w), w);
            int y1 = clamp((int) Math.floor((r[1] - MARGIN) * h), h);
            int x2 = clamp((int) Math.ceil((r[2] + MARGIN) * w), w);
            int y2 = clamp((int) Math.ceil((r[3] + MARGIN) * h), h);
            if (x2 - x1 < 4 || y2 - y1 < 4) {
                throw new ToolFailure("这块太小了，换大一点的区域");
            }
            BufferedImage crop = image.getSubimage(x1, y1, x2 - x1, y2 - y1);
            double zoom = Math.min(MAX_ZOOM, (double) MAX_SIDE / Math.max(crop.getWidth(), crop.getHeight()));
            BufferedImage view = scale(crop, zoom);
            return ToolResult.withImages("放大了" + name[0] + " 的「" + area + "」（原图 " + w + "×" + h + "，这块 " + crop.getWidth() + "×" + crop.getHeight()
                    + (zoom > 1.05 ? "，放大了 " + String.format(Locale.ROOT, "%.1f", zoom) + " 倍" : "") + "）。", List.of(encode(view)));
        } catch (AgentException e) {
            throw new ToolFailure("这张图打不开：" + e.getMessage());
        }
    }

    private static byte[] load(ChatAgent agent, String ref, Workspace workspace, String[] name) throws ToolFailure {
        Matcher sticker = STICKER.matcher(ref);
        if (sticker.find()) {
            long id = Long.parseLong(sticker.group(1));
            StickerStore store = agent.stickers().store();
            StickerStore.Sticker s = store.get(id);
            if (s == null || s.hidden() || s.status().equals("unsafe")) {
                throw new ToolFailure("没有表情包#" + id);
            }
            name[0] = "表情包#" + id;
            return read(store.path(s));
        }
        String lower = ref.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            name[0] = "这张网页图片";
            return PublicWeb.download(ref, MAX_BYTES);
        }
        Matcher number = NUMBER.matcher(ref);
        if (number.matches()) {
            long id = Long.parseLong(number.group(1));
            name[0] = "图片#" + id;
            try {
                return agent.image(id);
            } catch (ToolFailure e) {
                StickerStore.Sticker s = e.getMessage().startsWith("没有图片") ? agent.stickers().store().get(id) : null;
                if (s == null || s.hidden() || s.status().equals("unsafe") || s.file().isEmpty()) {
                    throw e;
                }
                name[0] = "表情包#" + id;
                return read(agent.stickers().store().path(s));
            }
        }
        if (workspace == null) {
            throw new ToolFailure("不知道「" + ref + "」是哪张图：聊天里的图填编号（[图片#12] 就填 12），网页上的图填网址");
        }
        Path path = workspace.existingFile(ref);
        name[0] = workspace.relative(path);
        return read(path);
    }

    private static byte[] read(Path path) throws ToolFailure {
        try {
            if (Files.size(path) > MAX_BYTES) {
                throw new ToolFailure("这张图太大了（超过 15 MB）");
            }
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new ToolFailure("读不了这张图：" + e.getMessage());
        }
    }

    private static BufferedImage decode(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static double[] region(String area) throws ToolFailure {
        String a = area.replace(" ", "").replace("　", "");
        double[] place = PLACES.get(a.replace("部分", "").replace("那块", "").replace("的", ""));
        if (place != null) {
            return place;
        }
        Matcher cells = CELLS.matcher(a);
        if (cells.matches()) {
            int c1 = Character.toUpperCase(cells.group(1).charAt(0)) - 'A';
            int r1 = cells.group(2).charAt(0) - '1';
            int c2 = cells.group(3) == null ? c1 : Character.toUpperCase(cells.group(3).charAt(0)) - 'A';
            int r2 = cells.group(4) == null ? r1 : cells.group(4).charAt(0) - '1';
            return new double[] {Math.min(c1, c2) / (double) GRID, Math.min(r1, r2) / (double) GRID,
                    (Math.max(c1, c2) + 1) / (double) GRID, (Math.max(r1, r2) + 1) / (double) GRID};
        }
        Matcher box = BOX.matcher(a);
        if (box.matches()) {
            double[] v = new double[4];
            for (int i = 0; i < 4; i++) {
                v[i] = Math.min(100, Integer.parseInt(box.group(i + 1))) / 100.0;
            }
            if (v[2] > v[0] && v[3] > v[1]) {
                return v;
            }
        }
        throw new ToolFailure("看不懂 area「" + area + "」：写格子（B2、B2:C3）、方位（左上、下半、中间）或百分比框（30,40,60,55）");
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }

    private static BufferedImage scale(BufferedImage source, double factor) {
        int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return target;
    }

    private static void drawGrid(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(Math.max(1f, Math.max(w, h) / 500f)));
        g.setColor(new Color(255, 40, 40, 150));
        for (int i = 1; i < GRID; i++) {
            g.drawLine(w * i / GRID, 0, w * i / GRID, h);
            g.drawLine(0, h * i / GRID, w, h * i / GRID);
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(12, Math.min(24, Math.min(w, h) / 40))));
        FontMetrics metrics = g.getFontMetrics();
        for (int col = 0; col < GRID; col++) {
            for (int row = 0; row < GRID; row++) {
                String label = (char) ('A' + col) + String.valueOf(row + 1);
                int x = w * col / GRID + 3;
                int y = h * row / GRID + 3;
                g.setColor(new Color(0, 0, 0, 150));
                g.fillRoundRect(x, y, metrics.stringWidth(label) + 6, metrics.getHeight(), 6, 6);
                g.setColor(new Color(255, 230, 80));
                g.drawString(label, x + 3, y + metrics.getAscent());
            }
        }
        g.dispose();
    }

    private static ImagePart encode(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.9f);
            try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(stream);
                writer.write(null, new IIOImage(image, null, null), param);
            } finally {
                writer.dispose();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new ImagePart("image/jpeg", Base64.getEncoder().encodeToString(out.toByteArray()));
    }
}
