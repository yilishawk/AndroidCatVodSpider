package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Y03YY extends Spider {

    private final String host = "https://www.03yy.live";
    private final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    private Map<String, String> getHeader() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", host + "/");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        return headers;
    }

    // ==================== 筛选配置 ====================
    private static final String[][] MOVIE_TYPES = {
            {"全部", ""}, {"动作片", "5"}, {"喜剧片", "10"}, {"爱情片", "6"},
            {"科幻片", "7"}, {"恐怖片", "8"}, {"战争片", "9"}, {"剧情片", "12"}, {"动画片", "25"}
    };

    private static final String[][] TV_TYPES = {
            {"全部", ""}, {"大陆剧", "13"}, {"欧美剧", "27"}, {"韩国剧", "26"},
            {"香港剧", "14"}, {"台湾剧", "46"}, {"日本剧", "16"}, {"泰国剧", "47"}, {"海外剧", "28"}
    };

    private static final String[][] VARIETY_TYPES = {
            {"全部", ""}, {"大陆综艺", "29"}, {"港台综艺", "30"}, {"日韩综艺", "31"}, {"欧美综艺", "32"}
    };

    @Override
    public void init(Context context, String extend) throws Exception {
        OkHttp.string(host + "/", getHeader());
    }

    // ==================== 首页 + 筛选 ====================
    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("1", "电影"));
        classes.add(new Class("13", "大陆剧"));
        classes.add(new Class("3", "综艺"));
        classes.add(new Class("48", "短剧"));

        if (filter) {
            Map<String, List<Filter>> filters = new HashMap<>();

            // 电影筛选
            List<Filter> movieFilter = new ArrayList<>();
            List<Filter.Value> movieValues = new ArrayList<>();
            for (String[] type : MOVIE_TYPES) {
                movieValues.add(new Filter.Value(type[0], type[1]));
            }
            movieFilter.add(new Filter("type", "分类", movieValues));
            filters.put("1", movieFilter);

            // 电视剧筛选
            List<Filter> tvFilter = new ArrayList<>();
            List<Filter.Value> tvValues = new ArrayList<>();
            for (String[] type : TV_TYPES) {
                tvValues.add(new Filter.Value(type[0], type[1]));
            }
            tvFilter.add(new Filter("type", "分类", tvValues));
            filters.put("13", tvFilter);

            // 综艺筛选
            List<Filter> varietyFilter = new ArrayList<>();
            List<Filter.Value> varietyValues = new ArrayList<>();
            for (String[] type : VARIETY_TYPES) {
                varietyValues.add(new Filter.Value(type[0], type[1]));
            }
            varietyFilter.add(new Filter("type", "分类", varietyValues));
            filters.put("3", varietyFilter);

            // 短剧暂时不加细分筛选
            filters.put("48", new ArrayList<>());

            return Result.string(classes, filters);
        }

        return Result.string(classes);
    }

    // ==================== 分类页 ====================
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String type = extend != null && extend.containsKey("type") ? extend.get("type") : "";

        String url;
        if (TextUtils.isEmpty(type)) {
            url = host + "/type/index" + tid + "-" + pg + ".html";
        } else {
            url = host + "/type/index" + type + "-" + pg + ".html";
        }

        String html = OkHttp.string(url, getHeader());
        if (TextUtils.isEmpty(html)) return Result.string(new ArrayList<>());

        Document doc = Jsoup.parse(html);
        Elements items = doc.select(".Pic-list .pic-content");

        List<Vod> list = new ArrayList<>();
        for (Element item : items) {
            Element a = item.selectFirst("a");
            if (a == null) continue;

            String vid = extractVid(a.attr("href"));
            if (TextUtils.isEmpty(vid)) continue;

            String name = a.attr("title");
            String pic = item.selectFirst("img") != null ? item.selectFirst("img").attr("src") : "";
            String remark = item.selectFirst("span") != null ? item.selectFirst("span").text() : "";

            if (!TextUtils.isEmpty(pic) && !pic.startsWith("http")) pic = host + pic;

            Vod vod = new Vod();
            vod.setVodId(vid);
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodRemarks(remark);
            list.add(vod);
        }

        return Result.string(list);
    }

    private String extractVid(String href) {
        if (TextUtils.isEmpty(href)) return "";
        Matcher m = Pattern.compile("/movie/index(\\d+)\\.html").matcher(href);
        return m.find() ? m.group(1) : "";
    }

    // ==================== 详情页 ====================
    @Override
    public String detailContent(List<String> ids) throws Exception {
        // （你的原有代码保持不变）
        if (ids == null || ids.isEmpty()) return Result.string(new Vod());
        String vid = ids.get(0);
        String url = host + "/movie/index" + vid + ".html";
        String html = OkHttp.string(url, getHeader());
        if (TextUtils.isEmpty(html)) return Result.string(new Vod());

        Document doc = Jsoup.parse(html);
        String name = doc.selectFirst("h1") != null ? doc.selectFirst("h1").text().trim() : "";
        String pic = "";
        Element img = doc.selectFirst(".m-pic-l img");
        if (img != null) {
            pic = img.attr("src");
            if (!pic.startsWith("http")) pic = host + pic;
        }

        String director = "", actor = "", area = "", year = "", content = "";
        for (Element li : doc.select(".m-content ul li")) {
            String text = li.text();
            if (text.contains("导演：")) director = text.replace("导演：", "").trim();
            else if (text.contains("主演：")) actor = text.replace("主演：", "").trim();
            else if (text.contains("地区")) area = text.replace("地区：", "").trim();
            else if (text.contains("年份")) year = text.replace("年份：", "").trim();
        }

        Elements intro = doc.select(".m-intro p");
        for (Element p : intro) {
            if (content.length() > 0) content += "\n";
            content += p.text().trim();
        }

        // 播放源
        Map<String, List<String>> playMap = new LinkedHashMap<>();
        Elements lineTabs = doc.select(".playfrom #playlist li");
        for (int i = 0; i < lineTabs.size(); i++) {
            String lineName = lineTabs.get(i).text().trim();
            Element listDiv = doc.selectFirst("#stab8" + (i + 1));
            if (listDiv == null) continue;

            List<String> episodes = new ArrayList<>();
            for (Element a : listDiv.select("ul li a")) {
                String epName = a.text().trim();
                String epUrl = a.attr("href");
                if (!epUrl.startsWith("http")) epUrl = host + epUrl;
                episodes.add(epName + "$" + epUrl);
            }
            if (!episodes.isEmpty()) {
                playMap.put(lineName, episodes);
            }
        }

        Vod vod = new Vod();
        vod.setVodId(vid);
        vod.setVodName(name);
        vod.setVodPic(pic);
        vod.setVodDirector(director);
        vod.setVodActor(actor);
        vod.setVodArea(area);
        vod.setVodYear(year);
        vod.setVodContent(content);
        vod.setVodPlayFrom(String.join("$$$", playMap.keySet()));
        vod.setVodPlayUrl(String.join("$$$", playMap.values().stream().map(v -> String.join("#", v)).toList()));

        return Result.string(vod);
    }

    // ==================== 搜索 ====================
    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        // （你的原有搜索代码保持不变）
        String url = host + "/search.php?searchword=" + URLEncoder.encode(key, "UTF-8");
        String html = OkHttp.string(url, getHeader());
        if (TextUtils.isEmpty(html)) return Result.string(new ArrayList<>());

        Document doc = Jsoup.parse(html);
        Elements items = doc.select(".Pic-list .pic-content");
        List<Vod> list = new ArrayList<>();

        for (Element item : items) {
            Element a = item.selectFirst("a");
            if (a == null) continue;
            String vid = extractVid(a.attr("href"));
            if (TextUtils.isEmpty(vid)) continue;

            String name = a.attr("title");
            String pic = item.selectFirst("img") != null ? item.selectFirst("img").attr("src") : "";
            String remark = item.selectFirst("i") != null ? item.selectFirst("i").text() : "";

            if (!TextUtils.isEmpty(pic) && !pic.startsWith("http")) pic = host + pic;

            Vod vod = new Vod();
            vod.setVodId(vid);
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodRemarks(remark);
            list.add(vod);
        }
        return Result.string(list);
    }

    // ==================== 播放解析（保持不变）===================
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // 你的原有播放代码...
        try {
            String html = OkHttp.string(id, getHeader());
            String pn = extractRegex(html, "var pn=\"([^\"]+)\"");
            String nowBase64 = extractRegex(html, "var now=base64decode\\(\"([^\"]+)\"\\)");
            if (TextUtils.isEmpty(pn) || TextUtils.isEmpty(nowBase64)) {
                return parseFallback(id);
            }
            String now = new String(android.util.Base64.decode(nowBase64, android.util.Base64.DEFAULT));
            String loaderHtml = OkHttp.string(host + "/js/player/" + pn + ".html", getHeader());
            String iframeSrc = extractRegex(loaderHtml, "<iframe[^>]+src=[\"']([^\"']+)[\"']");
            if (TextUtils.isEmpty(iframeSrc)) return parseFallback(id);

            String apiPath = iframeSrc.contains("?") ? iframeSrc.split("\\?")[0] : iframeSrc;
            String apiUrl = host + apiPath + "?url=" + now + "&ref=" + id;
            String apiHtml = OkHttp.string(apiUrl, getHeader());
            String realUrl = extractVideoUrl(apiHtml);
            if (!TextUtils.isEmpty(realUrl)) {
                return Result.get().url(realUrl).string();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return parseFallback(id);
    }

    private String parseFallback(String id) {
        return Result.get().url(id).parse(1).string();
    }

    private String extractVideoUrl(String html) {
        String media = extractRegex(html, "mediaInfo\\s*=\\s*(\\[.*?\\])", Pattern.DOTALL);
        if (!media.isEmpty()) {
            String url = extractRegex(media, "\"url\"\\s*:\\s*\"([^\"]+)\"");
            if (!url.isEmpty()) return url.replace("\\/", "/");
        }
        String videoUrl = extractRegex(html, "videoUrl\\s*=\\s*\"([^\"]+)\"");
        if (!videoUrl.isEmpty()) return videoUrl.replace("\\/", "/");
        return "";
    }

    private String extractRegex(String text, String regex) {
        return extractRegex(text, regex, 0);
    }

    private String extractRegex(String text, String regex, int flags) {
        if (TextUtils.isEmpty(text)) return "";
        Matcher m = Pattern.compile(regex, flags).matcher(text);
        return m.find() ? m.group(1) : "";
    }
}
