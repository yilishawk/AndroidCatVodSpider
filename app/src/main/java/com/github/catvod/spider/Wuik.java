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
 * 5ik影视 API 源（原 https://www.5ik.top）
 * Java Spider 版 - 可直接打包成 JAR 用于 TVBox / CatVod
 * 作者：基于原 PHP 脚本改写
 * 日期：2025年12月
 */
public class Wuik extends Spider {

    private static final String API_BASE = "https://api.5ik.top";
    private static final String USER_AGENT = "Mozilla/5ik.top (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36";

    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Origin", "https://www.5ik.top");
        headers.put("Referer", "https://www.5ik.top/");
        headers.put("Accept", "application/json");
        headers.put("X-Requested-With", "XMLHttpRequest");
        return headers;
    }

    // ==================== 首页分类 ====================
    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("drama", "电视剧"));
        classes.add(new Class("movie", "电影"));
        classes.add(new Class("short", "短剧"));
        classes.add(new Class("variety", "综艺"));

        // 首页推荐可留空，或请求一个分类作为推荐
        List<Vod> homeVods = new ArrayList<>();
        // 可选：请求电视剧第一页作为首页推荐
        // homeVods.addAll(fetchCategoryList("drama", "1", "0"));

        return Result.string(classes, homeVods);
    }

    // ==================== 分类内容（支持二级分类 subcat） ====================
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        String subcat = extend.getOrDefault("subcat", "0");
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

        String json = OkHttp.string(url, getHeaders());
        JsonObject resp = JsonParser.parseString(json).getAsJsonObject();

        if (resp.has("ret") && resp.get("ret").getAsInt() == 200 && resp.has("data")) {
            JsonArray array = resp.getAsJsonObject("data").getAsJsonArray("list");
            if (array != null) {
                for (JsonElement elem : array) {
                    JsonObject v = elem.getAsJsonObject();
                    Vod vod = new Vod();
                    vod.setVodId(v.get("mediaKey").getAsString());
                    vod.setVodName(v.get("title").getAsString());
                    vod.setVodPic(v.get("coverImgUrl").getAsString());
                    vod.setVodRemarks(v.get("updateStatus") != null && !v.get("updateStatus").isJsonNull()
                            ? v.get("updateStatus").getAsString()
                            : (v.has("updateMsg") ? v.get("updateMsg").getAsString() : ""));
                    list.add(vod);
                }
            }
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
        JsonObject resp = JsonParser.parseString(json).getAsJsonObject();

        if (!resp.has("ret") || resp.get("ret").getAsInt() != 200 || !resp.getAsJsonObject("data").has("detailInfo")) {
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
        vod.setVodName(info.get("title").getAsString());
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
        String json = quick ? OkHttp.string(url, getHeaders()) : OkHttp.string(url, getHeaders()); // quick 不影响缓存

        List<Vod> list = new ArrayList<>();
        JsonObject resp = JsonParser.parseString(json).getAsJsonObject();

        if (resp.has("ret") && resp.get("ret").getAsInt() == 200 && resp.has("data")) {
            JsonArray array = resp.getAsJsonObject("data").getAsJsonArray("list");
            if (array != null) {
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
            }
        }
        return Result.string(list);
    }

    // ==================== 播放解析（选最高清晰度） ====================
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        // 只支持 "默认线路"
        if (!"默认线路".equals(flag)) {
            return Result.get().url("").parse(0).string();
        }

        // 解析 play 参数：mediaKey&episodeId=xxx
        String[] parts = id.split("&episodeId=");
        if (parts.length < 2) {
            return Result.get().url("").parse(0).string();
        }
        String mediaKey = parts[0];
        try {
            mediaKey = java.net.URLDecoder.decode(mediaKey, "UTF-8");
        } catch (Exception ignored) {}
        String episodeId = parts[1];

        String playUrl = API_BASE + "/api/video/getplaydata"
                + "?mediaKey=" + URLEncoder.encode(mediaKey)
                + "&videoId=" + episodeId
                + "&videoType=1&liveLine=&System=h5&AppVersion=1.0&SystemVersion=h5&version=H3"
                + "&DeviceId=13ad12e02b9933302c87f6e7872a9068"
                + "&i18n=0&uid=129507530&gid=1"
                + "&token=1234bc0965e4440b81befc8838a4f2c7"
                + "&sign=22123479cbcf4be629ab18f9bd1b7d48c4fddb2aaf3e42f5b4f31061b2d0e6b2_5f049e480273981058544501dad7d50f"
                + "&expire=1764839454.49322&login_uid=129507530&pub=1764645066&vv=17d6334e4fdee80ffeecbe07991f1288";

        String json = OkHttp.string(playUrl, getHeaders());
        JsonObject resp = JsonParser.parseString(json).getAsJsonObject();

        if (resp.has("ret") && resp.get("ret").getAsInt() == 200 && resp.has("data")) {
            JsonArray candidates = resp.getAsJsonObject("data").getAsJsonArray("list");
            if (candidates != null && candidates.size() > 0) {
                List<JsonObject> validList = new ArrayList<>();
                for (JsonElement e : candidates) {
                    JsonObject item = e.getAsJsonObject();
                    if (item.has("mediaUrl") && !item.get("mediaUrl").isJsonNull() && !item.get("mediaUrl").getAsString().trim().isEmpty()) {
                        validList.add(item);
                    }
                }
                if (!validList.isEmpty()) {
                    // 按 resolution 降序（最高清优先）
                    validList.sort((a, b) -> {
                        int ra = a.has("resolution") ? a.get("resolution").getAsInt() : 0;
                        int rb = b.has("resolution") ? b.get("resolution").getAsInt() : 0;
                        return Integer.compare(rb, ra);
                    });
                    String realUrl = validList.get(0).get("mediaUrl").getAsString();

                    // 添加必要 header
                    Map<String, String> headers = new HashMap<>();
                    headers.put("User-Agent", USER_AGENT);
                    headers.put("Referer", "https://www.5ik.top/");

                    return Result.get().url(realUrl).header(headers).parse(0).string();
                }
            }
        }
        return Result.get().url("").parse(0).string();
    }
}
