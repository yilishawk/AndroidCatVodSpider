package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp; // 引入系统最稳的网络框架

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author 九州空间全修复版（电视防崩溃稳定版）
 * 站点: m.9zhoukj.com
 */
public class JP extends Spider {

    private static final String HOST = "https://m.9zhoukj.com";
    private static final String KEY = "cb808529bae6b6be45ecfab29a4889bc";
    private static final String DEVICE_ID = "7dbc13a7-7976-4d7b-89d2-c110d09d7410";
    private static final String UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36";

    // ================== 电视兼容：最稳字符串拼接工具 ==================
    private String safeJoin(String delimiter, List<String> list) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            sb.append(list.get(i));
            if (i < list.size() - 1) {
                sb.append(delimiter);
            }
        }
        return sb.toString();
    }

    // ================== 签名工具 ==================
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
        
        // 电视兼容：改用 StringBuilder 拼接 URL 参数
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) query.append("&");
            String k = keys.get(i);
            query.append(k).append("=").append(valid.get(k));
        }
        String t = String.valueOf(System.currentTimeMillis());
        String signStr = query.toString() + "&key=" + KEY + "&t=" + t;
        String md5hex = md5(signStr);
        String sign = sha1(md5hex);
        return t + "|" + sign;
    }

    private Map<String, String> buildHeaders(Map<String, String> params) throws Exception {
        String[] ts = generateSign(params).split("\\|");
        String t = ts[0];
        String sign = ts[1];
        
        // 电视兼容：直接用最常规的 Map 传递请求头
        Map<String, String> headers = new HashMap<>();
        headers.put("Host", "m.9zhoukj.com");
        headers.put("client-type", "3");
        headers.put("deviceId", DEVICE_ID);
        headers.put("sign", sign);
        headers.put("t", t);
        headers.put("User-Agent", UA);
        headers.put("Referer", HOST + "/");
        return headers;
    }

    // 通用请求方法：全面改用系统内置 OkHttp 工具类
    private String fetch(String path, Map<String, String> params) throws Exception {
        // 电视兼容：手工安全拼接带有 Query 参数的 URL
        StringBuilder urlBuilder = new StringBuilder(HOST).append(path);
        if (params != null && !params.isEmpty()) {
            urlBuilder.append("?");
            List<String> keys = new ArrayList<>(params.keySet());
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i);
                urlBuilder.append(key).append("=").append(URLEncoder.encode(params.get(key), "UTF-8"));
                if (i < keys.size() - 1) {
                    urlBuilder.append("&");
                }
            }
        }
        
        Map<String, String> reqHeaders = buildHeaders(params != null ? params : new HashMap<>());
        
        // 关键点：直接调用系统封装，自带低版本证书和防崩溃处理
        return OkHttp.string(urlBuilder.toString(), reqHeaders);
    }

    // ================== 首页 ==================
    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = new JSONArray();

            String[][] classArr = {
                    {"2_14", "国产剧"},
                    {"2_15", "欧美剧"},
                    {"2_62", "日韩剧"},
                    {"2_16", "港台剧"},
                    {"1_", "电影"},
                    {"3_", "综艺"}
            };
            for (String[] c : classArr) {
                JSONObject cls = new JSONObject();
                cls.put("type_id", c[0]);
                cls.put("type_name", c[1]);
                classes.put(cls);
            }
            result.put("class", classes);

            if (filter) {
                JSONArray years = new JSONArray();
                years.put(createOption("全部", ""));
                for (int y = 2026; y >= 2010; y--)
                    years.put(createOption(String.valueOf(y), String.valueOf(y)));

                JSONArray sorts = new JSONArray();
                sorts.put(createOption("最新", "1"));
                sorts.put(createOption("最热", "2"));

                JSONArray tvFilter = new JSONArray();
                tvFilter.put(createFilter("v_class", "剧情", new String[][]{
                        {"全部", ""}, {"古装", "古装"}, {"战争", "战争"}, {"喜剧", "喜剧"},
                        {"家庭", "家庭"}, {"犯罪", "犯罪"}, {"动作", "动作"}, {"奇幻", "奇幻"},
                        {"剧情", "剧情"}, {"历史", "历史"}, {"短片", "短片"}
                }));
                tvFilter.put(createFilter("area", "地区", new String[][]{
                        {"全部", ""}, {"中国大陆", "中国大陆"}, {"中国香港", "中国香港"}, {"美国", "美国"}
                }));
                tvFilter.put(createFilter("year", "年代", years));
                tvFilter.put(createFilter("sort", "排序", sorts));

                JSONArray movFilter = new JSONArray();
                movFilter.put(createFilter("type", "类型", new String[][]{
                        {"全部", ""}, {"喜剧", "22"}, {"动作", "23"}, {"科幻", "30"},
                        {"爱情", "26"}, {"悬疑", "27"}, {"奇幻", "87"}, {"剧情", "37"},
                        {"恐怖", "36"}, {"犯罪", "35"}, {"动画", "33"}, {"惊悚", "34"},
                        {"战争", "25"}, {"冒险", "31"}, {"灾难", "81"}
                }));
                movFilter.put(createFilter("v_class", "剧情", new String[][]{
                        {"全部", ""}, {"爱情", "爱情"}, {"动作", "动作"}, {"科幻", "科幻"}, {"恐怖", "恐怖"}
                }));
                movFilter.put(createFilter("area", "地区", new String[][]{
                        {"全部", ""}, {"中国大陆", "中国大陆"}, {"中国香港", "中国香港"},
                        {"中国台湾", "中国台湾"}, {"美国", "美国"}, {"日本", "日本"},
                        {"韩国", "韩国"}, {"印度", "印度"}, {"泰国", "泰国"},
                        {"英国", "英国"}, {"法国", "法国"}
                }));
                movFilter.put(createFilter("year", "年代", years));
                movFilter.put(createFilter("sort", "排序", sorts));

                JSONArray zyFilter = new JSONArray();
                zyFilter.put(createFilter("type", "类型", new String[][]{
                        {"全部", ""}, {"国产综艺", "69"}, {"港台综艺", "70"}, {"日韩综艺", "72"}
                }));
                zyFilter.put(createFilter("v_class", "剧情", new String[][]{
                        {"全部", ""}, {"真人秀", "真人秀"}, {"音乐", "音乐"}, {"脱口秀", "脱口秀"}
                }));
                zyFilter.put(createFilter("year", "年代", years));

                JSONObject filters = new JSONObject();
                filters.put("2_14", tvFilter);
                filters.put("2_15", tvFilter);
                filters.put("2_62", tvFilter);
                filters.put("2_16", tvFilter);
                filters.put("1_", movFilter);
                filters.put("3_", zyFilter);
                result.put("filters", filters);
            }
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
        }
        return "";
    }

    private JSONObject createOption(String n, String v) throws Exception {
        JSONObject o = new JSONObject();
        o.put("n", n);
        o.put("v", v);
        return o;
    }

    private JSONObject createFilter(String key, String name, JSONArray value) throws Exception {
        JSONObject f = new JSONObject();
        f.put("key", key);
        f.put("name", name);
        f.put("value", value);
        return f;
    }

    private JSONObject createFilter(String key, String name, String[][] options) throws Exception {
        JSONArray arr = new JSONArray();
        for (String[] opt : options) arr.put(createOption(opt[0], opt[1]));
        return createFilter(key, name, arr);
    }

    // ================== 分类列表 ==================
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            String[] parts = tid.split("_");
            String type1 = parts[0];
            String subType = parts.length > 1 ? parts[1] : "";

            Map<String, String> params = new HashMap<>();
            params.put("type1", type1);
            params.put("pageNum", pg);
            params.put("pageSize", "30");
            params.put("sort", extend.getOrDefault("sort", "1"));
            params.put("sortBy", "1");

            String finalType = extend.getOrDefault("type", subType);
            if (finalType != null && !finalType.isEmpty()) {
                params.put("type", finalType);
            }

            if (extend.containsKey("area")) params.put("area", extend.get("area"));
            if (extend.containsKey("v_class")) params.put("v_class", extend.get("v_class"));
            if (extend.containsKey("year")) params.put("year", extend.get("year"));

            String json = fetch("/api/mw-movie/anonymous/video/list", params);
            if (json == null || json.isEmpty()) return "{\"list\":[], \"page\":" + pg + "}";

            JSONObject data = new JSONObject(json).optJSONObject("data");
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
            JSONObject result = new JSONObject();
            result.put("list", videos);
            result.put("page", Integer.parseInt(pg));
            result.put("pagecount", 9999);
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
        }
        return "{\"list\":[], \"page\":" + pg + "}";
    }

    // ================== 详情 ==================
    @Override
    public String detailContent(List<String> ids) {
        try {
            String vodId = ids.get(0);
            Map<String, String> params = new HashMap<>();
            params.put("id", vodId);
            String json = fetch("/api/mw-movie/anonymous/video/detail", params);
            if (json == null || json.isEmpty()) return "{\"list\":[]}";

            JSONObject data = new JSONObject(json).optJSONObject("data");
            if (data == null) return "{\"list\":[]}";

            JSONObject vod = new JSONObject();
            vod.put("vod_id", data.optString("vodId", vodId));
            vod.put("vod_name", data.optString("vodName", ""));
            vod.put("vod_pic", data.optString("vodPic", ""));
            vod.put("type_name", "");
            vod.put("vod_year", "");
            vod.put("vod_area", "");
            vod.put("vod_director", "");
            vod.put("vod_actor", "");
            vod.put("vod_content", data.optString("vodContent", ""));
            vod.put("vod_play_from", "九州空间");

            JSONArray episodes = data.optJSONArray("episodeList");
            List<String> epList = new ArrayList<>();
            if (episodes != null && episodes.length() > 0) {
                for (int i = 0; i < episodes.length(); i++) {
                    JSONObject ep = episodes.getJSONObject(i);
                    String name = ep.optString("name", "正片");
                    String nid = ep.optString("nid", "0");
                    epList.add(name + "$" + vodId + "@@" + nid);
                }
            } else {
                epList.add("正片$" + vodId + "@@0");
            }
            // 电视兼容：改用安全拼接
            vod.put("vod_play_url", safeJoin("#", epList));

            JSONArray list = new JSONArray();
            list.put(vod);
            JSONObject result = new JSONObject();
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
        }
        return "{\"list\":[]}";
    }

    // ================== 搜索 ==================
    @Override
    public String searchContent(String key, boolean quick) {
        try {
            return searchContentWithPage(key, "1");
        } catch (Exception e) {
            SpiderDebug.log(e);
        }
        return "{\"list\":[]}";
    }

    private String searchContentWithPage(String key, String pg) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("keyword", key);
        params.put("pageNum", pg);
        params.put("pageSize", "30");
        params.put("sourceCode", "1");

        String json = fetch("/api/mw-movie/anonymous/video/searchByWord", params);
        if (json == null || json.isEmpty()) return "{\"list\":[]}";

        JSONObject data = new JSONObject(json).optJSONObject("data");
        JSONObject resultObj = data != null ? data.optJSONObject("result") : null;
        JSONArray rawList = resultObj != null ? resultObj.optJSONArray("list") : null;
        JSONArray filtered = new JSONArray();
        if (rawList != null) {
            for (int i = 0; i < rawList.length(); i++) {
                JSONObject item = rawList.getJSONObject(i);
                if (item.optString("vodName", "").contains(key)) {
                    JSONObject vod = new JSONObject();
                    vod.put("vod_id", item.optString("vodId"));
                    vod.put("vod_name", item.optString("vodName"));
                    vod.put("vod_pic", item.optString("vodPic"));
                    vod.put("vod_remarks", item.optString("vodRemarks", ""));
                    filtered.put(vod);
                }
            }
        }
        JSONObject result = new JSONObject();
        result.put("list", filtered);
        result.put("page", Integer.parseInt(pg));
        return result.toString();
    }

    // ================== 播放解析 ==================
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String[] parts = id.split("@@");
            if (parts.length < 2) return fallbackParse();
            String vodId = parts[0];
            String nid = parts[1];

            Map<String, String> params = new HashMap<>();
            params.put("clientType", "3");
            params.put("id", vodId);
            params.put("nid", nid);

            String json = fetch("/api/mw-movie/anonymous/v2/video/episode/url", params);
            if (json == null || json.isEmpty()) return fallbackParse();

            JSONObject data = new JSONObject(json).optJSONObject("data");
            if (data == null) return fallbackParse();
            JSONArray list = data.optJSONArray("list");
            if (list == null || list.length() == 0) return fallbackParse();

            String url = null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject v = list.getJSONObject(i);
                if (v.optInt("resolution") == 1080) {
                    url = v.optString("url");
                    break;
                }
            }
            if (url == null) url = list.getJSONObject(0).optString("url");
            if (url == null || url.isEmpty()) return fallbackParse();

            JSONObject result = new JSONObject();
            result.put("parse", 0);
            result.put("url", url);
            JSONObject header = new JSONObject();
            header.put("User-Agent", UA);
            header.put("Referer", HOST);
            header.put("Origin", HOST);
            result.put("header", header);
            return result.toString();

        } catch (Exception e) {
            SpiderDebug.log(e);
        }
        return fallbackParse();
    }

    private String fallbackParse() {
        try {
            JSONObject result = new JSONObject();
            result.put("parse", 1);
            result.put("url", "");
            return result.toString();
        } catch (annotation ignored) {
            return "{\"parse\":1,\"url\":\"\"}";
        }
    }
}
