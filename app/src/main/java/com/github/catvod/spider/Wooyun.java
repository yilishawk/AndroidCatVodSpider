package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Wooyun extends Spider {

    private static final String HOST = "https://wooyun.tv";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private void logger(String msg) {
        try { Proxy.log("[Wooyun] " + msg); } catch (Exception ignored) {}
    }

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Content-Type", "application/json");
        headers.put("Referer", HOST + "/");
        return headers;
    }

    private List<Filter> buildFilters() {
        List<Filter> filters = new ArrayList<>();

        filters.add(new Filter("sort", "排序", Arrays.asList(
                new Filter.Value("全部",   "default"),
                new Filter.Value("新上映", "newest"),
                new Filter.Value("热播榜", "hot"),
                new Filter.Value("评分榜", "rating"),
                new Filter.Value("近期更新","new_update")
        )));

        filters.add(new Filter("genre", "类型", Arrays.asList(
                new Filter.Value("全部", ""),
                new Filter.Value("动作", "action"),
                new Filter.Value("喜剧", "comedy"),
                new Filter.Value("剧情", "drama"),
                new Filter.Value("爱情", "romance"),
                new Filter.Value("惊悚", "thriller"),
                new Filter.Value("恐怖", "horror"),
                new Filter.Value("科幻", "sci_fi"),
                new Filter.Value("奇幻", "fantasy"),
                new Filter.Value("战争", "war"),
                new Filter.Value("历史", "history"),
                new Filter.Value("冒险", "adventure"),
                new Filter.Value("犯罪", "crime")
        )));

        filters.add(new Filter("region", "地区", Arrays.asList(
                new Filter.Value("全部", ""),
                new Filter.Value("大陆", "china"),
                new Filter.Value("香港", "hong_kong"),
                new Filter.Value("台湾", "taiwan"),
                new Filter.Value("日本", "japan"),
                new Filter.Value("韩国", "korea"),
                new Filter.Value("美国", "usa"),
                new Filter.Value("英国", "uk")
        )));

        filters.add(new Filter("language", "语言", Arrays.asList(
                new Filter.Value("全部", ""),
                new Filter.Value("中文", "chinese"),
                new Filter.Value("英语", "english"),
                new Filter.Value("日语", "japanese"),
                new Filter.Value("韩语", "korean"),
                new Filter.Value("法语", "french"),
                new Filter.Value("德语", "german"),
                new Filter.Value("泰语", "thai"),
                new Filter.Value("俄语", "russian")
        )));

        filters.add(new Filter("year", "年份", Arrays.asList(
                new Filter.Value("全部", ""),
                new Filter.Value("今年",  "THIS_YEAR"),
                new Filter.Value("去年",  "LAST_YEAR"),
                new Filter.Value("更早",  "EARLIER"),
                new Filter.Value("90年代","IN_THE_1990S"),
                new Filter.Value("80年代","IN_THE_1980S"),
                new Filter.Value("怀旧",  "NOSTALGIA")
        )));

        return filters;
    }

    @Override
    public void init(Context context, String extend) {
        logger("初始化完成");
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            List<Class> classes = new ArrayList<>();
            classes.add(new Class("tv_series",    "电视剧"));
            classes.add(new Class("movie",         "电影"));
            classes.add(new Class("korean_drama",  "韩剧"));
            classes.add(new Class("short_drama",   "短剧"));
            classes.add(new Class("variety",       "综艺"));

            // 所有分类使用相同筛选
            LinkedHashMap<String, List<Filter>> filterMap = new LinkedHashMap<>();
            List<Filter> filters = buildFilters();
            for (Class c : classes) filterMap.put(c.getTypeId(), filters);

            return Result.string(classes, filterMap);
        } catch (Exception e) {
            logger("homeContent 异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            int page = Integer.parseInt(pg);

            // 从 extend 里取筛选参数
            String sortCode  = extend != null ? extend.getOrDefault("sort",     "default") : "default";
            String genre     = extend != null ? extend.getOrDefault("genre",    "") : "";
            String region    = extend != null ? extend.getOrDefault("region",   "") : "";
            String language  = extend != null ? extend.getOrDefault("language", "") : "";
            String year      = extend != null ? extend.getOrDefault("year",     "") : "";

            // 组装 menuCodeList
            JSONArray menuCodeList = new JSONArray();
            if (!TextUtils.isEmpty(genre))    menuCodeList.put(genre);
            if (!TextUtils.isEmpty(region))   menuCodeList.put(region);
            if (!TextUtils.isEmpty(language)) menuCodeList.put(language);
            if (!TextUtils.isEmpty(year))     menuCodeList.put(year);

            JSONObject body = new JSONObject();
            body.put("menuCodeList", menuCodeList);
            body.put("pageIndex",    String.valueOf(page));
            body.put("pageSize",     24);
            body.put("searchKey",    "");
            body.put("sortCode",     sortCode);
            body.put("topCode",      tid);

            String res = OkHttp.post(
                    HOST + "/api/proxy?url=/movie/media/search",
                    body.toString(),
                    getHeaders()
            ).getBody();

            JSONArray records = new JSONObject(res).getJSONObject("data").getJSONArray("records");
            List<Vod> list = new ArrayList<>();
            for (int i = 0; i < records.length(); i++) {
                JSONObject item = records.getJSONObject(i);
                String pic = item.optString("posterUrlS3", "");
                if (TextUtils.isEmpty(pic)) pic = item.optString("posterUrl", "");
                Vod vod = new Vod();
                vod.setVodId(String.valueOf(item.optInt("id")));
                vod.setVodName(item.optString("title", ""));
                vod.setVodPic(pic);
                vod.setVodRemarks(item.optString("episodeStatus", ""));
                list.add(vod);
            }
            return Result.string(list);
        } catch (Exception e) {
            logger("categoryContent 异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String mediaId = ids.get(0);

            String detailRes = OkHttp.string(
                    HOST + "/api/proxy?url=/movie/media/base/detail?mediaId=" + mediaId,
                    getHeaders()
            );
            JSONObject info = new JSONObject(detailRes).getJSONObject("data");

            String epRes = OkHttp.string(
                    HOST + "/api/proxy?url=/movie/media/video/list?mediaId=" + mediaId + "&lineName=&resolutionCode=",
                    getHeaders()
            );
            JSONArray seasons = new JSONObject(epRes).getJSONArray("data");

            List<String> fromList = new ArrayList<>();
            List<String> urlList = new ArrayList<>();
            for (int s = 0; s < seasons.length(); s++) {
                JSONObject season = seasons.getJSONObject(s);
                int seasonNo = season.optInt("seasonNo", 1);
                JSONArray epList = season.optJSONArray("videoList");
                if (epList == null || epList.length() == 0) continue;
                fromList.add("第" + seasonNo + "季");
                List<String> urls = new ArrayList<>();
                for (int e = 0; e < epList.length(); e++) {
                    JSONObject ep = epList.getJSONObject(e);
                    String name = ep.optString("remark", "第" + ep.optInt("epNo") + "集");
                    String url = ep.optString("playUrl", "");
                    if (!TextUtils.isEmpty(url)) urls.add(name + "$" + url);
                }
                urlList.add(TextUtils.join("#", urls));
            }

            JSONArray actorsArr = info.optJSONArray("actors");
            JSONArray dirsArr   = info.optJSONArray("directors");
            List<String> actors    = new ArrayList<>();
            List<String> directors = new ArrayList<>();
            if (actorsArr != null) for (int i = 0; i < actorsArr.length(); i++) actors.add(actorsArr.getString(i));
            if (dirsArr   != null) for (int i = 0; i < dirsArr.length();   i++) directors.add(dirsArr.getString(i));

            String pic = info.optString("posterUrlS3", "");
            if (TextUtils.isEmpty(pic)) pic = info.optString("posterUrl", "");

            Vod vod = new Vod();
            vod.setVodId(mediaId);
            vod.setVodName(info.optString("title", ""));
            vod.setVodPic(pic);
            vod.setVodYear(String.valueOf(info.optInt("releaseYear")));
            vod.setVodArea(info.optString("region", ""));
            vod.setVodActor(TextUtils.join(", ", actors));
            vod.setVodDirector(TextUtils.join(", ", directors));
            vod.setVodContent(info.optString("overview", ""));
            vod.setVodRemarks(info.optString("episodeStatus", ""));
            vod.setVodPlayFrom(TextUtils.join("$$$", fromList));
            vod.setVodPlayUrl(TextUtils.join("$$$", urlList));

            return Result.string(vod);
        } catch (Exception e) {
            logger("detailContent 异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            JSONObject body = new JSONObject();
            body.put("menuCodeList", new JSONArray());
            body.put("pageIndex",    "1");
            body.put("pageSize",     10);
            body.put("searchKey",    key);
            body.put("sortCode",     "");
            body.put("topCode",      "");

            String res = OkHttp.post(
                    HOST + "/api/proxy?url=/movie/media/search",
                    body.toString(),
                    getHeaders()
            ).getBody();

            JSONArray records = new JSONObject(res).getJSONObject("data").getJSONArray("records");
            List<Vod> list = new ArrayList<>();
            for (int i = 0; i < records.length(); i++) {
                JSONObject item = records.getJSONObject(i);
                if (!item.optString("title", "").trim().equals(key.trim())) continue;
                String pic = item.optString("posterUrlS3", "");
                if (TextUtils.isEmpty(pic)) pic = item.optString("posterUrl", "");
                Vod vod = new Vod();
                vod.setVodId(String.valueOf(item.optInt("id")));
                vod.setVodName(item.optString("title", ""));
                vod.setVodPic(pic);
                vod.setVodRemarks(item.optString("episodeStatus", ""));
                list.add(vod);
            }
            return Result.string(list);
        } catch (Exception e) {
            logger("searchContent 异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String realUrl = id;
            try {
                Map<String, String> h = new HashMap<>();
                h.put("User-Agent", UA);
                String location = OkHttp.getLocation(id, h);
                if (!TextUtils.isEmpty(location)) {
                    if (location.startsWith("/")) {
                        java.net.URL u = new java.net.URL(id);
                        realUrl = u.getProtocol() + "://" + u.getHost() + location;
                    } else {
                        realUrl = location;
                    }
                }
            } catch (Exception ignored) {}

            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA);
            return Result.get().url(realUrl).header(headers).string();
        } catch (Exception e) {
            logger("playerContent 异常: " + e.getMessage());
            return Result.get().parse(1).url(id).string();
        }
    }
}
