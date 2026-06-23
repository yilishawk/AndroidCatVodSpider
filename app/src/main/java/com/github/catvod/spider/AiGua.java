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

public class AiGua extends Spider {

    private static final String HOST     = "https://aigua8.com";
    private static final String API_CATE = HOST + "/video/refresh-cate";
    private static final String API_PLAY = HOST + "/video/play-url";

    private static final String[][] CHANNELS = {
            {"2",  "电视剧"},
            {"1",  "电影"},
            {"3",  "综艺"},
            {"4",  "动漫"},
            {"32", "纪录片"},
    };

    private static final String[] SOURCE_NAMES = {"普快线路", "超快线路"};
    private static final String[] SOURCE_IDS   = {"1",       "21"};

    // filters 必须是 LinkedHashMap 才能传给 Result.filters()
    private final LinkedHashMap<String, List<Filter>> filterCache = new LinkedHashMap<>();

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
        if (filterCache.containsKey(channelId)) return;
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
            latch.await();
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
        vod.setVodName(doc.select("meta[property=og:title]").attr("content"));
        vod.setVodPic(doc.select("meta[property=og:image]").attr("content"));
        vod.setVodContent(doc.select("meta[property=og:description]").attr("content"));

        for (Element p : doc.select(".info p, .video-info p, .detail-info p")) {
            String text = p.text();
            if (text.startsWith("导演")) vod.setVodDirector(text.replace("导演：", "").trim());
            else if (text.startsWith("主演")) vod.setVodActor(text.replace("主演：", "").trim());
            else if (text.startsWith("年份")) vod.setVodYear(text.replace("年份：", "").trim());
            else if (text.startsWith("地区") || text.startsWith("地域"))
                vod.setVodArea(text.replaceAll("地[区域]：", "").trim());
        }

        buildPlayUrls(vod, videoId, doc);
        return Result.get().vod(vod).string();
    }

    // 超快线路域名缓存
    private volatile String fastDomainCache = null;

    /**
     * 从 refresh-cate 列表里取第一条有 resource_url["21"] 的域名。
     * 例：https://cfav103.orangecloudapi.com/20260531/xxx/index.m3u8
     *   → https://cfav103.orangecloudapi.com
     */
    private String getFastDomain() {
        if (fastDomainCache != null) return fastDomainCache;
        try {
            String    resp = OkHttp.string(cateUrl("2", 1, new HashMap<>()), headers());
            JSONArray list = new JSONObject(resp).getJSONObject("data").getJSONArray("list");
            for (int i = 0; i < list.length(); i++) {
                JSONObject resUrl = list.getJSONObject(i).optJSONObject("resource_url");
                if (resUrl == null) continue;
                String url21 = resUrl.optString("21", "");
                if (url21.isEmpty()) continue;
                int slash = url21.indexOf("/", 8);
                if (slash > 0) {
                    fastDomainCache = url21.substring(0, slash);
                    return fastDomainCache;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * id 格式：videoId|chapterId
     * 普快：直接用 play-url 返回的 resource_url
     * 超快：普快路径 + refresh-cate 里的 "21" 域名拼合
     * 均添加 Referer: https://aigua8.com/
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String[] parts     = id.split("\\|", 2);
        String   videoId   = parts[0];
        String   chapterId = parts[1];

        // 始终用 sourceId=1 拿真实路径
        String apiUrl = API_PLAY
                + "?citycode=AMS"
                + "&page=detail"
                + "&chapterId=" + chapterId
                + "&videoId="   + videoId
                + "&sourceId=1";

        String resp    = OkHttp.string(apiUrl, headers());
        String baseUrl = new JSONObject(resp)
                .getJSONObject("data")
                .getJSONObject("urlinfo")
                .getString("resource_url");
        // baseUrl 示例：https://cf103.yfvodcdn.com/20260531/7RA7ZBAT/index.m3u8

        String finalUrl = baseUrl;
        if ("超快线路".equals(flag)) {
            String domain21 = getFastDomain();
            if (domain21 != null) {
                int pathStart = baseUrl.indexOf("/", 8);
                if (pathStart > 0) finalUrl = domain21 + baseUrl.substring(pathStart);
            }
        }

        Map<String, String> referer = new HashMap<>();
        referer.put("Referer", HOST + "/");
        return Result.get().url(finalUrl).header(referer).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return Result.get().vod(Collections.emptyList()).string();
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return Result.get().vod(Collections.emptyList()).string();
    }
}
