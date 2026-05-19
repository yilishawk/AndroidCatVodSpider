package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkHttpWithCookie;
import okhttp3.CookieJar;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
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
    
    // 使用項目的 CookieJar 實例，確保 cf_clearance 能在請求間傳遞
    private CookieJar cookieJar = CookieJar.NO_COOKIES; 

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", ua);
        headers.put("Referer", host + "/");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        return headers;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        // 在訪問首頁時觸發 Cookie 初始化
        OkHttpWithCookie.string(host, getHeaders(), cookieJar);
        
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("movie", "電影"));
        classes.add(new Class("tv", "電視劇"));
        return Result.string(classes, new ArrayList<>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = Integer.parseInt(pg);
        // Python 邏輯：page_index = page - 1
        String url = String.format("%s/cn/%s/---%d-.html", host, tid, page - 1);
        
        String html = OkHttpWithCookie.string(url, getHeaders(), cookieJar);
        Document doc = Jsoup.parse(html);
        Elements items = doc.select(".lists-content ul li");
        
        List<Vod> list = new ArrayList<>();
        for (Element li : items) {
            Element thumbA = li.selectFirst("a.thumbnail");
            if (thumbA == null) continue;
            
            String vodId = thumbA.attr("href");
            String name = li.select("h2 a").text().trim();
            String pic = li.select("img").attr("src");
            if (pic.isEmpty()) pic = li.select("img").attr("data-src");
            String remarks = li.select(".countrie .orange").text().trim();

            list.add(new Vod(vodId, name, pic, remarks));
        }
        return Result.string(list);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String vodId = ids.get(0);
        String url = vodId.startsWith("http") ? vodId : host + vodId;
        
        String html = OkHttpWithCookie.string(url, getHeaders(), cookieJar);
        Document doc = Jsoup.parse(html);
        
        Vod vod = new Vod();
        vod.setVodId(vodId);
        
        // 解析標題與年份：例如 "名偵探柯南 (1996)"
        Element titleElem = doc.selectFirst("h1.product-title");
        if (titleElem != null) {
            String fullTitle = titleElem.text().trim();
            Matcher titleMatcher = Pattern.compile("(.+?)\\s*\\((\\d{4})\\)").matcher(fullTitle);
            if (titleMatcher.find()) {
                vod.setVodName(titleMatcher.group(1).trim());
                vod.setVodYear(titleMatcher.group(2));
            } else {
                vod.setVodName(fullTitle);
            }
        }

        vod.setVodPic(doc.select(".product-header img.thumb").attr("src"));
        vod.setVodDirector(doc.select(".product-excerpt:contains(導演) span a").text());
        vod.setVodActor(doc.select(".product-excerpt:contains(主演) span a").text());
        vod.setVodArea(doc.select(".product-excerpt:contains(國家) span a").text());
        vod.setVodContent(doc.select(".product-excerpt:contains(簡介) span").text().trim());

        // 提取 script 中的 infoid 和 m3u8 播放數組
        String scriptText = "";
        for (Element script : doc.select("script")) {
            String scriptHtml = script.html();
            if (scriptHtml.contains("infoid") && scriptHtml.contains("m3u8")) {
                scriptText = scriptHtml;
                break;
            }
        }

        String infoid = "";
        List<String> episodes = new ArrayList<>();
        Matcher infoMatcher = Pattern.compile("infoid\\s*=\\s*(\\d+)").matcher(scriptText);
        if (infoMatcher.find()) infoid = infoMatcher.group(1);
        
        Matcher m3u8Matcher = Pattern.compile("m3u8\\s*=\\s*\\[(.*?)\\]").matcher(scriptText);
        if (m3u8Matcher.find()) {
            String arrayContent = m3u8Matcher.group(1);
            Matcher epMatcher = Pattern.compile("['\"]?(\\d+)['\"]?").matcher(arrayContent);
            while (epMatcher.find()) {
                episodes.add(epMatcher.group(1));
            }
        }

        if (!infoid.isEmpty() && !episodes.isEmpty()) {
            List<String> playUrls = new ArrayList<>();
            for (String ep : episodes) {
                // 格式：第01集$/info/m3u8/8141/1.m3u8
                playUrls.add("第" + ep + "集$/info/m3u8/" + infoid + "/" + ep + ".m3u8");
            }
            vod.setVodPlayFrom("PPnix");
            vod.setVodPlayUrl(String.join("#", playUrls));
        }

        return Result.string(vod);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // 1. 域名隨機化 (IPFS 分布式節點切換)
        String m3u8Url = id.startsWith("http") ? id : host + id;
        try {
            int randNum = new Random().nextInt(16) + 1;
            // 抓包顯示 authority 常為 www.ppnix.com，
            // 但 Python 代碼中會替換 ipfs.ppnix.com -> {1-16}.ppnix.com
            m3u8Url = m3u8Url.replace("ipfs.ppnix.com", randNum + ".ppnix.com");
        } catch (Exception ignored) {}

        // 2. 構造抓包所見的核心 Headers
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", ua);
        headers.put("Accept", "*/*");
        headers.put("sec-ch-ua-mobile", "?1");
        headers.put("sec-fetch-site", "same-origin");
        headers.put("sec-fetch-mode", "cors");
        headers.put("sec-fetch-dest", "empty");
        headers.put("Origin", host);
        
        // 動態生成抓包中的 Referer: https://www.ppnix.com/cn/tv/8141.html
        String referer = host + "/";
        Matcher m = Pattern.compile("/info/m3u8/(\\d+)/").matcher(id);
        if (m.find()) {
            referer = host + "/cn/tv/" + m.group(1) + ".html";
        }
        headers.put("Referer", referer);

        // 3. 返回結果 (Result 會自動將 headers 轉為 JSON 字符串)
        return Result.get().url(m3u8Url).header(headers).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return Result.string(new ArrayList<>());
    }
}
