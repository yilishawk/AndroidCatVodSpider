package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 农民影视
 * - 筛选从列表页动态解析（类型/地区/年份/排序）
 * - parse=0 不带 Referer，带 Origin
 * - 返回壳子的 URL 对汉字做 percent-encode
 */
public class NM extends Spider {

    private static final String siteUrl = "https://vip.wwgz.cn:5200";
    private static final String apiHost = "https://api.wwgz.cn:520";
    private static final String ORIGIN = "https://api.wwgz.cn:520";

    private final OkHttpClient client = new OkHttpClient();

    private static final String[][] CLASS_ARR = {
            {"12", "国产剧"},
            {"1", "电影"},
            {"2", "电视剧"},
            {"3", "综艺"},
            {"26", "短剧"}
    };

    private Headers getHeaders() {
        return new Headers.Builder()
                .add("User-Agent",
                        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36")
                .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .add("Accept-Language", "zh-CN,zh;q=0.9")
                .build();
    }

    private String fetch(String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .headers(getHeaders())
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return response.body().string();
            }
            throw new Exception("Request failed: " + response.code());
        }
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = new JSONArray();
            for (String[] c : CLASS_ARR) {
                JSONObject obj = new JSONObject();
                obj.put("type_id", c[0]);
                obj.put("type_name", c[1]);
                classes.put(obj);
            }
            result.put("class", classes);

            if (filter) {
                JSONObject filters = new JSONObject();
                for (String[] c : CLASS_ARR) {
                    try {
                        String listUrl = siteUrl + String.format(
                                "/vod-list-id-%s-pg-1-order--by-time-class-0-year-0-letter--area--lang-.html",
                                c[0]);
                        String html = fetch(listUrl);
                        Document doc = Jsoup.parse(html);
                        filters.put(c[0], buildFilters(doc));
                    } catch (Exception e) {
                        SpiderDebug.log(e);
                    }
                }
                result.put("filters", filters);
            }
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return errorMsg(e.getMessage());
        }
    }

    /** 从列表页动态解析：类型 / 地区 / 年份 / 排序 */
    private JSONArray buildFilters(Document doc) throws Exception {
        JSONArray filters = new JSONArray();

        // 类型：ul.con 里切换 list-id
        JSONArray classOpts = new JSONArray();
        classOpts.put(createOption("全部", "0"));
        LinkedHashSet<String> seenClass = new LinkedHashSet<>();
        for (Element a : doc.select("ul.con li a[href*=vod-list-id-]")) {
            String href = a.attr("href");
            Matcher m = Pattern.compile("vod-list-id-(\\d+)-").matcher(href);
            if (!m.find()) continue;
            String id = m.group(1);
            String name = a.attr("title");
            if (name.isEmpty()) name = a.text().trim();
            if (name.isEmpty() || name.contains("全部")) continue;
            if (!seenClass.add(id)) continue;
            classOpts.put(createOption(name, id));
        }
        if (classOpts.length() > 1) {
            filters.put(createFilter("class", "类型", classOpts));
        }

        // 地区
        JSONArray areaOpts = new JSONArray();
        areaOpts.put(createOption("全部", ""));
        LinkedHashSet<String> seenArea = new LinkedHashSet<>();
        for (Element a : doc.select("a[href*=-area-]")) {
            Matcher m = Pattern.compile("area-([^\"&]+?)-lang").matcher(a.attr("href"));
            if (!m.find()) continue;
            String raw = m.group(1);
            if (raw.isEmpty() || "0".equals(raw)) continue;
            String area;
            try {
                area = java.net.URLDecoder.decode(raw, "UTF-8");
            } catch (Exception e) {
                area = raw;
            }
            if (area.isEmpty() || !seenArea.add(area)) continue;
            String name = a.text().trim();
            if (name.isEmpty() || "地区".equals(name) || "全部".equals(name)) name = area;
            areaOpts.put(createOption(name, area));
        }
        filters.put(createFilter("area", "地区", areaOpts));

        // 年份
        JSONArray yearOpts = new JSONArray();
        yearOpts.put(createOption("全部", "0"));
        LinkedHashSet<String> seenYear = new LinkedHashSet<>();
        for (Element a : doc.select("a[href*=-year-]")) {
            Matcher m = Pattern.compile("year-(\\d+)-").matcher(a.attr("href"));
            if (!m.find()) continue;
            String y = m.group(1);
            if ("0".equals(y) || !seenYear.add(y)) continue;
            yearOpts.put(createOption(y, y));
        }
        filters.put(createFilter("year", "年份", yearOpts));

        // 排序
        JSONArray orderOpts = new JSONArray();
        orderOpts.put(createOption("最新", "time"));
        orderOpts.put(createOption("最热", "hits"));
        orderOpts.put(createOption("评分", "score"));
        filters.put(createFilter("order", "排序", orderOpts));

        return filters;
    }

    private JSONObject createOption(String n, String v) throws Exception {
        JSONObject opt = new JSONObject();
        opt.put("n", n);
        opt.put("v", v);
        return opt;
    }

    private JSONObject createFilter(String key, String name, JSONArray value) throws Exception {
        JSONObject f = new JSONObject();
        f.put("key", key);
        f.put("name", name);
        f.put("value", value);
        return f;
    }

    private String errorMsg(String msg) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("msg", msg == null ? "未知错误" : msg);
            return obj.toString();
        } catch (Exception ignored) {
        }
        return "{}";
    }

    private int getTotalPages(Document doc) {
        Elements pageLinks = doc.select(".page a");
        int max = 1;
        for (Element a : pageLinks) {
            String text = a.text().trim();
            if (text.matches("\\d+")) {
                int p = Integer.parseInt(text);
                if (p > max) max = p;
            }
        }
        return max;
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            if (extend == null) extend = new HashMap<>();
            String order = extend.containsKey("order") ? extend.get("order") : "time";
            String classId = extend.containsKey("class") ? extend.get("class") : "0";
            String year = extend.containsKey("year") ? extend.get("year") : "0";
            String area = extend.containsKey("area") ? extend.get("area") : "";

            if (order == null || order.isEmpty()) order = "time";
            if (classId == null || classId.isEmpty()) classId = "0";
            if (year == null || year.isEmpty()) year = "0";
            if (area == null) area = "";

            String classParam = "0";
            String listId = !"0".equals(classId) ? classId : tid;

            String yearPart = "0".equals(year) ? "--" : "-" + year;
            String areaPart;
            if (area.isEmpty()) {
                areaPart = "--";
            } else {
                try {
                    areaPart = "-" + URLEncoder.encode(area, "UTF-8");
                } catch (Exception e) {
                    areaPart = "-" + area;
                }
            }

            String url = siteUrl + String.format(
                    "/vod-list-id-%s-pg-%s-order--by-%s-class-%s-year%s-letter--area%s-lang-.html",
                    listId, pg, order, classParam, yearPart, areaPart
            );

            String html = fetch(url);
            Document doc = Jsoup.parse(html);
            Elements items = doc.select("ul.resize_list li");
            JSONArray videoList = new JSONArray();

            for (Element li : items) {
                Element a = li.selectFirst("a");
                if (a == null) continue;
                String href = a.attr("href");
                String title = a.attr("title");
                if (title.isEmpty()) title = a.text().trim();

                Element picDiv = li.selectFirst("div.pic");
                String picUrl = "";
                if (picDiv != null) {
                    Element img = picDiv.selectFirst("img");
                    if (img != null) {
                        picUrl = img.attr("data-echo");
                        if (picUrl.isEmpty()) picUrl = img.attr("src");
                    }
                }

                String remarks = "";
                Element span = li.selectFirst("span.sBottom span");
                if (span != null) remarks = span.text().trim();

                String vodId;
                if (href.startsWith("/vod-detail-id-")) {
                    String detailId = href.split("-")[3].replace(".html", "");
                    vodId = "detail_" + detailId;
                } else {
                    vodId = href;
                }

                JSONObject vod = new JSONObject();
                vod.put("vod_id", vodId);
                vod.put("vod_name", title);
                vod.put("vod_pic", picUrl);
                vod.put("vod_remarks", remarks);
                videoList.put(vod);
            }

            int totalPages = getTotalPages(doc);
            JSONObject result = new JSONObject();
            result.put("list", videoList);
            result.put("pagecount", totalPages);
            result.put("page", Integer.parseInt(pg));
            result.put("limit", videoList.length());
            result.put("total", totalPages * 20);

            if (filter) {
                try {
                    JSONObject filters = new JSONObject();
                    filters.put(tid, buildFilters(doc));
                    result.put("filters", filters);
                } catch (Exception ignored) {
                }
            }
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return errorMsg(e.getMessage());
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String vodId = ids.get(0);
            String detailId = "";
            String detailUrl;

            if (vodId.startsWith("detail_")) {
                detailId = vodId.substring(7);
                detailUrl = siteUrl + "/vod-detail-id-" + detailId + ".html";
            } else {
                detailUrl = vodId.startsWith("http") ? vodId : siteUrl + vodId;
                Matcher m = Pattern.compile("vod-detail-id-(\\d+)").matcher(detailUrl);
                if (m.find()) detailId = m.group(1);
            }

            String html = fetch(detailUrl);
            Document doc = Jsoup.parse(html);

            Element titleEl = doc.selectFirst("h1.title a");
            String title = titleEl != null ? titleEl.text().trim() : "";

            Element picEl = doc.selectFirst(".page-hd img");
            String pic = "";
            if (picEl != null) {
                pic = picEl.attr("src");
                if (pic.isEmpty()) pic = picEl.attr("data-echo");
            }

            StringBuilder actor = new StringBuilder();
            Elements actorLinks = doc.select(".desc_item:contains(主演:) a");
            for (Element a : actorLinks) {
                if (actor.length() > 0) actor.append(", ");
                actor.append(a.text().trim());
            }

            StringBuilder director = new StringBuilder();
            Elements dirLinks = doc.select(".desc_item:contains(导演:) a");
            for (Element a : dirLinks) {
                if (director.length() > 0) director.append(", ");
                director.append(a.text().trim());
            }

            Element yearEl = doc.selectFirst(".desc_item:contains(年代:) a");
            String year = yearEl != null ? yearEl.text().trim() : "";

            String area = "";
            Element areaEl = doc.selectFirst(".desc_item:contains(地区:) a");
            if (areaEl != null) area = areaEl.text().trim();

            String typeName = "";
            Element typeEl = doc.selectFirst(".type-title");
            if (typeEl != null) typeName = typeEl.text().trim();

            Element introEl = doc.selectFirst("article.detail-con p");
            if (introEl == null) introEl = doc.selectFirst(".detail-con");
            String intro = introEl != null ? introEl.text().replaceAll("\\s+", " ").trim() : "";

            List<String> playFromList = new ArrayList<>();
            List<String> playUrlList = new ArrayList<>();

            if (!detailId.isEmpty()) {
                String playPageUrl = siteUrl + "/vod-play-id-" + detailId + "-src-1-num-1.html";
                try {
                    String playHtml = fetch(playPageUrl);
                    Matcher fromMatcher = Pattern.compile("mac_from\\s*=\\s*'([^']+)'").matcher(playHtml);
                    Matcher urlMatcher = Pattern.compile("mac_url\\s*=\\s*'([^']+)'").matcher(playHtml);
                    if (fromMatcher.find() && urlMatcher.find()) {
                        String macFrom = fromMatcher.group(1);
                        String macUrl = urlMatcher.group(1);
                        String[] fromParts = macFrom.split("\\$\\$\\$");
                        String[] urlParts = macUrl.split("\\$\\$\\$");
                        int lineCount = Math.min(fromParts.length, urlParts.length);
                        for (int i = 0; i < lineCount; i++) {
                            String lineName = fromParts[i].trim();
                            if (lineName.isEmpty()) lineName = "线路" + (i + 1);
                            String[] episodes = urlParts[i].split("#");
                            List<String> epList = new ArrayList<>();
                            for (String ep : episodes) {
                                if (ep.trim().isEmpty()) continue;
                                epList.add(ep.trim());
                            }
                            Collections.sort(epList, (o1, o2) ->
                                    Integer.compare(extractEpisodeNumber(o1), extractEpisodeNumber(o2)));
                            if (!epList.isEmpty()) {
                                playFromList.add(lineName);
                                playUrlList.add(String.join("#", epList));
                            }
                        }
                    }
                } catch (Exception ignored) {
                    SpiderDebug.log(ignored);
                }
            }

            JSONObject vod = new JSONObject();
            vod.put("vod_id", vodId);
            vod.put("vod_name", title);
            vod.put("vod_pic", pic);
            vod.put("type_name", typeName);
            vod.put("vod_year", year);
            vod.put("vod_area", area);
            vod.put("vod_director", director.toString());
            vod.put("vod_actor", actor.toString());
            vod.put("vod_content", intro);
            vod.put("vod_play_from", String.join("$$$", playFromList));
            vod.put("vod_play_url", String.join("$$$", playUrlList));

            JSONArray list = new JSONArray();
            list.put(vod);
            JSONObject result = new JSONObject();
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return errorMsg(e.getMessage());
        }
    }

    private int extractEpisodeNumber(String s) {
        Matcher m = Pattern.compile("第(\\d+)集").matcher(s);
        if (m.find()) return Integer.parseInt(m.group(1));
        return 0;
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String pg = "1";
            String url = siteUrl + "/vod-search-pg-" + pg + "-wd-" + URLEncoder.encode(key, "UTF-8") + ".html";
            String html = fetch(url);
            Document doc = Jsoup.parse(html);
            Elements items = doc.select("ul#data_list li");
            if (items.isEmpty()) items = doc.select("ul.ulPicTxt li");

            JSONArray videoList = new JSONArray();
            for (Element li : items) {
                Element titleEl = li.selectFirst(".txt .sTit");
                if (titleEl == null) titleEl = li.selectFirst("a[title]");
                String title = titleEl != null ? titleEl.text().trim() : "";

                Element detailA = li.selectFirst(".pic a");
                if (detailA == null) detailA = li.selectFirst(".aPlayBtn");
                String href = detailA != null ? detailA.attr("href") : "";
                if (href.isEmpty() || title.isEmpty()) continue;

                Element imgEl = li.selectFirst(".pic img");
                String picUrl = "";
                if (imgEl != null) {
                    picUrl = imgEl.attr("data-src");
                    if (picUrl.isEmpty()) picUrl = imgEl.attr("src");
                }

                Element remarksEl = li.selectFirst(".sStyle");
                if (remarksEl == null) remarksEl = li.selectFirst(".sDes em:not(.emTit)");
                String remarks = remarksEl != null ? remarksEl.text().trim() : "";

                String vodId;
                if (href.startsWith("/vod-detail-id-")) {
                    String detailId = href.split("-")[3].replace(".html", "");
                    vodId = "detail_" + detailId;
                } else {
                    vodId = href;
                }

                JSONObject v = new JSONObject();
                v.put("vod_id", vodId);
                v.put("vod_name", title);
                v.put("vod_pic", picUrl);
                v.put("vod_remarks", remarks);
                videoList.put(v);
            }

            int pageCount = 1;
            Element lastPage = doc.selectFirst(".page a:last-child");
            if (lastPage != null) {
                Matcher m = Pattern.compile("pg-(\\d+)").matcher(lastPage.attr("href"));
                if (m.find()) pageCount = Integer.parseInt(m.group(1));
            }

            JSONObject result = new JSONObject();
            result.put("list", videoList);
            result.put("page", Integer.parseInt(pg));
            result.put("pagecount", pageCount);
            result.put("limit", videoList.length());
            result.put("total", videoList.length() * pageCount);
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return errorMsg(e.getMessage());
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            if (id != null && !id.contains("http") && !id.contains("$") && !id.contains("?")) {
                String apiUrl = apiHost + "/player/?url=" + id;
                String res = fetch(apiUrl);
                Matcher urlMatcher = Pattern.compile("\"url\":\\s*\"([^\"]+)\"").matcher(res);
                if (urlMatcher.find()) {
                    String realUrl = urlMatcher.group(1).replace("\\u0026", "&");
                    return successPlayerResult(realUrl);
                }
                Matcher iframeMatcher = Pattern.compile("<iframe[^>]+src=\"([^\"]+)\"").matcher(res);
                if (iframeMatcher.find()) {
                    return successPlayerResult(iframeMatcher.group(1));
                }
            } else {
                String playUrl = id.startsWith("http") ? id : siteUrl + id;
                String html = fetch(playUrl);
                Matcher macUrlMatcher = Pattern.compile("mac_url\\s*=\\s*'([^']+)'").matcher(html);
                if (!macUrlMatcher.find()) {
                    return fallbackToParse(playUrl);
                }
                String macUrl = macUrlMatcher.group(1);
                int currentNum = 1;
                Matcher numMatcher = Pattern.compile("-num-(\\d+)\\.html").matcher(playUrl);
                if (numMatcher.find()) currentNum = Integer.parseInt(numMatcher.group(1));

                String targetEncrypted = null;
                String[] lines = macUrl.split("\\$\\$\\$");
                for (String line : lines) {
                    String[] parts = line.split("#");
                    for (String part : parts) {
                        Matcher m = Pattern.compile("第(\\d+)集\\$(.*)").matcher(part);
                        if (m.find() && Integer.parseInt(m.group(1)) == currentNum) {
                            targetEncrypted = m.group(2);
                            break;
                        }
                    }
                    if (targetEncrypted != null) break;
                }
                if (targetEncrypted == null) {
                    Pattern p = Pattern.compile("第" + currentNum + "集\\$(.*?)(?=#|$)");
                    for (String line : lines) {
                        Matcher m = p.matcher(line);
                        if (m.find()) {
                            targetEncrypted = m.group(1);
                            break;
                        }
                    }
                }
                if (targetEncrypted != null && !targetEncrypted.isEmpty()) {
                    String apiUrl = apiHost + "/player/?url=" + targetEncrypted;
                    String apiRes = fetch(apiUrl);
                    Matcher urlMatcher = Pattern.compile("\"url\":\\s*\"([^\"]+)\"").matcher(apiRes);
                    if (urlMatcher.find()) {
                        String realUrl = urlMatcher.group(1).replace("\\u0026", "&");
                        return successPlayerResult(realUrl);
                    }
                }
            }
        } catch (Exception e) {
            SpiderDebug.log(e);
        }
        return fallbackToParse(id);
    }

    private String successPlayerResult(String realUrl) {
        try {
            JSONObject result = new JSONObject();
            result.put("parse", 0);
            result.put("url", encodeUrl(realUrl));
            JSONObject header = new JSONObject();
            header.put("User-Agent", getHeaders().get("User-Agent"));
            header.put("Origin", ORIGIN);
            result.put("header", header);
            return result.toString();
        } catch (Exception ignored) {
        }
        return "{\"parse\":0,\"url\":\"\"}";
    }

    private String fallbackToParse(String url) {
        try {
            JSONObject result = new JSONObject();
            result.put("parse", 1);
            result.put("url", url != null ? encodeUrl(url) : "");
            JSONObject header = new JSONObject();
            header.put("User-Agent", getHeaders().get("User-Agent"));
            header.put("Origin", ORIGIN);
            result.put("header", header);
            return result.toString();
        } catch (Exception ignored) {
        }
        return "{\"parse\":1,\"url\":\"\"}";
    }

    private String encodeUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        try {
            int schemeIdx = url.indexOf("://");
            if (schemeIdx < 0) return percentEncode(url);
            String prefix = url.substring(0, schemeIdx + 3);
            String rest = url.substring(schemeIdx + 3);
            int pathStart = rest.length();
            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == '/' || c == '?' || c == '#') {
                    pathStart = i;
                    break;
                }
            }
            String host = rest.substring(0, pathStart);
            String tail = rest.substring(pathStart);
            return prefix + host + percentEncode(tail);
        } catch (Exception e) {
            SpiderDebug.log(e);
            return url;
        }
    }

    private String percentEncode(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length() * 2);
        try {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '%' && i + 2 < s.length()
                        && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                    sb.append(c).append(s.charAt(i + 1)).append(s.charAt(i + 2));
                    i += 2;
                    continue;
                }
                if (c < 0x80) {
                    sb.append(c);
                    continue;
                }
                byte[] bytes = String.valueOf(c).getBytes("UTF-8");
                for (byte b : bytes) {
                    sb.append('%')
                            .append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                            .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
                }
            }
        } catch (Exception e) {
            SpiderDebug.log(e);
            return s;
        }
        return sb.toString();
    }

    private boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
