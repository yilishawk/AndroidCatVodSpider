package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

public class Proxy extends Spider {
    private static StringBuilder sb = new StringBuilder("<div style='color:#888;'>--- 凱哥哥全能矩陣引擎已啟動 ---</div>");
    private static boolean isServerRunning = false;
    private static final int MY_LOG_PORT = 10086;

    public static int getPort() { return 9978; }
    public static String getUrl() { return "http://127.0.0.1:9978/proxy"; }

    public static void log(String msg) {
        if (msg == null) return;
        if (sb.length() > 200000) sb.delete(0, 100000);
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        sb.append("<div class='line'><span class='time'>[").append(time).append("]</span> ")
          .append("<span class='msg'>").append(msg).append("</span></div>");
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
     * 注意：此方法不是重写父类方法，而是因为 TV 应用会通过反射调用它。
     * 不要加 @Override 注解，否则编译会失败。
     */
/**
     * 适配 JS 逻辑的 Proxy 分发方法
     */
    public Object[] proxy(Map<String, String> params) {
        log("收到 proxy 调用: " + params);
        String doParam = params.get("do");

        // ✅ 替换为这个版本
if ("danmaku".equals(doParam)) {
    String title = params.getOrDefault("title", "");
    String episodeRaw = params.getOrDefault("episode", "1");
    
    // 解码标题和集数，防止 URL 编码导致匹配失败
    try { title = java.net.URLDecoder.decode(title, "UTF-8"); } catch (Exception ignored) {}
    try { episodeRaw = java.net.URLDecoder.decode(episodeRaw, "UTF-8"); } catch (Exception ignored) {}

    // ⚡ 集数映射逻辑：完全对齐 JS，提取数字并转为 int
    // 例如 "02" 会变成 2，"第05集" 会变成 5
    int ep = 1;
    try {
        String digits = episodeRaw.replaceAll("\\D", ""); 
        if (!digits.isEmpty()) {
            ep = Integer.parseInt(digits); 
        }
    } catch (Exception e) {
        ep = 1;
    }

    log("🎯 [弹幕代理] 标题: " + title + " | 映射集数: " + ep);

    // 调用 DanmuHelper 获取 MD5 后的接口数据
    String xml = DanmuHelper.getDanmuXml(title, ep);
    
    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "application/xml; charset=utf-8");
    headers.put("Access-Control-Allow-Origin", "*"); // 允许跨域
    headers.put("Connection", "close");
    
    return new Object[]{200, headers, xml};
}

        return errorResponse(400, "Missing or invalid 'do' parameter");
    }

    private Object[] errorResponse(int code, String message) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "text/plain; charset=utf-8");
        log("错误响应: " + code + " " + message);
        return new Object[]{code, headers, message};
    }
}
