package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

public class Qkys extends Spider {

    private String host = "https://www.qkw1.com";
    private String jxHost = "https://zyz-omtcqq-com-oss-cn-hangzhou-shanghai-yys-valipl-vip-cp11.xmsu8.top";

    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36");
        headers.put("Referer", host + "/");
        return headers;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("guochan", "国产剧"));
        classes.add(new Class("2", "连续剧"));
        classes.add(new Class("1", "电影"));
        classes.add(new Class("3", "综艺"));
        classes.add(new Class("4", "动漫"));
        return Result.string(classes, new ArrayList<>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String url = host + "/qkwshow/" + tid + "--------" + pg + "---.html";
        Document doc = Jsoup.parse(OkHttp.string(url, getHeaders()));
        List<Vod> list = new ArrayList<>();
        for (Element item : doc.select(".stui-vodlist__item")) {
            Element thumb = item.selectFirst(".stui-vodlist__thumb");
            if (thumb == null) continue;
            String href = thumb.attr("href");
            if (!href.startsWith("http")) href = host + href;
            String pic = thumb.attr("data-original");
            if (pic == null || pic.isEmpty()) pic = thumb.attr("src");
            list.add(new Vod(href, thumb.attr("title"), pic, item.select(".pic-text").text().trim()));
        }
        return Result.get().page(Integer.parseInt(pg), Integer.parseInt(pg) + 1, list.size(), 1000).vod(list).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String url = ids.get(0).startsWith("http") ? ids.get(0) : host + ids.get(0);
        Document doc = Jsoup.parse(OkHttp.string(url, getHeaders()));
        Vod vod = new Vod();
        vod.setVodId(ids.get(0));
        
        // 标题
        Element titleElem = doc.selectFirst(".stui-content__detail .title");
        if (titleElem != null) vod.setVodName(titleElem.text().trim());
        
        // 图片和备注
        Element thumbImg = doc.selectFirst(".stui-content__thumb img");
        if (thumbImg != null) {
            String pic = thumbImg.attr("data-original");
            if (pic == null || pic.isEmpty()) pic = thumbImg.attr("src");
            vod.setVodPic(pic);
        }
        Element picText = doc.selectFirst(".stui-content__thumb .pic-text");
        if (picText != null) vod.setVodRemarks(picText.text().trim());
        
        // 导演
        Element dirElem = doc.selectFirst(".stui-content__detail p.data:contains(导演)");
        if (dirElem != null) {
            String director = dirElem.text().replace("导演：", "").trim();
            vod.setVodDirector(director);
        }
        
        // 主演
        Element actElem = doc.selectFirst(".stui-content__detail p.data:contains(主演)");
        if (actElem != null) {
            String actor = actElem.text().replace("主演：", "").trim();
            vod.setVodActor(actor);
        }
        
        // 类型、地区、年份
        Element typeElem = doc.selectFirst(".stui-content__detail p.data:contains(类型)");
        if (typeElem != null) {
            String type = typeElem.text().replace("类型：", "").trim();
            vod.setTypeName(type);
        }
        Element areaElem = doc.selectFirst(".stui-content__detail p.data:contains(地区)");
        if (areaElem != null) {
            String area = areaElem.text().replace("地区：", "").trim();
            vod.setVodArea(area);
        }
        Element yearElem = doc.selectFirst(".stui-content__detail p.data:contains(年份)");
        if (yearElem != null) {
            String year = yearElem.text().replace("年份：", "").trim();
            vod.setVodYear(year);
        }
        
        // 简介
        Element descElem = doc.selectFirst(".stui-content__desc");
        if (descElem != null) {
            vod.setVodContent(descElem.text().trim());
        }
        
        // 播放列表
        List<String> fromList = new ArrayList<>();
        List<String> urlList = new ArrayList<>();
        for (Element head : doc.select(".stui-pannel__head")) {
            String title = head.select("h3.title").text();
            if (title.contains("源") || title.contains("播放")) {
                fromList.add(title);
                Elements as = head.parent().select("ul.stui-content__playlist a");
                List<String> links = new ArrayList<>();
                for (Element a : as) {
                    String href = a.attr("href");
                    if (!href.startsWith("http")) href = host + href;
                    links.add(a.text() + "$" + href);
                }
                urlList.add(String.join("#", links));
            }
        }
        vod.setVodPlayFrom(String.join("$$$", fromList));
        vod.setVodPlayUrl(String.join("$$$", urlList));
        
        return Result.get().vod(vod).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    public String searchContent(String key, boolean quick, String pg) throws Exception {
        String url = host + "/qkwsearch/-------------.html?wd=" + URLEncoder.encode(key, "UTF-8") + "&submit=";
        Document doc = Jsoup.parse(OkHttp.string(url, getHeaders()));
        List<Vod> list = new ArrayList<>();
        for (Element item : doc.select(".stui-vodlist__item")) {
            Element thumb = item.selectFirst(".stui-vodlist__thumb");
            if (thumb == null) continue;
            String href = thumb.attr("href");
            if (!href.startsWith("http")) href = host + href;
            String pic = thumb.attr("data-original");
            if (pic == null || pic.isEmpty()) pic = thumb.attr("src");
            list.add(new Vod(href, thumb.attr("title"), pic, item.select(".pic-text").text().trim()));
        }
        return Result.string(list);
    }

@Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
    String playUrl = id.startsWith("http") ? id : host + id;
    
    // 第一次请求，携带 Referer
    HashMap<String, String> headers = getHeaders();
    headers.put("Referer", host + "/");
    String html = OkHttp.string(playUrl, headers);
    
    // 检查是否是 JS 跳转页面（空内容 + 包含 location.href）
    if (html != null && (html.contains("window.location.href") || html.contains("location.href"))) {
        // 第二次请求，增加额外的 cookie 和完整 header
        HashMap<String, String> retryHeaders = getHeaders();
        retryHeaders.put("Referer", playUrl);  // Referer 指向自身
        retryHeaders.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        retryHeaders.put("Upgrade-Insecure-Requests", "1");
        html = OkHttp.string(playUrl, retryHeaders);
    }
    
    try {
        Matcher m = Pattern.compile("var player_aaaa=(\\{.*?\\})").matcher(html);
        if (!m.find()) return Result.get().parse(1).url(playUrl).string();
        
        JsonObject pdata = JsonParser.parseString(m.group(1)).getAsJsonObject();
        String idxUrl = jxHost + "/index.php?url=" + pdata.get("url").getAsString() + "&type=" + pdata.get("from").getAsString();
        
        // 中转页也携带完整 header
        HashMap<String, String> idxHeaders = getHeaders();
        idxHeaders.put("Referer", playUrl);
        String idxHtml = OkHttp.string(idxUrl, idxHeaders);
        
        Map<String, String> params = new HashMap<>();
        params.put("url", extractField("url", idxHtml));
        params.put("time", extractField("time", idxHtml));
        params.put("key", "");
        params.put("vkey", extractField("vkey", idxHtml));
        
        HashMap<String, String> apiHeaders = getHeaders();
        apiHeaders.put("X-Requested-With", "XMLHttpRequest");
        apiHeaders.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        apiHeaders.put("Referer", idxUrl);
        apiHeaders.put("Origin", jxHost);

        String apiResp = OkHttp.post(jxHost + "/admin/mizhi_json.php", params, apiHeaders).getBody();
        JsonObject res = JsonParser.parseString(apiResp).getAsJsonObject();
        String finalUrl = res.has("url") ? res.get("url").getAsString() : res.get("video_url").getAsString();
        
        return Result.get().url(finalUrl).header(getHeaders()).string();
    } catch (Exception e) {
        return Result.get().parse(1).url(playUrl).string();
    }
}

    private String extractField(String name, String text) {
        Matcher m = Pattern.compile("\"" + name + "\":\\s*\"(.*?)\"").matcher(text);
        return m.find() ? m.group(1) : "";
    }
}
