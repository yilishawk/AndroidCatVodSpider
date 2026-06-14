package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

public class Proxy extends Spider {

    private static StringBuilder sb = new StringBuilder("<div style='color:#888;'>--- 凱哥全能矩陣引擎已啟動 ---</div>");
    private static boolean isServerRunning = false;
    
    private static final int PROXY_PORT = 10086;   // 主代理端口（图片 + M3U8）
    private static final int OLD_PORT = 9978;      // 保留记录

    public static int getPort() { return PROXY_PORT; }
    public static String getUrl() { 
        return "http://127.0.0.1:" + PROXY_PORT + "/proxy"; 
    }

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
            try (ServerSocket server = new ServerSocket(PROXY_PORT)) {
                server.setReuseAddress(true);
                isServerRunning = true;
                while (true) {
                    try (Socket client = server.accept()) {
                        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                        String req = in.readLine();
                        if (req == null) continue;

                        try (OutputStream out = client.getOutputStream()) {
                            if (req.contains("/danmu")) {
                                String query = req.contains("?") ? req.split("\\?")[1].split(" ")[0] : "";
                                Map<String, String> qparams = new HashMap<>();
                                for (String kv : query.split("&")) {
                                    String[] pair = kv.split("=", 2);
                                    if (pair.length == 2) {
                                        try { qparams.put(pair[0], URLDecoder.decode(pair[1], "UTF-8")); } catch (Exception ignored) {}
                                    }
                                }
                                Object[] danmuResult = DanmuHelper.getDanmuResponse(qparams);
                                byte[] body = new byte[0];
                                if (danmuResult.length >= 3 && danmuResult[2] instanceof InputStream) {
                                    body = ((InputStream) danmuResult[2]).readAllBytes();
                                }
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/xml; charset=utf-8\r\nConnection: close\r\n\r\n".getBytes());
                                out.write(body);
                                out.flush();
                            } else if (req.contains("/?clean")) {
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

    public Object[] proxy(Map<String, String> params) {
        log("📨 [Proxy] 收到请求: " + params);

        String action = params.get("do");

        if ("getPoster".equals(action)) {
            return handleGetPoster(params);
        } else if ("proxyM3u8".equals(action)) {
            return handleProxyM3u8(params);
        } else if ("danmu".equals(action)) {
            return handleDanmu(params);
        }

        return errorResponse(400, "Unknown action: " + action);
    }

    // ====================== 图片代理 ======================
    private Object[] handleGetPoster(Map<String, String> params) {
        String title = params.get("title");
        if (title == null || title.trim().isEmpty()) {
            return defaultImage();
        }

        try {
            String searchUrl = "https://hongniuzy.tv/index.php/ajax/suggest.html?mid=1&wd=" 
                             + URLEncoder.encode(title.trim(), "UTF-8");

            String jsonStr = OkHttp.string(searchUrl);
            JSONObject obj = new JSONObject(jsonStr);
            JSONArray list = obj.optJSONArray("list");

            if (list != null && list.length() > 0) {
                String pic = list.getJSONObject(0).optString("pic");
                if (pic.startsWith("http")) {
                    // 正确获取图片字节
                    String imageData = OkHttp.string(pic);   // 先取字符串（兼容）
                    byte[] data = imageData.getBytes("ISO-8859-1"); // 二进制安全方式
                    return new Object[]{200, "image/jpeg", new ByteArrayInputStream(data)};
                }
            }
        } catch (Exception e) {
            log("❌ getPoster 失败: " + e.getMessage());
        }
        return defaultImage();
    }

    private Object[] defaultImage() {
        return new Object[]{200, "image/jpeg", new ByteArrayInputStream(new byte[0])};
    }

    // ====================== M3U8 TS 域名替换 ======================
    private Object[] handleProxyM3u8(Map<String, String> params) {
        String url = params.get("url");
        if (url == null) return errorResponse(400, "Missing url");

        try {
            String content = OkHttp.string(url);

            // === TS 域名替换（在这里添加）===
            // content = content.replace("https://旧域名.com", "https://新域名.com");

            byte[] bytes = content.getBytes("UTF-8");
            return new Object[]{200, "application/vnd.apple.mpegurl", new ByteArrayInputStream(bytes)};
        } catch (Exception e) {
            log("❌ proxyM3u8 失败: " + e.getMessage());
            return errorResponse(500, e.getMessage());
        }
    }

    // ====================== 弹幕 ======================
    private Object[] handleDanmu(Map<String, String> params) {
        String title = params.get("title");
        String episode = params.get("episode");
        try { title = URLDecoder.decode(title, "UTF-8"); params.put("title", title); } catch (Exception ignored) {}
        try { episode = URLDecoder.decode(episode, "UTF-8"); params.put("episode", episode); } catch (Exception ignored) {}
        return DanmuHelper.getDanmuResponse(params);
    }

    private Object[] errorResponse(int code, String message) {
        return new Object[]{code, "text/plain; charset=utf-8", new ByteArrayInputStream(message.getBytes())};
    }
}
