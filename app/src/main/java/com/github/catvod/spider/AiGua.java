package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.utils.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AiGua extends Spider {

    private static final String HOST      = "https://aigua8.com";
    private static final String API_CATE  = HOST + "/video/refresh-cate";
    private static final String API_PLAY  = HOST + "/video/play-url";

    // channel_id -> 频道名
    private static final String[][] CHANNELS = {
            {"2",  "电视剧"},
            {"1",  "电影"},
            {"3",  "综艺"},
            {"4",  "动漫"},
            {"32", "纪录片"},
    };

    // 线路名 -> sourceId（detail 页 data-source-id）
    private static final String[]   SOURCE_NAMES = {"普快线路", "超快线路"};
    private static final String[]   SOURCE_IDS   = {"1",       "21"};

    // 各频道筛选缓存
    private final Map<String, List<Filter>> filterCache = new HashMap<>();

    // ------------------------------------------------------------------ 工具

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("Referer",    HOST + "/");
        h.put("User-Agent", "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 Chrome/114 Safari/537.36");
        return h;
    }

    private String cateUrl(String channelId, int page, Map<String, String> ext) {
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

    /** /video/refresh-cate 返回的 list 数组 → Vod 列表（仅封面信息） */
    private List<Vod> parseVodList(JSONArray arr) throws Exception {
        List<Vod> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Vod vod = new Vod();
            vod.setVodId(String.valueOf(o.optInt("video_id")));
            vod.setVodName(o.optString("video_name"));
            vod.setVodPic(o.optString("cover"));
            vod.setVodRemarks(o.optString("flag"));
            vod.setVodScore(o.optString("score"));
            list.add(vod);
        }
        return list;
    }

    /** 从 search_box 动态解析筛选条件，跳过 channel_id / source */
    private List<Filter> fetchFilters(String channelId) {
        if (filterCache.containsKey(channelId)) return filterCache.get(channelId);
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
                    values.add(new Filter.Value(v.getString("display"), v.get("value").toString()));
                }
                result.add(new Filter(field, box.getString("label"), values));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        filterCache.put(channelId, result);
        return result;
    }

    /**
     * 解析详情页 HTML，提取所有集数。
     * <li data-video-id="223864" data-chapter-id="2580049" data-source-id="1" ...>
     *     <div class="select-link">01</div>
     * </li>
     * 两条线路章节 ID 相同，只解析 sourceId=1 那组，复用给 sourceId=21。
     *
     * playerContent 的 id 格式：videoId|chapterId
     */
    private void buildPlayUrls(Vod vod, String videoId, Document doc) {
        // 取 source-id=1 的集数列表（source-id=21 结构一致，章节号相同）
        StringBuilder epNames = new StringBuilder();
        StringBuilder epIds   = new StringBuilder();

        // 选所有 data-source-id="1" 的 li
        Elements items = doc.select("li[data-source-id=1][data-chapter-id]");
        for (int i = 0; i < items.size(); i++) {
            Element li        = items.get(i);
            String  chapterId = li.attr("data-chapter-id");
            String  title     = li.select(".select-link").text().trim();
            if (title.isEmpty()) title = String.valueOf(i + 1);
            if (i > 0) { epNames.append("#"); epIds.append("#"); }
            // id = videoId|chapterId，playerContent 里拆开再调 play-url
            epNames.append(title);
            epIds.append(videoId).append("|").append(chapterId);
        }

        String epNamesStr = epNames.toString();
        String epIdsStr   = epIds.toString();

        // 两条线路，集数完全一样，flag 区分 sourceId
        StringBuilder fromSb = new StringBuilder();
        StringBuilder urlSb  = new StringBuilder();
        for (int k = 0; k < SOURCE_NAMES.length; k++) {
            if (k > 0) { fromSb.append("$$$"); urlSb.append("$$$"); }
            fromSb.append(SOURCE_NAMES[k]);
            // 每集 URL = epTitle$videoId|chapterId
            // 需要把 epNames 和 epIds 合并成 "title$id#title$id#..."
            String[] names = epNamesStr.split("#", -1);
            String[] ids   = epIdsStr.split("#", -1);
            StringBuilder line = new StringBuilder();
            for (int j = 0; j < names.length; j++) {
                if (j > 0) line.append("#");
                line.append(names[j]).append("$").append(ids[j]);
            }
            urlSb.append(line);
        }
        vod.setVodPlayFrom(fromSb.toString());
        vod.setVodPlayUrl(urlSb.toString());
    }

    // ------------------------------------------------------------------ Spider

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class>               classes   = new ArrayList<>();
        Map<String, List<Filter>> filterMap = new LinkedHashMap<>();

        if (filter) {
            ExecutorService exec  = Executors.newFixedThreadPool(CHANNELS.length);
            CountDownLatch  latch = new CountDownLatch(CHANNELS.length);
            for (String[] ch : CHANNELS) {
                String tid = ch[0];
                exec.submit(() -> { try { fetchFilters(tid); } finally { latch.countDown(); } });
            }
            latch.await();
            exec.shutdown();
            for (String[] ch : CHANNELS)
                filterMap.put(ch[0], filterCache.getOrDefault(ch[0], new ArrayList<>()));
        }
        for (String[] ch : CHANNELS) classes.add(new Class(ch[0], ch[1]));

        // 首页推荐：电视剧第一页
        String    resp = OkHttp.string(cateUrl("2", 1, new HashMap<>()), headers());
        JSONArray list = new JSONObject(resp).getJSONObject("data").getJSONArray("list");

        return Result.get()
                .classContent(classes)
                .filterContent(filterMap)
                .vodContent(parseVodList(list))
                .toJson();
    }

    @Override
    public String homeVideoContent() throws Exception {
        String    resp = OkHttp.string(cateUrl("2", 1, new HashMap<>()), headers());
        JSONArray list = new JSONObject(resp).getJSONObject("data").getJSONArray("list");
        return Result.get().vodContent(parseVodList(list)).toJson();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) throws Exception {
        int    page = pg == null || pg.isEmpty() ? 1 : Integer.parseInt(pg);
        String resp = OkHttp.string(cateUrl(tid, page, extend), headers());
        JSONObject data   = new JSONObject(resp).getJSONObject("data");
        int        total  = data.optInt("total_count", 0);
        JSONArray  list   = data.getJSONArray("list");

        return Result.get()
                .vodContent(parseVodList(list))
                .page(pg)
                .total(String.valueOf(total))
                .limit("24")
                .toJson();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String videoId  = ids.get(0);
        String pageUrl  = HOST + "/video/detail?video_id=" + videoId;
        String html     = OkHttp.string(pageUrl, headers());
        Document doc    = Jsoup.parse(html);

        Vod vod = new Vod();
        vod.setVodId(videoId);

        // 基本信息（meta / 页面文字）
        vod.setVodName(doc.select("meta[property=og:title]").attr("content"));
        vod.setVodPic(doc.select("meta[property=og:image]").attr("content"));
        vod.setVodContent(doc.select("meta[property=og:description]").attr("content"));

        // 详细字段：导演、主演、年份、地区等（各站结构不同，按实际 HTML 调整选择器）
        for (Element p : doc.select(".info p, .video-info p, .detail-info p")) {
            String text = p.text();
            if (text.startsWith("导演")) vod.setVodDirector(text.replace("导演：", "").trim());
            else if (text.startsWith("主演")) vod.setVodActor(text.replace("主演：", "").trim());
            else if (text.startsWith("年份")) vod.setVodYear(text.replace("年份：", "").trim());
            else if (text.startsWith("地区") || text.startsWith("地域"))
                vod.setVodArea(text.replaceAll("地[区域]：", "").trim());
        }

        // 集数 + 线路
        buildPlayUrls(vod, videoId, doc);

        return Result.get().vodContent(Arrays.asList(vod)).toJson();
    }

    /**
     * id 格式：videoId|chapterId（由 buildPlayUrls 写入）
     * flag：线路名（"普快线路" / "超快线路"）→ sourceId
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String[] parts     = id.split("\\|", 2);
        String   videoId   = parts[0];
        String   chapterId = parts[1];

        // flag → sourceId
        String sourceId = SOURCE_IDS[0]; // 默认普快
        for (int k = 0; k < SOURCE_NAMES.length; k++) {
            if (SOURCE_NAMES[k].equals(flag)) { sourceId = SOURCE_IDS[k]; break; }
        }

        String url = API_PLAY
                + "?citycode=AMS"
                + "&page=detail"
                + "&chapterId=" + chapterId
                + "&videoId="   + videoId
                + "&sourceId="  + sourceId;

        String     resp    = OkHttp.string(url, headers());
        JSONObject urlinfo = new JSONObject(resp)
                .getJSONObject("data")
                .getJSONObject("urlinfo");

        String resourceUrl = urlinfo.getString("resource_url");
        // begin_time / end_time 可选传给框架（若框架支持）
        // int beginTime = urlinfo.optInt("begin_time", 0);
        // int endTime   = urlinfo.optInt("end_time",   0);

        return Result.get().url(resourceUrl).toJson();
    }

    // 搜索暂不实现
    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return Result.get().toJson();
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return Result.get().toJson();
    }
}
