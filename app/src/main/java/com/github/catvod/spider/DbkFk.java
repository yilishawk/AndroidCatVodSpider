package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import com.github.catvod.utils.Util;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DbkFk — 独播库（主） + FkTv（副线路）聚合 Spider
 *
 * 分工：
 *   homeContent / categoryContent / searchContent → 纯独播库
 *   detailContent → 独播库主线路 + 并发 FkTv 副线路（按标题完全匹配）
 *   playerContent → 按 id 来源走各自解析分支
 */
public class DbkFk extends Spider {

    // ========================================================
    // ===================== 配置区 开始 ======================
    // ========================================================

    // 独播库
    private static final String DBK_HOST = "https://www.dbku.tv";

    // FkTv
    private static final String FK_HOST  = "https://fktv.me";
    private static final String FK_UA    = "Mozilla/5.0 (Linux; Android 15; 23054RA19C Build/AP3A.240905.015.A2; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/147.0.7727.137 Mobile Safari/537.36";
    private static final String FK_COOKIE = "_did=wEdXiQxa07zJ15hm0AsNjxsc4rZRSKzb; _device=pc";

    // FkTv 副线路搜索超时（秒）
    private static final int FK_TIMEOUT = 3;

    // FkTv 副线路名前缀
    private static final String FK_PREFIX = "[FK]";

    // 每部剧 FkTv 最多取多少集（避免拉太多）
    private static final int FK_EP_LIMIT = 120;

    // ========================================================
    // ===================== 配置区 结束 ======================
    // ========================================================

    // ──────────────────────────────────────────────
    // Headers
    // ──────────────────────────────────────────────

    private Map<String, String> dbkHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", Util.CHROME);
        h.put("Referer", DBK_HOST + "/");
        return h;
    }

    private Map<String, String> fkHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", FK_UA);
        h.put("Referer", FK_HOST + "/");
        h.put("Accept", "application/json, text/javascript, */*; q=0.01");
        h.put("Cookie", FK_COOKIE);
        return h;
    }

    // ──────────────────────────────────────────────
    // 网络请求
    // ──────────────────────────────────────────────

    private String dbkFetch(String url) {
        try {
            return OkHttp.string(url, dbkHeaders());
        } catch (Exception e) {
            SpiderDebug.log("[DbkFk/DBK] fetch error: " + e.getMessage());
            return "";
        }
    }

    private String fkFetch(String url) {
        try {
            return OkHttp.string(url, fkHeaders());
        } catch (Exception e) {
            SpiderDebug.log("[DbkFk/FK] fetch error: " + e.getMessage());
            return "";
        }
    }

    private String encode(String s) {
        try {
            return s == null || s.isEmpty() ? "" : URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ──────────────────────────────────────────────
    // homeContent — 独播库分类
    // ──────────────────────────────────────────────

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = new JSONArray();
            String[][] classArr = {
                {"13", "陆剧"}, {"2", "电视剧"}, {"1", "电影"}, {"3", "综艺"},
                {"4", "动漫"}, {"15", "日韩剧"}, {"21", "短剧"}, {"14", "台泰剧"}, {"20", "港剧"}
            };
            for (String[] c : classArr) {
                JSONObject cls = new JSONObject();
                cls.put("type_id", c[0]);
                cls.put("type_name", c[1]);
                classes.put(cls);
            }
            result.put("class", classes);

            if (filter) {
                JSONArray tvFilter = new JSONArray();
                tvFilter.put(createFilter("class", "剧情", new String[][]{
                    {"全部", ""}, {"悬疑", "悬疑"}, {"武侠", "武侠"}, {"科幻", "科幻"},
                    {"都市", "都市"}, {"爱情", "爱情"}, {"古装", "古装"}, {"战争", "战争"},
                    {"青春", "青春"}, {"偶像", "偶像"}, {"喜剧", "喜剧"}, {"家庭", "家庭"},
                    {"奇幻", "奇幻"}, {"剧情", "剧情"}, {"乡村", "乡村"}, {"年代", "年代"},
                    {"警匪", "警匪"}, {"谍战", "谍战"}, {"历险", "历险"}, {"罪案", "罪案"},
                    {"宫廷", "宫廷"}, {"经典", "经典"}, {"动作", "动作"}, {"惊悚", "惊悚"},
                    {"历史", "历史"}, {"穿越", "穿越"}, {"同性", "同性"}
                }));
                tvFilter.put(createFilter("area", "地区", new String[][]{
                    {"全部", ""}, {"大陆", "大陆"}, {"香港", "香港"}, {"台湾", "台湾"},
                    {"韩国", "韩国"}, {"日本", "日本"}, {"新加坡", "新加坡"}, {"泰国", "泰国"}
                }));
                tvFilter.put(createFilter("year", "年份", new String[][]{
                    {"全部", ""}, {"2026", "2026"}, {"2025", "2025"}, {"2024", "2024"},
                    {"2023", "2023"}, {"2022", "2022"}, {"2021", "2021"}, {"2020", "2020"},
                    {"2019", "2019"}, {"2018", "2018"}, {"2017", "2017"}, {"更早", "更早"}
                }));
                tvFilter.put(createFilter("lang", "语言", new String[][]{
                    {"全部", ""}, {"国语", "国语"}, {"粤语", "粤语"}, {"韩语", "韩语"},
                    {"泰语", "泰语"}, {"日语", "日语"}
                }));
                tvFilter.put(createFilter("by", "排序", new String[][]{
                    {"时间", "time"}, {"人气", "hits"}, {"评分", "score"}
                }));

                JSONArray movieFilter = new JSONArray();
                movieFilter.put(createFilter("class", "剧情", new String[][]{
                    {"全部", ""}, {"喜剧", "喜剧"}, {"爱情", "爱情"}, {"恐怖", "恐怖"},
                    {"动作", "动作"}, {"科幻", "科幻"}, {"剧情", "剧情"}, {"警匪", "警匪"},
                    {"战争", "战争"}, {"犯罪", "犯罪"}, {"动画", "动画"}, {"奇幻", "奇幻"},
                    {"武侠", "武侠"}, {"冒险", "冒险"}, {"悬疑", "悬疑"}, {"惊悚", "惊悚"},
                    {"古装", "古装"}, {"同性", "同性"}
                }));
                movieFilter.put(createFilter("area", "地区", new String[][]{
                    {"全部", ""}, {"大陆", "大陆"}, {"香港", "香港"}, {"台湾", "台湾"},
                    {"韩国", "韩国"}, {"英国", "英国"}, {"法国", "法国"}, {"加拿大", "加拿大"},
                    {"澳大利亚", "澳大利亚"}
                }));
                movieFilter.put(createFilter("year", "年份", new String[][]{
                    {"全部", ""}, {"2026", "2026"}, {"2025", "2025"}, {"2024", "2024"},
                    {"2023", "2023"}, {"2022", "2022"}, {"2020", "2020"}, {"2019", "2019"}
                }));
                movieFilter.put(createFilter("lang", "语言", new String[][]{
                    {"全部", ""}, {"国语", "国语"}, {"粤语", "粤语"}, {"韩语", "韩语"},
                    {"英语", "英语"}, {"法语", "法语"}
                }));
                movieFilter.put(createFilter("by", "排序", new String[][]{
                    {"时间", "time"}, {"人气", "hits"}, {"评分", "score"}
                }));

                JSONObject filters = new JSONObject();
                for (String[] c : classArr) {
                    String tid = c[0];
                    filters.put(tid, tid.equals("1") || tid.equals("4") ? movieFilter : tvFilter);
                }
                result.put("filters", filters);
            }
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
        }
        return "";
    }

    // ──────────────────────────────────────────────
    // categoryContent — 独播库列表
    // ──────────────────────────────────────────────

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            if (extend == null) extend = new HashMap<>();
            String area   = extend.containsKey("area")  ? extend.get("area")  : "";
            String by     = extend.containsKey("by")    ? extend.get("by")    : "time";
            String class_ = extend.containsKey("class") ? extend.get("class") : "";
            String lang   = extend.containsKey("lang")  ? extend.get("lang")  : "";
            String year   = extend.containsKey("year")  ? extend.get("year")  : "";

            String url = DBK_HOST + "/vodshow/" + tid + "-" + encode(area) + "-" + by + "-" +
                         encode(class_) + "-" + encode(lang) + "----" + pg + "---" + year + ".html";

            SpiderDebug.log("[DbkFk/DBK] category URL: " + url);
            String html = dbkFetch(url);
            if (html == null || html.isEmpty()) return "{\"list\":[]}";

            Document doc = Jsoup.parse(html);
            Elements items = doc.select("ul.myui-vodlist li");
            if (items.isEmpty()) {
                items = doc.select("li .myui-vodlist__thumb").parents();
            }

            JSONArray videoList = new JSONArray();
            for (Element li : items) {
                Element a = li.selectFirst("a.myui-vodlist__thumb");
                if (a == null) a = li.selectFirst("a[data-original]");
                if (a == null) continue;

                JSONObject vod = new JSONObject();
                String href = a.attr("href");
                vod.put("vod_id", href.startsWith("/") ? href : "/" + href);
                vod.put("vod_name", a.attr("title"));
                vod.put("vod_pic", a.attr("data-original"));
                Element picText = li.selectFirst(".pic-text");
                vod.put("vod_remarks", picText != null ? picText.text().trim() : "");
                videoList.put(vod);
            }

            JSONObject result = new JSONObject();
            result.put("list", videoList);
            result.put("page", Integer.parseInt(pg));
            result.put("pagecount", 99);
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
        }
        return "";
    }

    // ──────────────────────────────────────────────
    // searchContent — 独播库搜索
    // ──────────────────────────────────────────────

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String url = DBK_HOST + "/vodsearch/-------------.html?wd=" + encode(key);
            String html = dbkFetch(url);
            if (html == null || html.isEmpty()) return "{\"list\":[]}";

            Document doc = Jsoup.parse(html);
            JSONArray videoList = new JSONArray();
            for (Element li : doc.select("#searchList li")) {
                Element a = li.selectFirst("a.myui-vodlist__thumb");
                if (a == null) continue;
                JSONObject vod = new JSONObject();
                String href = a.attr("href");
                vod.put("vod_id", href.startsWith("/") ? href : "/" + href);
                vod.put("vod_name", a.attr("title"));
                vod.put("vod_pic", a.attr("data-original"));
                Element tag = a.selectFirst(".tag");
                vod.put("vod_remarks", tag != null ? tag.text().trim() : "");
                videoList.put(vod);
            }
            JSONObject result = new JSONObject();
            result.put("list", videoList);
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
        }
        return "{\"list\":[]}";
    }

    // ──────────────────────────────────────────────
    // detailContent — 独播库主线路 + FkTv 并发副线路
    // ──────────────────────────────────────────────

    @Override
    public String detailContent(List<String> ids) {
        try {
            String vodId = ids.get(0);
            String url = vodId.startsWith("http") ? vodId : DBK_HOST + vodId;
            String html = dbkFetch(url);
            if (html == null || html.isEmpty()) return "{\"list\":[]}";

            Document doc = Jsoup.parse(html);
            Element detail = doc.selectFirst(".myui-content__detail");
            if (detail == null) return "{\"list\":[]}";

            // 基本信息
            Element titleElem = detail.selectFirst("h1.title");
            String title = titleElem != null ? titleElem.text().trim() : "";
            Element img = doc.selectFirst(".myui-content__thumb img");
            String pic = img != null ? img.attr("data-original") : "";
            Element sketch = doc.selectFirst(".sketch.content");
            String content = sketch != null ? sketch.text().trim() : "";

            String director = "", actor = "", typeName = "", area = "", year = "", remarks = "";
            for (Element p : detail.select("p.data")) {
                String text = p.text().trim();
                if (text.contains("导演：")) {
                    director = text.replace("导演：", "");
                } else if (text.contains("主演：")) {
                    StringBuilder sb = new StringBuilder();
                    for (Element a : p.select("a")) {
                        if (sb.length() > 0) sb.append(" / ");
                        sb.append(a.text().trim());
                    }
                    actor = sb.toString();
                } else if (text.contains("分类：")) {
                    Elements aTags = p.select("a");
                    if (aTags.size() > 0) typeName = aTags.get(0).text().trim();
                    if (aTags.size() > 1) area = aTags.get(1).text().trim();
                    if (aTags.size() > 2) year = aTags.get(2).text().trim();
                } else if (text.contains("更新：")) {
                    remarks = text.replace("更新：", "");
                }
            }

            // 独播库主线路（分集 id 为独播库播放页相对路径）
            StringBuilder dbkPlayUrl = new StringBuilder();
            Elements playList = doc.select(".myui-content__list li a");
            if (playList.isEmpty()) {
                playList = doc.select(".tab-content .myui-content__list li a");
            }
            for (Element a : playList) {
                String name = a.text().trim();
                String href = a.attr("href");
                if (dbkPlayUrl.length() > 0) dbkPlayUrl.append("#");
                dbkPlayUrl.append(name).append("$").append(href);
            }

            List<String> fromList = new ArrayList<>();
            List<String> urlList = new ArrayList<>();
            fromList.add("独播库");
            urlList.add(dbkPlayUrl.toString());

            // 并发拉 FkTv 副线路
            if (!title.isEmpty()) {
                ExecutorService pool = Executors.newSingleThreadExecutor();
                Future<String[][]> future = pool.submit(() -> fkSearchAndDetail(title));
                try {
                    String[][] fkLines = future.get(FK_TIMEOUT, TimeUnit.SECONDS);
                    for (String[] line : fkLines) {
                        fromList.add(line[0]);
                        urlList.add(line[1]);
                    }
                } catch (Exception e) {
                    SpiderDebug.log("[DbkFk/FK] 副线路超时/失败: " + e.getMessage());
                }
                pool.shutdownNow();
            }

            JSONObject vod = new JSONObject();
            vod.put("vod_id", vodId);
            vod.put("vod_name", title);
            vod.put("vod_pic", pic);
            vod.put("vod_content", content);
            vod.put("vod_director", director);
            vod.put("vod_actor", actor);
            vod.put("type_name", typeName);
            vod.put("vod_area", area);
            vod.put("vod_year", year);
            vod.put("vod_remarks", remarks);
            vod.put("vod_play_from", String.join("$$$", fromList));
            vod.put("vod_play_url", String.join("$$$", urlList));

            JSONArray list = new JSONArray();
            list.put(vod);
            JSONObject result = new JSONObject();
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
        }
        return "{\"list\":[]}";
    }

    // ──────────────────────────────────────────────
    // playerContent — 按来源走不同解析分支
    // ──────────────────────────────────────────────

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        // FkTv 分支：id 格式为 detailUrl|linkId|lineIndex
        if (id.contains(FK_HOST) || id.contains("|")) {
            return fkPlayerContent(id);
        }
        // 独播库分支
        return dbkPlayerContent(id);
    }

    // ──────────────────────────────────────────────
    // FkTv — 搜索 + 详情（供 detailContent 并发调用）
    // 返回 String[N][2]：[from, urlList]
    // ──────────────────────────────────────────────

    private String[][] fkSearchAndDetail(String title) {
        try {
            String searchUrl = FK_HOST + "/channel?keywords=" + encode(title);
            String html = fkFetch(searchUrl);
            if (html == null || html.isEmpty()) return new String[0][];

            Document doc = Jsoup.parse(html);
            String targetNorm = normalize(title);
            String matchedDetailUrl = null;

            for (Element item : doc.select("div.item-wrap.vertical")) {
                Element a = item.selectFirst("a[href^=/movie/detail]");
                if (a == null) continue;
                String name = a.attr("title");
                if (targetNorm.equals(normalize(name))) {
                    matchedDetailUrl = FK_HOST + a.attr("href");
                    break;
                }
            }

            if (matchedDetailUrl == null) {
                SpiderDebug.log("[DbkFk/FK] 标题未完全匹配: " + title);
                return new String[0][];
            }

            // 拉 FkTv 详情页
            String detailHtml = fkFetch(matchedDetailUrl);
            if (detailHtml == null || detailHtml.isEmpty()) return new String[0][];

            List<String> linkIds = fkExtractLinkIds(detailHtml);
            if (linkIds.isEmpty()) return new String[0][];

            List<String> playLines = fkGetPlayUrls(matchedDetailUrl, linkIds.get(0));
            if (playLines.isEmpty()) return new String[0][];

            String[][] result = new String[playLines.size()][2];
            for (int i = 0; i < playLines.size(); i++) {
                String[] parts = playLines.get(i).split("\\$", 2);
                if (parts.length != 2) continue;

                String fromName = FK_PREFIX + parts[0];
                List<String> episodes = new ArrayList<>();
                int limit = Math.min(linkIds.size(), FK_EP_LIMIT);
                for (int j = 0; j < limit; j++) {
                    String epName = "第" + (j + 1) + "集";
                    episodes.add(epName + "$" + matchedDetailUrl + "|" + linkIds.get(j) + "|" + i);
                }
                result[i][0] = fromName;
                result[i][1] = String.join("#", episodes);
            }
            return result;
        } catch (Exception e) {
            SpiderDebug.log("[DbkFk/FK] fkSearchAndDetail error: " + e.getMessage());
        }
        return new String[0][];
    }

    // ──────────────────────────────────────────────
    // FkTv playerContent
    // ──────────────────────────────────────────────

    private String fkPlayerContent(String id) {
        try {
            if (id.contains("|")) {
                String[] parts = id.split("\\|");
                if (parts.length >= 3) {
                    String detailUrl = parts[0];
                    String linkId = parts[1];
                    int lineIndex = 0;
                    try { lineIndex = Integer.parseInt(parts[2]); } catch (Exception ignored) {}

                    List<String> playList = fkGetPlayUrls(detailUrl, linkId);
                    if (lineIndex < playList.size()) {
                        String[] arr = playList.get(lineIndex).split("\\$", 2);
                        if (arr.length == 2) {
                            JSONObject result = new JSONObject();
                            result.put("parse", 0);
                            result.put("url", arr[1]);
                            return result.toString();
                        }
                    }
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("[DbkFk/FK] playerContent error: " + e.getMessage());
        }
        try {
            JSONObject r = new JSONObject();
            r.put("parse", 1);
            r.put("url", id);
            return r.toString();
        } catch (Exception e) {
            return "{\"parse\":1,\"url\":\"\"}";
        }
    }

    // ──────────────────────────────────────────────
    // 独播库 playerContent
    // ──────────────────────────────────────────────

    private String dbkPlayerContent(String id) {
        String url = id.startsWith("http") ? id : DBK_HOST + id;
        try {
            String html = dbkFetch(url);
            if (html == null || html.isEmpty()) return dbkFallback(url);

            String realUrl = extractPlayerUrl(html);
            if (realUrl != null && realUrl.startsWith("http")) {
                JSONObject result = new JSONObject();
                result.put("parse", 0);
                result.put("url", realUrl);
                JSONObject header = new JSONObject();
                header.put("Referer", DBK_HOST + "/");
                result.put("header", header);
                return result.toString();
            }

            realUrl = extractIframeUrl(html);
            if (realUrl != null && realUrl.startsWith("http")) {
                JSONObject result = new JSONObject();
                result.put("parse", 1);
                result.put("url", realUrl);
                return result.toString();
            }

            realUrl = extractVideoUrl(html);
            if (realUrl != null && realUrl.startsWith("http")) {
                JSONObject result = new JSONObject();
                result.put("parse", 0);
                result.put("url", realUrl);
                return result.toString();
            }

            realUrl = extractM3u8Url(html);
            if (realUrl != null && realUrl.startsWith("http")) {
                JSONObject result = new JSONObject();
                result.put("parse", 0);
                result.put("url", realUrl);
                return result.toString();
            }
        } catch (Exception e) {
            SpiderDebug.log("[DbkFk/DBK] playerContent error: " + e.getMessage());
        }
        return dbkFallback(url);
    }

    private String dbkFallback(String url) {
        try {
            JSONObject result = new JSONObject();
            result.put("parse", 1);
            result.put("url", url);
            return result.toString();
        } catch (Exception e) {
            return "{\"parse\":1,\"url\":\"\"}";
        }
    }

    // ──────────────────────────────────────────────
    // FkTv 工具方法
    // ──────────────────────────────────────────────

    private List<String> fkExtractLinkIds(String html) {
        List<String> linkIds = new ArrayList<>();
        String scriptText = "";
        for (Element s : Jsoup.parse(html).select("script")) {
            if (s.html().contains("var links")) {
                scriptText = s.html();
                break;
            }
        }
        String linksStr = extractRegex(scriptText, "var links\\s*=\\s*(\\[.*?\\]);");
        if (!linksStr.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(linksStr);
                for (int i = 0; i < arr.length(); i++) {
                    String lid = arr.getJSONObject(i).optString("id");
                    if (!lid.isEmpty()) linkIds.add(lid);
                }
            } catch (Exception ignored) {}
        }
        if (linkIds.isEmpty()) {
            String defaultId = extractRegex(scriptText, "linkId\\s*=\\s*['\"](.*?)['\"]");
            if (!defaultId.isEmpty()) linkIds.add(defaultId);
        }
        return linkIds;
    }

    private List<String> fkGetPlayUrls(String detailUrl, String linkId) {
        List<String> urls = new ArrayList<>();
        try {
            Map<String, String> postData = new HashMap<>();
            postData.put("link_id", linkId);
            postData.put("is_switch", "1");

            Map<String, String> postHeader = new HashMap<>(fkHeaders());
            postHeader.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            postHeader.put("X-Requested-With", "XMLHttpRequest");
            postHeader.put("Origin", FK_HOST);

            OkResult res = OkHttp.post(detailUrl, postData, postHeader);
            JSONObject json = new JSONObject(res.getBody());

            if ("y".equals(json.optString("status"))) {
                JSONArray playLinks = json.getJSONObject("data").getJSONArray("play_links");
                for (int i = 0; i < playLinks.length(); i++) {
                    JSONObject line = playLinks.getJSONObject(i);
                    String m3u8 = line.optString("m3u8_url");
                    if (m3u8.startsWith("/")) m3u8 = FK_HOST + m3u8;
                    urls.add("线路" + (i + 1) + "$" + m3u8);
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("[DbkFk/FK] getPlayUrls error: " + e.getMessage());
        }
        return urls;
    }

    // ──────────────────────────────────────────────
    // 独播库解析工具方法
    // ──────────────────────────────────────────────

    private String extractPlayerUrl(String html) {
        try {
            Pattern p = Pattern.compile(
                "var\\s+player_data\\s*=\\s*(\\{[\\s\\S]*?\\})\\s*(?:</script>|;\\s*(?:var\\s|window\\.|</))",
                Pattern.MULTILINE);
            Matcher m = p.matcher(html);
            if (!m.find()) {
                p = Pattern.compile(
                    "<script[^>]*>\\s*var\\s+player_data\\s*=\\s*(\\{[\\s\\S]*?\\})\\s*;?\\s*</script>",
                    Pattern.MULTILINE);
                m = p.matcher(html);
                if (!m.find()) return null;
            }
            JSONObject playerData = new JSONObject(m.group(1).trim());
            int encrypt = playerData.optInt("encrypt", 0);
            String encUrl = playerData.optString("url", "");
            if (encUrl.isEmpty()) return null;
            return decodeVideoUrl(encUrl, encrypt);
        } catch (Exception e) {
            SpiderDebug.log("[DbkFk/DBK] extractPlayerUrl error: " + e.getMessage());
        }
        return null;
    }

    private String decodeVideoUrl(String encUrl, int encrypt) {
        if (encUrl == null || encUrl.isEmpty()) return null;
        try {
            if (encrypt == 0) {
                String plain = encUrl.startsWith("http") ? encUrl : URLDecoder.decode(encUrl, "UTF-8");
                return plain.startsWith("http") ? plain : null;
            }
            String step1 = Util.base64Decode(encUrl.replaceAll("\\s", ""));
            if (encrypt == 2 || step1.contains("%")) {
                String step2 = URLDecoder.decode(step1, "UTF-8");
                if (step2.startsWith("http")) return step2;
            }
            if (step1.startsWith("http")) return step1;
        } catch (Exception e) {
            SpiderDebug.log("[DbkFk/DBK] decodeVideoUrl error: " + e.getMessage());
        }
        return null;
    }

    private String extractIframeUrl(String html) {
        try {
            Document doc = Jsoup.parse(html);
            Element iframe = doc.selectFirst("iframe");
            if (iframe != null) {
                String src = iframe.attr("src");
                if (src != null && !src.isEmpty()) {
                    if (src.startsWith("//")) src = "https:" + src;
                    if (src.startsWith("http")) return src;
                }
            }
            Pattern p = Pattern.compile("<iframe[^>]+src=[\"']([^\"']+)[\"']");
            Matcher m = p.matcher(html);
            if (m.find()) {
                String src = m.group(1);
                if (src.startsWith("//")) src = "https:" + src;
                if (src.startsWith("http")) return src;
            }
        } catch (Exception e) {
            SpiderDebug.log("[DbkFk/DBK] extractIframeUrl error: " + e.getMessage());
        }
        return null;
    }

    private String extractVideoUrl(String html) {
        try {
            Document doc = Jsoup.parse(html);
            Element video = doc.selectFirst("video");
            if (video != null) {
                String src = video.attr("src");
                if (src != null && !src.isEmpty() && src.startsWith("http")) return src;
                Element source = video.selectFirst("source");
                if (source != null) {
                    src = source.attr("src");
                    if (src != null && !src.isEmpty() && src.startsWith("http")) return src;
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("[DbkFk/DBK] extractVideoUrl error: " + e.getMessage());
        }
        return null;
    }

    private String extractM3u8Url(String html) {
        try {
            Pattern p = Pattern.compile("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*");
            Matcher m = p.matcher(html);
            if (m.find()) return m.group();
        } catch (Exception e) {
            SpiderDebug.log("[DbkFk/DBK] extractM3u8Url error: " + e.getMessage());
        }
        return null;
    }

    // ──────────────────────────────────────────────
    // 通用工具
    // ──────────────────────────────────────────────

    private static String normalize(String name) {
        if (name == null) return "";
        String s = name.trim().replaceAll("[\\s　]+", "");
        s = s.replaceAll("[（(][^（）()]*[）)]\\s*$", "");
        return s.toLowerCase();
    }

    private String extractRegex(String text, String regex) {
        Pattern p = Pattern.compile(regex);
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    private JSONObject createOption(String n, String v) throws Exception {
        JSONObject o = new JSONObject();
        o.put("n", n);
        o.put("v", v);
        return o;
    }

    private JSONObject createFilter(String key, String name, String[][] options) throws Exception {
        JSONArray arr = new JSONArray();
        for (String[] opt : options) arr.put(createOption(opt[0], opt[1]));
        JSONObject f = new JSONObject();
        f.put("key", key);
        f.put("name", name);
        f.put("value", arr);
        return f;
    }
}
