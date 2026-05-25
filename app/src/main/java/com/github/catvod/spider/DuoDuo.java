package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;
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

/**
 * @author zhixc
 * 多多视频 - 继承 Cloud 实现网盘播放
 */
public class DuoDuo extends Cloud {

    private String siteUrl = "https://tv.yydsys.top/";
    private final Pattern regexCategory = Pattern.compile("index.php/vod/type/id/(\\w+).html");
    private final Pattern regexPageTotal = Pattern.compile("\\$\\(\"\\.mac_total\"\\)\\.text\\('(\\d+)'\\);");

    private Map<String, String> getHeader() {
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", Util.CHROME);
        return header;
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
    }

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        Document doc = Jsoup.parse(OkHttp.string(siteUrl, getHeader()));
        Elements elements = doc.select(".nav-link");
        for (Element e : elements) {
            Matcher mather = regexCategory.matcher(e.attr("href"));
            if (mather.find()) {
                classes.add(new Class(mather.group(1), e.text().trim()));
            }
        }
        return Result.string(classes, parseVodListFromDoc(doc));
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        Document doc = Jsoup.parse(OkHttp.string(
            String.format("%s/index.php/vod/show/id/%s/page/%s.html", siteUrl, tid, pg), 
            getHeader()
        ));
        
        int page = Integer.parseInt(pg), limit = 72, total = 0;
        Matcher matcher = regexPageTotal.matcher(doc.html());
        if (matcher.find()) total = Integer.parseInt(matcher.group(1));
        int count = total <= limit ? 1 : ((int) Math.ceil(total / (double) limit));
        
        return Result.get()
            .vod(parseVodListFromDoc(doc))
            .page(page, count, limit, total)
            .string();
    }

    private List<Vod> parseVodListFromDoc(Document doc) {
        List<Vod> list = new ArrayList<>();
        Elements elements = doc.select(".module-item");
        for (Element e : elements) {
            String vodId = e.selectFirst(".video-name a").attr("href");
            String vodPic = e.selectFirst(".module-item-pic > img").attr("data-src");
            String vodName = e.selectFirst(".video-name").text();
            String vodRemarks = e.selectFirst(".module-item-text").text();
            list.add(new Vod(vodId, vodName, vodPic, vodRemarks));
        }
        return list;
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String vodId = ids.get(0);
        Document doc = Jsoup.parse(OkHttp.string(siteUrl + vodId, getHeader()));

        Vod item = new Vod();
        item.setVodId(vodId);
        item.setVodName(doc.selectFirst(".video-info-header > .page-title").text());
        item.setVodPic(doc.selectFirst(".module-item-pic img").attr("data-src"));
        item.setVodArea(doc.select(".video-info-header a.tag-link").last().text());
        item.setTypeName(String.join(",", doc.select(".video-info-header div.tag-link a").eachText()));

        // 获取网盘分享链接
        List<String> shareLinks = doc.select(".module-row-text").eachAttr("data-clipboard-text");
        for (int i = 0; i < shareLinks.size(); i++) {
            shareLinks.set(i, shareLinks.get(i).trim());
        }

        // 用 Cloud 的方法解析网盘链接，获取播放列表
        if (!shareLinks.isEmpty()) {
            item.setVodPlayUrl(super.detailContentVodPlayUrl(shareLinks));
            item.setVodPlayFrom(super.detailContentVodPlayFrom(shareLinks));
        } else {
            item.setVodPlayUrl("");
            item.setVodPlayFrom("");
        }

        // 提取详细信息
        Elements elements = doc.select(".video-info-item");
        for (Element e : elements) {
            String title = e.previousElementSibling().text();
            if (title.contains("导演")) {
                item.setVodDirector(String.join(",", e.select("a").eachText()));
            } else if (title.contains("主演")) {
                item.setVodActor(String.join(",", e.select("a").eachText()));
            } else if (title.contains("年代")) {
                item.setVodYear(e.selectFirst("a").text().trim());
            } else if (title.contains("备注")) {
                item.setVodRemarks(e.text().trim());
            } else if (title.contains("剧情")) {
                item.setVodContent(e.selectFirst(".sqjj_a").text().replace("[收起部分]", "").trim());
            }
        }

        return Result.string(item);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            return super.playerContent(flag, id, vipFlags);
        } catch (Exception e) {
            return "{}";
        }
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return searchContent(key, pg);
    }

    private String searchContent(String key, String pg) {
        String searchURL = siteUrl + String.format(
            "/index.php/vod/search/page/%s/wd/%s.html", 
            pg, 
            URLEncoder.encode(key)
        );
        String html = OkHttp.string(searchURL, getHeader());
        Elements items = Jsoup.parse(html).select(".module-search-item");
        List<Vod> list = new ArrayList<>();
        for (Element item : items) {
            String vodId = item.select(".video-serial").attr("href");
            String name = item.select(".video-serial").attr("title");
            String pic = item.select(".module-item-pic > img").attr("data-src");
            String remark = item.select(".video-tag-icon").text();
            list.add(new Vod(vodId, name, pic, remark));
        }
        return Result.string(list);
    }
}
