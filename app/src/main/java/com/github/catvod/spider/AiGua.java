package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AiGua extends Spider {

    private static final String HOST = "https://aigua8.com";
    private static final String API_CATE = HOST + "/video/refresh-cate";
    private static final String API_PLAY = HOST + "/video/play-url";

    // 分类 id → 名称
    private static final String[][] CHANNELS = {
            {"2", "电视剧"},
            {"1", "电影"},
            {"3", "综艺"},
            {"4", "动漫"},
            {"32", "纪录片"},
    };

    // 播放线路展示顺序：超快(21) → 普快(1) → 如意(19) → 专线(16)
    private static final String[] SOURCE_NAMES = {"超快线路", "普快线路", "如意专线", "专线"};
    private static final String[] SOURCE_IDS = {"21", "1", "19", "16"};

    // 筛选器缓存，必须 LinkedHashMap 才能正确传给 Result.filters()
    private final LinkedHashMap<String, List<Filter>> filterCache = new LinkedHashMap<>();

    // ------------------------------------------------------------------ 工具方法

    /**
     * 基础请求头，不设置 Accept-Encoding
     */
    private Map<String, String> baseHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("Referer", HOST + "/");
        h.put("User-Agent", "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 Chrome/114 Safari/537.36");
        return h;
    }

    /**
     * 播放请求专用的请求头，模拟 PC 浏览器
     */
    private Map<String, String> playHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("Referer", HOST + "/");
        h.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.7727.56 Safari/537.36");
        return h;
    }

    /**
     * 拼接分类列表请求 URL
     */
    private String cateUrl(String channelId, int page, Map<String, String> ext) {
        String tag    = ext.getOrDefault("tag", "");
        String area   = ext.getOrDefault("area", "");
        String year   = ext.getOrDefault("year", "");
        String sort   = ext.getOrDefault("sort", "new");
        String status = ext.getOrDefault("status", "");
        return API_CATE
                + "?page_num=" + page
                + "&sorttype=desc"
                + "&channel_id=" + channelId
                + "&tag=" + ("0".equals(tag) ? "" : tag)
                + "&area=" + ("0".equals(area) ? "" : area)
                + "&year=" + ("0".equals(year) ? "" : year)
                + "&status=" + ("0".equals(status) ? "" : status)
                + "&page_size=24"
                + "&sort=" + sort
                + "&_=" + System.currentTimeMillis();
    }

    /**
     * 解析 API 返回的 JSON 列表为 Vod 集合
     */
    private List<Vod> parseVodList(JSONArray arr) throws Exception {
        List<Vod> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Vod vod = new Vod();
            vod.setVodId(String.valueOf(o.optInt("video_id")));
            vod.setVodName(o.optString("video_name"));
            vod.setVodPic(o.optString("cover"));
            vod.setVodRemarks(o.optString("flag"));
            list.add(vod);
        }
        return list;
    }

    /**
     * 预加载某个分类的筛选器
     */
    private void fetchFilters(String channelId) {
        if (filterCache.containsKey(channelId)) return;
        List<Filter> result = new ArrayList<>();
        try {
            String resp = OkHttp.string(cateUrl(channelId, 1, new HashMap<>()), baseHeaders());
            JSONArray searchBox = new JSONObject(resp)
                    .getJSONObject("data")
                    .getJSONArray("search_box");
            for (int i = 0; i < searchBox.length(); i++) {
                JSONObject box = searchBox.getJSONObject(i);
                String field = box.getString("field");
                if ("channel_id".equals(field) || "source".equals(field)) continue;
                JSONArray vals = box.getJSONArray("list");
                List<Filter.Value> values = new ArrayList<>();
                for (int j = 0; j < vals.length(); j++) {
                    JSONObject v = vals.getJSONObject(j);
                    values.add(new Filter.Value(v.getString("display"), v.get("value").toString()));
                }
                result.add(new Filter(field, box.getString("label"), values));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        filterCache.put(channelId, result);
    }

    /**
     * 解析详情页，构建多线路播放列表
     */
    private void buildPlayUrls(Vod vod, String videoId, Document doc) {
        List<String> fromList = new ArrayList<>();
        List<String> urlList = new ArrayList<>();

        for (int k = 0; k < SOURCE_NAMES.length; k++) {
            String sourceId = SOURCE_IDS[k];
            String sourceName = SOURCE_NAMES[k];
            Elements items = doc.select("li[data-source-id=" + sourceId + "][data-chapter-id]");
            if (items.isEmpty()) continue; // 该线路无剧集，跳过

            List<String> names = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            for (Element li : items) {
                String chapterId = li.attr("data-chapter-id");
                String title = li.select(".select-link").text().trim();
                if (title.isEmpty()) title = String.valueOf(names.size() + 1);
                names.add(title);
                ids.add(videoId + "|" + chapterId);
            }

            StringBuilder lineUrl = new StringBuilder();
            for (int j = 0; j < names.size(); j++) {
                if (j > 0) lineUrl.append("#");
                lineUrl.append(names.get(j)).append("$").append(ids.get(j));
            }

            fromList.add(sourceName);
            urlList.add(lineUrl.toString());
        }

        if (!fromList.isEmpty()) {
            vod.setVodPlayFrom(TextUtils.join("$$$", fromList));
            vod.setVodPlayUrl(TextUtils.join("$$$", urlList));
        }
    }

    // ------------------------------------------------------------------ Spider 核心方法

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        for (String[] ch : CHANNELS) {
            classes.add(new Class(ch[0], ch[1]));
        }

        if (filter) {
            ExecutorService exec = Executors.newFixedThreadPool(CHANNELS.length);
            CountDownLatch latch = new CountDownLatch(CHANNELS.length);
            for (String[] ch : CHANNELS) {
                String tid = ch[0];
                exec.submit(() -> {
                    try { fetchFilters(tid); } finally { latch.countDown(); }
                });
            }
            latch.await();
            exec.shutdown();
        }

        String resp = OkHttp.string(cateUrl("2", 1, new HashMap<>()), baseHeaders());
        JSONArray list = new JSONObject(resp).getJSONObject("data").getJSONArray("list");

        return Result.get()
                .classes(classes)
                .filters(filterCache)
                .vod(parseVodList(list))
                .string();
    }

    @Override
    public String homeVideoContent() throws Exception {
        String resp = OkHttp.string(cateUrl("2", 1, new HashMap<>()), baseHeaders());
        JSONArray list = new JSONObject(resp).getJSONObject("data").getJSONArray("list");
        return Result.get().vod(parseVodList(list)).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) throws Exception {
        int page = pg == null || pg.isEmpty() ? 1 : Integer.parseInt(pg);
        String resp = OkHttp.string(cateUrl(tid, page, extend), baseHeaders());
        JSONObject data = new JSONObject(resp).getJSONObject("data");
        int totalPage = data.optInt("total_page", 1);
        int total = data.optInt("total_count", 0);
        JSONArray list = data.getJSONArray("list");

        return Result.get()
                .page(page, totalPage, 24, total)
                .vod(parseVodList(list))
                .string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String videoId = ids.get(0);
        String html = OkHttp.string(HOST + "/video/detail?video_id=" + videoId, baseHeaders());
        Document doc = Jsoup.parse(html);

        Vod vod = new Vod();
        vod.setVodId(videoId);

        // 标题
        Element titleEl = doc.selectFirst("h1.player-title .title-txt");
        vod.setVodName(titleEl != null ? titleEl.text().trim() : "");

        // 封面 (originalSrc 懒加载)
        Element imgEl = doc.selectFirst(".GNbox-xq-img img[originalSrc]");
        if (imgEl != null) {
            vod.setVodPic(imgEl.attr("originalSrc"));
        }

        // 年份、地区（类型已忽略，因 Vod 无对应字段）
        Elements typeSpans = doc.select(".GNbox-type span");
        String year = "", area = "";
        for (Element span : typeSpans) {
            String text = span.text().trim();
            if (text.matches("\\d{4}")) {
                year = text;
            } else if (text.matches("^[\\u4e00-\\u9fa5]{2,4}$")) {
                area = text;
            }
        }
        if (!year.isEmpty()) vod.setVodYear(year);
        if (!area.isEmpty()) vod.setVodArea(area);

        // 导演、主演、简介
        Element dirSpan = doc.selectFirst(".GNbox-xq-text div:contains(导演) span");
        if (dirSpan != null) vod.setVodDirector(dirSpan.text().trim());

        Element actorSpan = doc.selectFirst(".GNbox-xq-text div:contains(主演) span");
        if (actorSpan != null) vod.setVodActor(actorSpan.text().trim());

        Element descSpan = doc.selectFirst(".GNbox-xq-text div:contains(简介) span");
        if (descSpan != null) vod.setVodContent(descSpan.text().trim());

        // 构建多线路播放列表
        buildPlayUrls(vod, videoId, doc);

        return Result.get().vod(vod).string();
    }

    /**
     * id 格式：videoId|chapterId
     * flag 对应 SOURCE_NAMES 中的线路名称，映射为 sourceId，然后调用 play-url
     * 从返回的 resource_url 对象中取对应 sourceId 的地址
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String[] parts = id.split("\\|", 2);
        String videoId = parts[0];
        String chapterId = parts[1];

        // 根据 flag 获取 sourceId
        String sourceId = "1"; // 默认普快
        for (int i = 0; i < SOURCE_NAMES.length; i++) {
            if (SOURCE_NAMES[i].equals(flag)) {
                sourceId = SOURCE_IDS[i];
                break;
            }
        }

        String apiUrl = API_PLAY
                + "?citycode=AMS"
                + "&page=detail"
                + "&chapterId=" + chapterId
                + "&videoId=" + videoId
                + "&sourceId=" + sourceId;

        String resp = OkHttp.string(apiUrl, baseHeaders());
        JSONObject urlinfo = new JSONObject(resp)
                .getJSONObject("data")
                .getJSONObject("urlinfo");

        // resource_url 是一个对象，key 为 sourceId
        JSONObject resourceUrl = urlinfo.getJSONObject("resource_url");
        String finalUrl = resourceUrl.optString(sourceId);
        if (TextUtils.isEmpty(finalUrl)) {
            // 如果该 sourceId 不存在，取第一个可用的
            JSONArray keys = resourceUrl.names();
            if (keys != null && keys.length() > 0) {
                finalUrl = resourceUrl.getString(keys.getString(0));
            }
        }

        // 播放请求使用 PC 模拟头
        return Result.get().url(finalUrl).header(playHeaders()).string();
    }

    // ------------------------------------------------------------------ 搜索

    private static final String API_SEARCH = HOST + "/video/refresh-video";

    /**
     * 解析搜索结果 HTML
     */
    private List<Vod> parseSearchHtml(String html) {
        List<Vod> list = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        for (Element box : doc.select(".SSbox")) {
            String href = box.select("a.SSjgImg").attr("href");
            String videoId = "";
            int idx = href.indexOf("video_id=");
            if (idx >= 0) videoId = href.substring(idx + 9);
            if (videoId.isEmpty()) continue;

            String pic = box.select("img[originalSrc]").attr("originalSrc");

            StringBuilder title = new StringBuilder();
            for (Element span : box.select(".SSjgName a span")) {
                String t = span.text().trim();
                if (!t.isEmpty()) title.append(t);
            }

            String year = "", actors = "";
            for (Element p : box.select(".SSjg > p")) {
                String text = p.text().trim();
                if (text.startsWith("年份")) year = text.replaceFirst("年份：?", "").trim();
                if (text.startsWith("主演")) {
                    Element first = p.select("span").first();
                    if (first != null) actors = first.text().trim();
                }
            }
            String remarks = year.isEmpty() ? actors : (actors.isEmpty() ? year : year + " " + actors);

            Vod vod = new Vod();
            vod.setVodId(videoId);
            vod.setVodName(title.toString());
            vod.setVodPic(pic);
            vod.setVodRemarks(remarks);
            list.add(vod);
        }
        return list;
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        int page = pg == null || pg.isEmpty() ? 1 : Integer.parseInt(pg);
        String url = API_SEARCH
                + "?page_num=" + page
                + "&sorttype=desc"
                + "&page_size=24"
                + "&tvNum=7"
                + "&sort=new"
                + "&keyword=" + java.net.URLEncoder.encode(key, "UTF-8");
        String html = OkHttp.string(url, baseHeaders());
        List<Vod> list = parseSearchHtml(html);
        int total = list.isEmpty() ? 0 : page * 24 + 1;
        return Result.get()
                .page(page, page + (list.isEmpty() ? 0 : 1), 24, total)
                .vod(list)
                .string();
    }
}
