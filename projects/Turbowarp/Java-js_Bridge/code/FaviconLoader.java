package code.rs6048;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 HTML 文件中提取网页图标（favicon）并解码为可显示的 Image。
 *
 * 支持:
 *   - <link rel="icon"> / rel="shortcut icon" / rel="apple-touch-icon"（按优先级取第一个可用的）
 *   - href 为相对路径、绝对文件路径、http(s) URL、data:image/...;base64 URI
 *   - 图片格式: PNG / JPEG / GIF / BMP（ImageIO 原生），以及 ICO（解析内嵌 PNG）
 *   - SVG 不解析（Swing 无原生支持），静默跳过
 *
 * 任何失败都静默返回 null，不打扰主流程。
 */
public class FaviconLoader {

    /** 单个 <link ...> 标签 */
    private static final Pattern LINK_TAG = Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    /** 标签内 rel 属性值 */
    private static final Pattern REL_ATTR = Pattern.compile("\\brel\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE);
    /** 标签内 href 属性值 */
    private static final Pattern HREF_ATTR = Pattern.compile("\\bhref\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE);
    /** rel 优先级：apple-touch-icon > shortcut icon > icon */
    private static final String[] REL_PRIORITY = {"apple-touch-icon", "shortcut icon", "icon"};

    /** 下载/解码大小上限（2MB），防止异常图标拖垮窗口 */
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private FaviconLoader() {
    }

    /**
     * 解析 HTML 并返回第一个可用的图标；没有或失败返回 null。
     *
     * @param htmlFile HTML 文件路径（用于解析相对 href）
     */
    public static Image loadFavicon(Path htmlFile) {
        if (htmlFile == null || !Files.isReadable(htmlFile)) {
            return null;
        }
        String content;
        try {
            content = Files.readString(htmlFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }

        Path baseDir = htmlFile.toAbsolutePath().normalize().getParent();

        // 收集所有 icon 类 link 的 href
        java.util.List<String[]> candidates = new java.util.ArrayList<>(); // [rel, href]
        Matcher tagMatcher = LINK_TAG.matcher(content);
        while (tagMatcher.find()) {
            String tag = tagMatcher.group();
            String rel = attr(REL_ATTR, tag);
            if (rel == null) {
                continue;
            }
            String href = attr(HREF_ATTR, tag);
            if (href == null || href.isBlank()) {
                continue;
            }
            candidates.add(new String[]{rel.toLowerCase(), href.trim()});
        }
        if (candidates.isEmpty()) {
            return null;
        }

        // 按优先级尝试；同优先级内取文档中第一个
        for (String priority : REL_PRIORITY) {
            for (String[] candidate : candidates) {
                String rel = candidate[0];
                boolean match = priority.equals("icon")
                        ? rel.equals("icon") || rel.equals("shortcut icon") || rel.equals("apple-touch-icon")
                        : rel.equals(priority);
                if (!match) {
                    continue;
                }
                Image img = resolveIcon(candidate[1], baseDir);
                if (img != null) {
                    return img;
                }
            }
        }
        return null;
    }

    private static String attr(Pattern pattern, String tag) {
        Matcher m = pattern.matcher(tag);
        return m.find() ? m.group(2) : null;
    }

    /** 按 href 形态取字节并解码为 Image */
    private static Image resolveIcon(String href, Path baseDir) {
        try {
            byte[] bytes;
            if (href.startsWith("data:")) {
                bytes = decodeDataUri(href);
            } else if (href.matches("(?i)^https?://.*")) {
                bytes = download(href);
            } else {
                // 文件路径：先做 URL 解码（%20 等），再按绝对/相对解析
                String decoded = URLDecoder.decode(href, StandardCharsets.UTF_8);
                Path p = Paths.get(decoded);
                if (!p.isAbsolute()) {
                    p = baseDir.resolve(p);
                }
                bytes = Files.readAllBytes(p.normalize());
            }
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
                return null;
            }
            return decodeImage(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    private static Image decodeImage(byte[] bytes) throws IOException {
        // ICO 文件头: 00 00 01 00 xx xx
        if (bytes.length > 6 && bytes[0] == 0 && bytes[1] == 0
                && bytes[2] == 1 && bytes[3] == 0) {
            return decodeIco(bytes);
        }
        if (looksLikeSvg(bytes)) {
            return null;
        }
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            return ImageIO.read(in);
        }
    }

    /** ICO: 解析 ICONDIR，取各条目中内嵌 PNG 的最大者 */
    private static Image decodeIco(byte[] ico) throws IOException {
        // ICONDIR 为 little-endian: reserved(2) type(2) count(2)
        int count = (ico[4] & 0xFF) | ((ico[5] & 0xFF) << 8);
        if (count <= 0 || count > 50) {
            return null;
        }
        int cursor = 6;
        Image best = null;
        int bestPixels = 0;
        for (int i = 0; i < count && cursor + 16 <= ico.length; i++) {
            int width = ico[cursor] & 0xFF;       // 0 表示 256
            int height = ico[cursor + 1] & 0xFF;
            int bytesInRes = readIntLE(ico, cursor + 8);
            int imageOffset = readIntLE(ico, cursor + 12);
            cursor += 16;
            if (imageOffset < 0 || imageOffset + bytesInRes > ico.length) {
                continue;
            }
            byte[] chunk = Arrays.copyOfRange(ico, imageOffset, imageOffset + bytesInRes);
            // 仅处理内嵌 PNG（现代 ICO 的主流形态）
            if (!isPngMagic(chunk)) {
                continue;
            }
            try (ByteArrayInputStream in = new ByteArrayInputStream(chunk)) {
                Image img = ImageIO.read(in);
                if (img != null) {
                    int pixels = (width == 0 ? 256 : width) * (height == 0 ? 256 : height);
                    if (pixels > bestPixels) {
                        best = img;
                        bestPixels = pixels;
                    }
                }
            }
        }
        return best;
    }

    private static int readIntLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static boolean isPngMagic(byte[] b) {
        return b.length >= 8 && b[0] == (byte) 0x89 && b[1] == (byte) 0x50
                && b[2] == (byte) 0x4E && b[3] == (byte) 0x47;
    }

    private static boolean looksLikeSvg(byte[] bytes) {
        String head = new String(bytes, 0, Math.min(bytes.length, 512), StandardCharsets.UTF_8);
        String lower = head.toLowerCase();
        return lower.contains("<svg");
    }

    /** data:image/png;base64,xxxx */
    private static byte[] decodeDataUri(String href) {
        int comma = href.indexOf(',');
        if (comma < 0) {
            return null;
        }
        String meta = href.substring(5, comma).toLowerCase();
        String payload = href.substring(comma + 1);
        if (!meta.contains("base64")) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] download(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("User-Agent", "WebGUI/1.0");
        conn.setInstanceFollowRedirects(true);
        try (InputStream in = conn.getInputStream()) {
            return in.readAllBytes();
        } finally {
            conn.disconnect();
        }
    }
}
