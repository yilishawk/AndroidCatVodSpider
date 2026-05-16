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
 * 骚火电影 - 首页秒开版
 * 完全对齐 Python 版本实现
 */
public class ShaoHuo extends Spider {

    private String host = "https://shdy3.com";
    private OkHttpClient client;
    private Map<String, String> headers;

    public ShaoHuo() {
        // 配置 OkHttpClient - 自动解压 gzip
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor(chain -> {
                    Request original = chain.request();
                    Request request = original.newBuilder()
                            .header("Accept-Encoding", "gzip, deflate")
                            .build();
                    return chain.proceed(request);
                })
                .build();

        // 初始化请求头 - 使用手机 UA（关键！）
        this.headers = new HashMap<>();
        this.headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 12; V2196A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.5060.129 Mobile Safari/537.36");
        this.headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        this.headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        this.headers.put("Accept-Encoding", "gzip, deflate");
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
                    String html = new String(response.body().bytes(), StandardCharsets.UTF_8);
                    Pattern pattern = Pattern.compile("(https://.*?\\.com).*?最新网址");
                    Matcher matcher = pattern.matcher(html);
                    if (matcher.find()) {
                        String newHost = matcher.group(1).trim();
                        if (newHost != null && !newHost.isEmpty()) {
                            this.host = newHost;
                            SpiderDebug.log("[骚火电影] 域名更新为: " + host);
                        }
                    } else {
                        SpiderDebug.log("[骚火电影] 未匹配到新域名，保持默认: " + host);
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
            // 自动检测编码
            byte[] bytes = response.body().bytes();
            String contentType = response.header("Content-Type");
            String charset = "UTF-8";
            if (contentType != null && contentType.toLowerCase().contains("charset=gbk")) {
                charset = "GBK";
            }
            String html = new String(bytes, charset);
            SpiderDebug.log("[骚火电影] fetch " + url + " 成功, 长度: " + html.length());
            return html;
        }
    }

    private String encode(String s) throws Exception {
        return s == null || s.isEmpty() ? "" : URLEncoder.encode(s, "UTF-8");
    }

    /**
     * 自然排序 - 与 Python 版完全一致
     */
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
     * 解析视频列表 - 与 Python 版 parse_list 完全一致
     */
    private JSONArray parseList(Document doc) {
        JSONArray videos = new JSONArray();
        Elements items = doc.select("ul.v_list li");
        SpiderDebug.log("[骚火电影] 找到 " + items.size() + " 个视频项");
        
        for (Element item : items) {
            Element link = item.selectFirst(".v_img a");
            if (link == null) continue;
            
            String href = link.attr("href");
            String fullId = href.startsWith("http") ? href : host + href;
            
            Element img = item.selectFirst(".v_img img");
            String pic = "";
            if (img != null) {
                pic = img.attr("data-original");
                if (pic == null || pic.isEmpty()) {
                    pic = img.attr("src");
                }
            }
            
            Element titleElem = item.selectFirst(".v_title a");
            String title = titleElem != null ? titleElem.text() : "";
            
            Element noteElem = item.selectFirst(".v_note");
            String remarks = noteElem != null ? noteElem.text() : "";
            
            try {
                JSONObject vod = new JSONObject();
                vod.put("vod_id", fullId);
                vod.put("vod_name", title);
                vod.put("vod_pic", pic != null ? pic : "");
                vod.put("vod_remarks", remarks);
                videos.put(vod);
                SpiderDebug.log("[骚火电影] 解析视频: " + title);
            } catch (Exception e) {
                SpiderDebug.log("[骚火电影] 解析失败: " + e.getMessage());
            }
        }
        return videos;
    }

    // ================== 首页 ==================

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = new JSONArray();
            
            // 与 Python 版完全一致的分类
            classes.put(createClass("20", "国产剧"));
            classes.put(createClass("1", "电影"));
            classes.put(createClass("2", "电视剧"));
            classes.put(createClass("4", "动漫"));
            result.put("class", classes);

            if (filter) {
                JSONObject filters = new JSONObject();
                
                // 电视剧筛选器
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
                
                // 电影筛选器
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
                
                // 动漫筛选器
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
            SpiderDebug.log("[骚火电影] homeContent error: " + e.getMessage());
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

    // ================== 分类列表 ==================

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            String currTid = tid;
            if (extend != null && extend.containsKey("cateId")) {
                currTid = extend.get("cateId");
            }
            
            String url = host + "/list/" + currTid + "-" + pg + ".html";
            SpiderDebug.log("[骚火电影] category URL: " + url);
            
            String html = fetch(url);
            Document doc = Jsoup.parse(html);
            JSONArray videoList = parseList(doc);
            
            SpiderDebug.log("[骚火电影] 解析到 " + videoList.length() + " 个视频");
            
            JSONObject result = new JSONObject();
            result.put("list", videoList);
            result.put("page", Integer.parseInt(pg));
            result.put("pagecount", 999);
            result.put("limit", 20);
            result.put("total", 9999);
            return result.toString();
            
        } catch (Exception e) {
            SpiderDebug.log("[骚火电影] categoryContent error: " + e.getMessage());
            e.printStackTrace();
            return "{\"list\":[], \"page\":" + pg + "}";
        }
    }

    // ================== 详情页 ==================

    @Override
    public String detailContent(List<String> ids) {
        try {
            if (ids == null || ids.isEmpty()) {
                return "{\"list\":[]}";
            }
            
            String url = ids.get(0);
            SpiderDebug.log("[骚火电影] detail URL: " + url);
            
            String html = fetch(url);
            Document doc = Jsoup.parse(html);
            
            // 使用 pyquery 风格的选择器
            Element vInfo = doc.selectFirst(".v_info_box");
            if (vInfo == null) {
                SpiderDebug.log("[骚火电影] 未找到 .v_info_box");
                return "{\"list\":[]}";
            }
            
            Element pElem = vInfo.selectFirst("p");
            String infoText = pElem != null ? pElem.text().trim() : "";
            
            // 提取地区
            String area = "";
            Pattern areaPattern = Pattern.compile("^(.*?)\\s*/");
            Matcher areaMatcher = areaPattern.matcher(infoText);
            if (areaMatcher.find()) {
                area = areaMatcher.group(1).trim();
            }
            
            // 提取年份
            String year = "";
            Pattern yearPattern = Pattern.compile("(\\d{4})");
            Matcher yearMatcher = yearPattern.matcher(infoText);
            if (yearMatcher.find()) {
                year = yearMatcher.group(1);
            }
            
            // 提取类型
            String vodType = "";
            Pattern typePattern = Pattern.compile("\\d{4}\\s*/\\s*(.*?)\\s*/");
            Matcher typeMatcher = typePattern.matcher(infoText);
            if (typeMatcher.find()) {
                vodType = typeMatcher.group(1).trim();
            }
            
            // 提取导演
            String director = "";
            Pattern directorPattern = Pattern.compile("导演:(.*?)(?= / 主演:|$)");
            Matcher directorMatcher = directorPattern.matcher(infoText);
            if (directorMatcher.find()) {
                director = directorMatcher.group(1).trim();
            }
            
            // 提取演员
            String actor = "";
            Pattern actorPattern = Pattern.compile("主演:(.*?)$");
            Matcher actorMatcher = actorPattern.matcher(infoText);
            if (actorMatcher.find()) {
                actor = actorMatcher.group(1).trim();
            }
            
            // 标题
            Element titleElem = vInfo.selectFirst("h1.v_title a");
            String title = titleElem != null ? titleElem.text().trim() : "";
            
            // 图片
            Element imgElem = doc.selectFirst(".v_img img");
            String pic = "";
            if (imgElem != null) {
                pic = imgElem.attr("data-original");
                if (pic == null || pic.isEmpty()) {
                    pic = imgElem.attr("src");
                }
            }
            
            // 简介
            Element contentElem = doc.selectFirst(".p_txt.show_part");
            String content = "";
            if (contentElem != null) {
                content = contentElem.text().replace("剧情介绍", "").trim();
            }
            
            // 播放来源
            List<String> playFromList = new ArrayList<>();
            for (Element li : doc.select(".play_from ul li")) {
                playFromList.add(li.text());
            }
            String playFrom = playFromList.isEmpty() ? "云播" : String.join("$$$", playFromList);
            
            // 播放链接
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
                // 自然排序
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
            SpiderDebug.log("[骚火电影] detailContent error: " + e.getMessage());
            e.printStackTrace();
            return "{\"list\":[]}";
        }
    }

    // ================== 搜索 ==================

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContentPg(key, quick, "1");
    }
    
    public String searchContentPg(String key, boolean quick, String pg) {
        try {
            String searchUrl = host + "/s----------.html?wd=" + encode(key);
            SpiderDebug.log("[骚火电影] search URL: " + searchUrl);
            
            String html = fetch(searchUrl);
            Document doc = Jsoup.parse(html);
            JSONArray videoList = parseList(doc);
            
            JSONObject result = new JSONObject();
            result.put("list", videoList);
            result.put("page", Integer.parseInt(pg));
            result.put("pagecount", 1);
            result.put("limit", videoList.length());
            result.put("total", videoList.length());
            return result.toString();
            
        } catch (Exception e) {
            SpiderDebug.log("[骚火电影] searchContent error: " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    // ================== 播放解析 ==================

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        SpiderDebug.log("[骚火电影] playerContent: " + id);
        
        try {
            String html = fetch(id);
            
            // 提取 iframe
            Pattern iframePattern = Pattern.compile("iframe src=\"(.*?)\"");
            Matcher iframeMatcher = iframePattern.matcher(html);
            if (iframeMatcher.find()) {
                String jxUrl = iframeMatcher.group(1);
                if (!jxUrl.startsWith("http")) {
                    jxUrl = host + jxUrl;
                }
                SpiderDebug.log("[骚火电影] 找到 iframe: " + jxUrl);
                JSONObject result = new JSONObject();
                result.put("parse", 1);
                result.put("url", jxUrl);
                return result.toString();
            }
            
            // 提取 video 标签
            Pattern videoPattern = Pattern.compile("<video[^>]+src=[\"']([^\"']+)[\"']");
            Matcher videoMatcher = videoPattern.matcher(html);
            if (videoMatcher.find()) {
                String videoUrl = videoMatcher.group(1);
                if (videoUrl.startsWith("http")) {
                    JSONObject result = new JSONObject();
                    result.put("parse", 0);
                    result.put("url", videoUrl);
                    return result.toString();
                }
            }
            
            // 提取 m3u8
            Pattern m3u8Pattern = Pattern.compile("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*");
            Matcher m3u8Matcher = m3u8Pattern.matcher(html);
            if (m3u8Matcher.find()) {
                JSONObject result = new JSONObject();
                result.put("parse", 0);
                result.put("url", m3u8Matcher.group());
                return result.toString();
            }
            
        } catch (Exception e) {
            SpiderDebug.log("[骚火电影] playerContent error: " + e.getMessage());
        }
        
        return "{\"parse\":1,\"url\":\"" + id + "\"}";
    }
}
