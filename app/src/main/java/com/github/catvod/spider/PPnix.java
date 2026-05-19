package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttpWithCookie;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PPnix extends Spider {

    private final String host = "https://www.ppnix.com";
    private final String ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";
    
    // 关键：必须使用同一个 CookieJar 实例来运行整个生命周期
    private final HashMap<String, List<Cookie>> cookieStore = new HashMap<>();
    private final CookieJar cookieJar = new CookieJar() {
        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            if (cookies != null && !cookies.isEmpty()) {
                cookieStore.put(url.host(), cookies);
                for (Cookie c : cookies) {
                    SpiderDebug.log("PPnix 存入 Cookie: [" + url.host() + "] " + c.name() + "=" + c.value());
                }
            }
        }
        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            List<Cookie> cookies = cookieStore.get(url.host());
            return cookies != null ? cookies : new ArrayList<>();
        }
    };

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", ua);
        headers.put("Referer", host + "/");
        return headers;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        SpiderDebug.log("PPnix: 正在初始化首页并尝试触发 Cloudflare 验证...");
        OkHttpWithCookie.string(host, getHeaders(), cookieJar);
        
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("movie", "电影"));
        classes.add(new Class("tv", "电视剧"));
        return Result.string(classes, new ArrayList<>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = Integer.parseInt(pg);
        String url = String.format("%s/cn/%s/---%d-.html", host, tid, page - 1);
        String html = OkHttpWithCookie.string(url, getHeaders(), cookieJar);
        Document doc = Jsoup.parse(html);
        Elements items = doc.select(".lists-content ul li");
        List<Vod> list = new ArrayList<>();
        for (Element li : items) {
            Element thumbA = li.selectFirst("a");
            if (thumbA == null) continue;
            String pic = li.select("img").attr("data-src");
            if (pic.isEmpty()) pic = li.select("img").attr("src");
            if (pic.startsWith("/")) pic = host + pic;
            list.add(new Vod(thumbA.attr("href"), li.select("h2").text().trim(), pic, li.select(".orange").text()));
        }
        return Result.string(list);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String url = ids.get(0).startsWith("http") ? ids.get(0) : host + ids.get(0);
        SpiderDebug.log("PPnix: 正在获取详情页: " + url);
        String html = OkHttpWithCookie.string(url, getHeaders(), cookieJar);
        Document doc = Jsoup.parse(html);
        
        Vod vod = new Vod();
        vod.setVodId(ids.get(0));
        vod.setVodName(doc.selectFirst(".product-title").text().replaceAll("\\(\\d+\\)", "").trim());
        vod.setVodPic(doc.select(".product-header img").attr("src"));
        
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

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String m3u8Url = id.startsWith("http") ? id : host + id;
        
        // 1. 域名随机化
        try {
            int randNum = new Random().nextInt(16) + 1;
            m3u8Url = m3u8Url.replace("ipfs.ppnix.com", randNum + ".ppnix.com");
        } catch (Exception ignored) {}

        // 2. 准备 Headers
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", ua);
        
        // 动态 Referer
        String referer = host + "/";
        Matcher m = Pattern.compile("/m3u8/(\\d+)/").matcher(id);
        if (m.find()) referer = host + "/cn/tv/" + m.group(1) + ".html";
        headers.put("Referer", referer);

        // 3. 提取内存中的 Cookie 并记录日志
        List<Cookie> cookies = cookieJar.loadForRequest(HttpUrl.parse(host));
        if (cookies != null && !cookies.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Cookie cookie : cookies) {
                sb.append(cookie.name()).append("=").append(cookie.value()).append("; ");
            }
            String cookieStr = sb.toString().trim();
            if (cookieStr.endsWith(";")) cookieStr = cookieStr.substring(0, cookieStr.length() - 1);
            
            headers.put("Cookie", cookieStr);
            SpiderDebug.log("PPnix 注入播放器 Cookie: " + cookieStr);
        } else {
            SpiderDebug.log("PPnix 警告: 播放器请求时 Cookie 库为空！");
        }

        SpiderDebug.log("PPnix 最终播放直链: " + m3u8Url);
        SpiderDebug.log("PPnix 最终 Referer: " + referer);

        // 4. 返回带 header 的 Result
        return Result.get().url(m3u8Url).header(headers).string();
    }
}
