package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.util.*;

public class Fkvm extends Spider {

    private String host = "https://www.4kvm.me";

    private HashMap<String, String> getHeaders(String referer) {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
        headers.put("Referer", referer);
        headers.put("Origin", host);
        return headers;
    }

    public String getName() {
        return "4K影視";
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("2|tvclasses=20", "國產劇"));
        classes.add(new Class("1", "電影"));
        classes.add(new Class("2", "電視劇"));
        classes.add(new Class("4", "綜藝"));
        return Result.string(classes, new ArrayList<Vod>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String realTid = tid;
        String extra = "";
        if (tid.contains("|")) {
            String[] parts = tid.split("\\|");
            realTid = parts[0];
            extra = "&" + parts[1];
        }
        String url = host + "/filter?classify=" + realTid + "&page=" + pg + extra;
        try {
            String html = OkHttp.string(url, getHeaders(host));
            Document doc = Jsoup.parse(html);
            List<Vod> list = new ArrayList<>();
            Elements cards = doc.select(".movie-card, .group, .relative.group");
            for (Element card : cards) {
                Element a = card.selectFirst("a[href^=/play/]");
                if (a == null) continue;
                String name = card.select("h3").text().trim();
                Element img = card.selectFirst("img");
                String pic = img != null ? (img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src")) : "";
                if (!pic.startsWith("http")) pic = host + pic;
                String remark = card.select("span.absolute").text().trim();
                list.add(new Vod(a.attr("href").replace("/play/", ""), name, pic, remark));
            }
            int page = Integer.parseInt(pg);
            // 5參數返回：page, pageCount, limit, total, list
            return Result.string(page, page + 1, list.size(), 1000, list);
        } catch (Exception e) {
            return Result.string(Integer.parseInt(pg), 0, 0, 0, new ArrayList<Vod>());
        }
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String url = host + "/play/" + ids.get(0);
        Document doc = Jsoup.parse(OkHttp.string(url, getHeaders(host)));
        Vod vod = new Vod();
        vod.setVodId(ids.get(0));
        vod.setVodName(doc.selectFirst("h1").text().trim());
        vod.setVodPic(doc.selectFirst(".movie-poster img").attr("src"));
        vod.setVodContent(doc.select(".bg-dark-800.rounded-lg.p-3 p").text().trim());
        
        List<String> playUrls = new ArrayList<>();
        Elements episodes = doc.select("a[href*=/play/" + ids.get(0) + "]");
        for (Element a : episodes) {
            if (a.attr("href").contains("episode=")) {
                String link = a.attr("href");
                if (!link.startsWith("http")) link = host + link;
                playUrls.add(a.text().trim() + "$" + link);
            }
        }
        vod.setVodPlayFrom("4K影視");
        vod.setVodPlayUrl(String.join("#", playUrls));
        return Result.string(vod);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // 直接交給殼子嗅探，返回播放頁 URL
        return Result.get().parse(1).url(id).header(getHeaders(id)).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        String url = host + "/search?q=" + URLEncoder.encode(key, "UTF-8");
        Document doc = Jsoup.parse(OkHttp.string(url, getHeaders(host)));
        List<Vod> list = new ArrayList<>();
        for (Element item : doc.select(".group")) {
            Element a = item.selectFirst("a[href^=/play/]");
            if (a == null) continue;
            list.add(new Vod(a.attr("href").replace("/play/", ""), item.select("h3").text().trim(), "", ""));
        }
        return Result.string(list);
    }
}
