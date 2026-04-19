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

    // 必須是 static 且返回 OkHttp 對象，供 Kotlin 調用
    public static OkHttp get() {
        return Loader.INSTANCE;
    }

    public static Response newCall(Request request) throws IOException {
        return client().newCall(request).execute();
    }

    public static Response newCall(String url) throws IOException {
        return client().newCall(new Request.Builder().url(url).build()).execute();
    }

    public static Response newCall(String url, Map<String, String> header) throws IOException {
        return client().newCall(new Request.Builder().url(url).headers(Headers.of(header)).build()).execute();
    }

    public static String string(String url) {
        return string(url, null);
    }

    public static String string(String url, Map<String, String> header) {
        return string(url, null, header);
    }

    // 這裡的參數順序必須嚴格按照你原始文件的 (url, params, header)
    public static String string(String url, Map<String, String> params, Map<String, String> header) {
        return string(url, params, header, null);
    }

    // 新增：支持編碼的底層方法
    public static String string(String url, Map<String, String> params, Map<String, String> header, String charset) {
        return url.startsWith("http") ? new OkRequest(GET, url, params, header).execute(client(), charset).getBody() : "";
    }

    // 提供給 KaiGe.java 用的快捷方法
    public static String string(String url, Map<String, String> header, String charset) {
        return string(url, null, header, charset);
    }

    public static String post(String url, Map<String, String> params) {
        return post(url, params, null);
    }

    public static String post(String url, Map<String, String> params, Map<String, String> header) {
        return url.startsWith("http") ? new OkRequest(POST, url, params, header).execute(client()).getBody() : "";
    }

    public static String postJson(String url, String json) {
        return postJson(url, json, null);
    }

    public static String postJson(String url, String json, Map<String, String> header) {
        return url.startsWith("http") ? new OkRequest(POST, url, json, header).execute(client()).getBody() : "";
    }

    public static Map<String, List<String>> getLocationHeader(String url, Map<String, String> header) throws IOException {
        return client().newBuilder().followRedirects(false).followSslRedirects(false).build().newCall(new Request.Builder().url(url).headers(Headers.of(header)).build()).execute().headers().toMultimap();
    }

    // 必須保留，供 BaiduDrive.kt 使用
    public static String getLocation(Map<String, List<String>> headers) {
        if (headers == null) return null;
        if (headers.containsKey("location")) return headers.get("location").get(0);
        if (headers.containsKey("Location")) return headers.get("Location").get(0);
        return null;
    }

    private static OkHttpClient build() {
        if (get().client != null) return get().client;
        return get().client = getBuilder().build();
    }

    private static OkHttpClient.Builder getBuilder() {
        return new OkHttpClient.Builder()
                .dns(safeDns())
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .hostnameVerifier((hostname, session) -> true)
                .sslSocketFactory(new SSLCompat(), SSLCompat.TM);
    }

    private static OkHttpClient client() {
        try {
            return Objects.requireNonNull(Spider.client());
        } catch (Throwable e) {
            return build();
        }
    }

    private static Dns safeDns() {
        try {
            return Objects.requireNonNull(Spider.safeDns());
        } catch (Throwable e) {
            return Dns.SYSTEM;
        }
    }
}
