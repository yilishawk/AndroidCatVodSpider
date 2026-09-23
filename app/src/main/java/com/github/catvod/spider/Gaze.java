package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 注视影视 gaze.red
 * 多域名互备: gaze.red / gaze.host / gaze.run / gaze.show / gazes.site / gazes.top / gazes.host / gazes.store
 *
 * 边界:
 *   homeContent / categoryContent —— SSR HTML 列表，可用。
 *   detailContent / playerContent —— 详情在 Cloudflare + CAPTCHA 后，
 *     纯 OkHttp 拿不到 WASM 参数时只能 parse:1 降级，不承诺直链可播。
 */
public class Gaze extends Spider {

    private String host = "https://gaze.red";
    private static final String[] BACKUP_HOSTS = {
            "https://gaze.red", "https://gaze.host", "https://gaze.run",
            "https://gaze.show", "https://gazes.site", "https://gazes.top",
            "https://gazes.host", "https://gazes.store"
    };
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final Map<String, String> TID_TO_MFORM = new HashMap<>();
    static {
        TID_TO_MFORM.put("1", "1");
        TID_TO_MFORM.put("2", "2");
        TID_TO_MFORM.put("bangumi", "bangumi");
    }

    /** key = tid|pg */
    private final Map<String, String> pageCache = new ConcurrentHashMap<>();

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        if (extend != null && extend.trim().startsWith("http")) {
            host = extend.trim().replaceAll("/$", "");
        }
    }

    // ==================== 抓取 ====================

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        h.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        h.put("Referer", host + "/");
        h.put("Origin", host);
        return h;
    }

    private Map<String, String> headersFor(String h) {
        Map<String, String> x = new HashMap<>(headers());
        x.put("Referer", h + "/");
        x.put("Origin", h);
        return x;
    }

    private String get(String url) {
        boolean absolute = url.startsWith("http");
        String[] hosts = absolute ? new String[]{host} : BACKUP_HOSTS;
        for (String h : hosts) {
            String target;
            if (absolute) {
                target = url;
            } else if (url.startsWith("/")) {
                target = h + url;
            } else {
                target = h + "/" + url;
            }
            try {
                String html = OkHttp.string(target, headersFor(h));
                if (!TextUtils.isEmpty(html) && !isCapPage(html)) {
                    return html;
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private boolean isCapPage(String html) {
        if (TextUtils.isEmpty(html)) return true;
        if (html.contains("cap-widget") && html.contains("/event/cap/")) return true;
        if (html.contains("<title>访问验证</title>")) return true;
        return false;
    }

    private String absUrl(String href) {
        if (TextUtils.isEmpty(href)) return "";
        if (href.startsWith("http")) return href;
        if (href.startsWith("//")) return "https:" + href;
        if (href.startsWith("/")) return host + href;
        return host + "/" + href;
    }

    private List<Vod> parseCards(String html) {
        List<Vod> list = new ArrayList<>();
        if (TextUtils.isEmpty(html)) return list;
        Document doc = Jsoup.parse(html);
        Elements cards = doc.select("article");
        for (Element card : cards) {
            // 首张常为彩色 svg 占位，末张才是真海报 data-src
            Element img = card.select("img[data-src]").last();
            Element link = card.selectFirst("a[href]");
            Element title = card.selectFirst("h3");
            if (img == null || link == null || title == null) continue;

            String pic = absUrl(img.attr("data-src"));
            String href = link.attr("href");
            String name = title.text().trim();
            if (TextUtils.isEmpty(name) || TextUtils.isEmpty(href)) continue;
            if (!href.contains("play/")) continue;

            String remarks = "";
            Element badge = card.selectFirst(".badge, .badge-pill");
            if (badge != null) remarks = badge.text().trim();

            Vod vod = new Vod();
            vod.setVodId(absUrl(href));
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodRemarks(remarks);
            list.add(vod);
        }
        return list;
    }

    private String mformOf(String tid) {
        String m = TID_TO_MFORM.get(tid);
        return (m != null) ? m : "1";
    }

    // ==================== Spider ====================

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("1", "电影"));
        classes.add(new Class("2", "电视剧"));
        classes.add(new Class("bangumi", "番剧"));

        String homeHtml = get("/");
        if (!TextUtils.isEmpty(homeHtml)) {
            pageCache.put("1|1", homeHtml);
        }
        List<Vod> homeVods = parseCards(homeHtml);

        return Result.get()
                .classes(classes)
                .vod(homeVods)
                .page(1, 1, homeVods.size(), 0)
                .string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) throws Exception {
        int page = TextUtils.isEmpty(pg) ? 1 : Integer.parseInt(pg);
        String mform = mformOf(tid);
        String cacheKey = tid + "|" + page;

        String url = "/filter?mform=" + URLEncoder.encode(mform, "UTF-8")
                + "&sort=grade"
                + (page > 1 ? "&page=" + page : "");
        String html = get(url);

        if (!TextUtils.isEmpty(html)) {
            pageCache.put(cacheKey, html);
        } else {
            String cached = pageCache.get(cacheKey);
            if (TextUtils.isEmpty(cached) && page > 1) {
                cached = pageCache.get(tid + "|" + (page - 1));
            }
            if (!TextUtils.isEmpty(cached)) html = cached;
        }

        List<Vod> vods = parseCards(html);
        int next = vods.isEmpty() ? page : page + 1;
        return Result.get()
                .vod(vods)
                .page(page, next, vods.size(), 0)
                .string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return Result.error("id 为空");
        String id = ids.get(0);
        String url = id.startsWith("http") ? id : absUrl(id);
        String html = get(url);

        if (TextUtils.isEmpty(html)) {
            Vod vod = new Vod();
            vod.setVodId(id);
            vod.setVodName("未知");
            vod.setVodRemarks("详情页被盾拦截, 未能解析");
            return Result.get().vod(vod).string();
        }

        Document doc = Jsoup.parse(html);

        String name = "";
        Element h1 = doc.selectFirst("h1");
        if (h1 != null) name = h1.text().trim();
        if (TextUtils.isEmpty(name)) {
            String t = doc.title();
            name = t.contains("-") ? t.substring(0, t.indexOf("-")).trim() : t;
        }

        String pic = "";
        Element metaImg = doc.selectFirst("meta[property=og:image]");
        if (metaImg != null) pic = metaImg.attr("content");
        if (TextUtils.isEmpty(pic)) {
            Elements allDataSrc = doc.select("img[data-src]");
            if (!allDataSrc.isEmpty()) pic = allDataSrc.last().attr("data-src");
        }
        if (TextUtils.isEmpty(pic)) {
            Element anyImg = doc.selectFirst("img");
            if (anyImg != null) {
                pic = anyImg.attr("src");
                if (TextUtils.isEmpty(pic)) pic = anyImg.absUrl("src");
            }
        }
        pic = absUrl(pic);

        String content = "";
        Element metaDesc = doc.selectFirst("meta[name=description]");
        if (metaDesc != null) content = metaDesc.attr("content");
        if (TextUtils.isEmpty(content)) {
            Element summary = doc.selectFirst(".summary, .intro, .desc, [class*=summary]");
            if (summary != null) content = summary.text().trim();
        }

        String secretKey = extractAttrOrScript(doc, "secret_key", "secretKey");
        String playKey = extractAttrOrScript(doc, "play_key", "playKey");
        String quality = extractAttrOrScript(doc, "quality", "quality");
        String dataid = extractAttrOrScript(doc, "dataid", "dataId");
        if (TextUtils.isEmpty(dataid)) {
            Matcher dm = Pattern.compile("play/([0-9a-fA-F]{32})").matcher(id);
            if (dm.find()) dataid = dm.group(1);
        }

        boolean hasParams = !TextUtils.isEmpty(secretKey)
                && !TextUtils.isEmpty(playKey)
                && !TextUtils.isEmpty(dataid)
                && !TextUtils.isEmpty(quality);

        String playFrom = "Gaze";
        String playUrl = hasParams
                ? "gaze-wasm#" + dataid + "|" + secretKey + "|" + quality + "|" + playKey
                : id;

        Vod vod = new Vod();
        vod.setVodId(id);
        vod.setVodName(name);
        vod.setVodPic(pic);
        vod.setVodContent(content);
        vod.setVodRemarks(hasParams ? "WASM 直链" : "参数不全(parse:1 降级)");
        vod.setVodPlayFrom(playFrom);
        vod.setVodPlayUrl(playUrl);
        return Result.get().vod(vod).string();
    }

    private String extractAttrOrScript(Document doc, String attrName, String jsVar) {
        Element el = doc.selectFirst("[" + attrName + "]");
        if (el != null && !TextUtils.isEmpty(el.attr(attrName))) return el.attr(attrName);

        if (TextUtils.isEmpty(jsVar)) jsVar = attrName;
        Pattern p = Pattern.compile(
                "(?:var\\s+|let\\s+|const\\s+|window\\.[\\w.]+\\.)?" + Pattern.quote(jsVar)
                        + "\\s*[:=]\\s*[\"']([^\"']+)[\"']",
                Pattern.CASE_INSENSITIVE);
        for (Element s : doc.select("script")) {
            String js = s.data();
            if (TextUtils.isEmpty(js)) continue;
            Matcher m = p.matcher(js);
            if (m.find()) {
                String v = m.group(1).trim();
                if (!TextUtils.isEmpty(v)) return v;
            }
        }
        return "";
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return Result.get().vod(new ArrayList<>()).page(1, 1, 0, 0).string();
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return searchContent(key, quick);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", host + "/");
        headers.put("Origin", host);
        headers.put("Accept", "*/*");

        String play = id;
        if (!TextUtils.isEmpty(id) && id.startsWith("gaze-wasm#")) {
            play = id;
        } else if (!TextUtils.isEmpty(id) && !id.startsWith("http")) {
            play = absUrl(id);
        }

        return Result.get()
                .parse(1)
                .url(play)
                .header(headers)
                .string();
    }
}
