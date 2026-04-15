package com.spider.bttwo;

import okhttp3.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BTTwoSpider {
    private String host = "https://www.bttwo.org";
    private final String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private final OkHttpClient client;
    private boolean domainInited = false;

    public BTTwoSpider() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
        init("");
    }

    private void updateHeadersReferer(Request.Builder builder) {
        if (host != null && !host.isEmpty()) {
            builder.header("Referer", host + "/");
        }
    }

    public String getName() {
        return "两个BT[修复版]";
    }

    public void init(String extend) {
        if (domainInited) return;
        
        System.out.println("[INIT] 开始初始化，当前域名: " + host);
        
        try {
            Request request = new Request.Builder()
                    .url(host)
                    .header("User-Agent", ua)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Cookie", "myannoun=1")
                    .build();
            
            try (Response response = client.newCall(request).execute()) {
                if (response.code() == 200) {
                    System.out.println("[INIT] 当前域名 " + host + " 可用");
                    domainInited = true;
                    return;
                }
            }
        } catch (IOException e) {
            System.out.println("[INIT] 当前域名不可用: " + e.getMessage());
        }

        String publishUrl = "https://www.bttwo.vip/";
        System.out.println("[INIT] 尝试从发布页 " + publishUrl + " 获取最新域名");
        
        try {
            Request request = new Request.Builder()
                    .url(publishUrl)
                    .header("User-Agent", ua)
                    .build();
            
            try (Response response = client.newCall(request).execute()) {
                if (response.code() == 200) {
                    String body = response.body().string();
                    Pattern pattern = Pattern.compile("href=\"(https?://www\\.bttwo\\.[a-z]+)\"");
                    Matcher matcher = pattern.matcher(body);
                    if (matcher.find()) {
                        String newHost = matcher.group(1).replaceAll("/$", "");
                        System.out.println("[INIT] 从发布页获取到新域名: " + newHost);
                        this.host = newHost;
                        domainInited = true;
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("[INIT] 获取发布页失败: " + e.getMessage());
        }
    }

    public Map<String, Object> homeContent(Map<String, Object> filter) {
        List<Map<String, String>> classes = new ArrayList<>();
        
        Map<String, String> class1 = new HashMap<>();
        class1.put("type_name", "国产剧");
        class1.put("type_id", "zgjun");
        classes.add(class1);
        
        Map<String, String> class2 = new HashMap<>();
        class2.put("type_name", "电影");
        class2.put("type_id", "new-movie");
        classes.add(class2);
        
        Map<String, String> class3 = new HashMap<>();
        class3.put("type_name", "美剧");
        class3.put("type_id", "meiju");
        classes.add(class3);
        
        Map<String, String> class4 = new HashMap<>();
        class4.put("type_name", "日韩剧");
        class4.put("type_id", "jpsrtv");
        classes.add(class4);
        
        Map<String, Object> result = new HashMap<>();
        result.put("class", classes);
        return result;
    }

    public Map<String, Object> categoryContent(String tid, int pg, Map<String, Object> filter, String extend) {
        if (!domainInited) init("");
        
        String url;
        if (pg == 1) {
            url = host + "/" + tid;
        } else {
            url = host + "/" + tid + "/page/" + pg;
        }
        
        System.out.println("[CATEGORY] 分类ID: " + tid + ", 页码: " + pg);
        System.out.println("[CATEGORY] 完整URL: " + url);
        
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", ua)
                    .header("Referer", host + "/")
                    .build();
            
            try (Response response = client.newCall(request).execute()) {
                if (response.code() != 200) {
                    System.out.println("[CATEGORY] 请求失败，状态码 " + response.code());
                    Map<String, Object> result = new HashMap<>();
                    result.put("list", new ArrayList<>());
                    result.put("page", pg);
                    result.put("pagecount", 0);
                    return result;
                }
                
                String html = response.body().string();
                Document doc = Jsoup.parse(html);
                
                Elements items = doc.select("div.bt_img ul li");
                if (items.isEmpty()) items = doc.select("ul.movie_list li");
                if (items.isEmpty()) items = doc.select(".list_box li");
                if (items.isEmpty()) items = doc.select("div.item");
                
                System.out.println("[CATEGORY] 共找到 " + items.size() + " 个条目");
                
                List<Map<String, String>> videoList = new ArrayList<>();
                for (Element li : items) {
                    Element aTag = li.selectFirst("a");
                    if (aTag == null) continue;
                    
                    String href = aTag.attr("href");
                    String fullUrl = host + (href.startsWith("/") ? href : "/" + href);
                    
                    Element imgTag = aTag.selectFirst("img");
                    String picUrl = "";
                    if (imgTag != null) {
                        picUrl = imgTag.attr("data-original");
                        if (picUrl.isEmpty()) picUrl = imgTag.attr("src");
                    }
                    
                    Element remarkTag = li.selectFirst(".jidi span");
                    if (remarkTag == null) remarkTag = li.selectFirst(".remarks");
                    String remark = remarkTag != null ? remarkTag.text().trim() : "";
                    
                    String name = "";
                    if (imgTag != null) name = imgTag.attr("alt");
                    if (name.isEmpty()) name = aTag.text().trim();
                    if (name.isEmpty()) name = "未知影片";
                    
                    Map<String, String> video = new HashMap<>();
                    video.put("vod_id", fullUrl);
                    video.put("vod_name", name);
                    video.put("vod_pic", picUrl);
                    video.put("vod_remarks", remark);
                    videoList.add(video);
                }
                
                Map<String, Object> result = new HashMap<>();
                result.put("list", videoList);
                result.put("page", pg);
                result.put("pagecount", 99);
                return result;
            }
        } catch (IOException e) {
            System.out.println("[CATEGORY] 异常: " + e.getMessage());
            e.printStackTrace();
            Map<String, Object> result = new HashMap<>();
            result.put("list", new ArrayList<>());
            result.put("page", pg);
            result.put("pagecount", 0);
            return result;
        }
    }

    public Map<String, Object> detailContent(List<String> ids) {
        String url = ids != null && !ids.isEmpty() ? ids.get(0) : "";
        if (url.isEmpty()) {
            System.out.println("[DETAIL] 未收到有效 ids");
            Map<String, Object> result = new HashMap<>();
            result.put("list", new ArrayList<>());
            return result;
        }
        
        if (!domainInited) init("");
        System.out.println("[DETAIL] 请求详情页: " + url);
        
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", ua)
                    .header("Referer", host + "/")
                    .build();
            
            try (Response response = client.newCall(request).execute()) {
                if (response.code() != 200) {
                    System.out.println("[DETAIL] 详情页请求失败: " + response.code());
                    Map<String, Object> result = new HashMap<>();
                    result.put("list", new ArrayList<>());
                    return result;
                }
                
                String html = response.body().string();
                Document doc = Jsoup.parse(html);
                
                Element titleTag = doc.selectFirst("h1");
                String vodName = titleTag != null ? titleTag.text().trim() : "";
                
                Element picTag = doc.selectFirst(".dyimg img");
                String vodPic = picTag != null ? picTag.attr("src") : "";
                
                Element descTag = doc.selectFirst(".yp_context");
                String vodContent = descTag != null ? descTag.text().trim() : "";
                
                Map<String, String> vod = new HashMap<>();
                vod.put("vod_id", url);
                vod.put("vod_name", vodName);
                vod.put("vod_pic", vodPic);
                vod.put("vod_content", vodContent);
                
                Elements infoItems = doc.select(".moviedteail_list li");
                for (Element li : infoItems) {
                    String text = li.text().trim();
                    if (text.contains("类型：")) {
                        vod.put("type_name", text.replace("类型：", "").trim());
                    } else if (text.contains("地区：")) {
                        vod.put("vod_area", text.replace("地区：", "").trim());
                    } else if (text.contains("年份：")) {
                        vod.put("vod_year", text.replace("年份：", "").trim());
                    } else if (text.contains("导演：")) {
                        vod.put("vod_director", text.replace("导演：", "").trim());
                    } else if (text.contains("主演：")) {
                        vod.put("vod_actor", text.replace("主演：", "").trim());
                    } else if (text.contains("语言：")) {
                        vod.put("vod_language", text.replace("语言：", "").trim());
                    }
                }
                
                String typeName = vod.getOrDefault("type_name", "");
                if (typeName.contains("情色")) {
                    vod.put("vod_play_from", "温馨提示");
                    vod.put("vod_play_url", "内容敏感，暂不提供播放$#");
                } else {
                    List<String> playLinks = new ArrayList<>();
                    Elements playBtns = doc.select(".paly_list_btn a");
                    if (playBtns.isEmpty()) playBtns = doc.select(".downurl a");
                    
                    for (Element a : playBtns) {
                        String name = a.text().trim();
                        String href = a.attr("href");
                        String fullUrl = host + (href.startsWith("/") ? href : "/" + href);
                        playLinks.add(name + "$" + fullUrl);
                    }
                    
                    if (!playLinks.isEmpty()) {
                        vod.put("vod_play_from", "两个BT");
                        vod.put("vod_play_url", String.join("#", playLinks));
                    } else {
                        vod.put("vod_play_from", "暂无资源");
                        vod.put("vod_play_url", "");
                    }
                }
                
                System.out.println("[DETAIL] 详情页解析成功: " + vodName);
                List<Map<String, String>> list = new ArrayList<>();
                list.add(vod);
                Map<String, Object> result = new HashMap<>();
                result.put("list", list);
                return result;
            }
        } catch (IOException e) {
            System.out.println("[DETAIL] 异常: " + e.getMessage());
            e.printStackTrace();
            Map<String, Object> result = new HashMap<>();
            result.put("list", new ArrayList<>());
            return result;
        }
    }

    public Map<String, Object> searchContent(String key, boolean quick, String pg) {
        if (!domainInited) init("");
        
        try {
            String searchUrl = host + "/xsssearch?q=" + URLEncoder.encode(key, "UTF-8");
            System.out.println("[SEARCH] 搜索关键词: " + key + ", URL: " + searchUrl);
            
            Request request = new Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", ua)
                    .header("Referer", host + "/xsssearch")
                    .build();
            
            try (Response response = client.newCall(request).execute()) {
                if (response.code() != 200) {
                    System.out.println("[SEARCH] 搜索请求失败: " + response.code());
                    Map<String, Object> result = new HashMap<>();
                    result.put("list", new ArrayList<>());
                    return result;
                }
                
                String html = response.body().string();
                Document doc = Jsoup.parse(html);
                Elements items = doc.select("ul li");
                
                List<Map<String, String>> videoList = new ArrayList<>();
                for (Element li : items) {
                    Element a = li.selectFirst("h3.dytit a");
                    if (a == null) a = li.selectFirst("a");
                    if (a != null && a.attr("href").contains("/movie/")) {
                        String href = a.attr("href");
                        String fullUrl = host + (href.startsWith("/") ? href : "/" + href);
                        String name = a.text().trim();
                        
                        Element img = li.selectFirst("img");
                        String picUrl = "";
                        if (img != null) {
                            picUrl = img.attr("data-original");
                            if (picUrl.isEmpty()) picUrl = img.attr("src");
                        }
                        
                        Element remark = li.selectFirst(".jidi span");
                        String remarkText = remark != null ? remark.text().trim() : "";
                        
                        Map<String, String> video = new HashMap<>();
                        video.put("vod_id", fullUrl);
                        video.put("vod_name", name);
                        video.put("vod_pic", picUrl);
                        video.put("vod_remarks", remarkText);
                        videoList.add(video);
                        break; // 只取第一个结果
                    }
                }
                
                System.out.println("[SEARCH] 搜索 '" + key + "' 得到 " + videoList.size() + " 个结果");
                Map<String, Object> result = new HashMap<>();
                result.put("list", videoList);
                return result;
            }
        } catch (IOException e) {
            System.out.println("[SEARCH] 异常: " + e.getMessage());
            e.printStackTrace();
            Map<String, Object> result = new HashMap<>();
            result.put("list", new ArrayList<>());
            return result;
        }
    }

    public Map<String, Object> playerContent(String flag, String id, boolean vip) {
        System.out.println("[PLAYER] 请求播放: " + id);
        
        Map<String, String> playHeaders = new HashMap<>();
        playHeaders.put("User-Agent", ua);
        playHeaders.put("Origin", host.replaceAll("/$", ""));
        playHeaders.put("Referer", "");
        
        try {
            Request request = new Request.Builder()
                    .url(id)
                    .header("User-Agent", ua)
                    .header("Referer", host + "/")
                    .build();
            
            try (Response response = client.newCall(request).execute()) {
                if (response.code() != 200) {
                    System.out.println("[PLAYER] 播放页请求失败: " + response.code());
                    Map<String, Object> result = new HashMap<>();
                    result.put("parse", 1);
                    result.put("url", id);
                    result.put("header", playHeaders);
                    return result;
                }
                
                String html = response.body().string();
                
                String[] patterns = {
                    "fetch\\s*\\(\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']",
                    "url\\s*:\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']",
                    "video\\s*:\\s*\\{\\s*url\\s*:\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']",
                    "loadSource\\s*\\(\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']",
                    "src\\s*:\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']",
                    "(https?://[^\\s\"']+\\.m3u8[^\\s\"']*)"
                };
                
                for (String patternStr : patterns) {
                    Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
                    Matcher matcher = pattern.matcher(html);
                    if (matcher.find()) {
                        String m3u8Url = matcher.group(1);
                        if (m3u8Url.startsWith("/")) {
                            m3u8Url = host + m3u8Url;
                        }
                        m3u8Url = m3u8Url.replace("\\/", "/");
                        System.out.println("[PLAYER] 提取到m3u8地址: " + m3u8Url);
                        
                        Map<String, Object> result = new HashMap<>();
                        result.put("parse", 0);
                        result.put("url", m3u8Url);
                        result.put("header", playHeaders);
                        return result;
                    }
                }
                
                System.out.println("[PLAYER] 未找到m3u8地址，返回原始链接");
                Map<String, Object> result = new HashMap<>();
                result.put("parse", 1);
                result.put("url", id);
                result.put("header", playHeaders);
                return result;
            }
        } catch (IOException e) {
            System.out.println("[PLAYER] 异常: " + e.getMessage());
            e.printStackTrace();
            Map<String, Object> result = new HashMap<>();
            result.put("parse", 1);
            result.put("url", id);
            result.put("header", playHeaders);
            return result;
        }
    }
}
