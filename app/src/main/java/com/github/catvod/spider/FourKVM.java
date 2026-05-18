package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONObject;
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

    private JSONArray getAreasOptions() throws Exception {
        JSONArray options = new JSONArray();
        String[][] areas = {
            {"全部地区", ""}, {"中国", "7"}, {"美国", "5"}, {"日本", "11"},
            {"韩国", "12"}, {"英国", "30"}, {"法国", "6"}, {"德国", "18"},
            {"意大利", "19"}, {"西班牙", "24"}, {"加拿大", "32"}, {"澳大利亚", "22"},
            {"俄罗斯", "16"}, {"印度", "34"}, {"泰国", "33"}, {"中国香港", "14"},
            {"中国台湾", "21"}, {"巴西", "26"}, {"阿根廷", "27"}
        };
        for (String[] area : areas) {
            JSONObject opt = new JSONObject();
            opt.put("n", area[0]);
            opt.put("v", area[1]);
            options.put(opt);
        }
        return options;
    }

    private JSONArray getTvClassesOptions() throws Exception {
        JSONArray options = new JSONArray();
        String[][] tvClasses = {
            {"全部类型", ""}, {"国产剧", "20"}, {"美剧", "21"}, {"韩剧", "22"},
            {"日剧", "23"}, {"泰剧", "24"}, {"日番", "25"}, {"国漫", "26"}
        };
        for (String[] tvClass : tvClasses) {
            JSONObject opt = new JSONObject();
            opt.put("n", tvClass[0]);
            opt.put("v", tvClass[1]);
            options.put(opt);
        }
        return options;
    }

    private JSONArray getTypesOptions() throws Exception {
        JSONArray options = new JSONArray();
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
            JSONObject opt = new JSONObject();
            opt.put("n", type[0]);
            opt.put("v", type[1]);
            options.put(opt);
        }
        return options;
    }

    private JSONObject createFilter(String key, String name, JSONArray value) throws Exception {
        JSONObject filter = new JSONObject();
        filter.put("key", key);
        filter.put("name", name);
        filter.put("value", value);
        return filter;
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = new JSONArray();

            // 自定义默认筛选分类：国产剧（电视剧分类=国产剧）
            JSONObject guochanju = new JSONObject();
            guochanju.put("type_name", "国产剧");
            guochanju.put("type_id", "2|tvclasses=20");
            classes.put(guochanju);

            JSONObject movie = new JSONObject();
            movie.put("type_name", "电影");
            movie.put("type_id", "1");
            classes.put(movie);

            JSONObject tv = new JSONObject();
            tv.put("type_name", "电视剧");
            tv.put("type_id", "2");
            classes.put(tv);

            JSONObject variety = new JSONObject();
            variety.put("type_name", "综艺");
            variety.put("type_id", "4");
            classes.put(variety);

            result.put("class", classes);

            if (filter) {
                JSONObject filters = new JSONObject();

                // 电影筛选器
                JSONArray movieFilters = new JSONArray();
                movieFilters.put(createFilter("areas", "地区", getAreasOptions()));
                movieFilters.put(createFilter("types", "类型", getTypesOptions()));
                filters.put("1", movieFilters);

                // 电视剧筛选器
                JSONArray tvFilters = new JSONArray();
                tvFilters.put(createFilter("areas", "地区", getAreasOptions()));
                tvFilters.put(createFilter("tvclasses", "电视剧分类", getTvClassesOptions()));
                tvFilters.put(createFilter("types", "类型", getTypesOptions()));
                filters.put("2", tvFilters);

                // 综艺筛选器
                JSONArray varietyFilters = new JSONArray();
                varietyFilters.put(createFilter("areas", "地区", getAreasOptions()));
                varietyFilters.put(createFilter("types", "类型", getTypesOptions()));
                filters.put("4", varietyFilters);

                result.put("filters", filters);
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
            JSONArray videos = new JSONArray();

            // 卡片选择器兼容 .movie-card 和 .group
            Elements cards = doc.select(".movie-card");
            if (cards.isEmpty()) {
                cards = doc.select(".group");
            }

            for (Element card : cards) {
                Element link = card.selectFirst("a[href^=\"/play/\"]");
                if (link == null) continue;

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

                JSONObject vod = new JSONObject();
                vod.put("vod_id", vodId);
                vod.put("vod_name", title);
                vod.put("vod_pic", pic != null ? pic : "");
                vod.put("vod_remarks", remark);
                videos.put(vod);
            }

            // 分页估算
            int currentPage = Integer.parseInt(pg);
            int pagecount = currentPage + 5;
            pagecount = Math.min(pagecount, 20);
            int limit = videos.length();
            int total = limit * pagecount;

            JSONObject result = new JSONObject();
            result.put("list", videos);
            result.put("page", currentPage);
            result.put("pagecount", pagecount);
            result.put("limit", limit);
            result.put("total", total);
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
        JSONObject result = new JSONObject();
        result.put("list", new JSONArray());
        result.put("page", page);
        result.put("pagecount", 1);
        result.put("limit", 0);
        result.put("total", 0);
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

            JSONObject vod = new JSONObject();
            vod.put("vod_id", vodId);
            vod.put("vod_name", title);
            vod.put("vod_pic", pic);
            vod.put("vod_director", director);
            vod.put("vod_actor", actor);
            vod.put("vod_area", area);
            vod.put("vod_year", year);
            vod.put("vod_content", content);
            vod.put("vod_play_from", String.join("$$$", playFromList));
            vod.put("vod_play_url", String.join("$$$", playUrlList));

            JSONArray list = new JSONArray();
            list.put(vod);
            JSONObject result = new JSONObject();
            result.put("list", list);
            return result.toString();

        } catch (Exception e) {
            SpiderDebug.log("[4k影视] detailContent error: " + e.getMessage());
            e.printStackTrace();
            return "{\"list\":[]}";
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContentPg(key, quick, "1");
    }

    public String searchContentPg(String key, boolean quick, String pg) {
        try {
            String url = host + "/search?q=" + URLEncoder.encode(key, "UTF-8");
            SpiderDebug.log("[4k影视] search URL: " + url);

            String html = get(url);
            if (html == null) {
                return "{\"list\":[]}";
            }

            Document doc = Jsoup.parse(html);
            JSONArray videos = new JSONArray();

            for (Element item : doc.select(".group")) {
                Element a = item.selectFirst("a[href^=\"/play/\"]");
                if (a == null) continue;

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

                JSONObject vod = new JSONObject();
                vod.put("vod_id", vodId);
                vod.put("vod_name", title);
                vod.put("vod_pic", pic != null ? pic : "");
                vod.put("vod_remarks", "");
                videos.put(vod);
            }

            JSONObject result = new JSONObject();
            result.put("list", videos);
            result.put("page", Integer.parseInt(pg));
            result.put("pagecount", 1);
            result.put("limit", videos.length());
            result.put("total", videos.length());
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
                JSONObject result = new JSONObject();
                result.put("parse", 1);
                result.put("url", url);
                JSONObject header = new JSONObject();
                header.put("User-Agent", headers.get("User-Agent"));
                header.put("Referer", url);
                header.put("Origin", host);
                result.put("header", header);
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
                    JSONObject result = new JSONObject();
                    result.put("parse", 0);
                    result.put("url", videoUrl);
                    JSONObject header = new JSONObject();
                    header.put("User-Agent", headers.get("User-Agent"));
                    header.put("Referer", url);
                    header.put("Origin", host);
                    result.put("header", header);
                    return result.toString();
                }
            }

            // 未匹配到视频链接，返回 parse=1 让壳子嗅探
            JSONObject result = new JSONObject();
            result.put("parse", 1);
            result.put("url", url);
            JSONObject header = new JSONObject();
            header.put("User-Agent", headers.get("User-Agent"));
            header.put("Referer", url);
            header.put("Origin", host);
            result.put("header", header);
            return result.toString();

        } catch (Exception e) {
            SpiderDebug.log("[4k影视] playerContent error: " + e.getMessage());
            JSONObject result = new JSONObject();
            result.put("parse", 1);
            result.put("url", id);
            JSONObject header = new JSONObject();
            header.put("User-Agent", headers.get("User-Agent"));
            header.put("Referer", host + "/");
            header.put("Origin", host);
            result.put("header", header);
            return result.toString();
        }
    }

    @Override
    public boolean isVideoCast() {
        return true;
    }
}
