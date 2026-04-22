package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;

public class Proxy extends Spider {
    private static StringBuilder sb = new StringBuilder("<div style='color:#666;'>--- 凱哥實時監聽系統啟動 ---</div><br>");
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
                    try (Socket client = server.accept()) {
                        // 🚀 讀取請求，攔截清空指令
                        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                        String line = in.readLine();
                        if (line != null && line.contains("/?clean")) {
                            sb.setLength(0);
                            sb.append("<div style='color:#f00;'>--- 日誌已清空 ---</div><br>");
                        }

                        try (OutputStream out = client.getOutputStream()) {
                            String content = sb.toString();
                            String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n" +
                                    "<html><head><meta charset='utf-8'><title>KaiGe Debug</title>" +
                                    "<style>" +
                                    "body{background:#fff;color:#000;font-family:sans-serif;font-size:13px;margin:0;padding:10px;line-height:1.6;}" +
                                    ".time{color:#888;font-family:monospace;margin-right:8px;}" +
                                    ".line{border-bottom:1px solid #eee;padding:4px 0;word-break:break-all;}" +
                                    ".header{position:sticky;top:0;background:#fff;padding:10px 0;border-bottom:2px solid #000;display:flex;justify-content:space-between;align-items:center;z-index:99;}" +
                                    "button{background:#000;color:#fff;border:none;padding:6px 12px;font-size:12px;cursor:pointer;border-radius:4px;}" +
                                    "</style></head><body>" +
                                    "<div class='header'>" +
                                    "<b>📟 凱哥實時監聽</b>" +
                                    "<button onclick='fetch(\"/?clean\").then(()=>location.reload())'>🧹 清空日誌</button>" +
                                    "</div>" +
                                    "<div id='log-container'>" + content + "</div>" +
                                    "<script>" +
                                    "let lastHtml = '';" +
                                    "setInterval(() => {" +
                                    "  fetch(location.href).then(r => r.text()).then(html => {" +
                                    "    if(html.indexOf('log-container') === -1) return;" +
                                    "    let newHtml = html.split('id=\"log-container\">')[1].split('</div>')[0];" +
                                    "    if(newHtml !== lastHtml) {" +
                                    "      document.getElementById('log-container').innerHTML = newHtml;" +
                                    "      lastHtml = newHtml;" +
                                    "      window.scrollTo(0, document.body.scrollHeight);" + // 🚀 自動滾動
                                    "    }" +
                                    "  });" +
                                    "}, 1000);" +
                                    "</script></body></html>";
                            out.write(response.getBytes("UTF-8"));
                            out.flush();
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) { isServerRunning = false; }
        }).start();
    }

    public Object[] proxy(Map<String, String> params) {
        return null;
    }
}
