package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AiGua extends Spider {

    private static final String HOST     = "https://aigua8.com";
    private static final String API_CATE = HOST + "/video/refresh-cate";
    private static final String API_PLAY = HOST + "/video/play-url";
    private static final String API_SEARCH = HOST + "/video/refresh-video";

    private static final String[][] CHANNELS = {
            {"2",  "电视剧"},
            {"1",  "电影"},
            {"3",  "综艺"},
            {"4",  "动漫"},
            {"32", "纪录片"},
    };

    // 真实线路：21=超快线路，1=普快线路，19=如意专线，16=专线（play-url 接口一次返回全部线路地址，
    // 不需要按线路分别请求；这里的顺序就是 vod_play_from 里展示给用户的线路顺序）
    private static final String[] SOURCE_NAMES = {"超快线路", "普快线路", "如意专线", "专线"};
    private static final String[] SOURCE_IDS   = {"21",      "1",       "19",      "16"};

    // filters 必须是 LinkedHashMap 才能传给 Result.filters()，
    // 但 fetchFilters() 会被多个线程池线程并发调用，LinkedHashMap 本身不是线程安全的，
    // 用 Collections.synchronizedMap 包一层，避免并发 put 导致内部结构损坏。
    private final LinkedHashMap<String, List<Filter>> filterCache =
            new LinkedHashMap<>();
    private final Map<String, List<Filter>> safeFilterCache =
            Collections.synchronizedMap(filterCache);

    // ------------------------------------------------------------------ 工具

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("Referer",    HOST + "/");
        h.put("User-Agent", "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 Chrome/114 Safari/537.36");
        return h;
    }

    private String cateUrl(String channelId, int page, Map<String, String> ext) {
        if (ext == null) ext = new HashMap<>();
        String tag    = ext.getOrDefault("tag",    "");
        String area   = ext.getOrDefault("area",   "");
        String year   = ext.getOrDefault("year",   "");
        String sort   = ext.getOrDefault("sort",   "new");
        String status = ext.getOrDefault("status", "");
        return API_CATE
                + "?page_num="   + page
                + "&sorttype=desc"
                + "&channel_id=" + channelId
                + "&tag="        + ("0".equals(tag)    ? "" : tag)
                + "&area="       + ("0".equals(area)   ? "" : area)
                + "&year="       + ("0".equals(year)   ? "" : year)
                + "&status="     + ("0".equals(status) ? "" : status)
                + "&page_size=24"
                + "&sort="       + sort
                + "&_="          + System.currentTimeMillis();
    }

    private List<Vod> parseVodList(JSONArray arr) throws Exception {
        List<Vod> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Vod vod = new Vod();
            vod.setVodId(String.valueOf(o.optInt("video_id")));
            vod.setVodName(o.optString("video_name"));
            vod.setVodPic(o.optString("cover"));
            vod.setVodRemarks(o.optString("flag"));
            list.add(vod);
        }
        return list;
    }

    private void fetchFilters(String channelId) {
        if (safeFilterCache.containsKey(channelId)) return;
        List<Filter> result = new ArrayList<>();
        try {
            String resp = OkHttp.string(cateUrl(channelId, 1, new HashMap<>()), headers());
            JSONArray searchBox = new JSONObject(resp)
                    .getJSONObject("data")
                    .getJSONArray("search_box");
            for (int i = 0; i < searchBox.length(); i++) {
                JSONObject box = searchBox.getJSONObject(i);
                String field   = box.getString("field");
                if ("channel_id".equals(field) || "source".equals(field)) continue;
                JSONArray vals = box.getJSONArray("list");
                List<Filter.Value> values = new ArrayList<>();
                for (int j = 0; j < vals.length(); j++) {
                    JSONObject v = vals.getJSONObject(j);
                    // TODO: 需要对照真实 Filter.java 确认 Filter/Filter.Value 构造函数参数顺序，
                    // 顺序传反了不会报编译错误，只会导致筛选项名称和值对不上。
                    values.add(new Filter.Value(v.getString("display"), v.get("value").toString()));
                }
                result.add(new Filter(field, box.getString("label"), values));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        safeFilterCache.put(channelId, result);
    }

    /**
     * 解析详情页 HTML 集数，只取 data-source-id="1" 那组（两线路章节号相同）。
     * 播放 id 格式：videoId|chapterId
     */
    private void buildPlayUrls(Vod vod, String videoId, Document doc) {
        Elements items = doc.select("li[data-source-id=1][data-chapter-id]");
        if (items.isEmpty()) return;

        String[] titles = new String[items.size()];
        String[] ids    = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            Element li = items.get(i);
            String title = li.select(".select-link").text().trim();
            if (title.isEmpty()) title = String.valueOf(i + 1);
            titles[i] = title;
            ids[i]    = videoId + "|" + li.attr("data-chapter-id");
        }

        StringBuilder fromSb = new StringBuilder();
        StringBuilder urlSb  = new StringBuilder();
        for (int k = 0; k < SOURCE_NAMES.length; k++) {
            if (k > 0) { fromSb.append("$$$"); urlSb.append("$$$"); }
            fromSb.append(SOURCE_NAMES[k]);
            StringBuilder line = new StringBuilder();
            for (int j = 0; j < titles.length; j++) {
                if (j > 0) line.append("#");
                line.append(titles[j]).append("$").append(ids[j]);
            }
            urlSb.append(line);
        }
        vod.setVodPlayFrom(fromSb.toString());
        vod.setVodPlayUrl(urlSb.toString());
    }

    // ------------------------------------------------------------------ Spider

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        for (String[] ch : CHANNELS) classes.add(new Class(ch[0], ch[1]));

        if (filter) {
            ExecutorService exec  = Executors.newFixedThreadPool(CHANNELS.length);
            CountDownLatch  latch = new CountDownLatch(CHANNELS.length);
            for (String[] ch : CHANNELS) {
                String tid = ch[0];
                exec.submit(() -> { try { fetchFilters(tid); } finally { latch.countDown(); } });
            }
            // 加超时兜底，避免某个线程网络请求异常挂起导致 homeContent 永远不返回
            latch.await(15, TimeUnit.SECONDS);
            exec.shutdown();
        }

        String    resp = OkHttp.string(cateUrl("2", 1, new HashMap<>()), headers());
        JSONArray list = new JSONObject(resp).getJSONObject("data").getJSONArray("list");

        return Result.get()
                .classes(classes)
                .filters(filterCache)
                .vod(parseVodList(list))
                .string();
    }

    @Override
    public String homeVideoContent() throws Exception {
        String    resp = OkHttp.string(cateUrl("2", 1, new HashMap<>()), headers());
        JSONArray list = new JSONObject(resp).getJSONObject("data").getJSONArray("list");
        return Result.get().vod(parseVodList(list)).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) throws Exception {
        int    page = pg == null || pg.isEmpty() ? 1 : Integer.parseInt(pg);
        String resp = OkHttp.string(cateUrl(tid, page, extend), headers());
        JSONObject data      = new JSONObject(resp).getJSONObject("data");
        int        totalPage = data.optInt("total_page",  1);
        int        total     = data.optInt("total_count", 0);
        JSONArray  list      = data.getJSONArray("list");

        return Result.get()
                .page(page, totalPage, 24, total)
                .vod(parseVodList(list))
                .string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String   videoId = ids.get(0);
        String   html    = OkHttp.string(HOST + "/video/detail?video_id=" + videoId, headers());
        Document doc     = Jsoup.parse(html);

        Vod vod = new Vod();
        vod.setVodId(videoId);

        // 标题：<h1 class="player-title"><em class="title-txt">南来北往</em>
        String title = doc.select("h1.player-title em.title-txt").text().trim();
        if (title.isEmpty()) title = doc.select("meta[property=og:title]").attr("content");
        vod.setVodName(title);

        // 封面：跟搜索列表一样是懒加载，真实地址在 originalSrc，src 只是占位图
        String pic = doc.select(".GNbox-xq-img img").attr("originalSrc");
        if (pic.isEmpty()) pic = doc.select("meta[property=og:image]").attr("content");
        vod.setVodPic(pic);

        // 类型/年份/地区挤在同一串 span 里，没有各自的 class，只能按内容规律拆：
        // 数字（4 位）判定为年份，年份前的算类型（可能多个），年份后的算地区
        List<String> genres = new ArrayList<>();
        List<String> areas  = new ArrayList<>();
        String year = "";
        boolean yearSeen = false;
        for (Element sp : doc.select(".GNbox-type span")) {
            String t = sp.text().trim();
            if (t.isEmpty()) continue;
            if (t.matches("\\d{4}")) {
                year = t;
                yearSeen = true;
            } else if (!yearSeen) {
                genres.add(t);
            } else {
                areas.add(t);
            }
        }
        vod.setTypeName(String.join("/", genres));
        vod.setVodYear(year);
        vod.setVodArea(String.join(" ", areas));

        // 导演/主演/简介：都在 .GNbox-xq-text 下的 <div>，标签是直接文本节点，
        // 值在紧跟着的 <span> 里，用 "："（全角冒号）切分
        boolean contentSet = false;
        for (Element row : doc.select(".GNbox-xq-text div")) {
            String text = row.text().trim();
            if (text.startsWith("导演")) vod.setVodDirector(stripLabel(text, "导演"));
            else if (text.startsWith("主演")) vod.setVodActor(stripLabel(text, "主演"));
            else if (text.startsWith("简介")) {
                vod.setVodContent(stripLabel(text, "简介"));
                contentSet = true;
            }
        }
        if (!contentSet) {
            String desc = doc.select("meta[property=og:description]").attr("content");
            if (!desc.isEmpty()) vod.setVodContent(desc);
        }

        buildPlayUrls(vod, videoId, doc);
        return Result.get().vod(vod).string();
    }

    /** 去掉形如 "导演：" 这种前缀标签（全角冒号），返回后面的值并 trim */
    private String stripLabel(String text, String label) {
        int idx = text.indexOf('：');
        if (idx < 0) idx = text.indexOf(':');
        if (idx < 0) return text.replace(label, "").trim();
        return text.substring(idx + 1).trim();
    }

    /**
     * id 格式：videoId|chapterId
     * flag → sourceId 对照 SOURCE_NAMES/SOURCE_IDS（超快=21，普快=1，如意专线=19，专线=16）
     * play-url 接口不管传哪个 sourceId，都会把当前 chapter 下**全部线路**的地址一次性返回在
     * data.urlinfo.resource_url 这个对象里（key 是线路 id，不是单个字符串），
     * 所以这里按 flag 对应的 sourceId 去这个对象里取值即可，不需要为每条线路单独发请求。
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String[] parts     = id.split("\\|", 2);
        String   videoId   = parts[0];
        String   chapterId = parts[1];

        // flag → sourceId
        String sourceId = SOURCE_IDS[0];
        for (int k = 0; k < SOURCE_NAMES.length; k++) {
            if (SOURCE_NAMES[k].equals(flag)) { sourceId = SOURCE_IDS[k]; break; }
        }

        String apiUrl = API_PLAY
                + "?citycode=AMS"
                + "&page=detail"
                + "&chapterId=" + chapterId
                + "&videoId="   + videoId
                + "&sourceId="  + sourceId;

        String resp = OkHttp.string(apiUrl, headers());
        JSONObject resourceUrls = new JSONObject(resp)
                .getJSONObject("data")
                .getJSONObject("urlinfo")
                .getJSONObject("resource_url");

        // 优先取当前 flag 对应的线路；如果这个 chapter 恰好没有这条线路的地址（不同集数
        // 可用线路可能不完全一致），按 SOURCE_IDS 的顺序找第一个有地址的线路兜底，
        // 避免直接失败——好过完全播放不了。
        String finalUrl = resourceUrls.optString(sourceId, "");
        if (finalUrl.isEmpty()) {
            for (String candidate : SOURCE_IDS) {
                String u = resourceUrls.optString(candidate, "");
                if (!u.isEmpty()) { finalUrl = u; break; }
            }
        }
        if (finalUrl.isEmpty()) {
            return Result.get().url("").string();
        }

        Map<String, String> referer = new HashMap<>();
        referer.put("Referer", HOST + "/");
        return Result.get().url(finalUrl).header(referer).string();
    }

    /**
     * 解析搜索结果 HTML，每条 .SSbox 对应一个视频。
     * 封面用 img[originalSrc]（懒加载真实地址），标题从 .SSjgName a span 拼接，
     * 备注用年份 + 主演首位。
     */
    private List<Vod> parseSearchHtml(String html) {
        List<Vod> list = new ArrayList<>();
        Document  doc  = Jsoup.parse(html);
        for (Element box : doc.select(".SSbox")) {
            // video_id
            String href = box.select("a.SSjgImg").attr("href");
            // href = "/video/detail?video_id=223864"
            String videoId = "";
            int idx = href.indexOf("video_id=");
            if (idx >= 0) videoId = href.substring(idx + 9);
            if (videoId.isEmpty()) continue;

            // 封面（懒加载，真实 src 在 originalSrc）
            String pic = box.select("img[originalSrc]").attr("originalSrc");

            // 标题：.SSjgName a 下所有 span 的文字拼合（排除空 span）
            StringBuilder title = new StringBuilder();
            for (Element span : box.select(".SSjgName a span")) {
                String t = span.text().trim();
                if (!t.isEmpty()) title.append(t);
            }

            // 年份 + 主演首位 → 备注
            String year    = "";
            String actors  = "";
            for (Element p : box.select(".SSjg > p")) {
                String text = p.text().trim();
                if (text.startsWith("年份")) year   = text.replaceFirst("年份：?", "").trim();
                if (text.startsWith("主演")) actors = p.select("span").first() != null
                        ? p.select("span").first().text().trim() : "";
            }
            String remarks = year.isEmpty() ? actors : (actors.isEmpty() ? year : year + " " + actors);

            Vod vod = new Vod();
            vod.setVodId(videoId);
            vod.setVodName(title.toString());
            vod.setVodPic(pic);
            vod.setVodRemarks(remarks);
            list.add(vod);
        }
        return list;
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    public String searchContent(String key, boolean quick, String pg) throws Exception {
        int page = pg == null || pg.isEmpty() ? 1 : Integer.parseInt(pg);
        String url = API_SEARCH
                + "?page_num="  + page
                + "&sorttype=desc"
                + "&page_size=24"
                + "&tvNum=7"
                + "&sort=new"
                + "&keyword="   + java.net.URLEncoder.encode(key, "UTF-8");
        String     html = OkHttp.string(url, headers());
        List<Vod>  list = parseSearchHtml(html);
        // 搜索结果 HTML 不提供总页数，有结果就允许翻页
        int total = list.isEmpty() ? 0 : page * 24 + 1;
        return Result.get()
                .page(page, page + (list.isEmpty() ? 0 : 1), 24, total)
                .vod(list)
                .string();
    }
}
