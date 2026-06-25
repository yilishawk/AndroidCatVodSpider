package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.bean.Filter;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Dyrs extends Spider {

    private String host = "https://dyrs1.vip";
    private final HashMap<String, String> headers = new HashMap<>();
    // 只拉取一次备用域名
    private final AtomicBoolean backupFetched = new AtomicBoolean(false);

    public Dyrs() {
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36");
        headers.put("Referer", host);
        headers.put("Cookie", "ip_id=6a190f5dbbc12883f158ae2b45500338; attack_key=41002");
    }

    // ── 域名管理：只在 init 里调一次 ─────────────────────────────────
    @Override
    public void init(Context context, String extend) throws Exception {
        ensureHost();
    }

    private void ensureHost() {
        try {
            String html = OkHttp.string(host + "/", headers);
            if (html.contains("电影人生") || html.contains("dyrs")) return;
        } catch (Exception ignored) {}
        fetchBackupHost();
    }

    private void fetchBackupHost() {
        // 只拉一次，避免重复请求
        if (!backupFetched.compareAndSet(false, true)) return;
        try {
            String json = OkHttp.string("https://ysgcw.cc/api/videox/least", headers);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String newHost = null;
            if (obj.has("least")) newHost = obj.get("least").getAsString();
            if (TextUtils.isEmpty(newHost) && obj.has("urls")) {
                JsonArray arr = obj.getAsJsonArray("urls");
                for (JsonElement e : arr) {
                    String u = e.getAsString();
                    if (u.startsWith("http")) { newHost = u; break; }
                }
            }
            if (!TextUtils.isEmpty(newHost)) {
                host = newHost.replaceAll("/+$", "");
                headers.put("Referer", host);
            }
        } catch (Exception ignored) {}
    }

    private String fixPic(String pic) {
        if (TextUtils.isEmpty(pic)) return "";
        if (pic.startsWith("http")) return pic;
        return host + (pic.startsWith("/") ? pic : "/" + pic);
    }

    // ── 首页 ──────────────────────────────────────────────────────
    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("dianshiju", "电视剧"));
        classes.add(new Class("dianying",  "电影"));
        classes.add(new Class("zongyi",    "综艺"));
        classes.add(new Class("duanju",    "短剧"));

        Result result = new Result().classes(classes);
        if (filter) result.filters(getFilterConfig());
        return result.toString();
    }

    private LinkedHashMap<String, List<Filter>> getFilterConfig() {
        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();

        List<Filter.Value> yearValues = new ArrayList<>();
        yearValues.add(new Filter.Value("全部", ""));
        for (int i = 2026; i >= 2000; i--)
            yearValues.add(new Filter.Value(String.valueOf(i), String.valueOf(i)));

        List<Filter.Value> sortValues = Arrays.asList(
                new Filter.Value("默认", ""),
                new Filter.Value("热度", "play_hot"),
                new Filter.Value("年份", "year")
        );

        filters.put("dianshiju", Arrays.asList(
                new Filter("class", "分类", Arrays.asList(
                        new Filter.Value("全部",""),new Filter.Value("剧情","剧情"),new Filter.Value("爱情","爱情"),
                        new Filter.Value("喜剧","喜剧"),new Filter.Value("悬疑","悬疑"),new Filter.Value("犯罪","犯罪"),
                        new Filter.Value("古装","古装"),new Filter.Value("惊悚","惊悚"),new Filter.Value("奇幻","奇幻"),
                        new Filter.Value("动作","动作"),new Filter.Value("家庭","家庭"),new Filter.Value("都市","都市"),
                        new Filter.Value("科幻","科幻"),new Filter.Value("历史","历史"),new Filter.Value("战争","战争"),
                        new Filter.Value("冒险","冒险"),new Filter.Value("武侠","武侠"),new Filter.Value("青春","青春"),
                        new Filter.Value("恐怖","恐怖"),new Filter.Value("传记","传记"),new Filter.Value("谍战","谍战")
                )),
                new Filter("area", "地区", Arrays.asList(
                        new Filter.Value("全部",""),new Filter.Value("内地","内地"),new Filter.Value("美国","美国"),
                        new Filter.Value("中国香港","中国香港"),new Filter.Value("日本","日本"),new Filter.Value("英国","英国"),
                        new Filter.Value("韩国","韩国"),new Filter.Value("泰国","泰国"),new Filter.Value("中国台湾","中国台湾"),
                        new Filter.Value("加拿大","加拿大"),new Filter.Value("西班牙","西班牙"),new Filter.Value("德国","德国"),
                        new Filter.Value("法国","法国"),new Filter.Value("澳大利亚","澳大利亚"),new Filter.Value("意大利","意大利"),
                        new Filter.Value("墨西哥","墨西哥"),new Filter.Value("印度","印度"),new Filter.Value("新加坡","新加坡"),
                        new Filter.Value("俄罗斯","俄罗斯"),new Filter.Value("土耳其","土耳其"),new Filter.Value("巴西","巴西")
                )),
                new Filter("year", "年份", yearValues),
                new Filter("sort", "排序", sortValues)
        ));

        filters.put("dianying", Arrays.asList(
                new Filter("class", "分类", Arrays.asList(
                        new Filter.Value("全部",""),new Filter.Value("剧情","剧情"),new Filter.Value("喜剧","喜剧"),
                        new Filter.Value("动作","动作"),new Filter.Value("爱情","爱情"),new Filter.Value("惊悚","惊悚"),
                        new Filter.Value("犯罪","犯罪"),new Filter.Value("院线","院线"),new Filter.Value("悬疑","悬疑"),
                        new Filter.Value("恐怖","恐怖"),new Filter.Value("冒险","冒险"),new Filter.Value("奇幻","奇幻"),
                        new Filter.Value("科幻","科幻"),new Filter.Value("家庭","家庭"),new Filter.Value("战争","战争"),
                        new Filter.Value("古装","古装"),new Filter.Value("历史","历史"),new Filter.Value("传记","传记"),
                        new Filter.Value("武侠","武侠"),new Filter.Value("动画","动画"),new Filter.Value("音乐","音乐")
                )),
                new Filter("area", "地区", Arrays.asList(
                        new Filter.Value("全部",""),new Filter.Value("美国","美国"),new Filter.Value("内地","内地"),
                        new Filter.Value("中国香港","中国香港"),new Filter.Value("日本","日本"),new Filter.Value("英国","英国"),
                        new Filter.Value("法国","法国"),new Filter.Value("韩国","韩国"),new Filter.Value("加拿大","加拿大"),
                        new Filter.Value("德国","德国"),new Filter.Value("中国台湾","中国台湾"),new Filter.Value("印度","印度"),
                        new Filter.Value("意大利","意大利"),new Filter.Value("其它地区","其它地区"),new Filter.Value("西班牙","西班牙"),
                        new Filter.Value("澳大利亚","澳大利亚"),new Filter.Value("泰国","泰国"),new Filter.Value("俄罗斯","俄罗斯"),
                        new Filter.Value("比利时","比利时"),new Filter.Value("丹麦","丹麦"),new Filter.Value("墨西哥","墨西哥")
                )),
                new Filter("year", "年份", yearValues),
                new Filter("sort", "排序", sortValues)
        ));

        filters.put("zongyi", Arrays.asList(
                new Filter("class", "分类", Arrays.asList(
                        new Filter.Value("全部",""),new Filter.Value("真人秀","真人秀"),new Filter.Value("大陆综艺","大陆综艺"),
                        new Filter.Value("综艺","综艺"),new Filter.Value("纪录片","纪录片"),new Filter.Value("脱口秀","脱口秀"),
                        new Filter.Value("音乐","音乐"),new Filter.Value("晚会","晚会"),new Filter.Value("喜剧","喜剧"),
                        new Filter.Value("相声","相声"),new Filter.Value("歌舞","歌舞"),new Filter.Value("日韩综艺","日韩综艺"),
                        new Filter.Value("欧美综艺","欧美综艺"),new Filter.Value("游戏","游戏"),new Filter.Value("爱情","爱情"),
                        new Filter.Value("生活","生活"),new Filter.Value("文化","文化"),new Filter.Value("历史","历史")
                )),
                new Filter("area", "地区", Arrays.asList(
                        new Filter.Value("全部",""),new Filter.Value("大陆","大陆"),new Filter.Value("内地","内地"),
                        new Filter.Value("美国","美国"),new Filter.Value("韩国","韩国"),new Filter.Value("中国大陆","中国大陆"),
                        new Filter.Value("香港","香港"),new Filter.Value("英国","英国"),new Filter.Value("台湾","台湾"),
                        new Filter.Value("日本","日本"),new Filter.Value("加拿大","加拿大"),new Filter.Value("泰国","泰国"),
                        new Filter.Value("法国","法国"),new Filter.Value("澳大利亚","澳大利亚"),new Filter.Value("德国","德国"),
                        new Filter.Value("印度","印度")
                )),
                new Filter("year", "年份", yearValues),
                new Filter("sort", "排序", sortValues)
        ));

        filters.put("duanju", Arrays.asList(
                new Filter("class", "分类", Arrays.asList(
                        new Filter.Value("全部",""),new Filter.Value("短剧","短剧"),new Filter.Value("剧情","剧情"),
                        new Filter.Value("爱情","爱情"),new Filter.Value("爽文","爽文"),new Filter.Value("古装","古装"),
                        new Filter.Value("短片","短片"),new Filter.Value("奇幻","奇幻"),new Filter.Value("喜剧","喜剧"),
                        new Filter.Value("悬疑","悬疑"),new Filter.Value("玄幻","玄幻"),new Filter.Value("都市","都市"),
                        new Filter.Value("穿越","穿越"),new Filter.Value("犯罪","犯罪"),new Filter.Value("家庭","家庭"),
                        new Filter.Value("科幻","科幻"),new Filter.Value("武侠","武侠"),new Filter.Value("惊悚","惊悚"),
                        new Filter.Value("冒险","冒险"),new Filter.Value("动作","动作")
                )),
                new Filter("area", "地区", Arrays.asList(
                        new Filter.Value("全部",""),new Filter.Value("中国大陆","中国大陆"),new Filter.Value("大陆","大陆"),
                        new Filter.Value("内地","内地"),new Filter.Value("韩国","韩国"),new Filter.Value("日本","日本"),
                        new Filter.Value("美国","美国"),new Filter.Value("泰国","泰国"),new Filter.Value("台湾","台湾"),
                        new Filter.Value("中国","中国"),new Filter.Value("加拿大","加拿大"),new Filter.Value("俄罗斯","俄罗斯"),
                        new Filter.Value("巴西","巴西"),new Filter.Value("中国香港","中国香港"),new Filter.Value("中国台湾","中国台湾"),
                        new Filter.Value("英国","英国")
                )),
                new Filter("year", "年份", yearValues),
                new Filter("sort", "排序", sortValues)
        ));

        return filters;
    }

    // ── 分类列表 ──────────────────────────────────────────────────
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String area = extend.getOrDefault("area", "");
        String cls  = extend.getOrDefault("class", "");
        String year = extend.getOrDefault("year", "");
        String sort = extend.getOrDefault("sort", "play_hot"); // 默认热度，与 Python 版一致

        StringBuilder sb = new StringBuilder(host).append("/").append(tid).append(".html?");
        if (!area.isEmpty()) sb.append("area=").append(URLEncoder.encode(area, "UTF-8")).append("&");
        if (!cls.isEmpty())  sb.append("class=").append(URLEncoder.encode(cls, "UTF-8")).append("&");
        if (!year.isEmpty()) sb.append("year=").append(URLEncoder.encode(year, "UTF-8")).append("&");
        sb.append("sort_field=").append(URLEncoder.encode(sort, "UTF-8"));
        if (!"1".equals(pg)) sb.append("&page=").append(pg);

        String html = OkHttp.string(sb.toString(), headers);
        Document doc = Jsoup.parse(html);

        List<Vod> list = new ArrayList<>();
        // 修复：class 属性含空格时要用 CSS 选择器，不能直接用 . 连接全部 class
        for (Element item : doc.select("div.group.relative")) {
            Element a = item.selectFirst("a[title]");
            if (a == null) continue;
            Element img = item.selectFirst("img");
            String pic = img != null
                    ? (img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src"))
                    : "";
            Vod vod = new Vod();
            vod.setVodId(a.attr("href"));
            vod.setVodName(a.attr("title"));
            vod.setVodPic(fixPic(pic));
            Element remark = item.selectFirst("div.top-2");
            vod.setVodRemarks(remark != null ? remark.text().trim() : "");
            list.add(vod);
        }
        return Result.string(list);
    }

    // ── 详情页 ────────────────────────────────────────────────────
    @Override
    public String detailContent(List<String> ids) throws Exception {
        String detailUrl = ids.get(0).startsWith("http") ? ids.get(0) : host + ids.get(0);
        String html = OkHttp.string(detailUrl, headers);
        Document doc = Jsoup.parse(html);

        Vod vod = new Vod();
        vod.setVodId(ids.get(0));

        String vodName = "", vodPic = "", vodYear = "", vodArea = "",
               vodContent = "", vodDirector = "", vodActor = "", vodLang = "", vodType = "";

        // JSON-LD 解析（与 Python 版对齐）
        Element ldJson = doc.selectFirst("script[type=application/ld+json]");
        if (ldJson != null) {
            try {
                JsonObject jd = JsonParser.parseString(ldJson.html()).getAsJsonObject();
                vodName    = jd.has("name") ? jd.get("name").getAsString() : "";
                vodPic     = jd.has("image") ? fixPic(jd.get("image").getAsString()) : "";
                vodContent = jd.has("description") ? jd.get("description").getAsString()
                        .replaceAll("<[^>]+>", "").trim() : "";
                if (jd.has("releaseDate")) {
                    Matcher m = Pattern.compile("(19|20)\\d{2}").matcher(jd.get("releaseDate").getAsString());
                    if (m.find()) vodYear = m.group();
                }
                if (TextUtils.isEmpty(vodYear) && jd.has("year"))
                    vodYear = jd.get("year").getAsString();
                vodArea = jd.has("countryOfOrigin") ? jd.get("countryOfOrigin").getAsString() : "";
                vodLang = jd.has("inLanguage") ? jd.get("inLanguage").getAsString() : "";
                // 导演
                if (jd.has("director")) {
                    JsonElement d = jd.get("director");
                    if (d.isJsonObject()) vodDirector = d.getAsJsonObject().get("name").getAsString();
                    else if (d.isJsonArray() && d.getAsJsonArray().size() > 0)
                        vodDirector = d.getAsJsonArray().get(0).getAsJsonObject().get("name").getAsString();
                    else vodDirector = d.getAsString();
                }
                // 主演
                if (jd.has("actor")) {
                    JsonArray actors = jd.getAsJsonArray("actor");
                    List<String> actorList = new ArrayList<>();
                    for (JsonElement e : actors) {
                        if (e.isJsonObject()) actorList.add(e.getAsJsonObject().get("name").getAsString());
                        else actorList.add(e.getAsString());
                    }
                    vodActor = TextUtils.join(", ", actorList);
                }
                // 类型
                if (jd.has("genre")) {
                    JsonElement g = jd.get("genre");
                    if (g.isJsonArray()) {
                        List<String> genres = new ArrayList<>();
                        for (JsonElement e : g.getAsJsonArray()) genres.add(e.getAsString());
                        vodType = TextUtils.join(", ", genres);
                    } else vodType = g.getAsString();
                }
            } catch (Exception ignored) {}
        }

        // HTML 降级（与 Python 版对齐）
        if (TextUtils.isEmpty(vodName)) {
            Element h3 = doc.selectFirst("h3[title]");
            vodName = h3 != null ? h3.attr("title") : "未知";
        }
        if (TextUtils.isEmpty(vodPic)) {
            Element img = doc.selectFirst("img.lazy-image");
            if (img != null) vodPic = fixPic(img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src"));
        }
        if (TextUtils.isEmpty(vodContent)) {
            Element cont = doc.selectFirst("div.text-justify");
            if (cont != null) vodContent = cont.text().trim();
        }
        // 从页面信息块补充导演/主演/年份/地区/语言/类型
        if (TextUtils.isEmpty(vodDirector) && TextUtils.isEmpty(vodActor)) {
            Element infoDiv = doc.selectFirst("div.flex.flex-col.gap-y-2");
            if (infoDiv != null) {
                for (Element line : infoDiv.select("div.flex")) {
                    String text = line.text().replace("\n", "").trim();
                    if (text.contains("导演") && TextUtils.isEmpty(vodDirector))
                        vodDirector = text.replace("导演：", "").replace("导演:", "").trim();
                    else if (text.contains("主演") && TextUtils.isEmpty(vodActor))
                        vodActor = text.replace("主演：", "").replace("主演:", "").trim();
                    else if (text.contains("上映") && TextUtils.isEmpty(vodYear))
                        vodYear = text.replace("上映：", "").replace("上映:", "").trim();
                    else if (text.contains("地区") && TextUtils.isEmpty(vodArea))
                        vodArea = text.replace("地区：", "").replace("地区:", "").trim();
                    else if (text.contains("语言") && TextUtils.isEmpty(vodLang))
                        vodLang = text.replace("语言：", "").replace("语言:", "").trim();
                    else if (text.contains("类型") && TextUtils.isEmpty(vodType))
                        vodType = text.replace("类型：", "").replace("类型:", "").trim();
                }
            }
        }

        vod.setVodName(TextUtils.isEmpty(vodName) ? "未知" : vodName);
        vod.setVodPic(vodPic);
        vod.setVodYear(vodYear);
        vod.setVodArea(vodArea);
        vod.setVodContent(vodContent);
        vod.setVodDirector(vodDirector);
        vod.setVodActor(vodActor);

        // ── 播放线路：多线程并发，用数组保序，对齐 Python 版 ex.map 的有序性 ──
        Elements tabs = doc.select("#originTabs a");
        int size = tabs.size();
        if (size == 0) return Result.get().vod(vod).string();

        // 用固定大小数组保证线路顺序与 tab 顺序一致
        final String[] fromArr = new String[size];
        final String[] urlArr  = new String[size];
        CountDownLatch latch = new CountDownLatch(size);
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(size, 8));

        for (int i = 0; i < size; i++) {
            final int idx = i;
            final Element tab = tabs.get(i);
            executor.execute(() -> {
                try {
                    Map<String, String> line = parseLine(tab, detailUrl);
                    if (line != null && !TextUtils.isEmpty(line.get("url"))) {
                        fromArr[idx] = line.get("from");
                        urlArr[idx]  = line.get("url");
                    }
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        executor.shutdown();

        // 过滤掉空位，按顺序拼接
        List<String> fromList = new ArrayList<>();
        List<String> urlList  = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (!TextUtils.isEmpty(fromArr[i]) && !TextUtils.isEmpty(urlArr[i])) {
                fromList.add(fromArr[i]);
                urlList.add(urlArr[i]);
            }
        }

        vod.setVodPlayFrom(TextUtils.join("$$$", fromList));
        vod.setVodPlayUrl(TextUtils.join("$$$", urlList));

        return Result.string(vod);
    }

    private Map<String, String> parseLine(Element tab, String detailUrl) {
    try {
        // 1. 获取线路名称
        Element btn = tab.selectFirst("button");
        String fromName = "";
        if (btn != null && !btn.attr("data-origin").isEmpty()) {
            fromName = btn.attr("data-origin");
        } else {
            String rawText = (btn != null) ? btn.text() : tab.text();
            fromName = rawText.replaceAll("\\d+$", "").trim();
        }

        // 2. 处理 URL
        String lineUrl = tab.attr("href");
        if (TextUtils.isEmpty(lineUrl)) return null;
        if (!lineUrl.startsWith("http")) {
            lineUrl = host + (lineUrl.startsWith("/") ? "" : "/") + lineUrl;
        }

        // 3. 发起请求
        HashMap<String, String> lineHeaders = new HashMap<>(headers);
        lineHeaders.put("Referer", lineUrl);
        String respHtml = OkHttp.string(lineUrl, lineHeaders);
        if (TextUtils.isEmpty(respHtml)) return null;

        // 4. 直接在原始 HTML 上提取 data-title 和 currentUrl
        // （避免 Jsoup 重排 DOM 导致 <script> 脱离 <a> 而定位失败）
        List<String> titles = new ArrayList<>();
        Matcher titleMatcher = Pattern.compile("data-title=\"([^\"]+)\"").matcher(respHtml);
        while (titleMatcher.find()) {
            titles.add(titleMatcher.group(1));
        }

        List<String> epUrls = new ArrayList<>();
        Matcher urlMatcher = Pattern.compile("let currentUrl\\s*=\\s*\"([^\"]+)\"").matcher(respHtml);
        while (urlMatcher.find()) {
            String epUrl = urlMatcher.group(1)
                                     .replace("\\/", "/")
                                     .replace("\u0026", "&");
            if (!epUrl.startsWith("http")) epUrl = host + epUrl;
            epUrls.add(epUrl);
        }

        if (titles.isEmpty() || epUrls.isEmpty()) return null;

        // 5. 按顺序拼集数
        List<String> urls = new ArrayList<>();
        int count = Math.min(titles.size(), epUrls.size());
        for (int i = 0; i < count; i++) {
            urls.add(titles.get(i) + "$" + epUrls.get(i));
        }
        if (urls.isEmpty()) return null;

        Map<String, String> result = new HashMap<>();
        result.put("from", fromName);
        result.put("url", TextUtils.join("#", urls));
        return result;
    } catch (Exception e) {
        return null;
    }
}

    /** 处理 \\uXXXX 形式的 unicode 转义，对齐 Python 的 unicode_escape 解码 */
    private String unescapeUnicode(String s) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            if (i + 5 < s.length() && s.charAt(i) == '\\' && s.charAt(i + 1) == 'u') {
                try {
                    int code = Integer.parseInt(s.substring(i + 2, i + 6), 16);
                    sb.append((char) code);
                    i += 6;
                    continue;
                } catch (NumberFormatException ignored) {}
            }
            sb.append(s.charAt(i));
            i++;
        }
        return sb.toString();
    }

    // ── 播放器 ───────────────────────────────────────────────────
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return Result.get().url(id).parse(0).header(headers).string();
    }

    // ── 搜索 ──────────────────────────────────────────────────────
    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return search(key);
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return search(key);
    }

    private String search(String key) {
        try {
            String url = host + "/s.html?name=" + URLEncoder.encode(key, "UTF-8");
            String html = OkHttp.string(url, headers);
            Document doc = Jsoup.parse(html);

            List<Vod> list = new ArrayList<>();
            // 修复：原来只取第一条，现在取全部
            Element grid = doc.selectFirst("div#image-grid");
            if (grid == null) return Result.string(list);

            for (Element item : grid.select("div.group.relative")) {
                Element a = item.selectFirst("a[title]");
                if (a == null) continue;
                Element img = item.selectFirst("img");
                String pic = img != null
                        ? (img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src"))
                        : "";
                Vod vod = new Vod();
                vod.setVodId(a.attr("href"));
                vod.setVodName(a.attr("title"));
                vod.setVodPic(fixPic(pic));
                Element remark = item.selectFirst("div.top-2");
                vod.setVodRemarks(remark != null ? remark.text().trim() : "");
                list.add(vod);
            }
            return Result.string(list);
        } catch (Exception e) {
            return Result.string(new ArrayList<>());
        }
    }
}
