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
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 骚火电影 - 首页秒开版
 * 站点: https://shdy3.com
 */
public class ShaoHuo extends Spider {

    private String host = "https://shdy3.com";
    private final OkHttpClient client;
    private final Map<String, String> headers;

    public ShaoHuoMovie() {
        // 初始化 OkHttpClient
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();

        // 初始化请求头
        this.headers = new HashMap<>();
        this.headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 12; V2196A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.5060.129 Mobile Safari/537.36");
        this.headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        this.headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        this.headers.put("Accept-Encoding", "gzip, deflate");
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
                    String html = response.body().string();
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

    @Override
    public String getName() {
        return "骚火电影[首页秒开版]";
    }

    // ================== 工具方法 ==================

    /**
     * 自然排序：确保集数 1, 2, ... 10 顺序正确
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
     * 自然排序比较器
     */
    private int naturalCompare(String s1, String s2) {
        List<Object> keys1 = naturalSortKey(s1);
        List<Object> keys2 = naturalSortKey(s2);
        int minLen = Math.min(keys1.size(), keys2.size());
        for (int i = 0; i < minLen; i++) {
            Object o1 = keys1.get(i);
            Object o2 = keys2.get(i);
            if (o1 instanceof Integer && o2 instanceof Integer) {
                int cmp = ((Integer) o1).compareTo((Integer) o2);
                if (cmp != 0) return cmp;
            } else if (o1 instanceof String && o2 instanceof String) {
                int cmp = ((String) o1).compareTo((String) o2);
                if (cmp != 0) return cmp;
            } else {
                return o1.toString().compareTo(o2.toString());
            }
        }
        return Integer.compare(keys1.size(), keys2.size());
    }

    /**
     * 模拟 JS OKOK 解密逻辑
     */
    private String decodeKey(String encodedStr, Map<String, String> eeDict) {
        try {
            SpiderDebug.log("[骚火电影] 开始解密 OKOK key...");
            // Base64 解码
            String decodedBase64 = new String(Base64.getDecoder().decode(encodedStr), StandardCharsets.UTF_8);
            
            // 按 key 长度降序排序
            List<String> sortedKeys = new ArrayList<>(eeDict.keySet());
            sortedKeys.sort((a, b) -> Integer.compare(b.length(), a.length()));
            
            StringBuilder result = new StringBuilder();
            int i = 0;
            while (i < decodedBase64.length()) {
                boolean matchFound = false;
                for (String key : sortedKeys) {
                    if (decodedBase64.startsWith(key, i)) {
                        result.append(eeDict.get(key));
                        i += key.length();
                        matchFound = true;
                        break;
                    }
                }
                if (!matchFound) {
                    result.append(decodedBase64.charAt(i));
                    i++;
                }
            }
            SpiderDebug.log("[骚火电影] 解密成功");
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log("[骚火电影] 解密失败: " + e.getMessage());
            return "";
        }
    }

    /**
     * 带重试的 GET 请求
     */
    private String retryGet(String url, Map<String, String> extraHeaders, int maxRetries) throws IOException {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                SpiderDebug.log("[骚火电影] 请求 GET " + url + " (尝试 " + attempt + "/" + maxRetries + ")");
                Map<String, String> allHeaders = new HashMap<>(headers);
                if (extraHeaders != null) {
                    allHeaders.putAll(extraHeaders);
                }
                Request request = new Request.Builder()
                        .url(url)
                        .headers(Headers.of(allHeaders))
                        .build();
                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        SpiderDebug.log("[骚火电影] 请求成功，状态码: " + response.code());
                        return response.body().string();
                    } else {
                        SpiderDebug.log("[骚火电影] 请求失败，状态码: " + response.code());
                    }
                }
            } catch (IOException e) {
                SpiderDebug.log("[骚火电影] 请求失败 (尝试 " + attempt + "): " + e.getMessage());
                if (attempt == maxRetries) throw e;
                long wait = (long) Math.pow(2, attempt - 1);
                SpiderDebug.log("[骚火电影] 等待 " + wait + " 秒后重试...");
                try {
                    Thread.sleep(wait * 1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return null;
    }

    private String retryGet(String url, int maxRetries) throws IOException {
        return retryGet(url, null, maxRetries);
    }

    /**
     * 带重试的 POST 请求
     */
    private String retryPost(String url, Map<String, String> data, Map<String, String> extraHeaders, int maxRetries) throws IOException {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                SpiderDebug.log("[骚火电影] 请求 POST " + url + " (尝试 " + attempt + "/" + maxRetries + ")");
                Map<String, String> allHeaders = new HashMap<>(headers);
                if (extraHeaders != null) {
                    allHeaders.putAll(extraHeaders);
                }
                
                // 构建表单数据
                StringBuilder formBody = new StringBuilder();
                for (Map.Entry<String, String> entry : data.entrySet()) {
                    if (formBody.length() > 0) formBody.append("&");
                    formBody.append(entry.getKey()).append("=").append(URLEncoder.encode(entry.getValue(), "UTF-8"));
                }
                
                RequestBody body = RequestBody.create(
                        MediaType.parse("application/x-www-form-urlencoded; charset=UTF-8"),
                        formBody.toString()
                );
                
                Request request = new Request.Builder()
                        .url(url)
                        .post(body)
                        .headers(Headers.of(allHeaders))
                        .build();
                        
                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        SpiderDebug.log("[骚火电影] 请求成功，状态码: " + response.code());
                        return response.body().string();
                    }
                }
            } catch (IOException e) {
                SpiderDebug.log("[骚火电影] 请求失败 (尝试 " + attempt + "): " + e.getMessage());
                if (attempt == maxRetries) throw e;
                long wait = (long) Math.pow(2, attempt - 1);
                try {
                    Thread.sleep(wait * 1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return null;
    }

    /**
     * 解析视频列表
     */
    private JSONArray parseList(Document doc) {
        JSONArray videos = new JSONArray();
        Elements items = doc.select("ul.v_list li");
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
            
            JSONObject vod = new JSONObject();
            try {
                vod.put("vod_id", fullId);
                vod.put("vod_name", title);
                vod.put("vod_pic", pic);
                vod.put("vod_remarks", remarks);
                videos.put(vod);
            } catch (Exception e) {
                SpiderDebug.log("[骚火电影] 解析列表项失败: " + e.getMessage());
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
            
            // 分类配置
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
                
                // 国产剧/电视剧筛选器
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
                
                // 电影筛选器
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
                
                // 动漫筛选器
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
            
            String html = retryGet(url, 3);
            if (html == null) {
                return "{\"list\":[], \"page\":" + pg + "}";
            }
            
            Document doc = Jsoup.parse(html);
            JSONArray videoList = parseList(doc);
            
            JSONObject result = new JSONObject();
            result.put("list", videoList);
            result.put("page", Integer.parseInt(pg));
            result.put("pagecount", 999);
            result.put("limit", 20);
            result.put("total", 9999);
            return result.toString();
            
        } catch (Exception e) {
            SpiderDebug.log("[骚火电影] categoryContent error: " + e.getMessage());
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
            
            String html = retryGet(url, 3);
            if (html == null) {
                return "{\"list\":[]}";
            }
            
            Document doc = Jsoup.parse(html);
            
            // 提取信息
            Element vInfoBox = doc.selectFirst(".v_info_box");
            if (vInfoBox == null) {
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
            
            // 标题
            Element titleElem = vInfoBox.selectFirst("h1.v_title a");
            String title = titleElem != null ? titleElem.text() : "";
            
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
            StringBuilder playFrom = new StringBuilder();
            for (Element li : doc.select(".play_from ul li")) {
                if (playFrom.length() > 0) playFrom.append("$$$");
                playFrom.append(li.text());
            }
            
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
                currentLineLinks.sort((x, y) -> naturalCompare(x.get("name"), y.get("name")));
                
                List<String> formatted = new ArrayList<>();
                for (Map<String, String> item : currentLineLinks) {
                    formatted.add(item.get("name") + "$" + item.get("url"));
                }
                playUrlGroups.add(String.join("#", formatted));
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

    // ================== 搜索 ==================

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContentPg(key, quick, "1");
    }
    
    public String searchContentPg(String key, boolean quick, String pg) {
        try {
            String searchUrl = host + "/s----------.html?wd=" + URLEncoder.encode(key, "UTF-8");
            SpiderDebug.log("[骚火电影] search URL: " + searchUrl);
            
            String html = retryGet(searchUrl, 3);
            if (html == null) {
                return "{\"list\":[]}";
            }
            
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
        SpiderDebug.log("[骚火电影] ========== 开始播放解析 ==========");
        SpiderDebug.log("[骚火电影] 传入参数: flag=" + flag + ", id=" + id);
        
        try {
            // 1. 请求播放页
            SpiderDebug.log("[骚火电影] 步骤1: 请求播放页 " + id);
            String html = retryGet(id, headers, 3);
            if (html == null) {
                SpiderDebug.log("[骚火电影] 播放页请求失败");
                return "{\"parse\":1,\"url\":\"" + id + "\"}";
            }
            
            // 提取 iframe
            Pattern iframePattern = Pattern.compile("iframe src=\"(.*?)\"");
            Matcher iframeMatcher = iframePattern.matcher(html);
            if (!iframeMatcher.find()) {
                SpiderDebug.log("[骚火电影] 未找到 iframe 标签，直接返回原链接");
                return "{\"parse\":1,\"url\":\"" + id + "\"}";
            }
            
            String jxUrl = iframeMatcher.group(1);
            if (!jxUrl.startsWith("http")) {
                jxUrl = host + jxUrl;
            }
            SpiderDebug.log("[骚火电影] 获取到 iframe 解析地址: " + jxUrl);
            
            // 2. 请求 iframe 页
            SpiderDebug.log("[骚火电影] 步骤2: 请求 iframe 页 " + jxUrl);
            Map<String, String> jxHeaders = new HashMap<>(headers);
            jxHeaders.put("Referer", host + "/");
            
            String jxHtml = retryGet(jxUrl, jxHeaders, 3);
            if (jxHtml == null) {
                SpiderDebug.log("[骚火电影] iframe 页请求失败");
                return "{\"parse\":1,\"url\":\"" + id + "\"}";
            }
            SpiderDebug.log("[骚火电影] iframe 页长度: " + jxHtml.length() + " 字符");
            
            // 3. 提取参数
            SpiderDebug.log("[骚火电影] 步骤3: 提取加密参数");
            
            Pattern urlPattern = Pattern.compile("var url = \"(.*?)\";");
            Pattern tPattern = Pattern.compile("var t = \"(.*?)\";");
            Pattern keyPattern = Pattern.compile("var key = OKOK\\(\"(.*?)\"\\);");
            Pattern eePattern = Pattern.compile("const ee = (\\{.*?\\}) ;");
            
            Matcher urlMatcher = urlPattern.matcher(jxHtml);
            Matcher tMatcher = tPattern.matcher(jxHtml);
            Matcher keyMatcher = keyPattern.matcher(jxHtml);
            Matcher eeMatcher = eePattern.matcher(jxHtml);
            
            if (!urlMatcher.find() || !tMatcher.find() || !keyMatcher.find()) {
                SpiderDebug.log("[骚火电影] 正则匹配失败");
                return "{\"parse\":1,\"url\":\"" + id + "\"}";
            }
            if (!eeMatcher.find()) {
                SpiderDebug.log("[骚火电影] 未找到 ee 字典，无法解密");
                return "{\"parse\":1,\"url\":\"" + id + "\"}";
            }
            
            String urlVal = urlMatcher.group(1);
            String tVal = tMatcher.group(1);
            String encodedKey = keyMatcher.group(1);
            String eeStr = eeMatcher.group(1);
            
            SpiderDebug.log("[骚火电影] 提取参数: url=" + urlVal + ", t=" + tVal);
            
            // 解析 ee 字典
            JSONObject eeJson = new JSONObject(eeStr);
            Map<String, String> eeDict = new HashMap<>();
            for (String key : eeJson.keySet()) {
                eeDict.put(key, eeJson.getString(key));
            }
            
            // 4. 解密 key
            String realKey = decodeKey(encodedKey, eeDict);
            if (realKey.isEmpty()) {
                SpiderDebug.log("[骚火电影] 解密失败");
                return "{\"parse\":1,\"url\":\"" + id + "\"}";
            }
            SpiderDebug.log("[骚火电影] 解密后的 key: " + realKey);
            
            // 5. POST 到解析 API
            SpiderDebug.log("[骚火电影] 步骤4: 请求解析 API");
            String apiUrl = "https://hhjx.hhplayer.com/api.php";
            Map<String, String> payload = new HashMap<>();
            payload.put("url", urlVal);
            payload.put("t", tVal);
            payload.put("key", realKey);
            payload.put("act", "0");
            payload.put("play", "1");
            
            Map<String, String> apiHeaders = new HashMap<>(headers);
            apiHeaders.put("Origin", "https://hhjx.hhplayer.com");
            apiHeaders.put("Referer", jxUrl);
            apiHeaders.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            apiHeaders.put("X-Requested-With", "XMLHttpRequest");
            
            String apiResponse = retryPost(apiUrl, payload, apiHeaders, 3);
            if (apiResponse == null) {
                SpiderDebug.log("[骚火电影] API 请求失败");
                return "{\"parse\":1,\"url\":\"" + id + "\"}";
            }
            
            JSONObject finalData = new JSONObject(apiResponse);
            SpiderDebug.log("[骚火电影] API 响应: " + finalData.toString());
            
            if (finalData.optInt("code") == 200) {
                String videoUrl = finalData.optString("url");
                if (videoUrl != null && !videoUrl.isEmpty() && !videoUrl.startsWith("http")) {
                    videoUrl = "https://hhjx.hhplayer.com" + videoUrl;
                }
                SpiderDebug.log("[骚火电影] 解析成功，获得播放地址: " + videoUrl);
                
                JSONObject result = new JSONObject();
                result.put("parse", 0);
                result.put("url", videoUrl);
                JSONObject header = new JSONObject();
                header.put("User-Agent", headers.get("User-Agent"));
                header.put("Origin", "https://hhjx.hhplayer.com");
                result.put("header", header);
                return result.toString();
            } else {
                SpiderDebug.log("[骚火电影] API 返回 code 非 200: " + finalData.optInt("code"));
                return "{\"parse\":1,\"url\":\"" + id + "\"}";
            }
            
        } catch (Exception e) {
            SpiderDebug.log("[骚火电影] 播放解析异常: " + e.getMessage());
            e.printStackTrace();
            return "{\"parse\":1,\"url\":\"" + id + "\"}";
        }
    }
}
