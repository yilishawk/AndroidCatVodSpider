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

public class Bdys extends Spider {

    // 使用你抓包获取的域名
    private String host = "https://v.xlys.ltd.ua";

    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        // 按照你抓包成功的 Headers 填充
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36");
        headers.put("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("sec-ch-ua", "\"Google Chrome\";v=\"147\", \"Not.A/Brand\";v=\"8\", \"Chromium\";v=\"147\"");
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        headers.put("sec-fetch-dest", "document");
        headers.put("sec-fetch-mode", "navigate");
        headers.put("sec-fetch-site", "none");
        headers.put("sec-fetch-user", "?1");
        headers.put("upgrade-insecure-requests", "1");
        return headers;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        // type=1 电影, type=2 电视剧, type=3 动漫
        classes.add(new Class("1", "电影"));
        classes.add(new Class("2", "电视剧"));
        classes.add(new Class("3", "动漫"));
        return Result.string(classes, new ArrayList<Vod>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        // 构造路径: /s/all/页码?type=类型
        String url = host + "/s/all/" + pg + "?type=" + tid;
        
        try {
            // 获取网页源码
            String html = OkHttp.string(url, getHeaders());
            Document doc = Jsoup.parse(html);
            List<Vod> list = new ArrayList<>();
            
            // 哔滴影视典型的列表选择器 (card 或者 list-item)
            Elements items = doc.select(".card, .list-item, .video-list-item");
            if (items.isEmpty()) {
                // 备用选择器：如果是基于 tag 结构的
                items = doc.select("a.relative.group"); 
            }

            for (Element item : items) {
                String vodId = item.attr("href");
                if (vodId.isEmpty()) vodId = item.select("a").attr("href");
                
                String name = item.select(".title, h3, .name").text().trim();
                String pic = item.select("img").attr("data-src");
                if (pic.isEmpty()) pic = item.select("img").attr("src");
                if (pic.startsWith("//")) pic = "https:" + pic;
                else if (pic.startsWith("/")) pic = host + pic;
                
                String remark = item.select(".tag, .remark, .absolute.bottom-1").text().trim();
                
                if (!vodId.isEmpty()) {
                    list.add(new Vod(vodId, name, pic, remark));
                }
            }

            int page = Integer.parseInt(pg);
            // 返回 5 参数格式，确保壳子加载数据
            return Result.string(page, page + 1, list.size(), 1000, list);
        } catch (Exception e) {
            return Result.string(Integer.parseInt(pg), 0, 0, 0, new ArrayList<Vod>());
        }
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String url = ids.get(0);
        if (!url.startsWith("http")) url = host + url;
        
        Document doc = Jsoup.parse(OkHttp.string(url, getHeaders()));
        Vod vod = new Vod();
        vod.setVodId(ids.get(0));
        vod.setVodName(doc.select("h1").text().trim());
        vod.setVodPic(doc.select(".poster img").attr("src"));
        vod.setVodContent(doc.select(".introduction, .desc").text().trim());

        List<String> playUrls = new ArrayList<>();
        // 找到所有的播放按钮或列表
        Elements episodes = doc.select("a[href*='/play/']");
        for (Element a : episodes) {
            String href = a.attr("href");
            if (!href.startsWith("http")) href = host + href;
            playUrls.add(a.text().trim() + "$" + href);
        }
        
        vod.setVodPlayFrom("哔滴影视");
        vod.setVodPlayUrl(String.join("#", playUrls));
        return Result.string(vod);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // 哔滴影视通常需要嗅探，parse 设置为 1
        return Result.get().parse(1).url(id).header(getHeaders()).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        String url = host + "/search?wd=" + URLEncoder.encode(key, "UTF-8");
        Document doc = Jsoup.parse(OkHttp.string(url, getHeaders()));
        List<Vod> list = new ArrayList<>();
        for (Element item : doc.select(".card, .search-item")) {
            Element a = item.selectFirst("a");
            if (a != null) {
                list.add(new Vod(a.attr("href"), a.select(".title").text(), "", ""));
            }
        }
        return Result.string(list);
    }
}
