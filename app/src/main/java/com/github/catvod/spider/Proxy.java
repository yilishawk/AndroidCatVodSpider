package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Proxy extends Spider {
    private static final int MY_LOG_PORT = 10086;
    private static final int CACHE_REFRESH_MS = 300;   // 缓存刷新间隔
    private static final int MAX_LOG_LEN = 120000;
    private static final int TRIM_KEEP = 60000;

    private static final StringBuffer sb = new StringBuffer("<div style='color:#00FF00;'>--- 凱哥綠色監聽系統已啟動 ---</div><br>");
    private static volatile String cachedHtml = "";
    private static volatile boolean isServerRunning = false;
    private static final ExecutorService threadPool = Executors.newFixedThreadPool(4);
    private static final Object logLock = new Object();

    static {
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(CACHE_REFRESH_MS);
                    refreshCache();
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }).start();
    }

    private static void refreshCache() {
        String content;
        synchronized (logLock) {
            content = sb.toString();
        }
        String newHtml = "<html><head><meta charset='utf-8'><title>凱哥 Matrix Log</title>" +
                "<meta http-equiv='refresh' content='2'>" +
                "<style>body{background:#000000;color:#00FF00;font-family:monospace;padding:10px;font-size:12px;}" +
                ".log-box{background:#000000;padding:12px;border:1px solid #004400;word-wrap:break-word;}</style></head>" +
                "<body><h3 style='color:#00FF00;'>📟 凱哥綠色實時監聽 (Port: " + MY_LOG_PORT + ")</h3>" +
                "<div class='log-box'>" + content + "</div></body></html>";
        cachedHtml = newHtml;
    }

    public static void log(String msg) {
        if (msg == null) return;
        synchronized (logLock) {
            if (sb.length() > MAX_LOG_LEN) {
                sb.delete(0, TRIM_KEEP);
                sb.insert(0, "<div style='color:#008800;'>[系統] 歷史日誌已清理...</div><br>");
            }
            String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
            sb.append("<div style='border-bottom:1px solid #1a1a1a;padding:5px;'>")
              .append("<span style='color:#008800;'>[").append(time).append("]</span> ")
              .append("<span style='color:#00FF00;'>").append(msg).append("</span>")
              .append("</div>");
        }
        if (!isServerRunning) {
            startLegacyServer();
        }
    }

    private static void startLegacyServer() {
        if (isServerRunning) return;
        synchronized (Proxy.class) {
            if (isServerRunning) return;
            isServerRunning = true;
        }
        new Thread(() -> {
            try (ServerSocket server = new ServerSocket(MY_LOG_PORT)) {
                server.setReuseAddress(true);
                while (true) {
                    Socket client = server.accept();
                    client.setSoTimeout(2000);
                    threadPool.submit(() -> {
                        try (client; OutputStream out = client.getOutputStream()) {
                            String response = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: text/html; charset=utf-8\r\n" +
                                    "Connection: close\r\n\r\n" +
                                    cachedHtml;
                            out.write(response.getBytes("UTF-8"));
                            out.flush();
                        } catch (Exception ignored) {}
                    });
                }
            } catch (Exception e) {
                isServerRunning = false;
            }
        }).start();
    }

    // ========== 以下为原有 9978 相关方法，保留不变 ==========
    public static int getPort() {
        return 9978;
    }

    public static String getUrl() {
        return "http://127.0.0.1:9978/proxy";
    }

    public static Object[] proxy(Map<String, String> params) throws Exception {
        if (params == null) return null;
        Object action = params.get("do");
        if ("kaige_debug".equals(action)) {
            String html = "<html><body style='background:#000;color:#00FF00;'>端口：<a href='http://127.0.0.1:10086' style='color:#00FF00;'>10086</a></body></html>";
            return new Object[]{200, "text/html; charset=utf-8", new ByteArrayInputStream(html.getBytes("UTF-8"))};
        }
        return null;
    }
}
