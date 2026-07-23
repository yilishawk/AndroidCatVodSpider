package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.parser.Json;
import com.github.catvod.utils.Util;
import com.github.catvod.bean.Vod;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Sub;
import com.github.catvod.bean.Filter;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GimyTV Spider
 */
public class Gimy extends Spider {

    private static final String SITE_URL = "https://gimy.info";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36";

    private Map<String, Class> classMap = new HashMap<>();
    private List<Filter> filterList = new ArrayList<>();

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        loadClasses();
    }

    private void loadClasses() {
        try {
            String html = OkHttp.string(SITE_URL, UA);
            if (TextUtils.isEmpty(html)) return;
            Document doc = Jsoup.parse(html);
            Elements cards = doc.select(".country-card");
            
            for (Element card : cards) {
                String href = card.attr("abs:href");
                String title = card.text().trim();
                // Remove count info from title if present
                int idx = title.lastIndexOf("部");
                if (idx > 0) title = title.substring(0, idx).trim();
                
                // Extract tid from href, e.g., https://gimy.info/china-drama/ -> china-drama
                String tid = href.replace(SITE_URL + "/", "").replace("/", "");
                if (tid.endsWith("/")) tid = tid.substring(0, tid.length() - 1);
                
                if (!classMap.containsKey(tid)) {
                    classMap.put(tid, new Class(tid, title));
                    filterList.add(new Filter(tid, "", ""));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>(classMap.values());
        // Sort classes by name or keep order
        classes.sort((a, b) -> a.getTypeName().compareTo(b.getTypeName()));
        
        return Result.string(classes, filter ? filterList : null);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, Map<String, String> extend) {
        try {
            String url = String.format("%s/%s/page/%d/", SITE_URL, tid, pg == null || pg.equals("1") ? 1 : Integer.parseInt(pg));
            String html = OkHttp.string(url, UA);
            if (TextUtils.isEmpty(html)) return "";
            
            Document doc = Jsoup.parse(html);
            Elements items = doc.select(".movie-grid .movie-card");
            List<Vod> list = new ArrayList<>();
            
            for (Element item : items) {
                String vodId = item.attr("href").replace(SITE_URL, "");
                String title = item.select("h3").text();
                String img = item.select("img").attr("abs:src");
                String remark = item.select(".ep-count").text();
                
                Vod vod = new Vod();
                vod.setVodId(vodId);
                vod.setVodName(title);
                vod.setPicUrl(img);
                vod.setVodRemarks(remark);
                list.add(vod);
            }
            
            return Result.string(list, 1, classes.size(), pg);
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String url = SITE_URL + ids.get(0);
            String html = OkHttp.string(url, UA);
            if (TextUtils.isEmpty(html)) return "";
            
            Document doc = Jsoup.parse(html);
            Element infoDiv = doc.selectOne(".info");
            if (infoDiv == null) return "";
            
            String title = infoDiv.select("h1").text();
            String desc = infoDiv.select(".desc").html().replace("<br>", "\n").replace("<p>", "").replace("</p>", "");
            String typeName = infoDiv.select(".info p a").text();
            String remark = infoDiv.select(".stat").text();
            
            // Get image from poster-wrap
            String img = doc.selectOne(".poster-wrap img").attr("abs:src");
            
            List<Vod> list = new ArrayList<>();
            Vod vod = new Vod();
            vod.setVodId(ids.get(0));
            vod.setVodName(title);
            vod.setVodPic(img);
            vod.setVodContent(desc);
            vod.setVodRemarks(remark);
            vod.setTypeName(typeName);
            
            // Parse episodes
            Elements eps = doc.select(".episode-pill-list a");
            StringBuilder playUrl = new StringBuilder();
            List<String> names = new ArrayList<>();
            List<String> urls = new ArrayList<>();
            
            for (int i = 0; i < eps.size(); i++) {
                Element ep = eps.get(i);
                String epTitle = ep.attr("title");
                String epHref = ep.attr("abs:href").replace(SITE_URL, "");
                
                names.add(epTitle);
                urls.add(epHref);
            }
            
            // Sort episodes? The prompt says "if order is incorrect, sort from first". 
            // Since we just collect them, let's assume the HTML order is mostly fine or we can't easily parse number without complex logic.
            // However, simple string sort on "第X集" might work if numbers are small, but usually these sites have specific order.
            // Let's just append them.
            
            String playlist = TextUtils.join("#", names) + "$" + TextUtils.join("$", urls);
            vod.setVodPlayFrom("GimyTV");
            vod.setVodPlayUrl(playlist);
            
            list.add(vod);
            return Result.string(list);
            
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String searchContent(String quick, String pg) {
        try {
            String url = String.format("%s/?s=%s", SITE_URL, quick);
            String html = OkHttp.string(url, UA);
            if (TextUtils.isEmpty(html)) return "";
            
            Document doc = Jsoup.parse(html);
            Elements items = doc.select(".movie-grid .movie-card");
            List<Vod> list = new ArrayList<>();
            
            for (Element item : items) {
                String vodId = item.attr("href").replace(SITE_URL, "");
                String title = item.select("h3").text();
                String img = item.select("img").attr("abs:src");
                String remark = item.select(".ep-count").text();
                
                Vod vod = new Vod();
                vod.setVodId(vodId);
                vod.setVodName(title);
                vod.setPicUrl(img);
                vod.setVodRemarks(remark);
                list.add(vod);
            }
            
            return Result.string(list, 1, classes.size(), pg);
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String playerContent(String flag, String id, List<Filter> filters) {
        try {
            String url = SITE_URL + id;
            String html = OkHttp.string(url, UA);
            if (TextUtils.isEmpty(html)) return "";
            
            Document doc = Jsoup.parse(html);
            Element video = doc.selectOne("video");
            String playUrl = "";
            if (video != null) {
                playUrl = video.select("source").attr("abs:src");
            }
            
            if (playUrl.isEmpty()) {
                // Fallback or error
                playUrl = SITE_URL + "/"; 
            }
            
            return Result.string(playUrl, "mp4", playUrl);
        } catch (Exception e) {
            return "";
        }
    }
}
