package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;
import com.github.catvod.utils.TmdbUtil;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SiniTV extends BaseSpider {

    private static final String SITE_URL = "https://sinitv.cc";
    private static final String CATEGORY_URL = SITE_URL + "/vodshow/%s-------%d---.html";
    private static final String DETAIL_URL = SITE_URL + "/voddetail/%s.html";

    // 分类映射（英文站）
    private static final Map<String, String> CATEGORIES = new LinkedHashMap<>();

    static {
        CATEGORIES.put("1-Tiongkok", "TV Series (China)");
        CATEGORIES.put("2-Tiongkok", "Movies (China)");
        CATEGORIES.put("1", "TV Series");
        CATEGORIES.put("2", "Movies");
        CATEGORIES.put("3", "Anime");
        CATEGORIES.put("4", "Variety Show");
        CATEGORIES.put("5", "Documentary");
    }

    // 季数正则
    private static final Pattern SEASON_PATTERN = Pattern.compile("(Season|Musim|Saison|Temporada)\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EPISODE_PATTERN = Pattern.compile("(E|Episode|EP|S\\d+E)\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    // 本地代理缓存
    private static final ConcurrentHashMap<String, String> proxyCache = new ConcurrentHashMap<>();

    // ====================== Home ======================

    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        for (Map.Entry<String, String> entry : CATEGORIES.entrySet()) {
            classes.add(new Class(entry.getKey(), entry.getValue()));
        }

        if (filter) {
            LinkedHashMap<String, List<Filter>> filters = buildFilters();
            return Result.string(classes, filters);
        }
        return Result.string(classes);
    }

    private LinkedHashMap<String, List<Filter>> buildFilters() {
        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();

        // 年份筛选
        List<Filter.Value> yearValues = new ArrayList<>();
        yearValues.add(new Filter.Value("All", ""));
        for (int y = 2026; y >= 2000; y--) {
            yearValues.add(new Filter.Value(String.valueOf(y), String.valueOf(y)));
        }
        filters.put("year", Arrays.asList(new Filter("year", "Year", yearValues)));

        // 地区筛选
        List<Filter.Value> areaValues = new ArrayList<>();
        areaValues.add(new Filter.Value("All", ""));
        areaValues.add(new Filter.Value("China", "Tiongkok"));
        areaValues.add(new Filter.Value("Japan", "Jepang"));
        areaValues.add(new Filter.Value("Korea", "Korea"));
        areaValues.add(new Filter.Value("USA", "Amerika"));
        areaValues.add(new Filter.Value("UK", "Inggris"));
        filters.put("area", Arrays.asList(new Filter("area", "Region", areaValues)));

        return filters;
    }

    // ====================== Category ======================

    public String categoryContent(String tid, String pg, boolean filter, Map<String, String> extend) throws Exception {
        int page = TextUtils.isEmpty(pg) ? 1 : Integer.parseInt(pg);

        String url = String.format(CATEGORY_URL, tid, page);
        if (extend != null && !extend.isEmpty()) {
            url = buildFilterUrl(tid, page, extend);
        }

        Document doc = Jsoup.connect(url)
                .userAgent(Util.CHROME)
                .timeout(15000)
                .get();

        List<Vod> vodList = new ArrayList<>();
        Elements items = doc.select("div.public-list-box");

        // 使用多线程并发获取海报
        List<Thread> threads = new ArrayList<>();
        List<Vod> syncList = new ArrayList<>();

        for (Element item : items) {
            Element link = item.selectFirst("a.public-list-exp");
            if (link == null) continue;

            String href = link.attr("href");
            String vodId = extractIdFromUrl(href);
            String title = link.attr("title");

            Element img = link.selectFirst("img");
            String pic = "";
            if (img != null) {
                pic = img.attr("data-src");
                if (TextUtils.isEmpty(pic)) {
                    pic = img.attr("src");
                }
            }

            // 去除季数用于搜索
            String searchTitle = removeSeason(title);

            // 先创建基础 Vod 对象
            Vod vod = new Vod(vodId, title, "");
            vodList.add(vod);

            // 多线程获取 TMDB 信息
            Thread t = new Thread(() -> {
                try {
                    String poster = TmdbUtil.getPosterUrl(searchTitle);
                    if (!TextUtils.isEmpty(poster)) {
                        // 通过本地代理获取图片
                        String proxyUrl = Proxy.getUrl() + "?do=getPoster&title=" 
                                + URLEncoder.encode(searchTitle, "UTF-8");
                        vod.setVodPic(proxyUrl);
                    } else if (!TextUtils.isEmpty(pic)) {
                        String proxyPic = pic.startsWith("http") ? pic : "https:" + pic;
                        vod.setVodPic(proxyPic);
                    }
                } catch (Exception e) {
                    // 降级使用原始图片
                    if (!TextUtils.isEmpty(pic)) {
                        vod.setVodPic(pic.startsWith("http") ? pic : "https:" + pic);
                    }
                }
            });
            t.start();
            threads.add(t);
        }

        // 等待所有线程完成（最多5秒）
        for (Thread t : threads) {
            try {
                t.join(5000);
            } catch (InterruptedException ignored) {}
        }

        int total = vodList.size();
        int pageCount = estimatePageCount(doc);

        return Result.string(page, pageCount, 20, total, vodList);
    }

    private String buildFilterUrl(String tid, int page, Map<String, String> extend) {
        StringBuilder sb = new StringBuilder(SITE_URL + "/vodshow/");
        sb.append(tid);

        String area = extend.get("area");
        String year = extend.get("year");

        if (!TextUtils.isEmpty(area) || !TextUtils.isEmpty(year)) {
            sb.append("--");
            if (!TextUtils.isEmpty(area)) sb.append("area-").append(area);
            sb.append("--");
            if (!TextUtils.isEmpty(year)) sb.append("year-").append(year);
        }

        sb.append("-------").append(page).append("---.html");
        return sb.toString();
    }

    // ====================== Detail ======================

    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return Result.error("Missing ID");

        String vodId = ids.get(0);
        String url = String.format(DETAIL_URL, vodId);

        Document doc = Jsoup.connect(url)
                .userAgent(Util.CHROME)
                .timeout(15000)
                .get();

        Vod vod = new Vod();
        vod.setVodId(vodId);

        // 提取标题
        Element titleEl = doc.selectFirst("div.this-desc-title");
        if (titleEl != null) {
            String title = titleEl.text();
            String searchTitle = removeSeason(title);
            
            // 通过本地代理获取中文标题
            String zhTitle = Proxy.getTitle(searchTitle);
            String finalName = TextUtils.isEmpty(zhTitle) ? title : zhTitle;
            vod.setVodName(finalName);

            // 通过本地代理获取海报
            String proxyPoster = Proxy.getPoster(searchTitle);
            if (!TextUtils.isEmpty(proxyPoster)) {
                vod.setVodPic(proxyPoster);
            }
        }

        // 提取海报（如果本地代理没有）
        if (TextUtils.isEmpty(vod.getVodPic())) {
            Element posterEl = doc.selectFirst("div.this-pic-bj img");
            if (posterEl != null) {
                String poster = posterEl.attr("src");
                if (!TextUtils.isEmpty(poster)) {
                    vod.setVodPic(poster.startsWith("http") ? poster : "https:" + poster);
                }
            }
        }

        // 提取年份、地区、状态
        Element infoEl = doc.selectFirst("div.this-desc-info");
        if (infoEl != null) {
            Elements spans = infoEl.select("span");
            if (spans.size() >= 3) {
                vod.setVodYear(spans.get(0).text());
                vod.setVodArea(spans.get(1).text());
                vod.setVodRemarks(spans.get(2).text());
            }
        }

        // 提取演员
        Element actorEl = doc.selectFirst("div.this-info");
        if (actorEl != null) {
            String actorText = actorEl.text().replace("Pemeran:", "").trim();
            vod.setVodActor(actorText);
        }

        // 提取简介
        Element descEl = doc.selectFirst("div#height_limit.text");
        if (descEl != null) {
            String desc = descEl.text().replace("Deskripsi:", "").trim();
            vod.setVodContent(desc);
        }

        // 提取标签
        Elements tags = doc.select("div.this-desc-tags span");
        if (!tags.isEmpty()) {
            StringBuilder tagBuilder = new StringBuilder();
            for (Element tag : tags) {
                tagBuilder.append(tag.text()).append(",");
            }
            if (tagBuilder.length() > 0) {
                tagBuilder.deleteCharAt(tagBuilder.length() - 1);
                vod.setVodTag(tagBuilder.toString());
            }
        }

        // 提取播放列表
        Elements episodes = doc.select("ul.anthology-list-play li a");
        if (!episodes.isEmpty()) {
            Vod.VodPlayBuilder builder = new Vod.VodPlayBuilder();
            List<Vod.VodPlayBuilder.PlayUrl> playUrls = new ArrayList<>();

            for (Element ep : episodes) {
                String epHref = ep.attr("href");
                String epTitle = ep.text();

                Vod.VodPlayBuilder.PlayUrl playUrl = new Vod.VodPlayBuilder.PlayUrl();
                playUrl.flag = "XP";
                playUrl.name = epTitle;
                playUrl.url = epHref;
                playUrls.add(playUrl);
            }

            builder.append("XP", playUrls);
            Vod.VodPlayBuilder.BuildResult result = builder.build();
            vod.setVodPlayFrom(result.vodPlayFrom);
            vod.setVodPlayUrl(result.vodPlayUrl);
        }

        return Result.string(vod);
    }

    // ====================== Player ======================

    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String playId = id;
        if (id.contains("/")) {
            playId = id.substring(id.lastIndexOf("/") + 1);
        }
        if (playId.contains(".html")) {
            playId = playId.replace(".html", "");
        }

        String url = SITE_URL + "/vodplay/" + playId + ".html";

        Document doc = Jsoup.connect(url)
                .userAgent(Util.CHROME)
                .timeout(15000)
                .get();

        String playUrl = null;
        Elements scripts = doc.select("script");

        for (Element script : scripts) {
            String html = script.html();
            if (html.contains("player_aaaa")) {
                Pattern urlPattern = Pattern.compile("\"url\":\"([^\"]+)\"");
                Matcher matcher = urlPattern.matcher(html);
                if (matcher.find()) {
                    playUrl = matcher.group(1);
                    break;
                }
            }
        }

        if (TextUtils.isEmpty(playUrl)) {
            for (Element script : scripts) {
                String html = script.html();
                Pattern urlPattern = Pattern.compile("url:\\s*['\"]([^'\"]+?)['\"]");
                Matcher matcher = urlPattern.matcher(html);
                if (matcher.find()) {
                    playUrl = matcher.group(1);
                    break;
                }
            }
        }

        if (TextUtils.isEmpty(playUrl)) {
            return Result.error("Play URL not found");
        }

        Result result = Result.get().url(playUrl);
        if (playUrl.endsWith(".m3u8") || playUrl.contains("master.m3u8")) {
            result.m3u8();
        }

        return result.string();
    }

    // ====================== Search ======================

    public String searchContent(String key, boolean quick) throws Exception {
        if (TextUtils.isEmpty(key)) return Result.error("Please enter search keyword");

        String searchUrl = SITE_URL + "/index.php/ajax/suggest.html?mid=1&wd="
                + URLEncoder.encode(key, "UTF-8");

        String json = OkHttp.string(searchUrl);
        if (TextUtils.isEmpty(json)) return Result.error("Search failed");

        JSONObject obj = new JSONObject(json);
        JSONArray list = obj.optJSONArray("list");

        List<Vod> vodList = new ArrayList<>();
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.getJSONObject(i);
                String vodId = item.optString("vod_id");
                String vodName = item.optString("vod_name");
                String pic = item.optString("pic");

                String searchTitle = removeSeason(vodName);
                
                // 通过本地代理获取
                String zhTitle = Proxy.getTitle(searchTitle);
                String finalName = TextUtils.isEmpty(zhTitle) ? vodName : zhTitle;

                String proxyPoster = Proxy.getPoster(searchTitle);
                String finalPic = TextUtils.isEmpty(proxyPoster) ? pic : proxyPoster;

                Vod vod = new Vod(vodId, finalName, finalPic);
                vodList.add(vod);
            }
        }

        return Result.string(vodList);
    }

    // ====================== 辅助方法 ======================

    private String extractIdFromUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        Pattern pattern = Pattern.compile("/voddetail/(\\d+)\\.html");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return url.replaceAll("\\D", "");
    }

    private String removeSeason(String title) {
        if (TextUtils.isEmpty(title)) return "";
        Matcher matcher = SEASON_PATTERN.matcher(title);
        if (matcher.find()) {
            return title.substring(0, matcher.start()).trim();
        }
        return title;
    }

    private int estimatePageCount(Document doc) {
        Element pageEl = doc.selectFirst("ul.pagination li:last-child a");
        if (pageEl != null) {
            String href = pageEl.attr("href");
            Pattern pattern = Pattern.compile("---(\\d+)\\.html");
            Matcher matcher = pattern.matcher(href);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        return 1;
    }
}
