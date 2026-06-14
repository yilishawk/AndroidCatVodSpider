package com.github.catvod.spider;

import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Proxy {

    private static final StringBuilder sb = new StringBuilder("<div style='color:#888;'>--- 凱哥全能矩陣引擎已啟動 ---</div>");

    // 框架本地服务器真实地址：http://127.0.0.1:<实际探测端口>/proxy
    public static String getUrl() {
        return com.github.catvod.Proxy.getUrl(true);
    }

    public static void log(String msg) {
        if (msg == null) return;
        if (sb.length() > 200000) sb.delete(0, 100000);
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        sb.append("<div class='line'><span class='time'>[").append(time).append("]</span> ")
          .append("<span class='msg'>").append(msg).append("</span></div>");
    }

    // 必须是 static，签名 Object[] proxy(Map<String,String>)，框架靠反射 invoke(null, params) 调用
    public static Object[] proxy(Map<String, String> params) {
        log("📨 [Proxy] 收到请求: " + params);
        String action = params.get("do");

        if ("getPoster".equals(action)) return handleGetPoster(params);
        if ("proxyM3u8".equals(action)) return handleProxyM3u8(params);
        if ("danmu".equals(action)) return handleDanmu(params);
        if ("logs".equals(action)) return handleLogsPage();
        if ("get_logs".equals(action)) return handleGetLogs();
        if ("clean".equals(action)) return handleClean();

        return errorResponse(400, "Unknown action: " + action);
    }

    // ====================== 图片代理 ======================
    private static Object[] handleGetPoster(Map<String, String> params) {
        String title = params.get("title");
        if (title == null || title.trim().isEmpty()) return defaultImage();

        try {
            String searchUrl = "https://hongniuzy.tv/index.php/ajax/suggest.html?mid=1&wd="
                    + URLEncoder.encode(title.trim(), "UTF-8");

            String jsonStr = OkHttp.string(searchUrl);
            JSONObject obj = new JSONObject(jsonStr);
            JSONArray list = obj.optJSONArray("list");

            if (list != null && list.length() > 0) {
                String pic = list.getJSONObject(0).optString("pic");
                if (pic.startsWith("http")) {
                    String imageData = OkHttp.string(pic);
                    byte[] data = imageData.getBytes("ISO-8859-1");
                    return new Object[]{200, "image/jpeg", new ByteArrayInputStream(data)};
                }
            }
        } catch (Exception e) {
            log("❌ getPoster 失败: " + e.getMessage());
        }
        return defaultImage();
    }

    private static Object[] defaultImage() {
        return new Object[]{200, "image/jpeg", new ByteArrayInputStream(new byte[0])};
    }

    // ====================== M3U8 TS 域名替换 ======================
    private static Object[] handleProxyM3u8(Map<String, String> params) {
        String url = params.get("url");
        if (url == null) return errorResponse(400, "Missing url");

        try {
            String content = OkHttp.string(url);
            content = replacePpnixDomain(content);

            byte[] bytes = content.getBytes("UTF-8");
            return new Object[]{200, "application/vnd.apple.mpegurl", new ByteArrayInputStream(bytes)};
        } catch (Exception e) {
            log("❌ proxyM3u8 失败: " + e.getMessage());
            return errorResponse(500, e.getMessage());
        }
    }

    private static String replacePpnixDomain(String m3u8Content) {
        if (m3u8Content == null || !m3u8Content.contains("ipfs.ppnix.com")) return m3u8Content;

        Pattern pattern = Pattern.compile("(https?://)ipfs\\.ppnix\\.com(/[^\\s'\"]*?\\.(ts|m4s|mp4|key)?)");
        Matcher matcher = pattern.matcher(m3u8Content);
        StringBuffer out = new StringBuffer();

        while (matcher.find()) {
            int randomNum = (int) (Math.random() * 16) + 1;
            String replacement = matcher.group(1) + randomNum + ".ppnix.com" + matcher.group(2);
            matcher.appendReplacement(out, replacement);
        }
        matcher.appendTail(out);

        return out.toString();
    }

    // ====================== 弹幕 ======================
    private static Object[] handleDanmu(Map<String, String> params) {
        String title = params.get("title");
        String episode = params.get("episode");
        try { params.put("title", URLDecoder.decode(title, "UTF-8")); } catch (Exception ignored) {}
        try { params.put("episode", URLDecoder.decode(episode, "UTF-8")); } catch (Exception ignored) {}
        return DanmuHelper.getDanmuResponse(params);
    }

    // ====================== 日志面板（走真实端口，浏览器访问 getUrl()+"?do=logs"） ======================
    private static Object[] handleLogsPage() {
        String html = "<html><head><meta charset='utf-8'><style>" +
                "body{background:#fff;color:#000;font-family:monospace;font-size:12px;margin:0;padding:10px;}" +
                ".header{position:sticky;top:0;background:#fff;padding:5px;border-bottom:1px solid #000;display:flex;justify-content:space-between;z-index:9;}" +
                ".time{color:#888;margin-right:5px;}.line{border-bottom:1px solid #eee;padding:2px 0;}" +
                "button{background:#000;color:#fff;border:none;padding:4px 8px;border-radius:3px;}" +
                "</style></head><body>" +
                "<div class='header'><b>📟 凱哥監聽</b><button onclick='clr()'>🧹 清空</button></div>" +
                "<div id='logs'>正在對接矩陣數據...</div>" +
                "<script>" +
                "function clr(){fetch('?do=clean').then(()=>location.reload());}" +
                "let last = '';" +
                "setInterval(() => {" +
                "  fetch('?do=get_logs').then(r=>r.text()).then(data=>{" +
                "    if(data !== last) {" +
                "      document.getElementById('logs').innerHTML = data;" +
                "      last = data;" +
                "      window.scrollTo(0, document.body.scrollHeight);" +
                "    }" +
                "  });" +
                "}, 1000);" +
                "</script></body></html>";
        try {
            return new Object[]{200, "text/html; charset=utf-8", new ByteArrayInputStream(html.getBytes("UTF-8"))};
        } catch (Exception e) {
            return errorResponse(500, e.getMessage());
        }
    }

    private static Object[] handleGetLogs() {
        try {
            return new Object[]{200, "text/plain; charset=utf-8", new ByteArrayInputStream(sb.toString().getBytes("UTF-8"))};
        } catch (Exception e) {
            return errorResponse(500, e.getMessage());
        }
    }

    private static Object[] handleClean() {
        sb.setLength(0);
        sb.append("<div style='color:red;'>--- 日誌已手動清空 ---</div>");
        return new Object[]{200, "text/plain; charset=utf-8", new ByteArrayInputStream("OK".getBytes())};
    }

    private static Object[] errorResponse(int code, String message) {
        return new Object[]{code, "text/plain; charset=utf-8", new ByteArrayInputStream(message.getBytes())};
    }
}
