package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;

public class Proxy extends Spider {
    private static StringBuffer sb = new StringBuffer("<div style='color:#00FF00;'>--- 凱哥矩陣監聽系統已就緒 ---</div><br>");
    private static boolean isServerRunning = false;
    private static final int MY_LOG_PORT = 10086; 

    public static int getPort() { return 9978; }
    public static String getUrl() { return "http://127.0.0.1:9978/proxy"; }

    public static void log(String msg) {
        if (msg == null) return;
        // 內存守護
        if (sb.length() > 100000) {
            sb.delete(0, 50000); 
            sb.insert(0, "<div style='color:#008800;'>[系統] 緩存清理成功...</div><br>");
        }
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        sb.append("<div style='border-bottom:1px solid #111;padding:4px;'>")
          .append("<span style='color:#008800;'>[").append(time).append("]</span> ")
          .append("<span style='color:#00FF00;'>").append(msg).append("</span>")
          .append("</div>");
        
        if (!isServerRunning) startLegacyServer();
    }

    private static void startLegacyServer() {
        if (isServerRunning) return;
        new Thread(() -> {
            try (ServerSocket server = new ServerSocket(MY_LOG_PORT)) {
                server.setReuseAddress(true); // 🚀 關鍵：允許端口秒級重用
                isServerRunning = true;
                while (true) {
                    try {
                        Socket client = server.accept();
                        // 🚀 關鍵：極短超時，防止瀏覽器佔坑
                        client.setSoTimeout(500); 
                        new Thread(() -> {
                            // 🚀 使用 try-with-resources 自動關閉所有流
                            try (Socket s = client; OutputStream out = s.getOutputStream()) {
                                String content = sb.toString();
                                String response = "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: text/html; charset=utf-8\r\n" +
                                        "Connection: close\r\n" + // 🚀 強制要求客戶端關閉
                                        "Content-Length: " + content.getBytes("UTF-8").length + 2000 + "\r\n\r\n" +
                                        "<html><head><meta charset='utf-8'>" +
                                        "<title>KaiGe Log</title>" +
                                        "<meta http-equiv='refresh' content='1'>" + 
                                        "<style>body{background:#000;color:#00FF00;font-family:monospace;font-size:12px;line-height:1.2;}</style></head>" +
                                        "<body><div style='border:1px solid #004400;padding:8px;'>" + content + "</div></body></html>";
                                out.write(response.getBytes("UTF-8"));
                                out.flush();
                            } catch (Exception ignored) {}
                        }).start();
                    } catch (Exception e) {
                        Thread.sleep(100);
                    }
                }
            } catch (Exception e) {
                isServerRunning = false;
            }
        }).start();
    }

    public static Object[] proxy(Map<String, String> params) throws Exception {
        if (params == null) return null;
        if ("kaige_debug".equals(params.get("do"))) {
            String html = "<html><body style='background:#000;color:#00FF00;'>Port: <a href='http://127.0.0.1:10086' style='color:#00FF00;'>10086</a></body></html>";
            return new Object[]{200, "text/html; charset=utf-8", new ByteArrayInputStream(html.getBytes("UTF-8"))};
        }
        return null;
    }
}
