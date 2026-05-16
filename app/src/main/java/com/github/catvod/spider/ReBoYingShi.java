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
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * ReBoYingShi 爬虫 (原 AppRJ)
 * 站点: http://v.rbotv.cn
 */
public class ReBoYingShi extends Spider {

    private String baseUrl = "http://v.rbotv.cn";
    private static final String SECRET = "7gp0bnd2sr85ydii2j32pcypscoc4w6c7g5spl";
    private static final String UA = "okhttp-okgo/jeasonlzy";

    private OkHttpClient client;

    // ================== 初始化 ==================

    @Override
    public void init(Context context, String extend) {
        SpiderDebug.log("[AppRJ] init called");

        client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();

        if (extend != null && !extend.isEmpty()) {
            try {
                JSONObject cfg = new JSONObject(extend);
                if (cfg.has("url")) {
                    String u = cfg.optString("url");
                    if (u.endsWith("/")) {
                        u = u.substring(0, u.length() - 1);
                    }
                    baseUrl = u;
                    SpiderDebug.log("[AppRJ] baseUrl updated to: " + baseUrl);
                }
            } catch (Exception e) {
                SpiderDebug.log("[AppRJ] Failed to parse extend: " + e.getMessage());
            }
        }
    }

    // 移除 getName 和 isVideoCast 的 @Override，如果父类没有这些方法
    
    public String getName() {
        return "AppRJ";
    }

    public boolean isVideoCast() {
        return true;
    }

    // ================== 工具方法 ==================

    private String md5(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            SpiderDebug.log("[AppRJ] MD5 error: " + e.getMessage());
            return "";
        }
    }

    private String makeSign(String timestamp) {
        return md5(SECRET + timestamp);
    }

    /**
     * 通用 POST 请求 - 与 Python 版本保持一致
     */
    private JSONObject post(String path, Map<String, String> params) {
        if (client == null) {
            SpiderDebug.log("[AppRJ] client not initialized");
            return null;
        }

        String url = baseUrl + path;
        SpiderDebug.log("[AppRJ] POST: " + url);

        FormBody.Builder formBuilder = new FormBody.Builder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                formBuilder.add(e.getKey(), e.getValue());
            }
        }

        Request request = new Request.Builder()
                .url(url)
                .post(formBuilder.build())
                .header("User-Agent", UA)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String body = response.body().string();
                SpiderDebug.log("[AppRJ] Response: " + body);
                JSONObject result = new JSONObject(body);
                int code = result.optInt("code");
                if (code != 1) {
                    SpiderDebug.log("[AppRJ] API error code: " + code);
                    return null;
                }
                return result;
            } else {
                SpiderDebug.log("[AppRJ] HTTP error: " + response.code());
                return null;
            }
        } catch (Exception e) {
            SpiderDebug.log("[AppRJ] Request failed: " + e.getMessage());
            return null;
        }
    }

    // ================== 首页 ==================

    @Override
    public String homeContent(boolean filter) {
        try {
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            String sign = makeSign(timestamp);
            
            Map<String, String> params = new HashMap<>();
            params.put("timestamp", timestamp);
            params.put("sign", sign);
            
            JSONObject result = post("/v3/type/top_type", params);
            if (result == null) {
                return "{\"class\":[], \"filters\":{}}";
            }

            JSONObject data = result.optJSONObject("data");
            if (data == null) {
                return "{\"class\":[], \"filters\":{}}";
            }

            JSONArray classArr = new JSONArray();
            JSONObject filtersObj = new JSONObject();

            JSONArray list = data.optJSONArray("list");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.getJSONObject(i);
                    String typeId = String.valueOf(item.optInt("type_id"));
                    String typeName = item.optString("type_name");
                    
                    JSONObject cls = new JSONObject();
                    cls.put("type_id", typeId);
                    cls.put("type_name", typeName);
                    classArr.put(cls);

                    JSONArray filterItems = new JSONArray();

                    // extend 筛选 (类型)
                    JSONArray extendList = item.optJSONArray("extend");
                    if (extendList != null && extendList.length() > 1) {
                        JSONArray options = new JSONArray();
                        for (int j = 0; j < extendList.length(); j++) {
                            String val = extendList.optString(j);
                            if (val != null && !val.trim().isEmpty()) {
                                JSONObject opt = new JSONObject();
                                opt.put("n", val.trim());
                                opt.put("v", val.trim());
                                options.put(opt);
                            }
                        }
                        if (options.length() > 0) {
                            JSONObject filterItem = new JSONObject();
                            filterItem.put("key", "class");
                            filterItem.put("name", "类型");
                            filterItem.put("value", options);
                            filterItems.put(filterItem);
                        }
                    }

                    // area 筛选
                    JSONArray areaList = item.optJSONArray("area");
                    if (areaList != null && areaList.length() > 1) {
                        JSONArray options = new JSONArray();
                        for (int j = 0; j < areaList.length(); j++) {
                            String val = areaList.optString(j);
                            if (val != null && !val.trim().isEmpty()) {
                                JSONObject opt = new JSONObject();
                                opt.put("n", val.trim());
                                opt.put("v", val.trim());
                                options.put(opt);
                            }
                        }
                        if (options.length() > 0) {
                            JSONObject filterItem = new JSONObject();
                            filterItem.put("key", "area");
                            filterItem.put("name", "地区");
                            filterItem.put("value", options);
                            filterItems.put(filterItem);
                        }
                    }

                    // year 筛选
                    JSONArray yearList = item.optJSONArray("year");
                    if (yearList != null && yearList.length() > 1) {
                        JSONArray options = new JSONArray();
                        for (int j = 0; j < yearList.length(); j++) {
                            String val = yearList.optString(j);
                            if (val != null && !val.trim().isEmpty()) {
                                JSONObject opt = new JSONObject();
                                opt.put("n", val.trim());
                                opt.put("v", val.trim());
                                options.put(opt);
                            }
                        }
                        if (options.length() > 0) {
                            JSONObject filterItem = new JSONObject();
                            filterItem.put("key", "year");
                            filterItem.put("name", "年份");
                            filterItem.put("value", options);
                            filterItems.put(filterItem);
                        }
                    }

                    // lang 筛选
                    JSONArray langList = item.optJSONArray("lang");
                    if (langList != null && langList.length() > 1) {
                        JSONArray options = new JSONArray();
                        for (int j = 0; j < langList.length(); j++) {
                            String val = langList.optString(j);
                            if (val != null && !val.trim().isEmpty()) {
                                JSONObject opt = new JSONObject();
                                opt.put("n", val.trim());
                                opt.put("v", val.trim());
                                options.put(opt);
                            }
                        }
                        if (options.length() > 0) {
                            JSONObject filterItem = new JSONObject();
                            filterItem.put("key", "lang");
                            filterItem.put("name", "语言");
                            filterItem.put("value", options);
                            filterItems.put(filterItem);
                        }
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
            SpiderDebug.log("[AppRJ] homeContent error: " + e.getMessage());
            return "{\"class\":[], \"filters\":{}}";
        }
    }

    // ================== 分类列表 ==================

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            String sign = makeSign(timestamp);
            
            Map<String, String> params = new HashMap<>();
            params.put("timestamp", timestamp);
            params.put("sign", sign);
            params.put("type_id", tid);
            params.put("page", pg);
            params.put("limit", "12");

            if (extend != null) {
                String[] keys = {"area", "class", "lang", "year"};
                for (String key : keys) {
                    String value = extend.get(key);
                    if (value != null && !value.isEmpty()) {
                        params.put(key, value);
                    }
                }
            }

            JSONObject result = post("/v3/home/type_search", params);
            if (result == null) {
                return "{\"list\":[], \"page\":" + pg + "}";
            }

            JSONObject data = result.optJSONObject("data");
            JSONArray list = (data != null) ? data.optJSONArray("list") : null;
            
            JSONArray videos = new JSONArray();
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.getJSONObject(i);
                    String pic = item.optString("vod_pic");
                    if (pic == null || pic.isEmpty()) {
                        pic = item.optString("vod_pic_thumb", "");
                    }
                    
                    JSONObject vod = new JSONObject();
                    vod.put("vod_id", String.valueOf(item.optInt("vod_id")));
                    vod.put("vod_name", item.optString("vod_name"));
                    vod.put("vod_pic", pic);
                    vod.put("vod_remarks", item.optString("vod_remarks", ""));
                    videos.put(vod);
                }
            }
            
            JSONObject ret = new JSONObject();
            ret.put("list", videos);
            ret.put("page", Integer.parseInt(pg));
            return ret.toString();
            
        } catch (Exception e) {
            SpiderDebug.log("[AppRJ] categoryContent error: " + e.getMessage());
            return "{\"list\":[], \"page\":" + pg + "}";
        }
    }

    // ================== 详情 ==================

    @Override
    public String detailContent(List<String> ids) {
        try {
            if (ids == null || ids.isEmpty()) {
                return "{\"list\":[]}";
            }
            
            String vodId = ids.get(0);
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            String sign = makeSign(timestamp);
            
            Map<String, String> params = new HashMap<>();
            params.put("timestamp", timestamp);
            params.put("sign", sign);
            params.put("vod_id", vodId);
            
            JSONObject result = post("/v3/home/vod_details", params);
            if (result == null) {
                return "{\"list\":[]}";
            }

            JSONObject data = result.optJSONObject("data");
            if (data == null) {
                return "{\"list\":[]}";
            }

            String pic = data.optString("vod_pic");
            if (pic == null || pic.isEmpty()) {
                pic = data.optString("vod_pic_thumb", "");
            }

            List<String> playFromList = new ArrayList<>();
            List<String> playUrlList = new ArrayList<>();

            JSONArray playSources = data.optJSONArray("vod_play_list");
            if (playSources != null) {
                for (int i = 0; i < playSources.length(); i++) {
                    JSONObject source = playSources.getJSONObject(i);
                    String sourceName = source.optString("name");
                    if (sourceName == null || sourceName.isEmpty()) {
                        sourceName = source.optString("title", "未知源");
                    }

                    // 构建 parse_param - 用 @ 连接
                    JSONArray parseUrls = source.optJSONArray("parse_urls");
                    String parseParam = "";
                    if (parseUrls != null && parseUrls.length() > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (int j = 0; j < parseUrls.length(); j++) {
                            if (j > 0) sb.append("@");
                            String url = parseUrls.optString(j);
                            if (url != null) {
                                sb.append(url);
                            }
                        }
                        parseParam = sb.toString();
                    }

                    // 处理播放列表
                    JSONArray urls = source.optJSONArray("urls");
                    if (urls != null && urls.length() > 0) {
                        List<String> episodes = new ArrayList<>();
                        for (int j = 0; j < urls.length(); j++) {
                            JSONObject urlItem = urls.getJSONObject(j);
                            String name = urlItem.optString("name", "");
                            String rawUrl = urlItem.optString("url", "");
                            
                            // 清洗：去掉首尾竖线，取竖线前的内容作为加密串
                            String cleaned = rawUrl;
                            if (cleaned != null) {
                                while (cleaned.startsWith("|")) {
                                    cleaned = cleaned.substring(1);
                                }
                                while (cleaned.endsWith("|")) {
                                    cleaned = cleaned.substring(0, cleaned.length() - 1);
                                }
                                int idx = cleaned.indexOf('|');
                                String encrypted = (idx != -1) ? cleaned.substring(0, idx) : cleaned;
                                
                                String epStr = name + "$" + parseParam + "|" + encrypted;
                                episodes.add(epStr);
                            }
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
            vod.put("vod_class", data.optString("vod_class", ""));
            vod.put("vod_remarks", data.optString("vod_remarks", ""));
            vod.put("vod_play_from", String.join("$$$", playFromList));
            vod.put("vod_play_url", String.join("$$$", playUrlList));

            JSONArray list = new JSONArray();
            list.put(vod);
            
            JSONObject ret = new JSONObject();
            ret.put("list", list);
            return ret.toString();
            
        } catch (Exception e) {
            SpiderDebug.log("[AppRJ] detailContent error: " + e.getMessage());
            e.printStackTrace();
            return "{\"list\":[]}";
        }
    }

    // ================== 搜索 ==================

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            if (key == null || key.trim().isEmpty()) {
                return "{\"list\":[]}";
            }
            
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            String sign = makeSign(timestamp);
            
            Map<String, String> params = new HashMap<>();
            params.put("timestamp", timestamp);
            params.put("sign", sign);
            params.put("keyword", key.trim());
            params.put("limit", "12");
            params.put("page", "1");
            
            JSONObject result = post("/v3/home/search", params);
            if (result == null) {
                return "{\"list\":[]}";
            }

            JSONObject data = result.optJSONObject("data");
            JSONArray list = (data != null) ? data.optJSONArray("list") : null;
            
            JSONArray videos = new JSONArray();
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.getJSONObject(i);
                    String pic = item.optString("vod_pic");
                    if (pic == null || pic.isEmpty()) {
                        pic = item.optString("vod_pic_thumb", "");
                    }
                    
                    JSONObject vod = new JSONObject();
                    vod.put("vod_id", String.valueOf(item.optInt("vod_id")));
                    vod.put("vod_name", item.optString("vod_name"));
                    vod.put("vod_pic", pic);
                    vod.put("vod_remarks", item.optString("vod_remarks", ""));
                    videos.put(vod);
                }
            }
            
            JSONObject ret = new JSONObject();
            ret.put("list", videos);
            return ret.toString();
            
        } catch (Exception e) {
            SpiderDebug.log("[AppRJ] searchContent error: " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    // ================== 播放解析 ==================

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            if (id == null || id.isEmpty()) {
                return "{\"parse\":0,\"url\":\"\"}";
            }

            // 提取 $ 后的部分
            String parts = id;
            if (id.contains("$")) {
                String[] idParts = id.split("\\$", 2);
                if (idParts.length >= 2) {
                    parts = idParts[1];
                }
            }
            
            // 分离 parse_param 和 encrypted
            String[] segments = parts.split("\\|");
            if (segments.length >= 2) {
                String parseParam = segments[0];
                String encrypted = segments[1];
                
                if (parseParam != null && parseParam.startsWith("http")) {
                    String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
                    String sign = makeSign(timestamp);
                    
                    String reqUrl;
                    if (parseParam.contains("?url=") || parseParam.contains("&url=")) {
                        reqUrl = parseParam + encrypted;
                    } else {
                        reqUrl = parseParam + "?url=" + encrypted;
                    }
                    
                    if (!reqUrl.contains("&sign=") && !reqUrl.contains("?sign=")) {
                        reqUrl += "&sign=" + sign + "&timestamp=" + timestamp;
                    }
                    
                    SpiderDebug.log("[AppRJ] Player request: " + reqUrl);
                    
                    Request request = new Request.Builder()
                            .url(reqUrl)
                            .header("User-Agent", UA)
                            .get()
                            .build();
                            
                    try (Response response = client.newCall(request).execute()) {
                        if (response.isSuccessful() && response.body() != null) {
                            String body = response.body().string();
                            JSONObject json = new JSONObject(body);
                            String realUrl = json.optString("url");
                            
                            if (realUrl != null && realUrl.startsWith("http")) {
                                JSONObject ret = new JSONObject();
                                ret.put("parse", 0);
                                ret.put("url", realUrl);
                                return ret.toString();
                            }
                        }
                    }
                }
            } else if (segments.length == 1) {
                String url = segments[0];
                if (url.startsWith("http")) {
                    JSONObject ret = new JSONObject();
                    ret.put("parse", 0);
                    ret.put("url", url);
                    return ret.toString();
                }
            }
            
            return "{\"parse\":0,\"url\":\"\"}";
            
        } catch (Exception e) {
            SpiderDebug.log("[AppRJ] playerContent error: " + e.getMessage());
            return "{\"parse\":0,\"url\":\"\"}";
        }
    }
}
