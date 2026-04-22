package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;

public class Proxy extends Spider {
    private static StringBuilder sb = new StringBuilder("<div style='color:#00FF00;'>--- 凱哥實時矩陣監聽系統啟動 ---</div><br>");
    private static boolean isServerRunning = false;
    private static final int MY_LOG_PORT = 10086;

    public static int getPort() { return 9978; }
    public static String getUrl() { return "http://127.0.0.1:9978/proxy"; }

    public static void log(String msg) {
        if (msg == null) return;
        if (sb.length() > 200000) sb.delete(0, 100000);
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        sb.append("<div class='line'>")
          .append("<span class='time'>[").append(time).append("]</span> ")
          .append("<span class='msg'>").append(msg).append("</span>")
          .append("</div>");
        if (!isServerRunning) startLegacyServer();
    }

    private static void startLegacyServer() {
        if (isServerRunning) return;
        new Thread(() -> {
            try (ServerSocket server = new ServerSocket(MY_LOG_PORT)) {
                server.setReuseAddress(true);
                isServerRunning = true;
                while (true) {
                    try (Socket client = server.accept(); OutputStream out = client.getOutputStream()) {
                        String content = sb.toString();
                        String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n" +
                                "<html><head><meta charset='utf-8'><title>KaiGe Debugger</title>" +
                                "<style>" +
                                "body{background:#000;color:#00FF00;font-family:monospace;font-size:12px;margin:0;padding:10px;line-height:1.5;}" +
                                ".time{color:#008800;margin-right:8px;}" +
                                ".line{border-bottom:1px solid #111;padding:2px 0;word-break:break-all;}" +
                                "h3{position:sticky;top:0;background:#000;margin:0;padding:10px 0;border-bottom:2px solid #00FF00;}" +
                                "</style></head><body>" +
                                "<h3>📟 凱哥實時監聽 (自動更新中...)</h3>" +
                                "<div id='log-container'>" + content + "</div>" +
                                "<script>" +
                                "let lastHtml = '';" +
                                "setInterval(() => {" +
                                "  fetch(location.href).then(r => r.text()).then(html => {" +
                                "    let parser = new DOMParser();" +
                                "    let doc = parser.parseFromString(html, 'text/html');" +
                                "    let newHtml = doc.getElementById('log-container').innerHTML;" +
                                "    if(newHtml !== lastHtml) {" +
                                "      document.getElementById('log-container').innerHTML = newHtml;" +
                                "      lastHtml = newHtml;" +
                                "      window.scrollTo(0, document.body.scrollHeight);" + // 🚀 自動滾動到底部
                                "    }" +
                                "  });" +
                                "}, 1000);" +
                                "</script></body></html>";
                        out.write(response.getBytes("UTF-8"));
                        out.flush();
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) { isServerRunning = false; }
        }).start();
    }

    public Object[] proxy(Map<String, String> params) {
        return null;
    }
}
