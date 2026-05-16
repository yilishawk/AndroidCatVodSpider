package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 独播库[全功能筛选版] - 修复播放解析与失败嗅探
 */
public class DuBoKu extends Spider {

    private static final String HOST = "https://www.dbku.tv";
    private static final Headers HEADERS = new Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
            .add("Referer", HOST + "/")
            .build();

    private final OkHttpClient client = new OkHttpClient();

    private String fetch(String url) throws Exception {
        Request request = new Request.Builder().url(url).headers(HEADERS).build();
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return new String(response.body().bytes(), "UTF-8");
            }
            throw new Exception("HTTP " + response.code());
        }
    }

    private String encode(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = new JSONArray();
            String[][] classArr = {
                {"13","陆剧"},{"2","电视剧"},{"1","电影"},{"3","综艺"},
                {"4","动漫"},{"15","日韩剧"},{"21","短剧"},{"14","台泰剧"},{"20","港剧"}
            };
            for (String[] c : classArr) {
                JSONObject cls = new JSONObject();
                cls.put("type_id", c[0]);
                cls.put("type_name", c[1]);
                classes.put(cls);
            }
            result.put("class", classes);

            if (filter) {
                // 电视剧筛选器
                JSONArray tvFilter = new JSONArray();
                tvFilter.put(createFilter("class","剧情", new String[][]{
                    {"全部",""},{"悬疑","悬疑"},{"武侠","武侠"},{"科幻","科幻"},{"都市","都市"},{"爱情","爱情"},{"古装","古装"},{"战争","战争"},{"青春","青春"},{"偶像","偶像"},{"喜剧","喜剧"},{"家庭","家庭"},{"奇幻","奇幻"},{"剧情","剧情"},{"乡村","乡村"},{"年代","年代"},{"警匪","警匪"},{"谍战","谍战"},{"历险","历险"},{"罪案","罪案"},{"宫廷","宫廷"},{"经典","经典"},{"动作","动作"},{"惊悚","惊悚"},{"历史","历史"},{"穿越","穿越"},{"同性","同性"}
                }));
                tvFilter.put(createFilter("area","地区", new String[][]{
                    {"全部",""},{"大陆","大陆"},{"香港","香港"},{"台湾","台湾"},{"韩国","韩国"},{"日本","日本"},{"新加坡","新加坡"},{"泰国","泰国"}
                }));
                tvFilter.put(createFilter("year","年份", new String[][]{
                    {"全部",""},{"2026","2026"},{"2025","2025"},{"2024","2024"},{"2023","2023"},{"2022","2022"},{"2021","2021"},{"2020","2020"},{"2019","2019"},{"2018","2018"},{"2017","2017"},{"更早","更早"}
                }));
                tvFilter.put(createFilter("lang","语言", new String[][]{
                    {"全部",""},{"国语","国语"},{"粤语","粤语"},{"韩语","韩语"},{"泰语","泰语"},{"日语","日语"}
                }));
                tvFilter.put(createFilter("by","排序", new String[][]{
                    {"时间","time"},{"人气","hits"},{"评分","score"}
                }));

                // 电影筛选器
                JSONArray movieFilter = new JSONArray();
                movieFilter.put(createFilter("class","剧情", new String[][]{
                    {"全部",""},{"喜剧","喜剧"},{"爱情","爱情"},{"恐怖","恐怖"},{"动作","动作"},{"科幻","科幻"},{"剧情","剧情"},{"警匪","警匪"},{"战争","战争"},{"犯罪","犯罪"},{"动画","动画"},{"奇幻","奇幻"},{"武侠","武侠"},{"冒险","冒险"},{"悬疑","悬疑"},{"惊悚","惊悚"},{"古装","古装"},{"同性","同性"}
                }));
                movieFilter.put(createFilter("area","地区", new String[][]{
                    {"全部",""},{"大陆","大陆"},{"香港","香港"},{"台湾","台湾"},{"韩国","韩国"},{"英国","英国"},{"法国","法国"},{"加拿大","加拿大"},{"澳大利亚","澳大利亚"}
                }));
                movieFilter.put(createFilter("year","年份", new String[][]{
                    {"全部",""},{"2026","2026"},{"2025","2025"},{"2024","2024"},{"2023","2023"},{"2022","2022"},{"2020","2020"},{"2019","2019"}
                }));
                movieFilter.put(createFilter("lang","语言", new String[][]{
                    {"全部",""},{"国语","国语"},{"粤语","粤语"},{"韩语","韩语"},{"英语","英语"},{"法语","法语"}
                }));
                movieFilter.put(createFilter("by","排序", new String[][]{
                    {"时间","time"},{"人气","hits"},{"评分","score"}
                }));

                JSONObject filters = new JSONObject();
                for (String[] c : classArr) {
                    String tid = c[0];
                    if (tid.equals("1") || tid.equals("4")) filters.put(tid, movieFilter);
                    else filters.put(tid, tvFilter);
                }
                result.put("filters", filters);
            }
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
        }
        return "";
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

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            if (extend == null) extend = new HashMap<>();
            String area = extend.getOrDefault("area", "");
            String by = extend.getOrDefault("by", "time");
            String class_ = extend.getOrDefault("class", "");
            String lang = extend.getOrDefault("lang", "");
            String year = extend.getOrDefault("year", "");

            String url = HOST + "/vodshow/" +
                tid + "-" + encode(area) + "-" + by + "-" + encode(class_) + "-" +
                encode(lang) + "----" + pg + "---" + year + ".html";

            SpiderDebug.log("[DuBoKu] category URL: " + url);
            String html = fetch(url);
            Document doc = Jsoup.parse(html);
            Elements items = doc.select("ul.myui-vodlist li");
            if (items.isEmpty()) {
                // 尝试其他可能的选择器
                items = doc.select("ul li a.myui-vodlist__thumb").isEmpty() ?
                        doc.select("li .myui-vodlist__thumb") : items;
                SpiderDebug.log("[DuBoKu] no items found, trying fallback selectors");
            }
            JSONArray videoList = new JSONArray();
            for (Element li : items) {
                Element a = li.selectFirst("a.myui-vodlist__thumb");
                if (a == null) a = li.selectFirst("a[data-original]");
                if (a == null) continue;
                JSONObject vod = new JSONObject();
                vod.put("vod_id", a.attr("href"));
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
            result.put("limit", 48);
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
        }
        return "";
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String vodId = ids.get(0);
            String url = vodId.startsWith("http") ? vodId : HOST + vodId;
            String html = fetch(url);
            Document doc = Jsoup.parse(html);

            Element detail = doc.selectFirst(".myui-content__detail");
            if (detail == null) return "{\"list\":[]}";

            String title = detail.selectFirst("h1.title").text().trim();
            Element img = doc.selectFirst(".myui-content__thumb img");
            String pic = img != null ? img.attr("data-original") : "";
            Element sketch = doc.selectFirst(".sketch.content");
            String content = sketch != null ? sketch.text().trim() : "";

            String director = "", actor = "", typeName = "", area = "", year = "", remarks = "";
            for (Element p : detail.select("p.data")) {
                String text = p.text().trim();
                if (text.contains("导演：")) director = text.replace("导演：", "");
                else if (text.contains("主演：")) {
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
                } else if (text.contains("更新：")) remarks = text.replace("更新：", "");
            }

            StringBuilder playUrl = new StringBuilder();
            for (Element a : doc.select(".myui-content__list li a")) {
                String name = a.text().trim();
                String href = a.attr("href");
                if (playUrl.length() > 0) playUrl.append("#");
                playUrl.append(name).append("$").append(href);
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
            vod.put("vod_play_from", "独播库");
            vod.put("vod_play_url", playUrl.toString());

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

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String url = HOST + "/vodsearch/-------------.html?wd=" + encode(key);
            String html = fetch(url);
            Document doc = Jsoup.parse(html);
            JSONArray videoList = new JSONArray();
            for (Element li : doc.select("#searchList li")) {
                Element a = li.selectFirst("a.myui-vodlist__thumb");
                if (a == null) continue;
                JSONObject vod = new JSONObject();
                vod.put("vod_id", a.attr("href"));
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

    /**
     * 播放解析 - 修复版
     * 1. 从 <script>var player_data = {...}</script> 中提取 url 字段
     * 2. Base64 解码 -> URL 解码 -> 真实播放地址
     * 3. 失败时返回 parse=1 + 完整播放页 URL，让壳子嗅探
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String url = id.startsWith("http") ? id : HOST + id;
            String html = fetch(url);

            // 尝试从 player_data 中提取 url
            Pattern p = Pattern.compile("var player_data\\s*=\\s*(\\{[^;]+\\})\\s*;");
            Matcher m = p.matcher(html);
            if (m.find()) {
                String jsonStr = m.group(1);
                JSONObject playerData = new JSONObject(jsonStr);
                String encUrl = playerData.optString("url");
                if (encUrl != null && !encUrl.isEmpty()) {
                    // Base64 解码
                    int padding = 4 - encUrl.length() % 4;
                    if (padding != 4) for (int i = 0; i < padding; i++) encUrl += "=";
                    byte[] decoded = Base64.getDecoder().decode(encUrl);
                    String decodedUrl = new String(decoded, StandardCharsets.UTF_8);
                    // URL 解码（因为可能含有 %3A 等编码）
                    String realUrl = URLDecoder.decode(decodedUrl, "UTF-8");

                    if (realUrl.startsWith("http")) {
                        JSONObject result = new JSONObject();
                        result.put("parse", 0);
                        result.put("url", realUrl);
                        JSONObject header = new JSONObject();
                        header.put("User-Agent", "Mozilla/5.0");
                        header.put("Referer", HOST + "/");
                        result.put("header", header);
                        return result.toString();
                    }
                }
            }

            // 尝试旧版正则 "url":"..."（作为备用）
            Pattern oldP = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
            Matcher oldM = oldP.matcher(html);
            if (oldM.find()) {
                String encUrl = oldM.group(1);
                int padding = 4 - encUrl.length() % 4;
                if (padding != 4) for (int i = 0; i < padding; i++) encUrl += "=";
                byte[] decoded = Base64.getDecoder().decode(encUrl);
                String realUrl = URLDecoder.decode(new String(decoded, StandardCharsets.UTF_8), "UTF-8");
                if (realUrl.startsWith("http")) {
                    JSONObject result = new JSONObject();
                    result.put("parse", 0);
                    result.put("url", realUrl);
                    JSONObject header = new JSONObject();
                    header.put("User-Agent", "Mozilla/5.0");
                    header.put("Referer", HOST + "/");
                    result.put("header", header);
                    return result.toString();
                }
            }

        } catch (Exception e) {
            SpiderDebug.log(e);
        }

        // 全部失败 → 让壳子嗅探（必须用完整播放页 URL）
        String fullPlayUrl = id.startsWith("http") ? id : HOST + id;
        return "{\"parse\":1,\"url\":\"" + fullPlayUrl + "\"}";
    }
}
