package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.Spider;
import com.github.catvod.utils.Json;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class O3YY extends Spider {

    private String host = "https://www.03yy.live";
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    private Map<String, String> headers;
    private OkHttpClient client;
    private Gson gson = new Gson();

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        headers = new HashMap<>();
        headers.put("User-Agent", userAgent);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Referer", host);
        client = new OkHttpClient.Builder().build();
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        JsonObject result = new JsonObject();
        JsonArray classes = new JsonArray();
        classes.add(createClass("大陆剧", "13"));
        classes.add(createClass("电影", "1"));
        classes.add(createClass("综艺", "3"));
        classes.add(createClass("短剧", "48"));
        result.add("class", classes);

        if (filter) {
            JsonObject filters = new JsonObject();
            JsonArray tvTypes = createFilterValues(new String[][]{
                {"全部", ""}, {"大陆剧", "13"}, {"欧美剧", "27"}, {"韩国剧", "26"},
                {"香港剧", "14"}, {"台湾剧", "46"}, {"日本剧", "16"}, {"泰国剧", "47"}, {"海外剧", "28"}
            });
            JsonArray movieTypes = createFilterValues(new String[][]{
                {"全部", ""}, {"动作片", "5"}, {"喜剧片", "10"}, {"爱情片", "6"},
                {"科幻片", "7"}, {"恐怖片", "8"}, {"战争片", "9"}, {"剧情片", "12"}, {"动画片", "25"}
            });
            JsonArray varietyTypes = createFilterValues(new String[][]{
                {"全部", ""}, {"大陆综艺", "29"}, {"港台综艺", "30"}, {"日韩综艺", "31"}, {"欧美综艺", "32"}
            });
            filters.add("13", createFilterGroup("class", "类型", tvTypes));
            filters.add("1", createFilterGroup("class", "类型", movieTypes));
            filters.add("3", createFilterGroup("class", "类型", varietyTypes));
            filters.add("48", createFilterGroup("class", "类型", tvTypes));
            result.add("filters", filters);
        }
        return result.toString();
    }

    private JsonObject createClass(String name, String id) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type_name", name);
        obj.addProperty("type_id", id);
        return obj;
    }

    private JsonArray createFilterValues(String[][] pairs) {
        JsonArray arr = new JsonArray();
        for (String[] pair : pairs) {
            JsonObject opt = new JsonObject();
            opt.addProperty("n", pair[0]);
            opt.addProperty("v", pair[1]);
            arr.add(opt);
        }
        return arr;
    }

    private JsonArray createFilterGroup(String key, String name, JsonArray values) {
        JsonObject group = new JsonObject();
        group.addProperty("key", key);
        group.addProperty("name", name);
        group.add("value", values);
        JsonArray arr = new JsonArray();
        arr.add(group);
        return arr;
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = Integer.parseInt(pg);
        String subTid = (extend != null && extend.containsKey("class")) ? extend.get("class") : tid;
        String url = host + "/type/index" + subTid + "-" + page + ".html";
        String html = fetch(url);
        if (html == null) return emptyCategoryResult(page);

        Document doc = Jsoup.parse(html);
        Elements items = doc.select(".Pic-list .pic-content");
        JsonArray list = new JsonArray();
        for (Element item : items) {
            Element a = item.selectFirst("a:first-of-type");
            if (a == null) continue;
            String href = a.attr("href");
            Matcher m = Pattern.compile("/movie/index(\\d+)\\.html").matcher(href);
            if (!m.find()) continue;
            String vid = m.group(1);
            String title = a.attr("title");
            if (TextUtils.isEmpty(title)) {
                Element h4a = item.selectFirst("h4 a");
                if (h4a != null) title = h4a.text();
            }
            Element imgElem = item.selectFirst("img");
            String pic = imgElem != null ? imgElem.attr("src") : "";
            if (!TextUtils.isEmpty(pic) && !pic.startsWith("http")) pic = host + pic;
            Element span = item.selectFirst("span");
            Element iElem = item.selectFirst("i");
            String remark = (span != null) ? span.text() : (iElem != null ? iElem.text() : "");
            JsonObject vod = new JsonObject();
            vod.addProperty("vod_id", vid);
            vod.addProperty("vod_name", title.trim());
            vod.addProperty("vod_pic", pic);
            vod.addProperty("vod_remarks", remark);
            list.add(vod);
        }
        JsonObject result = new JsonObject();
        result.add("list", list);
        result.addProperty("page", page);
        result.addProperty("pagecount", 99);
        result.addProperty("limit", 20);
        result.addProperty("total", 9999);
        return result.toString();
    }

    private String emptyCategoryResult(int page) {
        JsonObject result = new JsonObject();
        result.add("list", new JsonArray());
        result.addProperty("page", page);
        result.addProperty("pagecount", 1);
        result.addProperty("limit", 20);
        result.addProperty("total", 0);
        return result.toString();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String vid = ids.get(0);
        String url = host + "/movie/index" + vid + ".html";
        String html = fetch(url);
        if (html == null) return "{\"list\":[]}";

        Document doc = Jsoup.parse(html);
        String title = doc.selectFirst("h1") != null ? doc.selectFirst("h1").text().trim() : "";
        Element imgElem = doc.selectFirst(".m-pic-l img");
        String pic = imgElem != null ? imgElem.attr("src") : "";
        if (!TextUtils.isEmpty(pic) && !pic.startsWith("http")) pic = host + pic;

        String director = "", actor = "", area = "", typeName = "", year = "";
        Elements lis = doc.select(".m-content ul li");
        for (Element li : lis) {
            String text = li.text();
            if (text.contains("导演：")) {
                Element a = li.selectFirst("a");
                director = a != null ? a.text().replace(" ", "").trim() : "";
            } else if (text.contains("主演：")) {
                List<String> actors = new ArrayList<>();
                for (Element a : li.select("a")) actors.add(a.text());
                actor = TextUtils.join(",", actors);
            } else if (text.contains("地区") && li.select("span").size() > 0) {
                Elements spans = li.select("span");
                if (spans.size() >= 1) area = spans.get(0).text();
                if (spans.size() >= 2) typeName = spans.get(1).text();
                if (spans.size() >= 3) year = spans.get(2).text();
            }
        }

        Elements introPs = doc.select(".m-intro p");
        StringBuilder contentBuilder = new StringBuilder();
        for (Element p : introPs) contentBuilder.append(p.text().trim()).append(" ");
        String content = contentBuilder.toString().trim();

        List<String> playFromList = new ArrayList<>();
        List<String> playUrlList = new ArrayList<>();
        Elements lineTabs = doc.select(".playfrom #playlist li");
        for (int idx = 0; idx < lineTabs.size(); idx++) {
            String lineName = lineTabs.get(idx).text().trim();
            String listId = "stab8" + (idx + 1);
            Element listDiv = doc.selectFirst("#" + listId);
            if (listDiv == null) continue;
            List<String> episodes = new ArrayList<>();
            for (Element a : listDiv.select("ul li a")) {
                String epName = a.text();
                String epLink = a.attr("href");
                if (!TextUtils.isEmpty(epLink)) {
                    String fullLink = epLink.startsWith("http") ? epLink : host + epLink;
                    episodes.add(epName + "$" + fullLink);
                }
            }
            if (!episodes.isEmpty()) {
                playFromList.add(lineName);
                playUrlList.add(TextUtils.join("#", episodes));
            }
        }

        JsonObject vod = new JsonObject();
        vod.addProperty("vod_id", vid);
        vod.addProperty("vod_name", title);
        vod.addProperty("vod_pic", pic);
        vod.addProperty("vod_director", director);
        vod.addProperty("vod_actor", actor);
        vod.addProperty("vod_area", area);
        vod.addProperty("type_name", typeName);
        vod.addProperty("vod_year", year);
        vod.addProperty("vod_content", content);
        vod.addProperty("vod_play_from", TextUtils.join("$$$", playFromList));
        vod.addProperty("vod_play_url", TextUtils.join("$$$", playUrlList));
        JsonArray list = new JsonArray();
        list.add(vod);
        JsonObject result = new JsonObject();
        result.add("list", list);
        return result.toString();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        int page = Integer.parseInt(pg);
        String url = host + "/search.php?searchword=" + URLEncoder.encode(key, "UTF-8");
        String html = fetch(url);
        if (html == null) return emptySearchResult(page);

        Document doc = Jsoup.parse(html);
        Elements items = doc.select(".Pic-list .pic-content");
        JsonArray list = new JsonArray();
        for (Element item : items) {
            Element a = item.selectFirst("a:first-of-type");
            if (a == null) continue;
            String href = a.attr("href");
            Matcher m = Pattern.compile("/movie/index(\\d+)\\.html").matcher(href);
            if (!m.find()) continue;
            String vid = m.group(1);
            String title = a.attr("title");
            if (TextUtils.isEmpty(title)) {
                Element h4a = item.selectFirst("h4 a");
                if (h4a != null) title = h4a.text();
            }
            Element imgElem = item.selectFirst("img");
            String pic = imgElem != null ? imgElem.attr("src") : "";
            if (!TextUtils.isEmpty(pic) && !pic.startsWith("http")) pic = host + pic;
            Element iElem = item.selectFirst("i");
            Element span = item.selectFirst("span");
            String remark = (iElem != null) ? iElem.text() : (span != null ? span.text() : "");
            JsonObject vod = new JsonObject();
            vod.addProperty("vod_id", vid);
            vod.addProperty("vod_name", title.trim());
            vod.addProperty("vod_pic", pic);
            vod.addProperty("vod_remarks", remark);
            list.add(vod);
        }
        JsonObject result = new JsonObject();
        result.add("list", list);
        result.addProperty("page", page);
        return result.toString();
    }

    private String emptySearchResult(int page) {
        JsonObject result = new JsonObject();
        result.add("list", new JsonArray());
        result.addProperty("page", page);
        return result.toString();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        try {
            // 第一步：请求播放页
            String playHtml = fetch(id);
            if (playHtml == null) return simplePlayerResult(id);

            // 提取 pn 和 now
            String pn = extractVar(playHtml, "var pn=\"([^\"]+)\"");
            String nowBase64 = extractVar(playHtml, "var now=base64decode\\(\"([^\"]+)\"\\)");
            String now = nowBase64 != null ? new String(Base64.decode(nowBase64, Base64.DEFAULT), StandardCharsets.UTF_8) : null;
            String nextPage = extractVar(playHtml, "var nextPage=\"([^\"]+)\"");

            // 如果没有 pn 或 now，尝试直接从页面提取视频链接
            if (pn == null || now == null) {
                JsonObject direct = extractVideoFromHtml(playHtml, id);
                if (direct != null) return direct.toString();
                return simplePlayerResult(id);
            }

            // 请求播放器加载页
            String loaderUrl = host + "/js/player/" + pn + ".html";
            String loaderHtml = fetch(loaderUrl);
            if (loaderHtml == null) return simplePlayerResult(id);

            // 提取 iframe src
            String iframeSrc = extractIframeSrc(loaderHtml);
            if (iframeSrc == null) return simplePlayerResult(id);

            // 构建 API URL
            String apiPath = iframeSrc.split("\\?")[0];
            Map<String, String> params = new HashMap<>();
            params.put("url", now);
            if (nextPage != null && !nextPage.isEmpty()) {
                params.put("next", host + nextPage);
            }
            if (iframeSrc.contains("ref") || iframeSrc.contains("parentUrl")) {
                params.put("ref", id);
            }
            String query = buildQuery(params);
            String apiUrl = host + apiPath + "?" + query;

            // 请求 API
            String apiHtml = fetch(apiUrl);
            if (apiHtml == null) return simplePlayerResult(id);

            JsonObject result = extractVideoFromHtml(apiHtml, id);
            if (result != null) return result.toString();

        } catch (Exception e) {
            e.printStackTrace();
        }
        return simplePlayerResult(id);
    }

    private String extractVar(String html, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private String extractIframeSrc(String html) {
        // 尝试 <iframe src="...">
        Matcher m1 = Pattern.compile("<iframe[^>]+src=[\"']([^\"']+)[\"']").matcher(html);
        if (m1.find()) return m1.group(1);
        // 尝试 iframe.src = "..."
        Matcher m2 = Pattern.compile("iframe\\.src\\s*=\\s*[\"']([^\"']+)[\"']").matcher(html);
        if (m2.find()) return m2.group(1);
        return null;
    }

    private String buildQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) sb.append("&");
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        return sb.toString();
    }

    private JsonObject extractVideoFromHtml(String html, String refUrl) {
        // 尝试匹配 mediaInfo 数组
        Matcher mediaMatcher = Pattern.compile("(?:var|const|let)\\s+mediaInfo\\s*=\\s*(\\[.*?\\]);", Pattern.DOTALL).matcher(html);
        if (mediaMatcher.find()) {
            try {
                String mediaJson = mediaMatcher.group(1);
                JsonArray mediaList = JsonParser.parseString(mediaJson).getAsJsonArray();
                String bestUrl = null;
                for (int i = 0; i < mediaList.size(); i++) {
                    JsonObject item = mediaList.get(i).getAsJsonObject();
                    if (item.has("url")) {
                        String url = item.get("url").getAsString();
                        if (item.has("definition") && item.get("definition").getAsString().contains("1080P")) {
                            bestUrl = url;
                            break;
                        }
                        if (bestUrl == null) bestUrl = url;
                    }
                }
                if (bestUrl != null) {
                    JsonObject result = new JsonObject();
                    result.addProperty("parse", 0);
                    result.addProperty("url", bestUrl);
                    JsonObject header = new JsonObject();
                    header.addProperty("User-Agent", userAgent);
                    header.addProperty("Referer", refUrl);
                    header.addProperty("Origin", host);
                    result.add("header", header);
                    return result;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        // 尝试匹配 videoUrl
        Matcher videoMatcher = Pattern.compile("(?:var|const|let)\\s+videoUrl\\s*=\\s*\"([^\"]+)\"").matcher(html);
        if (videoMatcher.find()) {
            String videoUrl = videoMatcher.group(1).replace("\\/", "/");
            JsonObject result = new JsonObject();
            result.addProperty("parse", 0);
            result.addProperty("url", videoUrl);
            JsonObject header = new JsonObject();
            header.addProperty("User-Agent", userAgent);
            header.addProperty("Referer", refUrl);
            header.addProperty("Origin", host);
            result.add("header", header);
            return result;
        }
        return null;
    }

    private String simplePlayerResult(String url) {
        JsonObject result = new JsonObject();
        result.addProperty("parse", 1);
        result.addProperty("url", url);
        return result.toString();
    }

    private String fetch(String url) {
        try {
            Request request = new Request.Builder().url(url).headers(okhttp3.Headers.of(headers)).build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    return response.body().string();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
