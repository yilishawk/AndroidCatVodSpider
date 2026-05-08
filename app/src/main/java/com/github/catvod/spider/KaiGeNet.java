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
import javax.net.ssl.*;
import okhttp3.OkHttpClient;
import java.util.concurrent.TimeUnit;

public class KaiGeNet {

    private static final Map<String, String> cookieJar = new ConcurrentHashMap<>();
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.6167.178 Mobile Safari/537.36";

    // 🚀 核心：全局攔截並更換 TLS 引擎
    static {
        try {
            // 獲取一個帶有 Chrome 指紋的 Client
            KaiGeTLSFactory factory = new KaiGeTLSFactory();
            X509TrustManager trustManager = new X509TrustManager() {
                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[]{}; }
            };

            OkHttpClient client = new OkHttpClient.Builder()
                    .sslSocketFactory(factory, trustManager)
                    .hostnameVerifier((hostname, session) -> true)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build();

            // 💎 凱哥特技：強行把我們自定義的 Client 注入到原生的 OkHttp 類中
            // 這樣所有調用 OkHttp.get / OkHttp.post 的地方都會自動帶上指紋
            java.lang.reflect.Field field = OkHttp.class.getDeclaredField("client");
            field.setAccessible(true);
            field.set(null, client);
        } catch (Exception ignored) {
            // 如果注入失敗，它會使用原生的，保證不崩潰
        }
    }

    public static OkResult smartRequest(String siteUrl, String method, String url, String body, Map<String, String> headers) {
        String host = getHost(url);
        if (headers == null) headers = new HashMap<>();

        if (!headers.containsKey("User-Agent")) headers.put("User-Agent", MOBILE_UA);

        if (!headers.containsKey("Referer")) {
            if (!TextUtils.isEmpty(siteUrl) && siteUrl.matches("^[\\x00-\\x7F]*$")) {
                headers.put("Referer", siteUrl);
            } else {
                headers.put("Referer", getHost(siteUrl) + "/");
            }
        }

        if (cookieJar.containsKey(host)) {
            headers.put("Cookie", cookieJar.get(host));
        }

        // 🚀 直接調用原生的 OkHttp，保證 OkResult 數據完全正常
        OkResult res = execute(method, url, body, headers);

        String setCookie = getSetCookie(res.getResp());
        if (!TextUtils.isEmpty(setCookie)) {
            cookieJar.put(host, setCookie);
            if (res.getBody() != null && res.getBody().trim().length() < 1000) {
                headers.put("Cookie", setCookie);
                res = execute(method, url, body, headers);
                String secondCookie = getSetCookie(res.getResp());
                if (!TextUtils.isEmpty(secondCookie)) cookieJar.put(host, secondCookie);
            }
        }
        return res;
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
        return OkHttp.get(url, parseToMap(body), headers);
    }

    // --- TLS 偽裝核心 ---
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
                ((SSLSocket) s).setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                ((SSLSocket) s).setEnabledCipherSuites(chromeCiphers);
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
        try { return new URL(urlStr).getHost(); } catch (Exception e) { return urlStr; }
    }

    private static Map<String, String> parseToMap(String body) {
        Map<String, String> map = new HashMap<>();
        if (TextUtils.isEmpty(body)) return map;
        try {
            String[] pairs = body.split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) map.put(kv[0], kv[1]);
            }
        } catch (Exception ignored) {}
        return map;
    }
}
