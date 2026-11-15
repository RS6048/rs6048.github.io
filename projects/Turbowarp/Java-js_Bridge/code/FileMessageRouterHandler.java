package code.rs6048;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * JS ↔ Java 文件互通桥接。
 *
 * 协议（JSON over cefQuery）：
 *   请求: {"action":"readFile","path":"...","data":"..."}
 *   响应: {"ok":true,"data":"..."} 或 {"ok":false,"error":"..."}
 *
 * 支持的动作:
 *   readFile   — 读取文件，data=文件内容(UTF-8)
 *   writeFile  — 覆盖写入，data=要写入的内容
 *   appendFile — 追加写入，data=要追加的内容
 *   listDir    — 列出目录条目，data=JSON数组字符串
 *   exists     — 检查文件/目录是否存在，data="true"/"false"
 *   deleteFile — 删除文件或空目录
 *   makeDir    — 递归创建目录
 *   fileSize   — 文件字节数，data=数字字符串
 *
 * 安全说明：
 *   默认不限制路径（等同当前用户权限）。如要限制 Scratch 只能访问
 *   指定目录，打开下面的 ROOT_LIMIT 并把路径设为允许的根目录即可；
 *   所有路径都会先 normalize 后再校验前缀，防止 "../" 逃逸。
 */
public class FileMessageRouterHandler extends CefMessageRouterHandlerAdapter {

    /** 设为非空时，只允许访问该目录及其子目录；null 表示不限制。 */
    private static final Path ROOT_LIMIT = null;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    /** 窗口图标回调：JS 回传栅格化后的 favicon PNG 后触发。 */
    private volatile Consumer<Image> iconCallback;

    /** 内容尺寸回调：页面加载后 JS 回传舞台/canvas 实际尺寸时触发。 */
    private volatile Consumer<java.awt.Dimension> sizeCallback;

    public void setIconCallback(Consumer<Image> callback) {
        this.iconCallback = callback;
    }

    public void setSizeCallback(Consumer<java.awt.Dimension> callback) {
        this.sizeCallback = callback;
    }

    @Override
    public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId,
                           String request, boolean persistent, CefQueryCallback callback) {
        // 在后台线程执行文件操作，避免阻塞 CEF UI 线程；
        // CefQueryCallback.success/failure 线程安全，可在任意线程调用。
        executor.submit(() -> {
            String response;
            try {
                JSONObject req = new JSONObject(request);
                JSONObject resp = dispatch(req.optString("action", ""), req);
                response = resp.toString();
            } catch (Exception e) {
                JSONObject err = new JSONObject();
                err.put("ok", false);
                err.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
                response = err.toString();
            }
            callback.success(response);
        });
        return true; // 已处理，阻止进一步传播
    }

    private JSONObject dispatch(String action, JSONObject req) {
        JSONObject resp = new JSONObject();
        try {
            switch (action) {
                case "readFile": {
                    Path p = resolve(req.getString("path"));
                    String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    resp.put("ok", true).put("data", content);
                    break;
                }
                case "writeFile": {
                    Path p = resolve(req.getString("path"));
                    Files.createDirectories(p.toAbsolutePath().getParent());
                    Files.write(p, req.getString("data").getBytes(StandardCharsets.UTF_8));
                    resp.put("ok", true).put("data", "");
                    break;
                }
                case "appendFile": {
                    Path p = resolve(req.getString("path"));
                    Files.createDirectories(p.toAbsolutePath().getParent());
                    Files.write(p, req.getString("data").getBytes(StandardCharsets.UTF_8),
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                    resp.put("ok", true).put("data", "");
                    break;
                }
                case "listDir": {
                    Path p = resolve(req.getString("path"));
                    JSONArray arr = new JSONArray();
                    try (var stream = Files.list(p)) {
                        List<Path> items = stream.sorted().toList();
                        for (Path item : items) {
                            JSONObject entry = new JSONObject();
                            entry.put("name", item.getFileName().toString());
                            entry.put("isDir", Files.isDirectory(item));
                            entry.put("size", Files.isDirectory(item) ? 0 : Files.size(item));
                            arr.put(entry);
                        }
                    }
                    resp.put("ok", true).put("data", arr.toString());
                    break;
                }
                case "exists": {
                    Path p = resolve(req.getString("path"));
                    resp.put("ok", true).put("data", String.valueOf(Files.exists(p)));
                    break;
                }
                case "deleteFile": {
                    Path p = resolve(req.getString("path"));
                    Files.deleteIfExists(p);
                    resp.put("ok", true).put("data", "");
                    break;
                }
                case "makeDir": {
                    Path p = resolve(req.getString("path"));
                    Files.createDirectories(p);
                    resp.put("ok", true).put("data", "");
                    break;
                }
                case "fileSize": {
                    Path p = resolve(req.getString("path"));
                    resp.put("ok", true).put("data", String.valueOf(Files.size(p)));
                    break;
                }
                case "__setWindowIcon": {
                    // JS 通过 canvas 将 favicon（含 SVG）栅格化为 PNG data URL 回传。
                    // 格式: data:image/png;base64,xxxx
                    String dataUrl = req.optString("data", "");
                    Consumer<Image> cb = this.iconCallback;
                    if (cb != null && dataUrl.startsWith("data:image/")) {
                        int comma = dataUrl.indexOf(',');
                        if (comma > 0) {
                            byte[] png = Base64.getDecoder().decode(dataUrl.substring(comma + 1));
                            Image img = ImageIO.read(new ByteArrayInputStream(png));
                            if (img != null) {
                                cb.accept(img);
                            }
                        }
                    }
                    resp.put("ok", true).put("data", "");
                    break;
                }
                case "__setContentSize": {
                    // 页面加载后 JS 回传舞台/canvas 的实际尺寸，用于自动设定窗口大小
                    int w = req.optInt("width", 0);
                    int h = req.optInt("height", 0);
                    Consumer<java.awt.Dimension> cb = this.sizeCallback;
                    if (cb != null && w > 0 && h > 0) {
                        cb.accept(new java.awt.Dimension(w, h));
                    }
                    resp.put("ok", true).put("data", "");
                    break;
                }
                default:
                    resp.put("ok", false).put("error", "未知动作: " + action);
            }
        } catch (Exception e) {
            String msg = e instanceof IOException io
                    ? io.getMessage() : (e.getMessage() == null ? e.toString() : e.getMessage());
            resp.put("ok", false).put("error", msg);
        }
        return resp;
    }

    /** 解析路径并应用根目录限制（normalize 防 ../ 逃逸）。 */
    private Path resolve(String raw) throws IOException {
        if (raw == null || raw.isBlank()) {
            throw new IOException("路径不能为空");
        }
        Path p = Paths.get(raw).normalize().toAbsolutePath();
        if (ROOT_LIMIT != null) {
            Path root = ROOT_LIMIT.toAbsolutePath().normalize();
            if (!p.startsWith(root)) {
                throw new IOException("访问被拒绝: 路径超出允许的根目录 " + root);
            }
        }
        return p;
    }
}
