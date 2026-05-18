package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 03影院 - 需要先访问首页获取 Cookie
 * 站点: https://www.03yy.live
 */
public class Y03YY extends Spider {

    private String host = "https://www.03yy.live";
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    
    // 存储 Cookie
    private String cookie = "";
    
    // 自定义 OkHttpClient（支持 Cookie 自动管理）
    private OkHttpClient client;

    public Y03YY() {
        // 初始化 OkHttpClient，自动管理 Cookie
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        SpiderDebug.log("[Y03YY] init called");
        // 先访问首页获取 Cookie
        fetchHomePageCookie();
    }

    /**
     * 访问首页获取 Cookie
     */
    private void fetchHomePageCookie() {
        try {
            SpiderDebug.log("[Y03YY] 访问首页获取 Cookie: " + host);
            
            Request request = new Request.Builder()
                    .url(host + "/")
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .build();
            
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    // 提取 Cookie
                    Headers headers = response.headers();
                    List<String> cookies = headers.values("Set-Cookie");
                    StringBuilder cookieBuilder = new StringBuilder();
                    for (String c : cookies) {
                        String cookieValue = c.split(";")[0];
                        if (cookieBuilder.length() > 0) {
                            cookieBuilder.append("; ");
                        }
                        cookieBuilder.append(cookieValue);
                    }
                    cookie = cookieBuilder.toString();
                    SpiderDebug.log("[Y03YY] 获取到 Cookie: " + cookie);
                } else {
                    SpiderDebug.log("[Y03YY] 首页请求失败: " + response.code());
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("[Y03YY] 获取 Cookie 失败: " + e.getMessage());
        }
    }

    /**
     * 获取请求头（包含 Cookie）
     */
    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", userAgent);
        headers.put("Referer", host + "/");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        if (!TextUtils.isEmpty(cookie)) {
            headers.put("Cookie", cookie);
        }
        return headers;
    }

    /**
 * 带 Cookie 的 GET 请求 - 改用自定义 client 确保 Cookie 生效
 */
    private String get(String url) {
    try {
        Map<String, String> hdrs = getHeaders();
        Request.Builder builder = new Request.Builder().url(url);
        for (Map.Entry<String, String> entry : hdrs.entrySet()) {
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

    /**
     * 刷新 Cookie（当请求失败时重新获取）
     */
    private void refreshCookie() {
        SpiderDebug.log("[Y03YY] 刷新 Cookie");
        fetchHomePageCookie();
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
            
            String[][] tvTypes = {{"全部", ""}, {"大陆剧", "13"}, {"欧美剧", "27"}, {"韩国剧", "26"}, {"香港剧", "14"}, {"台湾剧", "46"}};
            String[][] movieTypes = {{"全部", ""}, {"动作片", "5"}, {"喜剧片", "10"}, {"爱情片", "6"}, {"科幻片", "7"}};

            filters.add("13", createFilterArray("class", "类型", tvTypes));
            filters.add("1", createFilterArray("class", "类型", movieTypes));
            filters.add("3", createFilterArray("class", "类型", tvTypes));
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

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = Integer.parseInt(pg);
        String subTid = (extend != null && extend.containsKey("class") && !extend.get("class").isEmpty()) ? extend.get("class") : tid;
        
        String url = host + "/type/index" + subTid + "-" + page + ".html";
        SpiderDebug.log("[Y03YY] category URL: " + url);
        
        String html = get(url);
        
        // 如果返回空或包含重定向，刷新 Cookie 重试一次
        if (TextUtils.isEmpty(html) || html.contains("window.location") || html.contains("document.location")) {
            SpiderDebug.log("[Y03YY] 请求失败或需要重定向，刷新 Cookie 重试");
            refreshCookie();
            html = get(url);
        }
        
        if (TextUtils.isEmpty(html)) {
            return emptyCategoryResult(page);
        }

        Document doc = Jsoup.parse(html);
        Elements items = doc.select(".Pic-list li, .pic-list li, .vodlist li, .pic-content");
        JsonArray list = new JsonArray();
        
        for (Element item : items) {
            Element a = item.selectFirst("a");
            if (a == null) continue;

            String href = a.attr("href");
            String vid = extractVid(href);
            if (vid.isEmpty()) continue;

            String title = a.attr("title");
            if (TextUtils.isEmpty(title)) {
                Element h4 = item.selectFirst("h4");
                if (h4 != null) title = h4.text();
                else title = item.selectFirst(".title") != null ? item.selectFirst(".title").text() : "";
            }

            String pic = a.select("img").attr("data-original");
            if (TextUtils.isEmpty(pic)) pic = a.select("img").attr("src");
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
        result.addProperty("total", 999);
        return result.toString();
    }

    private String extractVid(String href) {
        if (TextUtils.isEmpty(href)) return "";
        Matcher m = Pattern.compile("index(\\d+)\\.html").matcher(href);
        if (m.find()) return m.group(1);
        // 兜底：只提取数字
        String digits = href.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? "" : digits;
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return "{\"list\":[]}";
        
        String vid = ids.get(0);
        String url = host + "/movie/index" + vid + ".html";
        SpiderDebug.log("[Y03YY] detail URL: " + url);
        
        String html = get(url);
        
        if (TextUtils.isEmpty(html) || html.contains("window.location") || html.contains("document.location")) {
            SpiderDebug.log("[Y03YY] 详情页请求失败，刷新 Cookie 重试");
            refreshCookie();
            html = get(url);
        }
        
        if (TextUtils.isEmpty(html)) {
            return "{\"list\":[]}";
        }
        
        Document doc = Jsoup.parse(html);

        JsonObject vod = new JsonObject();
        vod.addProperty("vod_id", vid);
        
        Element h1 = doc.selectFirst("h1");
        vod.addProperty("vod_name", h1 != null ? h1.text().trim() : "");
        
        // 播放源解析
        Elements tabs = doc.select(".playfrom #playlist li");
        
        // 备用选择器
        if (tabs.isEmpty()) {
            tabs = doc.select(".play-tabs li, .source-tab li, .play-source li");
        }
        
        List<String> fromList = new ArrayList<>();
        List<String> urlList = new ArrayList<>();
        
        if (!tabs.isEmpty()) {
            for (int i = 0; i < tabs.size(); i++) {
                String tabText = tabs.get(i).text().trim();
                if (TextUtils.isEmpty(tabText)) tabText = "线路" + (i + 1);
                fromList.add(tabText);
                
                // 获取对应的播放列表
                Elements aList = new Elements();
                
                // 尝试多种选择器格式
                if (i < 8) {
                    aList = doc.select("#stab8" + (i + 1) + " a");
                }
                if (aList.isEmpty()) {
                    aList = doc.select(".playlist-" + (i + 1) + " a, .content-" + (i + 1) + " a");
                }
                if (aList.isEmpty() && i == 0) {
                    aList = doc.select(".playlist a, .video-list a");
                }
                
                List<String> eps = new ArrayList<>();
                for (Element a : aList) {
                    String name = a.text().trim();
                    String link = a.attr("href");
                    if (!link.startsWith("http")) link = host + link;
                    eps.add(name + "$" + link);
                }
                
                if (eps.isEmpty()) {
                    // 如果没有找到集数，尝试直接获取播放地址
                    Element playerIframe = doc.selectFirst("#playIframe, .player-iframe, iframe");
                    if (playerIframe != null) {
                        String src = playerIframe.attr("src");
                        if (!src.startsWith("http")) src = host + src;
                        eps.add("播放$" + src);
                    }
                }
                
                urlList.add(TextUtils.join("#", eps));
            }
        } else {
            // 没有找到 tabs，尝试直接获取播放器
            fromList.add("默认线路");
            Element playerIframe = doc.selectFirst("#playIframe, .player-iframe, iframe");
            if (playerIframe != null) {
                String src = playerIframe.attr("src");
                if (!src.startsWith("http")) src = host + src;
                urlList.add("播放$" + src);
            } else {
                urlList.add("");
            }
        }

        vod.addProperty("vod_play_from", TextUtils.join("$$$", fromList));
        vod.addProperty("vod_play_url", TextUtils.join("$$$", urlList));
        
        // 提取其他信息
        Element picElem = doc.selectFirst(".vod-img img, .pic img, .poster img");
        if (picElem != null) {
            String pic = picElem.attr("data-original");
            if (TextUtils.isEmpty(pic)) pic = picElem.attr("src");
            if (!pic.isEmpty() && !pic.startsWith("http")) pic = host + pic;
            vod.addProperty("vod_pic", pic);
        } else {
            vod.addProperty("vod_pic", "");
        }
        
        Element contentElem = doc.selectFirst(".vod-content, .intro, .description");
        if (contentElem != null) {
            vod.addProperty("vod_content", contentElem.text().trim());
        } else {
            vod.addProperty("vod_content", "");
        }
        
        JsonArray list = new JsonArray();
        list.add(vod);
        JsonObject result = new JsonObject();
        result.add("list", list);
        return result.toString();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        String url = host + "/search.php?searchword=" + URLEncoder.encode(key, "UTF-8");
        SpiderDebug.log("[Y03YY] search URL: " + url);
        
        String html = get(url);
        
        if (TextUtils.isEmpty(html) || html.contains("window.location") || html.contains("document.location")) {
            refreshCookie();
            html = get(url);
        }
        
        if (TextUtils.isEmpty(html)) {
            return "{\"list\":[]}";
        }
        
        Document doc = Jsoup.parse(html);
        Elements items = doc.select(".Pic-list li, .pic-content, .search-item");
        JsonArray list = new JsonArray();
        
        for (Element item : items) {
            Element a = item.selectFirst("a");
            if (a == null) continue;
            
            String href = a.attr("href");
            String vid = extractVid(href);
            if (vid.isEmpty()) continue;
            
            String title = a.attr("title");
            if (TextUtils.isEmpty(title)) {
                Element h4 = item.selectFirst("h4");
                if (h4 != null) title = h4.text();
            }
            
            String pic = a.select("img").attr("data-original");
            if (TextUtils.isEmpty(pic)) pic = a.select("img").attr("src");
            if (!pic.isEmpty() && !pic.startsWith("http")) pic = host + pic;
            
            JsonObject vod = new JsonObject();
            vod.addProperty("vod_id", vid);
            vod.addProperty("vod_name", title.trim());
            vod.addProperty("vod_pic", pic);
            list.add(vod);
        }
        
        JsonObject result = new JsonObject();
        result.add("list", list);
        return result.toString();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        SpiderDebug.log("[Y03YY] playerContent: flag=" + flag + ", id=" + id);
        
        String playUrl = id.startsWith("http") ? id : host + id;
        
        // 如果是 m3u8 或 mp4 链接，直接播放
        if (playUrl.contains(".m3u8") || playUrl.contains(".mp4")) {
            JsonObject result = new JsonObject();
            result.addProperty("parse", 0);
            result.addProperty("url", playUrl);
            JsonObject header = new JsonObject();
            header.addProperty("User-Agent", userAgent);
            header.addProperty("Referer", host + "/");
            result.add("header", header);
            return result.toString();
        }
        
        // 否则让 App 自动嗅探
        JsonObject result = new JsonObject();
        result.addProperty("parse", 1);
        result.addProperty("url", playUrl);
        return result.toString();
    }

    private String emptyCategoryResult(int page) {
        JsonObject result = new JsonObject();
        result.add("list", new JsonArray());
        result.addProperty("page", page);
        return result.toString();
    }
}
