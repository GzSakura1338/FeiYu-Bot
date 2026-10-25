package dafeiyu.agent.model;

import dafeiyu.agent.AgentException;
import dafeiyu.agent.ImagePart;
import dafeiyu.util.HttpCalls;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

public final class ImageLoader {

    private static final int MAX_DOWNLOAD = 10 * 1024 * 1024;
    private static final int MAX_SEND = 4 * 1024 * 1024;
    private static final int MAX_SIDE = 1568;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private ImageLoader() {}

    public static ImagePart download(String url) throws AgentException {
        return fromBytes(fetch(url));
    }

    public static String mime(byte[] bytes) {
        return sniff(bytes);
    }

    public static byte[] fetch(String url) throws AgentException {
        HttpResponse<byte[]> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();
            response = HttpCalls.send(HTTP, request, HttpResponse.BodyHandlers.ofByteArray(), Duration.ofSeconds(45));
        } catch (IllegalArgumentException e) {
            throw new AgentException("invalid image url");
        } catch (IOException e) {
            throw new AgentException("image download failed: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException("image download interrupted");
        }
        if (response.statusCode() / 100 != 2) {
            throw new AgentException("image download failed: HTTP " + response.statusCode());
        }
        if (response.body().length > MAX_DOWNLOAD) {
            throw new AgentException("image is larger than 10 MB");
        }
        return response.body();
    }

    public static ImagePart fromDataUrl(String dataUrl) throws AgentException {
        int comma = dataUrl == null ? -1 : dataUrl.indexOf(',');
        if (comma < 0 || !dataUrl.startsWith("data:") || !dataUrl.substring(0, comma).endsWith(";base64")) {
            throw new AgentException("invalid image data");
        }
        try {
            return fromBytes(Base64.getDecoder().decode(dataUrl.substring(comma + 1)));
        } catch (IllegalArgumentException e) {
            throw new AgentException("invalid image data");
        }
    }

    public static ImagePart fromBytes(byte[] bytes) throws AgentException {
        if (bytes == null || bytes.length == 0) {
            throw new AgentException("empty image");
        }
        if (bytes.length > MAX_DOWNLOAD) {
            throw new AgentException("image is larger than 10 MB");
        }
        String mime = sniff(bytes);
        if (mime == null) {
            throw new AgentException("unsupported image format");
        }
        BufferedImage image = null;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException ignored) {
        }
        if (image != null && (mime.equals("image/gif") || bytes.length > MAX_SEND
                || image.getWidth() > MAX_SIDE || image.getHeight() > MAX_SIDE)) {
            return reencode(image);
        }
        if (bytes.length > MAX_SEND) {
            throw new AgentException("image is too large to send");
        }
        return new ImagePart(mime, Base64.getEncoder().encodeToString(bytes));
    }

    static ImagePart solid(Color color, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, size, size);
        g.dispose();
        return encode(image, "png");
    }

    private static ImagePart reencode(BufferedImage source) {
        double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        boolean alpha = source.getColorModel().hasAlpha();
        BufferedImage target = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        if (!alpha) {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
        }
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return encode(target, alpha ? "png" : "jpg");
    }

    private static ImagePart encode(BufferedImage image, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, format, out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new ImagePart(format.equals("png") ? "image/png" : "image/jpeg", Base64.getEncoder().encodeToString(out.toByteArray()));
    }

    private static String sniff(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "image/gif";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        if (b.length >= 2 && b[0] == 'B' && b[1] == 'M') return "image/bmp";
        return null;
    }
}
