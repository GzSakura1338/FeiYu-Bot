package dafeiyu.agent.image;

import dafeiyu.agent.AgentException;
import dafeiyu.agent.ImagePart;
import dafeiyu.agent.model.ImageLoader;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Node;

public final class GifFrames {

    public record Frames(List<ImagePart> parts, int total, String note) {}

    private GifFrames() {}

    public static boolean animated(byte[] bytes) {
        return count(bytes) > 1;
    }

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
     * 抽帧。GIF 每帧只存「变化区域」（差分编码），必须按 disposalMethod 叠加到
     * 逻辑屏幕尺寸的画布上，否则 reader.read(i) 拿到的是残缺局部帧，
     * 后面的帧会整片糊成雪花（2026-10-27 实测：236x320 局部帧 vs 240x320 完整帧）。
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

                int canvasW = 0;
                int canvasH = 0;
                try {
                    IIOMetadata stream = reader.getStreamMetadata();
                    if (stream != null) {
                        IIOMetadataNode root = (IIOMetadataNode) stream.getAsTree("javax_imageio_gif_stream_1.0");
                        IIOMetadataNode lsd = child(root, "LogicalScreenDescriptor");
                        if (lsd != null) {
                            canvasW = intAttr(lsd, "logicalScreenWidth");
                            canvasH = intAttr(lsd, "logicalScreenHeight");
                        }
                    }
                } catch (RuntimeException ignored) {
                }
                if (canvasW <= 0 || canvasH <= 0) {
                    BufferedImage probe = reader.read(0);
                    if (probe == null) {
                        throw new AgentException("这个动图一帧都没读出来");
                    }
                    canvasW = probe.getWidth();
                    canvasH = probe.getHeight();
                }

                int picks = Math.min(take, total);
                int step = Math.max(1, total / picks);
                List<ImagePart> parts = new ArrayList<>();

                BufferedImage canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
                BufferedImage backup = null;
                String prevDisposal = "none";
                int prevLeft = 0;
                int prevTop = 0;
                int prevW = canvasW;
                int prevH = canvasH;

                for (int i = 0; i < total; i++) {
                    BufferedImage frame = reader.read(i);
                    if (frame == null) {
                        continue;
                    }

                    int left = 0;
                    int top = 0;
                    String disposal = "none";
                    try {
                        IIOMetadata md = reader.getImageMetadata(i);
                        IIOMetadataNode root = (IIOMetadataNode) md.getAsTree("javax_imageio_gif_image_1.0");
                        IIOMetadataNode desc = child(root, "ImageDescriptor");
                        if (desc != null) {
                            left = intAttr(desc, "imageLeftPosition");
                            top = intAttr(desc, "imageTopPosition");
                        }
                        IIOMetadataNode gce = child(root, "GraphicControlExtension");
                        if (gce != null) {
                            String d = gce.getAttribute("disposalMethod");
                            if (d != null && !d.isBlank()) {
                                disposal = d;
                            }
                        }
                    } catch (RuntimeException ignored) {
                    }

                    if (i > 0) {
                        Graphics2D g = canvas.createGraphics();
                        if ("restoreToBackgroundColor".equals(prevDisposal)) {
                            g.setComposite(AlphaComposite.Clear);
                            g.fillRect(prevLeft, prevTop, prevW, prevH);
                        } else if ("restoreToPrevious".equals(prevDisposal) && backup != null) {
                            g.setComposite(AlphaComposite.Src);
                            g.drawImage(backup, 0, 0, null);
                        }
                        g.dispose();
                    }

                    if ("restoreToPrevious".equals(disposal)) {
                        backup = copyOf(canvas);
                    }

                    Graphics2D painter = canvas.createGraphics();
                    painter.drawImage(frame, left, top, null);
                    painter.dispose();

                    if (i % step == 0 && parts.size() < picks) {
                        parts.add(ImageLoader.fromImage(copyOf(canvas)));
                    }

                    prevDisposal = disposal;
                    prevLeft = left;
                    prevTop = top;
                    prevW = frame.getWidth();
                    prevH = frame.getHeight();
                }

                if (parts.isEmpty()) {
                    throw new AgentException("这个动图一帧都没读出来");
                }

                String note = "（这是动图的 " + parts.size() + " 个画面，按播放顺序排的，每张都按完整画布合成过；原图一共 " + total + " 帧，"
                        + "抽的是其中均匀分布的 " + parts.size() + " 帧，所以动作细节可能不全）";
                return new Frames(parts, total, note);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            throw new AgentException("动图抽帧失败：" + e.getMessage());
        }
    }

    private static BufferedImage copyOf(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        if (root == null) {
            return null;
        }
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (name.equals(n.getNodeName()) && n instanceof IIOMetadataNode m) {
                return m;
            }
        }
        return null;
    }

    private static int intAttr(IIOMetadataNode node, String attr) {
        try {
            String v = node.getAttribute(attr);
            return v == null || v.isBlank() ? 0 : Integer.parseInt(v.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
