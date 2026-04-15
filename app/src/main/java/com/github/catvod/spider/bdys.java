package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class bttw extends Spider {

    private String host = "https://www.bttwo.org";
    private static final String USER_AGENT = Util.CHROME;

    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Cookie", "myannoun=1");
        return headers;
    }

    private HashMap<String, String> getHeadersWithReferer(String referer) {
        HashMap<String, String> headers = getHeaders();
        headers.put("Referer", referer);
        return headers;
    }

    @Override
    public String getName() {
        return "两个BT";
    }

    @Override
    public void init(String extend) {
        // 可选：域名初始化逻辑
    }

    @Override
    public boolean isVideoCast() {
        return true;
    }

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("zgjun", "国产剧"));
        classes.add(new Class("new-movie", "电影"));
        classes.add(new Class("meiju", "美剧"));
        classes.add(new Class("jpsrtv", "日韩剧"));
        
        // 根据bdys.java的写法，homeContent返回Result.string(classes, new ArrayList<>())
        return Result.string(classes, new ArrayList<>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        int page = Integer.parseInt(pg);
        String url;
        if (page == 1) {
            url = host + "/" + tid;
        } else {
            url = host + "/" + tid + "/page/" + page;
        }
        
        System.out.println("[CAT] 请求URL: " + url);
        
        try {
            String html = OkHttp.string(url, getHeadersWithReferer(host + "/"));
            Document doc = Jsoup.parse(html);
            
            List<Vod> list = new ArrayList<>();
            Elements items = doc.select("div.bt_img ul li");
            if (items.isEmpty()) items = doc.select("ul.movie_list li");
            if (items.isEmpty()) items = doc.select(".list_box li");
            if (items.isEmpty()) items = doc.select("div.item");
            
            for (Element li : items) {
                Element aTag = li.selectFirst("a");
                if (aTag == null) continue;
                
                String href = aTag.attr("href");
                String vodId = href.startsWith("http") ? href : host + href;
                
                Element imgTag = aTag.selectFirst("img");
                String pic = "";
                if (imgTag != null) {
                    pic = imgTag.attr("data-original");
                    if (pic.isEmpty()) pic = imgTag.attr("src");
                }
                
                Element remarkTag = li.selectFirst(".jidi span");
                if (remarkTag == null) remarkTag = li.selectFirst(".remarks");
                String remark = remarkTag != null ? remarkTag.text().trim() : "";
                
                String name = "";
                if (imgTag != null) name = imgTag.attr("alt");
                if (name.isEmpty()) name = aTag.text().trim();
                if (name.isEmpty()) name = "未知影片";
                
                list.add(new Vod(vodId, name, pic, remark));
            }
            
            System.out.println("[CAT] 找到 " + list.size() + " 个视频");
            // 使用bdys.java中的格式：Result.string(page, list.size(), Integer.MAX_VALUE, list)
            return Result.string(page, list.size(), Integer.MAX_VALUE, list);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.string(page, 0, 0, new ArrayList<>());
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        String url = ids.get(0);
        if (url == null || url.isEmpty()) return Result.string(new ArrayList<>());
        
        System.out.println("[DETAIL] 请求URL: " + url);
        
        try {
            String html = OkHttp.string(url, getHeadersWithReferer(host + "/"));
            Document doc = Jsoup.parse(html);
            
            Element titleTag = doc.selectFirst("h1");
            String name = titleTag != null ? titleTag.text().trim() : "";
            
            Element picTag = doc.selectFirst(".dyimg img");
            String pic = picTag != null ? picTag.attr("src") : "";
            
            Element descTag = doc.selectFirst(".yp_context");
            String content = descTag != null ? descTag.text().trim() : "";
            
            Vod vod = new Vod();
            vod.setVodId(url);
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodContent(content);
            
            // 提取详细信息
            Elements infoItems = doc.select(".moviedteail_list li");
            for (Element li : infoItems) {
                String text = li.text().trim();
                if (text.contains("类型：")) {
                    vod.setTypeName(text.replace("类型：", "").trim());
                } else if (text.contains("地区：")) {
                    vod.setVodArea(text.replace("地区：", "").trim());
                } else if (text.contains("年份：")) {
                    vod.setVodYear(text.replace("年份：", "").trim());
                } else if (text.contains("导演：")) {
                    vod.setVodDirector(text.replace("导演：", "").trim());
                } else if (text.contains("主演：")) {
                    vod.setVodActor(text.replace("主演：", "").trim());
                }
            }
            
            // 处理播放链接
            List<String> playLinks = new ArrayList<>();
            Elements playBtns = doc.select(".paly_list_btn a");
            if (playBtns.isEmpty()) playBtns = doc.select(".downurl a");
            
            for (Element a : playBtns) {
                String playName = a.text().trim();
                String href = a.attr("href");
                String fullUrl = href.startsWith("http") ? href : host + href;
                playLinks.add(playName + "$" + fullUrl);
            }
            
            if (!playLinks.isEmpty()) {
                vod.setVodPlayFrom("两个BT");
                vod.setVodPlayUrl(String.join("#", playLinks));
            } else {
                vod.setVodPlayFrom("暂无资源");
                vod.setVodPlayUrl("");
            }
            
            System.out.println("[DETAIL] 提取成功: " + name);
            // 使用bdys.java中的格式：Result.string(vod)
            return Result.string(vod);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String searchUrl = host + "/xsssearch?q=" + URLEncoder.encode(key, "UTF-8");
            System.out.println("[SEARCH] 搜索关键词: " + key + ", URL: " + searchUrl);
            
            String html = OkHttp.string(searchUrl, getHeadersWithReferer(host + "/xsssearch"));
            Document doc = Jsoup.parse(html);
            
            List<Vod> list = new ArrayList<>();
            Elements items = doc.select("ul li");
            
            for (Element li : items) {
                Element a = li.selectFirst("h3.dytit a");
                if (a == null) a = li.selectFirst("a");
                if (a != null && a.attr("href").contains("/movie/")) {
                    String href = a.attr("href");
                    String vodId = href.startsWith("http") ? href : host + href;
                    String name = a.text().trim();
                    
                    Element img = li.selectFirst("img");
                    String pic = "";
                    if (img != null) {
                        pic = img.attr("data-original");
                        if (pic.isEmpty()) pic = img.attr("src");
                    }
                    
                    Element remark = li.selectFirst(".jidi span");
                    String remarkText = remark != null ? remark.text().trim() : "";
                    
                    list.add(new Vod(vodId, name, pic, remarkText));
                    break; // 只取第一个结果
                }
            }
            
            System.out.println("[SEARCH] 搜索 '" + key + "' 得到 " + list.size() + " 个结果");
            return Result.string(list);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Origin", host.replaceAll("/$", ""));
        headers.put("Referer", "");
        
        try {
            String html = OkHttp.string(id, getHeadersWithReferer(host + "/"));
            
            if (html != null) {
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
                        // 使用bdys.java中的格式：Result.get().url(url).header(headers).string()
                        return Result.get().url(m3u8Url).header(headers).string();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        System.out.println("[PLAYER] 未找到m3u8地址，返回原始链接");
        return Result.get().url(id).header(headers).string();
    }
}
