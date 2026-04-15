package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;

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

    // 如果构建报错此处 method does not override，请保持去掉 @Override
    public String getName() {
        return "两个BT";
    }

    @Override
    public void init(String extend) {
        super.init(extend);
    }

    // 如果构建报错此处 method does not override，请去掉 @Override
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
        return Result.string(classes, new ArrayList<Vod>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        int page = Integer.parseInt(pg);
        String url = page == 1 ? host + "/" + tid : host + "/" + tid + "/page/" + page;
        
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
                
                String name = (imgTag != null && !imgTag.attr("alt").isEmpty()) ? imgTag.attr("alt") : aTag.text().trim();
                if (name.isEmpty()) name = "未知影片";
                
                list.add(new Vod(vodId, name, pic, remark));
            }
            
            // --- 核心修复：必须传入 5 个参数 ---
            // 参数：当前页, 总页数, 每页限制, 总条数, 列表
            return Result.string(page, page + 1, list.size(), 1000, list);
        } catch (Exception e) {
            // 失败时也必须返回 5 个参数的空结果
            return Result.string(page, 0, 0, 0, new ArrayList<Vod>());
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        String url = ids.get(0);
        try {
            String html = OkHttp.string(url, getHeadersWithReferer(host + "/"));
            Document doc = Jsoup.parse(html);
            
            Vod vod = new Vod();
            vod.setVodId(url);
            vod.setVodName(doc.selectFirst("h1") != null ? doc.selectFirst("h1").text().trim() : "");
            vod.setVodPic(doc.selectFirst(".dyimg img") != null ? doc.selectFirst(".dyimg img").attr("src") : "");
            vod.setVodContent(doc.selectFirst(".yp_context") != null ? doc.selectFirst(".yp_context").text().trim() : "");
            
            Elements infoItems = doc.select(".moviedteail_list li");
            for (Element li : infoItems) {
                String text = li.text().trim();
                if (text.contains("类型：")) vod.setTypeName(text.replace("类型：", "").trim());
                else if (text.contains("地区：")) vod.setVodArea(text.replace("地区：", "").trim());
                else if (text.contains("年份：")) vod.setVodYear(text.replace("年份：", "").trim());
                else if (text.contains("导演：")) vod.setVodDirector(text.replace("导演：", "").trim());
                else if (text.contains("主演：")) vod.setVodActor(text.replace("主演：", "").trim());
            }
            
            List<String> playLinks = new ArrayList<>();
            Elements playBtns = doc.select(".paly_list_btn a");
            if (playBtns.isEmpty()) playBtns = doc.select(".downurl a");
            
            for (Element a : playBtns) {
                String playName = a.text().trim();
                String href = a.attr("href");
                String fullUrl = href.startsWith("http") ? href : host + href;
                playLinks.add(playName + "$" + fullUrl);
            }
            
            vod.setVodPlayFrom(playLinks.isEmpty() ? "暂无资源" : "两个BT");
            vod.setVodPlayUrl(String.join("#", playLinks));

            return Result.string(vod);
        } catch (Exception e) {
            return Result.string(new Vod());
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String searchUrl = host + "/xsssearch?q=" + URLEncoder.encode(key, "UTF-8");
            String html = OkHttp.string(searchUrl, getHeadersWithReferer(host + "/xsssearch"));
            Document doc = Jsoup.parse(html);
            List<Vod> list = new ArrayList<>();
            Elements items = doc.select("ul li");
            
            for (Element li : items) {
                Element a = li.selectFirst("h3.dytit a");
                if (a == null) a = li.selectFirst("a");
                if (a != null && a.attr("href").contains("/movie/")) {
                    String vodId = a.attr("href").startsWith("http") ? a.attr("href") : host + a.attr("href");
                    String name = a.text().trim();
                    String pic = li.selectFirst("img") != null ? li.selectFirst("img").attr("src") : "";
                    list.add(new Vod(vodId, name, pic, ""));
                }
            }
            return Result.string(list);
        } catch (Exception e) {
            return Result.string(new ArrayList<Vod>());
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Origin", host.replaceAll("/$", ""));
        
        try {
            String html = OkHttp.string(id, getHeadersWithReferer(host + "/"));
            if (html != null) {
                String[] patterns = {
                    "fetch\\s*\\(\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']",
                    "url\\s*:\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']",
                    "video\\s*:\\s*\\{\\s*url\\s*:\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']",
                    "(https?://[^\\s\"']+\\.m3u8[^\\s\"']*)"
                };
                
                for (String patternStr : patterns) {
                    Matcher matcher = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE).matcher(html);
                    if (matcher.find()) {
                        String m3u8Url = matcher.group(1).replace("\\/", "/");
                        if (m3u8Url.startsWith("/")) m3u8Url = host + m3u8Url;
                        return Result.get().url(m3u8Url).header(headers).string();
                    }
                }
            }
        } catch (Exception ignored) {}
        
        return Result.get().url(id).header(headers).string();
    }
}
