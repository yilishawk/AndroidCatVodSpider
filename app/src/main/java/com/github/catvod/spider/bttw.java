package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.catvod.utils.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.Response;

public class bttw extends Spider {

    private String host = "https://www.bttwo.org";
    private String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private boolean domainInited = false;

    @Override
    public void init(String extend) {
        if (domainInited) return;

        try {
            Request request = new Request.Builder()
                    .url(host)
                    .header("User-Agent", ua)
                    .build();
            Response response = OkHttp.client().newCall(request).execute();
            if (response.isSuccessful()) {
                domainInited = true;
            }
            response.close();
        } catch (Exception e) {
            // 尝试获取新域名
            try {
                Request pubRequest = new Request.Builder()
                        .url("https://www.bttwo.vip/")
                        .header("User-Agent", ua)
                        .build();
                Response pubResponse = OkHttp.client().newCall(pubRequest).execute();
                if (pubResponse.isSuccessful()) {
                    String html = pubResponse.body().string();
                    Pattern pattern = Pattern.compile("href=\"(https?://www\\.bttwo\\.[a-z]+)\"");
                    Matcher matcher = pattern.matcher(html);
                    if (matcher.find()) {
                        host = matcher.group(1).replaceAll("/$", "");
                        domainInited = true;
                    }
                }
                pubResponse.close();
            } catch (Exception ex) {
                // ignore
            }
        }
    }

    @Override
    public String getName() {
        return "两个BT[修复版]";
    }

    @Override
    public JSONObject homeContent(Map<String, String> filter) {
        JSONObject result = new JSONObject();
        JSONArray classes = new JSONArray();
        
        String[][] classData = {
            {"国产剧", "zgjun"},
            {"电影", "new-movie"},
            {"美剧", "meiju"},
            {"日韩剧", "jpsrtv"}
        };
        
        for (String[] data : classData) {
            JSONObject clazz = new JSONObject();
            clazz.put("type_name", data[0]);
            clazz.put("type_id", data[1]);
            classes.put(clazz);
        }
        
        result.put("class", classes);
        return result;
    }

    @Override
    public JSONObject categoryContent(String tid, String pg, Map<String, String> filter, Map<String, String> extend) {
        if (!domainInited) init("");
        
        int page = Integer.parseInt(pg);
        String url;
        if (page == 1) {
            url = host + "/" + tid;
        } else {
            url = host + "/" + tid + "/page/" + page;
        }
        
        JSONObject result = new JSONObject();
        JSONArray list = new JSONArray();
        
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", ua)
                    .header("Referer", host + "/")
                    .build();
            Response response = OkHttp.client().newCall(request).execute();
            
            if (response.isSuccessful()) {
                String html = response.body().string();
                Document doc = Jsoup.parse(html);
                
                Elements items = doc.select("div.bt_img ul li");
                if (items.isEmpty()) items = doc.select("ul.movie_list li");
                if (items.isEmpty()) items = doc.select(".list_box li");
                if (items.isEmpty()) items = doc.select("div.item");
                
                for (Element li : items) {
                    Element aTag = li.selectFirst("a");
                    if (aTag == null) continue;
                    
                    String href = aTag.attr("href");
                    String fullUrl = href.startsWith("http") ? href : host + href;
                    
                    Element imgTag = aTag.selectFirst("img");
                    String picUrl = "";
                    if (imgTag != null) {
                        picUrl = imgTag.attr("data-original");
                        if (TextUtils.isEmpty(picUrl)) picUrl = imgTag.attr("src");
                    }
                    
                    Element remarkTag = li.selectFirst(".jidi span");
                    if (remarkTag == null) remarkTag = li.selectFirst(".remarks");
                    String remark = remarkTag != null ? remarkTag.text().trim() : "";
                    
                    String name = "";
                    if (imgTag != null) name = imgTag.attr("alt");
                    if (TextUtils.isEmpty(name)) name = aTag.text().trim();
                    if (TextUtils.isEmpty(name)) name = "未知影片";
                    
                    JSONObject video = new JSONObject();
                    video.put("vod_id", fullUrl);
                    video.put("vod_name", name);
                    video.put("vod_pic", picUrl);
                    video.put("vod_remarks", remark);
                    list.put(video);
                }
            }
            response.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        result.put("list", list);
        result.put("page", page);
        result.put("pagecount", 99);
        return result;
    }

    @Override
    public JSONObject detailContent(List<String> ids) {
        String url = ids.get(0);
        if (TextUtils.isEmpty(url)) return new JSONObject();
        if (!domainInited) init("");
        
        JSONObject result = new JSONObject();
        JSONArray list = new JSONArray();
        JSONObject vod = new JSONObject();
        
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", ua)
                    .header("Referer", host + "/")
                    .build();
            Response response = OkHttp.client().newCall(request).execute();
            
            if (response.isSuccessful()) {
                String html = response.body().string();
                Document doc = Jsoup.parse(html);
                
                Element titleTag = doc.selectFirst("h1");
                String vodName = titleTag != null ? titleTag.text().trim() : "";
                
                Element picTag = doc.selectFirst(".dyimg img");
                String vodPic = picTag != null ? picTag.attr("src") : "";
                
                Element descTag = doc.selectFirst(".yp_context");
                String vodContent = descTag != null ? descTag.text().trim() : "";
                
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
                
                // 处理播放链接
                List<String> playLinks = new ArrayList<>();
                Elements playBtns = doc.select(".paly_list_btn a");
                if (playBtns.isEmpty()) playBtns = doc.select(".downurl a");
                
                for (Element a : playBtns) {
                    String name = a.text().trim();
                    String href = a.attr("href");
                    String fullUrl = href.startsWith("http") ? href : host + href;
                    playLinks.add(name + "$" + fullUrl);
                }
                
                if (!playLinks.isEmpty()) {
                    vod.put("vod_play_from", "两个BT");
                    vod.put("vod_play_url", TextUtils.join("#", playLinks));
                } else {
                    vod.put("vod_play_from", "暂无资源");
                    vod.put("vod_play_url", "");
                }
            }
            response.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        list.put(vod);
        result.put("list", list);
        return result;
    }

    @Override
    public JSONObject searchContent(String key, String pg) {
        if (!domainInited) init("");
        
        JSONObject result = new JSONObject();
        JSONArray list = new JSONArray();
        
        try {
            String searchUrl = host + "/xsssearch?q=" + URLEncoder.encode(key, "UTF-8");
            Request request = new Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", ua)
                    .header("Referer", host + "/xsssearch")
                    .build();
            Response response = OkHttp.client().newCall(request).execute();
            
            if (response.isSuccessful()) {
                String html = response.body().string();
                Document doc = Jsoup.parse(html);
                Elements items = doc.select("ul li");
                
                for (Element li : items) {
                    Element a = li.selectFirst("h3.dytit a");
                    if (a == null) a = li.selectFirst("a");
                    if (a != null && a.attr("href").contains("/movie/")) {
                        String href = a.attr("href");
                        String fullUrl = href.startsWith("http") ? href : host + href;
                        String name = a.text().trim();
                        
                        Element img = li.selectFirst("img");
                        String picUrl = "";
                        if (img != null) {
                            picUrl = img.attr("data-original");
                            if (TextUtils.isEmpty(picUrl)) picUrl = img.attr("src");
                        }
                        
                        Element remark = li.selectFirst(".jidi span");
                        String remarkText = remark != null ? remark.text().trim() : "";
                        
                        JSONObject video = new JSONObject();
                        video.put("vod_id", fullUrl);
                        video.put("vod_name", name);
                        video.put("vod_pic", picUrl);
                        video.put("vod_remarks", remarkText);
                        list.put(video);
                        break; // 只取第一个
                    }
                }
            }
            response.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        result.put("list", list);
        return result;
    }

    @Override
    public JSONObject playerContent(String flag, String id, List<String> vipFlags) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", ua);
        headers.put("Origin", host.replaceAll("/$", ""));
        headers.put("Referer", "");
        
        JSONObject result = new JSONObject();
        
        try {
            Request request = new Request.Builder()
                    .url(id)
                    .header("User-Agent", ua)
                    .header("Referer", host + "/")
                    .build();
            Response response = OkHttp.client().newCall(request).execute();
            
            if (response.isSuccessful()) {
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
                        
                        result.put("parse", 0);
                        result.put("url", m3u8Url);
                        JSONObject headerObj = new JSONObject(headers);
                        result.put("header", headerObj);
                        return result;
                    }
                }
            }
            response.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        result.put("parse", 1);
        result.put("url", id);
        JSONObject headerObj = new JSONObject(headers);
        result.put("header", headerObj);
        return result;
    }

    @Override
    public boolean isVideoCast() {
        return true;
    }
}
