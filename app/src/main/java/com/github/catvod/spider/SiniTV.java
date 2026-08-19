package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.TmdbUtil;
import com.github.catvod.utils.Util;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SiniTV extends BaseSpider {

    private static final String SITE_URL = "https://sinitv.cc";
    private static final String CATEGORY_URL = SITE_URL + "/vodshow/%s-------%d---.html";
    private static final String DETAIL_URL = SITE_URL + "/voddetail/%s.html";
    private static final String PLAY_URL = SITE_URL + "/vodplay/%s-%d-%d.html";

    // 分类映射
    private static final Map<String, String> CATEGORIES = new LinkedHashMap<>();

    static {
        CATEGORIES.put("1-Tiongkok", "中国电视剧");
        CATEGORIES.put("2-Tiongkok", "中国电影");
        CATEGORIES.put("1", "电视剧");
        CATEGORIES.put("2", "电影");
        CATEGORIES.put("3", "动漫");
        CATEGORIES.put("4", "综艺");
        CATEGORIES.put("5", "纪录片");
    }

    // 语言标记正则
    private static final Pattern SEASON_PATTERN = Pattern.compile("Musim ke (\\d+)", Pattern.CASE_INSENSITIVE);

    @Override
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
        yearValues.add(new Filter.Value("全部", ""));
        for (int y = 2026; y >= 2000; y--) {
            yearValues.add(new Filter.Value(String.valueOf(y), String.valueOf(y)));
        }
        filters.put("year", Arrays.asList(new Filter("year", "年份", yearValues)));

        // 地区筛选
        List<Filter.Value> areaValues = new ArrayList<>();
        areaValues.add(new Filter.Value("全部", ""));
        areaValues.add(new Filter.Value("中国", "Tiongkok"));
        areaValues.add(new Filter.Value("日本", "Jepang"));
        areaValues.add(new Filter.Value("韩国", "Korea"));
        areaValues.add(new Filter.Value("美国", "Amerika"));
        areaValues.add(new Filter.Value("英国", "Inggris"));
        filters.put("area", Arrays.asList(new Filter("area", "地区", areaValues)));

        return filters;
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, Map<String, String> extend) throws Exception {
        int page = TextUtils.isEmpty(pg) ? 1 : Integer.parseInt(pg);

        // 构建分类URL，支持筛选参数
        String url = String.format(CATEGORY_URL, tid, page);
        if (extend != null && !extend.isEmpty()) {
            url = buildFilterUrl(tid, page, extend);
        }

        Document doc = Jsoup.connect(url)
                .userAgent(Util.CHROME)
                .timeout(10000)
                .get();

        List<Vod> vodList = new ArrayList<>();
        Elements items = doc.select("div.public-list-box");

        for (Element item : items) {
            Element link = item.selectFirst("a.public-list-exp");
            if (link == null) continue;

            String href = link.attr("href");
            String vodId = extractIdFromUrl(href);
            String title = link.attr("title");
            String pic = "";
            Element img = link.selectFirst("img");
            if (img != null) {
                pic = img.attr("data-src");
                if (TextUtils.isEmpty(pic)) {
                    pic = img.attr("src");
                }
            }

            // 去除 "Musim ke X" 用于搜索
            String searchTitle = removeSeason(title);

            // 尝试通过TMDB获取更好的封面
            String poster = TmdbUtil.getPosterUrl(searchTitle);
            if (TextUtils.isEmpty(poster) && !TextUtils.isEmpty(pic)) {
                poster = pic.startsWith("http") ? pic : "https:" + pic;
            }

            Vod vod = new Vod(vodId, title, poster);
            vodList.add(vod);
        }

        // 获取分页信息
        int total = vodList.size();
        int pageCount = estimatePageCount(doc);

        return Result.string(page, pageCount, 20, total, vodList);
    }

    private String buildFilterUrl(String tid, int page, Map<String, String> extend) {
        // 格式: /vodshow/1-Tiongkok--area-Jepang--year-2024-------2---.html
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

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return Result.error("缺少ID");

        String vodId = ids.get(0);
        String url = String.format(DETAIL_URL, vodId);

        Document doc = Jsoup.connect(url)
                .userAgent(Util.CHROME)
                .timeout(10000)
                .get();

        Vod vod = new Vod();
        vod.setVodId(vodId);

        // 提取标题
        Element titleEl = doc.selectFirst("div.this-desc-title");
        if (titleEl != null) {
            String title = titleEl.text();
            vod.setVodName(title);
            // 去除季数用于搜索
            String searchTitle = removeSeason(title);
            // 用TMDB增强
            String[] tmdbInfo = TmdbUtil.getInfo(searchTitle);
            if (!TextUtils.isEmpty(tmdbInfo[0]) && !tmdbInfo[0].equals(searchTitle)) {
                vod.setVodName(tmdbInfo[0]);
            }
            if (!TextUtils.isEmpty(tmdbInfo[1])) {
                vod.setVodPic(tmdbInfo[1]);
            }
        }

        // 提取海报
        Element posterEl = doc.selectFirst("div.this-pic-bj img");
        if (posterEl != null && TextUtils.isEmpty(vod.getVodPic())) {
            String poster = posterEl.attr("src");
            if (!TextUtils.isEmpty(poster)) {
                vod.setVodPic(poster.startsWith("http") ? poster : "https:" + poster);
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
        Vod.VodPlayBuilder builder = new Vod.VodPlayBuilder();
        List<Vod.VodPlayBuilder.PlayUrl> playUrls = new ArrayList<>();

        Elements episodes = doc.select("ul.anthology-list-play li a");
        int sourceId = 1;
        for (Element ep : episodes) {
            String epHref = ep.attr("href");
            String epTitle = ep.text();

            Vod.VodPlayBuilder.PlayUrl playUrl = new Vod.VodPlayBuilder.PlayUrl();
            playUrl.flag = "source_" + sourceId;
            playUrl.name = epTitle;
            playUrl.url = epHref;
            playUrls.add(playUrl);
        }

        if (!playUrls.isEmpty()) {
            builder.append("XP", playUrls);
            Vod.VodPlayBuilder.BuildResult result = builder.build();
            vod.setVodPlayFrom(result.vodPlayFrom);
            vod.setVodPlayUrl(result.vodPlayUrl);
        }

        return Result.string(vod);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // id格式: vodplay/48047-1-1.html 或者 48047-1-1
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
                .timeout(10000)
                .get();

        // 提取 player_aaaa 数据
        Elements scripts = doc.select("script");
        String playUrl = null;

        for (Element script : scripts) {
            String html = script.html();
            if (html.contains("player_aaaa")) {
                // 提取 url 字段
                Pattern urlPattern = Pattern.compile("\"url\":\"([^\"]+)\"");
                Matcher matcher = urlPattern.matcher(html);
                if (matcher.find()) {
                    playUrl = matcher.group(1);
                    break;
                }
            }
        }

        if (TextUtils.isEmpty(playUrl)) {
            // 尝试其他方式提取
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
            return Result.error("未找到播放地址");
        }

        // 检查是否为m3u8
        boolean isM3u8 = playUrl.endsWith(".m3u8") || playUrl.contains("master.m3u8");

        Result result = Result.get().url(playUrl);
        if (isM3u8) {
            result.m3u8();
        }

        return result.string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        if (TextUtils.isEmpty(key)) return Result.error("请输入搜索关键词");

        // 搜索API
        String searchUrl = SITE_URL + "/index.php/ajax/suggest.html?mid=1&wd="
                + URLEncoder.encode(key, "UTF-8");

        String json = OkHttp.string(searchUrl);
        if (TextUtils.isEmpty(json)) return Result.error("搜索失败");

        JSONObject obj = new JSONObject(json);
        JSONArray list = obj.optJSONArray("list");

        List<Vod> vodList = new ArrayList<>();
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.getJSONObject(i);
                String vodId = item.optString("vod_id");
                String vodName = item.optString("vod_name");
                String pic = item.optString("pic");

                // 尝试用TMDB增强
                String searchTitle = removeSeason(vodName);
                String[] tmdbInfo = TmdbUtil.getInfo(searchTitle);
                String finalName = TextUtils.isEmpty(tmdbInfo[0]) ? vodName : tmdbInfo[0];
                String finalPic = TextUtils.isEmpty(tmdbInfo[1]) ? pic : tmdbInfo[1];

                Vod vod = new Vod(vodId, finalName, finalPic);
                vodList.add(vod);
            }
        }

        return Result.string(vodList);
    }

    // ====================== 辅助方法 ======================

    /**
     * 从URL中提取ID
     */
    private String extractIdFromUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        Pattern pattern = Pattern.compile("/voddetail/(\\d+)\\.html");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return url.replaceAll("\\D", "");
    }

    /**
     * 从播放URL提取集数
     */
    private String extractEpisodeFromUrl(String url) {
        if (TextUtils.isEmpty(url)) return "1";
        Pattern pattern = Pattern.compile("-(\\d+)\\.html$");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "1";
    }

    /**
     * 去除 "Musim ke X" (印尼语: 第X季)
     */
    private String removeSeason(String title) {
        if (TextUtils.isEmpty(title)) return "";
        Matcher matcher = SEASON_PATTERN.matcher(title);
        if (matcher.find()) {
            return title.substring(0, matcher.start()).trim();
        }
        return title;
    }

    /**
     * 估算总页数
     */
    private int estimatePageCount(Document doc) {
        // 尝试从分页元素获取
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
