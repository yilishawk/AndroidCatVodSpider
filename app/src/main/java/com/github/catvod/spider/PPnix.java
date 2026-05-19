package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.SSLCompat;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PPnix extends Spider {

    private final String host = "https://www.ppnix.com";
    private final String ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";

    private final HashMap<String, List<Cookie>> cookieStore = new HashMap<>();

    private final CookieJar cookieJar = new CookieJar() {
        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            if (cookies != null && !cookies.isEmpty()) {
                // merge，不直接覆盖
                Map<String, Cookie> map = new HashMap<>();
                List<Cookie> existing = cookieStore.containsKey(url.host())
                        ? cookieStore.get(url.host()) : new ArrayList<Cookie>();
                for (Cookie c : existing) map.put(c.name(), c);
                for (Cookie c : cookies) map.put(c.name(), c);
                cookieStore.put(url.host(), new ArrayList<>(map.values()));
                for (Cookie c : cookies) {
                    SpiderDebug.log("PPnix 收到 Cookie: [" + url.host() + "] " + c.name() + "=" + c.value());
                }
            }
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            List<Cookie> cookies = cookieStore.get(url.host());
            return cookies != null ? cookies : new ArrayList<Cookie>();
        }
    };

    // 自建 client：绑定 cookieJar + SSLCompat，确保 Cookie 全程生效
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
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", ua);
        headers.put("Referer", host + "/");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        return headers;
    }

    private String get(String url) {
        return get(url, getHeaders());
    }

    private String get(String url, Map<String, String> headers) {
        try {
            Request.Builder builder = new Request.Builder().url(url);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                builder.header(e.getKey(), e.getValue());
            }
            try (Response response = client.newCall(builder.build()).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    return response.body().string();
                }
                SpiderDebug.log("PPnix 请求失败: " + url + " code=" + response.code());
                return "";
            }
        } catch (Exception e) {
            SpiderDebug.log("PPnix 请求异常: " + url + " " + e.getMessage());
            return "";
        }
    }

    // ==================== 首页 ====================

    @Override
    public String homeContent(boolean filter) throws Exception {
        SpiderDebug.log("PPnix: 正在加载首页，初始化 Cookie...");
        get(host);

        List<Cookie> cookies = cookieJar.loadForRequest(HttpUrl.parse(host));
        if (cookies.isEmpty()) {
            SpiderDebug.log("PPnix 警告: Cookie 仍为空，尝试二次访问...");
            get(host + "/cn/tv/---0-.html");
        } else {
            SpiderDebug.log("PPnix 初始化成功，Cookie 数量: " + cookies.size());
        }

        List<Class> classes = new ArrayList<>();
        classes.add(new Class("movie", "电影"));
        classes.add(new Class("tv",    "电视剧"));
        classes.add(new Class("comic", "动漫"));

        return Result.string(classes, new ArrayList<>());
    }

    // ==================== 分类列表 ====================

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = Integer.parseInt(pg);
        String url = String.format("%s/cn/%s/---%d-.html", host, tid, page - 1);
        SpiderDebug.log("PPnix 分类请求: " + url);

        String html = get(url);
        Document doc = Jsoup.parse(html);
        Elements items = doc.select(".lists-content ul li");

        List<Vod> list = new ArrayList<>();
        for (Element li : items) {
            Element thumbA = li.selectFirst("a");
            if (thumbA == null) continue;

            String vodId = thumbA.attr("href");
            String name = li.select("h2").text().trim();
            String pic = li.select("img").attr("data-src");
            if (pic.isEmpty()) pic = li.select("img").attr("src");
            if (pic.startsWith("/")) pic = host + pic;
            String remarks = li.select(".orange").text().trim();

            list.add(new Vod(vodId, name, pic, remarks));
        }
        return Result.string(list);
    }

    // ==================== 详情页 ====================

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String vodId = ids.get(0);
        String url = vodId.startsWith("http") ? vodId : host + vodId;
        SpiderDebug.log("PPnix 详情页请求: " + url);

        String html = get(url);
        Document doc = Jsoup.parse(html);

        Vod vod = new Vod();
        vod.setVodId(vodId);
        vod.setVodName(doc.selectFirst(".product-title").text().replaceAll("\\(\\d+\\)", "").trim());
        vod.setVodPic(doc.select(".product-header img").attr("src"));
        vod.setVodActor(doc.select(".product-excerpt:contains(主演) span").text());
        vod.setVodContent(doc.select(".product-excerpt:contains(简介) span").text().trim());

        String scriptText = "";
        for (Element script : doc.select("script")) {
            if (script.html().contains("infoid") && script.html().contains("m3u8")) {
                scriptText = script.html();
                break;
            }
        }

        String infoid = "";
        Matcher im = Pattern.compile("infoid\\s*=\\s*(\\d+)").matcher(scriptText);
        if (im.find()) infoid = im.group(1);

        Matcher mm = Pattern.compile("m3u8\\s*=\\s*\\[(.*?)\\]").matcher(scriptText);
        if (mm.find() && !infoid.isEmpty()) {
            String[] eps = mm.group(1).replaceAll("['\"\\s]", "").split(",");
            List<String> urls = new ArrayList<>();
            for (String ep : eps) {
                urls.add("第" + ep + "集$/info/m3u8/" + infoid + "/" + ep + ".m3u8");
            }
            vod.setVodPlayFrom("PPnix");
            vod.setVodPlayUrl(String.join("#", urls));
        }
        return Result.string(vod);
    }

    // ==================== 播放解析 ====================

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String infoId = "";
        Matcher m = Pattern.compile("/m3u8/(\\d+)/").matcher(id);
        if (m.find()) infoId = m.group(1);
        String refererUrl = host + "/cn/tv/" + infoId + ".html";

        // Cookie 为空时强制补刷
        List<Cookie> currentCookies = cookieJar.loadForRequest(HttpUrl.parse(host));
        if (currentCookies.isEmpty()) {
            SpiderDebug.log("PPnix: 播放前 Cookie 为空，强制补验证...");
            get(refererUrl);
            currentCookies = cookieJar.loadForRequest(HttpUrl.parse(host));
        }

        String m3u8Url = id.startsWith("http") ? id : host + id;
        try {
            int randNum = new Random().nextInt(16) + 1;
            m3u8Url = m3u8Url.replace("ipfs.ppnix.com", randNum + ".ppnix.com");
        } catch (Exception ignored) {}

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", ua);
        headers.put("Referer", refererUrl);

        if (!currentCookies.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Cookie cookie : currentCookies) {
                sb.append(cookie.name()).append("=").append(cookie.value()).append("; ");
            }
            String cookieStr = sb.toString().trim();
            if (cookieStr.endsWith(";")) cookieStr = cookieStr.substring(0, cookieStr.length() - 1);
            headers.put("Cookie", cookieStr);
            SpiderDebug.log("PPnix 注入 Cookie: " + cookieStr);
        }

        return Result.get().url(m3u8Url).header(headers).string();
    }

    // ==================== 搜索 ====================

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        String url = host + "/cn/search/-------------.html?wd=" + key;
        String html = get(url);
        Document doc = Jsoup.parse(html);
        Elements items = doc.select(".lists-content li");
        List<Vod> list = new ArrayList<>();
        for (Element li : items) {
            String name = li.select("h2").text().trim();
            if (name.isEmpty()) continue;
            list.add(new Vod(
                    li.select("a").attr("href"),
                    name,
                    li.select("img").attr("data-src"),
                    ""
            ));
        }
        return Result.string(list);
    }
}
