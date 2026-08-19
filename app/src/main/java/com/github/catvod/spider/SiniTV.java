package com.github.catvod.spider;

import android.text.TextUtils;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SiniTV extends Spider {

    private static final String SITE_URL = "https://sinitv.cc";
    private static final String CATEGORY_URL = SITE_URL + "/vodshow/%s-------%d---.html";
    private static final String DETAIL_URL = SITE_URL + "/voddetail/%s.html";

    private static final Map<String, String> CATEGORIES = new LinkedHashMap<>();

    static {
        CATEGORIES.put("1", "电视剧");
        CATEGORIES.put("2", "电影");
        CATEGORIES.put("3", "动漫");
        CATEGORIES.put("4", "综艺");
        CATEGORIES.put("5", "纪录片");
        CATEGORIES.put("1-Tiongkok", "国产剧");
        CATEGORIES.put("2-Tiongkok", "国产电影");
    }

    private static final Pattern SEASON_PATTERN = Pattern.compile("(Season|Musim|Saison|Temporada)\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private Map<String, String> baseHeaders;

    @Override
    public void init(android.content.Context context, String extend) throws Exception {
        baseHeaders = new HashMap<>();
        baseHeaders.put("User-Agent", Util.CHROME);
        baseHeaders.put("Referer", SITE_URL + "/");
    }

    // ====================== Home ======================
    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        for (Map.Entry<String, String> entry : CATEGORIES.entrySet()) {
            classes.add(new Class(entry.getKey(), entry.getValue()));
        }
        // 和 PPnix 完全一样的写法
        return Result.get().classes(classes).string();
    }

    // ====================== Category ======================
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = TextUtils.isEmpty(pg) ? 1 : Integer.parseInt(pg);
        String url = String.format(CATEGORY_URL, tid, page);

        String html = OkHttp.string(url, baseHeaders);
        if (TextUtils.isEmpty(html)) {
            return Result.get().vod(new ArrayList<>()).page(page, page, 0, 0).string();
        }

        Document doc = Jsoup.parse(html);
        List<Vod> vodList = new ArrayList<>();
        Elements items = doc.select("div.public-list-box");

        for (Element item : items) {
            Element link = item.selectFirst("a.public-list-exp");
            if (link == null) continue;

            String href = link.attr("href");
            String vodId = extractIdFromUrl(href);
            String title = link.attr("title");
            if (TextUtils.isEmpty(title)) {
                Element titleEl = item.selectFirst(".public-list-div a");
                if (titleEl != null) title = titleEl.text().trim();
            }

            Element img = link.selectFirst("img");
            String pic = "";
            if (img != null) {
                pic = img.attr("data-src");
                if (TextUtils.isEmpty(pic)) pic = img.attr("src");
                if (!TextUtils.isEmpty(pic) && !pic.startsWith("http")) {
                    pic = "https:" + pic;
                }
            }

            // 尝试获取中文标题和海报
            String searchTitle = removeSeason(title);
            String zhTitle = Proxy.getTitleSync(searchTitle);
            String finalName = TextUtils.isEmpty(zhTitle) ? title : zhTitle;

            String poster = Proxy.getPosterSync(searchTitle);
            if (TextUtils.isEmpty(poster)) poster = pic;

            Vod vod = new Vod();
            vod.setVodId(vodId);
            vod.setVodName(finalName);
            vod.setVodPic(poster);
            vodList.add(vod);
        }

        int count = vodList.size() > 0 ? page + 1 : page;
        return Result.get()
                .vod(vodList)
                .page(page, count, vodList.size(), 0)
                .string();
    }

    // ====================== Detail ======================
    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return Result.error("Missing ID");
        String vodId = ids.get(0);
        String url = String.format(DETAIL_URL, vodId);

        String html = OkHttp.string(url, baseHeaders);
        if (TextUtils.isEmpty(html)) return Result.error("详情请求失败");

        Document doc = Jsoup.parse(html);
        Vod vod = new Vod();
        vod.setVodId(vodId);

        Element titleEl = doc.selectFirst("div.this-desc-title");
        if (titleEl != null) {
            String title = titleEl.text().trim();
            String searchTitle = removeSeason(title);
            String zhTitle = Proxy.getTitleSync(searchTitle);
            vod.setVodName(TextUtils.isEmpty(zhTitle) ? title : zhTitle);

            String poster = Proxy.getPosterSync(searchTitle);
            if (!TextUtils.isEmpty(poster)) {
                vod.setVodPic(poster);
            }
        }

        // 页面海报兜底
        Element posterEl = doc.selectFirst("div.this-pic-bj img");
        if (posterEl != null) {
            String poster = posterEl.attr("src");
            if (!TextUtils.isEmpty(poster)) {
                if (!poster.startsWith("http")) poster = "https:" + poster;
                vod.setVodPic(poster);
            }
        }

        Element infoEl = doc.selectFirst("div.this-desc-info");
        if (infoEl != null) {
            Elements spans = infoEl.select("span");
            if (spans.size() >= 3) {
                vod.setVodYear(spans.get(0).text());
                vod.setVodArea(spans.get(1).text());
                vod.setVodRemarks(spans.get(2).text());
            }
        }

        Element actorEl = doc.selectFirst("div.this-info");
        if (actorEl != null) {
            String actorText = actorEl.text().replace("Pemeran:", "").trim();
            vod.setVodActor(actorText);
        }

        Element descEl = doc.selectFirst("div#height_limit.text");
        if (descEl != null) {
            String desc = descEl.text().replace("Deskripsi:", "").trim();
            vod.setVodContent(desc);
        }

        // 播放列表
        Elements episodes = doc.select("ul.anthology-list-play li a");
        if (!episodes.isEmpty()) {
            List<String> playUrls = new ArrayList<>();
            for (Element ep : episodes) {
                String epHref = ep.attr("href");
                String epTitle = ep.text().trim();
                playUrls.add(epTitle + "$" + epHref);
            }
            vod.setVodPlayFrom("XP");
            vod.setVodPlayUrl(TextUtils.join("#", playUrls));
        }

        return Result.get().vod(vod).string();
    }

    // ====================== Player ======================
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String playId = id;
        if (id.contains("/")) {
            playId = id.substring(id.lastIndexOf("/") + 1);
        }
        if (playId.endsWith(".html")) {
            playId = playId.replace(".html", "");
        }

        String url = SITE_URL + "/vodplay/" + playId + ".html";
        String html = OkHttp.string(url, baseHeaders);
        if (TextUtils.isEmpty(html)) return Result.error("播放页请求失败");

        Document doc = Jsoup.parse(html);
        String playUrl = null;

        Elements scripts = doc.select("script");
        for (Element script : scripts) {
            String js = script.html();
            if (js.contains("player_aaaa") || js.contains("url")) {
                Pattern p = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
                Matcher m = p.matcher(js);
                if (m.find()) {
                    playUrl = m.group(1);
                    break;
                }
                p = Pattern.compile("url\\s*:\\s*['\"]([^'\"]+)['\"]");
                m = p.matcher(js);
                if (m.find()) {
                    playUrl = m.group(1);
                    break;
                }
            }
        }

        if (TextUtils.isEmpty(playUrl)) {
            return Result.error("未找到播放地址");
        }

        Result result = Result.get().url(playUrl);
        if (playUrl.contains(".m3u8")) {
            result.m3u8();
        }
        return result.string();
    }

    // ====================== Search ======================
    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        if (TextUtils.isEmpty(key)) return Result.get().vod(new ArrayList<>()).string();

        String searchUrl = SITE_URL + "/index.php/ajax/suggest.html?mid=1&wd=" + URLEncoder.encode(key, "UTF-8");
        String json = OkHttp.string(searchUrl, baseHeaders);
        if (TextUtils.isEmpty(json)) {
            return Result.get().vod(new ArrayList<>()).string();
        }

        try {
            org.json.JSONObject obj = new org.json.JSONObject(json);
            org.json.JSONArray list = obj.optJSONArray("list");
            List<Vod> vodList = new ArrayList<>();

            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    org.json.JSONObject item = list.getJSONObject(i);
                    String vodId = item.optString("vod_id");
                    String vodName = item.optString("vod_name");
                    String pic = item.optString("pic");

                    String searchTitle = removeSeason(vodName);
                    String zhTitle = Proxy.getTitleSync(searchTitle);
                    String finalName = TextUtils.isEmpty(zhTitle) ? vodName : zhTitle;
                    String poster = Proxy.getPosterSync(searchTitle);
                    if (TextUtils.isEmpty(poster)) poster = pic;

                    Vod vod = new Vod();
                    vod.setVodId(vodId);
                    vod.setVodName(finalName);
                    vod.setVodPic(poster);
                    vodList.add(vod);
                }
            }
            return Result.get().vod(vodList).string();
        } catch (Exception e) {
            return Result.get().vod(new ArrayList<>()).string();
        }
    }

    // ====================== 辅助方法 ======================
    private String extractIdFromUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        Pattern pattern = Pattern.compile("/voddetail/(\\d+)\\.html");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) return matcher.group(1);
        return url.replaceAll("\\D", "");
    }

    private String removeSeason(String title) {
        if (TextUtils.isEmpty(title)) return "";
        Matcher matcher = SEASON_PATTERN.matcher(title);
        if (matcher.find()) {
            return title.substring(0, matcher.start()).trim();
        }
        return title;
    }
}
