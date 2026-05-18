package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 4k影视
 * 站点: https://www.4kvm.me
 */
public class FourKVM extends Spider {

    private String host = "https://www.4kvm.me";
    private OkHttpClient client;
    private Map<String, String> headers;

    public FourKVM() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();

        this.headers = new HashMap<>();
        this.headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
        this.headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        this.headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        this.headers.put("Referer", host);
    }

    @Override
    public void init(Context context, String extend) {
        SpiderDebug.log("[4k影视] init called");
    }

    private String get(String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .headers(Headers.of(headers))
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return response.body().string();
            }
            return null;
        }
    }

    /**
     * 去除重复的标题（如 '红猪 红猪' -> '红猪'）
     */
    private String cleanTitle(String rawTitle) {
        if (rawTitle == null || rawTitle.isEmpty()) {
            return "";
        }
        rawTitle = rawTitle.trim();
        // 匹配 "标题 标题" 格式
        Pattern p1 = Pattern.compile("^(.*?)[\\s:：]+(\\1)$");
        Matcher m1 = p1.matcher(rawTitle);
        if (m1.find()) {
            return m1.group(1);
        }
        // 匹配 "标题 标题"（空格分隔）
        if (rawTitle.contains(" ")) {
            String[] parts = rawTitle.split(" ", 2);
            if (parts.length == 2 && parts[0].equals(parts[1])) {
                return parts[0];
            }
        }
        return rawTitle;
    }

    // ================== 筛选器选项 ==================

    private JsonArray getAreasOptions() {
        JsonArray options = new JsonArray();
        String[][] areas = {
            {"全部地区", ""}, {"中国", "7"}, {"美国", "5"}, {"日本", "11"},
            {"韩国", "12"}, {"英国", "30"}, {"法国", "6"}, {"德国", "18"},
            {"意大利", "19"}, {"西班牙", "24"}, {"加拿大", "32"}, {"澳大利亚", "22"},
            {"俄罗斯", "16"}, {"印度", "34"}, {"泰国", "33"}, {"中国香港", "14"},
            {"中国台湾", "21"}, {"巴西", "26"}, {"阿根廷", "27"}
        };
        for (String[] area : areas) {
            JsonObject opt = new JsonObject();
            opt.addProperty("n", area[0]);
            opt.addProperty("v", area[1]);
            options.add(opt);
        }
        return options;
    }

    private JsonArray getTvClassesOptions() {
        JsonArray options = new JsonArray();
        String[][] tvClasses = {
            {"全部类型", ""}, {"国产剧", "20"}, {"美剧", "21"}, {"韩剧", "22"},
            {"日剧", "23"}, {"泰剧", "24"}, {"日番", "25"}, {"国漫", "26"}
        };
        for (String[] tvClass : tvClasses) {
            JsonObject opt = new JsonObject();
            opt.addProperty("n", tvClass[0]);
            opt.addProperty("v", tvClass[1]);
            options.add(opt);
        }
        return options;
    }

    private JsonArray getTypesOptions() {
        JsonArray options = new JsonArray();
        String[][] types = {
            {"全部类型", ""}, {"剧情", "1"}, {"悬疑", "2"}, {"恐怖", "3"},
            {"惊悚", "4"}, {"喜剧", "5"}, {"爱情", "6"}, {"科幻", "14"},
            {"动作", "10"}, {"冒险", "18"}, {"犯罪", "9"}, {"动画", "11"},
            {"奇幻", "12"}, {"音乐", "13"}, {"历史", "15"}, {"战争", "16"},
            {"家庭", "19"}, {"纪录", "20"}, {"西部", "23"}, {"情色", "25"},
            {"真人秀", "26"}, {"古装", "27"}, {"传记", "28"}, {"同性", "29"},
            {"运动", "30"}, {"武侠", "31"}, {"歌舞", "32"}, {"灾难", "34"},
            {"短片", "35"}
        };
        for (String[] type : types) {
            JsonObject opt = new JsonObject();
            opt.addProperty("n", type[0]);
            opt.addProperty("v", type[1]);
            options.add(opt);
        }
        return options;
    }

    private JsonObject createFilter(String key, String name, JsonArray value) {
        JsonObject filter = new JsonObject();
        filter.addProperty("key", key);
        filter.addProperty("name", name);
        filter.add("value", value);
        return filter;
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            JsonObject result = new JsonObject();
            JsonArray classes = new JsonArray();

            // 自定义默认筛选分类：国产剧（电视剧分类=国产剧）
            JsonObject guochanju = new JsonObject();
            guochanju.addProperty("type_name", "国产剧");
            guochanju.addProperty("type_id", "2|tvclasses=20");
            classes.add(guochanju);

            JsonObject movie = new JsonObject();
            movie.addProperty("type_name", "电影");
            movie.addProperty("type_id", "1");
            classes.add(movie);

            JsonObject tv = new JsonObject();
            tv.addProperty("type_name", "电视剧");
            tv.addProperty("type_id", "2");
            classes.add(tv);

            JsonObject variety = new JsonObject();
            variety.addProperty("type_name", "综艺");
            variety.addProperty("type_id", "4");
            classes.add(variety);

            result.add("class", classes);

            if (filter) {
                JsonObject filters = new JsonObject();

                // 电影筛选器
                JsonArray movieFilters = new JsonArray();
                movieFilters.add(createFilter("areas", "地区", getAreasOptions()));
                movieFilters.add(createFilter("types", "类型", getTypesOptions()));
                filters.add("1", movieFilters);

                // 电视剧筛选器
                JsonArray tvFilters = new JsonArray();
                tvFilters.add(createFilter("areas", "地区", getAreasOptions()));
                tvFilters.add(createFilter("tvclasses", "电视剧分类", getTvClassesOptions()));
                tvFilters.add(createFilter("types", "类型", getTypesOptions()));
                filters.add("2", tvFilters);

                // 综艺筛选器
                JsonArray varietyFilters = new JsonArray();
                varietyFilters.add(createFilter("areas", "地区", getAreasOptions()));
                varietyFilters.add(createFilter("types", "类型", getTypesOptions()));
                filters.add("4", varietyFilters);

                result.add("filters", filters);
            }

            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log("[4k影视] homeContent error: " + e.getMessage());
            return "{\"class\":[], \"filters\":{}}";
        }
    }

    /**
     * 解析可能带参数的 tid（格式：真实tid|参数串）
     */
    private Map<String, String> parseTidParams(String tid) {
        Map<String, String> defaultParams = new HashMap<>();
        if (tid != null && tid.contains("|")) {
            String[] parts = tid.split("\\|", 2);
            String paramStr = parts[1];
            for (String pair : paramStr.split("&")) {
                if (pair.contains("=")) {
                    String[] kv = pair.split("=", 2);
                    defaultParams.put(kv[0], kv[1]);
                }
            }
        }
        return defaultParams;
    }

    private String extractRealTid(String tid) {
        if (tid != null && tid.contains("|")) {
            return tid.split("\\|", 2)[0];
        }
        return tid;
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            // 解析可能带参数的 tid
            Map<String, String> defaultParams = parseTidParams(tid);
            String realTid = extractRealTid(tid);

            // 构建基础 URL
            String url = host + "/filter?classify=" + realTid + "&page=" + pg;

            // 合并默认参数和 extend 参数（extend 优先）
            Map<String, String> params = new HashMap<>(defaultParams);
            if (extend != null) {
                params.putAll(extend);
            }

            if (params.containsKey("areas") && !params.get("areas").isEmpty()) {
                url += "&areas=" + params.get("areas");
            }
            if (params.containsKey("tvclasses") && !params.get("tvclasses").isEmpty()) {
                url += "&tvclasses=" + params.get("tvclasses");
            }
            if (params.containsKey("types") && !params.get("types").isEmpty()) {
                url += "&types=" + params.get("types");
            }

            SpiderDebug.log("[4k影视] category URL: " + url);

            String html = get(url);
            if (html == null) {
                return emptyCategoryResult(pg);
            }

            Document doc = Jsoup.parse(html);
            JsonArray videos = new JsonArray();

            // 卡片选择器兼容 .movie-card 和 .group
            Elements cards = doc.select(".movie-card");
            if (cards.isEmpty()) {
                cards = doc.select(".group");
            }

            for (Element card : cards) {
                Element link = card.selectFirst("a[href^=\"/play/\"]");
                if (link == null) {
                    continue;
                }

                String href = link.attr("href");
                String vodId = href.substring(href.lastIndexOf("/") + 1);

                Element titleElem = card.selectFirst("h3");
                String rawTitle = titleElem != null ? titleElem.text().trim() : "";
                String title = cleanTitle(rawTitle);

                Element img = card.selectFirst("img");
                String pic = "";
                if (img != null) {
                    pic = img.attr("data-src");
                    if (pic == null || pic.isEmpty()) {
                        pic = img.attr("src");
                    }
                }
                if (pic != null && !pic.isEmpty() && !pic.startsWith("http")) {
                    pic = host + pic;
                }

                Element remarkElem = card.selectFirst("span.absolute.bottom-0");
                if (remarkElem == null) {
                    remarkElem = card.selectFirst(".remark");
                }
                String remark = remarkElem != null ? remarkElem.text().trim() : "";

                JsonObject vod = new JsonObject();
                vod.addProperty("vod_id", vodId);
                vod.addProperty("vod_name", title);
                vod.addProperty("vod_pic", pic != null ? pic : "");
                vod.addProperty("vod_remarks", remark);
                videos.add(vod);
            }

            // 分页估算
            int currentPage = Integer.parseInt(pg);
            int pagecount = currentPage + 5;
            pagecount = Math.min(pagecount, 20);
            int limit = videos.size();
            int total = limit * pagecount;

            JsonObject result = new JsonObject();
            result.add("list", videos);
            result.addProperty("page", currentPage);
            result.addProperty("pagecount", pagecount);
            result.addProperty("limit", limit);
            result.addProperty("total", total);
            return result.toString();

        } catch (Exception e) {
            SpiderDebug.log("[4k影视] categoryContent error: " + e.getMessage());
            return emptyCategoryResult(pg);
        }
    }

    private String emptyCategoryResult(String pg) {
        int page = 1;
        try {
            page = Integer.parseInt(pg);
        } catch (NumberFormatException ignored) {}
        JsonObject result = new JsonObject();
        result.add("list", new JsonArray());
        result.addProperty("page", page);
        result.addProperty("pagecount", 1);
        result.addProperty("limit", 0);
        result.addProperty("total", 0);
        return result.toString();
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            if (ids == null || ids.isEmpty()) {
                return "{\"list\":[]}";
            }

            String vodId = ids.get(0);
            String url = host + "/play/" + vodId;
            SpiderDebug.log("[4k影视] detail URL: " + url);

            String html = get(url);
            if (html == null) {
                return "{\"list\":[]}";
            }

            Document doc = Jsoup.parse(html);

            // 标题
            Element titleElem = doc.selectFirst("h1");
            String rawTitle = titleElem != null ? titleElem.text().trim() : "";
            String title = cleanTitle(rawTitle);

            // 海报
            Element img = doc.selectFirst(".movie-poster img");
            String pic = "";
            if (img != null) {
                pic = img.attr("src");
            }
            if (pic != null && !pic.isEmpty() && !pic.startsWith("http")) {
                pic = host + pic;
            }

            // 导演、演员、地区、年份
            String director = "", actor = "", area = "", year = "";
            Elements infoItems = doc.select(".bg-dark-800.rounded-lg.p-3 .grid");
            for (Element item : infoItems) {
                Elements cells = item.select(".col-span-1, .col-span-2");
                List<Element> cellList = cells.subList(0, cells.size());
                for (int i = 0; i < cellList.size() - 1; i += 2) {
                    String key = cellList.get(i).text().trim();
                    String val = cellList.get(i + 1).text().trim();
                    if (key.contains("导演")) {
                        director = val;
                    } else if (key.contains("主演")) {
                        actor = val;
                    } else if (key.contains("地区")) {
                        area = val;
                    } else if (key.contains("年份")) {
                        year = val;
                    }
                }
            }

            // 简介
            Element descElem = doc.selectFirst(".bg-dark-800.rounded-lg.p-3 p");
            String content = descElem != null ? descElem.text().trim() : "";

            // 剧集列表（默认线路）
            List<String> playFromList = new ArrayList<>();
            List<String> playUrlList = new ArrayList<>();
            Elements episodeLinks = doc.select(".episode-link");

            if (!episodeLinks.isEmpty()) {
                String lineName = "4K影视";
                List<String> episodes = new ArrayList<>();
                for (Element a : episodeLinks) {
                    String epName = a.text().trim();
                    String epLink = a.attr("href");
                    if (epLink != null && !epLink.isEmpty()) {
                        String fullLink = epLink.startsWith("http") ? epLink : host + epLink;
                        episodes.add(epName + "$" + fullLink);
                    }
                }
                if (!episodes.isEmpty()) {
                    playFromList.add(lineName);
                    playUrlList.add(String.join("#", episodes));
                }
            }

            JsonObject vod = new JsonObject();
            vod.addProperty("vod_id", vodId);
            vod.addProperty("vod_name", title);
            vod.addProperty("vod_pic", pic);
            vod.addProperty("vod_director", director);
            vod.addProperty("vod_actor", actor);
            vod.addProperty("vod_area", area);
            vod.addProperty("vod_year", year);
            vod.addProperty("vod_content", content);
            vod.addProperty("vod_play_from", String.join("$$$", playFromList));
            vod.addProperty("vod_play_url", String.join("$$$", playUrlList));

            JsonArray list = new JsonArray();
            list.add(vod);
            JsonObject result = new JsonObject();
            result.add("list", list);
            return result.toString();

        } catch (Exception e) {
            SpiderDebug.log("[4k影视] detailContent error: " + e.getMessage());
            e.printStackTrace();
            return "{\"list\":[]}";
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String url = host + "/search?q=" + URLEncoder.encode(key, "UTF-8");
            SpiderDebug.log("[4k影视] search URL: " + url);

            String html = get(url);
            if (html == null) {
                return "{\"list\":[]}";
            }

            Document doc = Jsoup.parse(html);
            JsonArray videos = new JsonArray();

            for (Element item : doc.select(".group")) {
                Element a = item.selectFirst("a[href^=\"/play/\"]");
                if (a == null) {
                    continue;
                }

                String href = a.attr("href");
                String vodId = href.substring(href.lastIndexOf("/") + 1);

                Element titleElem = item.selectFirst("h3");
                String rawTitle = titleElem != null ? titleElem.text().trim() : "";
                String title = cleanTitle(rawTitle);

                Element img = item.selectFirst("img");
                String pic = "";
                if (img != null) {
                    pic = img.attr("data-src");
                    if (pic == null || pic.isEmpty()) {
                        pic = img.attr("src");
                    }
                }
                if (pic != null && !pic.isEmpty() && !pic.startsWith("http")) {
                    pic = host + pic;
                }

                JsonObject vod = new JsonObject();
                vod.addProperty("vod_id", vodId);
                vod.addProperty("vod_name", title);
                vod.addProperty("vod_pic", pic != null ? pic : "");
                vod.addProperty("vod_remarks", "");
                videos.add(vod);
            }

            JsonObject result = new JsonObject();
            result.add("list", videos);
            return result.toString();

        } catch (Exception e) {
            SpiderDebug.log("[4k影视] searchContent error: " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        SpiderDebug.log("[4k影视] playerContent flag=" + flag + ", id=" + id);

        try {
            String url = id.startsWith("http") ? id : host + id;
            String html = get(url);
            if (html == null) {
                JsonObject result = new JsonObject();
                result.addProperty("parse", 1);
                result.addProperty("url", url);
                JsonObject header = new JsonObject();
                header.addProperty("User-Agent", headers.get("User-Agent"));
                header.addProperty("Referer", url);
                header.addProperty("Origin", host);
                result.add("header", header);
                return result.toString();
            }

            // 多种正则匹配视频链接
            String[] patterns = {
                "<video[^>]+src=\"([^\"]+)\"",
                "<source[^>]+src=\"([^\"]+)\"",
                "(?:var|let|const)\\s+videoUrl\\s*=\\s*[\"']([^\"']+)[\"']",
                "(?:var|let|const)\\s+url\\s*=\\s*[\"']([^\"']+\\.m3u8)[\"']",
                "\"url\"\\s*:\\s*\"([^\"]+\\.m3u8)\"",
                "([^\"']+\\.m3u8[^\"']*)"
            };

            for (String patternStr : patterns) {
                Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(html);
                if (matcher.find()) {
                    String videoUrl = matcher.group(1);
                    if (videoUrl.startsWith("//")) {
                        videoUrl = "https:" + videoUrl;
                    } else if (videoUrl.startsWith("/")) {
                        videoUrl = host + videoUrl;
                    }
                    SpiderDebug.log("[4k影视] 找到视频链接: " + videoUrl);
                    JsonObject result = new JsonObject();
                    result.addProperty("parse", 0);
                    result.addProperty("url", videoUrl);
                    JsonObject header = new JsonObject();
                    header.addProperty("User-Agent", headers.get("User-Agent"));
                    header.addProperty("Referer", url);
                    header.addProperty("Origin", host);
                    result.add("header", header);
                    return result.toString();
                }
            }

            // 未匹配到视频链接，返回 parse=1 让壳子嗅探
            JsonObject result = new JsonObject();
            result.addProperty("parse", 1);
            result.addProperty("url", url);
            JsonObject header = new JsonObject();
            header.addProperty("User-Agent", headers.get("User-Agent"));
            header.addProperty("Referer", url);
            header.addProperty("Origin", host);
            result.add("header", header);
            return result.toString();

        } catch (Exception e) {
            SpiderDebug.log("[4k影视] playerContent error: " + e.getMessage());
            JsonObject result = new JsonObject();
            result.addProperty("parse", 1);
            result.addProperty("url", id);
            JsonObject header = new JsonObject();
            header.addProperty("User-Agent", headers.get("User-Agent"));
            header.addProperty("Referer", host + "/");
            header.addProperty("Origin", host);
            result.add("header", header);
            return result.toString();
        }
    }

    public boolean isVideoCast() {
        return true;
    }
}
