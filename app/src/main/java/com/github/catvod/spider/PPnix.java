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
    
    // 强制使用单例模式维护 Cookie，防止在不同生命周期被重置
    private final HashMap<String, List<Cookie>> cookieStore = new HashMap<>();
    private final CookieJar cookieJar = new CookieJar() {
        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            if (cookies != null && !cookies.isEmpty()) {
                cookieStore.put(url.host(), cookies);
                for (Cookie c : cookies) {
                    SpiderDebug.log("PPnix 收到 Cookie: [" + url.host() + "] " + c.name() + "=" + c.value());
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
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        return headers;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        SpiderDebug.log("PPnix: 正在加载首页，初始化验证状态...");
        // 1. 访问首页以触发 Cloudflare 验证并获取 cf_clearance
        String html = OkHttpWithCookie.string(host, getHeaders(), cookieJar);
        
        // 2. 检查是否获取到关键 Cookie
        List<Cookie> cookies = cookieJar.loadForRequest(HttpUrl.parse(host));
        if (cookies.isEmpty()) {
            SpiderDebug.log("PPnix 警告: 首页访问后 Cookie 库仍为空，尝试二次访问...");
            OkHttpWithCookie.string(host + "/cn/tv/---0-.html", getHeaders(), cookieJar);
        } else {
            SpiderDebug.log("PPnix 首页初始化成功，已获取 Cookie 数量: " + cookies.size());
        }

        // 3. 定义分类
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("movie", "电影"));
        classes.add(new Class("tv", "电视剧"));
        classes.add(new Class("comic", "动漫"));
        
        return Result.string(classes, new ArrayList<>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = Integer.parseInt(pg);
        // 分页逻辑遵循 Python：page-1
        String url = String.format("%s/cn/%s/---%d-.html", host, tid, page - 1);
        SpiderDebug.log("PPnix 分类请求: " + url);
        
        String html = OkHttpWithCookie.string(url, getHeaders(), cookieJar);
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

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String vodId = ids.get(0);
        String url = vodId.startsWith("http") ? vodId : host + vodId;
        SpiderDebug.log("PPnix 详情页请求: " + url);
        
        String html = OkHttpWithCookie.string(url, getHeaders(), cookieJar);
        Document doc = Jsoup.parse(html);
        
        Vod vod = new Vod();
        vod.setVodId(vodId);
        vod.setVodName(doc.selectFirst(".product-title").text().replaceAll("\\(\\d+\\)", "").trim());
        vod.setVodPic(doc.select(".product-header img").attr("src"));
        vod.setVodActor(doc.select(".product-excerpt:contains(主演) span").text());
        vod.setVodContent(doc.select(".product-excerpt:contains(简介) span").text().trim());

        // 解析播放列表脚本
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
                // 拼接直链路径
                urls.add("第" + ep + "集$/info/m3u8/" + infoid + "/" + ep + ".m3u8");
            }
            vod.setVodPlayFrom("PPnix");
            vod.setVodPlayUrl(String.join("#", urls));
        }
        return Result.string(vod);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // 1. 获取 ID 对应的原始详情页（用于 Referer 和 预检）
        String infoId = "";
        Matcher m = Pattern.compile("/m3u8/(\\d+)/").matcher(id);
        if (m.find()) infoId = m.group(1);
        String refererUrl = host + "/cn/tv/" + infoId + ".html";

        // 2. 检查 Cookie，如果为空，强制再刷一次详情页
        List<Cookie> currentCookies = cookieJar.loadForRequest(HttpUrl.parse(host));
        if (currentCookies.isEmpty()) {
            SpiderDebug.log("PPnix: 播放前检查 Cookie 为空，正在强制补验证...");
            OkHttpWithCookie.string(refererUrl, getHeaders(), cookieJar);
            currentCookies = cookieJar.loadForRequest(HttpUrl.parse(host));
        }

        // 3. 构建播放地址
        String m3u8Url = id.startsWith("http") ? id : host + id;
        try {
            // 域名混淆处理 (1-16.ppnix.com)
            int randNum = new Random().nextInt(16) + 1;
            m3u8Url = m3u8Url.replace("ipfs.ppnix.com", randNum + ".ppnix.com");
        } catch (Exception ignored) {}

        // 4. 构建 Header
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", ua);
        headers.put("Referer", refererUrl);

        // 5. 将 Cookie 对象转为播放器可识别的字符串格式
        if (!currentCookies.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Cookie cookie : currentCookies) {
                sb.append(cookie.name()).append("=").append(cookie.value()).append("; ");
            }
            String cookieStr = sb.toString().trim();
            if (cookieStr.endsWith(";")) cookieStr = cookieStr.substring(0, cookieStr.length() - 1);
            
            headers.put("Cookie", cookieStr);
            SpiderDebug.log("PPnix 成功向播放器注入 Header -> Cookie: " + cookieStr);
        }

        // 返回播放结果，TVBox 会自动处理 header 中的 Cookie
        return Result.get().url(m3u8Url).header(headers).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        String url = host + "/cn/search/-------------.html?wd=" + key;
        String html = OkHttpWithCookie.string(url, getHeaders(), cookieJar);
        Document doc = Jsoup.parse(html);
        Elements items = doc.select(".lists-content li");
        List<Vod> list = new ArrayList<>();
        for (Element li : items) {
            String name = li.select("h2").text().trim();
            if (name.isEmpty()) continue;
            list.add(new Vod(li.select("a").attr("href"), name, li.select("img").attr("data-src"), ""));
        }
        return Result.string(list);
    }
}
