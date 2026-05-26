package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QiYou extends Spider {

    private final String siteUrl = "http://www.qiyoudy4.com";
    private final String UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";

    private Map<String, String> getHeader() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        headers.put("Referer", siteUrl + "/");
        return headers;
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
        classes.add(new Class("4", "综艺"));

        String homeHtml = OkHttp.string(siteUrl, getHeader());
        List<Vod> list = parseVodList(homeHtml);

        return Result.string(classes, list);
    }

    // 分类页
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String url = siteUrl + "/list/" + tid + "_" + pg + ".html";
        String html = OkHttp.string(url, getHeader());
        List<Vod> list = parseVodList(html);

        return Result.string(list);
    }

    // 通用列表解析
    private List<Vod> parseVodList(String html) {
        List<Vod> list = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        Elements items = doc.select("ul.stui-vodlist li");
        if (items.isEmpty()) {
            items = doc.select("ul.stui-vodlist__media li");  // 搜索页
        }

        for (Element item : items) {
            Element a = item.selectFirst("a.stui-vodlist__thumb");
            if (a == null) continue;

            String pic = a.attr("data-original");
            String name = a.attr("title");
            String href = a.attr("href");
            String remark = "";

            Element remarkEl = item.selectFirst(".pic-text");
            if (remarkEl != null) remark = remarkEl.text();

            if (!href.startsWith("http")) href = siteUrl + href;
            if (pic != null && pic.startsWith("//")) pic = "https:" + pic;

            Vod vod = new Vod();
            vod.setVodId(href);
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodRemarks(remark);
            list.add(vod);
        }
        return list;
    }

    // ==================== 优化后的详情页解析 ====================
    @Override
    public String detailContent(List<String> ids) throws Exception {
        String detailUrl = ids.get(0);
        String html = OkHttp.string(detailUrl, getHeader());
        Document doc = Jsoup.parse(html);

        String name = doc.selectFirst("h1.line1") != null ? doc.selectFirst("h1.line1").text() : "";
        String pic = "";
        Element thumb = doc.selectFirst(".stui-content__thumb a");
        if (thumb != null) pic = thumb.attr("data-original");

        String type = "", area = "", year = "", actor = "", director = "", content = "";

        // 遍历所有 p 标签进行信息提取
        Elements ps = doc.select(".stui-content__detail p");
        for (Element p : ps) {
            String text = p.text().trim();

            if (text.contains("类型：")) {
                type = p.selectFirst("a") != null ? p.selectFirst("a").text() : text.replace("类型：", "").trim();
            } else if (text.contains("地区：")) {
                area = text.replace("地区：", "").trim();
            } else if (text.contains("年份：")) {
                year = text.replace("年份：", "").trim();
            } else if (text.contains("主演：")) {
                actor = text.replace("主演：", "").trim();
            } else if (text.contains("导演：")) {
                director = text.replace("导演：", "").trim();
            } else if (text.contains("简介：") || text.contains("剧情：")) {
                content = p.selectFirst(".desc") != null ? p.selectFirst(".desc").text() : text;
            }
        }

        // 提取简介（更精确）
        Element descEl = doc.selectFirst(".desc.hidden-xs");
        if (descEl != null) {
            content = descEl.text().replace("简介：", "").replace("详情", "").trim();
        }

        // 解析播放源
        Map<String, List<String>> playMap = new LinkedHashMap<>();
        Elements playlists = doc.select(".tab-pane");

        for (int i = 0; i < playlists.size(); i++) {
            Element pane = playlists.get(i);
            Elements links = pane.select("ul.stui-content__playlist a");

            List<String> urls = new ArrayList<>();
            for (Element link : links) {
                String playUrl = link.attr("href");
                if (!playUrl.startsWith("http")) playUrl = siteUrl + playUrl;
                urls.add(link.text() + "$" + playUrl);
            }
            if (!urls.isEmpty()) {
                playMap.put("播放源" + (i + 1), urls);
            }
        }

        Vod vod = new Vod();
        vod.setVodId(detailUrl);
        vod.setVodName(name);
        vod.setVodPic(pic);
        vod.setTypeName(type);
        vod.setVodArea(area);
        vod.setVodYear(year);
        vod.setVodActor(actor);
        vod.setVodDirector(director);
        vod.setVodContent(content);
        vod.setVodPlayFrom(String.join("$$$", playMap.keySet()));
        vod.setVodPlayUrl(String.join("$$$", playMap.values().stream().map(v -> String.join("#", v)).toList()));

        return Result.string(vod);
    }

    // 搜索（POST）
    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("searchword", key);

            Map<String, String> headers = getHeader();
            headers.put("Content-Type", "application/x-www-form-urlencoded");

            OkResult okResult = OkHttp.post(siteUrl + "/search.php", params, headers);
            String html = okResult.getBody();

            List<Vod> list = parseVodList(html);
            return Result.string(list);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.string(new ArrayList<>());
        }
    }

    // 播放解析（带降级嗅探）
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        try {
            String html = OkHttp.string(id, getHeader());
            Document doc = Jsoup.parse(html);

            Element iframe = doc.selectFirst("iframe");
            String iframeSrc = iframe != null ? iframe.attr("src") : "";

            if (!iframeSrc.isEmpty()) {
                String iframeHtml = OkHttp.string(iframeSrc, getHeader());

                String urlStr = extractRegex(iframeHtml, "const Url = \"(.*?)\";");
                String sign = extractRegex(iframeHtml, "const Sign = \"(.*?)\";");
                String from = extractRegex(iframeHtml, "const From = \"(.*?)\";");

                if (!urlStr.isEmpty() && !sign.isEmpty()) {
                    String apiUrl = "http://meizi.yongfan99.com/player/api.php?url=" + urlStr 
                                  + "&sign=" + sign + "&t=" + from;

                    String json = OkHttp.string(apiUrl, getHeader());
                    String realUrl = extractRegex(json, "\"url\":\"(.*?)\"");

                    if (!realUrl.isEmpty()) {
                        realUrl = realUrl.replace("\\", "");
                        return Result.get().url(realUrl).string();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 解析失败 → 让壳子自己嗅探
        return Result.get()
                .url(id)
                .parse(1)
                .header(getHeader())
                .string();
    }

    private String extractRegex(String text, String regex) {
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }
}
