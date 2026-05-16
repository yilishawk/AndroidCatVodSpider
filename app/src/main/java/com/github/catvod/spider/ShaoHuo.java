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

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 骚火电影 - 首页秒开版
 * 站点: https://shdy3.com
 */
public class ShaoHuo extends Spider {

    private String host = "https://shdy3.com";
    private OkHttpClient client;
    private Map<String, String> headers;

    public ShaoHuo() {
        // 配置 OkHttpClient 支持自动解压
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                // 添加拦截器处理 gzip（OkHttp 默认支持，但确保 Accept-Encoding 头）
                .addInterceptor(chain -> {
                    Request original = chain.request();
                    Request request = original.newBuilder()
                            .header("Accept-Encoding", "gzip, deflate")
                            .build();
                    return chain.proceed(request);
                })
                .build();

        // 初始化请求头 - 模拟浏览器
        this.headers = new HashMap<>();
        this.headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        this.headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
        this.headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
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
                    // 注意：这里可能返回的是 GBK 编码
                    byte[] bytes = response.body().bytes();
                    String html = new String(bytes, detectCharset(bytes, response));
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

    /**
     * 检测字符集
     */
    private String detectCharset(byte[] data, Response response) {
        // 先从 Content-Type 头获取
        String contentType = response.header("Content-Type");
        if (contentType != null && contentType.contains("charset=")) {
            Pattern p = Pattern.compile("charset=([^;]+)");
            Matcher m = p.matcher(contentType);
            if (m.find()) {
                String charset = m.group(1).trim();
                SpiderDebug.log("[骚火电影] 检测到字符集: " + charset);
                return charset;
            }
        }
        
        // 从 HTML meta 标签检测
        String html = new String(data, StandardCharsets.ISO_8859_1);
        Pattern p = Pattern.compile("<meta[^>]+charset=[\"']?([^\"'\\s>]+)");
        Matcher m = p.matcher(html);
        if (m.find()) {
            String charset = m.group(1).trim();
            SpiderDebug.log("[骚火电影] 从 meta 检测到字符集: " + charset);
            return charset;
        }
        
        // 默认 UTF-8
        SpiderDebug.log("[骚火电影] 使用默认字符集: UTF-8");
        return "UTF-8";
    }

    private String fetch(String url) throws IOException {
        return fetch(url, null);
    }

    private String fetch(String url, Map<String, String> extraHeaders) throws IOException {
        SpiderDebug.log("[骚火电影] 请求: " + url);
        
        Map<String, String> allHeaders = new HashMap<>(headers);
        if (extraHeaders != null) {
            allHeaders.putAll(extraHeaders);
        }
        
        Request request = new Request.Builder()
                .url(url)
                .headers(Headers.of(allHeaders))
                .build();
        
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            
            byte[] bytes = response.body().bytes();
            String charset = detectCharset(bytes, response);
            String html = new String(bytes, charset);
            
            SpiderDebug.log("[骚火电影] 响应长度: " + html.length() + " 字符");
            return html;
        }
    }

    private String encode(String s) throws Exception {
        return s == null || s.isEmpty() ? "" : URLEncoder.encode(s, "UTF-8");
    }

    /**
     * 自然排序比较器
     */
    private int naturalCompare(String s1, String s2) {
        Pattern p = Pattern.compile("\\d+");
        Matcher m1 = p.matcher(s1);
        Matcher m2 = p.matcher(s2);
        
        while (m1.find() && m2.find()) {
            int num1 = Integer.parseInt(m1.group());
            int num2 = Integer.parseInt(m2.group());
            if (num1 != num2) {
                return Integer.compare(num1, num2);
            }
        }
        return s1.compareTo(s2);
    }

    private JSONArray parseList(Document doc) {
        JSONArray videos = new JSONArray();
        
        // 调试：输出页面标题
        String title = doc.title();
        SpiderDebug.log("[骚火电影] 页面标题: " + title);
        
        // 尝试多种选择器
        Elements items = doc.select("ul.v_list li");
        if (items.isEmpty()) {
            items = doc.select(".v_list li");
        }
        if (items.isEmpty()) {
            items = doc.select("li.v_item");
        }
        if (items.isEmpty()) {
            items = doc.select(".video-item");
        }
        
        SpiderDebug.log("[骚火电影] 找到 " + items.size() + " 个视频项");
        
        for (Element item : items) {
            Element link = item.selectFirst(".v_img a");
            if (link == null) link = item.selectFirst("a[href*=/v/]");
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
                if (pic != null && !pic.startsWith("http") && pic.startsWith("/")) {
                    pic = host + pic;
                }
            }
            
            Element titleElem = item.selectFirst(".v_title a");
            String titleText = titleElem != null ? titleElem.text().trim() : "";
            if (titleText.isEmpty()) {
                titleText = link.attr("title");
            }
            
            Element noteElem = item.selectFirst(".v_note");
            String remarks = noteElem != null ? noteElem.text().trim() : "";
            
            try {
                JSONObject vod = new JSONObject();
                vod.put("vod_id", fullId);
                vod.put("vod_name", titleText);
                vod.put("vod_pic", pic != null ? pic : "");
                vod.put("vod_remarks", remarks);
                videos.put(vod);
                SpiderDebug.log("[骚火电影] 解析视频: " + titleText);
            } catch (Exception e) {
                SpiderDebug.log("[骚火电影] 解析失败: " + e.getMessage());
            }
        }
        
        return videos;
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = new JSONArray();
            
            String[][] classArr = {
                {"20", "国产剧"},
                {"1", "电影"},
                {"2", "电视剧"},
                {"4", "动漫"}
            };
            
            for (String[] c : classArr) {
                JSONObject cls = new JSONObject();
                cls.put("type_id", c[0]);
                cls.put("type_name", c[1]);
                classes.put(cls);
            }
            result.put("class", classes);
            
            if (filter) {
                JSONObject filters = new JSONObject();
                
                JSONArray tvFilters = new JSONArray();
                JSONObject tvTypeFilter = new JSONObject();
                tvTypeFilter.put("key", "cateId");
                tvTypeFilter.put("name", "类型");
                JSONArray tvOptions = new JSONArray();
                String[][] tvTypes = {
                    {"全部", "2"}, {"大陆", "20"}, {"TVB", "21"},
                    {"韩剧", "22"}, {"美剧", "23"}
                };
                for (String[] opt : tvTypes) {
                    JSONObject option = new JSONObject();
                    option.put("n", opt[0]);
                    option.put("v", opt[1]);
                    tvOptions.put(option);
                }
                tvTypeFilter.put("value", tvOptions);
                tvFilters.put(tvTypeFilter);
                filters.put("20", tvFilters);
                filters.put("2", tvFilters);
                
                JSONArray movieFilters = new JSONArray();
                JSONObject movieTypeFilter = new JSONObject();
                movieTypeFilter.put("key", "cateId");
                movieTypeFilter.put("name", "类型");
                JSONArray movieOptions = new JSONArray();
                String[][] movieTypes = {
                    {"全部", "1"}, {"喜剧", "6"}, {"爱情", "7"},
                    {"动作", "9"}, {"科幻", "10"}, {"剧情", "15"}
                };
                for (String[] opt : movieTypes) {
                    JSONObject option = new JSONObject();
                    option.put("n", opt[0]);
                    option.put("v", opt[1]);
                    movieOptions.put(option);
                }
                movieTypeFilter.put("value", movieOptions);
                movieFilters.put(movieTypeFilter);
                filters.put("1", movieFilters);
                
                JSONArray animeFilters = new JSONArray();
                JSONObject animeTypeFilter = new JSONObject();
                animeTypeFilter.put("key", "cateId");
                animeTypeFilter.put("name", "类型");
                JSONArray animeOptions = new JSONArray();
                JSONObject animeOption = new JSONObject();
                animeOption.put("n", "全部");
                animeOption.put("v", "4");
                animeOptions.put(animeOption);
                animeTypeFilter.put("value", animeOptions);
                animeFilters.put(animeTypeFilter);
                filters.put("4", animeFilters);
                
                result.put("filters", filters);
            }
            
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log("[骚火电影] homeContent error: " + e.getMessage());
            return "{\"class\":[], \"filters\":{}}";
        }
    }

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
            
            // 调试：保存 HTML 到文件
            // saveDebugHtml(html, "category_" + currTid + "_" + pg);
            
            JSONArray videoList = parseList(doc);
            SpiderDebug.log("[骚火电影] 解析到 " + videoList.length() + " 个视频");
            
            JSONObject result = new JSONObject();
            result.put("list", videoList);
            result.put("page", Integer.parseInt(pg));
            result.put("pagecount", 99);
            return result.toString();
            
        } catch (Exception e) {
            SpiderDebug.log("[骚火电影] categoryContent error: " + e.getMessage());
            e.printStackTrace();
            return "{\"list\":[], \"page\":" + pg + "}";
        }
    }

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
            
            Element vInfoBox = doc.selectFirst(".v_info_box");
            if (vInfoBox == null) {
                SpiderDebug.log("[骚火电影] 未找到 .v_info_box");
                return "{\"list\":[]}";
            }
            
            String infoText = vInfoBox.selectFirst("p") != null ? vInfoBox.selectFirst("p").text() : "";
            
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
            
            Element titleElem = vInfoBox.selectFirst("h1.v_title a");
            String title = titleElem != null ? titleElem.text().trim() : "";
            
            Element imgElem = doc.selectFirst(".v_img img");
            String pic = "";
            if (imgElem != null) {
                pic = imgElem.attr("data-original");
                if (pic == null || pic.isEmpty()) {
                    pic = imgElem.attr("src");
                }
            }
            
            Element contentElem = doc.selectFirst(".p_txt.show_part");
            String content = "";
            if (contentElem != null) {
                content = contentElem.text().replace("剧情介绍", "").trim();
            }
            
            // 播放来源
            StringBuilder playFrom = new StringBuilder();
            for (Element li : doc.select(".play_from ul li")) {
                if (playFrom.length() > 0) playFrom.append("$$$");
                playFrom.append(li.text().trim());
            }
            if (playFrom.length() == 0) {
                playFrom.append("云播");
            }
            
            // 播放链接
            List<String> playUrlGroups = new ArrayList<>();
            Elements playLinks = doc.select("#play_link li a");
            if (playLinks.isEmpty()) {
                playLinks = doc.select(".play_list a");
            }
            
            List<String> episodes = new ArrayList<>();
            for (Element a : playLinks) {
                String name = a.text().trim();
                String link = a.attr("href");
                String fullLink = link.startsWith("http") ? link : host + link;
                episodes.add(name + "$" + fullLink);
            }
            
            // 自然排序
            episodes.sort((x, y) -> naturalCompare(x.split("\\$")[0], y.split("\\$")[0]));
            playUrlGroups.add(String.join("#", episodes));
            
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
            vod.put("vod_play_from", playFrom.toString());
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

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }
    
    public String searchContent(String key, boolean quick, String pg) {
        try {
            String searchUrl = host + "/s----------.html?wd=" + encode(key);
            SpiderDebug.log("[骚火电影] search URL: " + searchUrl);
            
            String html = fetch(searchUrl);
            Document doc = Jsoup.parse(html);
            JSONArray videoList = parseList(doc);
            
            JSONObject result = new JSONObject();
            result.put("list", videoList);
            return result.toString();
            
        } catch (Exception e) {
            SpiderDebug.log("[骚火电影] searchContent error: " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        SpiderDebug.log("[骚火电影] playerContent: " + id);
        
        try {
            String html = fetch(id);
            
            // 提取 iframe
            Pattern iframePattern = Pattern.compile("<iframe[^>]+src=[\"']([^\"']+)[\"']");
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
            
            // 提取 m3u8 链接
            Pattern m3u8Pattern = Pattern.compile("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*");
            Matcher m3u8Matcher = m3u8Pattern.matcher(html);
            if (m3u8Matcher.find()) {
                String m3u8Url = m3u8Matcher.group();
                JSONObject result = new JSONObject();
                result.put("parse", 0);
                result.put("url", m3u8Url);
                return result.toString();
            }
            
        } catch (Exception e) {
            SpiderDebug.log("[骚火电影] playerContent error: " + e.getMessage());
        }
        
        return "{\"parse\":1,\"url\":\"" + id + "\"}";
    }
    
    /**
     * 调试方法：保存 HTML 到文件
     */
    private void saveDebugHtml(String html, String name) {
        try {
            java.io.File dir = new java.io.File("/sdcard/shaohuo_debug");
            if (!dir.exists()) dir.mkdirs();
            java.io.File file = new java.io.File(dir, name + ".html");
            java.io.FileWriter fw = new java.io.FileWriter(file);
            fw.write(html);
            fw.close();
            SpiderDebug.log("[骚火电影] 已保存到: " + file.getAbsolutePath());
        } catch (Exception e) {
            SpiderDebug.log("[骚火电影] 保存失败: " + e.getMessage());
        }
    }
}
