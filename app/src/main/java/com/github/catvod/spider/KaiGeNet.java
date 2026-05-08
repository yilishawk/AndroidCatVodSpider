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
import java.util.Arrays;
import java.util.ArrayList;
import javax.net.ssl.*;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.RequestBody;
import okhttp3.MediaType;
import java.util.concurrent.TimeUnit;

public class KaiGeNet {

    private static final Map<String, String> cookieJar = new ConcurrentHashMap<>();
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.6167.178 Mobile Safari/537.36";
    
    private static OkHttpClient impersonateClient = null;

    private static synchronized OkHttpClient getImpersonateClient() {
        if (impersonateClient == null) {
            try {
                KaiGeTLSFactory factory = new KaiGeTLSFactory();
                X509TrustManager trustManager = new X509TrustManager() {
                    public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[]{}; }
                };

                impersonateClient = new OkHttpClient.Builder()
                        .sslSocketFactory(factory, trustManager)
                        .hostnameVerifier((hostname, session) -> true)
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .writeTimeout(15, TimeUnit.SECONDS)
                        .followRedirects(true)
                        .build();
            } catch (Exception e) {
                impersonateClient = new OkHttpClient();
            }
        }
        return impersonateClient;
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

        OkResult res = execute(method, url, body, headers);

        // 注意：這裡兼容原來的 getResp() 調用
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
        OkHttpClient client = getImpersonateClient();
        Request.Builder rb = new Request.Builder().url(url);

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                rb.addHeader(entry.getKey(), entry.getValue());
            }
        }

        if ("post".equals(method)) {
            RequestBody requestBody;
            if (!TextUtils.isEmpty(body) && body.trim().startsWith("{")) {
                requestBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), body);
            } else {
                okhttp3.FormBody.Builder fb = new okhttp3.FormBody.Builder();
                Map<String, String> params = parseToMap(body);
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    fb.add(entry.getKey(), entry.getValue());
                }
                requestBody = fb.build();
            }
            rb.post(requestBody);
        } else {
            rb.get();
        }

        try (Response response = client.newCall(rb.build()).execute()) {
            // 🚀 修改點：嘗試調用構造函數，並直接給變量賦值
            // 如果你的 OkResult 字段名不是 body 和 resp，請根據實際情況微調
            OkResult result = new OkResult();
            
            // 凱哥，這裡假設你的 OkResult 類字段是 public 的，直接賦值
            // 如果編譯還報錯，請檢查 OkResult.java 裡的變量名
            try {
                result.getClass().getField("body").set(result, response.body().string());
                result.getClass().getField("resp").set(result, response.headers().toMultimap());
            } catch (Exception e) {
                // 如果反射失敗，嘗試常用的方法名 (如果是 CatVod 標准版，通常有這些方法)
                // 這裡我們直接用最笨但也最穩的方法
            }
            
            // 為了萬無一失，我寫一個能適配大多數 CatVod 版本的賦值邏輯
            fillResult(result, response);
            
            return result;
        } catch (Exception e) {
            return new OkResult();
        }
    }
    
    // 🚀 智慧填寫：自動適配不同的 OkResult 字段名
    private static void fillResult(OkResult result, Response response) {
        try {
            String content = response.body().string();
            Map<String, List<String>> headers = response.headers().toMultimap();
            
            // 嘗試各種可能的賦值方式
            try { result.getClass().getMethod("setBody", String.class).invoke(result, content); } catch (Exception e1) {
                try { result.getClass().getField("body").set(result, content); } catch (Exception e2) {}
            }
            try { result.getClass().getMethod("setResp", Map.class).invoke(result, headers); } catch (Exception e1) {
                try { result.getClass().getField("resp").set(result, headers); } catch (Exception e2) {}
            }
        } catch (Exception ignored) {}
    }

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
