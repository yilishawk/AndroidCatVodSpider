package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Y03YY extends Spider {

    private String host = "https://www.03yy.live";
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
    }

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", userAgent);
        headers.put("Referer", host + "/");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        return headers;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        JsonObject result = new JsonObject();
        JsonArray classes = new JsonArray();
        classes.add(createClass("大陆剧", "13"));
        classes.add(createClass("电影", "1"));
        classes.add(createClass("综艺", "3"));
        classes.add(createClass("短剧", "48"));
        result.add("class", classes);

        if (filter) {
            JsonObject filters = new JsonObject();
            String[][] tvTypes = {{"全部", ""}, {"大陆剧", "13"}, {"欧美剧", "27"}, {"韩国剧", "26"}, {"香港剧", "14"}, {"台湾剧", "46"}};
            String[][] movieTypes = {{"全部", ""}, {"动作片", "5"}, {"喜剧片", "10"}, {"爱情片", "6"}, {"科幻片", "7"}};

            filters.add("13", createFilterArray("class", "类型", tvTypes));
            filters.add("1", createFilterArray("class", "类型", movieTypes));
            filters.add("3", createFilterArray("class", "类型", tvTypes)); // 简化处理
            filters.add("48", createFilterArray("class", "类型", tvTypes));
            result.add("filters", filters);
        }
        return result.toString();
    }

    private JsonObject createClass(String name, String id) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type_name", name);
        obj.addProperty("type_id", id);
        return obj;
    }

    private JsonArray createFilterArray(String key, String name, String[][] pairs) {
        JsonArray arr = new JsonArray();
        JsonObject group = new JsonObject();
        group.addProperty("key", key);
        group.addProperty("name", name);
        JsonArray values = new JsonArray();
        for (String[] pair : pairs) {
            JsonObject opt = new JsonObject();
            opt.addProperty("n", pair[0]);
            opt.addProperty("v", pair[1]);
            values.add(opt);
        }
        group.add("value", values);
        arr.add(group);
        return arr;
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = Integer.parseInt(pg);
        String subTid = (extend != null && extend.containsKey("class") && !extend.get("class").isEmpty()) ? extend.get("class") : tid;
        
        // 修正 URL：苹果CMS 常用格式
        String url = host + "/type/index" + subTid + "-" + page + ".html";
        String html = OkHttp.string(url, getHeaders());
        if (TextUtils.isEmpty(html)) return emptyCategoryResult(page);

        Document doc = Jsoup.parse(html);
        // 兼容多种可能的列表容器选择器
        Elements items = doc.select(".Pic-list li, .pic-list li, .vodlist li, .pic-content");
        JsonArray list = new JsonArray();
        
        for (Element item : items) {
            Element a = item.selectFirst("a");
            if (a == null) continue;

            String href = a.attr("href");
            String vid = extractVid(href);
            if (vid.isEmpty()) continue;

            String title = a.attr("title");
            if (TextUtils.isEmpty(title)) title = item.select("h4, .title").text();

            // 图片懒加载处理
            String pic = a.select("img").attr("data-original");
            if (TextUtils.isEmpty(pic)) pic = a.select("img").attr("src");
            if (!pic.startsWith("http")) pic = host + pic;

            String remark = item.select("span, i, .remark").text().trim();

            JsonObject vod = new JsonObject();
            vod.addProperty("vod_id", vid);
            vod.addProperty("vod_name", title.trim());
            vod.addProperty("vod_pic", pic);
            vod.addProperty("vod_remarks", remark);
            list.add(vod);
        }

        JsonObject result = new JsonObject();
        result.add("list", list);
        result.addProperty("page", page);
        result.addProperty("pagecount", 99);
        result.addProperty("limit", 20);
        result.addProperty("total", 999);
        return result.toString();
    }

    private String extractVid(String href) {
        Matcher m = Pattern.compile("index(\\d+)\\.html").matcher(href);
        if (m.find()) return m.group(1);
        return href.replaceAll("[^0-9]", ""); // 兜底逻辑：只提取数字
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String vid = ids.get(0);
        String url = host + "/movie/index" + vid + ".html";
        String html = OkHttp.string(url, getHeaders());
        Document doc = Jsoup.parse(html);

        JsonObject vod = new JsonObject();
        vod.addProperty("vod_id", vid);
        vod.addProperty("vod_name", doc.select("h1").text().trim());
        
        // 播放源解析
        Elements tabs = doc.select(".playfrom #playlist li");
        List<String> fromList = new ArrayList<>();
        List<String> urlList = new ArrayList<>();
        
        for (int i = 0; i < tabs.size(); i++) {
            fromList.add(tabs.get(i).text().trim());
            Elements aList = doc.select("#stab8" + (i + 1) + " a");
            List<String> eps = new ArrayList<>();
            for (Element a : aList) {
                eps.add(a.text() + "$" + a.attr("href"));
            }
            urlList.add(TextUtils.join("#", eps));
        }

        vod.addProperty("vod_play_from", TextUtils.join("$$$", fromList));
        vod.addProperty("vod_play_url", TextUtils.join("$$$", urlList));
        
        JsonArray list = new JsonArray();
        list.add(vod);
        JsonObject result = new JsonObject();
        result.add("list", list);
        return result.toString();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        String url = host + "/search.php?searchword=" + URLEncoder.encode(key, "UTF-8");
        String html = OkHttp.string(url, getHeaders());
        Document doc = Jsoup.parse(html);
        Elements items = doc.select(".Pic-list li, .pic-content");
        JsonArray list = new JsonArray();
        for (Element item : items) {
            String vid = extractVid(item.select("a").attr("href"));
            JsonObject vod = new JsonObject();
            vod.addProperty("vod_id", vid);
            vod.addProperty("vod_name", item.select("h4, .title").text());
            vod.addProperty("vod_pic", item.select("img").attr("data-original"));
            list.add(vod);
        }
        JsonObject result = new JsonObject();
        result.add("list", list);
        return result.toString();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String playUrl = id.startsWith("http") ? id : host + id;
        JsonObject result = new JsonObject();
        result.addProperty("parse", 1); // 03yy 通常使用解析接口，设置为 1 让 App 自动嗅探
        result.addProperty("url", playUrl);
        return result.toString();
    }

    private String emptyCategoryResult(int page) {
        JsonObject result = new JsonObject();
        result.add("list", new JsonArray());
        result.addProperty("page", page);
        return result.toString();
    }
}
