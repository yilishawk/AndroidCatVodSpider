package com.github.catvod.spider;

import android.util.Base64;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.ProxyVideo;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Proxy {

    private static final int PROXY_PORT = 9978;

    public static final StringBuilder sb = new StringBuilder("<div style='color:#888;'>--- 凱哥全能矩陣引擎已啟動---</div>");

    public static int getPort() {
        return PROXY_PORT;
    }

    public static String getUrl() {
        return "http://127.0.0.1:" + PROXY_PORT + "/proxy";
    }

    public static void log(String msg) {
        if (msg == null) return;
        if (sb.length() > 200000) sb.delete(0, 100000);
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        sb.append("<div class='line'><span class='time'>[").append(time).append("]</span> ")
          .append("<span class='msg'>").append(msg).append("</span></div>");
    }

    /**
     * 将异常的完整堆栈转成可在日志面板里显示的字符串（换行替换为 <br>）
     */
    public static String getStackTrace(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString().replace("\n", "<br>");
    }

    public static Object[] proxy(Map<String, String> params) {
        String action = params.get("do");

        if ("logs".equals(action) || "kaige_debug".equals(action)) return handleLogsPage();
        if ("get_logs".equals(action)) return handleGetLogs();
        if ("clean".equals(action)) return handleClean();

        log("📨 [Proxy] 收到请求: " + params);

        if ("getPoster".equals(action)) return handleGetPoster(params);
        if ("proxyM3u8".equals(action)) return handleProxyM3u8(params);
        if ("danmu".equals(action)) return handleDanmu(params);
        if ("proxy".equals(action)) return handleCommonProxy(params);
        if ("iptvzb".equals(action)) return handleIptvZb(params);

        return errorResponse(400, "Unknown action: " + action);
    }

    // ====================== 搜索词标准化 ======================

    private static String normalizeSearchTitle(String title) {
        if (title == null) return "";
        String s = title.trim();

        Pattern seasonPattern = Pattern.compile("第([0-9]+)季");
        Matcher seasonMatcher = seasonPattern.matcher(s);
        StringBuffer seasonBuf = new StringBuffer();
        while (seasonMatcher.find()) {
            int num = Integer.parseInt(seasonMatcher.group(1));
            seasonMatcher.appendReplacement(seasonBuf, "第" + toChineseNum(num) + "季");
        }
        seasonMatcher.appendTail(seasonBuf);
        s = seasonBuf.toString();

        s = s.replaceAll("[(（]粤[)）]", "粤语版");
        s = s.replaceAll("[(（]国[)）]", "国语版");
        s = s.replaceAll("[(（]英[)）]", "英语版");
        s = s.replaceAll("[(（]日[)）]", "日语版");
        s = s.replaceAll("[(（]韩[)）]", "韩语版");

        return s;
    }

    private static String toChineseNum(int n) {
        String[] chinese = {
            "零","一","二","三","四","五","六","七","八","九","十",
            "十一","十二","十三","十四","十五","十六","十七","十八","十九","二十"
        };
        if (n >= 0 && n < chinese.length) return chinese[n];
        return String.valueOf(n);
    }

    // ====================== 图片代理 ======================

    private static Object[] handleGetPoster(Map<String, String> params) {
        String title = params.get("title");
        if (title == null || title.trim().isEmpty()) return defaultImage();
        title = normalizeSearchTitle(title);

        try {
            String searchUrl = "https://hongniuzy.tv/index.php/ajax/suggest.html?mid=1&wd="
                    + URLEncoder.encode(title.trim(), "UTF-8");

            String jsonStr = OkHttp.string(searchUrl);
            JSONObject obj = new JSONObject(jsonStr);
            JSONArray list = obj.optJSONArray("list");

            if (list != null && list.length() > 0) {
                String pic = list.getJSONObject(0).optString("pic");
                if (pic.startsWith("http")) {
                    okhttp3.Response resp = OkHttp.newCall(pic, new HashMap<>());
                    String contentType = resp.header("Content-Type", "image/jpeg");
                    return new Object[]{200, contentType, resp.body().byteStream()};
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

    // ====================== 通用带Header视频代理 ======================

    private static Object[] handleCommonProxy(Map<String, String> params) {
        try {
            String url = new String(Base64.decode(params.get("url"), Base64.DEFAULT), "UTF-8");
            Map<String, String> headers = new HashMap<>();
            String headerParam = params.get("header");
            if (headerParam != null && !headerParam.isEmpty()) {
                String headerJson = new String(Base64.decode(headerParam, Base64.DEFAULT), "UTF-8");
                JsonObject obj = Json.safeObject(headerJson);
                for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                    headers.put(e.getKey(), e.getValue().getAsString());
                }
            }
            return ProxyVideo.proxy(url, headers);
        } catch (Exception e) {
            log("❌ [proxy] 失败: " + e.getMessage());
            return errorResponse(500, e.getMessage());
        }
    }

    // ====================== IPTV 直播源代理（taoiptv，走 TaoIPTV） ======================

    private static Object[] handleIptvZb(Map<String, String> params) {
        try {
            if (TaoIPTV.isLoading()) {
                log("⏳ IPTV[taoiptv] 爬虫运行中，暂不返回数据");
                return new Object[]{200, "text/plain; charset=utf-8", new ByteArrayInputStream("".getBytes("UTF-8"))};
            }

            String keyword = params.get("kw");
            String txt = TaoIPTV.getCache(keyword);
            byte[] bytes = (txt == null ? "" : txt).getBytes("UTF-8");
            return new Object[]{200, "text/plain; charset=utf-8", new ByteArrayInputStream(bytes)};
        } catch (Exception e) {
            log("❌ iptvzb 失败: " + e.getMessage() + "<br><pre>" + getStackTrace(e) + "</pre>");
            return errorResponse(500, e.getMessage());
        }
    }

    // ====================== 弹幕 ======================

    private static Object[] handleDanmu(Map<String, String> params) {
        String title = params.get("title");
        String episode = params.get("episode");
        try { params.put("title", URLDecoder.decode(title, "UTF-8")); } catch (Exception ignored) {}
        try { params.put("episode", URLDecoder.decode(episode, "UTF-8")); } catch (Exception ignored) {}
        return DanmuHelper.getDanmuResponse(params);
    }

    // ====================== 日志面板 ======================

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
