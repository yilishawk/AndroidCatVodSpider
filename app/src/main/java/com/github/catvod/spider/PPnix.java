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
import android.util.Base64;
import java.util.Random;

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
public String playerContent(String flag, String id, List<String> vipFlags) {
    try {
        // 1. 直连还原基础 M3U8 链接
        String m3u8Url = id.startsWith("http") ? id : HOST + id;

        // 2. 动态构建严苛的 Referer
        String referer = HOST + "/";
        Matcher mInfo = Pattern.compile("/info/m3u8/(\\d+)/").matcher(id);
        if (mInfo.find()) {
            String categoryType = id.contains("type=movie") ? "movie" : "tv";
            referer = HOST + "/cn/" + categoryType + "/" + mInfo.group(1) + ".html";
        }

        logger("▶️ [播放] 目标 URL: " + m3u8Url);
        logger("🔗 [播放] Referer: " + referer);

        // ★★★ 关键：下载 M3U8 内容并替换 TS 分片域名 ★★★
        String modifiedM3u8Content = downloadAndModifyM3u8(m3u8Url, referer);
        
        if (modifiedM3u8Content != null) {
            // 如果修改成功，将 M3U8 内容上传到临时存储或直接返回
            // 方法1：使用 Data URI（适合小文件）
            String dataUri = "data:application/vnd.apple.mpegurl;base64," + 
                             Base64.getEncoder().encodeToString(modifiedM3u8Content.getBytes("UTF-8"));
            m3u8Url = dataUri;
            logger("✅ [播放] M3U8 内容已修改并转为 Data URI");
        }

        // 3. 构造完整的请求头
        JSONObject headersObj = new JSONObject();
        
        headersObj.put("User-Agent", UA);
        headersObj.put("Accept", "*/*");
        headersObj.put("Accept-Language", "zh-CN,zh;q=0.9");
        headersObj.put("Accept-Encoding", "gzip, deflate, br");
        headersObj.put("Connection", "keep-alive");
        headersObj.put("Cache-Control", "no-cache");
        headersObj.put("Referer", referer);
        headersObj.put("Origin", HOST);
        headersObj.put("origin", HOST);
        headersObj.put("Sec-Fetch-Site", "same-origin");
        headersObj.put("Sec-Fetch-Mode", "cors");
        headersObj.put("Sec-Fetch-Dest", "empty");
        
        // 4. Cookie 处理
        try {
            String completeCookie = CookieManager.getInstance().getCookie(HOST);
            if (!TextUtils.isEmpty(completeCookie)) {
                headersObj.put("Cookie", completeCookie);
                headersObj.put("cookie", completeCookie);
                logger("🍪 [播放] Cookie 已注入");
                
                java.net.URL pUrl = new java.net.URL(m3u8Url);
                String playHost = pUrl.getProtocol() + "://" + pUrl.getHost();
                CookieManager.getInstance().setCookie(playHost, completeCookie);
                CookieManager.getInstance().flush();
            } else {
                logger("⚠️ [播放] 未检测到 Cookie");
            }
        } catch (Exception ce) {
            logger("🚨 [播放] Cookie 获取异常: " + ce.getMessage());
        }

        // 5. 组装返回 JSON
        JSONObject result = new JSONObject();
        result.put("parse", 0);
        result.put("url", m3u8Url);
        result.put("header", headersObj.toString());
        
        logger("📋 [播放请求头] " + headersObj.toString());
        
        return result.toString();
        
    } catch (Exception e) {
        logger("🚨 [播放异常] " + e.getMessage());
        e.printStackTrace();
        return "{}";
    }
}

/**
 * 下载 M3U8 文件并替换 TS 分片域名
 * Python 逻辑：ipfs.ppnix.com -> 随机数字.ppnix.com
 */
    private String downloadAndModifyM3u8(String m3u8Url, String referer) {
    try {
        // 下载 M3U8 内容
        String m3u8Content = get(m3u8Url, referer);
        if (TextUtils.isEmpty(m3u8Content)) {
            logger("⚠️ [M3U8] 下载失败");
            return null;
        }
        
        logger("📥 [M3U8] 原始内容长度: " + m3u8Content.length());
        
        // 按行处理
        String[] lines = m3u8Content.split("\n");
        StringBuilder modified = new StringBuilder();
        Random random = new Random();
        
        for (String line : lines) {
            if (line.startsWith("#")) {
                // 注释行直接保留
                modified.append(line).append("\n");
            } else if (line.trim().startsWith("http")) {
                // TS 分片 URL，需要替换域名
                try {
                    java.net.URL url = new java.net.URL(line.trim());
                    String host = url.getHost();
                    
                    if (host.contains("ipfs.ppnix.com")) {
                        // 随机 1-16 的数字前缀
                        int randNum = random.nextInt(16) + 1;
                        String newHost = randNum + ".ppnix.com";
                        String newUrl = line.replace(host, newHost);
                        modified.append(newUrl).append("\n");
                        logger("🔀 [域名替换] " + host + " -> " + newHost);
                    } else {
                        modified.append(line).append("\n");
                    }
                } catch (Exception e) {
                    modified.append(line).append("\n");
                }
            } else {
                modified.append(line).append("\n");
            }
        }
        
        logger("✅ [M3U8] 修改完成，新内容长度: " + modified.length());
        return modified.toString();
        
    } catch (Exception e) {
        logger("🚨 [M3U8] 处理异常: " + e.getMessage());
        return null;
    }
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
