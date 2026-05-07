package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class Proxy extends Spider {
    private static StringBuilder sb = new StringBuilder("<div style='color:#888;'>--- 凱哥全能矩陣引擎已啟動 ---</div>");
    private static boolean isServerRunning = false;
    private static final int MY_LOG_PORT = 10086;          // 日志面板端口
    private static final int DANMU_PORT = 9978;            // 弹幕接口端口

    public static int getPort() { return DANMU_PORT; }
    public static String getUrl() { return "http://127.0.0.1:" + DANMU_PORT + "/proxy"; }

    public static void log(String msg) {
        if (msg == null) return;
        if (sb.length() > 200000) sb.delete(0, 100000);
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        sb.append("<div class='line'><span class='time'>[").append(time).append("]</span> ")
          .append("<span class='msg'>").append(msg).append("</span></div>");
        if (!isServerRunning) startLegacyServer();          // 启动日志面板服务
    }

    // 启动两个服务：10086（日志面板） + 9978（弹幕接口）
    private static void startLegacyServer() {
        if (isServerRunning) return;

        // 原有的 10086 端口日志面板服务 (保持不变)
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

        // ========== 新增：9978 端口弹幕服务 ==========
        new Thread(() -> {
            try (ServerSocket danmuServer = new ServerSocket(DANMU_PORT)) {
                log("弹幕服务已启动在端口 " + DANMU_PORT);
                while (true) {
                    try (Socket client = danmuServer.accept()) {
                        BufferedReader reader = new BufferedReader(
                            new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)
                        );
                        String requestLine = reader.readLine();
                        if (requestLine == null) continue;

                        // 解析请求路径和参数
                        String[] parts = requestLine.split(" ");
                        if (parts.length < 2) continue;
                        String path = parts[1];
                        
                        // 只处理 /proxy?do=danmu 的请求
                        if (path.startsWith("/proxy") && path.contains("do=danmu")) {
                            String title = extractParam(path, "title");
                            String episode = extractParam(path, "episode");
                            
                            log("收到弹幕请求: title=" + title + ", episode=" + episode);
                            
                            // 生成弹幕 XML (这里先返回一个模拟的示例 XML)
                            String xmlDanmu = generateMockDanmuXML(title, episode);
                            
                            OutputStream out = client.getOutputStream();
                            String response = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/xml; charset=utf-8\r\n" +
                                    "Connection: close\r\n\r\n" +
                                    xmlDanmu;
                            out.write(response.getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        } else {
                            // 其他路径返回 404
                            OutputStream out = client.getOutputStream();
                            out.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
                            out.flush();
                        }
                    } catch (Exception e) {
                        log("弹幕服务处理异常: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                log("弹幕服务启动失败: " + e.getMessage());
            }
        }).start();
    }

    // 从请求路径中提取 URL 参数值 (简单解析，不处理编码)
    private static String extractParam(String path, String paramName) {
        String pattern = paramName + "=";
        int start = path.indexOf(pattern);
        if (start == -1) return "";
        start += pattern.length();
        int end = path.indexOf("&", start);
        if (end == -1) end = path.length();
        String value = path.substring(start, end);
        try {
            // 处理 URL 编码 (如 %E6%96%B9...)
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    // 生成一个示例弹幕 XML (模拟数据)
    private static String generateMockDanmuXML(String title, String episode) {
        // 这里你可以替换成真正的弹幕获取逻辑（从 B站、弹弹play 等拉取）
        // 目前返回一个简单的带一条弹幕的 XML
        long now = System.currentTimeMillis() / 1000;
        // p 参数格式: 出现时间(秒), 弹幕类型, 字号, 颜色, 发送时间戳, 弹幕池, 用户ID, 弹幕ID
        String p = "5.0,1,25,16777215," + now + ",0,123456,0";
        String content = "观众弹幕测试：" + title + " 第" + episode + "集";
        
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
               "<i>\n" +
               "    <d p=\"" + p + "\">" + escapeXml(content) + "</d>\n" +
               "</i>";
    }

    // 简单的 XML 转义
    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    // 原 Spider 接口实现 (可保留空实现)
    public Object[] proxy(Map<String, String> params) { return null; }
}