package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.*;

public class Fkvm extends Spider {

    private String host = "https://www.4kvm.me";

    private HashMap<String, String> getHeaders(String ref) {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
        headers.put("Referer", ref);
        headers.put("Origin", host);
        return headers;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("2|tvclasses=20", "国产剧"));
        classes.add(new Class("1", "电影"));
        classes.add(new Class("2", "电视剧"));
        classes.add(new Class("4", "综艺"));
        return Result.string(classes, new ArrayList<>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String realTid = tid.split("\\|")[0];
        String url = host + "/filter?classify=" + realTid + "&page=" + pg;
        if (tid.contains("|")) url += "&" + tid.split("\\|")[1];
        
        Document doc = Jsoup.parse(OkHttp.string(url, getHeaders(host)));
        List<Vod> list = new ArrayList<>();
        for (Element card : doc.select(".movie-card, .group")) {
            Element a = card.selectFirst("a[href^=/play/]");
            if (a == null) continue;
            String name = card.select("h3").text().trim();
            Element img = card.selectFirst("img");
            String pic = img != null ? (img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src")) : "";
            if (!pic.startsWith("http")) pic = host + pic;
            list.add(new Vod(a.attr("href").replace("/play/", ""), name, pic, card.select("span.absolute").text()));
        }
        return Result.get().page(Integer.parseInt(pg), Integer.parseInt(pg) + 1, list.size(), 1000).vod(list).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String url = host + "/play/" + ids.get(0);
        Document doc = Jsoup.parse(OkHttp.string(url, getHeaders(host)));
        Vod vod = new Vod();
        vod.setVodId(ids.get(0));
        vod.setVodName(doc.selectFirst("h1").text().trim());
        vod.setVodPic(host + doc.selectFirst(".movie-poster img").attr("src"));
        
        List<String> playUrls = new ArrayList<>();
        for (Element a : doc.select("a[href*=/play/" + ids.get(0) + "]")) {
            if (a.attr("href").contains("episode=")) {
                playUrls.add(a.text().trim() + "$" + host + a.attr("href"));
            }
        }
        vod.setVodPlayFrom("4K影视");
        vod.setVodPlayUrl(String.join("#", playUrls));
        return Result.get().vod(vod).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // 完全交给壳子嗅探，格式遵循 FongMi 规范
        return Result.get()
                .parse(1)
                .url(id)
                .header(getHeaders(id))
                .string();
    }
}
