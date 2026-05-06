package com.github.catvod.spider;

import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import android.text.TextUtils;

import java.net.URL;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class KaiGeNet {

    // 🚀 Cookie 精细缓存（host + path 级别）
    private static final Map<String, String> cookieJar = new ConcurrentHashMap<>();

    // 🚀 可扩展 UA 池（防封）
    private static final List<String> UA_POOL = Arrays.asList(
        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 Chrome/121.0 Mobile",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 Version/16.0 Mobile",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"
    );

    public static boolean DEBUG = false;

    /**
     * 🚀 頂級智能請求入口
     */
    public static OkResult smartRequest(String siteUrl, String method, String url, String body, Map<String, String> headers) {

        if (headers == null) headers = new HashMap<>();

        String key = getKey(url);

        // ✅ 1. 隨機 UA（降低封鎖）
        headers.putIfAbsent("User-Agent", randomUA());

        // ✅ 2. Referer 安全策略
        headers.putIfAbsent("Referer", buildReferer(siteUrl, url));

        // ✅ 3. 注入 Cookie（按 path 隔離）
        if (cookieJar.containsKey(key)) {
            headers.put("Cookie", cookieJar.get(key));
        }

        OkResult res = null;

        // 🚀 4. 智能重試（最多2次）
        for (int i = 0; i < 2; i++) {

            res = execute(method, url, body, headers);

            if (res == null) continue;

            String setCookie = getSetCookie(res.getResp());

            // ✅ 更新 Cookie
            if (!TextUtils.isEmpty(setCookie)) {
                cookieJar.put(key, setCookie);
                headers.put("Cookie", setCookie);
            }

            // 🚀 判斷是否成功（防 5s 盾 / 空頁）
            if (isValidBody(res.getBody())) {
                break;
            }

            log("Retry triggered...");
        }

        return res;
    }

    // 🚀 判斷返回是否有效
    private static boolean isValidBody(String body) {
        if (TextUtils.isEmpty(body)) return false;

        String b = body.toLowerCase();

        // 常見防護頁特徵
        if (b.contains("checking your browser") ||
            b.contains("cloudflare") ||
            b.contains("captcha")) {
            return false;
        }

        return body.length() > 500;
    }

    // 🚀 執行器
    private static OkResult execute(String method, String url, String body, Map<String, String> headers) {

        method = (method == null) ? "get" : method.toLowerCase();

        try {
            if ("post".equals(method)) {

                if (!TextUtils.isEmpty(body) && body.trim().startsWith("{")) {
                    return OkHttp.post(url, body, headers);
                } else {
                    return OkHttp.post(url, parseToMap(body), headers);
                }
            }

            return OkHttp.get(url, parseToMap(body), headers);

        } catch (Exception e) {
            log("Request error: " + e.getMessage());
            return null;
        }
    }

    // 🚀 UA 隨機
    private static String randomUA() {
        return UA_POOL.get(new Random().nextInt(UA_POOL.size()));
    }

    // 🚀 Referer 策略
    private static String buildReferer(String siteUrl, String targetUrl) {
        try {
            if (!TextUtils.isEmpty(siteUrl) && siteUrl.matches("^[\\x00-\\x7F]*$")) {
                return siteUrl;
            }
            return getHost(targetUrl) + "/";
        } catch (Exception e) {
            return "";
        }
    }

    // 🚀 Cookie Key（host + path）
    private static String getKey(String url) {
        try {
            URL u = new URL(url);
            return u.getHost() + u.getPath();
        } catch (Exception e) {
            return url;
        }
    }

    // 🚀 提取 Set-Cookie
    private static String getSetCookie(Map<String, List<String>> respHeaders) {
        if (respHeaders == null) return "";

        List<String> cookies = respHeaders.get("Set-Cookie");
        if (cookies == null) cookies = respHeaders.get("set-cookie");

        if (cookies != null && !cookies.isEmpty()) {
            return TextUtils.join(";", cookies);
        }

        return "";
    }

    // 🚀 Host 提取
    private static String getHost(String urlStr) {
        if (TextUtils.isEmpty(urlStr)) return "";

        try {
            return new URL(urlStr).getProtocol() + "://" + new URL(urlStr).getHost();
        } catch (Exception e) {
            try {
                URI uri = URI.create(urlStr);
                return uri.getScheme() + "://" + uri.getHost();
            } catch (Exception ex) {
                return urlStr;
            }
        }
    }

    // 🚀 解析 body -> map
    private static Map<String, String> parseToMap(String body) {
        Map<String, String> map = new HashMap<>();

        if (TextUtils.isEmpty(body)) return map;

        try {
            String[] pairs = body.split("&");

            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    map.put(kv[0], kv[1]);
                }
            }
        } catch (Exception ignored) {}

        return map;
    }

    private static void log(String msg) {
        if (DEBUG) System.out.println("[KaiGeNet] " + msg);
    }
}