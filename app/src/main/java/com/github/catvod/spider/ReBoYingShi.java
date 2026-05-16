package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * ReBoYingShi 爬虫 (原 AppRJ)
 * 站点: http://v.rbotv.cn
 */
public class ReBoYingShi extends Spider {

    private String baseUrl = "http://v.rbotv.cn";
    private static final String SECRET = "7gp0bnd2sr85ydii2j32pcypscoc4w6c5g7spl";
    private static final String UA = "okhttp-okgo/jeasonlzy";

    private final OkHttpClient client = new OkHttpClient();

    // ================== 工具方法 ==================

    private String md5(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(input.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String makeSign(String timestamp) throws Exception {
        return md5(SECRET + timestamp);
    }

    /**
     * 通用 POST 请求，自动附加 timestamp 和 sign
     */
    private JSONObject post(String path, Map<String, String> params) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String sign = makeSign(timestamp);

        FormBody.Builder formBuilder = new FormBody.Builder();
        formBuilder.add("timestamp", timestamp);
        formBuilder.add("sign", sign);
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                formBuilder.add(e.getKey(), e.getValue());
            }
        }

        Request request = new Request.Builder()
                .url(baseUrl + path)
                .post(formBuilder.build())
                .header("User-Agent", UA)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String body = response.body().string();
                return new JSONObject(body);
            }
        }
        return null;
    }

    // ================== 初始化 ==================

    @Override
    public void init(Context context, String extend) {
        if (extend != null && !extend.isEmpty()) {
            try {
                JSONObject cfg = new JSONObject(extend);
                if (cfg.has("url")) {
                    String u = cfg.optString("url");
                    if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
                    baseUrl = u;
                }
            } catch (Exception e) {
                SpiderDebug.log(e);
            }
        }
    }

    // ================== 首页 ==================

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = post("/v3/type/top_type", null);
            if (result == null || result.optInt("code") != 1) {
                return "{\"class\":[], \"filters\":{}}";
            }

            JSONObject data = result.optJSONObject("data");
            if (data == null) return "{\"class\":[], \"filters\":{}}";

            JSONArray classArr = new JSONArray();
            JSONObject filtersObj = new JSONObject();

            JSONArray list = data.optJSONArray("list");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.getJSONObject(i);
                    String typeId = item.optString("type_id");
                    String typeName = item.optString("type_name");
                    JSONObject cls = new JSONObject();
                    cls.put("type_id", typeId);
                    cls.put("type_name", typeName);
                    classArr.put(cls);

                    // 构建筛选器
                    JSONArray filterItems = new JSONArray();

                    // extend (类型)
                    JSONArray extendList = item.optJSONArray("extend");
                    if (extendList != null && extendList.length() > 1) {
                        JSONArray options = new JSONArray();
                        for (int j = 0; j < extendList.length(); j++) {
                            String val = extendList.optString(j).trim();
                            if (!val.isEmpty()) {
                                JSONObject opt = new JSONObject();
                                opt.put("n", val);
                                opt.put("v", val);
                                options.put(opt);
                            }
                        }
                        filterItems.put(createFilter("class", "类型", options));
                    }

                    // area
                    JSONArray areaList = item.optJSONArray("area");
                    if (areaList != null && areaList.length() > 1) {
                        JSONArray options = new JSONArray();
                        for (int j = 0; j < areaList.length(); j++) {
                            String val = areaList.optString(j).trim();
                            if (!val.isEmpty()) {
                                JSONObject opt = new JSONObject();
                                opt.put("n", val);
                                opt.put("v", val);
                                options.put(opt);
                            }
                        }
                        filterItems.put(createFilter("area", "地区", options));
                    }

                    // year
                    JSONArray yearList = item.optJSONArray("year");
                    if (yearList != null && yearList.length() > 1) {
                        JSONArray options = new JSONArray();
                        for (int j = 0; j < yearList.length(); j++) {
                            String val = yearList.optString(j).trim();
                            if (!val.isEmpty()) {
                                JSONObject opt = new JSONObject();
                                opt.put("n", val);
                                opt.put("v", val);
                                options.put(opt);
                            }
                        }
                        filterItems.put(createFilter("year", "年份", options));
                    }

                    // lang
                    JSONArray langList = item.optJSONArray("lang");
                    if (langList != null && langList.length() > 1) {
                        JSONArray options = new JSONArray();
                        for (int j = 0; j < langList.length(); j++) {
                            String val = langList.optString(j).trim();
                            if (!val.isEmpty()) {
                                JSONObject opt = new JSONObject();
                                opt.put("n", val);
                                opt.put("v", val);
                                options.put(opt);
                            }
                        }
                        filterItems.put(createFilter("lang", "语言", options));
                    }

                    if (filterItems.length() > 0) {
                        filtersObj.put(typeId, filterItems);
                    }
                }
            }

            JSONObject home = new JSONObject();
            home.put("class", classArr);
            home.put("filters", filtersObj);
            return home.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return "{\"class\":[], \"filters\":{}}";
        }
    }

    private JSONObject createFilter(String key, String name, JSONArray value) throws Exception {
        JSONObject f = new JSONObject();
        f.put("key", key);
        f.put("name", name);
        f.put("value", value);
        return f;
    }

    // ================== 分类列表 ==================

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("type_id", tid);
            params.put("page", pg);
            params.put("limit", "12");

            if (extend != null) {
                for (String k : new String[]{"area", "class", "lang", "year"}) {
                    String v = extend.get(k);
                    if (v != null && !v.isEmpty()) {
                        params.put(k, v);
                    }
                }
            }

            JSONObject result = post("/v3/home/type_search", params);
            if (result == null || result.optInt("code") != 1) {
                return "{\"list\":[], \"page\":" + pg + "}";
            }

            JSONObject data = result.optJSONObject("data");
            JSONArray list = data != null ? data.optJSONArray("list") : null;
            JSONArray videos = new JSONArray();
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.getJSONObject(i);
                    String pic = item.optString("vod_pic");
                    if (pic.isEmpty()) pic = item.optString("vod_pic_thumb", "");
                    JSONObject vod = new JSONObject();
                    vod.put("vod_id", item.optString("vod_id"));
                    vod.put("vod_name", item.optString("vod_name"));
                    vod.put("vod_pic", pic);
                    vod.put("vod_remarks", item.optString("vod_remarks", ""));
                    videos.put(vod);
                }
            }
            JSONObject ret = new JSONObject();
            ret.put("list", videos);
            ret.put("page", Integer.parseInt(pg));
            ret.put("pagecount", 99999);
            return ret.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return "{\"list\":[], \"page\":" + pg + "}";
        }
    }

    // ================== 详情 ==================

    @Override
    public String detailContent(List<String> ids) {
        try {
            if (ids == null || ids.isEmpty()) return "{\"list\":[]}";
            String vodId = ids.get(0);
            Map<String, String> params = new HashMap<>();
            params.put("vod_id", vodId);
            JSONObject result = post("/v3/home/vod_details", params);
            if (result == null || result.optInt("code") != 1) return "{\"list\":[]}";

            JSONObject data = result.optJSONObject("data");
            if (data == null) return "{\"list\":[]}";

            String pic = data.optString("vod_pic");
            if (pic.isEmpty()) pic = data.optString("vod_pic_thumb", "");

            List<String> playFromList = new ArrayList<>();
            List<String> playUrlList = new ArrayList<>();

            JSONArray playSources = data.optJSONArray("vod_play_list");
            if (playSources != null) {
                for (int i = 0; i < playSources.length(); i++) {
                    JSONObject source = playSources.getJSONObject(i);
                    String sourceName = source.optString("name");
                    if (sourceName.isEmpty()) sourceName = source.optString("title", "未知源");

                    // parse_urls
                    JSONArray parseUrls = source.optJSONArray("parse_urls");
                    String parseParam = "";
                    if (parseUrls != null && parseUrls.length() > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (int j = 0; j < parseUrls.length(); j++) {
                            if (j > 0) sb.append("@");
                            sb.append(parseUrls.optString(j));
                        }
                        parseParam = sb.toString();
                    }

                    JSONArray urls = source.optJSONArray("urls");
                    if (urls != null && urls.length() > 0) {
                        List<String> episodes = new ArrayList<>();
                        for (int j = 0; j < urls.length(); j++) {
                            JSONObject urlItem = urls.getJSONObject(j);
                            String name = urlItem.optString("name", "");
                            String rawUrl = urlItem.optString("url", "");
                            // 清洗加密串
                            String encrypted = cleanEncrypted(rawUrl);
                            episodes.add(name + "$" + parseParam + "|" + encrypted);
                        }
                        if (!episodes.isEmpty()) {
                            playFromList.add(sourceName);
                            playUrlList.add(String.join("#", episodes));
                        }
                    }
                }
            }

            JSONObject vod = new JSONObject();
            vod.put("vod_id", vodId);
            vod.put("vod_name", data.optString("vod_name", ""));
            vod.put("vod_pic", pic);
            vod.put("vod_content", data.optString("vod_content", ""));
            vod.put("vod_year", data.optString("vod_year", ""));
            vod.put("vod_actor", data.optString("vod_actor", ""));
            vod.put("vod_director", data.optString("vod_director", ""));
            vod.put("type_name", data.optString("vod_class", ""));
            vod.put("vod_remarks", data.optString("vod_remarks", ""));
            vod.put("vod_play_from", String.join("$$$", playFromList));
            vod.put("vod_play_url", String.join("$$$", playUrlList));

            JSONArray list = new JSONArray();
            list.put(vod);
            JSONObject ret = new JSONObject();
            ret.put("list", list);
            return ret.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return "{\"list\":[]}";
        }
    }

    private String cleanEncrypted(String raw) {
        if (raw == null) return "";
        String cleaned = raw;
        if (cleaned.startsWith("|")) cleaned = cleaned.substring(1);
        if (cleaned.endsWith("|")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        int idx = cleaned.indexOf('|');
        return idx != -1 ? cleaned.substring(0, idx) : cleaned;
    }

    // ================== 搜索 ==================

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("keyword", key);
            params.put("limit", "12");
            params.put("page", "1");
            JSONObject result = post("/v3/home/search", params);
            if (result == null || result.optInt("code") != 1) return "{\"list\":[]}";

            JSONObject data = result.optJSONObject("data");
            JSONArray list = data != null ? data.optJSONArray("list") : null;
            JSONArray videos = new JSONArray();
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.getJSONObject(i);
                    String pic = item.optString("vod_pic");
                    if (pic.isEmpty()) pic = item.optString("vod_pic_thumb", "");
                    JSONObject vod = new JSONObject();
                    vod.put("vod_id", item.optString("vod_id"));
                    vod.put("vod_name", item.optString("vod_name"));
                    vod.put("vod_pic", pic);
                    vod.put("vod_remarks", item.optString("vod_remarks", ""));
                    videos.put(vod);
                }
            }
            JSONObject ret = new JSONObject();
            ret.put("list", videos);
            ret.put("page", 1);
            return ret.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return "{\"list\":[]}";
        }
    }

    // ================== 播放解析 ==================

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            if (id == null) return "{\"parse\":0,\"url\":\"\"}";

            String parts;
            if (id.contains("$")) {
                parts = id.split("\\$", 2)[1];
            } else {
                parts = id;
            }

            String parseParam = "";
            String encrypted = "";
            String[] segments = parts.split("\\|");
            if (segments.length >= 2) {
                parseParam = segments[0];
                encrypted = segments[1];
            } else {
                return "{\"parse\":0,\"url\":\"" + id.replace("\"", "\\\"") + "\"}";
            }

            if (!parseParam.startsWith("http")) {
                return "{\"parse\":0,\"url\":\"\"}";
            }

            // 拼接请求地址
            String reqUrl;
            if (parseParam.contains("?url=") || parseParam.contains("&url=")) {
                reqUrl = parseParam + encrypted;
            } else {
                reqUrl = parseParam + "?url=" + encrypted;
            }

            // 附加签名
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            String sign = makeSign(timestamp);
            if (!reqUrl.contains("&sign=") && !reqUrl.contains("?sign=")) {
                if (reqUrl.contains("?")) {
                    reqUrl += "&sign=" + sign + "&timestamp=" + timestamp;
                } else {
                    reqUrl += "?sign=" + sign + "&timestamp=" + timestamp;
                }
            }

            Request request = new Request.Builder()
                    .url(reqUrl)
                    .header("User-Agent", UA)
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JSONObject json = new JSONObject(response.body().string());
                    String realUrl = json.optString("url");
                    if (realUrl != null && realUrl.startsWith("http")) {
                        JSONObject ret = new JSONObject();
                        ret.put("parse", 0);
                        ret.put("url", realUrl);
                        return ret.toString();
                    }
                }
            }
            return "{\"parse\":0,\"url\":\"\"}";
        } catch (Exception e) {
            SpiderDebug.log(e);
            return "{\"parse\":0,\"url\":\"\"}";
        }
    }
}
