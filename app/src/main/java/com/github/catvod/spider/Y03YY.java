package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.SSLCompat;
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

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 03影院
 * 站点: https://www.03yy.live
 * 需要先访问首页拿到 __ancc_token，后续请求自动携带
 */
public class Y03YY extends Spider {

    private String host = "https://www.03yy.live";
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    // CookieJar 自动保存/发送 Cookie（含 __ancc_token）
    private final CookieJar cookieJar = new CookieJar() {
        private final Map<String, List<Cookie>> store = new HashMap<>();

        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            List<Cookie> existing = store.containsKey(url.host()) ? store.get(url.host()) : new ArrayList<>();
            // 更新或追加，避免覆盖其他 Cookie
            Map<String, Cookie> map = new HashMap<>();
            for (Cookie c : existing) map.put(c.name(), c);
            for (Cookie c : cookies) map.put(c.name(), c);
            store.put(url.host(), new ArrayList<>(map.values()));
            SpiderDebug.log("[Y03YY] 保存 Cookie: " + url.host() + " -> " + map.keySet());
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            List<Cookie> cookies = store.get(url.host());
            return cookies != null ? cookies : new ArrayList<>();
        }
    };

    // 自建 client：绑定 CookieJar + 框架 SSLCompat（解决握手失败）
    private final OkHttpClient client = new OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .sslSocketFactory(new SSLCompat(), SSLCompat.TM)
            .hostnameVerifier((hostname, session) -> true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", userAgent);
        headers.put("Referer", host + "/");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        return headers;
    }

    private String get(String url) {
        try {
            Request.Builder builder = new Request.Builder().url(url);
            for (Map.Entry<String, String> entry : getHeaders().entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
            try (Response response = client.newCall(builder.build()).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    return response.body().string();
                }
                SpiderDebug.log("[Y03YY] 请求失败: " + url + ", code=" + response.code());
                return "";
            }
        } catch (Exception e) {
            SpiderDebug.log("[Y03YY] 请求异常: " + url + ", " + e.getMessage());
            return "";
        }
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        SpiderDebug.log("[Y03YY] init: 访问首页获取 __ancc_token");
        // 302 -> 自动跳转 -> Set-Cookie 由 CookieJar 自动保存
        get(host + "/");
        SpiderDebug.log("[Y03YY] init 完成");
    }

    // ==================== 首页 ====================

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

            String[][] tvTypes = {
                {"全部", ""}, {"大陆剧", "13"}, {"欧美剧", "27"}, {"韩国剧", "26"},
                {"香港剧", "14"}, {"台湾剧", "46"}, {"日本剧", "16"}, {"泰国剧", "47"}, {"海外剧", "28"}
            };
            String[][] movieTypes = {
                {"全部", ""}, {"动作片", "5"}, {"喜剧片", "10"}, {"爱情片", "6"},
                {"科幻片", "7"}, {"恐怖片", "8"}, {"战争片", "9"}, {"剧情片", "12"}, {"动画片", "25"}
            };
            String[][] varietyTypes = {
                {"全部", ""}, {"大陆综艺", "29"}, {"港台综艺", "30"}, {"日韩综艺", "31"}, {"欧美综艺", "32"}
            };

            filters.add("13", createFilterArray("class", "类型", tvTypes));
            filters.add("1",  createFilterArray("class", "类型", movieTypes));
            filters.add("3",  createFilterArray("class", "类型", varietyTypes));
            filters.add("48", createFilterArray("class", "类型", tvTypes));
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

    private JsonArray createFilterArray(String key, String name, String[][] pairs) {
        JsonArray arr = new JsonArray();
        JsonObject group = new JsonObject();
        group.addProperty("key", key);
        group.addProperty("name", name);
        JsonArray values = new JsonArray();
        for (String[] pair : pairs) {
            JsonObject opt = new JsonObject();
            opt.addProperty("n", pair[0]);
            opt.addProperty("v", pair[1]);
            values.add(opt);
        }
        group.add("value", values);
        arr.add(group);
        return arr;
    }

    // ==================== 分类列表 ====================

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = Integer.parseInt(pg);
        String subTid = (extend != null && extend.containsKey("class") && !extend.get("class").isEmpty())
                ? extend.get("class") : tid;

        String url = host + "/type/index" + subTid + "-" + page + ".html";
        SpiderDebug.log("[Y03YY] category URL: " + url);

        String html = get(url);
        if (TextUtils.isEmpty(html)) return emptyCategoryResult(page);

        Document doc = Jsoup.parse(html);
        Elements items = doc.select(".Pic-list .pic-content");
        SpiderDebug.log("[Y03YY] 找到列表项: " + items.size());

        JsonArray list = new JsonArray();
        for (Element item : items) {
            Element a = item.selectFirst("a");
            if (a == null) continue;

            String vid = extractVid(a.attr("href"));
            if (vid.isEmpty()) continue;

            String title = a.attr("title");
            if (TextUtils.isEmpty(title)) {
                Element h4a = item.selectFirst("h4 a");
                if (h4a != null) title = h4a.text();
            }

            Element img = item.selectFirst("img");
            String pic = img != null ? img.attr("src") : "";
            if (!pic.isEmpty() && !pic.startsWith("http")) pic = host + pic;

            String remark = "";
            Element span = item.selectFirst("span");
            if (span != null) remark = span.text();
            if (TextUtils.isEmpty(remark)) {
                Element i = item.selectFirst("i");
                if (i != null) remark = i.text();
            }

            JsonObject vod = new JsonObject();
            vod.addProperty("vod_id", vid);
            vod.addProperty("vod_name", title != null ? title.trim() : "");
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

    private String extractVid(String href) {
        if (TextUtils.isEmpty(href)) return "";
        Matcher m = Pattern.compile("/movie/index(\\d+)\\.html").matcher(href);
        return m.find() ? m.group(1) : "";
    }

    // ==================== 详情页 ====================

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return "{\"list\":[]}";

        String vid = ids.get(0);
        String url = host + "/movie/index" + vid + ".html";
        SpiderDebug.log("[Y03YY] detail URL: " + url);

        String html = get(url);
        if (TextUtils.isEmpty(html)) return "{\"list\":[]}";

        Document doc = Jsoup.parse(html);

        Element h1 = doc.selectFirst("h1");
        String title = h1 != null ? h1.text().trim() : "";

        String pic = "";
        Element imgElem = doc.selectFirst(".m-pic-l img");
        if (imgElem != null) {
            pic = imgElem.attr("src");
            if (!pic.isEmpty() && !pic.startsWith("http")) pic = host + pic;
        }

        String director = "", actor = "", area = "", typeName = "", year = "";
        for (Element li : doc.select(".m-content ul li")) {
            String text = li.text();
            if (text.contains("导演：")) {
                Element a = li.selectFirst("a");
                director = a != null ? a.text().replace(" ", "").trim() : "";
            } else if (text.contains("主演：")) {
                List<String> actors = new ArrayList<>();
                for (Element a : li.select("a")) actors.add(a.text());
                actor = TextUtils.join(",", actors);
            } else if (text.contains("地区")) {
                Elements spans = li.select("span");
                if (spans.size() >= 1) area = spans.get(0).text();
                if (spans.size() >= 2) typeName = spans.get(1).text();
                if (spans.size() >= 3) year = spans.get(2).text();
            }
        }

        StringBuilder contentBuilder = new StringBuilder();
        for (Element p : doc.select(".m-intro p")) {
            if (contentBuilder.length() > 0) contentBuilder.append(" ");
            contentBuilder.append(p.text().trim());
        }

        List<String> playFromList = new ArrayList<>();
        List<String> playUrlList = new ArrayList<>();
        Elements lineTabs = doc.select(".playfrom #playlist li");
        for (int idx = 0; idx < lineTabs.size(); idx++) {
            String lineName = lineTabs.get(idx).text().trim();
            Element listDiv = doc.selectFirst("#stab8" + (idx + 1));
            if (listDiv == null) continue;

            List<String> episodes = new ArrayList<>();
            for (Element a : listDiv.select("ul li a")) {
                String epName = a.text();
                String epLink = a.attr("href");
                if (!epLink.isEmpty()) {
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
        vod.addProperty("vod_type", typeName);
        vod.addProperty("vod_year", year);
        vod.addProperty("vod_content", contentBuilder.toString());
        vod.addProperty("vod_play_from", TextUtils.join("$$$", playFromList));
        vod.addProperty("vod_play_url", TextUtils.join("$$$", playUrlList));

        JsonArray list = new JsonArray();
        list.add(vod);
        JsonObject result = new JsonObject();
        result.add("list", list);
        return result.toString();
    }

    // ==================== 搜索 ====================

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        String url = host + "/search.php?searchword=" + URLEncoder.encode(key, "UTF-8");
        SpiderDebug.log("[Y03YY] search URL: " + url);

        String html = get(url);
        if (TextUtils.isEmpty(html)) return "{\"list\":[]}";

        Document doc = Jsoup.parse(html);
        Elements items = doc.select(".Pic-list .pic-content");
        JsonArray list = new JsonArray();

        for (Element item : items) {
            Element a = item.selectFirst("a");
            if (a == null) continue;

            String vid = extractVid(a.attr("href"));
            if (vid.isEmpty()) continue;

            String title = a.attr("title");
            if (TextUtils.isEmpty(title)) {
                Element h4a = item.selectFirst("h4 a");
                if (h4a != null) title = h4a.text();
            }

            Element img = item.selectFirst("img");
            String pic = img != null ? img.attr("src") : "";
            if (!pic.isEmpty() && !pic.startsWith("http")) pic = host + pic;

            String remark = "";
            Element i = item.selectFirst("i");
            if (i != null) remark = i.text();
            if (TextUtils.isEmpty(remark)) {
                Element span = item.selectFirst("span");
                if (span != null) remark = span.text();
            }

            JsonObject vod = new JsonObject();
            vod.addProperty("vod_id", vid);
            vod.addProperty("vod_name", title != null ? title.trim() : "");
            vod.addProperty("vod_pic", pic);
            vod.addProperty("vod_remarks", remark);
            list.add(vod);
        }

        JsonObject result = new JsonObject();
        result.add("list", list);
        return result.toString();
    }

    // ==================== 播放解析 ====================

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        SpiderDebug.log("[Y03YY] playerContent: flag=" + flag + ", id=" + id);

        String playHtml = get(id);
        if (TextUtils.isEmpty(playHtml)) {
            SpiderDebug.log("[Y03YY] 播放页请求失败");
            return buildParseResult(0, id);
        }

        Matcher pnMatcher = Pattern.compile("var pn=\"([^\"]+)\"").matcher(playHtml);
        String pn = pnMatcher.find() ? pnMatcher.group(1) : null;

        Matcher nowMatcher = Pattern.compile("var now=base64decode\\(\"([^\"]+)\"\\)").matcher(playHtml);
        String now = "";
        if (nowMatcher.find()) {
            try {
                now = new String(
                        android.util.Base64.decode(nowMatcher.group(1), android.util.Base64.DEFAULT),
                        StandardCharsets.UTF_8
                );
            } catch (Exception e) {
                SpiderDebug.log("[Y03YY] base64解码失败: " + e.getMessage());
            }
        }

        Matcher nextMatcher = Pattern.compile("var nextPage=\"([^\"]+)\"").matcher(playHtml);
        String nextPage = nextMatcher.find() ? nextMatcher.group(1) : "";

        if (TextUtils.isEmpty(pn) || TextUtils.isEmpty(now)) {
            SpiderDebug.log("[Y03YY] 未找到 pn/now，尝试直接提取视频地址");
            String direct = extractVideoFromHtml(playHtml, id);
            return direct != null ? direct : buildParseResult(0, id);
        }

        String playerLoaderUrl = host + "/js/player/" + pn + ".html";
        SpiderDebug.log("[Y03YY] 请求播放器加载页: " + playerLoaderUrl);
        String loaderHtml = get(playerLoaderUrl);
        if (TextUtils.isEmpty(loaderHtml)) {
            SpiderDebug.log("[Y03YY] 播放器加载页请求失败");
            return buildParseResult(0, id);
        }

        String iframeSrc = null;
        Matcher iframeMatcher = Pattern.compile("<iframe[^>]+src=[\"']([^\"']+)[\"']").matcher(loaderHtml);
        if (iframeMatcher.find()) {
            iframeSrc = iframeMatcher.group(1);
        } else {
            Matcher srcMatcher = Pattern.compile("iframe\\.src\\s*=\\s*[\"']([^\"']+)[\"']").matcher(loaderHtml);
            if (srcMatcher.find()) iframeSrc = srcMatcher.group(1);
        }

        if (TextUtils.isEmpty(iframeSrc)) {
            SpiderDebug.log("[Y03YY] 未提取到 iframe src");
            return buildParseResult(0, id);
        }

        String apiPath = iframeSrc.contains("?") ? iframeSrc.split("\\?")[0] : iframeSrc;
        StringBuilder query = new StringBuilder("url=").append(now);
        if (iframeSrc.contains("ref") || iframeSrc.contains("parentUrl")) {
            query.append("&ref=").append(id);
        }
        if ((iframeSrc.contains("next") || iframeSrc.contains("nextParam")) && !TextUtils.isEmpty(nextPage)) {
            query.append("&next=").append(host).append(nextPage);
        }
        String fullApiUrl = host + apiPath + "?" + query;
        SpiderDebug.log("[Y03YY] 请求API: " + fullApiUrl);

        String apiHtml = get(fullApiUrl);
        if (!TextUtils.isEmpty(apiHtml)) {
            String result = extractVideoFromHtml(apiHtml, id);
            if (result != null) return result;
        }

        SpiderDebug.log("[Y03YY] 兜底返回原始链接");
        return buildParseResult(0, id);
    }

    private String extractVideoFromHtml(String html, String refUrl) {
        Matcher mediaMatcher = Pattern.compile(
                "(?:var|const|let)\\s+mediaInfo\\s*=\\s*(\\[.*?\\]);",
                Pattern.DOTALL).matcher(html);
        if (mediaMatcher.find()) {
            try {
                org.json.JSONArray mediaList = new org.json.JSONArray(mediaMatcher.group(1));
                String bestUrl = null;
                for (int i = 0; i < mediaList.length(); i++) {
                    org.json.JSONObject item = mediaList.getJSONObject(i);
                    String u = item.optString("url", "");
                    if (!u.isEmpty()) {
                        if (item.optString("definition", "").contains("1080P")) { bestUrl = u; break; }
                        if (bestUrl == null) bestUrl = u;
                    }
                }
                if (bestUrl != null) {
                    SpiderDebug.log("[Y03YY] 从 mediaInfo 提取到视频地址: " + bestUrl);
                    return buildVideoResult(bestUrl, refUrl);
                }
            } catch (Exception e) {
                SpiderDebug.log("[Y03YY] mediaInfo 解析失败: " + e.getMessage());
            }
        }

        Matcher videoMatcher = Pattern.compile(
                "(?:var|const|let)\\s+videoUrl\\s*=\\s*\"([^\"]+)\"").matcher(html);
        if (videoMatcher.find()) {
            String videoUrl = videoMatcher.group(1).replace("\\/", "/");
            if (!videoUrl.isEmpty()) {
                SpiderDebug.log("[Y03YY] 从 videoUrl 提取到视频地址: " + videoUrl);
                return buildVideoResult(videoUrl, refUrl);
            }
        }

        return null;
    }

    private String buildVideoResult(String videoUrl, String refUrl) {
        JsonObject header = new JsonObject();
        header.addProperty("User-Agent", userAgent);
        header.addProperty("Referer", refUrl);
        header.addProperty("Origin", host);
        JsonObject result = new JsonObject();
        result.addProperty("parse", 0);
        result.addProperty("url", videoUrl);
        result.add("header", header);
        return result.toString();
    }

    private String buildParseResult(int parse, String url) {
        JsonObject result = new JsonObject();
        result.addProperty("parse", parse);
        result.addProperty("url", url);
        return result.toString();
    }

    private String emptyCategoryResult(int page) {
        JsonObject result = new JsonObject();
        result.add("list", new JsonArray());
        result.addProperty("page", page);
        return result.toString();
    }
}
