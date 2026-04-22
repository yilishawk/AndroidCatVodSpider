package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;

public class Proxy extends Spider {
    private static StringBuffer sb = new StringBuffer("<div style='color:#00FF00;'>--- 凱哥實時矩陣監聽已就緒 ---</div><br>");
    private static boolean isServerRunning = false;
    private static final int MY_LOG_PORT = 10086;

    public static void log(String msg) {
        if (msg == null) return;
        if (sb.length() > 200000) sb.delete(0, 100000);
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
                server.setReuseAddress(true);
                isServerRunning = true;
                while (true) {
                    Socket client = server.accept();
                    new Thread(() -> {
                        try (client; OutputStream out = client.getOutputStream()) {
                            String content = sb.toString();
                            String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n" +
                                    "<html><head><meta charset='utf-8'><title>KaiGe Matrix</title>" +
                                    "<style>body{background:#000;color:#00FF00;font-family:monospace;font-size:11px;margin:0;padding:10px;}" +
                                    "#logs{word-wrap:break-word;padding-bottom:100px;}</style></head>" +
                                    "<body>" +
                                    "<h3>📟 凱哥實時監聽面板</h3>" +
                                    "<div id='logs'>" + content + "</div>" +
                                    "<script>" +
                                    "let lastContent = '';" +
                                    "function fetchLogs(){" +
                                    "  fetch(window.location.href).then(r=>r.text()).then(html=>{" +
                                    "    let parser = new DOMParser();" +
                                    "    let doc = parser.parseFromString(html, 'text/html');" +
                                    "    let newContent = doc.getElementById('logs').innerHTML;" +
                                    "    if(newContent !== lastContent){" +
                                    "      document.getElementById('logs').innerHTML = newContent;" +
                                    "      lastContent = newContent;" +
                                    "      if(window.scrollY + window.innerHeight >= document.body.scrollHeight - 150){" +
                                    "        window.scrollTo(0, document.body.scrollHeight);" +
                                    "      }" +
                                    "    }" +
                                    "  });" +
                                    "}" +
                                    "setInterval(fetchLogs, 1000);" + // 🚀 每秒無感刷新
                                    "</script>" +
                                    "</body></html>";
                            out.write(response.getBytes("UTF-8"));
                            out.flush();
                        } catch (Exception ignored) {}
                    }).start();
                }
            } catch (Exception e) { isServerRunning = false; }
        }).start();
    }

    public static Object[] proxy(Map<String, String> params) throws Exception {
        return null;
    }
}
