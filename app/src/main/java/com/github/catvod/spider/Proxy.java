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

        if ("danmaku".equals(doParam)) {
            // 1. 获取并解码参数
            String title = params.getOrDefault("title", "");
            String episodeRaw = params.getOrDefault("episode", "1");

            try {
                title = URLDecoder.decode(title, "UTF-8");
            } catch (Exception ignored) {}
            try {
                episodeRaw = URLDecoder.decode(episodeRaw, "UTF-8");
            } catch (Exception ignored) {}

            // 2. 严格对齐 JS 的集数提取逻辑：只保留数字
            int ep = 1;
            try {
                // 对应 JS 的: parseInt(episode.toString().replace(/\D/g, '')) || 1
                String digits = episodeRaw.replaceAll("\\D", "");
                if (!digits.isEmpty()) {
                    ep = Integer.parseInt(digits);
                }
            } catch (Exception e) {
                ep = 1; // 转换失败默认为第 1 集
            }

            log("🎯 [弹幕请求] 标题: " + title + " | 识别集数: " + ep);

            // 3. 调用重写后的 DanmuHelper
            // 如果解析出的标题为空且没有直接传入 URL，Helper 会返回 generateEmptyDanmu
            String xml = DanmuHelper.getDanmuXml(title, ep);

            // 4. 封装响应
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/xml; charset=utf-8");
            headers.put("Connection", "close");
            
            // 增加 CORS 跨域支持（防止某些 TV 播放器跨域读取失败）
            headers.put("Access-Control-Allow-Origin", "*");

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
