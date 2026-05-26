package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class FkTv extends Spider {

    private final String siteUrl = "https://fktv.me";
    private final String imageSearchUrl = "https://hongniuzy.tv/index.php/ajax/suggest.html?mid=1&wd=";
    
    private final String UA = "Mozilla/5.0 (Linux; Android 15; 23054RA19C Build/AP3A.240905.015.A2; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/147.0.7727.137 Mobile Safari/537.36";
    
    // 使用你提供的 Cookie
    private final String COOKIE = "_did=wEdXiQxa07zJ15hm0AsNjxsc4rZRSKzb; _device=pc";

    private Map<String, String> getHeader() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", siteUrl + "/");
        headers.put("Accept", "application/json, text/javascript, */*; q=0.01");
        headers.put("Cookie", COOKIE);           // 固定使用此 Cookie
        return headers;
    }

    // 多线程获取图片
    private List<Vod> parseListWithImage(String html) {
        List<Vod> list = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        Elements items = doc.select("div.item-wrap.vertical");
        ExecutorService executor = Executors.newFixedThreadPool(8);

        for (Element item : items) {
            Element a = item.selectFirst("a[href^=/movie/detail]");
            if (a == null) continue;

            String href = siteUrl + a.attr("href");
            String name = a.attr("title");
            String remark = item.selectFirst(".category") != null ? item.selectFirst(".category").text() : "";

            Vod vod = new Vod();
            vod.setVodId(href);
            vod.setVodName(name);
            vod.setVodRemarks(remark);
            list.add(vod);

            executor.execute(() -> {
                String pic = getBetterImage(name);
                if (pic.isEmpty()) {
                    Element img = item.selectFirst("div.lazy-load");
                    pic = img != null ? img.attr("data-src") : "";
                }
                vod.setVodPic(pic);
            });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(8, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}

        return list;
    }

    private String getBetterImage(String title) {
        try {
            String url = imageSearchUrl + URLEncoder.encode(title, "UTF-8");
            String json = OkHttp.string(url);
            JSONObject obj = new JSONObject(json);
            JSONArray arr = obj.optJSONArray("list");
            if (arr != null && arr.length() > 0) {
                String pic = arr.getJSONObject(0).optString("pic");
                if (pic.startsWith("http")) return pic;
            }
        } catch (Exception ignored) {}
        return "";
    }

    @Override
    public void init(Context context, String extend) throws Exception {
    }

    // 首页
    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("1", "电影"));
        classes.add(new Class("2", "电视剧"));
        classes.add(new Class("3", "综艺"));
        classes.add(new Class("8", "短剧"));

        String html = OkHttp.string(siteUrl + "/channel?cat_id=1&page=1&page_size=32", getHeader());
        List<Vod> list = parseListWithImage(html);

        return Result.string(classes, list);
    }

    // 分类页
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String url = siteUrl + "/channel?page=" + pg + "&cat_id=" + tid + "&tag_id=&order=new&page_size=32";
        String html = OkHttp.string(url, getHeader());
        List<Vod> list = parseListWithImage(html);
        return Result.string(list);
    }

    // 搜索
    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        try {
            String url = siteUrl + "/channel?keywords=" + URLEncoder.encode(key, "UTF-8");
            String html = OkHttp.string(url, getHeader());
            List<Vod> list = parseListWithImage(html);
            return Result.string(list);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.string(new ArrayList<>());
        }
    }

    // 详情页
    @Override
    public String detailContent(List<String> ids) throws Exception {
        String detailUrl = ids.get(0);
        String html = OkHttp.string(detailUrl, getHeader());
        Document doc = Jsoup.parse(html);

        String name = doc.selectFirst(".name") != null ? doc.selectFirst(".name").text() : "";
        String content = "";
        Element desc = doc.selectFirst(".desc");
        if (desc != null) content = desc.text();

        String linkId = extractRegex(html, "linkId\\s*=\\s*['\"](.*?)['\"]");

        String pic = getBetterImage(name);

        // 获取播放线路
        Map<String, String> postData = new HashMap<>();
        postData.put("link_id", linkId);
        postData.put("is_switch", "1");

        Map<String, String> postHeader = new HashMap<>(getHeader());
        postHeader.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        postHeader.put("X-Requested-With", "XMLHttpRequest");
        postHeader.put("Origin", siteUrl);

        OkResult postRes = OkHttp.post(detailUrl, postData, postHeader);
        String jsonStr = postRes.getBody();

        Map<String, List<String>> playMap = new LinkedHashMap<>();
        try {
            JSONObject json = new JSONObject(jsonStr);
            if ("y".equals(json.optString("status"))) {
                JSONArray playLinks = json.getJSONObject("data").getJSONArray("play_links");
                List<String> urls = new ArrayList<>();
                for (int i = 0; i < playLinks.length(); i++) {
                    String m3u8 = playLinks.getJSONObject(i).optString("m3u8_url");
                    if (m3u8.startsWith("/")) m3u8 = siteUrl + m3u8;
                    urls.add("线路" + (i + 1) + "$" + m3u8);
                }
                playMap.put("默认", urls);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        Vod vod = new Vod();
        vod.setVodId(detailUrl);
        vod.setVodName(name);
        vod.setVodPic(pic);
        vod.setVodContent(content);
        vod.setVodPlayFrom(String.join("$$$", playMap.keySet()));
        vod.setVodPlayUrl(String.join("$$$", playMap.values().stream().map(v -> String.join("#", v)).toList()));

        return Result.string(vod);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return Result.get().url(id).string();
    }

    private String extractRegex(String text, String regex) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex);
        java.util.regex.Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : "";
    }
}
