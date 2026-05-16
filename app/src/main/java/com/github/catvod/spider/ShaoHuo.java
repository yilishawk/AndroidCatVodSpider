package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 骚火电影 - 首页秒开修复版
 * 完全修复编码截断导致的“无视频列表”问题
 */
public class ShaoHuo extends Spider {

    private String host = "https://shdy3.com";
    private final OkHttpClient client;
    private final Map<String, String> headers;

    public ShaoHuo() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();

        this.headers = new HashMap<>();
        this.headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 12; V2196A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.5060.129 Mobile Safari/537.36");
        this.headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        this.headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        this.headers.put("Connection", "keep-alive");
    }

    @Override
    public void init(Context context, String extend) {
        SpiderDebug.log("[骚火电影] 开始解析最新域名...");
        try {
            Request request = new Request.Builder()
                    .url("http://shapp.us")
                    .headers(Headers.of(headers))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String html = response.body().string(); // 让 okhttp 自动处理 gzip 文本
                    Pattern pattern = Pattern.compile("(https://.*?\\.com).*?最新网址");
                    Matcher matcher = pattern.matcher(html);
                    if (matcher.find()) {
                        this.host = matcher.group(1).trim();
                        SpiderDebug.log("[骚火电影] 域名更新为: " + host);
                    }
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("[骚火电影] 域名解析失败: " + e.getMessage());
        }
    }

    private String fetch(String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .headers(Headers.of(headers))
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new Exception("HTTP " + response.code());
            }
            
            // 【核心修复】鲁棒性更强的编码解析
            byte[] bytes = response.body().bytes();
            String contentType = response.header("Content-Type", "").toLowerCase();
            String charset = "UTF-8";
            
            // 骚火部分老页面是 GBK 编码，强制兼容
            if (contentType.contains("gbk") || contentType.contains("gb2312")) {
                charset = "GBK";
            } else {
                // 双重保险：检查字节流头部或内容
                String preview = new String(bytes, 0, Math.min(bytes.length, 1024), StandardCharsets.ISO_8859_1);
                if (preview.contains("charset=gbk") || preview.contains("charset=GBK")) {
                    charset = "GBK";
                }
            }
            return new String(bytes, charset);
        }
    }

    private List<Object> naturalSortKey(String s) {
        List<Object> result = new ArrayList<>();
        Matcher matcher = Pattern.compile("([0-9]+)").matcher(s);
        int lastIndex = 0;
        while (matcher.find()) {
            if (matcher.start() > lastIndex) {
                result.add(s.substring(lastIndex, matcher.start()).toLowerCase());
            }
            result.add(Integer.parseInt(matcher.group(1)));
            lastIndex = matcher.end();
        }
        if (lastIndex < s.length()) {
            result.add(s.substring(lastIndex).toLowerCase());
        }
        return result;
    }

    /**
     * 解析视频列表 - 增强了选择器容错，防止因网页标签微调导致无列表
     */
    private JSONArray parseList(Document doc) {
        JSONArray videos = new JSONArray();
        // 骚火电影的列表容器可能是 ul.v_list，也可能是 div.v_list
        Elements items = doc.select("ul.v_list li, .v_list li");
        SpiderDebug.log("[骚火电影] 找到 DOM 节点数量: " + items.size());
        
        for (Element item : items) {
            try {
                Element link = item.selectFirst(".v_img a, a");
                if (link == null) continue;
                
                String href = link.attr("href");
                if (href.contains("javascript:")) continue;
                String fullId = href.startsWith("http") ? href : host + href;
                
                Element img = item.selectFirst("img");
                String pic = "";
                if (img != null) {
                    pic = img.hasAttr("data-original") ? img.attr("data-original") : img.attr("src");
                }
                
                // 兼容多套排版下的标题抓取
                Element titleElem = item.selectFirst(".v_title a, .v_title, h3 a, p a");
                String title = titleElem != null ? titleElem.text().trim() : "";
                if (title.isEmpty()) continue;
                
                Element noteElem = item.selectFirst(".v_note, .v_remarks, .note");
                String remarks = noteElem != null ? noteElem.text().trim() : "";
                
                JSONObject vod = new JSONObject();
                vod.put("vod_id", fullId);
                vod.put("vod_name", title);
                vod.put("vod_pic", pic);
                vod.put("vod_remarks", remarks);
                videos.put(vod);
            } catch (Exception e) {
                // 单条数据解析失败不影响整页
            }
        }
        return videos;
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = new JSONArray();
            
            classes.put(createClass("20", "国产剧"));
            classes.put(createClass("1", "电影"));
            classes.put(createClass("2", "电视剧"));
            classes.put(createClass("4", "动漫"));
            result.put("class", classes);

            if (filter) {
                JSONObject filters = new JSONObject();
                
                JSONArray tvFilters = new JSONArray();
                JSONObject tvFilter = new JSONObject();
                tvFilter.put("key", "cateId");
                tvFilter.put("name", "类型");
                JSONArray tvValues = new JSONArray();
                tvValues.put(createOption("全部", "2"));
                tvValues.put(createOption("大陆", "20"));
                tvValues.put(createOption("TVB", "21"));
                tvValues.put(createOption("韩剧", "22"));
                tvValues.put(createOption("美剧", "23"));
                tvFilter.put("value", tvValues);
                tvFilters.put(tvFilter);
                
                JSONArray movieFilters = new JSONArray();
                JSONObject movieFilter = new JSONObject();
                movieFilter.put("key", "cateId");
                movieFilter.put("name", "类型");
                JSONArray movieValues = new JSONArray();
                movieValues.put(createOption("全部", "1"));
                movieValues.put(createOption("喜剧", "6"));
                movieValues.put(createOption("爱情", "7"));
                movieValues.put(createOption("动作", "9"));
                movieValues.put(createOption("科幻", "10"));
                movieValues.put(createOption("剧情", "15"));
                movieFilter.put("value", movieValues);
                movieFilters.put(movieFilter);
                
                JSONArray animeFilters = new JSONArray();
                JSONObject animeFilter = new JSONObject();
                animeFilter.put("key", "cateId");
                animeFilter.put("name", "类型");
                JSONArray animeValues = new JSONArray();
                animeValues.put(createOption("全部", "4"));
                animeFilter.put("value", animeValues);
                animeFilters.put(animeFilter);
                
                filters.put("20", tvFilters);
                filters.put("1", movieFilters);
                filters.put("2", tvFilters);
                filters.put("4", animeFilters);
                result.put("filters", filters);
            }
            return result.toString();
        } catch (Exception e) {
            return "{\"class\":[], \"filters\":{}}";
        }
    }

    private JSONObject createClass(String id, String name) throws Exception {
        JSONObject cls = new JSONObject();
        cls.put("type_id", id);
        cls.put("type_name", name);
        return cls;
    }

    private JSONObject createOption(String n, String v) throws Exception {
        JSONObject opt = new JSONObject();
        opt.put("n", n);
        opt.put("v", v);
        return opt;
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            String currTid = tid;
            if (extend != null && extend.containsKey("cateId") && !extend.get("cateId").isEmpty()) {
                currTid = extend.get("cateId");
            }
            
            // 防御空值
            if (currTid == null || currTid.isEmpty()) currTid = "1";
            
            String url = host + "/list/" + currTid + "-" + pg + ".html";
            String html = fetch(url);
            Document doc = Jsoup.parse(html, url); // 带上baseUri利于相对路径解析
            JSONArray videoList = parseList(doc);
            
            JSONObject result = new JSONObject();
            result.put("list", videoList);
            result.put("page", Integer.parseInt(pg));
            result.put("pagecount", 999);
            result.put("limit", 20);
            result.put("total", 9999);
            return result.toString();
        } catch (Exception e) {
            return "{\"list\":[], \"page\":" + pg + "}";
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            if (ids == null || ids.isEmpty()) return "{\"list\":[]}";
            String url = ids.get(0);
            String html = fetch(url);
            Document doc = Jsoup.parse(html, url);
            
            Element vInfo = doc.selectFirst(".v_info_box");
            if (vInfo == null) return "{\"list\":[]}";
            
            Element pElem = vInfo.selectFirst("p");
            String infoText = pElem != null ? pElem.text().trim() : "";
            
            String area = "";
            Matcher areaMatcher = Pattern.compile("^(.*?)\\s*/").matcher(infoText);
            if (areaMatcher.find()) area = areaMatcher.group(1).trim();
            
            String year = "";
            Matcher yearMatcher = Pattern.compile("(\\d{4})").matcher(infoText);
            if (yearMatcher.find()) year = yearMatcher.group(1);
            
            String vodType = "";
            Matcher typeMatcher = Pattern.compile("\\d{4}\\s*/\\s*(.*?)\\s*/").matcher(infoText);
            if (typeMatcher.find()) vodType = typeMatcher.group(1).trim();
            
            String director = "";
            Matcher directorMatcher = Pattern.compile("导演:(.*?)(?= / 主演:|$)").matcher(infoText);
            if (directorMatcher.find()) director = directorMatcher.group(1).trim();
            
            String actor = "";
            Matcher actorMatcher = Pattern.compile("主演:(.*?)$").matcher(infoText);
            if (actorMatcher.find()) actor = actorMatcher.group(1).trim();
            
            Element titleElem = vInfo.selectFirst("h1.v_title a");
            String title = titleElem != null ? titleElem.text().trim() : "";
            
            Element imgElem = doc.selectFirst(".v_img img");
            String pic = imgElem != null ? (imgElem.hasAttr("data-original") ? imgElem.attr("data-original") : imgElem.attr("src")) : "";
            
            Element contentElem = doc.selectFirst(".p_txt.show_part");
            String content = contentElem != null ? contentElem.text().replace("剧情介绍", "").trim() : "";
            
            List<String> playFromList = new ArrayList<>();
            for (Element li : doc.select(".play_from ul li")) {
                playFromList.add(li.text());
            }
            String playFrom = playFromList.isEmpty() ? "云播" : String.join("$$$", playFromList);
            
            List<String> playUrlGroups = new ArrayList<>();
            for (Element group : doc.select("#play_link li")) {
                List<Map<String, String>> currentLineLinks = new ArrayList<>();
                for (Element a : group.select("a")) {
                    String name = a.text();
                    String link = a.attr("href");
                    String fullLink = link.startsWith("http") ? link : host + link;
                    Map<String, String> item = new HashMap<>();
                    item.put("name", name);
                    item.put("url", fullLink);
                    currentLineLinks.add(item);
                }
                
                currentLineLinks.sort((x, y) -> {
                    List<Object> k1 = naturalSortKey(x.get("name"));
                    List<Object> k2 = naturalSortKey(y.get("name"));
                    for (int i = 0; i < Math.min(k1.size(), k2.size()); i++) {
                        Object o1 = k1.get(i);
                        Object o2 = k2.get(i);
                        if (o1 instanceof Integer && o2 instanceof Integer) {
                            int cmp = ((Integer) o1).compareTo((Integer) o2);
                            if (cmp != 0) return cmp;
                        } else {
                            int cmp = o1.toString().compareTo(o2.toString());
                            if (cmp != 0) return cmp;
                        }
                    }
                    return Integer.compare(k1.size(), k2.size());
                });
                
                List<String> formatted = new ArrayList<>();
                for (Map<String, String> item : currentLineLinks) {
                    formatted.add(item.get("name") + "$" + item.get("url"));
                }
                if (!formatted.isEmpty()) {
                    playUrlGroups.add(String.join("#", formatted));
                }
            }
            
            JSONObject vod = new JSONObject();
            vod.put("vod_id", url);
            vod.put("vod_name", title);
            vod.put("vod_pic", pic);
            vod.put("vod_type", vodType);
            vod.put("vod_area", area);
            vod.put("vod_year", year);
            vod.put("vod_director", director);
            vod.put("vod_actor", actor);
            vod.put("vod_content", content);
            vod.put("vod_play_from", playFrom);
            vod.put("vod_play_url", String.join("$$$", playUrlGroups));
            
            JSONArray list = new JSONArray();
            list.put(vod);
            JSONObject result = new JSONObject();
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            return "{\"list\":[]}";
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String searchUrl = host + "/s----------.html?wd=" + URLEncoder.encode(key, "UTF-8");
            String html = fetch(searchUrl);
            Document doc = Jsoup.parse(html, searchUrl);
            JSONArray videoList = parseList(doc);
            
            JSONObject result = new JSONObject();
            result.put("list", videoList);
            result.put("page", 1);
            result.put("pagecount", 1);
            result.put("limit", videoList.length());
            result.put("total", videoList.length());
            return result.toString();
        } catch (Exception e) {
            return "{\"list\":[]}";
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String html = fetch(id);
            Matcher iframeMatcher = Pattern.compile("iframe src=\"(.*?)\"").matcher(html);
            if (iframeMatcher.find()) {
                String jxUrl = iframeMatcher.group(1);
                if (!jxUrl.startsWith("http")) jxUrl = host + jxUrl;
                JSONObject result = new JSONObject();
                result.put("parse", 1);
                result.put("url", jxUrl);
                return result.toString();
            }
            
            Matcher videoMatcher = Pattern.compile("<video[^>]+src=[\"']([^\"']+)[\"']").matcher(html);
            if (videoMatcher.find()) {
                JSONObject result = new JSONObject();
                result.put("parse", 0);
                result.put("url", videoMatcher.group(1));
                return result.toString();
            }
        } catch (Exception e) {
            SpiderDebug.log("[骚火电影] 播放解析失败: " + e.getMessage());
        }
        return "{\"parse\":1,\"url\":\"" + id + "\"}";
    }
}
