package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 九州空间 - 自建 OkHttpClient + 手写 JSONObject，兼容 Fongmi
 * 站点: m.9zhoukj.com
 */
public class JP extends Spider {

    private static final String HOST = "https://m.9zhoukj.com";
    private static final String KEY = "cb808529bae6b6be45ecfab29a4889bc";
    private static final String DEVICE_ID = "7dbc13a7-7976-4d7b-89d2-c110d09d7410";
    private static final String UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36";

    private final OkHttpClient client;
    private final Map<String, String> headers;

    public JP() {
        // 自建 OkHttpClient（兼容老旧 TLS）
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .sslSocketFactory(new com.github.catvod.net.SSLCompat(),
                        com.github.catvod.net.SSLCompat.TM)
                .hostnameVerifier((hostname, session) -> true)
                .build();

        this.headers = new HashMap<>();
        this.headers.put("User-Agent", UA);
        this.headers.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        this.headers.put("Accept", "application/json, text/plain, */*");
        this.headers.put("Referer", HOST + "/");
    }

    private void log(String msg) {
        SpiderDebug.log("[JP] " + msg);
    }

    // ----- 签名工具（原封不动） -----
    private String md5(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(input.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String sha1(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest(input.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String generateSign(Map<String, String> params) throws Exception {
        Map<String, String> valid = new HashMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            String v = e.getValue();
            if (v != null && !v.isEmpty()) valid.put(e.getKey(), v);
        }
        List<String> keys = new ArrayList<>(valid.keySet());
        Collections.sort(keys);
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) query.append("&");
            query.append(keys.get(i)).append("=").append(valid.get(keys.get(i)));
        }
        String t = String.valueOf(System.currentTimeMillis());
        String signStr = query.toString() + "&key=" + KEY + "&t=" + t;
        String md5hex = md5(signStr);
        String sign = sha1(md5hex);
        return t + "|" + sign;
    }

    private Map<String, String> buildHeaders(Map<String, String> params) throws Exception {
        String[] ts = generateSign(params).split("\\|");
        Map<String, String> h = new HashMap<>(headers);
        h.put("Host", "m.9zhoukj.com");
        h.put("client-type", "3");
        h.put("deviceId", DEVICE_ID);
        h.put("sign", ts[1]);
        h.put("t", ts[0]);
        return h;
    }

    // ----- 网络请求封装 -----
    private String get(String url, Map<String, String> extraHeaders) throws Exception {
        Map<String, String> allHeaders = new HashMap<>(headers);
        if (extraHeaders != null) allHeaders.putAll(extraHeaders);
        Request request = new Request.Builder()
                .url(url)
                .headers(Headers.of(allHeaders))
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new Exception("HTTP " + response.code());
            }
            return response.body().string();
        }
    }

    private String postForm(String url, Map<String, String> formData, Map<String, String> extraHeaders) throws Exception {
        Map<String, String> allHeaders = new HashMap<>(headers);
        if (extraHeaders != null) allHeaders.putAll(extraHeaders);
        // 构建表单
        StringBuilder bodyBuilder = new StringBuilder();
        for (Map.Entry<String, String> e : formData.entrySet()) {
            if (bodyBuilder.length() > 0) bodyBuilder.append("&");
            bodyBuilder.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                    .append("=")
                    .append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        RequestBody body = RequestBody.create(
                MediaType.parse("application/x-www-form-urlencoded; charset=UTF-8"),
                bodyBuilder.toString()
        );
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .headers(Headers.of(allHeaders))
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new Exception("HTTP " + response.code());
            }
            return response.body().string();
        }
    }

    /**
     * 通用 API 请求（自动加签名头）
     */
    private String fetchApi(String path, Map<String, String> params) throws Exception {
        if (params == null) params = new HashMap<>();
        // 签名仅对非空参数进行，且需要排序
        Map<String, String> valid = new HashMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                valid.put(e.getKey(), e.getValue());
            }
        }
        // 构建 URL（GET）
        String url;
        if (path.contains("?")) {
            url = HOST + path;
        } else {
            StringBuilder urlBuilder = new StringBuilder(HOST).append(path);
            if (!valid.isEmpty()) {
                urlBuilder.append("?");
                List<String> keys = new ArrayList<>(valid.keySet());
                Collections.sort(keys);
                for (int i = 0; i < keys.size(); i++) {
                    if (i > 0) urlBuilder.append("&");
                    urlBuilder.append(keys.get(i)).append("=")
                            .append(URLEncoder.encode(valid.get(keys.get(i)), "UTF-8"));
                }
            }
            url = urlBuilder.toString();
        }
        // 签名头（基于原始参数，非 URL 编码）
        Map<String, String> headersWithSign = buildHeaders(valid);
        return get(url, headersWithSign);
    }

    // ----- homeContent -----
    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = new JSONArray();
            String[][] classArr = {
                    {"2_14", "国产剧"}, {"2_15", "欧美剧"}, {"2_62", "日韩剧"},
                    {"2_16", "港台剧"}, {"1_", "电影"}, {"3_", "综艺"}
            };
            for (String[] c : classArr) {
                JSONObject cls = new JSONObject();
                cls.put("type_id", c[0]);
                cls.put("type_name", c[1]);
                classes.put(cls);
            }
            result.put("class", classes);

            if (filter) {
                // 构建 filters（与之前一致，改用 JSONObject）
                JSONObject filters = new JSONObject();

                // 公共年份
                JSONArray yearValues = new JSONArray();
                yearValues.put(createOption("全部", ""));
                for (int y = 2026; y >= 2010; y--) {
                    yearValues.put(createOption(String.valueOf(y), String.valueOf(y)));
                }
                JSONArray sortValues = new JSONArray();
                sortValues.put(createOption("最新", "1"));
                sortValues.put(createOption("最热", "2"));

                // 电视剧通用
                JSONArray tvFilters = new JSONArray();
                tvFilters.put(createFilter("v_class", "剧情", new String[][]{
                        {"全部",""},{"古装","古装"},{"战争","战争"},{"喜剧","喜剧"},{"家庭","家庭"},
                        {"犯罪","犯罪"},{"动作","动作"},{"奇幻","奇幻"},{"剧情","剧情"},{"历史","历史"},{"短片","短片"}
                }));
                tvFilters.put(createFilter("area", "地区", new String[][]{
                        {"全部",""},{"中国大陆","中国大陆"},{"中国香港","中国香港"},{"美国","美国"}
                }));
                tvFilters.put(createFilter("year", "年代", yearValues));
                tvFilters.put(createFilter("sort", "排序", sortValues));

                // 电影
                JSONArray movieFilters = new JSONArray();
                movieFilters.put(createFilter("type", "类型", new String[][]{
                        {"全部",""},{"喜剧","22"},{"动作","23"},{"科幻","30"},{"爱情","26"},{"悬疑","27"},
                        {"奇幻","87"},{"剧情","37"},{"恐怖","36"},{"犯罪","35"},{"动画","33"},{"惊悚","34"},
                        {"战争","25"},{"冒险","31"},{"灾难","81"}
                }));
                movieFilters.put(createFilter("v_class", "剧情", new String[][]{
                        {"全部",""},{"爱情","爱情"},{"动作","动作"},{"科幻","科幻"},{"恐怖","恐怖"}
                }));
                movieFilters.put(createFilter("area", "地区", new String[][]{
                        {"全部",""},{"中国大陆","中国大陆"},{"中国香港","中国香港"},{"中国台湾","中国台湾"},
                        {"美国","美国"},{"日本","日本"},{"韩国","韩国"},{"印度","印度"},{"泰国","泰国"},
                        {"英国","英国"},{"法国","法国"}
                }));
                movieFilters.put(createFilter("year", "年代", yearValues));
                movieFilters.put(createFilter("sort", "排序", sortValues));

                // 综艺
                JSONArray zyFilters = new JSONArray();
                zyFilters.put(createFilter("type", "类型", new String[][]{
                        {"全部",""},{"国产综艺","69"},{"港台综艺","70"},{"日韩综艺","72"}
                }));
                zyFilters.put(createFilter("v_class", "剧情", new String[][]{
                        {"全部",""},{"真人秀","真人秀"},{"音乐","音乐"},{"脱口秀","脱口秀"}
                }));
                zyFilters.put(createFilter("year", "年代", yearValues));

                filters.put("2_14", tvFilters);
                filters.put("2_15", tvFilters);
                filters.put("2_62", tvFilters);
                filters.put("2_16", tvFilters);
                filters.put("1_", movieFilters);
                filters.put("3_", zyFilters);
                result.put("filters", filters);
            }
            return result.toString();
        } catch (Exception e) {
            log("homeContent error: " + e.getMessage());
            return "{\"class\":[],\"filters\":{}}";
        }
    }

    private JSONObject createOption(String n, String v) throws Exception {
        JSONObject o = new JSONObject();
        o.put("n", n);
        o.put("v", v);
        return o;
    }

    private JSONObject createFilter(String key, String name, String[][] opts) throws Exception {
        JSONObject f = new JSONObject();
        f.put("key", key);
        f.put("name", name);
        JSONArray arr = new JSONArray();
        for (String[] opt : opts) {
            arr.put(createOption(opt[0], opt[1]));
        }
        f.put("value", arr);
        return f;
    }

    private JSONObject createFilter(String key, String name, JSONArray values) throws Exception {
        JSONObject f = new JSONObject();
        f.put("key", key);
        f.put("name", name);
        f.put("value", values);
        return f;
    }

    // ----- categoryContent（关键修复） -----
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            if (extend == null) extend = new HashMap<>();
            int pageNum = 1;
            try { pageNum = Integer.parseInt(pg); } catch (Exception ignored) {}
            if (tid == null || tid.isEmpty()) {
                return "{\"list\":[],\"page\":" + pageNum + ",\"pagecount\":1}";
            }

            String[] parts = tid.split("_", 2);
            String type1 = parts[0];
            String subType = parts.length > 1 ? parts[1] : "";

            Map<String, String> params = new HashMap<>();
            params.put("type1", type1);
            params.put("pageNum", String.valueOf(pageNum));
            params.put("pageSize", "30");
            params.put("sort", extend.getOrDefault("sort", "1"));
            params.put("sortBy", "1");

            String finalType = extend.getOrDefault("type", subType);
            if (finalType != null && !finalType.isEmpty()) params.put("type", finalType);
            String area = extend.getOrDefault("area", "");
            if (!area.isEmpty()) params.put("area", area);
            String vClass = extend.getOrDefault("v_class", "");
            if (!vClass.isEmpty()) params.put("v_class", vClass);
            String year = extend.getOrDefault("year", "");
            if (!year.isEmpty()) params.put("year", year);

            String json = fetchApi("/api/mw-movie/anonymous/video/list", params);
            if (json == null || json.isEmpty()) {
                return "{\"list\":[],\"page\":" + pageNum + ",\"pagecount\":1}";
            }

            JSONObject root = new JSONObject(json);
            int code = root.optInt("code", -1);
            if (code != 0 && code != 200) {
                log("list API error: " + root.optString("msg"));
                return "{\"list\":[],\"page\":" + pageNum + ",\"pagecount\":1}";
            }

            JSONObject data = root.optJSONObject("data");
            JSONArray list = data != null ? data.optJSONArray("list") : null;
            JSONArray videos = new JSONArray();
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.getJSONObject(i);
                    JSONObject vod = new JSONObject();
                    vod.put("vod_id", item.optString("vodId"));
                    vod.put("vod_name", item.optString("vodName"));
                    vod.put("vod_pic", item.optString("vodPic"));
                    vod.put("vod_remarks", item.optString("vodRemarks", ""));
                    videos.put(vod);
                }
            }

            // 计算总页数
            int totalCount = 0;
            int totalPage = 1;
            if (data != null) {
                totalCount = data.optInt("totalCount", data.optInt("total", 0));
                totalPage = data.optInt("totalPage", 0);
                if (totalPage <= 0 && totalCount > 0) {
                    totalPage = (int) Math.ceil(totalCount / 30.0);
                }
            }
            if (totalPage <= 0) totalPage = 1;

            JSONObject result = new JSONObject();
            result.put("list", videos);
            result.put("page", pageNum);
            result.put("pagecount", totalPage);
            result.put("limit", 30);
            result.put("total", totalCount);
            return result.toString();
        } catch (Exception e) {
            log("categoryContent error: " + e.getMessage());
            return "{\"list\":[],\"page\":1,\"pagecount\":1}";
        }
    }

    // ----- detailContent -----
    @Override
    public String detailContent(List<String> ids) {
        try {
            if (ids == null || ids.isEmpty()) return "{\"list\":[]}";
            String vodId = ids.get(0);
            Map<String, String> params = new HashMap<>();
            params.put("id", vodId);
            String json = fetchApi("/api/mw-movie/anonymous/video/detail", params);
            if (json == null || json.isEmpty()) return "{\"list\":[]}";

            JSONObject data = new JSONObject(json).optJSONObject("data");
            if (data == null) return "{\"list\":[]}";

            JSONObject vod = new JSONObject();
            vod.put("vod_id", data.optString("vodId", vodId));
            vod.put("vod_name", data.optString("vodName", ""));
            vod.put("vod_pic", data.optString("vodPic", ""));
            vod.put("vod_content", data.optString("vodContent", data.optString("vodBlurb", "")));

            JSONArray episodes = data.optJSONArray("episodeList");
            List<String> epList = new ArrayList<>();
            if (episodes != null) {
                for (int i = 0; i < episodes.length(); i++) {
                    JSONObject ep = episodes.getJSONObject(i);
                    String name = ep.optString("name", "正片");
                    String nid = ep.optString("nid", "0");
                    epList.add(name + "$" + vodId + "@@" + nid);
                }
            }

            if (epList.isEmpty()) {
                vod.put("vod_play_from", "");
                vod.put("vod_play_url", "");
            } else {
                vod.put("vod_play_from", "九州空间");
                vod.put("vod_play_url", String.join("#", epList));
            }

            JSONArray list = new JSONArray();
            list.put(vod);
            JSONObject result = new JSONObject();
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            log("detailContent error: " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    // ----- searchContent -----
    @Override
    public String searchContent(String key, boolean quick) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("keyword", key);
            params.put("pageNum", "1");
            params.put("pageSize", "30");
            params.put("sourceCode", "1");

            String json = fetchApi("/api/mw-movie/anonymous/video/searchByWord", params);
            if (json == null || json.isEmpty()) return "{\"list\":[]}";

            JSONObject root = new JSONObject(json);
            JSONObject data = root.optJSONObject("data");
            JSONObject resultObj = data != null ? data.optJSONObject("result") : null;
            JSONArray rawList = resultObj != null ? resultObj.optJSONArray("list") : null;
            if (rawList == null && data != null) {
                rawList = data.optJSONArray("list");
            }

            JSONArray videos = new JSONArray();
            if (rawList != null) {
                for (int i = 0; i < rawList.length(); i++) {
                    JSONObject item = rawList.getJSONObject(i);
                    JSONObject vod = new JSONObject();
                    vod.put("vod_id", item.optString("vodId"));
                    vod.put("vod_name", item.optString("vodName"));
                    vod.put("vod_pic", item.optString("vodPic"));
                    vod.put("vod_remarks", item.optString("vodRemarks", ""));
                    videos.put(vod);
                }
            }

            int total = data != null ? data.optInt("totalCount", data.optInt("total", videos.length())) : videos.length();
            int totalPage = Math.max(1, (int) Math.ceil(total / 30.0));
            JSONObject result = new JSONObject();
            result.put("list", videos);
            result.put("page", 1);
            result.put("pagecount", totalPage);
            result.put("limit", 30);
            result.put("total", total);
            return result.toString();
        } catch (Exception e) {
            log("searchContent error: " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    // ----- playerContent -----
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String[] parts = id.split("@@");
            if (parts.length < 2) {
                return "{\"parse\":1,\"url\":\"" + id + "\"}";
            }
            String vodId = parts[0];
            String nid = parts[1];

            Map<String, String> params = new HashMap<>();
            params.put("clientType", "3");
            params.put("id", vodId);
            params.put("nid", nid);

            String json = fetchApi("/api/mw-movie/anonymous/v2/video/episode/url", params);
            if (json == null || json.isEmpty()) {
                return "{\"parse\":1,\"url\":\"" + id + "\"}";
            }

            JSONObject data = new JSONObject(json).optJSONObject("data");
            if (data == null) return "{\"parse\":1,\"url\":\"" + id + "\"}";

            JSONArray list = data.optJSONArray("list");
            if (list == null || list.length() == 0) return "{\"parse\":1,\"url\":\"" + id + "\"}";

            String url = null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject v = list.getJSONObject(i);
                if (v.optInt("resolution") == 1080) {
                    url = v.optString("url");
                    break;
                }
            }
            if (url == null || url.isEmpty()) {
                url = list.getJSONObject(0).optString("url");
            }
            if (url == null || url.isEmpty()) return "{\"parse\":1,\"url\":\"" + id + "\"}";

            JSONObject header = new JSONObject();
            header.put("User-Agent", UA);
            header.put("Referer", HOST + "/");
            header.put("Origin", HOST);

            JSONObject result = new JSONObject();
            result.put("parse", 0);
            result.put("url", url);
            result.put("header", header);
            return result.toString();
        } catch (Exception e) {
            log("playerContent error: " + e.getMessage());
            return "{\"parse\":1,\"url\":\"" + id + "\"}";
        }
    }
}
