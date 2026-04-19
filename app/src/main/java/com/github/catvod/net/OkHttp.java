package com.github.catvod.net;

import com.github.catvod.crawler.Spider;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import okhttp3.Dns;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class OkHttp {

    public static final String POST = "POST";
    public static final String GET = "GET";
    private OkHttpClient client;

    private static class Loader {
        static volatile OkHttp INSTANCE = new OkHttp();
    }

    // 這個方法必須保留，但不能和下面的靜態請求方法衝突
    public static OkHttp get() {
        return Loader.INSTANCE;
    }

    // === 百度雲/123盤腳本最依賴的靜態請求方法 (返回 OkResult) ===
    
    public static OkResult get(String url, Map<String, String> params, Map<String, String> header) {
        return new OkRequest(GET, url, params, header).execute(client());
    }

    public static OkResult post(String url, Map<String, String> params, Map<String, String> header) {
        return new OkRequest(POST, url, params, header).execute(client());
    }

    public static OkResult postJson(String url, String json, Map<String, String> header) {
        return new OkRequest(POST, url, json, header).execute(client());
    }

    // === 供 KaiGe.java 等使用的 String 返回方法 ===

    public static String string(String url) {
        return string(url, null);
    }

    public static String string(String url, Map<String, String> header) {
        return string(url, null, header, null);
    }

    // 新增：支持編碼的 string 方法
    public static String string(String url, Map<String, String> header, String charset) {
        return string(url, null, header, charset);
    }

    public static String string(String url, Map<String, String> params, Map<String, String> header, String charset) {
        return url.startsWith("http") ? new OkRequest(GET, url, params, header).execute(client(), charset).getBody() : "";
    }

    // === 原有的 newCall 和 工具方法 ===

    public static Response newCall(Request request) throws IOException {
        return client().newCall(request).execute();
    }

    public static Response newCall(String url, Map<String, String> header) throws IOException {
        return client().newCall(new Request.Builder().url(url).headers(Headers.of(header)).build()).execute();
    }

    public static String getLocation(Map<String, List<String>> headers) {
        if (headers == null) return null;
        if (headers.containsKey("location")) return headers.get("location").get(0);
        if (headers.containsKey("Location")) return headers.get("Location").get(0);
        return null;
    }

    // === 底層構建 ===

    private static OkHttpClient client() {
        try {
            return Objects.requireNonNull(Spider.client());
        } catch (Throwable e) {
            return build();
        }
    }

    private static OkHttpClient build() {
        if (get().client != null) return get().client;
        return get().client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .sslSocketFactory(new SSLCompat(), SSLCompat.TM)
                .hostnameVerifier((hostname, session) -> true)
                .build();
    }
}
