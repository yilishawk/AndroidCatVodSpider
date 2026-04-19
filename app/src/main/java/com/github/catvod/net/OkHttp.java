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

    public static OkHttp get() {
        return Loader.INSTANCE;
    }

    // === 百度雲/123盤等腳本調用的核心靜態方法 (返回 OkResult) ===
    
    public static OkResult get(String url, Map<String, String> params, Map<String, String> header) {
        return new OkRequest(GET, url, params, header).execute(client());
    }

    public static OkResult post(String url, Map<String, String> params, Map<String, String> header) {
        return new OkRequest(POST, url, params, header).execute(client());
    }

    public static OkResult postJson(String url, String json, Map<String, String> header) {
        return new OkRequest(POST, url, json, header).execute(client());
    }

    // === 必須嚴格對齊參數順序的 String 返回方法 ===

    public static String string(String url) {
        return string(url, null);
    }

    public static String string(String url, Map<String, String> header) {
        // 修正點：原始順序是 (url, params, header)，這裡 header 必須放在第三位
        return string(url, null, header, null);
    }

    // 提供給 KaiGe.java 用的 3 參數快捷方法
    public static String string(String url, Map<String, String> header, String charset) {
        return string(url, null, header, charset);
    }

    // 最底層的 string 方法
    public static String string(String url, Map<String, String> params, Map<String, String> header, String charset) {
        return url.startsWith("http") ? new OkRequest(GET, url, params, header).execute(client(), charset).getBody() : "";
    }

    // === Kotlin 腳本依賴的 newCall 和 工具方法 ===

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

    // === Client 構建邏輯 ===

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
