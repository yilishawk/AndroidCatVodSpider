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

    private static final String DEFAULT_SITE = "https://www.qiyou03.com";
    private static final String PUBLISH_URL = "http://qiyoudy.info/";
    private final String UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";

    private String siteUrl = DEFAULT_SITE;

    private Map<String, String> getHeader() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        headers.put("Referer", siteUrl + "/");
        return headers;
    }

    // 从发布页获取最新观影网址
    private String getSiteUrl() {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA);
            String html = OkHttp.string(PUBLISH_URL, headers);
            Document doc = Jsoup.parse(html);

            // 优先匹配包含"最新观影网址"的 li 中的链接
            Elements lis = doc.select("ul li");
            for (Element li : lis) {
                String text = li.text();
                if (text.contains("最新观影网址")) {
                    Element a = li.selectFirst("a[href]");
                    if (a != null) {
                        String href = a.attr("href").trim();
                        if (!href.isEmpty() && !href.equals("#")) {
                            if (!href.startsWith("http")) href = "http://" + href;
                            // 去掉末尾斜杠
                            if (href.endsWith("/")) href = href.substring(0, href.length() - 1);
                            return href;
                        }
                    }
                }
            }

            // 兜底：找所有 http 链接中含 qiyou 的
            for (Element a : doc.select("a[href]")) {
                String href = a.attr("href").trim();
                if (href.startsWith("http") && href.contains("qiyou") && !href.contains("viayoo")) {
                    if (href.endsWith("/")) href = href.substring(0, href.length() - 1);
                    return href;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return DEFAULT_SITE;
    }

    // 防反爬重试方法
    private String fetchWithRetry(String url, int maxRetry) {
        for (int i = 0; i < maxRetry; i++) {
            try {
                String html = OkHttp.string(url, getHeader());
                if (html.contains("Loading......1S") ||
                    html.contains("sx1420w415i.065846.xyz") ||
                    html.length() < 800) {
                    Thread.sleep(800);
                    continue;
                }
                return html;
            } catch (Exception e) {
                try { Thread.sleep(1000); } catch (Exception ignored) {}
            }
        }
        return "";
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        // 初始化时动态获取最新域名
        siteUrl = getSiteUrl();
    }

    // 首页
    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("1", "电影"));
        classes.add(new Class("2", "电视剧"));
        classes.add(new Class("3", "综艺"));

        String homeHtml = fetchWithRetry(siteUrl, 3);
        List<Vod> list = parseVodList(homeHtml);

        return Result.string(classes, list);
    }

    // 分类页
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String url = siteUrl + "/list/" + tid + "_" + pg + ".html";
        String html = fetchWithRetry(url, 3);
        List<Vod> list = parseVodList(html);

        return Result.string(list);
    }

    // 通用列表解析
    private List<Vod> parseVodList(String html) {
        List<Vod> list = new ArrayList<>();
        if (html.isEmpty()) return list;

        Document doc = Jsoup.parse(html);
        Elements items = doc.select("ul.stui-vodlist li, ul.stui-vodlist__media li");

        for (Element item : items) {
            Element a = item.selectFirst("a.stui-vodlist__thumb");
            if (a == null) continue;

            String pic = a.attr("data-original");
            String name = a.attr("title");
            String href = a.attr("href");
            String remark = "";

            Element remarkEl = item.selectFirst(".pic-text, .text-muted");
            if (remarkEl != null) remark = remarkEl.text().trim();

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

    // 详情页
    @Override
    public String detailContent(List<String> ids) throws Exception {
        String detailUrl = ids.get(0);
        String html = fetchWithRetry(detailUrl, 3);
        if (html.isEmpty()) return Result.string(new Vod());

        Document doc = Jsoup.parse(html);
        Vod vod = new Vod();

        vod.setVodId(detailUrl);
        vod.setVodName(doc.selectFirst("h1.line1") != null ? doc.selectFirst("h1.line1").text() : "");

        Element thumb = doc.selectFirst(".stui-content__thumb a");
        if (thumb != null) {
            String pic = thumb.attr("data-original");
            if (pic.startsWith("//")) pic = "https:" + pic;
            vod.setVodPic(pic);
        }

        Elements ps = doc.select(".stui-content__detail p");
        for (Element p : ps) {
            String text = p.text().trim();
            if (text.contains("类型：")) vod.setTypeName(text.replace("类型：", "").trim());
            else if (text.contains("地区：")) vod.setVodArea(text.replace("地区：", "").trim());
            else if (text.contains("年份：")) vod.setVodYear(text.replace("年份：", "").trim());
            else if (text.contains("主演：")) vod.setVodActor(text.replace("主演：", "").trim());
            else if (text.contains("导演：")) vod.setVodDirector(text.replace("导演：", "").trim());
        }

        Element descEl = doc.selectFirst(".desc.hidden-xs");
        if (descEl != null) {
            vod.setVodContent(descEl.text().replace("简介：", "").trim());
        }

        // 播放源解析
        Map<String, List<String>> playMap = new LinkedHashMap<>();
        Elements playlists = doc.select(".tab-pane");
        for (int i = 0; i < playlists.size(); i++) {
            Element pane = playlists.get(i);
            Elements links = pane.select("ul.stui-content__playlist a");
            List<String> urls = new ArrayList<>();
            for (Element link : links) {
                String playUrl = link.attr("href");
                if (!playUrl.startsWith("http")) playUrl = siteUrl + playUrl;
                urls.add(link.text().trim() + "$" + playUrl);
            }
            if (!urls.isEmpty()) {
                playMap.put("播放源" + (i + 1), urls);
            }
        }

        if (!playMap.isEmpty()) {
            vod.setVodPlayFrom(String.join("$$$", playMap.keySet()));
            List<String> playUrls = new ArrayList<>();
            for (List<String> urls : playMap.values()) {
                playUrls.add(String.join("#", urls));
            }
            vod.setVodPlayUrl(String.join("$$$", playUrls));
        }

        return Result.string(vod);
    }

    // 搜索
    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("searchword", key);

            Map<String, String> headers = getHeader();
            headers.put("Content-Type", "application/x-www-form-urlencoded");

            OkResult result = OkHttp.post(siteUrl + "/search.php", params, headers);
            String html = result.getBody();

            List<Vod> list = parseVodList(html);
            return Result.string(list);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.string(new ArrayList<>());
        }
    }

    // 播放解析
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        try {
            String html = fetchWithRetry(id, 3);
            Document doc = Jsoup.parse(html);

            Element iframe = doc.selectFirst("iframe");
            if (iframe != null) {
                String iframeSrc = iframe.attr("src");
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
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 兜底直链
        return Result.get()
                .url(id)
                .parse(1)
                .header(getHeader())
                .string();
    }

    private String extractRegex(String text, String regex) {
        if (text == null) return "";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }
}
