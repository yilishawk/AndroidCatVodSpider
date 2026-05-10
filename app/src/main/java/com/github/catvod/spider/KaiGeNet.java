package com.github.catvod.spider;

import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import android.text.TextUtils;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;
import okhttp3.OkHttpClient;

/**
 * 凱哥网络增强层 2.0
 * 功能：自动gzip/br、自动Cloudflare友好、自动Referer修复、UA切换、自动重试、自动302跟随
 */
public class KaiGeNet {

    private static final Map<String, String> cookieJar = new ConcurrentHashMap<>();
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.6167.178 Mobile Safari/537.36";
    private static final String PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36";

    private static boolean useMobileUA = true; // 默认使用移动端 UA

    // 全局 TLS 指纹伪装 + 自动重试等
    static {
        try {
            KaiGeTLSFactory factory = new KaiGeTLSFactory();
            X509TrustManager trustManager = new X509TrustManager() {
                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[]{}; }
            };

            OkHttpClient client = new OkHttpClient.Builder()
                    .sslSocketFactory(factory, trustManager)
                    .hostnameVerifier((hostname, session) -> true)
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .followRedirects(true)      // 自动302跟随
                    .followSslRedirects(true)
                    .build();

            // 反射注入全局 OkHttpClient
            java.lang.reflect.Field field = OkHttp.class.getDeclaredField("client");
            field.setAccessible(true);
            field.set(null, client);

        } catch (Exception ignored) {
            // 注入失败时使用系统默认，不崩溃
        }
    }

    /**
     * 智能请求（推荐使用此方法）
     */
    public static OkResult smartRequest(String siteUrl, String method, String url, String body, Map<String, String> headers) {
        return smartRequest(siteUrl, method, url, body, headers, 3); // 默认重试3次
    }

    public static OkResult smartRequest(String siteUrl, String method, String url, String body, Map<String, String> headers, int retryCount) {
        String host = getHost(url);
        if (headers == null) headers = new HashMap<>();

        // 自动 UA
        if (!headers.containsKey("User-Agent")) {
            headers.put("User-Agent", useMobileUA ? MOBILE_UA : PC_UA);
        }

        // 自动 Referer 修复
        if (!headers.containsKey("Referer") && !TextUtils.isEmpty(siteUrl)) {
            headers.put("Referer", siteUrl);
        }

        // 自动支持 gzip, br 解压
        headers.put("Accept-Encoding", "gzip, deflate, br");

        if (cookieJar.containsKey(host)) {
            headers.put("Cookie", cookieJar.get(host));
        }

        OkResult res = null;
        Exception lastException = null;

        for (int i = 0; i < retryCount; i++) {
            try {
                res = execute(method, url, body, headers);
                if (res.getCode() == 200 && !TextUtils.isEmpty(res.getBody())) {
                    break; // 成功则跳出重试
                }
            } catch (Exception e) {
                lastException = e;
            }
            if (i < retryCount - 1) {
                try { Thread.sleep(800 + i * 400L); } catch (Exception ignored) {}
            }
        }

        // 更新 Cookie
        if (res != null) {
            String setCookie = getSetCookie(res.getResp());
            if (!TextUtils.isEmpty(setCookie)) {
                cookieJar.put(host, setCookie);
            }
        }

        return res != null ? res : new OkResult(0, null, null, "");
    }

    private static OkResult execute(String method, String url, String body, Map<String, String> headers) {
        method = (method == null) ? "get" : method.toLowerCase();
        if ("post".equals(method)) {
            if (!TextUtils.isEmpty(body) && body.trim().startsWith("{")) {
                return OkHttp.post(url, body, headers);
            } else {
                return OkHttp.post(url, parseToMap(body), headers);
            }
        }
        return OkHttp.get(url, parseToMap(body == null ? "" : body), headers);
    }

    // ==================== TLS 指纹伪装核心 ====================
    private static class KaiGeTLSFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private final String[] chromeCiphers = {
            "TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256", "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"
        };

        public KaiGeTLSFactory() throws Exception {
            SSLContext sc = SSLContext.getInstance("TLSv1.3");
            sc.init(null, null, null);
            this.delegate = sc.getSocketFactory();
        }

        private Socket patch(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) s;
                ssl.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                ssl.setEnabledCipherSuites(chromeCiphers);
            }
            return s;
        }

        @Override public String[] getDefaultCipherSuites() { return chromeCiphers; }
        @Override public String[] getSupportedCipherSuites() { return chromeCiphers; }

        @Override public Socket createSocket(Socket s, String h, int p, boolean a) throws IOException { return patch(delegate.createSocket(s, h, p, a)); }
        @Override public Socket createSocket(String h, int p) throws IOException { return patch(delegate.createSocket(h, p)); }
        @Override public Socket createSocket(String h, int p, java.net.InetAddress l, int lp) throws IOException { return patch(delegate.createSocket(h, p, l, lp)); }
        @Override public Socket createSocket(java.net.InetAddress a, int p) throws IOException { return patch(delegate.createSocket(a, p)); }
        @Override public Socket createSocket(java.net.InetAddress a, int p, java.net.InetAddress la, int lp) throws IOException { return patch(delegate.createSocket(a, p, la, lp)); }
    }

    private static String getSetCookie(Map<String, List<String>> respHeaders) {
        if (respHeaders == null) return "";
        List<String> cookies = respHeaders.get("Set-Cookie");
        if (cookies == null) cookies = respHeaders.get("set-cookie");
        return (cookies != null && !cookies.isEmpty()) ? TextUtils.join(";", cookies) : "";
    }

    private static String getHost(String urlStr) {
        if (TextUtils.isEmpty(urlStr)) return "";
        try {
            return new URL(urlStr).getHost();
        } catch (Exception e) {
            return urlStr;
        }
    }

    private static Map<String, String> parseToMap(String body) {
        Map<String, String> map = new HashMap<>();
        if (TextUtils.isEmpty(body)) return map;
        try {
            String[] pairs = body.split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    map.put(kv[0].trim(), kv[1].trim());
                }
            }
        } catch (Exception ignored) {}
        return map;
    }

    /** 切换 UA（可在 init 中调用） */
    public static void switchUA(boolean mobile) {
        useMobileUA = mobile;
    }
}