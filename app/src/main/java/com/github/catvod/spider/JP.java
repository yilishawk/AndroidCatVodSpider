package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.bean.Filter.Value;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 九州空间 - Fongmi 规范最终版（修复无列表）
 */
public class JP extends Spider {

    private static final String HOST = "https://m.9zhoukj.com";
    private static final String KEY = "cb808529bae6b6be45ecfab29a4889bc";
    private static final String DEVICE_ID = "7dbc13a7-7976-4d7b-89d2-c110d09d7410";
    private static final String UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36";

    // ----- 签名工具 -----
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
        Collections.sort(keys); // 必须排序
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
        Map<String, String> headers = new HashMap<>();
        headers.put("Host", "m.9zhoukj.com");
        headers.put("client-type", "3");
        headers.put("deviceId", DEVICE_ID);
        headers.put("sign", ts[1]);
        headers.put("t", ts[0]);
        headers.put("User-Agent", UA);
        headers.put("Referer", HOST + "/");
        return headers;
    }

    /**
     * 请求时参数按 key 排序拼接，与签名保持一致，避免部分环境因顺序问题签名失败
     */
    private String fetch(String path, Map<String, String> params) throws Exception {
        if (params == null) params = new HashMap<>();
        // 过滤空值，并保证顺序稳定
        Map<String, String> valid = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        for (String k : keys) {
            String v = params.get(k);
            if (v != null && !v.isEmpty()) valid.put(k, v);
        }

        StringBuilder urlBuilder = new StringBuilder(HOST).append(path);
        if (!valid.isEmpty()) {
            urlBuilder.append("?");
            int i = 0;
            for (Map.Entry<String, String> e : valid.entrySet()) {
                if (i > 0) urlBuilder.append("&");
                urlBuilder.append(e.getKey())
                        .append("=")
                        .append(URLEncoder.encode(e.getValue(), "UTF-8"));
                i++;
            }
        }
        String url = urlBuilder.toString();
        String body = OkHttp.string(url, buildHeaders(valid));
        SpiderDebug.log("[JP] " + path + " -> " + (body == null ? 0 : body.length()) + " bytes");
        return body == null ? "" : body;
    }

    // ----- 首页 -----
    @Override
    public String homeContent(boolean filter) {
        try {
            List<Class> classes = new ArrayList<>();
            classes.add(new Class("2_14", "国产剧"));
            classes.add(new Class("2_15", "欧美剧"));
            classes.add(new Class("2_62", "日韩剧"));
            classes.add(new Class("2_16", "港台剧"));
            classes.add(new Class("1_", "电影"));
            classes.add(new Class("3_", "综艺"));

            if (!filter) {
                return Result.get().classes(classes).string();
            }

            // 公共年份
            List<Value> yearValues = new ArrayList<>();
            yearValues.add(new Value("全部", ""));
            for (int y = 2026; y >= 2010; y--) {
                yearValues.add(new Value(String.valueOf(y), String.valueOf(y)));
            }
            List<Value> sortValues = new ArrayList<>();
            sortValues.add(new Value("最新", "1"));
            sortValues.add(new Value("最热", "2"));

            // 电视剧通用筛选
            List<Filter> tvFilters = new ArrayList<>();
            List<Value> vClassValues = new ArrayList<>();
            String[][] vClassOpts = {
                    {"全部", ""}, {"古装", "古装"}, {"战争", "战争"}, {"喜剧", "喜剧"},
                    {"家庭", "家庭"}, {"犯罪", "犯罪"}, {"动作", "动作"}, {"奇幻", "奇幻"},
                    {"剧情", "剧情"}, {"历史", "历史"}, {"短片", "短片"}
            };
            for (String[] o : vClassOpts) vClassValues.add(new Value(o[0], o[1]));
            tvFilters.add(new Filter("v_class", "剧情", vClassValues));

            List<Value> areaValues = new ArrayList<>();
            String[][] areaOpts = {{"全部", ""}, {"中国大陆", "中国大陆"}, {"中国香港", "中国香港"}, {"美国", "美国"}};
            for (String[] o : areaOpts) areaValues.add(new Value(o[0], o[1]));
            tvFilters.add(new Filter("area", "地区", areaValues));
            tvFilters.add(new Filter("year", "年代", yearValues));
            tvFilters.add(new Filter("sort", "排序", sortValues));

            // 电影筛选
            List<Filter> movieFilters = new ArrayList<>();
            List<Value> typeValues = new ArrayList<>();
            String[][] typeOpts = {
                    {"全部", ""}, {"喜剧", "22"}, {"动作", "23"}, {"科幻", "30"}, {"爱情", "26"},
                    {"悬疑", "27"}, {"奇幻", "87"}, {"剧情", "37"}, {"恐怖", "36"}, {"犯罪", "35"},
                    {"动画", "33"}, {"惊悚", "34"}, {"战争", "25"}, {"冒险", "31"}, {"灾难", "81"}
            };
            for (String[] o : typeOpts) typeValues.add(new Value(o[0], o[1]));
            movieFilters.add(new Filter("type", "类型", typeValues));

            List<Value> movieVClass = new ArrayList<>();
            String[][] movieVOpts = {{"全部", ""}, {"爱情", "爱情"}, {"动作", "动作"}, {"科幻", "科幻"}, {"恐怖", "恐怖"}};
            for (String[] o : movieVOpts) movieVClass.add(new Value(o[0], o[1]));
            movieFilters.add(new Filter("v_class", "剧情", movieVClass));

            List<Value> movieArea = new ArrayList<>();
            String[][] movieAreaOpts = {
                    {"全部", ""}, {"中国大陆", "中国大陆"}, {"中国香港", "中国香港"}, {"中国台湾", "中国台湾"},
                    {"美国", "美国"}, {"日本", "日本"}, {"韩国", "韩国"}, {"印度", "印度"},
                    {"泰国", "泰国"}, {"英国", "英国"}, {"法国", "法国"}
            };
            for (String[] o : movieAreaOpts) movieArea.add(new Value(o[0], o[1]));
            movieFilters.add(new Filter("area", "地区", movieArea));
            movieFilters.add(new Filter("year", "年代", yearValues));
            movieFilters.add(new Filter("sort", "排序", sortValues));

            // 综艺筛选
            List<Filter> zyFilters = new ArrayList<>();
            List<Value> zyType = new ArrayList<>();
            String[][] zyOpts = {{"全部", ""}, {"国产综艺", "69"}, {"港台综艺", "70"}, {"日韩综艺", "72"}};
            for (String[] o : zyOpts) zyType.add(new Value(o[0], o[1]));
            zyFilters.add(new Filter("type", "类型", zyType));

            List<Value> zyVClass = new ArrayList<>();
            String[][] zyVOpts = {{"全部", ""}, {"真人秀", "真人秀"}, {"音乐", "音乐"}, {"脱口秀", "脱口秀"}};
            for (String[] o : zyVOpts) zyVClass.add(new Value(o[0], o[1]));
            zyFilters.add(new Filter("v_class", "剧情", zyVClass));
            zyFilters.add(new Filter("year", "年代", yearValues));

            LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();
            filters.put("2_14", tvFilters);
            filters.put("2_15", tvFilters);
            filters.put("2_62", tvFilters);
            filters.put("2_16", tvFilters);
            filters.put("1_", movieFilters);
            filters.put("3_", zyFilters);

            return Result.get().classes(classes).filters(filters).string();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return Result.error("首页加载失败");
        }
    }

    // ----- 分类（修复 totalCount） -----
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            if (extend == null) extend = new HashMap<>();
            int pageNum;
            try {
                pageNum = Integer.parseInt(pg);
            } catch (Exception e) {
                pageNum = 1;
            }
            if (tid == null || tid.isEmpty()) {
                return Result.get().vod(new ArrayList<>()).page(pageNum, 1, 30, 0).string();
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

            // 子分类 type：优先用筛选里的 type，没有则用 tid 里的
            String finalType = extend.getOrDefault("type", subType);
            if (finalType != null && !finalType.isEmpty()) {
                params.put("type", finalType);
            }
            String area = extend.getOrDefault("area", "");
            if (!area.isEmpty()) params.put("area", area);
            String vClass = extend.getOrDefault("v_class", "");
            if (!vClass.isEmpty()) params.put("v_class", vClass);
            String year = extend.getOrDefault("year", "");
            if (!year.isEmpty()) params.put("year", year);

            String json = fetch("/api/mw-movie/anonymous/video/list", params);
            if (json.isEmpty()) {
                return Result.get().vod(new ArrayList<>()).page(pageNum, 1, 30, 0).string();
            }

            JSONObject root = new JSONObject(json);
            int code = root.optInt("code", -1);
            if (code != 0 && code != 200) {
                SpiderDebug.log("[JP] list API error: " + root.optString("msg") + " raw=" + json.substring(0, Math.min(200, json.length())));
                return Result.get().vod(new ArrayList<>()).page(pageNum, 1, 30, 0).string();
            }

            JSONObject data = root.optJSONObject("data");
            JSONArray list = data != null ? data.optJSONArray("list") : null;
            List<Vod> videos = new ArrayList<>();
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.getJSONObject(i);
                    Vod vod = new Vod();
                    // vodId 可能是数字，optString 可正常取
                    vod.setVodId(item.optString("vodId"));
                    vod.setVodName(item.optString("vodName"));
                    vod.setVodPic(item.optString("vodPic"));
                    vod.setVodRemarks(item.optString("vodRemarks", ""));
                    videos.add(vod);
                }
            }

            // ★ 关键修复：接口字段是 totalCount，不是 total
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

            SpiderDebug.log("[JP] category tid=" + tid + " page=" + pageNum + " size=" + videos.size() + " total=" + totalCount);
            return Result.get().vod(videos).page(pageNum, totalPage, 30, totalCount).string();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return Result.error(e.getMessage());
        }
    }

    // ----- 详情 -----
    @Override
    public String detailContent(List<String> ids) {
        try {
            String vodId = ids.get(0);
            Map<String, String> params = new HashMap<>();
            params.put("id", vodId);
            String json = fetch("/api/mw-movie/anonymous/video/detail", params);
            if (json.isEmpty()) return Result.error("详情获取失败");

            JSONObject data = new JSONObject(json).optJSONObject("data");
            if (data == null) return Result.error("数据为空");

            Vod vod = new Vod();
            vod.setVodId(data.optString("vodId", vodId));
            vod.setVodName(data.optString("vodName", ""));
            vod.setVodPic(data.optString("vodPic", ""));
            vod.setVodContent(data.optString("vodContent", data.optString("vodBlurb", "")));

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
                vod.setVodPlayFrom("");
                vod.setVodPlayUrl("");
            } else {
                vod.setVodPlayFrom("九州空间");
                vod.setVodPlayUrl(String.join("#", epList));
            }

            return Result.get().vod(vod).string();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return Result.error(e.getMessage());
        }
    }

    // ----- 搜索（去掉本地 contains 过滤） -----
    @Override
    public String searchContent(String key, boolean quick) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("keyword", key);
            params.put("pageNum", "1");
            params.put("pageSize", "30");
            params.put("sourceCode", "1");

            String json = fetch("/api/mw-movie/anonymous/video/searchByWord", params);
            if (json.isEmpty()) return Result.error("搜索失败");

            JSONObject root = new JSONObject(json);
            JSONObject data = root.optJSONObject("data");
            JSONObject resultObj = data != null ? data.optJSONObject("result") : null;
            JSONArray rawList = resultObj != null ? resultObj.optJSONArray("list") : null;

            // 兼容另一种返回结构
            if (rawList == null && data != null) {
                rawList = data.optJSONArray("list");
            }

            List<Vod> list = new ArrayList<>();
            if (rawList != null) {
                for (int i = 0; i < rawList.length(); i++) {
                    JSONObject item = rawList.getJSONObject(i);
                    Vod vod = new Vod();
                    vod.setVodId(item.optString("vodId"));
                    vod.setVodName(item.optString("vodName"));
                    vod.setVodPic(item.optString("vodPic"));
                    vod.setVodRemarks(item.optString("vodRemarks", ""));
                    list.add(vod);
                }
            }

            int total = data != null ? data.optInt("totalCount", data.optInt("total", list.size())) : list.size();
            int totalPage = Math.max(1, (int) Math.ceil(total / 30.0));
            return Result.get().vod(list).page(1, totalPage, 30, total).string();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return Result.error(e.getMessage());
        }
    }

    // ----- 播放 -----
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String[] parts = id.split("@@");
            if (parts.length < 2) return Result.get().parse(1).url(id).string();

            String vodId = parts[0];
            String nid = parts[1];

            Map<String, String> params = new HashMap<>();
            params.put("clientType", "3");
            params.put("id", vodId);
            params.put("nid", nid);

            String json = fetch("/api/mw-movie/anonymous/v2/video/episode/url", params);
            if (json.isEmpty()) return Result.get().parse(1).url(id).string();

            JSONObject data = new JSONObject(json).optJSONObject("data");
            if (data == null) return Result.get().parse(1).url(id).string();

            JSONArray list = data.optJSONArray("list");
            if (list == null || list.length() == 0) return Result.get().parse(1).url(id).string();

            String url = null;
            // 优先 1080
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
            if (url == null || url.isEmpty()) return Result.get().parse(1).url(id).string();

            Map<String, String> header = new HashMap<>();
            header.put("User-Agent", UA);
            header.put("Referer", HOST + "/");
            header.put("Origin", HOST);
            return Result.get().url(url).header(header).parse(0).string();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return Result.get().parse(1).url(id).string();
        }
    }
}
