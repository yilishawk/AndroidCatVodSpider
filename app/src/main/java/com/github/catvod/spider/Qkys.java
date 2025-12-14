package com.github.catvod.spider;/*
/**

@auther lushunming
*/


import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Notify;
import com.github.catvod.utils.ProxyVideo;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Qkys extends Spider {

    private final String siteUrl = "https://m.87kkt.com";

    private Map<String, String> getHeader() {
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/100.0.4896.77 Mobile/15E148 Safari/604.1");
        header.put("Connection", "keep-alive");
        header.put("Referer", "https://m.87kkt.com/");
        header.put("sec-fetch-dest", "iframe");
        header.put("sec-fetch-mode", "navigate");
        header.put("sec-fetch-site", "cross-site");
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
       /* header.put("sec-ch-ua", "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"");
        header.put("sec-ch-ua-mobile", "?0");
        header.put("sec-ch-ua-platform", "\"Windows\"");*/
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
        //String filters = extend.get("filters");
        String html = OkHttp.string(target);
        Document doc = Jsoup.parse(html);
        getVods(list, doc);
        String total = "" + Integer.MAX_VALUE;


        return Result.get().vod(list).page(Integer.parseInt(pg), Integer.parseInt(total) / 12 + ((Integer.parseInt(total) % 12) > 0 ? 1 : 0), 12, Integer.parseInt(total)).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
    // 1. 爬取详情页HTML并解析
    String detailUrl = this.siteUrl + ids.get(0);
    Document doc = Jsoup.parse(OkHttp.string(detailUrl, getHeader()));

    // 2. 提取基础信息
    // 标题
    String title = doc.select(".stui-content__detail > h1.title.wdetail").text();
    // 封面图
    String vodPic = doc.select(".stui-content__thumb > a.pic > img").attr("data-original");
    if (StringUtils.isEmpty(vodPic)) {
        vodPic = doc.select(".stui-content__thumb > a.pic > img").attr("src");
    }

    // 类型/地区/年份（拆分p.data.hidden-xs的文本）
    String classifyInfo = doc.select(".stui-content__detail > p.data.hidden-xs").text();
    String classifyName = ""; // 类型
    String vodArea = "";      // 地区
    String vodYear = "";      // 年份
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

    // 状态（作为备注）
    String vodRemarks = doc.select(".stui-content__detail > p.data:contains(\"状态：\") > span").text();

    // 导演（拼接所有a标签文本）
    StringBuilder director = new StringBuilder();
    Elements directorLinks = doc.select(".stui-content__detail > p.data:contains(\"导演：\") > a");
    for (Element a : directorLinks) {
        director.append(a.text()).append(" ");
    }
    String vodDirector = director.toString().trim();

    // 主演（拼接所有a标签文本）
    StringBuilder actor = new StringBuilder();
    Elements actorLinks = doc.select(".stui-content__detail > p.data:contains(\"主演：\") > a");
    for (Element a : actorLinks) {
        actor.append(a.text()).append(" ");
    }
    String vodActor = actor.toString().trim();

    // 简介（合并摘要和完整内容）
    String briefSketch = doc.select(".detail-sketch").text();
    String briefContent = doc.select(".detail-content").text();
    String vodContent = StringUtils.isEmpty(briefContent) ? briefSketch : (briefSketch + briefContent);

    // 3. 提取多播放源+集数链接（核心修改点）
    StringBuilder vodPlayFrom = new StringBuilder(); // 播放源名称（如YY超清源$$$JP超清源$$$...）
    StringBuilder vodPlayUrl = new StringBuilder();  // 对应集数链接（如01$/87k375605-4-1.html#02$/87k375605-4-2.html$$$...）
    
    // 遍历所有播放源区块（每个.stui-vodlist__head对应一个播放源）
    Elements playSourceHeads = doc.select(".stui-vodlist__head");
    for (int i = 0; i < playSourceHeads.size(); i++) {
        Element head = playSourceHeads.get(i);
        // 播放源名称（如YY超清源）
        String sourceName = head.select("h3.title").text();
        if (StringUtils.isEmpty(sourceName)) {
            continue; // 跳过无名称的播放源
        }
        // 对应的播放列表（当前播放源下的ul.stui-content__playlist）
        Element playlist = head.nextElementSibling(); // 播放源标题的下一个元素就是ul列表
        if (playlist == null || !playlist.hasClass("stui-content__playlist")) {
            continue;
        }
        Elements episodes = playlist.select("li > a");
        if (episodes.isEmpty()) {
            continue; // 跳过无集数的播放源
        }

        // 拼接播放源名称（多个源用$$$分隔）
        if (vodPlayFrom.length() > 0) {
            vodPlayFrom.append("$$$");
        }
        vodPlayFrom.append(sourceName);

        // 拼接当前播放源的集数（集数之间用#分隔，格式：集数名称$链接）
        StringBuilder episodeStr = new StringBuilder();
        for (Element episode : episodes) {
            String epName = episode.text(); // 集数名称（如01、第1集）
            String epUrl = episode.attr("href"); // 集数链接
            if (StringUtils.isEmpty(epUrl)) {
                continue;
            }
            if (episodeStr.length() > 0) {
                episodeStr.append("#");
            }
            episodeStr.append(epName).append("$").append(epUrl);
        }

        // 拼接当前播放源的集数到总播放URL（多个源用$$$分隔）
        if (vodPlayUrl.length() > 0) {
            vodPlayUrl.append("$$$");
        }
        vodPlayUrl.append(episodeStr);
    }

    // 4. 封装Vod对象
    Vod vod = new Vod();
    vod.setVodId(ids.get(0));
    vod.setVodName(title);
    vod.setVodPic(vodPic);
    vod.setTypeName(classifyName); // 类型
    vod.setVodArea(vodArea);       // 地区
    vod.setVodYear(vodYear);       // 年份
    vod.setVodRemarks(vodRemarks); // 状态（如已完结）
    vod.setVodDirector(vodDirector); // 导演
    vod.setVodActor(vodActor);       // 主演
    vod.setVodContent(vodContent);   // 简介
    vod.setVodPlayFrom(vodPlayFrom.toString()); // 播放源名称
    vod.setVodPlayUrl(vodPlayUrl.toString());   // 集数链接
    return Result.string(vod);
}

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
       String encodedKey = java.net.URLEncoder.encode(key, "UTF-8");
       String searchUrl = siteUrl + "/87s" + encodedKey + "----------1---.html";
        String html = OkHttp.string(searchUrl);
        if (html.contains("Just a moment")) {

            Notify.show("在线之家资源需要人机验证");
        }
        Document document = Jsoup.parse(html);
        List<Vod> list = new ArrayList<>();
        for (Element div : document.select(".stui-vodlist > li")) {
            String id = div.select("a.stui-vodlist__thumb").attr("href");
            String name = div.select(".stui-vodlist__detail > h4.title > a").text();
            String pic = div.select("a.stui-vodlist__thumb").attr("data-original");
            if (pic.isEmpty()) pic = div.select("img").attr("src");
            String remark = div.select("a.stui-vodlist__thumb > span.pic-text").text();

            list.add(new Vod(id, name, pic, remark));
        }

        return Result.string(list);
    }


@Override
public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
    // 1. 第一步：爬取视频播放页，提取player_aaaa中的关键参数
    String playPageUrl = siteUrl + id; // 播放页完整URL（如https://m.87kkt.com/87k386741-3-2.html）
    String playPageHtml = OkHttp.string(playPageUrl, getHeader()); // 携带原网站请求头

    // 提取player_aaaa JSON对象（正则匹配，避免解析失败）
    Matcher playerMatcher = Pattern.compile("var player_aaaa=(\\{.*?\\});").matcher(playPageHtml);
    if (!playerMatcher.find()) {
        Notify.show("解析失败：未找到播放配置");
        return Result.error("未找到播放配置");
    }
    JSONObject playerJson = new JSONObject(playerMatcher.group(1));

    // 提取player_aaaa核心参数（缺一不可，否则CDN链接无效）
    String encryptUrl = playerJson.optString("url", ""); // 加密播放参数（如WtVilaOnQcii00w0Y3jbdzl...）
    String type = playerJson.optString("from", ""); // 线路标识（如YYNB）
    String playData = playerJson.optString("play_data", ""); // data参数（如1c0f68912a442e3a83a...）
    String next = playPageUrl; // next参数：当前播放页URL

    // 容错：核心参数为空直接返回错误
    if (StringUtils.isEmpty(encryptUrl) || StringUtils.isEmpty(type) || StringUtils.isEmpty(playData)) {
        Notify.show("解析失败：播放核心参数缺失");
        return Result.error("播放核心参数缺失");
    }

    // 2. 第二步：拼接CDN播放链接，访问并提取config参数
    String cdnDomain = "https://cdn-omtcqq-com-oss-cn-hangzhou-shanghai-yys-valipl-vip-cp13.87kkt.com";
    // 拼接CDN链接（next参数需URL编码，避免特殊字符失效）
    String cdnPlayUrl = String.format(
        "%s/index.php?url=%s&type=%s&next=%s&data=%s",
        cdnDomain,
        encryptUrl,
        type,
        URLEncoder.encode(next, "UTF-8"),
        playData
    );

    // 访问CDN链接：必须设置Referer（原网站域名），否则403拒绝访问
    Map<String, String> cdnHeader = getVideoHeader();
    cdnHeader.put("Referer", siteUrl); // 关键：来路标识，不可缺少
    String cdnHtml = OkHttp.string(cdnPlayUrl, cdnHeader);

    // 提取config对象（正则匹配JSON，避免解析HTML标签干扰）
    Matcher configMatcher = Pattern.compile("var config = (\\{.*?\\});").matcher(cdnHtml);
    if (!configMatcher.find()) {
        Notify.show("解析失败：未找到CDN播放配置");
        return Result.error("未找到CDN播放配置");
    }
    JSONObject configJson = new JSONObject(configMatcher.group(1));

    // 提取POST请求所需参数（从config中获取）
    String postUrlParam = configJson.optString("url", ""); // 二次加密参数
    String time = configJson.optString("time", ""); // 时间戳
    String vkey = configJson.optString("vkey", ""); // 验证密钥
    String key = configJson.optString("key", ""); // 可选参数，默认空

    // 容错：POST参数为空直接返回错误
    if (StringUtils.isEmpty(postUrlParam) || StringUtils.isEmpty(time) || StringUtils.isEmpty(vkey)) {
        Notify.show("解析失败：POST请求参数缺失");
        return Result.error("POST请求参数缺失");
    }

    // 3. 第三步：构造POST请求，获取真实播放地址
    String postApi = cdnDomain + "/admin/mizhi_json.php"; // POST接口地址

    // 构造POST请求头（严格匹配网站要求，否则接口拒绝响应）
    Map<String, String> postHeader = new HashMap<>();
    postHeader.put("x-requested-with", "XMLHttpRequest"); // 标识AJAX请求，必须有
    postHeader.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36");
    postHeader.put("Accept", "application/json, text/javascript, */*; q=0.01");
    postHeader.put("sec-ch-ua", "\"Google Chrome\";v=\"143\", \"Chromium\";v=\"143\", \"Not A(Brand\";v=\"24\"");
    postHeader.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8"); // 表单提交格式
    postHeader.put("sec-ch-ua-mobile", "?0");
    postHeader.put("Origin", cdnDomain); // 跨域请求Origin，必须匹配CDN域名
    postHeader.put("Referer", cdnPlayUrl); // Referer为CDN播放链接，不可缺少

    // 构造POST请求体（所有参数需URL编码，避免特殊字符导致请求失效）
    String requestBody = String.format(
        "url=%s&time=%s&key=%s&vkey=%s",
        URLEncoder.encode(postUrlParam, "UTF-8"),
        URLEncoder.encode(time, "UTF-8"),
        URLEncoder.encode(key, "UTF-8"),
        URLEncoder.encode(vkey, "UTF-8")
    );

    // 发送POST请求（OkHttp.post需支持表单提交，CatVod的OkHttp工具类已兼容）
    String postResponse = OkHttp.post(postApi, postHeader, requestBody);
    if (StringUtils.isEmpty(postResponse)) {
        Notify.show("解析失败：POST请求无响应");
        return Result.error("POST请求无响应");
    }

    // 4. 第四步：解析POST响应，提取真实播放地址（核心修改！直接取json_url）
    JSONObject responseJson = new JSONObject(postResponse);
    // 从响应中提取json_url（真实m3u8播放地址）
    String realPlayUrl = responseJson.optString("json_url", "");

    // 容错：真实播放地址为空
    if (StringUtils.isEmpty(realPlayUrl)) {
        Notify.show("解析失败：未获取到真实播放地址");
        return Result.error("未获取到真实播放地址");
    }

    // 5. 第五步：返回播放地址给CatVod（携带必要请求头，确保播放不被拒绝）
    return Result.get()
        .url(realPlayUrl) // 真实m3u8地址
        .header(getVideoHeader()) // 复用视频请求头（包含User-Agent等）
        .referer(cdnDomain) // 播放时携带CDN域名作为Referer，避免403
        .string();
}


}
