package dafeiyu.agent.image;

import dafeiyu.agent.AgentException;
import dafeiyu.agent.ImagePart;
import dafeiyu.agent.model.ImageLoader;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 动图（GIF）抽帧。
 *
 * <p>原来 {@code ImageLoader.fromBytes} 对 GIF 走 {@code ImageIO.read()}，那**只读第一帧**，
 * 所以动态表情包在模型眼里永远是一张静止图。这里按帧序均匀抽若干张，让模型能看出"这是动的"。
 *
 * <p>纯 Java 实现，不依赖 ffmpeg —— GIF 本身就能被 ImageIO 按帧读。
 */
public final class GifFrames {

    /**
     * @param parts 抽出来的画面
     * @param total 这个动图一共多少帧
     * @param note  写给模型看的说明
     */
    public record Frames(List<ImagePart> parts, int total, String note) {}

    private GifFrames() {}

    /** 是不是动图（帧数 > 1）。读不出来就返回 false，交给原来的单帧逻辑。 */
    public static boolean animated(byte[] bytes) {
        return count(bytes) > 1;
    }

    /** 总帧数；不是 GIF 或读不出来返回 0。 */
    public static int count(byte[] bytes) {
        if (bytes == null || bytes.length < 6) {
            return 0;
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) {
                return 0;
            }
            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            try {
                reader.setInput(in, false, false);
                return reader.getNumImages(true);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /**
     * 均匀抽 {@code want} 帧。
     *
     * <p>总帧数比 want 少时按实际帧数取，不重复；want=1 时只取第一帧（等于关掉这个功能）。
     */
    public static Frames extract(byte[] bytes, int want) throws AgentException {
        int take = Math.max(1, want);
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) {
                throw new AgentException("这个动图读不出来");
            }
            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            try {
                reader.setInput(in, false, false);
                int total = reader.getNumImages(true);
                if (total <= 1) {
                    throw new AgentException("这不是动图");
                }
                int picks = Math.min(take, total);
                int step = Math.max(1, total / picks);
                List<ImagePart> parts = new ArrayList<>();
                for (int i = 0; i < total && parts.size() < picks; i += step) {
                    BufferedImage frame = reader.read(i);
                    if (frame == null) {
                        continue;
                    }
                    parts.add(ImageLoader.fromImage(frame));
                }
                if (parts.isEmpty()) {
                    throw new AgentException("这个动图一帧都没读出来");
                }
                String note = "（这是动图的 " + parts.size() + " 个画面，按播放顺序排的；原图一共 " + total + " 帧，"
                        + "抽的是其中均匀分布的 " + parts.size() + " 帧，所以动作细节可能不全）";
                return new Frames(parts, total, note);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            throw new AgentException("动图抽帧失败：" + e.getMessage());
        }
    }
}
