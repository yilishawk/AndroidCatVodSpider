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
 * 多域名互为备份: gaze.red / gaze.host / gaze.run / gaze.show / gazes.site / gazes.top / gazes.host / gazes.store
 * 站点结构(已实测):
 *   - SSR 渲染: 首页/分类页 HTML 直接内嵌 卡片(article.eP -> img[data-src]海报 + img[alt]片名 + a[href=play/<id>] + badge 豆瓣分)
 *   - 分类路由: /filter?mform=<type>&sort=grade   mform=1电影 2电视 bangumi番剧
 *   - 详情路由: /play/<32位hex>  (注意: 该页在 Cloudflare + 自研 CAPTCHA 盾之后, 未过盾时返回验证页而非详情)
 *   - 播放: 视频直链由 WASM 函数 build_play_url(dataid, secret_key, quality, play_key) 计算, 非明文。
 *
 * 边界(诚实标注, 不兜底):
 *   homeContent / categoryContent —— 抓 SSR HTML, 纯 HTML, 稳定可用。
 *   detailContent / playerContent —— 详情页在盾后, 纯 Java OkHttp 拿不到 secret_key/play_key;
 *     播放直链需壳侧 WebView 过盾 + WASM 计算, 这里只把能解析的参数塞进 vodPlayUrl,
 *     并标 parse:1 让壳走自有解码, 不承诺直链可播。
 */
public class Gaze extends Spider {

    // 主域名; 429/超时时可切备域
    private String host = "https://gaze.red";
    private static final String[] BACKUP_HOSTS = {
            "https://gaze.red", "https://gaze.host", "https://gaze.run",
            "https://gaze.show", "https://gazes.site", "https://gazes.top",
            "https://gazes.host", "https://gazes.store"
    };
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // 类型映射: tid -> mform (已实测)
    private static final Map<String, String> TID_TO_MFORM = new HashMap<>();
    static {
        TID_TO_MFORM.put("1", "1");          // 电影
        TID_TO_MFORM.put("2", "2");          // 电视
        TID_TO_MFORM.put("bangumi", "bangumi"); // 番剧
    }

    private final Map<String, String> pageCache = new ConcurrentHashMap<>(); // key=tid|pg -> html, 应对 429/翻页失败时复用

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        // 解析 host (可在 extend 里指定 https://gaze.host 等)
        if (extend != null && extend.trim().startsWith("http")) {
            host = extend.trim();
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

    /**
     * 带多域名容错的 GET。返回 html, 全失败返回 ""。
     * 说明: 站点限流(429), 不在此硬重试, 由调用方决定是否用 pageCache 兜底。
     */
    private String get(String url) {
        // url 传相对或绝对; 这里统一按绝对处理
        String abs = url.startsWith("http") ? url : host + url;
        String[] hosts = url.startsWith("http") ? new String[]{host} : BACKUP_HOSTS;
        for (String h : hosts) {
            String target = url.startsWith("http") ? abs : h + url;
            try {
                String html = OkHttp.string(target, headersFor(h));
                if (!TextUtils.isEmpty(html) && !isCapPage(html)) {
                    return html;
                }
                if (TextUtils.isEmpty(html)) continue;
                // 命中盾页: 记下来, 但继续试下一个域
            } catch (Exception e) {
                // 换下一个域
            }
        }
        return "";
    }

    private Map<String, String> headersFor(String h) {
        Map<String, String> x = new HashMap<>(headers());
        x.put("Referer", h + "/");
        x.put("Origin", h);
        return x;
    }

    /** 判断是否为访问验证(CAPTCHA)壳页, 而非真实内容 */
    private boolean isCapPage(String html) {
        if (TextUtils.isEmpty(html)) return true;
        // 验证页特征: <title>访问验证</title> + cap-widget + /event/cap/
        if (html.contains("cap-widget") && html.contains("/event/cap/")) return true;
        if (html.contains("<title>访问验证</title>")) return true;
        return false;
    }

    /** 从 SSR HTML 解析卡片, 填 List<Vod> (含详情页链接, 不解析集数) */
    private List<Vod> parseCards(String html) {
        List<Vod> list = new ArrayList<>();
        if (TextUtils.isEmpty(html)) return list;
        Document doc = Jsoup.parse(html);
        Elements cards = doc.select("article");
        for (Element card : cards) {
            // 卡片含两个 <img>: 首张是彩色 svg 占位, 末张才是真海报 data-src (实测 162/162)
            Element img = card.select("img[data-src]").last();
            Element link = card.selectFirst("a[href]");
            Element title = card.selectFirst("h3");
            if (img == null || link == null || title == null) continue;

            String pic = img.attr("data-src");
            String href = link.attr("href");           // 形如 play/<32hex>
            String name = title.text().trim();
            if (TextUtils.isEmpty(name) || TextUtils.isEmpty(href)) continue;

            // 详情页 vodId: 保留完整 play/<id> 相对路径
            String vodId = href.startsWith("http") ? href : host + "/" + href;

            // 备注: 豆瓣分
            String remarks = "";
            Element badge = card.selectFirst(".badge");
            if (badge == null) badge = card.selectFirst(".badge-pill");
            if (badge != null) remarks = badge.text().trim();

            Vod vod = new Vod();
            vod.setVodId(vodId);
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodRemarks(remarks);
            list.add(vod);
        }
        return list;
    }

    // ==================== 分类映射 ====================

    /** tid(1/2/bangumi) -> 展示名; 未知 tid 当电影处理并保留原样 */
    private String mformOf(String tid) {
        String m = TID_TO_MFORM.get(tid);
        return (m != null) ? m : "1";
    }

    // ==================== Spider ====================

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("1", "电影"));
        classes.add(new Class("2", "电视剧"));
        classes.add(new Class("bangumi", "番剧"));
        // 首页 SSR: 抓第一页卡片填进第一个 class, 其余 class 留空由分类补
        String homeHtml = get("/");
        List<Vod> homeVods = parseCards(homeHtml);
        Map<String, List<Vod>> vodMap = new HashMap<>();
        vodMap.put("1", homeVods);
        vodMap.put("2", new ArrayList<>());
        vodMap.put("bangumi", new ArrayList<>());

        Result result = Result.get()
                .classes(classes)
                .vod(homeVods)                 // 首页默认展示电影
                .page(1, 1, homeVods.size(), 0)
                .string();
        return result;
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) throws Exception {
        int page = TextUtils.isEmpty(pg) ? 1 : Integer.parseInt(pg);
        String mform = mformOf(tid);

        // /filter?mform=X&sort=grade 已实测; 分页参数未验证, 用 page 试探, 429 时回退上一页缓存
        String url = "/filter?mform=" + URLEncoder.encode(mform, "UTF-8")
                + "&sort=grade"
                + (page > 1 ? "&page=" + page : "");
        String html = get(url);

        // 429/盾页: 回退上一已缓存页, 避免空列表
        if (TextUtils.isEmpty(html)) {
            String cacheKey = tid + "|" + (page - 1);
            String cached = pageCache.get(cacheKey);
            if (!TextUtils.isEmpty(cached)) {
                html = cached;
            }
        }
        if (page > 1 && !TextUtils.isEmpty(html)) {
            pageCache.put(tid + "|" + page, html);
        }

        List<Vod> vods = parseCards(html);

        int next = vods.isEmpty() ? page : page + 1;
        int total = 0; // 站点未暴露总页数, 保持 0 (Result.page 0-value -> MAX_VALUE)
        return Result.get()
                .vod(vods)
                .page(page, next, vods.size(), total)
                .string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return Result.error("id 为空");
        String id = ids.get(0);
        String url = id.startsWith("http") ? id : host + "/" + id;
        String html = get(url);

        // 盾页/空 -> 降级: 返回最小 vod, 不解析出错误数据
        if (TextUtils.isEmpty(html)) {
            Vod vod = new Vod();
            vod.setVodId(id);
            vod.setVodName("未知");
            vod.setVodRemarks("详情页被盾拦截, 未能解析");
            return Result.get().vod(vod).string();
        }

        Document doc = Jsoup.parse(html);

        // 标题: <h1> / 或 <title>
        String name = "";
        Element h1 = doc.selectFirst("h1");
        if (h1 != null) name = h1.text().trim();
        if (TextUtils.isEmpty(name)) {
            String t = doc.title();
            name = t.contains("-") ? t.substring(0, t.indexOf("-")).trim() : t;
        }

        // 海报
        String pic = "";
        Element metaImg = doc.selectFirst("meta[property='og:image']");
        if (metaImg != null) pic = metaImg.attr("content");
        if (TextUtils.isEmpty(pic)) {
            // 卡片双 img: 占位 svg 在前, 真海报 data-src 在末位, 取 last
            Elements allDataSrc = doc.select("img[data-src]");
            if (allDataSrc.size() > 0) pic = allDataSrc.last().attr("data-src");
        }
        if (TextUtils.isEmpty(pic)) {
            Element anyImg = doc.selectFirst("img");
            if (anyImg != null) pic = anyImg.absUrl("src");
        }

        // 简介
        String content = "";
        Element metaDesc = doc.selectFirst("meta[name='description']");
        if (metaDesc != null) content = metaDesc.attr("content");
        if (TextUtils.isEmpty(content)) {
            Element summary = doc.selectFirst(".summary, .intro, .desc, [class*='summary']");
            if (summary != null) content = summary.text().trim();
        }

        // 播放参数: 真详情带 WASM 所需 secret_key/play_key/quality/dataid (实测未过盾, 这里尽力解析)
        String secretKey = extractAttrOrScript(doc, "secret_key", "secretKey");
        String playKey = extractAttrOrScript(doc, "play_key", "playKey");
        String quality = extractAttrOrScript(doc, "quality", "");
        String dataid = extractAttrOrScript(doc, "dataid", "dataId");
        if (TextUtils.isEmpty(dataid)) {
            // 从详情页 URL 兜底: play/<hex> 的 hex 即 dataid
            Matcher dm = Pattern.compile("play/([0-9a-f]{32})").matcher(id);
            if (dm.find()) dataid = dm.group(1);
        }

        // 播放字串:
        //   若拿到 4 参数 -> 塞成 "gaze-wasm#dataid|secret|quality|playkey", 由壳侧 WASM 计算直链
        //   否则 -> 降级 parse:1 直链 (壳若支持该域解码则播, 否则黑屏, 属边界)
        String playFrom = "";
        String playUrl = "";
        boolean hasParams = !TextUtils.isEmpty(secretKey) && !TextUtils.isEmpty(playKey)
                && !TextUtils.isEmpty(dataid) && !TextUtils.isEmpty(quality);
        if (hasParams) {
            playFrom = "Gaze";
            playUrl = "gaze-wasm#" + dataid + "|" + secretKey + "|" + quality + "|" + playKey;
        } else {
            // 边界: 拿不全 WASM 参数, 退化为详情页直链, parse:1 交壳处理
            playFrom = "Gaze";
            playUrl = id;
        }

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

    /** 从 <script> 内联变量或 data-* 属性里提取某字段的值, 尽力而为 */
    private String extractAttrOrScript(Document doc, String attrName, String jsVar) {
        // 1) data 属性
        Element el = doc.selectFirst("[" + attrName + "]");
        if (el != null && !TextUtils.isEmpty(el.attr(attrName))) return el.attr(attrName);
        // 2) 内联 JS 变量: var secret_key="xxx" / window.xx.secret_key="xxx"
        for (Element s : doc.select("script")) {
            String js = s.data();
            Matcher m = Pattern.compile(
                    "(?:var\\s+|let\\s+|const\\s+|window\\.[\\w.]+\\.)?" + Pattern.quote(jsVar)
                            + "\\s*[:=]\\s*[\"']([^\"']+)[\"']",
                    Pattern.CASE_INSENSITIVE).matcher(js);
            if (m.find()) {
                String v = m.group(1).trim();
                if (!TextUtils.isEmpty(v)) return v;
            }
        }
        return "";
    }

    @Override
    public String searchContent(String key, boolean quick) {
        // 站点未探明搜索端点(被盾/限流挡住), 不臆造。诚实返回空。
        return Result.get().vod(new ArrayList<>()).page(1, 1, 0, 0).string();
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        return searchContent(key, quick);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // 透传播放字串:
        //   id 可能是 "gaze-wasm#dataid|secret|quality|playkey" (来自 detailContent),
        //   也可能是 play/<id> 直链。
        // 直链可播性取决于壳侧是否用 WASM build_play_url 计算真实视频地址 —— 纯 Java 保证不了。
        // 这里如实透传 + 头, 不假装能解。
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", host + "/");
        headers.put("Origin", host);
        headers.put("Accept", "*/*");

        String m3u8 = id.startsWith("http") ? id : host + "/" + id;
        return Result.get()
                .parse(1)      // 交壳侧解码 (WASM 计算直链), 不自带可播 m3u8
                .url(m3u8)
                .header(headers)
                .string();
    }
}
