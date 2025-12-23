package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
// 确保导入 SpiderDebug
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import org.apache.commons.codec.binary.Base64;
import com.github.catvod.utils.Notify;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Qkys extends Spider {
    private final String siteUrl = "https://m.87kkt.com";

    private Map<String, String> getHeader() {
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36");
        header.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        String cookieString = "server_name_session=da36177dc7200e1bf20e798481dd4311; 5904a3788f1fcbc81fff0c26f2688e30=9f67751aaa76577c6408309ee1e0a6f5";
        header.put("Cookie", cookieString);
        return header;
    }

    private Map<String, String> getVideoHeader() {
        Map<String, String> header = new HashMap<>();
        header.put("Accept", "*/*");
        header.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,zh-TW;q=0.7,de;q=0.6");
        header.put("Cache-Control", "no-cache");
        header.put("Connection", "keep-alive");
        header.put("Pragma", "no-cache");
        header.put("Sec-Fetch-Dest", "video");
        header.put("Sec-Fetch-Mode", "no-cors");
        header.put("Sec-Fetch-Site", "cross-site");
        header.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        return header;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Vod> list = new ArrayList<>();
        List<Class> classes = new ArrayList<>();
        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();
        Document doc = Jsoup.parse(OkHttp.string(siteUrl));
        for (Element div : doc.select(".stui-header__menu > li ")) {
            classes.add(new Class(div.select(" a").attr("href"), div.select(" a").text()));
        }
        getVods(list, doc);
        return Result.string(classes, list);
    }

    private void getVods(List<Vod> list, Document doc) {
        for (Element div : doc.select(".stui-vodlist > li")) {
            String id = div.select(".stui-vodlist__box > a.stui-vodlist__thumb").attr("href");
            String name = div.select(".stui-vodlist__detail >h4.title > a").text();
            String pic = div.select(".stui-vodlist__box > a.stui-vodlist__thumb").attr("data-original");
            if (pic.isEmpty()) pic = div.select("img").attr("src");
            String remark = div.select(".stui-vodlist__box > a.stui-vodlist__thumb > span.pic-text").text();
            list.add(new Vod(id, name, pic, remark));
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        List<Vod> list = new ArrayList<>();
        String[] arr = tid.split("\\.");
        String target = siteUrl + arr[0] + "-" + pg + ".html";
        String html = OkHttp.string(target);
        Document doc = Jsoup.parse(html);
        getVods(list, doc);
        String total = "" + Integer.MAX_VALUE;
        return Result.get().vod(list).page(Integer.parseInt(pg), Integer.parseInt(total) / 12 + ((Integer.parseInt(total) % 12) > 0 ? 1 : 0), 12, Integer.parseInt(total)).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String detailUrl = this.siteUrl + ids.get(0);
        Document doc = Jsoup.parse(OkHttp.string(detailUrl, getHeader()));

        String title = doc.select(".stui-content__detail > h1.title.wdetail").text();
        String vodPic = doc.select(".stui-content__thumb > a.pic > img").attr("data-original");
        if (StringUtils.isEmpty(vodPic)) {
            vodPic = doc.select(".stui-content__thumb > a.pic > img").attr("src");
        }

        String classifyInfo = doc.select(".stui-content__detail > p.data.hidden-xs").text();
        String classifyName = "";
        String vodArea = "";
        String vodYear = "";
        if (StringUtils.isNotEmpty(classifyInfo)) {
            String[] infoParts = classifyInfo.split(" / ");
            for (String part : infoParts) {
                if (part.startsWith("类型：")) {
                    classifyName = part.replace("类型：", "");
                } else if (part.startsWith("地区：")) {
                    vodArea = part.replace("地区：", "");
                } else if (part.startsWith("年份：")) {
                    vodYear = part.replace("年份：", "");
                }
            }
        }

        String vodRemarks = doc.select(".stui-content__detail > p.data:contains(\"状态：\") > span").text();

        StringBuilder director = new StringBuilder();
        Elements directorLinks = doc.select(".stui-content__detail > p.data:contains(\"导演：\") > a");
        for (Element a : directorLinks) {
            director.append(a.text()).append(" ");
        }
        String vodDirector = director.toString().trim();

        StringBuilder actor = new StringBuilder();
        Elements actorLinks = doc.select(".stui-content__detail > p.data:contains(\"主演：\") > a");
        for (Element a : actorLinks) {
            actor.append(a.text()).append(" ");
        }
        String vodActor = actor.toString().trim();

        String briefSketch = doc.select(".detail-sketch").text();
        String briefContent = doc.select(".detail-content").text();
        String vodContent = StringUtils.isEmpty(briefContent) ? briefSketch : (briefSketch + briefContent);
        StringBuilder vodPlayFrom = new StringBuilder();
        StringBuilder vodPlayUrl = new StringBuilder();

        // 1. 选择所有的线路标题（Element Head）
        // CSS选择器：匹配所有 class="stui-vodlist__head" 的 div 元素
        Elements heads = doc.select("div.stui-vodlist__head"); 

        // 2. 选择所有的播放列表 (Element List)
        // CSS选择器：匹配所有 class="stui-content__playlist" 的 ul 元素
        Elements playlists = doc.select("ul.stui-content__playlist");

        // 确保线路标题和播放列表的数量一致或播放列表不少于标题
        // 如果数量不一致，可能意味着定位失败，或者网站结构不规范
        if (heads.size() != playlists.size()) {
            // 我们可以继续，但可能出错。先假设它们数量是一致的。
            System.out.println("警告：线路标题数量与播放列表数量不匹配！");
        }

        // 3. 通过索引同步遍历
        // 遍历线路标题集合
        for (int i = 0; i < heads.size(); i++) {
            Element head = heads.get(i);
            // 检查索引是否越界，安全起见
            if (i >= playlists.size()) {
                break; 
            }

            // 获取当前线路标题
            String sourceName = head.select("h3.title").text().trim();
            if (StringUtils.isEmpty(sourceName)) {
                continue;
            }

            // 获取与当前线路标题i对应的播放列表 i
            Element playlist = playlists.get(i);

            // 从列表中选择所有剧集链接
            Elements episodes = playlist.select("li > a");
            if (episodes.isEmpty()) {
                continue;
            }

            // --- 线路名称拼接 (使用 CatVod 标准 $$$ 分隔) ---
            if (vodPlayFrom.length() > 0) {
                vodPlayFrom.append("$$$");
            }
            vodPlayFrom.append(sourceName);

            // --- 集数链接拼接 ---
            StringBuilder episodeStr = new StringBuilder();
            for (Element episode : episodes) {
                String epName = episode.text().trim();
                String epUrl = episode.attr("href"); // 播放链接

                if (StringUtils.isEmpty(epUrl)) {
                    continue;
                }

                if (episodeStr.length() > 0) {
                    episodeStr.append("#"); // 剧集间分隔符
                }
                // 格式：集名$链接
                episodeStr.append(epName).append("$").append(epUrl);
            }

            // --- 播放链接拼接 (使用 CatVod 标准 $$$ 分隔) ---
            if (episodeStr.length() > 0) {
                if (vodPlayUrl.length() > 0) {
                    vodPlayUrl.append("$$$");
                }
                vodPlayUrl.append(episodeStr.toString());
            }
        }
        Vod vod = new Vod();
        vod.setVodId(ids.get(0));
        vod.setVodName(title);
        vod.setVodPic(vodPic);
        vod.setTypeName(classifyName);
        vod.setVodArea(vodArea);
        vod.setVodYear(vodYear);
        vod.setVodRemarks(vodRemarks);
        vod.setVodDirector(vodDirector);
        vod.setVodActor(vodActor);
        vod.setVodContent(vodContent);
        vod.setVodPlayFrom(vodPlayFrom.toString());
        vod.setVodPlayUrl(vodPlayUrl.toString());
        return Result.string(vod);
    }

   @Override
public String playerContent(String flag, String id, List<String> vipFlags) {
    try {
        String playPageUrl = siteUrl + id;  // e.g. https://m.87kkt.com/87k411945-4-1.html

        JSONObject result = new JSONObject();

        Map<String, String> headerMap = new HashMap<>();
        // 手机 UA（已证明有效）
        headerMap.put("User-Agent", "Mozilla/5.0 (Linux; Android 15; 23054RA19C) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/142.0.7444.171 Mobile Safari/537.36");
        headerMap.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        headerMap.put("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
        headerMap.put("sec-ch-ua", "\"Android WebView\";v=\"143\", \"Chromium\";v=\"143\", \"Not A(Brand\";v=\"24\"");
        headerMap.put("sec-ch-ua-mobile", "?1");
        headerMap.put("sec-ch-ua-platform", "\"Android\"");

        // *** 关键：添加 Referer 和相关 Sec-Fetch 头部，模拟从本站页面加载 ***
        headerMap.put("Referer", playPageUrl);  // 指向播放页本身
        headerMap.put("Sec-Fetch-Site", "same-origin");
        headerMap.put("Sec-Fetch-Mode", "navigate");
        headerMap.put("Sec-Fetch-Dest", "iframe");  // 或 "video"，根据实际嗅探类型试试

        result.put("parse", 1);
        result.put("playUrl", "");
        result.put("url", playPageUrl);
        result.put("header", new JSONObject(headerMap));

        return result.toString();
    } catch (Exception e) {
        SpiderDebug.log(e);
        return "{\"parse\":0,\"url\":\"\"}";
    }
}

} // <--- 闭合 Qkys 类
