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
    public static final StringBuilder sb = new StringBuilder("<div style='color:#888;'>---凱哥全能矩陣引擎已啟動---</div>");

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
        if ("proxySegment".equals(action)) return handleProxySegment(params);
        if ("ppnixKey".equals(action)) return handlePpnixKey(params);
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
                "零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十",
                "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十"
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

    // ====================== M3U8 代理（改写 KEY + 域名） ======================
    private static Object[] handleProxyM3u8(Map<String, String> params) {
        String url = params.get("url");
        if (url == null) return errorResponse(400, "Missing url");
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            headers.put("Referer", "https://www.ppnix.com/");
            headers.put("Origin", "https://www.ppnix.com");
            headers.put("Accept", "*/*");

            String content = OkHttp.string(url, headers);
            if (content == null || content.isEmpty()) {
                return errorResponse(500, "m3u8 empty");
            }

            // 分片地址改为经本地代理转发，代理内部会在多个编号域名间自动重试
            content = proxifySegments(content);

            // 把 KEY 指向本地二进制转换接口
            String localKeyUrl = getUrl() + "?do=ppnixKey";
            content = content.replace("URI=\"../key\"", "URI=\"" + localKeyUrl + "\"");
            content = content.replace("URI='../key'", "URI=\"" + localKeyUrl + "\"");
            content = content.replace("URI=\"https://www.ppnix.com/info/m3u8/key\"", "URI=\"" + localKeyUrl + "\"");

            log("✅ proxyM3u8 处理完成，分片已指向本地代理，KEY 已指向本地二进制接口");

            byte[] bytes = content.getBytes("UTF-8");
            return new Object[]{200, "application/vnd.apple.mpegurl", new ByteArrayInputStream(bytes)};
        } catch (Exception e) {
            log("❌ proxyM3u8 失败: " + e.getMessage());
            return errorResponse(500, e.getMessage());
        }
    }

    /**
     * 把 m3u8 里 ipfs.ppnix.com 的分片地址，改写成指向本地 do=proxySegment 接口，
     * 由服务端在多个编号域名（1-16.ppnix.com）间自动重试，规避单一网关变慢/跳转导致的超时。
     */
    private static String proxifySegments(String m3u8Content) {
        if (m3u8Content == null || !m3u8Content.contains("ipfs.ppnix.com")) return m3u8Content;
        Pattern pattern = Pattern.compile("(https?://)ipfs\\.ppnix\\.com(/[^\\s'\"]*?\\.(ts|m4s|mp4|key)?)");
        Matcher matcher = pattern.matcher(m3u8Content);
        StringBuffer out = new StringBuffer();
        try {
            while (matcher.find()) {
                int randomNum = (int) (Math.random() * 16) + 1;
                String candidateUrl = matcher.group(1) + randomNum + ".ppnix.com" + matcher.group(2);
                String proxied = getUrl() + "?do=proxySegment&url=" + URLEncoder.encode(candidateUrl, "UTF-8");
                matcher.appendReplacement(out, Matcher.quoteReplacement(proxied));
            }
            matcher.appendTail(out);
        } catch (Exception e) {
            log("❌ proxifySegments 失败，回退为原始内容: " + e.getMessage());
            return m3u8Content;
        }
        return out.toString();
    }

    // ====================== 分片代理（多编号域名自动重试） ======================
    private static Object[] handleProxySegment(Map<String, String> params) {
        String url = params.get("url");
        if (url == null) return errorResponse(400, "Missing url");

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.put("Referer", "https://www.ppnix.com/");
        headers.put("Origin", "https://www.ppnix.com");
        headers.put("Accept", "*/*");

        for (String candidate : buildSegmentCandidates(url)) {
            try {
                okhttp3.Response resp = OkHttp.newCall(candidate, headers);
                if (resp == null || resp.body() == null) continue;
                if (!resp.isSuccessful()) {
                    log("⚠️ 分片候选失败 (状态码 " + resp.code() + "): " + candidate);
                    continue;
                }
                String contentType = resp.header("Content-Type", "video/mp2t");
                log("✅ 分片命中: " + candidate);
                return new Object[]{200, contentType, resp.body().byteStream()};
            } catch (Exception e) {
                log("⚠️ 分片候选异常，换下一个域名重试: " + candidate + " → " + e.getMessage());
            }
        }

        log("❌ 分片所有候选域名均失败: " + url);
        return errorResponse(502, "all segment candidates failed");
    }

    /**
     * 根据原始分片 URL（形如 https://N.ppnix.com/ipfs/xxx），构造重试候选列表：
     * 1. 原始 URL 本身（保留站点自己的负载均衡选择）
     * 2. 其余 1-16 编号域名，随机顺序
     * 3. 最后兜底用 ipfs.ppnix.com 直连
     */
    private static java.util.List<String> buildSegmentCandidates(String originalUrl) {
        java.util.List<String> candidates = new java.util.ArrayList<>();
        candidates.add(originalUrl);

        Pattern hostPattern = Pattern.compile("(https?://)[\\w.-]+\\.ppnix\\.com(/.*)");
        Matcher m = hostPattern.matcher(originalUrl);
        if (!m.matches()) {
            return candidates;
        }
        String scheme = m.group(1);
        String path = m.group(2);

        java.util.List<Integer> nums = new java.util.ArrayList<>();
        for (int i = 1; i <= 16; i++) nums.add(i);
        java.util.Collections.shuffle(nums);
        for (int n : nums) {
            String candidate = scheme + n + ".ppnix.com" + path;
            if (!candidate.equals(originalUrl)) candidates.add(candidate);
        }

        candidates.add(scheme + "ipfs.ppnix.com" + path);
        return candidates;
    }

    // ====================== PPnix AES Key 转换（hex → 二进制） ======================
    private static Object[] handlePpnixKey(Map<String, String> params) {
        try {
            String keyUrl = "https://www.ppnix.com/info/m3u8/key";
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            headers.put("Referer", "https://www.ppnix.com/");
            headers.put("Origin", "https://www.ppnix.com");

            String keyHex = OkHttp.string(keyUrl, headers);
            if (keyHex == null || keyHex.trim().isEmpty()) {
                log("❌ ppnixKey 获取失败：返回空");
                return errorResponse(500, "key empty");
            }
            keyHex = keyHex.trim();

            if (isCloudflareChallenge(keyHex)) {
                log("🛡️ ppnixKey 疑似被 Cloudflare 拦截（返回的是挑战页而非 key），前200字符: "
                        + keyHex.substring(0, Math.min(200, keyHex.length())));
                return errorResponse(403, "blocked by cloudflare challenge");
            }

            log("🔑 获取到 key hex: " + keyHex);

            byte[] keyBytes = hexStringToByteArray(keyHex);
            if (keyBytes == null || keyBytes.length != 16) {
                log("❌ key 长度不正确: " + (keyBytes == null ? 0 : keyBytes.length)
                        + "，原始内容长度=" + keyHex.length()
                        + "，前200字符: " + keyHex.substring(0, Math.min(200, keyHex.length())));
                return errorResponse(500, "invalid key length");
            }

            return new Object[]{200, "application/octet-stream", new ByteArrayInputStream(keyBytes)};
        } catch (Exception e) {
            log("❌ ppnixKey 失败: " + e.getMessage());
            return errorResponse(500, e.getMessage());
        }
    }

    /**
     * 粗略判断响应是否为 Cloudflare（或类似 WAF）的 JS 挑战页，而不是期望的十六进制 key。
     * 判断依据：非纯十六进制字符 且 命中常见挑战页关键词。
     */
    private static boolean isCloudflareChallenge(String content) {
        if (content == null || content.isEmpty()) return false;
        if (content.matches("^[0-9a-fA-F]+$")) return false; // 纯十六进制，正常 key，直接放行
        String lower = content.toLowerCase();
        return lower.contains("cf-browser-verification")
                || lower.contains("just a moment")
                || lower.contains("checking your browser")
                || lower.contains("challenge-platform")
                || lower.contains("cf-chl")
                || lower.contains("<html");
    }

    private static byte[] hexStringToByteArray(String hex) {
        if (hex == null) return null;
        hex = hex.trim().replace(" ", "");
        if (hex.length() % 2 != 0) return null;
        byte[] data = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
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

    // ====================== IPTV 直播源代理 ======================
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
        try {
            params.put("title", URLDecoder.decode(title, "UTF-8"));
        } catch (Exception ignored) {
        }
        try {
            params.put("episode", URLDecoder.decode(episode, "UTF-8"));
        } catch (Exception ignored) {
        }
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
                " fetch('?do=get_logs').then(r=>r.text()).then(data=>{" +
                " if(data !== last) {" +
                " document.getElementById('logs').innerHTML = data;" +
                " last = data;" +
                " window.scrollTo(0, document.body.scrollHeight);" +
                " }" +
                " });" +
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
