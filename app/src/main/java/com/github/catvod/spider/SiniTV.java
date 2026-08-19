package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SiniTV extends Spider {

    private static final String SITE_URL = "https://sinitv.cc";

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.put("Referer", SITE_URL + "/");
        return headers;
    }

    /**
     * 清理季数后缀 (如 Musim ke 1, Musim 2 等)
     */
    private String cleanTitle(String title) {
        if (TextUtils.isEmpty(title)) return "";
        return title.replaceAll("(?i)\\s*Musim\\s*(ke)?\\s*\\d+", "").trim();
    }

    /**
     * 异步代理标题 URL
     */
    private String getProxyTitleUrl(String rawTitle) {
        try {
            String cleaned = cleanTitle(rawTitle);
            return Proxy.getUrl() + "?do=getTitle&title=" + URLEncoder.encode(cleaned, "UTF-8");
        } catch (Exception e) {
            return rawTitle;
        }
    }

    /**
     * 异步代理海报 URL
     */
    private String getProxyPosterUrl(String rawTitle) {
        try {
            String cleaned = cleanTitle(rawTitle);
            return Proxy.getUrl() + "?do=getPoster&title=" + URLEncoder.encode(cleaned, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        // 测试分类
        classes.add(new Class("1-Tiongkok", "中国陆剧"));[cite: 1]

        return Result.string(classes, new ArrayList<>());[cite: 3]
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        if (TextUtils.isEmpty(pg)) pg = "1";
        String url = SITE_URL + "/vodshow/" + tid + "-------" + pg + "---.html";
        
        String html = OkHttp.string(url, getHeaders());
        Document doc = Jsoup.parse(html);
        Elements items = doc.select("div.public-list-box");

        List<Vod> list = new ArrayList<>();
        for (Element item : items) {
            Element a = item.selectFirst("a.public-list-exp");
            if (a == null) continue;

            String href = a.attr("href");
            String vodId = href.replaceAll(".*/voddetail/(.*?)\\.html", "$1");
            String rawTitle = a.attr("title");
            
            Element prb = item.selectFirst("span.public-list-prb");
            String remarks = prb != null ? prb.text().trim() : "";

            // 使用 Vod 实体构造并绑定异步代理接口
            Vod vod = new Vod();[cite: 5]
            vod.setVodId(vodId);[cite: 5]
            vod.setVodName(getProxyTitleUrl(rawTitle));[cite: 5]
            vod.setVodPic(getProxyPosterUrl(rawTitle));[cite: 5]
            vod.setVodRemarks(remarks);[cite: 5]

            list.add(vod);
        }

        int pageNum = Integer.parseInt(pg);
        return Result.string(pageNum, 999, list.size(), 9999, list);[cite: 3]
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String vodId = ids.get(0);
        String url = SITE_URL + "/voddetail/" + vodId + ".html";
        
        String html = OkHttp.string(url, getHeaders());
        Document doc = Jsoup.parse(html);

        String rawTitle = doc.select("div.this-desc-title").text().trim();
        String typeName = doc.select("div.this-desc-tags").text().trim();
        
        String year = "";
        String area = "";
        Elements infoSpans = doc.select("div.this-desc-info span");
        if (infoSpans.size() >= 3) {
            year = infoSpans.get(1).text().trim();
            area = infoSpans.get(2).text().trim();
        }

        StringBuilder actor = new StringBuilder();
        Elements actorList = doc.select("div.this-info a");
        for (Element a : actorList) {
            if (actor.length() > 0) actor.append(",");
            actor.append(a.text().trim());
        }

        String desc = doc.select("div#height_limit").text().replace("Deskripsi:", "").trim();

        Elements tabElements = doc.select("div.anthology-tab div.swiper-wrapper a");
        Elements listBoxes = doc.select("div.anthology-list-box");

        Vod.VodPlayBuilder playBuilder = new Vod.VodPlayBuilder();[cite: 5]

        for (int i = 0; i < tabElements.size(); i++) {
            String fromName = tabElements.get(i).text().replaceAll("<.*?>", "").trim();
            if (TextUtils.isEmpty(fromName)) fromName = "播放源 " + (i + 1);

            List<Vod.VodPlayBuilder.PlayUrl> playUrls = new ArrayList<>();[cite: 5]
            if (i < listBoxes.size()) {
                Elements aLinks = listBoxes.get(i).select("ul.anthology-list-play li a");
                for (Element a : aLinks) {
                    Vod.VodPlayBuilder.PlayUrl playUrl = new Vod.VodPlayBuilder.PlayUrl();[cite: 5]
                    playUrl.name = a.text().trim();[cite: 5]
                    playUrl.url = a.attr("href");[cite: 5]
                    playUrls.add(playUrl);
                }
            }

            if (!playUrls.isEmpty()) {
                playBuilder.append(fromName, playUrls);[cite: 5]
            }
        }

        Vod.VodPlayBuilder.BuildResult playResult = playBuilder.build();[cite: 5]

        Vod vod = new Vod();[cite: 5]
        vod.setVodId(vodId);[cite: 5]
        vod.setVodName(getProxyTitleUrl(rawTitle));[cite: 5]
        vod.setVodPic(getProxyPosterUrl(rawTitle));[cite: 5]
        vod.setTypeName(typeName);[cite: 5]
        vod.setVodYear(year);[cite: 5]
        vod.setVodArea(area);[cite: 5]
        vod.setVodActor(actor.toString());[cite: 5]
        vod.setVodContent(desc);[cite: 5]
        vod.setVodPlayFrom(playResult.vodPlayFrom);[cite: 5]
        vod.setVodPlayUrl(playResult.vodPlayUrl);[cite: 5]

        return Result.string(vod);[cite: 3]
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        String searchUrl = SITE_URL + "/vodsearch/-------------.html?wd=" + URLEncoder.encode(key, "UTF-8");
        String html = OkHttp.string(searchUrl, getHeaders());
        Document doc = Jsoup.parse(html);

        Elements items = doc.select("div.public-list-box");
        List<Vod> list = new ArrayList<>();

        for (Element item : items) {
            Element a = item.selectFirst("a.public-list-exp");
            if (a == null) continue;

            String href = a.attr("href");
            String vodId = href.replaceAll(".*/voddetail/(.*?)\\.html", "$1");
            String rawTitle = a.attr("title");

            Element prb = item.selectFirst("span.public-list-prb");
            String remarks = prb != null ? prb.text().trim() : "";

            Vod vod = new Vod();[cite: 5]
            vod.setVodId(vodId);[cite: 5]
            vod.setVodName(getProxyTitleUrl(rawTitle));[cite: 5]
            vod.setVodPic(getProxyPosterUrl(rawTitle));[cite: 5]
            vod.setVodRemarks(remarks);[cite: 5]

            list.add(vod);
        }

        return Result.string(list);[cite: 3]
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String url = SITE_URL + id;
        String html = OkHttp.string(url, getHeaders());

        Pattern pattern = Pattern.compile("var\\s+player_aaaa\\s*=\\s*(\\{.*?\\});");
        Matcher matcher = pattern.matcher(html);

        if (matcher.find()) {
            String jsonStr = matcher.group(1);
            JSONObject jsonObject = new JSONObject(jsonStr);
            String playUrl = jsonObject.optString("url").replace("\\/", "/");

            return Result.get().url(playUrl).header(getHeaders()).string();[cite: 3]
        }

        return Result.error("未找到播放视频链接");[cite: 3]
    }
}
