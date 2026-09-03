package com.github.catvod.spider;

import android.content.Context;
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

    private static final String SITE_URL = "https://www.wumee.com";

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

    private static final Pattern SEASON_PATTERN =
            Pattern.compile("(Season|Musim|Saison|Temporada)\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private Map<String, String> baseHeaders;

    @Override
    public void init(Context context, String extend) throws Exception {
        baseHeaders = new HashMap<>();
        baseHeaders.put("User-Agent", Util.CHROME);
        baseHeaders.put("Referer", SITE_URL + "/");
    }

    /**
     * 纯数字 id：8 个 - → /vodshow/1--------1---.html
     * 已带地区段：7 个 - → /vodshow/1-Tiongkok-------1---.html
     */
    private String buildCategoryUrl(String tid, int page) {
        if (tid != null && tid.contains("-")) {
            return String.format(SITE_URL + "/vodshow/%s-------%d---.html", tid, page);
        }
        return String.format(SITE_URL + "/vodshow/%s--------%d---.html", tid, page);
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        for (Map.Entry<String, String> entry : CATEGORIES.entrySet()) {
            classes.add(new Class(entry.getKey(), entry.getValue()));
        }
        return Result.get().classes(classes).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = TextUtils.isEmpty(pg) ? 1 : Integer.parseInt(pg);
        String url = buildCategoryUrl(tid, page);
        String html = OkHttp.string(url, baseHeaders);
        if (TextUtils.isEmpty(html)) {
            return Result.get().vod(new ArrayList<>()).page(page, page, 0, 0).string();
        }

        Document doc = Jsoup.parse(html);
        List<Vod> list = new ArrayList<>();
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
            if (TextUtils.isEmpty(title)) continue;

            Element img = link.selectFirst("img");
            String pic = "";
            if (img != null) {
                pic = img.attr("data-src");
                if (TextUtils.isEmpty(pic)) pic = img.attr("src");
                if (!TextUtils.isEmpty(pic) && !pic.startsWith("http")) {
                    pic = "https:" + pic;
                }
            }

            String searchTitle = removeSeason(title);
            String zhTitle = Proxy.getTitleSync(searchTitle);
            String finalName = TextUtils.isEmpty(zhTitle) ? title : zhTitle;
            String poster = Proxy.getPosterSync(searchTitle);
            if (TextUtils.isEmpty(poster)) poster = pic;

            Vod vod = new Vod();
            vod.setVodId(vodId);
            vod.setVodName(finalName);
            vod.setVodPic(poster);
            list.add(vod);
        }

        int count = list.isEmpty() ? page : page + 1;
        return Result.get()
                .vod(list)
                .page(page, count, list.size(), 0)
                .string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return Result.error("Missing ID");
        String vodId = ids.get(0);
        String url = String.format(SITE_URL + "/voddetail/%s.html", vodId);
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

        Element posterEl = doc.selectFirst("div.this-pic-bj img");
        if (posterEl != null) {
            String poster = posterEl.attr("src");
            if (!TextUtils.isEmpty(poster)) {
                if (!poster.startsWith("http")) poster = "https:" + poster;
                if (TextUtils.isEmpty(vod.getVodPic())) {
                    vod.setVodPic(poster);
                }
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

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String playId = id;
        if (id.contains("/")) {
            playId = id.substring(id.lastIndexOf("/") + 1);
        }
        if (playId.endsWith(".html")) {
            playId = playId.replace(".html", "");
        }

        String pageUrl = SITE_URL + "/vodplay/" + playId + ".html";
        String html = OkHttp.string(pageUrl, baseHeaders);
        if (TextUtils.isEmpty(html)) return Result.error("播放页请求失败");

        String playUrl = extractPlayerAaaaUrl(html);
        if (TextUtils.isEmpty(playUrl)) {
            return Result.error("未找到播放地址");
        }

        playUrl = playUrl.replace("\\/", "/").replace("\\u0026", "&");

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", Util.CHROME);
        headers.put("Referer", SITE_URL + "/");

        Result result = Result.get().url(playUrl).header(headers);
        if (playUrl.contains(".m3u8")) {
            result.m3u8();
        }
        return result.string();
    }

    /**
     * 从 var player_aaaa={...} 用括号匹配取出 JSON，再读 url 字段
     */
    private String extractPlayerAaaaUrl(String html) {
        if (TextUtils.isEmpty(html)) return null;

        int start = html.indexOf("var player_aaaa=");
        if (start < 0) start = html.indexOf("player_aaaa=");
        if (start < 0) return null;

        int brace = html.indexOf('{', start);
        if (brace < 0) return null;

        int depth = 0;
        int end = -1;
        for (int i = brace; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i + 1;
                    break;
                }
            }
        }
        if (end < 0) return null;

        String json = html.substring(brace, end);
        try {
            org.json.JSONObject obj = new org.json.JSONObject(json);
            String u = obj.optString("url", "");
            if (!TextUtils.isEmpty(u)) return u;
        } catch (Exception ignored) {
        }

        Matcher m = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (m.find()) return m.group(1);
        return null;
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        if (TextUtils.isEmpty(key)) {
            return Result.get().vod(new ArrayList<>()).string();
        }
        String searchUrl = SITE_URL + "/index.php/ajax/suggest.html?mid=1&wd="
                + URLEncoder.encode(key, "UTF-8");
        String json = OkHttp.string(searchUrl, baseHeaders);
        if (TextUtils.isEmpty(json)) {
            return Result.get().vod(new ArrayList<>()).string();
        }
        try {
            org.json.JSONObject obj = new org.json.JSONObject(json);
            org.json.JSONArray arr = obj.optJSONArray("list");
            List<Vod> list = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject item = arr.getJSONObject(i);
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
                    list.add(vod);
                }
            }
            return Result.get().vod(list).string();
        } catch (Exception e) {
            return Result.get().vod(new ArrayList<>()).string();
        }
    }

    private String extractIdFromUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        Matcher m = Pattern.compile("/voddetail/(\\d+)\\.html").matcher(url);
        if (m.find()) return m.group(1);
        return url.replaceAll("\\D", "");
    }

    private String removeSeason(String title) {
        if (TextUtils.isEmpty(title)) return "";
        Matcher m = SEASON_PATTERN.matcher(title);
        if (m.find()) {
            return title.substring(0, m.start()).trim();
        }
        return title;
    }
}
