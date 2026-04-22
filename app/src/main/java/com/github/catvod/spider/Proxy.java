package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;

public class Proxy extends Spider {
    private static StringBuffer sb = new StringBuffer("<div style='color:#00FF00;'>--- 凱哥綠色監聽系統已啟動 ---</div><br>");
    private static boolean isServerRunning = false;
    private static final int MY_LOG_PORT = 10086; 

    public static int getPort() {
        return 9978;
    }

    public static String getUrl() {
        return "http://127.0.0.1:9978/proxy";
    }

    public static void log(String msg) {
        if (msg == null) return;
        if (sb.length() > 120000) {
            sb.delete(0, 60000); 
            sb.insert(0, "<div style='color:#008800;'>[系統] 歷史日誌已清理...</div><br>");
        }
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        sb.append("<div style='border-bottom:1px solid #1a1a1a;padding:5px;'>")
          .append("<span style='color:#008800;'>[").append(time).append("]</span> ")
          .append("<span style='color:#00FF00;'>").append(msg).append("</span>")
          .append("</div>");
        
        if (!isServerRunning) {
            startLegacyServer();
        }
    }

    private static void startLegacyServer() {
        if (isServerRunning) return;
        new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(MY_LOG_PORT);
                server.setReuseAddress(true); // 🚀 關鍵：允許端口快速重用
                isServerRunning = true;
                while (true) {
                    final Socket client = server.accept();
                    client.setSoTimeout(1500); // 🚀 防止死連接佔用
                    new Thread(() -> {
                        try (client; OutputStream out = client.getOutputStream()) {
                            String response = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: text/html; charset=utf-8\r\n" +
                                    "Connection: close\r\n\r\n" +
                                    "<html><head><meta charset='utf-8'>" +
                                    "<title>凱哥 Matrix Log</title>" +
                                    "<meta http-equiv='refresh' content='1'>" + 
                                    "<style>" +
                                    "body{background:#000000;color:#00FF00;font-family:monospace;padding:10px;font-size:12px;line-height:1.4;}" +
                                    ".log-box{background:#000000;padding:12px;border:1px solid #004400;word-wrap:break-word;}" +
                                    "b{color:#55FF55;text-shadow: 0 0 3px #00FF00;} " +
                                    "</style></head>" +
                                    "<body>" +
                                    "<h3 style='color:#00FF00;'>📟 凱哥綠色實時監聽 (Port: " + MY_LOG_PORT + ")</h3>" +
                                    "<div class='log-box'>" + sb.toString() + "</div>" +
                                    "</body></html>";
                            out.write(response.getBytes("UTF-8"));
                            out.flush();
                        } catch (Exception ignored) {}
                    }).start();
                }
            } catch (Exception e) {
                isServerRunning = false;
            }
        }).start();
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
