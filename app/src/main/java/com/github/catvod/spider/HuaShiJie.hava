package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class HuaShiJie extends Spider {

    // ============ 常量 ============
    private static final String API_BASE   = "https://api.huashijie.tv/api.php/";
    private static final String LIVE_BASE  = "http://194.147.100.173/box/";
    private static final String EPG_URL    = "http://cdn.1678520.xyz/epg/?ch={name}&date={date}";
    private static final String LOGO_URL   = "https://epg.112114.xyz/logo/{name}.png";
    private static final String UA         = "okhttp/5.0.0-alpha.14";

    // 设备相关，建议通过 extend 注入，这里保留测试机默认值仅作兜底
    private static final String DEFAULT_UID =
            "l1.mKUI7nkJjQL-kGr4NVldl1h2tsMx1brmJ2N-IW-b2FBQbjqXWIw";

    // ============ 运行期状态 ============
    private String liveUid = DEFAULT_UID;
    private OkHttpClient client;
    private final Gson gson = new Gson();

    @Override
    public void init(Context context, String extend) throws Exception {
        client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();

        // extend 支持两种写法：
        //   1) 纯 uid 字符串
        //   2) JSON: {"uid":"xxx"}
        if (!TextUtils.isEmpty(extend)) {
            String t = extend.trim();
            if (t.startsWith("{")) {
                try {
                    JsonObject o = gson.fromJson(t, JsonObject.class);
                    if (o.has("uid") && !o.get("uid").isJsonNull()) {
                        String u = o.get("uid").getAsString();
                        if (!TextUtils.isEmpty(u)) liveUid = u;
                    }
                } catch (Exception ignore) { /* 保底 */ }
            } else {
                liveUid = t;
            }
        }
    }

    // =================================================================
    //  通用 GET（自动 gzip：OkHttp 默认会给 Accept-Encoding 且自动解压）
    // =================================================================
    private String httpGet(String url) throws IOException {
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "*/*")
                .get()
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (resp.body() == null) return "";
            return resp.body().string();
        }
    }

    // =================================================================
    //  homeContent —— 分类
    //  GET v2.vod/androidtypes
    //  返回: data[] = [{type_id,type_name,...}, ...]
    // =================================================================
    @Override
    public String homeContent(boolean filter) throws Exception {
        String body = httpGet(API_BASE + "v2.vod/androidtypes");

        JsonObject out = new JsonObject();
        JsonArray classes = new JsonArray();

        JsonObject root = safeParse(body);
        JsonArray arr = extractDataArray(root);
        if (arr != null) {
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                JsonObject item = e.getAsJsonObject();
                JsonObject cls = new JsonObject();
                cls.addProperty("type_id",  optString(item, "type_id"));
                cls.addProperty("type_name", optString(item, "type_name"));
                classes.add(cls);
            }
        }
        out.add("class", classes);

        // 简单筛选器：地区 / 年份（覆盖所有分类）
        if (filter) out.add("filters", buildFilters(classes));
        return out.toString();
    }

    private JsonObject buildFilters(JsonArray classes) {
        JsonObject filters = new JsonObject();
        String[] areas = {"全部", "大陆", "香港", "台湾", "美国", "韩国", "日本", "泰国", "其它"};
        String[] years = {"全部", "2026", "2025", "2024", "2023", "2022", "2021", "2020", "2019", "2018"};

        for (JsonElement e : classes) {
            if (!e.isJsonObject()) continue;
            String tid = optString(e.getAsJsonObject(), "type_id");
            if (TextUtils.isEmpty(tid)) continue;

            JsonArray dims = new JsonArray();
            dims.add(buildFilterDim("area", "地区", areas));
            dims.add(buildFilterDim("year", "年份", years));
            filters.add(tid, dims);
        }
        return filters;
    }

    private JsonObject buildFilterDim(String key, String name, String[] values) {
        JsonObject dim = new JsonObject();
        dim.addProperty("key", key);
        dim.addProperty("name", name);
        JsonArray vals = new JsonArray();
        for (String v : values) {
            JsonObject o = new JsonObject();
            o.addProperty("n", v);
            o.addProperty("v", "全部".equals(v) ? "" : v);
            vals.add(o);
        }
        dim.add("value", vals);
        return dim;
    }

    // =================================================================
    //  categoryContent —— 分类列表 / 筛选
    //  GET v2.vod/androidfilter?type_id=&page=&area=&year=&class=
    // =================================================================
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) throws Exception {
        StringBuilder url = new StringBuilder(API_BASE)
                .append("v2.vod/androidfilter")
                .append("?type_id=").append(enc(tid))
                .append("&page=").append(enc(TextUtils.isEmpty(pg) ? "1" : pg));

        if (extend != null && !extend.isEmpty()) {
            String area  = extend.get("area");
            String year  = extend.get("year");
            String klass = extend.get("class");
            if (!TextUtils.isEmpty(area))  url.append("&area=").append(enc(area));
            if (!TextUtils.isEmpty(year))  url.append("&year=").append(enc(year));
            if (!TextUtils.isEmpty(klass)) url.append("&class=").append(enc(klass));
        }

        String body = httpGet(url.toString());
        return wrapListResult(body, true);
    }

    // =================================================================
    //  detailContent —— 详情
    //  GET v2.vod/androiddetail?vod_id=
    //  data.urls[] = [{type,key,url}]  （url 即 m3u8 明文直链）
    // =================================================================
    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return "";
        String vodId = ids.get(0);

        String body = httpGet(API_BASE + "v2.vod/androiddetail?vod_id=" + enc(vodId));
        JsonObject root = safeParse(body);
        JsonObject data = root != null && root.has("data") && root.get("data").isJsonObject()
                ? root.getAsJsonObject("data") : null;
        if (data == null) return "";

        JsonObject vod = new JsonObject();
        vod.addProperty("vod_id",      optString(data, "id"));
        vod.addProperty("vod_name",    optString(data, "name"));
        vod.addProperty("vod_pic",     optString(data, "pic"));
        vod.addProperty("vod_year",    optString(data, "year"));
        vod.addProperty("vod_area",    optString(data, "area"));
        vod.addProperty("vod_director",optString(data, "director"));
        vod.addProperty("vod_actor",   optString(data, "actor"));
        vod.addProperty("vod_content", optString(data, "content"));
        vod.addProperty("vod_remarks", optString(data, "state"));

        // urls[] -> vod_play_from / vod_play_url
        StringBuilder fromBuf = new StringBuilder();
        StringBuilder urlBuf  = new StringBuilder();

        JsonArray urls = data.has("urls") && data.get("urls").isJsonArray()
                ? data.getAsJsonArray("urls") : new JsonArray();

        for (int i = 0; i < urls.size(); i++) {
            JsonElement el = urls.get(i);
            if (!el.isJsonObject()) continue;
            JsonObject u = el.getAsJsonObject();
            String flag = firstNonEmpty(optString(u, "key"), optString(u, "type"), "线路" + (i + 1));
            String link = optString(u, "url");
            if (TextUtils.isEmpty(link)) continue;

            if (fromBuf.length() > 0) fromBuf.append("$$$");
            if (urlBuf.length() > 0)  urlBuf.append("$$$");

            fromBuf.append(flag);
            // 这里认为一条 url 就是正片/整季直链，集名统一“正片”
            // 若 urls[] 里 type/key 表示不同集数，请按业务改成 "第X集$url#..."
            urlBuf.append("正片").append("$").append(link);
        }

        vod.addProperty("vod_play_from", fromBuf.toString());
        vod.addProperty("vod_play_url",  urlBuf.toString());

        JsonArray list = new JsonArray();
        list.add(vod);
        JsonObject out = new JsonObject();
        out.add("list", list);
        return out.toString();
    }

    // =================================================================
    //  searchContent —— 搜索
    //  GET v2.vod/androidsearch10086?wd=&page=
    // =================================================================
    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        String body = httpGet(API_BASE + "v2.vod/androidsearch10086?wd="
                + enc(key) + "&page=" + enc(TextUtils.isEmpty(pg) ? "1" : pg));
        return wrapListResult(body, false);
    }

    // =================================================================
    //  playerContent —— 播放
    //  详情里已是明文 m3u8，直接 parse=0 返回
    // =================================================================
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        JsonObject out = new JsonObject();
        out.addProperty("parse", 0);
        out.addProperty("url", id);
        out.addProperty("jx", 0);
        // 部分 CDN 会校验 Referer，可按需补：
        // JsonObject h = new JsonObject();
        // h.addProperty("User-Agent", UA);
        // h.addProperty("Referer", "https://api.huashijie.tv/");
        // out.add("header", h);
        return out.toString();
    }

    // =================================================================
    //  liveContent —— 直播
    //  GET http://194.147.100.173/box/tvzb99.php?uid=<uid>
    //  直接返回 TVBox TXT 列表；备用线路 tvzb991
    // =================================================================
    @Override
    public String liveContent(String url) throws Exception {
        // 优先用配置里传入的 url，否则用默认海外专线
        String target = TextUtils.isEmpty(url) ? (LIVE_BASE + "tvzb99.php") : url;
        String full = target + (target.contains("?") ? "&" : "?") + "uid=" + enc(liveUid);
        return httpGet(full);
    }

    // =================================================================
    //  工具
    // =================================================================
    private JsonObject safeParse(String body) {
        if (TextUtils.isEmpty(body)) return null;
        try { return gson.fromJson(body, JsonObject.class); }
        catch (Exception e) { return null; }
    }

    private JsonArray extractDataArray(JsonObject root) {
        if (root == null) return null;
        if (root.has("data") && root.get("data").isJsonArray())
            return root.getAsJsonArray("data");
        if (root.has("list") && root.get("list").isJsonArray())
            return root.getAsJsonArray("list");
        return null;
    }

    /**
     * 把列表接口的 data[] 转成 { list:[Vod], pagecount:N }
     * pagecount 源码未给明确字段，这里若 body 里有 total/count 就折算，否则给 1。
     */
    private String wrapListResult(String body, boolean withPageCount) {
        JsonObject out = new JsonObject();
        JsonArray listArr = new JsonArray();

        JsonObject root = safeParse(body);
        JsonArray data = extractDataArray(root);
        if (data != null) {
            for (JsonElement e : data) {
                if (!e.isJsonObject()) continue;
                JsonObject it = e.getAsJsonObject();
                JsonObject v = new JsonObject();
                v.addProperty("vod_id",      optString(it, "id"));
                v.addProperty("vod_name",    optString(it, "name"));
                v.addProperty("vod_pic",     optString(it, "pic"));
                v.addProperty("vod_remarks", firstNonEmpty(optString(it, "updateInfo"), optString(it, "state")));
                v.addProperty("vod_year",    optString(it, "year"));
                v.addProperty("vod_area",    optString(it, "area"));
                v.addProperty("type_name",   optString(it, "className"));
                listArr.add(v);
            }
        }
        out.add("list", listArr);

        if (withPageCount) {
            int pageCount = 1;
            if (root != null && root.has("total") && !root.get("total").isJsonNull()) {
                try {
                    int total = root.get("total").getAsInt();
                    // 每页 20 条（抓包已知）
                    pageCount = Math.max(1, (total + 19) / 20);
                } catch (Exception ignore) { }
            }
            out.addProperty("pagecount", pageCount);
        }
        return out.toString();
    }

    private static String optString(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        try { return o.get(key).getAsString(); }
        catch (Exception e) { return ""; }
    }

    private static String firstNonEmpty(String... arr) {
        for (String s : arr) if (!TextUtils.isEmpty(s)) return s;
        return "";
    }

    private static String enc(String s) {
        if (s == null) return "";
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    @Override
    public void destroy() {
        if (client != null) {
            try { client.dispatcher().executorService().shutdown(); } catch (Exception ignore) { }
            try { client.connectionPool().evictAll(); } catch (Exception ignore) { }
            client = null;
        }
    }
}
