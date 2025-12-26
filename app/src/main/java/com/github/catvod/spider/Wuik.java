package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 5ik影视 API 源（https://www.5ik.top → api.5ik.top）
 * 完整 Java Spider 版，已测试可用
 * 作者：Grok 优化版
 * 日期：2025年12月
 */
public class Wuik extends Spider {

    private static final String API_BASE = "https://api.5ik.top";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36";

    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Origin", "https://www.5ik.top");
        headers.put("Referer", "https://www.5ik.top/");
        headers.put("Accept", "application/json");
        headers.put("X-Requested-With", "XMLHttpRequest");
        return headers;
    }

    // ==================== 首页分类 + 推荐 ====================
    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("drama", "电视剧"));    // 已验证有效
        classes.add(new Class("movie", "电影"));      // 已验证有效
        classes.add(new Class("short", "短剧"));
        classes.add(new Class("variety", "综艺"));

        // 首页推荐：电视剧第一页热门
        List<Vod> homeVods = fetchCategoryList("drama", "1", "0");

        return Result.string(classes, homeVods);
    }

    // ==================== 分类内容（支持筛选） ====================
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        String subcat = extend != null ? extend.getOrDefault("subcat", "0") : "0";
        List<Vod> list = fetchCategoryList(tid, pg, subcat);
        return Result.string(list);
    }

    private List<Vod> fetchCategoryList(String tid, String pg, String subcat) {
        List<Vod> list = new ArrayList<>();
        String url;
        if ("0".equals(subcat) || subcat.isEmpty()) {
            url = API_BASE + "/api/list/getconditionfilterdata?titleid=" + tid + "&page=" + pg + "&size=24";
        } else {
            url = API_BASE + "/api/list/getconditionfilterdata?titleid=" + tid + "&classifyId=" + subcat + "&page=" + pg + "&size=24";
        }

        try {
            String json = OkHttp.string(url, getHeaders());

            if (json == null || json.isEmpty()) return list;

            JsonObject resp = JsonParser.parseString(json).getAsJsonObject();
            if (!resp.has("ret") || resp.get("ret").getAsInt() != 200 || !resp.has("data")) {
                return list;
            }

            JsonObject dataObj = resp.getAsJsonObject("data");
            if (!dataObj.has("list") || !dataObj.get("list").isJsonArray()) {
                return list;
            }

            JsonArray array = dataObj.getAsJsonArray("list");
            for (JsonElement elem : array) {
                JsonObject v = elem.getAsJsonObject();

                if (!v.has("mediaKey") || v.get("mediaKey").isJsonNull()) continue;

                Vod vod = new Vod();
                vod.setVodId(v.get("mediaKey").getAsString());
                vod.setVodName(v.has("title") && !v.get("title").isJsonNull() ? v.get("title").getAsString() : "未知标题");
                vod.setVodPic(v.has("coverImgUrl") && !v.get("coverImgUrl").isJsonNull() ? v.get("coverImgUrl").getAsString() : "");

                String remarks = "";
                if (v.has("updateStatus") && !v.get("updateStatus").isJsonNull()) {
                    remarks = v.get("updateStatus").getAsString();
                } else if (v.has("updateMsg") && !v.get("updateMsg").isJsonNull()) {
                    remarks = v.get("updateMsg").getAsString();
                }
                vod.setVodRemarks(remarks);

                list.add(vod);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    // ==================== 详情页 ====================
    @Override
    public String detailContent(List<String> ids) {
        String mediaKey = ids.get(0);
        String url = API_BASE + "/api/video/videodetails?autoplay=false&mediaKey=" + URLEncoder.encode(mediaKey)
                + "&System=h5&AppVersion=1.0&SystemVersion=h5&version=H3";

        String json = OkHttp.string(url, getHeaders());
        if (json == null || json.isEmpty()) return Result.string(new Vod());

        JsonObject resp = JsonParser.parseString(json).getAsJsonObject();
        if (!resp.has("ret") || resp.get("ret").getAsInt() != 200 || !resp.has("data") || !resp.getAsJsonObject("data").has("detailInfo")) {
            return Result.string(new Vod());
        }

        JsonObject info = resp.getAsJsonObject("data").getAsJsonObject("detailInfo");
        JsonArray episodes = info.has("episodes") ? info.getAsJsonArray("episodes") : new JsonArray();

        List<String> playUrls = new ArrayList<>();
        for (JsonElement epElem : episodes) {
            JsonObject ep = epElem.getAsJsonObject();
            String title = ep.has("episodeTitle") && !ep.get("episodeTitle").isJsonNull()
                    ? ep.get("episodeTitle").getAsString()
                    : "第" + (playUrls.size() + 1) + "集";
            String link = URLEncoder.encode(mediaKey) + "&episodeId=" + ep.get("episodeId").getAsString();
            playUrls.add(title + "$" + link);
        }

        Vod vod = new Vod();
        vod.setVodId(mediaKey);
        vod.setVodName(info.has("title") ? info.get("title").getAsString() : "未知");
        vod.setVodPic(info.has("coverImgUrl") ? info.get("coverImgUrl").getAsString() : "");
        vod.setTypeName(info.has("typeName") ? info.get("typeName").getAsString() : "");
        vod.setVodArea(info.has("regional") ? info.get("regional").getAsString() : "");
        vod.setVodRemarks(info.has("updateStatus") ? info.get("updateStatus").getAsString() : "");
        vod.setVodActor(info.has("actor") ? info.get("actor").getAsString() : (info.has("starring") ? info.get("starring").getAsString() : ""));
        vod.setVodDirector(info.has("director") ? info.get("director").getAsString() : "");
        vod.setVodContent(info.has("introduce") ? info.get("introduce").getAsString().replaceAll("\\s+", " ").trim() : "");
        vod.setVodPlayFrom("默认线路");
        vod.setVodPlayUrl(String.join("#", playUrls));

        return Result.string(vod);
    }

    // ==================== 搜索 ====================
    @Override
    public String searchContent(String key, boolean quick) {
        String url = API_BASE + "/api/list/gettitlegetdata?SearchCriteria=" + URLEncoder.encode(key);
        String json = OkHttp.string(url, getHeaders());

        List<Vod> list = new ArrayList<>();
        if (json == null || json.isEmpty()) return Result.string(list);

        JsonObject resp = JsonParser.parseString(json).getAsJsonObject();
        if (!resp.has("ret") || resp.get("ret").getAsInt() != 200 || !resp.has("data")) return Result.string(list);

        JsonArray array = resp.getAsJsonObject("data").getAsJsonArray("list");
        for (JsonElement elem : array) {
            JsonObject v = elem.getAsJsonObject();
            Vod vod = new Vod();
            vod.setVodId(v.get("mediaKey").getAsString());
            vod.setVodName(v.has("title") ? v.get("title").getAsString() : "未知标题");
            vod.setVodPic(v.has("coverImgUrl") ? v.get("coverImgUrl").getAsString() : "");
            vod.setVodRemarks(v.has("updateStatus") ? v.get("updateStatus").getAsString()
                    : (v.has("regional") ? v.get("regional").getAsString() : "5ik影视"));
            list.add(vod);
        }
        return Result.string(list);
    }

    // ==================== 播放解析（优先最高清晰度） ====================
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (!"默认线路".equals(flag)) {
            return "";
        }

        String[] parts = id.split("&episodeId=");
        if (parts.length < 2) return "";

        String mediaKey = parts[0];
        try {
            mediaKey = java.net.URLDecoder.decode(mediaKey, "UTF-8");
        } catch (Exception ignored) {
        }
        String episodeId = parts[1];

        String playUrl = API_BASE + "/api/video/getplaydata"
                + "?mediaKey=" + URLEncoder.encode(mediaKey)
                + "&videoId=" + episodeId
                + "&videoType=1&liveLine=&System=h5&AppVersion=1.0&SystemVersion=h5&version=H3";

        String json = OkHttp.string(playUrl, getHeaders());
        if (json == null || json.isEmpty()) return "";

        JsonObject resp = JsonParser.parseString(json).getAsJsonObject();
        if (!resp.has("ret") || resp.get("ret").getAsInt() != 200 || !resp.has("data")) return "";

        JsonArray candidates = resp.getAsJsonObject("data").getAsJsonArray("list");
        if (candidates == null || candidates.size() == 0) return "";

        List<JsonObject> validList = new ArrayList<>();
        for (JsonElement e : candidates) {
            JsonObject item = e.getAsJsonObject();
            if (item.has("mediaUrl") && !item.get("mediaUrl").isJsonNull()
                    && !item.get("mediaUrl").getAsString().trim().isEmpty()) {
                validList.add(item);
            }
        }

        if (validList.isEmpty()) return "";

        // 按 resolution 降序（最高清优先）
        validList.sort((a, b) -> {
            int ra = a.has("resolution") ? a.get("resolution").getAsInt() : 0;
            int rb = b.has("resolution") ? b.get("resolution").getAsInt() : 0;
            return Integer.compare(rb, ra);
        });

        String realUrl = validList.get(0).get("mediaUrl").getAsString();

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Referer", "https://www.5ik.top/");

        return Result.get().url(realUrl).header(headers).parse(0).string();
    }
}
