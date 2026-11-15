package code.rs6048;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.impl.progress.ConsoleProgressHandler;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 使用 JCEF (Java Chromium Embedded Framework) 加载 TurboWarp 打包的 HTML。
 * 基于 Chromium 146，完整支持 WebGL，可正常运行 Scratch 项目。
 *
 * 启动参数（均为可选，顺序任意，带前缀）：
 *   java -jar win.jar [-x 宽] [-y 高] [-html 文件名] [-fullscreen|-f]
 *
 *   -x / --x / -width    窗口宽；可写成 -x=800
 *   -y / --y / -height   窗口高；可写成 -y=600
 *   -html / --html       要打开的 HTML 文件，默认 Project.html
 *   -fullscreen / -full / -f   无边框全屏启动；不写就是窗口模式
 *
 *   -x、-y 省略时自动读取 HTML 的尺寸（先静态解析 HTML，
 *   解析不到再等页面加载后读取舞台/canvas 实际尺寸）
 *
 * 示例：
 *   java -jar win.jar -html Project.html
 *   java -jar win.jar -x 1024 -y 768 -html Project.html
 *   java -jar win.jar -f -html Project.html
 *   java -jar win.jar -html Project.html -x 800        （顺序任意，高自动探测）
 *
 * 窗口打开后不可缩放。
 */
public class Main {

    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 800;
    private static final String DEFAULT_HTML = "Project.html";

    public static void main(String[] args) {
        // 锁定 1:1 像素缩放：JOGL/GLCanvas 按物理像素渲染，若 Swing 坐标被系统 DPI
        // 放大（如 185%），OSR 绘制缓冲区会小于窗口实际尺寸，产生黑边/错位。
        // 必须在任何 AWT 类初始化之前设置。
        if (System.getProperty("sun.java2d.uiScale") == null) {
            System.setProperty("sun.java2d.uiScale", "1.0");
        }

        // stderr 实时落盘（AWT 线程异常不经过 UncaughtExceptionHandler，只能从 stderr 抓）
        redirectStderr();

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> logCrash("线程 " + t.getName(), e));

        // ── 解析启动参数（带前缀、顺序无关） ──
        int argWidth = -1;
        int argHeight = -1;
        String htmlName = null;
        boolean fullScreen = false;

        for (int i = 0; i < args.length; i++) {
            String raw = args[i];
            if (raw == null) continue;
            String t = raw.trim();
            if (t.isEmpty() || t.equals("-") || t.equalsIgnoreCase("auto")) continue;

            // 支持 -x / --x / -x=200 三种写法
            String key = t;
            String inlineValue = null;
            if (key.startsWith("--")) key = key.substring(2);
            else if (key.startsWith("-")) key = key.substring(1);
            int eq = key.indexOf('=');
            if (eq >= 0) {
                inlineValue = key.substring(eq + 1).trim();
                key = key.substring(0, eq).trim();
            }
            key = key.toLowerCase();

            switch (key) {
                case "x": case "w": case "width": {
                    argWidth = takeInt(args, i, t, inlineValue);
                    if (inlineValue == null) i++;
                    break;
                }
                case "y": case "h": case "height": {
                    argHeight = takeInt(args, i, t, inlineValue);
                    if (inlineValue == null) i++;
                    break;
                }
                case "html": case "file": {
                    htmlName = takeValue(args, i, t, inlineValue);
                    if (inlineValue == null) i++;
                    break;
                }
                case "fullscreen": case "full": case "f": {
                    fullScreen = true;
                    break;
                }
                default: {
                    // 兼容旧写法：纯数字依次当宽、高；其余不带前缀的当作 HTML 文件名
                    if (t.matches("\\d+")) {
                        int v;
                        try {
                            v = Integer.parseInt(t);
                        } catch (NumberFormatException nfe) {
                            continue;
                        }
                        if (v <= 0) continue;
                        if (argWidth < 0) {
                            argWidth = v;
                        } else if (argHeight < 0) {
                            argHeight = v;
                        }
                    } else if (!t.startsWith("-")) {
                        htmlName = t;
                    } else {
                        usage("未知参数: " + t);
                    }
                }
            }
        }
        if (htmlName == null || htmlName.isBlank()) {
            htmlName = DEFAULT_HTML;
        }

        final int fWidth = argWidth;
        final int fHeight = argHeight;
        final String fHtml = htmlName;
        final boolean fFull = fullScreen;

        SwingUtilities.invokeLater(() -> {
            try {
                // ── 1. 定位 HTML ──
                File htmlFile = new File(fHtml);
                if (!htmlFile.exists()) {
                    JOptionPane.showMessageDialog(null,
                            "找不到文件: " + htmlFile.getAbsolutePath() +
                                    "\n用法: java -jar win.jar [-x 宽] [-y 高] "
                                    + "[-html 文件名] [-fullscreen|-f]",
                            "文件缺失", JOptionPane.ERROR_MESSAGE);
                    System.exit(1);
                }

                // ── 2. 决定窗口尺寸：参数优先，其次读取 HTML 尺寸 ──
                boolean needAuto = (fWidth < 0 || fHeight < 0) && !fFull;
                Dimension probed = needAuto ? probeHtmlSize(htmlFile) : null;
                Dimension size = null;
                if (!needAuto) {
                    size = new Dimension(Math.max(fWidth, 1), Math.max(fHeight, 1));
                } else {
                    int w = (fWidth > 0) ? fWidth : (probed != null ? probed.width : DEFAULT_WIDTH);
                    int h = (fHeight > 0) ? fHeight : (probed != null ? probed.height : DEFAULT_HEIGHT);
                    size = new Dimension(w, h);
                    System.err.println("[WebGUI] 宽高未指定，尺寸取自"
                            + (probed != null ? "HTML 解析" : "默认值") + ": " + w + "x" + h);
                }

                // ── 3. 初始化 CefApp ──
                CefAppBuilder builder = new CefAppBuilder();
                builder.setInstallDir(new File("jcef-bundle"));
                builder.setProgressHandler(new ConsoleProgressHandler());
                builder.getCefSettings().windowless_rendering_enabled = true;

                CefApp cefApp = builder.build();

                // ── 4. 创建 Client 与 Browser ──
                CefClient client = cefApp.createClient();

                CefMessageRouter router = CefMessageRouter.create();
                FileMessageRouterHandler fileHandler = new FileMessageRouterHandler();
                router.addHandler(fileHandler, true);
                client.addMessageRouter(router);

                String url = "file:///" + htmlFile.getAbsolutePath().replace("\\", "/");

                // OSR 模式（离屏渲染）
                CefBrowser browser = client.createBrowser(url, true, false);

                // ── 5. 创建窗口 ──
                JFrame frame = new JFrame(fFull ? "" : "Loading...");
                if (fFull) {
                    frame.setUndecorated(true);   // 全屏：无标题栏、无边框
                }
                frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                // 打开后不可缩放：JCEF 的离屏渲染在运行期反复改尺寸时会出现黑区/绘制错位，
                // 因此窗口尺寸只在启动时由参数（或 HTML 探测）决定，运行期禁止拖拽缩放。
                frame.setResizable(false);
                frame.setBackground(Color.WHITE);

                final Component browserUI = browser.getUIComponent();
                frame.getContentPane().setLayout(new BorderLayout());
                frame.getContentPane().add(browserUI, BorderLayout.CENTER);

                // 尺寸变化后强制 GLCanvas 同步布局并重绘，避免绘制溢出产生黑边。
                // 反复缩放时 Chromium 的新尺寸帧到达有延迟，只通知一次仍会残留黑区，
                // 因此连续补发多次（150/500/1200ms），让每一轮都按最终视口重渲染。
                final Timer[] settle = new Timer[3];
                final int[] delays = {150, 500, 1200};
                final Runnable onSettle = new Runnable() {
                    @Override
                    public void run() {
                        for (Timer t : settle) if (t != null) t.stop();
                        for (int i = 0; i < delays.length; i++) {
                            final int idx = i;
                            settle[i] = new Timer(delays[i], ev -> {
                                syncBrowser(browserUI);
                                forceNotifiedResize(browser, browserUI);
                                logGeom("afterResize", browser, browserUI);
                            });
                            settle[i].setRepeats(false);
                            settle[i].start();
                        }
                    }
                };
                browserUI.addComponentListener(new ComponentAdapter() {
                    @Override
                    public void componentResized(ComponentEvent e) {
                        onSettle.run();
                    }
                });

                // favicon 回调
                fileHandler.setIconCallback(img -> SwingUtilities.invokeLater(() -> {
                    if (img != null) frame.setIconImage(img);
                }));

                // 页面回传的实际内容尺寸：仅在「宽高未指定、非全屏、静态解析失败」时应用一次
                final AtomicBoolean sizeApplied = new AtomicBoolean(false);
                if (needAuto && probed == null) {
                    fileHandler.setSizeCallback(dim -> SwingUtilities.invokeLater(() -> {
                        // 自适应铺满页面时 canvas 尺寸≈窗口尺寸，这种值没有意义，忽略
                        int cw = frame.getWidth();
                        int ch = frame.getHeight();
                        boolean looksAdaptive = cw > 0 && ch > 0
                                && Math.abs(dim.width - cw) <= Math.max(64, cw / 8)
                                && Math.abs(dim.height - ch) <= Math.max(64, ch / 8);
                        if (looksAdaptive) {
                            System.err.println("[WebGUI] 忽略自适应画布尺寸: "
                                    + dim.width + "x" + dim.height);
                            return;
                        }
                        if (sizeApplied.compareAndSet(false, true)) {
                            System.err.println("[WebGUI] 按页面实际尺寸调整窗口: "
                                    + dim.width + "x" + dim.height);
                            frame.setSize(dim.width, dim.height);
                            frame.setLocationRelativeTo(null);
                            syncBrowser(browserUI);
                        }
                    }));
                }

                client.addDisplayHandler(new CefDisplayHandlerAdapter() {
                    @Override
                    public void onTitleChange(CefBrowser b, String title) {
                        SwingUtilities.invokeLater(() -> {
                            if (title != null && !title.isEmpty()) {
                                frame.setTitle(title);
                            }
                        });
                    }
                });

                // 页面加载完成后：白色背景 + favicon 提取 + 内容尺寸回传
                client.addLoadHandler(new CefLoadHandlerAdapter() {
                    @Override
                    public void onLoadEnd(CefBrowser b, CefFrame cefFrame, int httpStatusCode) {
                        String js = """
                                (function() {
                                  // 强制白色背景，覆盖 OSR GLCanvas 黑色清除色
                                  document.documentElement.style.setProperty('background', '#ffffff', 'important');
                                  document.body.style.setProperty('background', '#ffffff', 'important');

                                  // 回传页面实际内容尺寸（选面积最大的 canvas，避免取到辅助画布）
                                  try {
                                    var best = null, bestArea = -1;
                                    var list = document.querySelectorAll('canvas');
                                    for (var i = 0; i < list.length; i++) {
                                      var r = list[i].getBoundingClientRect();
                                      var area = r.width * r.height;
                                      if (area > bestArea) { bestArea = area; best = list[i]; }
                                    }
                                    var w = best ? best.width : 0;
                                    var h = best ? best.height : 0;
                                    if (w > 0 && h > 0 && window.cefQuery) {
                                      window.cefQuery({
                                        request: JSON.stringify({action: '__setContentSize', width: w, height: h}),
                                        onSuccess: function(r) {},
                                        onFailure: function(c2, m) {}
                                      });
                                    }
                                  } catch (e) {}

                                  // favicon：用 canvas 栅格化（含 SVG），保留透明通道
                                  function pickIcon() {
                                    var sels = [
                                      'link[rel="apple-touch-icon"]',
                                      'link[rel="shortcut icon"]',
                                      'link[rel="icon"]'
                                    ];
                                    for (var i = 0; i < sels.length; i++) {
                                      var el = document.querySelector(sels[i]);
                                      if (el && el.href) return el.href;
                                    }
                                    return null;
                                  }
                                  var href = pickIcon();
                                  if (!href) return;
                                  var img = new Image();
                                  img.crossOrigin = 'anonymous';
                                  img.onload = function() {
                                    try {
                                      var canvas = document.createElement('canvas');
                                      var size = 64;
                                      canvas.width = size;
                                      canvas.height = size;
                                      var ctx = canvas.getContext('2d');
                                      // 不填充背景，保留透明通道
                                      var scale = Math.min(size / img.width, size / img.height);
                                      var dw = img.width * scale;
                                      var dh = img.height * scale;
                                      var dx = (size - dw) / 2;
                                      var dy = (size - dh) / 2;
                                      ctx.drawImage(img, dx, dy, dw, dh);
                                      var dataUrl = canvas.toDataURL('image/png');
                                      if (window.cefQuery) {
                                        window.cefQuery({
                                          request: JSON.stringify({action: '__setWindowIcon', data: dataUrl}),
                                          onSuccess: function(r) {},
                                          onFailure: function(c3, m) {}
                                        });
                                      }
                                    } catch (e) {}
                                  };
                                  img.onerror = function() {};
                                  img.src = href;
                                })();
                                """;
                        b.executeJavaScript(js, url, 0);
                    }
                });

                // 后台用纯 Java 预读文件型 favicon（PNG/ICO）
                Path htmlPath = htmlFile.toPath();
                new Thread(() -> {
                    Image icon = FaviconLoader.loadFavicon(htmlPath);
                    if (icon != null) {
                        SwingUtilities.invokeLater(() -> frame.setIconImage(icon));
                    }
                }, "favicon-loader").start();

                // ── 6. 定位与显示 ──
                GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice();

                if (fFull) {
                    frame.setBounds(gd.getDefaultConfiguration().getBounds());
                    frame.setVisible(true);
                    // 不使用 setFullScreenWindow（独占全屏会强制占据最上层、抢焦点），
                    // 也不调用 setAlwaysOnTop：普通 Z 序，可被其他窗口覆盖、可 Alt+Tab 切走。
                } else {
                    frame.setSize(size);
                    frame.setLocationRelativeTo(null);
                    frame.setVisible(true);
                }

                // ── 7. 关闭时释放资源 ──
                frame.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        browser.close(true);
                        cefApp.dispose();
                        System.exit(0);
                    }
                });

            } catch (Throwable ex) {
                logCrash("初始化", ex);
                JOptionPane.showMessageDialog(null,
                        "JCEF 初始化失败:\n" + ex,
                        "启动错误", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }

    /**
     * 静态解析 HTML 得到内容尺寸。
     * 依次尝试：viewport 中带像素的宽高 → canvas 标签的 width/height →
     * html/body 内联样式中的 px 宽高。全部失败返回 null。
     */
    private static Dimension probeHtmlSize(File html) {
        try {
            String s = new String(Files.readAllBytes(html.toPath()), StandardCharsets.UTF_8);

            // 1) <meta name="viewport" content="width=..., height=...">
            Matcher mv = Pattern.compile(
                    "<meta[^>]*name\\s*=\\s*[\"']viewport[\"'][^>]*content\\s*=\\s*[\"']([^\"']*)[\"']",
                    Pattern.CASE_INSENSITIVE).matcher(s);
            if (mv.find()) {
                String content = mv.group(1);
                int w = numberAfterKey(content, "width");
                int h = numberAfterKey(content, "height");
                if (w > 0 && h > 0) return new Dimension(w, h);
            }

            // 2) <canvas width="..." height="...">
            Matcher mc = Pattern.compile("<canvas\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(s);
            while (mc.find()) {
                String tag = mc.group();
                int w = attrNumber(tag, "width");
                int h = attrNumber(tag, "height");
                if (w > 0 && h > 0) return new Dimension(w, h);
            }

            // 3) html / body 内联样式 width:NNNpx; height:NNNpx
            Matcher ms = Pattern.compile(
                    "(?:<html|<body)[^>]*style\\s*=\\s*[\"']([^\"']*)[\"']",
                    Pattern.CASE_INSENSITIVE).matcher(s);
            while (ms.find()) {
                String style = ms.group(1);
                int w = numberAfterKey(style, "width");
                int h = numberAfterKey(style, "height");
                if (w > 0 && h > 0) return new Dimension(w, h);
            }
        } catch (Throwable ignored) {
            // 解析失败视为未探测到
        }
        return null;
    }

    /** 在 "key=123" 或 "key: 123px" 里取数字 */
    private static int numberAfterKey(String text, String key) {
        Matcher m = Pattern.compile(
                "\\b" + Pattern.quote(key) + "\\s*[:=]\\s*(\\d+)",
                Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    /** 从标签字符串里取属性数字，如 width="480" */
    private static int attrNumber(String tag, String attr) {
        Matcher m = Pattern.compile(
                "\\b" + Pattern.quote(attr) + "\\s*=\\s*[\"']?(\\d+)",
                Pattern.CASE_INSENSITIVE).matcher(tag);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    /** 取参数值：支持 -flag value 与 -flag=value 两种写法 */
    private static String takeValue(String[] args, int i, String flag, String inline) {
        if (inline != null) {
            if (inline.isEmpty()) {
                usage("参数 " + flag + " 缺少取值");
                return "";
            }
            return inline;
        }
        if (i + 1 >= args.length) {
            usage("参数 " + flag + " 缺少取值");
            return "";
        }
        return args[i + 1] == null ? "" : args[i + 1].trim();
    }

    /** 取正整数参数值 */
    private static int takeInt(String[] args, int i, String flag, String inline) {
        String v = takeValue(args, i, flag, inline);
        int n;
        try {
            n = Integer.parseInt(v);
        } catch (NumberFormatException nfe) {
            usage("参数 " + flag + " 需要正整数，实际为: " + v);
            return -1;
        }
        if (n <= 0) {
            usage("参数 " + flag + " 需要正整数，实际为: " + v);
        }
        return n;
    }

    /** 参数错误时打印用法并退出 */
    private static void usage(String msg) {
        System.err.println("[WebGUI] 参数错误: " + msg);
        System.err.println("用法: java -jar win.jar [-x 宽] [-y 高] "
                + "[-html 文件名] [-fullscreen|-f]（顺序任意）");
        System.exit(2);
    }

    /**
     * 主动通知 Chromium 视口尺寸已变化。
     * JCEF 的 CefBrowserOsr 只在特定时机调用 wasResized，反复缩放时容易漏通知，
     * 导致渲染只覆盖旧尺寸区域、其余区域呈黑色（窗口越大黑区越大）。
     * 这里每次尺寸变化都补一次 wasResized(w, h)，让 Chromium 按新视口重新渲染整帧。
     */
    private static void forceNotifiedResize(CefBrowser browser, Component ui) {
        int w = ui.getWidth();
        int h = ui.getHeight();
        if (w <= 0 || h <= 0) return;
        try {
            Class<?> cls = Class.forName("org.cef.browser.CefBrowser_N");
            java.lang.reflect.Method m = cls.getDeclaredMethod("wasResized", int.class, int.class);
            m.setAccessible(true);
            m.invoke(browser, w, h);
            System.err.println("[FORCERESIZE] notified " + w + "x" + h);
        } catch (Throwable t) {
            System.err.println("[FORCERESIZE] failed: " + t);
        }
    }

    /**
     * 几何自检：对比 Swing 组件尺寸与 OSR 绘制区域尺寸。
     * 两者一致说明渲染区与窗口对齐（不会出现黑边）；不一致即绘制区溢出/不足。
     * 结果写入 stderr.log，用于缩放压力测试取证。
     */
    private static void logGeom(String tag, CefBrowser browser, Component ui) {
        Rectangle osr = osrRect(browser);
        int uw = ui.getWidth();
        int uh = ui.getHeight();
        boolean match = osr != null && osr.width == uw && osr.height == uh;
        System.err.println("[GEOM] " + tag + " ui=" + uw + "x" + uh
                + " osr=" + (osr == null ? "?" : osr.width + "x" + osr.height)
                + " match=" + match);
    }

    /** 反射读取 CefBrowserOsr 的绘制区域 browser_rect_ */
    private static Rectangle osrRect(CefBrowser browser) {
        try {
            java.lang.reflect.Field f = browser.getClass().getDeclaredField("browser_rect_");
            f.setAccessible(true);
            return (Rectangle) f.get(browser);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 强制浏览器组件重新布局并重绘，消除尺寸变化后的残留/黑边 */
    private static void syncBrowser(Component ui) {
        SwingUtilities.invokeLater(() -> {
            ui.invalidate();
            ui.validate();
            ui.repaint();
        });
        Timer t = new Timer(250, e -> {
            ui.invalidate();
            ui.validate();
            ui.repaint();
        });
        t.setRepeats(false);
        t.start();
    }

    /** 把异常完整写入 crash.log（进程退出后可读） */
    private static synchronized void logCrash(String label, Throwable e) {
        try (java.io.PrintWriter pw = new java.io.PrintWriter(
                new java.io.FileWriter("crash.log", true))) {
            pw.println("==== [" + label + "] " + new java.util.Date() + " ====");
            e.printStackTrace(pw);
            pw.flush();
        } catch (Throwable ignored) {
            // 写日志失败时忽略
        }
        System.err.println("[WebGUI] " + label + ": " + e);
        e.printStackTrace();
    }

    /** 复制 stderr 到 stderr.log 并逐字节 flush，保证异常堆栈完整落盘 */
    private static void redirectStderr() {
        try {
            final PrintStream orig = System.err;
            final FileOutputStream fos = new FileOutputStream("stderr.log", true);
            System.setErr(new PrintStream(new OutputStream() {
                @Override
                public void write(int b) {
                    try {
                        fos.write(b);
                        fos.flush();
                    } catch (Throwable ignored) {
                    }
                    try {
                        orig.write(b);
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    try {
                        fos.write(b, off, len);
                        fos.flush();
                    } catch (Throwable ignored) {
                    }
                    try {
                        orig.write(b, off, len);
                    } catch (Throwable ignored) {
                    }
                }
            }, true));
        } catch (Throwable ignored) {
            // 无法重定向时继续运行
        }
    }
}
