package com.github.catvod.spider;

import android.content.Context;
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

    /**
     * 修复：移除 @Override，因为新版基类可能不再包含此方法
     */
    public String getName() {
        return "两个BT";
    }

    /**
     * 核心修复：根据报错信息，init 参数应为 Context
     */
    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
    }

    /**
     * 修复：如果报错 method does not override，请保持去掉 @Override
     */
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
                String remark = remarkTag != null ? remarkTag.text().trim() : "";
                String name = (imgTag != null && !imgTag.attr("alt").isEmpty()) ? imgTag.attr("alt") : aTag.text().trim();
                
                list.add(new Vod(vodId, name, pic, remark));
            }
            
            // 保持 5 参数格式
            return Result.string(page, page + 1, list.size(), 1000, list);
        } catch (Exception e) {
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
            
            List<String> playLinks = new ArrayList<>();
            Elements playBtns = doc.select(".paly_list_btn a");
            for (Element a : playBtns) {
                String fullUrl = a.attr("href").startsWith("http") ? a.attr("href") : host + a.attr("href");
                playLinks.add(a.text().trim() + "$" + fullUrl);
            }
            
            vod.setVodPlayFrom("两个BT");
            vod.setVodPlayUrl(String.join("#", playLinks));

            return Result.string(vod);
        } catch (Exception e) {
            return Result.string(new Vod());
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", USER_AGENT);
        try {
            String html = OkHttp.string(id, getHeadersWithReferer(host + "/"));
            Matcher matcher = Pattern.compile("(https?://[^\\s\"']+\\.m3u8[^\\s\"']*)", Pattern.CASE_INSENSITIVE).matcher(html);
            if (matcher.find()) {
                String m3u8Url = matcher.group(1).replace("\\/", "/");
                return Result.get().url(m3u8Url).header(headers).string();
            }
        } catch (Exception ignored) {}
        return Result.get().url(id).header(headers).string();
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String searchUrl = host + "/xsssearch?q=" + URLEncoder.encode(key, "UTF-8");
            String html = OkHttp.string(searchUrl, getHeadersWithReferer(host + "/xsssearch"));
            Document doc = Jsoup.parse(html);
            List<Vod> list = new ArrayList<>();
            for (Element li : doc.select("ul li")) {
                Element a = li.selectFirst("a");
                if (a != null && a.attr("href").contains("/movie/")) {
                    String vodId = a.attr("href").startsWith("http") ? a.attr("href") : host + a.attr("href");
                    list.add(new Vod(vodId, a.text().trim(), "", ""));
                }
            }
            return Result.string(list);
        } catch (Exception e) {
            return Result.string(new ArrayList<Vod>());
        }
    }
}
