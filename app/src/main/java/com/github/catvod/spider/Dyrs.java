package com.github.catvod.spider;

import android.text.TextUtils;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.bean.Filter;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Dyrs extends Spider {

    private String host = "https://dyrs1.vip";
    private final HashMap<String, String> headers = new HashMap<>();

    public Dyrs() {
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
        headers.put("Referer", host);
    }

    private void ensureHost() {
        try {
            String html = OkHttp.string(host + "/", headers);
            if (html.contains("电影人生") || html.contains("dyrs")) return;
        } catch (Exception ignored) {}
        fetchBackupHost();
    }

    private void fetchBackupHost() {
        try {
            String json = OkHttp.string("https://ysgcw.cc/api/videox/least", headers);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String newHost = null;
            if (obj.has("least")) newHost = obj.get("least").getAsString();
            if (TextUtils.isEmpty(newHost) && obj.has("urls")) {
                JsonArray arr = obj.getAsJsonArray("urls");
                for (JsonElement e : arr) {
                    String u = e.getAsString();
                    if (u.startsWith("http")) {
                        newHost = u;
                        break;
                    }
                }
            }
            if (!TextUtils.isEmpty(newHost)) {
                host = newHost.replaceAll("/+$", "");
                headers.put("Referer", host);
            }
        } catch (Exception ignored) {}
    }

    private String fixPic(String pic) {
        if (TextUtils.isEmpty(pic)) return "";
        if (pic.startsWith("http")) return pic;
        return host + (pic.startsWith("/") ? pic : "/" + pic);
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        ensureHost();
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("dianshiju", "电视剧"));
        classes.add(new Class("dianying", "电影"));
        classes.add(new Class("zongyi", "综艺"));
        classes.add(new Class("duanju", "短剧"));

        Result result = new Result().classes(classes);
        if (filter) {
            result.filters(getFilterConfig());
        }
        return result.toString();
    }

    private LinkedHashMap<String, List<Filter>> getFilterConfig() {
        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();

        List<Filter.Value> yearValues = new ArrayList<>();
        yearValues.add(new Filter.Value("全部", ""));
        for (int i = 2026; i >= 2000; i--) {
            yearValues.add(new Filter.Value(String.valueOf(i), String.valueOf(i)));
        }

        List<Filter.Value> sortValues = Arrays.asList(
                new Filter.Value("默认", ""),
                new Filter.Value("热度", "play_hot"),
                new Filter.Value("年份", "year")
        );

        filters.put("dianshiju", Arrays.asList(
                new Filter("class", "分类", getTvClass()),
                new Filter("area", "地区", getTvArea()),
                new Filter("year", "年份", yearValues),
                new Filter("sort", "排序", sortValues)
        ));

        filters.put("dianying", Arrays.asList(
                new Filter("class", "分类", getMovieClass()),
                new Filter("area", "地区", getMovieArea()),
                new Filter("year", "年份", yearValues),
                new Filter("sort", "排序", sortValues)
        ));

        filters.put("zongyi", Arrays.asList(
                new Filter("class", "分类", getVarietyClass()),
                new Filter("area", "地区", getVarietyArea()),
                new Filter("year", "年份", yearValues),
                new Filter("sort", "排序", sortValues)
        ));

        filters.put("duanju", Arrays.asList(
                new Filter("class", "分类", getShortClass()),
                new Filter("area", "地区", getShortArea()),
                new Filter("year", "年份", yearValues),
                new Filter("sort", "排序", sortValues)
        ));

        return filters;
    }

    private List<Filter.Value> getTvClass() {
        return Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("剧情", "剧情"), new Filter.Value("爱情", "爱情"),
                new Filter.Value("喜剧", "喜剧"), new Filter.Value("悬疑", "悬疑"), new Filter.Value("犯罪", "犯罪"),
                new Filter.Value("古装", "古装"), new Filter.Value("惊悚", "惊悚"), new Filter.Value("奇幻", "奇幻"),
                new Filter.Value("动作", "动作"), new Filter.Value("家庭", "家庭"), new Filter.Value("都市", "都市")
        );
    }

    private List<Filter.Value> getTvArea() {
        return Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("内地", "内地"), new Filter.Value("美国", "美国"),
                new Filter.Value("中国香港", "中国香港"), new Filter.Value("日本", "日本"), new Filter.Value("韩国", "韩国")
        );
    }

    private List<Filter.Value> getMovieClass() {
        return Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("剧情", "剧情"), new Filter.Value("喜剧", "喜剧"),
                new Filter.Value("动作", "动作"), new Filter.Value("爱情", "爱情"), new Filter.Value("惊悚", "惊悚"),
                new Filter.Value("犯罪", "犯罪"), new Filter.Value("悬疑", "悬疑"), new Filter.Value("恐怖", "恐怖")
        );
    }

    private List<Filter.Value> getMovieArea() {
        return Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("美国", "美国"), new Filter.Value("内地", "内地"),
                new Filter.Value("中国香港", "中国香港"), new Filter.Value("日本", "日本"), new Filter.Value("韩国", "韩国")
        );
    }

    private List<Filter.Value> getVarietyClass() {
        return Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("真人秀", "真人秀"), new Filter.Value("大陆综艺", "大陆综艺"),
                new Filter.Value("综艺", "综艺"), new Filter.Value("纪录片", "纪录片")
        );
    }

    private List<Filter.Value> getVarietyArea() {
        return Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("大陆", "大陆"), new Filter.Value("内地", "内地"),
                new Filter.Value("美国", "美国"), new Filter.Value("韩国", "韩国")
        );
    }

    private List<Filter.Value> getShortClass() {
        return Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("短剧", "短剧"), new Filter.Value("剧情", "剧情"),
                new Filter.Value("爱情", "爱情"), new Filter.Value("古装", "古装"), new Filter.Value("玄幻", "玄幻")
        );
    }

    private List<Filter.Value> getShortArea() {
        return Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("中国大陆", "中国大陆"), new Filter.Value("内地", "内地"),
                new Filter.Value("韩国", "韩国"), new Filter.Value("日本", "日本")
        );
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        ensureHost();
        String area = extend.getOrDefault("area", "");
        String cls = extend.getOrDefault("class", "");
        String year = extend.getOrDefault("year", "");
        String sort = extend.getOrDefault("sort", "play_hot");

        StringBuilder sb = new StringBuilder(host).append("/").append(tid).append(".html?");
        if (!area.isEmpty()) sb.append("area=").append(URLEncoder.encode(area, "UTF-8")).append("&");
        if (!cls.isEmpty()) sb.append("class=").append(URLEncoder.encode(cls, "UTF-8")).append("&");
        if (!year.isEmpty()) sb.append("year=").append(URLEncoder.encode(year, "UTF-8")).append("&");
        if (!sort.isEmpty()) sb.append("sort_field=").append(URLEncoder.encode(sort, "UTF-8")).append("&");
        if (!"1".equals(pg)) sb.append("page=").append(pg);

        String url = sb.toString().replaceAll("&$", "");
        String html = OkHttp.string(url, headers);
        Document doc = Jsoup.parse(html);

        List<Vod> list = new ArrayList<>();
        Elements items = doc.select("div.group.relative");
        for (Element item : items) {
            Element a = item.selectFirst("a[title]");
            if (a == null) continue;
            Element img = item.selectFirst("img");
            Vod vod = new Vod();
            vod.setVodId(a.attr("href"));
            vod.setVodName(a.attr("title"));
            String pic = (img != null) ? img.attr("data-src") != null ? img.attr("data-src") : img.attr("src") : "";
            vod.setVodPic(fixPic(pic));
            Element remark = item.selectFirst("div.top-2");
            vod.setVodRemarks(remark != null ? remark.text().trim() : "");
            list.add(vod);
        }
        return Result.string(list);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        ensureHost();
        String detailUrl = ids.get(0).startsWith("http") ? ids.get(0) : host + ids.get(0);
        String html = OkHttp.string(detailUrl, headers);
        Document doc = Jsoup.parse(html);

        Vod vod = new Vod();
        vod.setVodId(ids.get(0));

        String vodName = "";
        String vodPic = "";
        String vodYear = "";
        String vodArea = "";
        String vodContent = "";

        // JSON-LD 解析
        Element ldJson = doc.selectFirst("script[type=application/ld+json]");
        if (ldJson != null) {
            try {
                JsonObject jd = JsonParser.parseString(ldJson.html()).getAsJsonObject();
                vodName = jd.has("name") ? jd.get("name").getAsString() : "";
                vodPic = jd.has("image") ? fixPic(jd.get("image").getAsString()) : "";
                if (jd.has("releaseDate")) {
                    String date = jd.get("releaseDate").getAsString();
                    vodYear = date.length() >= 4 ? date.substring(0, 4) : "";
                }
                vodArea = jd.has("countryOfOrigin") ? jd.get("countryOfOrigin").getAsString() : "";
                vodContent = jd.has("description") ? jd.get("description").getAsString() : "";
            } catch (Exception ignored) {}
        }

        // HTML 补充
        if (TextUtils.isEmpty(vodName)) {
            Element titleEl = doc.selectFirst("h3[title]");
            vodName = titleEl != null ? titleEl.attr("title") : "未知";
        }
        if (TextUtils.isEmpty(vodPic)) {
            Element img = doc.selectFirst("img.lazy-image");
            String pic = img != null ? (img.attr("data-src") != null ? img.attr("data-src") : img.attr("src")) : "";
            vodPic = fixPic(pic);
        }
        if (TextUtils.isEmpty(vodContent)) {
            Element cont = doc.selectFirst("div.text-justify");
            vodContent = cont != null ? cont.text().trim() : "";
        }

        vod.setVodName(vodName);
        vod.setVodPic(vodPic);
        vod.setVodYear(vodYear);
        vod.setVodArea(vodArea);
        vod.setVodContent(vodContent);

        // 播放线路解析
        Elements tabs = doc.select("#originTabs a");
        List<String> fromList = new ArrayList<>();
        List<String> urlList = new ArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(8, tabs.size()));
        for (Element tab : tabs) {
            executor.execute(() -> {
                try {
                    Map<String, String> line = parseLine(tab);
                    if (line != null && !TextUtils.isEmpty(line.get("url"))) {
                        synchronized (fromList) {
                            fromList.add(line.get("from"));
                            urlList.add(line.get("url"));
                        }
                    }
                } catch (Exception ignored) {}
            });
        }
        executor.shutdown();
        try {
            executor.awaitTermination(12, TimeUnit.SECONDS);
        } catch (Exception ignored) {}

        vod.setVodPlayFrom(TextUtils.join("$$$", fromList));
        vod.setVodPlayUrl(TextUtils.join("$$$", urlList));

        return Result.string(vod);
    }

    private Map<String, String> parseLine(Element tab) {
        try {
            Element btn = tab.selectFirst("button");
            String fromName = btn != null ? btn.attr("data-origin") : tab.text().trim();

            String lineUrl = host + tab.attr("href");
            String respHtml = OkHttp.string(lineUrl, headers);

            Pattern pattern = Pattern.compile("dyrs_vod_list\\s*=\\s*JSON\\.parse\\('(.*?)'\\);", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(respHtml);
            if (!matcher.find()) return null;

            String raw = matcher.group(1).replace("\\/", "/");
            JsonArray epData = JsonParser.parseString(raw).getAsJsonArray();

            List<String> urls = new ArrayList<>();
            for (JsonElement e : epData) {
                JsonObject ep = e.getAsJsonObject();
                String title = ep.has("title") ? ep.get("title").getAsString() : "正片";
                String url = ep.has("url") ? ep.get("url").getAsString() : "";

                if (!url.startsWith("http")) {
                    if (url.startsWith("//")) url = "https:" + url;
                    else if (url.startsWith("/")) url = host + url;
                    else url = host + "/" + url;
                }
                urls.add(title + "$" + url);
            }
            return Map.of("from", fromName, "url", TextUtils.join("#", urls));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return Result.get().url(id).parse(0).header(headers).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        ensureHost();
        String searchUrl = host + "/s.html?name=" + URLEncoder.encode(key, "UTF-8");
        String html = OkHttp.string(searchUrl, headers);
        Document doc = Jsoup.parse(html);

        List<Vod> list = new ArrayList<>();
        Element item = doc.selectFirst("div#image-grid div.group.relative");
        if (item != null) {
            Element a = item.selectFirst("a[title]");
            if (a != null) {
                Vod vod = new Vod();
                vod.setVodId(a.attr("href"));
                vod.setVodName(a.attr("title"));
                Element img = item.selectFirst("img");
                String pic = img != null ? (img.attr("data-src") != null ? img.attr("data-src") : img.attr("src")) : "";
                vod.setVodPic(fixPic(pic));
                Element remark = item.selectFirst("div.top-2");
                vod.setVodRemarks(remark != null ? remark.text().trim() : "");
                list.add(vod);
            }
        }
        return Result.string(list);
    }
}
