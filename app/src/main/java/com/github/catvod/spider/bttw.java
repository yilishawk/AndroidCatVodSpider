package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class bttw extends Spider {

    private static final String SITE_URL = "https://www.bttwo.org";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Cookie", "myannoun=1");
        return headers;
    }

    private Document getDocument(String url) throws Exception {
        Connection conn = Jsoup.connect(url).headers(getHeaders()).timeout(10000);
        return conn.get();
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Vod> list = new ArrayList<>();
        List<Class> classes = new ArrayList<>();

        String[] typeIds = {"zgjun", "new-movie", "meiju", "jpsrtv"};
        String[] typeNames = {"国产剧", "电影", "美剧", "日韩剧"};
        for (int i = 0; i < typeNames.length; i++) {
            classes.add(new Class(typeIds[i], typeNames[i]));
        }

        Document doc = getDocument(SITE_URL);
        Elements items = doc.select("div.bt_img.mi_ne_kd.mrb ul li");
        for (Element item : items) {
            Element a = item.select("a").first();
            if (a == null) continue;
            String href = a.attr("href");
            if (!href.startsWith("http")) href = SITE_URL + href;
            String name = item.select("h3.dytit a").text();
            String picUrl = item.select("img.thumb").attr("data-original");
            if (picUrl.isEmpty()) picUrl = item.select("img.thumb").attr("src");
            if (!picUrl.startsWith("http")) picUrl = SITE_URL + picUrl;
            String remark = item.select(".jidi span").text();
            String id = href;
            Vod vod = new Vod();
            vod.setVodId(id);
            vod.setVodName(name);
            vod.setVodPic(picUrl);
            vod.setVodRemarks(remark);
            list.add(vod);
        }
        return Result.string(classes, list);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        List<Vod> list = new ArrayList<>();
        String url;
        if ("1".equals(pg)) {
            url = SITE_URL + "/" + tid;
        } else {
            url = SITE_URL + "/" + tid + "/page/" + pg;
        }
        Document doc = getDocument(url);
        Elements items = doc.select("div.bt_img.mi_ne_kd.mrb ul li");
        for (Element item : items) {
            Element a = item.select("a").first();
            if (a == null) continue;
            String href = a.attr("href");
            if (!href.startsWith("http")) href = SITE_URL + href;
            String name = item.select("h3.dytit a").text();
            String picUrl = item.select("img.thumb").attr("data-original");
            if (picUrl.isEmpty()) picUrl = item.select("img.thumb").attr("src");
            if (!picUrl.startsWith("http")) picUrl = SITE_URL + picUrl;
            String remark = item.select(".jidi span").text();
            String id = href;
            Vod vod = new Vod();
            vod.setVodId(id);
            vod.setVodName(name);
            vod.setVodPic(picUrl);
            vod.setVodRemarks(remark);
            list.add(vod);
        }
        int page = Integer.parseInt(pg);
        int total = (page + 1) * 20;
        return Result.string(page, page + 1, 20, total, list);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String vodId = ids.get(0);
        Document doc = getDocument(vodId);
        String name = doc.select("h1").text();
        String pic = doc.select(".dyimg img").attr("src");
        if (!pic.startsWith("http")) pic = SITE_URL + pic;

        StringBuilder playFrom = new StringBuilder();
        StringBuilder playUrl = new StringBuilder();
        Elements playBtns = doc.select(".paly_list_btn a");
        if (playBtns.isEmpty()) playBtns = doc.select(".downurl a");
        List<String> episodes = new ArrayList<>();
        for (Element a : playBtns) {
            String epName = a.text();
            String epHref = a.attr("href");
            if (!epHref.startsWith("http")) epHref = SITE_URL + epHref;
            episodes.add(epName + "$" + epHref);
        }
        if (!episodes.isEmpty()) {
            playFrom.append("两个BT");
            playUrl.append(String.join("#", episodes));
        }

        Vod vod = new Vod();
        vod.setVodId(vodId);
        vod.setVodName(name);
        vod.setVodPic(pic);
        vod.setVodPlayFrom(playFrom.toString());
        vod.setVodPlayUrl(playUrl.toString());
        return Result.string(vod);
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        List<Vod> list = new ArrayList<>();
        String url = SITE_URL + "/xsssearch?q=" + URLEncoder.encode(key, "UTF-8");
        Document doc = getDocument(url);
        Elements items = doc.select("ul li");
        for (Element item : items) {
            Element a = item.select("h3.dytit a").first();
            if (a == null) a = item.select("a").first();
            if (a == null) continue;
            String href = a.attr("href");
            if (!href.contains("/movie/")) continue;
            String name = a.text();
            String picUrl = "";
            Element img = item.select("img").first();
            if (img != null) {
                picUrl = img.attr("data-original");
                if (picUrl.isEmpty()) picUrl = img.attr("src");
            }
            if (!picUrl.startsWith("http")) picUrl = SITE_URL + picUrl;
            String id = href;
            Vod vod = new Vod();
            vod.setVodId(id);
            vod.setVodName(name);
            vod.setVodPic(picUrl);
            list.add(vod);
        }
        return Result.string(list);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        Document doc = getDocument(id);
        String html = doc.html();
        Pattern pattern = Pattern.compile("'(https?://[^\\s']+\\.m3u8[^\\s']*)'");
        Matcher matcher = pattern.matcher(html);
        String videoUrl = "";
        if (matcher.find()) {
            videoUrl = matcher.group(1);
        }
        return Result.get().url(videoUrl).header(getHeaders()).string();
    }
}
