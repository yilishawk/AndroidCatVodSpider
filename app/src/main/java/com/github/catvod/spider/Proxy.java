package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;

public class Proxy extends Spider {
    private static StringBuilder sb = new StringBuilder("--- 凱哥獨立端口日誌系統已啟動 ---<br>");
    private static boolean isServerRunning = false;
    
    // 🚀 凱哥，這裡改你想要的端口
    private static final int MY_LOG_PORT = 10086; 

    public static int getPort() {
        return 9978; // 殼子原有的代理端口保持不變，兼容其他功能
    }

    public static String getUrl() {
        return "http://127.0.0.1:9978/proxy";
    }

    // 統一日誌出口
    public static void log(String msg) {
        synchronized (sb) {
            if (sb.length() > 150000) sb.setLength(0);
            String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
            sb.append("<div style='border-bottom:1px solid #333;padding:3px;'>")
              .append("<span style='color:#888;'>[").append(time).append("]</span> ")
              .append(msg).append("</div>");
        }
        // 第一次調用 log 時，如果伺服器沒開，就啟動它
        if (!isServerRunning) {
            startLegacyServer();
        }
    }

    // 🛠️ 另起爐灶：在獨立端口開啟 HTTP 服務
    private static void startLegacyServer() {
        if (isServerRunning) return;
        new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(MY_LOG_PORT);
                isServerRunning = true;
                while (true) {
                    Socket client = server.accept();
                    new Thread(() -> {
                        try {
                            OutputStream out = client.getOutputStream();
                            String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n\r\n" +
                                    "<html><head><meta charset='utf-8'><meta http-equiv='refresh' content='2'>" +
                                    "<style>body{background:#0d1117;color:#58a6ff;font-family:monospace;padding:20px;line-height:1.4;}</style></head>" +
                                    "<body><h2 style='color:#fff;'>🚀 凱哥獨立監聽端 (Port: " + MY_LOG_PORT + ")</h2>" +
                                    "<div style='background:#161b22;padding:15px;border-radius:6px;border:1px solid #30363d;'>" + sb.toString() + "</div>" +
                                    "<script>window.scrollTo(0,document.body.scrollHeight);</script></body></html>";
                            out.write(response.getBytes("UTF-8"));
                            out.flush();
                            client.close();
                        } catch (Exception ignored) {}
                    }).start();
                }
            } catch (Exception e) {
                isServerRunning = false;
            }
        }).start();
    }

    public static Object[] proxy(Map<String, String> params) throws Exception {
        // 原有的 proxy 邏輯保持不變，作為備用
        if (params == null) return null;
        Object doObj = params.get("do");
        String action = (doObj instanceof String[]) ? ((String[]) doObj)[0] : String.valueOf(doObj);

        if ("kaige_debug".equals(action)) {
            String html = "<html><head><meta charset='utf-8'></head><body>使用新端口查看：http://127.0.0.1:" + MY_LOG_PORT + "</body></html>";
            return new Object[]{200, "text/html; charset=utf-8", new ByteArrayInputStream(html.getBytes("UTF-8"))};
        }
        return null;
    }
}
