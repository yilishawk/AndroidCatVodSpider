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

    private static OkHttp get() {
        return Loader.INSTANCE;
    }

    // === 必須保留的 newCall 系列，否則 Kotlin 會報錯 ===
    public static Response newCall(Request request) throws IOException {
        return client().newCall(request).execute();
    }

    public static Response newCall(String url) throws IOException {
        return client().newCall(new Request.Builder().url(url).build()).execute();
    }

    public static Response newCall(String url, Map<String, String> header) throws IOException {
        return client().newCall(new Request.Builder().url(url).headers(Headers.of(header)).build()).execute();
    }

    // === String 返回系列 ===
    public static String string(String url) {
        return string(url, null);
    }

    public static String string(String url, Map<String, String> header) {
        return string(url, header, null);
    }

    // 我們新增的帶編碼的方法
    public static String string(String url, Map<String, String> header, String charset) {
        return url.startsWith("http") ? new OkRequest(GET, url, null, header).execute(client(), charset).getBody() : "";
    }

    // === Post 系列 ===
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

    // === 其餘工具方法保持不變 ===
    public static Map<String, List<String>> getLocationHeader(String url, Map<String, String> header) throws IOException {
        return client().newBuilder().followRedirects(false).followSslRedirects(false).build().newCall(new Request.Builder().url(url).headers(Headers.of(header)).build()).execute().headers().toMultimap();
    }

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
