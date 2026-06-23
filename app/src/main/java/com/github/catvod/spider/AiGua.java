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

    /**
     * 访问 refresh-cate 第一页，遍历 list 找第一条 resource_url["21"] 非空的条目，
     * 返回其域名部分（https://xxx.xxx.com），找不到返回 null。
     *
     * resource_url 结构示例：
     *   { "1": "https://cf103.yfvodcdn.com/20260531/xxx/index.m3u8",
     *     "21": "https://cfav103.orangecloudapi.com/20260531/xxx/index.m3u8" }
     */
    private String fetchFastDomain() {
        try {
            String    resp = OkHttp.string(cateUrl("2", 1, new HashMap<>()), headers());
            JSONObject root = new JSONObject(resp);
            JSONArray  list = root.getJSONObject("data").getJSONArray("list");
            for (int i = 0; i < list.length(); i++) {
                JSONObject item   = list.getJSONObject(i);
                // resource_url 可能是 JSONObject 或已序列化的 String，两种都处理
                JSONObject resUrl = null;
                Object raw = item.opt("resource_url");
                if (raw instanceof JSONObject) {
                    resUrl = (JSONObject) raw;
                } else if (raw instanceof String) {
                    try { resUrl = new JSONObject((String) raw); } catch (Exception ignored) {}
                }
                if (resUrl == null) continue;
                String url21 = resUrl.optString("21", "");
                if (url21.isEmpty()) continue;
                // 截取 "https://domain" 部分
                int slash = url21.indexOf("/", 8);
                if (slash > 0) return url21.substring(0, slash);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * id 格式：videoId|chapterId
     * 普快：play-url sourceId=1 → 直接返回 resource_url
     * 超快：play-url sourceId=1 拿路径，再从 refresh-cate 取 "21" 域名拼合；
     *       若取域名失败则 fallback 直接用 play-url sourceId=21
     * 均添加 Referer: https://aigua8.com/
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String[] parts     = id.split("\\|", 2);
        String   videoId   = parts[0];
        String   chapterId = parts[1];

        boolean isFast = "超快线路".equals(flag);

        // 普快：sourceId=1 直接用
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
        // 例：https://cf103.yfvodcdn.com/20260531/7RA7ZBAT/index.m3u8

        String finalUrl = baseUrl;
        if (isFast) {
            String domain21 = fetchFastDomain();
            if (domain21 != null) {
                // 只替换域名，保留路径不变
                int pathStart = baseUrl.indexOf("/", 8);
                if (pathStart > 0) {
                    finalUrl = domain21 + baseUrl.substring(pathStart);
                }
            } else {
                // fallback：直接用 sourceId=21 的 play-url
                String apiUrl21 = API_PLAY
                        + "?citycode=AMS"
                        + "&page=detail"
                        + "&chapterId=" + chapterId
                        + "&videoId="   + videoId
                        + "&sourceId=21";
                String resp21 = OkHttp.string(apiUrl21, headers());
                finalUrl = new JSONObject(resp21)
                        .getJSONObject("data")
                        .getJSONObject("urlinfo")
                        .getString("resource_url");
            }
        }

        Map<String, String> referer = new HashMap<>();
        referer.put("Referer", HOST + "/");
        return Result.get().url(finalUrl).header(referer).string();
    }

    private static final String API_SEARCH = HOST + "/video/refresh-video";

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

    @Override
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
