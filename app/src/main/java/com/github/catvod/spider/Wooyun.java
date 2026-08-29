package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
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
 * Wooyun.tv - 按骚火风格重写，兼容 Fongmi
 * 自己 OkHttpClient + 手写 JSONObject，不依赖 Result / 壳子 OkHttp
 */
public class Wooyun extends Spider {

    private static final String HOST = "https://wooyun.tv";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final OkHttpClient client;
    private final Map<String, String> headers;

    public Wooyun() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();

        this.headers = new HashMap<>();
        this.headers.put("User-Agent", UA);
        this.headers.put("Content-Type", "application/json");
        this.headers.put("Accept", "application/json, text/plain, */*");
        this.headers.put("Referer", HOST + "/");
        this.headers.put("Origin", HOST);
    }

    private void log(String msg) {
        SpiderDebug.log("[Wooyun] " + msg);
    }

    private String postJson(String url, String json) throws Exception {
        RequestBody body = RequestBody.create(
                MediaType.parse("application/json; charset=utf-8"),
                json
        );
        Request request = new Request.Builder()
                .url(url)
                .headers(Headers.of(headers))
                .post(body)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new Exception("HTTP " + response.code());
            }
            return response.body().string();
        }
    }

    private String get(String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .headers(Headers.of(headers))
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new Exception("HTTP " + response.code());
            }
            return response.body().string();
        }
    }

    /** 统一搜索/分类 API */
    private JSONArray searchApi(String topCode, String searchKey, String sortCode,
                                List<String> menuCodes, int page, int pageSize,
                                int[] totalOut) throws Exception {
        JSONArray menuCodeList = new JSONArray();
        if (menuCodes != null) {
            for (String c : menuCodes) {
                if (c != null && !c.isEmpty()) menuCodeList.put(c);
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
        log("api topCode=" + topCode + " key=" + searchKey + " page=" + page + " len=" + res.length());

        JSONArray list = new JSONArray();
        int total = 0;

        JSONObject root = new JSONObject(res);
        JSONObject data = root.optJSONObject("data");
        if (data != null) {
            total = data.optInt("total", 0);
            JSONArray records = data.optJSONArray("records");
            if (records != null) {
                for (int i = 0; i < records.length(); i++) {
                    JSONObject item = records.getJSONObject(i);
                    String id = item.optString("id", item.optString("mediaId", ""));
                    if (id.isEmpty()) continue;

                    String pic = item.optString("posterUrlS3", "");
                    if (pic.isEmpty()) pic = item.optString("posterUrl", "");

                    JSONObject vod = new JSONObject();
                    vod.put("vod_id", id);
                    vod.put("vod_name", item.optString("title", ""));
                    vod.put("vod_pic", pic);
                    vod.put("vod_remarks", item.optString("episodeStatus", ""));
                    list.put(vod);
                }
            }
        }
        log("解析 " + list.length() + " 条, total=" + total);
        if (totalOut != null && totalOut.length > 0) totalOut[0] = total;
        return list;
    }

    private JSONObject createClass(String id, String name) throws Exception {
        JSONObject c = new JSONObject();
        c.put("type_id", id);
        c.put("type_name", name);
        return c;
    }

    private JSONObject createOption(String n, String v) throws Exception {
        JSONObject o = new JSONObject();
        o.put("n", n);
        o.put("v", v);
        return o;
    }

    private JSONArray buildFilterValues(String[][] pairs) throws Exception {
        JSONArray arr = new JSONArray();
        for (String[] p : pairs) {
            arr.put(createOption(p[0], p[1]));
        }
        return arr;
    }

    private JSONArray buildOneFilterGroup() throws Exception {
        JSONArray filters = new JSONArray();

        JSONObject sort = new JSONObject();
        sort.put("key", "sort");
        sort.put("name", "排序");
        sort.put("value", buildFilterValues(new String[][]{
                {"全部", ""}, {"新上映", "newest"}, {"热播榜", "hot"},
                {"评分榜", "rating"}, {"近期更新", "new_update"}
        }));
        filters.put(sort);

        JSONObject genre = new JSONObject();
        genre.put("key", "genre");
        genre.put("name", "类型");
        genre.put("value", buildFilterValues(new String[][]{
                {"全部", ""}, {"动作", "action"}, {"喜剧", "comedy"}, {"剧情", "drama"},
                {"爱情", "romance"}, {"惊悚", "thriller"}, {"恐怖", "horror"},
                {"科幻", "sci_fi"}, {"奇幻", "fantasy"}, {"战争", "war"},
                {"历史", "history"}, {"冒险", "adventure"}, {"犯罪", "crime"}
        }));
        filters.put(genre);

        JSONObject region = new JSONObject();
        region.put("key", "region");
        region.put("name", "地区");
        region.put("value", buildFilterValues(new String[][]{
                {"全部", ""}, {"大陆", "china"}, {"香港", "hong_kong"}, {"台湾", "taiwan"},
                {"日本", "japan"}, {"韩国", "korea"}, {"美国", "usa"}, {"英国", "uk"}
        }));
        filters.put(region);

        JSONObject language = new JSONObject();
        language.put("key", "language");
        language.put("name", "语言");
        language.put("value", buildFilterValues(new String[][]{
                {"全部", ""}, {"中文", "chinese"}, {"英语", "english"}, {"日语", "japanese"},
                {"韩语", "korean"}, {"法语", "french"}, {"德语", "german"},
                {"泰语", "thai"}, {"俄语", "russian"}
        }));
        filters.put(language);

        JSONObject year = new JSONObject();
        year.put("key", "year");
        year.put("name", "年份");
        year.put("value", buildFilterValues(new String[][]{
                {"全部", ""}, {"今年", "THIS_YEAR"}, {"去年", "LAST_YEAR"},
                {"更早", "EARLIER"}, {"90年代", "IN_THE_1990S"},
                {"80年代", "IN_THE_1980S"}, {"怀旧", "NOSTALGIA"}
        }));
        filters.put(year);

        return filters;
    }

    @Override
    public void init(Context context, String extend) {
        log("初始化完成");
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = new JSONArray();
            classes.put(createClass("tv_series", "电视剧"));
            classes.put(createClass("movie", "电影"));
            classes.put(createClass("korean_drama", "韩剧"));
            classes.put(createClass("short_drama", "短剧"));
            classes.put(createClass("variety", "综艺"));
            result.put("class", classes);

            if (filter) {
                JSONObject filters = new JSONObject();
                JSONArray one = buildOneFilterGroup();
                filters.put("tv_series", one);
                filters.put("movie", one);
                filters.put("korean_drama", one);
                filters.put("short_drama", one);
                filters.put("variety", one);
                result.put("filters", filters);
            }
            return result.toString();
        } catch (Exception e) {
            log("homeContent: " + e.getMessage());
            return "{\"class\":[],\"filters\":{}}";
        }
    }

    @Override
    public String homeVideoContent() {
        try {
            int[] total = new int[1];
            JSONArray list = searchApi("tv_series", "", "", null, 1, 24, total);
            JSONObject result = new JSONObject();
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            log("homeVideoContent: " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            int page = 1;
            try {
                page = Integer.parseInt(pg);
            } catch (Exception ignored) {
            }

            String sortCode = "";
            List<String> menuCodes = new ArrayList<>();
            if (extend != null) {
                if (extend.containsKey("sort") && extend.get("sort") != null) {
                    sortCode = extend.get("sort");
                }
                for (String k : new String[]{"genre", "region", "language", "year"}) {
                    String v = extend.get(k);
                    if (v != null && !v.isEmpty()) menuCodes.add(v);
                }
            }

            log("category tid=[" + tid + "] page=" + page + " sort=" + sortCode);

            int[] totalArr = new int[1];
            JSONArray list = searchApi(tid, "", sortCode, menuCodes, page, 24, totalArr);
            int total = totalArr[0];
            int pagecount = total > 0 ? (int) Math.ceil(total / 24.0) : 999;
            if (pagecount < 1) pagecount = 1;

            JSONObject result = new JSONObject();
            result.put("list", list);
            result.put("page", page);
            result.put("pagecount", pagecount);
            result.put("limit", 24);
            result.put("total", total > 0 ? total : 9999);
            return result.toString();
        } catch (Exception e) {
            log("categoryContent: " + e.getMessage());
            e.printStackTrace();
            return "{\"list\":[],\"page\":1,\"pagecount\":1,\"limit\":24,\"total\":0}";
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            if (ids == null || ids.isEmpty()) return "{\"list\":[]}";
            String mediaId = ids.get(0);

            String detailRes = get(HOST + "/api/proxy?url=/movie/media/base/detail?mediaId=" + mediaId);
            JSONObject info = new JSONObject(detailRes).optJSONObject("data");
            if (info == null) return "{\"list\":[]}";

            String pic = info.optString("posterUrlS3", "");
            if (pic.isEmpty()) pic = info.optString("posterUrl", "");

            StringBuilder actors = new StringBuilder();
            StringBuilder directors = new StringBuilder();
            JSONArray actorsArr = info.optJSONArray("actors");
            JSONArray dirsArr = info.optJSONArray("directors");
            if (actorsArr != null) {
                for (int i = 0; i < actorsArr.length(); i++) {
                    if (i > 0) actors.append(", ");
                    actors.append(actorsArr.optString(i));
                }
            }
            if (dirsArr != null) {
                for (int i = 0; i < dirsArr.length(); i++) {
                    if (i > 0) directors.append(", ");
                    directors.append(dirsArr.optString(i));
                }
            }

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
                        if (!playUrl.isEmpty()) {
                            urls.add(name + "$" + playUrl);
                        }
                    }
                    if (!urls.isEmpty()) {
                        fromList.add("第" + seasonNo + "季");
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < urls.size(); i++) {
                            if (i > 0) sb.append("#");
                            sb.append(urls.get(i));
                        }
                        urlList.add(sb.toString());
                    }
                }
            }

            JSONObject vod = new JSONObject();
            vod.put("vod_id", mediaId);
            vod.put("vod_name", info.optString("title", ""));
            vod.put("vod_pic", pic);
            vod.put("vod_year", String.valueOf(info.optInt("releaseYear")));
            vod.put("vod_area", info.optString("region", ""));
            vod.put("vod_remarks", info.optString("episodeStatus", ""));
            vod.put("vod_actor", actors.toString());
            vod.put("vod_director", directors.toString());
            vod.put("vod_content", info.optString("overview", ""));

            if (fromList.isEmpty()) {
                vod.put("vod_play_from", "");
                vod.put("vod_play_url", "");
            } else {
                StringBuilder fromSb = new StringBuilder();
                StringBuilder urlSb = new StringBuilder();
                for (int i = 0; i < fromList.size(); i++) {
                    if (i > 0) {
                        fromSb.append("$$$");
                        urlSb.append("$$$");
                    }
                    fromSb.append(fromList.get(i));
                    urlSb.append(urlList.get(i));
                }
                vod.put("vod_play_from", fromSb.toString());
                vod.put("vod_play_url", urlSb.toString());
            }

            JSONArray list = new JSONArray();
            list.put(vod);
            JSONObject result = new JSONObject();
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            log("detail: " + e.getMessage());
            e.printStackTrace();
            return "{\"list\":[]}";
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            int[] totalArr = new int[1];
            JSONArray list = searchApi("", key, "", null, 1, 24, totalArr);
            int total = totalArr[0];
            JSONObject result = new JSONObject();
            result.put("list", list);
            result.put("page", 1);
            result.put("pagecount", total > 0 ? Math.max(1, (int) Math.ceil(total / 24.0)) : 1);
            result.put("limit", 24);
            result.put("total", total);
            return result.toString();
        } catch (Exception e) {
            log("search: " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            JSONObject header = new JSONObject();
            header.put("User-Agent", UA);
            header.put("Referer", HOST + "/");

            JSONObject result = new JSONObject();
            result.put("parse", 0);
            result.put("url", id);
            result.put("header", header);
            return result.toString();
        } catch (Exception e) {
            return "{\"parse\":1,\"url\":\"" + id + "\"}";
        }
    }
}
