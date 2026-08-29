package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Wooyun.tv - 兼容 Fongmi / OK影视
 * Fongmi 的 OkHttp 没有 post(json)，统一用 newCall + RequestBody
 */
public class Wooyun extends Spider {

    private static final String HOST = "https://wooyun.tv";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private Map<String, String> getHeader() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Referer", HOST + "/");
        h.put("Origin", HOST);
        return h;
    }

    private void log(String msg) {
        SpiderDebug.log("[Wooyun] " + msg);
    }

    /** POST JSON，兼容 Fongmi OkHttp（无 post 方法） */
    private String postJson(String url, String json) {
        try {
            RequestBody body = RequestBody.create(JSON, json);
            try (Response response = OkHttp.newCall(url, getHeader(), body).execute()) {
                if (response.body() == null) return "";
                return response.body().string();
            }
        } catch (Exception e) {
            log("postJson 异常: " + e.getMessage());
            e.printStackTrace();
            return "";
        }
    }

    /** GET */
    private String get(String url) {
        try {
            String res = OkHttp.string(url, getHeader());
            return res == null ? "" : res;
        } catch (Exception e) {
            log("get 异常: " + e.getMessage());
            return "";
        }
    }

    // ==================== 筛选 ====================
    private List<Filter> buildFilters() {
        List<Filter> filters = new ArrayList<>();
        filters.add(new Filter("sort", "排序", Arrays.asList(
                new Filter.Value("全部", ""),
                new Filter.Value("新上映", "newest"),
                new Filter.Value("热播榜", "hot"),
                new Filter.Value("评分榜", "rating"),
                new Filter.Value("近期更新", "new_update")
        )));
        filters.add(new Filter("genre", "类型", Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("动作", "action"), new Filter.Value("喜剧", "comedy"),
                new Filter.Value("剧情", "drama"), new Filter.Value("爱情", "romance"), new Filter.Value("惊悚", "thriller"),
                new Filter.Value("恐怖", "horror"), new Filter.Value("科幻", "sci_fi"), new Filter.Value("奇幻", "fantasy"),
                new Filter.Value("战争", "war"), new Filter.Value("历史", "history"), new Filter.Value("冒险", "adventure"),
                new Filter.Value("犯罪", "crime")
        )));
        filters.add(new Filter("region", "地区", Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("大陆", "china"), new Filter.Value("香港", "hong_kong"),
                new Filter.Value("台湾", "taiwan"), new Filter.Value("日本", "japan"), new Filter.Value("韩国", "korea"),
                new Filter.Value("美国", "usa"), new Filter.Value("英国", "uk")
        )));
        filters.add(new Filter("language", "语言", Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("中文", "chinese"), new Filter.Value("英语", "english"),
                new Filter.Value("日语", "japanese"), new Filter.Value("韩语", "korean"), new Filter.Value("法语", "french"),
                new Filter.Value("德语", "german"), new Filter.Value("泰语", "thai"), new Filter.Value("俄语", "russian")
        )));
        filters.add(new Filter("year", "年份", Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("今年", "THIS_YEAR"), new Filter.Value("去年", "LAST_YEAR"),
                new Filter.Value("更早", "EARLIER"), new Filter.Value("90年代", "IN_THE_1990S"), new Filter.Value("80年代", "IN_THE_1980S"),
                new Filter.Value("怀旧", "NOSTALGIA")
        )));
        return filters;
    }

    /**
     * 统一搜索/分类
     * @return Object[]{ List&lt;Vod&gt; list, Integer total }
     */
    private Object[] searchApi(String topCode, String searchKey, String sortCode,
                               List<String> menuCodes, int page, int pageSize) {
        List<Vod> list = new ArrayList<>();
        int total = 0;
        try {
            JSONArray menuCodeList = new JSONArray();
            if (menuCodes != null) {
                for (String c : menuCodes) {
                    if (!TextUtils.isEmpty(c)) menuCodeList.put(c);
                }
            }

            JSONObject body = new JSONObject();
            body.put("menuCodeList", menuCodeList);
            body.put("pageIndex", page);
            body.put("pageSize", pageSize);
            body.put("searchKey", searchKey == null ? "" : searchKey);
            body.put("sortCode", sortCode == null ? "" : sortCode);
            body.put("topCode", topCode == null ? "" : topCode);

            String res = postJson(HOST + "/api/proxy?url=/movie/media/search", body.toString());
            log("api topCode=" + topCode + " key=" + searchKey + " page=" + page
                    + " len=" + res.length());

            if (TextUtils.isEmpty(res)) return new Object[]{list, 0};

            JSONObject data = new JSONObject(res).optJSONObject("data");
            if (data == null) {
                log("data 为空: " + res.substring(0, Math.min(120, res.length())));
                return new Object[]{list, 0};
            }

            total = data.optInt("total", 0);
            JSONArray records = data.optJSONArray("records");
            if (records != null) {
                for (int i = 0; i < records.length(); i++) {
                    JSONObject item = records.getJSONObject(i);
                    String id = item.optString("id", item.optString("mediaId", ""));
                    if (TextUtils.isEmpty(id)) continue;

                    String pic = item.optString("posterUrlS3", "");
                    if (TextUtils.isEmpty(pic)) pic = item.optString("posterUrl", "");

                    Vod vod = new Vod();
                    vod.setVodId(id);
                    vod.setVodName(item.optString("title", ""));
                    vod.setVodPic(pic);
                    vod.setVodRemarks(item.optString("episodeStatus", ""));
                    list.add(vod);
                }
            }
            log("解析 " + list.size() + " 条, total=" + total);
        } catch (Exception e) {
            log("searchApi 异常: " + e.getMessage());
            e.printStackTrace();
        }
        return new Object[]{list, total};
    }

    // ==================== 生命周期 ====================
    @Override
    public void init(Context context, String extend) throws Exception {
        log("初始化完成");
    }

    /** 首页：分类 + 筛选 */
    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("tv_series", "电视剧"));
        classes.add(new Class("movie", "电影"));
        classes.add(new Class("korean_drama", "韩剧"));
        classes.add(new Class("short_drama", "短剧"));
        classes.add(new Class("variety", "综艺"));

        LinkedHashMap<String, List<Filter>> filterMap = new LinkedHashMap<>();
        List<Filter> filters = buildFilters();
        for (Class c : classes) {
            filterMap.put(c.getTypeId(), filters);
        }
        return Result.get().classes(classes).filters(filterMap).string();
    }

    /** 首页推荐（Fongmi 走这里） */
    @Override
    public String homeVideoContent() throws Exception {
        Object[] ret = searchApi("tv_series", "", "", null, 1, 24);
        @SuppressWarnings("unchecked")
        List<Vod> list = (List<Vod>) ret[0];
        return Result.get().vod(list).string();
    }

    /** 分类列表（必须带 page） */
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        if (extend == null) extend = new HashMap<>();
        int page = 1;
        try {
            page = Integer.parseInt(pg);
        } catch (Exception ignored) {}

        String sortCode = extend.getOrDefault("sort", "");
        List<String> menuCodes = new ArrayList<>();
        for (String k : new String[]{"genre", "region", "language", "year"}) {
            String v = extend.get(k);
            if (!TextUtils.isEmpty(v)) menuCodes.add(v);
        }

        log("category tid=[" + tid + "] page=" + page + " sort=" + sortCode + " menus=" + menuCodes);

        Object[] ret = searchApi(tid, "", sortCode, menuCodes, page, 24);
        @SuppressWarnings("unchecked")
        List<Vod> list = (List<Vod>) ret[0];
        int total = (Integer) ret[1];

        int pageSize = 24;
        int pagecount = total > 0 ? (int) Math.ceil(total / 24.0) : 1;
        if (pagecount < 1) pagecount = 1;

        return Result.get().vod(list).page(page, pagecount, pageSize, total).string();
    }

    /** 详情 */
    @Override
    public String detailContent(List<String> ids) throws Exception {
        String mediaId = ids.get(0);
        Vod vod = new Vod();
        vod.setVodId(mediaId);

        try {
            String detailRes = get(HOST + "/api/proxy?url=/movie/media/base/detail?mediaId=" + mediaId);
            JSONObject info = new JSONObject(detailRes).optJSONObject("data");
            if (info == null) return Result.string(vod);

            vod.setVodName(info.optString("title", ""));
            String pic = info.optString("posterUrlS3", "");
            if (TextUtils.isEmpty(pic)) pic = info.optString("posterUrl", "");
            vod.setVodPic(pic);
            vod.setVodYear(String.valueOf(info.optInt("releaseYear")));
            vod.setVodArea(info.optString("region", ""));
            vod.setVodRemarks(info.optString("episodeStatus", ""));
            vod.setVodContent(info.optString("overview", ""));

            JSONArray actorsArr = info.optJSONArray("actors");
            JSONArray dirsArr = info.optJSONArray("directors");
            List<String> actors = new ArrayList<>();
            List<String> directors = new ArrayList<>();
            if (actorsArr != null) {
                for (int i = 0; i < actorsArr.length(); i++) actors.add(actorsArr.optString(i));
            }
            if (dirsArr != null) {
                for (int i = 0; i < dirsArr.length(); i++) directors.add(dirsArr.optString(i));
            }
            vod.setVodActor(TextUtils.join(", ", actors));
            vod.setVodDirector(TextUtils.join(", ", directors));

            String epRes = get(HOST + "/api/proxy?url=/movie/media/video/list?mediaId=" + mediaId + "&lineName=&resolutionCode=");
            JSONArray seasons = new JSONObject(epRes).optJSONArray("data");

            List<String> fromList = new ArrayList<>();
            List<String> urlList = new ArrayList<>();
            if (seasons != null) {
                for (int s = 0; s < seasons.length(); s++) {
                    JSONObject season = seasons.getJSONObject(s);
                    int seasonNo = season.optInt("seasonNo", 1);
                    JSONArray epList = season.optJSONArray("videoList");
                    if (epList == null || epList.length() == 0) continue;

                    List<String> urls = new ArrayList<>();
                    for (int e = 0; e < epList.length(); e++) {
                        JSONObject ep = epList.getJSONObject(e);
                        String name = ep.optString("remark", "第" + ep.optInt("epNo") + "集");
                        String playUrl = ep.optString("playUrl", "");
                        if (!TextUtils.isEmpty(playUrl)) {
                            urls.add(name + "$" + playUrl);
                        }
                    }
                    if (!urls.isEmpty()) {
                        fromList.add("第" + seasonNo + "季");
                        urlList.add(TextUtils.join("#", urls));
                    }
                }
            }

            if (!fromList.isEmpty()) {
                vod.setVodPlayFrom(TextUtils.join("$$$", fromList));
                vod.setVodPlayUrl(TextUtils.join("$$$", urlList));
            }
        } catch (Exception e) {
            log("detail 异常: " + e.getMessage());
            e.printStackTrace();
        }
        return Result.string(vod);
    }

    /** 搜索 */
    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        Object[] ret = searchApi("", key, "", null, 1, 24);
        @SuppressWarnings("unchecked")
        List<Vod> list = (List<Vod>) ret[0];
        int total = (Integer) ret[1];
        int pagecount = total > 0 ? (int) Math.ceil(total / 24.0) : 1;
        if (pagecount < 1) pagecount = 1;
        return Result.get().vod(list).page(1, pagecount, 24, total).string();
    }

    /** 播放 */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", HOST + "/");
        return Result.get().url(id).header(headers).string();
    }
}
