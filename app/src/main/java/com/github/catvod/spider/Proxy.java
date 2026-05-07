package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class Proxy extends Spider {
    private static StringBuilder sb = new StringBuilder("<div style='color:#888;'>--- 凱哥全能矩陣引擎已啟動 ---</div>");
    private static boolean isServerRunning = false;
    private static final int MY_LOG_PORT = 10086;          // 日志面板端口（保持不变）

    public static int getPort() { return 9978; }           // 这个返回值可能被其他模块使用，保留
    public static String getUrl() { return "http://127.0.0.1:9978/proxy"; }

    public static void log(String msg) {
        if (msg == null) return;
        if (sb.length() > 200000) sb.delete(0, 100000);
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        sb.append("<div class='line'><span class='time'>[").append(time).append("]</span> ")
          .append("<span class='msg'>").append(msg).append("</span></div>");
        if (!isServerRunning) startLegacyServer();
    }

    // 原有的 10086 端口日志服务（完全未改动）
    private static void startLegacyServer() {
        if (isServerRunning) return;
        new Thread(() -> {
            try (ServerSocket server = new ServerSocket(MY_LOG_PORT)) {
                server.setReuseAddress(true);
                isServerRunning = true;
                while (true) {
                    try (Socket client = server.accept()) {
                        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                        String req = in.readLine();
                        if (req == null) continue;

                        try (OutputStream out = client.getOutputStream()) {
                            if (req.contains("/?clean")) {
                                sb.setLength(0);
                                sb.append("<div style='color:red;'>--- 日誌已手動清空 ---</div>");
                                out.write("HTTP/1.1 200 OK\r\n\r\nOK".getBytes());
                            } else if (req.contains("/get_logs")) {
                                String data = sb.toString();
                                String resp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\n" + data;
                                out.write(resp.getBytes("UTF-8"));
                            } else {
                                String html = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n\r\n" +
                                        "<html><head><meta charset='utf-8'><style>" +
                                        "body{background:#fff;color:#000;font-family:monospace;font-size:12px;margin:0;padding:10px;}" +
                                        ".header{position:sticky;top:0;background:#fff;padding:5px;border-bottom:1px solid #000;display:flex;justify-content:space-between;z-index:9;}" +
                                        ".time{color:#888;margin-right:5px;}.line{border-bottom:1px solid #eee;padding:2px 0;}" +
                                        "button{background:#000;color:#fff;border:none;padding:4px 8px;border-radius:3px;}" +
                                        "</style></head><body>" +
                                        "<div class='header'><b>📟 凱哥監聽</b><button onclick='clr()'>🧹 清空</button></div>" +
                                        "<div id='logs'>正在對接矩陣數據...</div>" +
                                        "<script>" +
                                        "function clr(){fetch('/?clean').then(()=>location.reload());}" +
                                        "let last = '';" +
                                        "setInterval(() => {" +
                                        "  fetch('/get_logs').then(r=>r.text()).then(data=>{" +
                                        "    if(data !== last) {" +
                                        "      document.getElementById('logs').innerHTML = data;" +
                                        "      last = data;" +
                                        "      window.scrollTo(0, document.body.scrollHeight);" +
                                        "    }" +
                                        "  });" +
                                        "}, 1000);" +
                                        "</script></body></html>";
                                out.write(html.getBytes("UTF-8"));
                            }
                            out.flush();
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) { isServerRunning = false; }
        }).start();
    }

    /**
     * TV 应用会通过 http://127.0.0.1:9978/proxy?do=danmu&title=xxx&episode=1 调用此方法
     * 注意：不要在此方法内再启动任何 ServerSocket，否则会和 TV 应用的主服务冲突
     */
    @Override
    public Object[] proxy(Map<String, String> params) {
        // 记录请求到日志面板（方便调试）
        log("收到 proxy 调用: " + params);

        // 1. 检查是否为弹幕请求
        String doParam = params.get("do");
        if (doParam == null || !doParam.equals("danmu")) {
            return errorResponse(400, "Missing or invalid 'do' parameter");
        }

        // 2. 提取标题和集数
        String title = params.get("title");
        String episode = params.get("episode");
        if (title == null || title.isEmpty() || episode == null || episode.isEmpty()) {
            return errorResponse(400, "Missing title or episode");
        }

        // 3. URL 解码
        try {
            title = URLDecoder.decode(title, "UTF-8");
        } catch (Exception ignored) {}
        try {
            episode = URLDecoder.decode(episode, "UTF-8");
        } catch (Exception ignored) {}

        log("弹幕请求：title=" + title + ", episode=" + episode);

        // 4. 生成弹幕 XML（模拟数据，可以替换成真实抓取逻辑）
        String xml = generateDanmuXml(title, episode);

        // 5. 返回成功响应
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/xml; charset=utf-8");
        headers.put("Connection", "close");
        return new Object[]{200, headers, xml};
    }

    /**
     * 生成弹幕 XML（目前是模拟一条弹幕，可自行扩展）
     */
    private String generateDanmuXml(String title, String episode) {
        long now = System.currentTimeMillis() / 1000;
        String p = "5.0,1,25,16777215," + now + ",0,123456,0";
        String content = "来自代理的弹幕：" + title + " 第" + episode + "集";
        
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
               "<i>\n" +
               "    <d p=\"" + escapeXml(p) + "\">" + escapeXml(content) + "</d>\n" +
               "</i>";
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private Object[] errorResponse(int code, String message) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "text/plain; charset=utf-8");
        log("错误响应: " + code + " " + message);
        return new Object[]{code, headers, message};
    }
}