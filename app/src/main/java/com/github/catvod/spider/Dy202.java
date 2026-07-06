package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.net.OkHttp;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.Response;

public class Dy202 extends Spider {

    private String host = "https://www.202dy.com";
    private String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.7727.56 Safari/537.36";
    private Map<String, String> baseHeaders;
    private String cookie;

    @Override
    public void init(Context context, String extend) throws Exception {
        // 模拟真实浏览器请求头获取 Cookie
        cookie = fetchCookie();
        baseHeaders = new HashMap<>();
        baseHeaders.put("User-Agent", ua);
        // 以下两个头服务器可能校验，增加成功率
        baseHeaders.put("sec-ch-ua", "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"147\", \"Google Chrome\";v=\"147\"");
        baseHeaders.put("sec-ch-ua-mobile", "?0");
        baseHeaders.put("sec-ch-ua-platform", "\"Windows\"");
        if (!TextUtils.isEmpty(cookie)) {
            baseHeaders.put("Cookie", cookie);
        }
        // 绝对不设置 Accept-Encoding
    }

    /**
     * 请求首页并返回完整 Cookie 字符串
     */
    private String fetchCookie() {
        try {
            Request request = new Request.Builder()
                    .url(host)
                    .header("User-Agent", ua)
                    .header("sec-ch-ua", "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"147\", \"Google Chrome\";v=\"147\"")
                    .header("sec-ch-ua-mobile", "?0")
                    .header("sec-ch-ua-platform", "\"Windows\"")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Upgrade-Insecure-Requests", "1")
                    .build();
            Response response = OkHttp.newCall(request);
            if (response == null || !response.isSuccessful()) {
                if (response != null) response.close();
                return "";
            }
            Headers headers = response.headers();
            List<String> cookies = headers.values("Set-Cookie");
            response.close();
            if (cookies.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (String c : cookies) {
                String[] parts = c.split(";");
                if (parts.length > 0) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(parts[0].trim());
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("tv", "电视剧"));
        classes.add(new Class("film", "电影"));
        classes.add(new Class("vs", "综艺"));
        return Result.get().classes(classes).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) throws Exception {
        String url = host + "/getapi/?pg=" + pg + "&type=" + tid;
        Map<String, String> headers = new HashMap<>(baseHeaders);
        headers.put("Referer", host + "/" + tid + "/");
        headers.put("Accept", "*/*");

        String jsonStr = OkHttp.string(url, headers);
        if (TextUtils.isEmpty(jsonStr)) {
            return Result.get().vod(new ArrayList<Vod>()).page(1, 1, 0, 0).string();
        }

        try {
            JSONObject obj = new JSONObject(jsonStr);
            if (obj.optInt("code") != 1) {
                return Result.error(obj.optString("msg", "数据获取失败"));
            }
            int page = obj.getInt("pg");
            int pagecount = obj.getInt("pagecount");
            int limit = obj.getInt("limit");
            int total = obj.getInt("total");

            JSONArray list = obj.getJSONArray("list");
            List<Vod> videos = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.getJSONObject(i);
                Vod vod = new Vod();
                vod.setVodId(item.getString("vod_en"));
                vod.setVodName(item.getString("vod_name"));
                vod.setVodPic(item.optString("vod_pic"));
                vod.setVodRemarks(item.optString("vod_remarks"));
                // 以下字段如果 Vod.java 不支持，请删除或替换
                // vod.setVodYear(item.optString("vod_year"));
                // vod.setVodArea(item.optString("vod_area"));
                // vod.setVodActor(item.optString("vod_actor"));
                // vod.setVodDirector(item.optString("vod_director"));
                // vod.setVodContent(item.optString("vod_class"));
                videos.add(vod);
            }
            return Result.get().vod(videos).page(page, pagecount, limit, total).string();
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return Result.error("id 为空");
        String vodEn = ids.get(0);
        String url = host + "/detail/" + vodEn + ".html";
        Map<String, String> headers = new HashMap<>(baseHeaders);
        headers.put("Referer", host + "/");

        String html = OkHttp.string(url, headers);
        if (TextUtils.isEmpty(html)) return Result.error("请求详情失败");

        Document doc = Jsoup.parse(html);
        Vod vod = new Vod();
        vod.setVodId(vodEn);

        // 标题
        Element titleEl = doc.selectFirst("h1.page-title");
        if (titleEl != null) vod.setVodName(titleEl.text().trim());

        // 封面
        Element imgEl = doc.selectFirst("img.url_img");
        if (imgEl != null) {
            String pic = imgEl.attr("src");
            if (pic.startsWith("/")) pic = host + pic;
            vod.setVodPic(pic);
        }

        // 导演、主演、年份、状态
        Elements infoItems = doc.select(".video-info-items");
        for (Element item : infoItems) {
            Element itemTitle = item.selectFirst(".video-info-itemtitle");
            if (itemTitle == null) continue;
            String titleText = itemTitle.text().trim();
            if (titleText.contains("导演")) {
                Element actorBox = item.selectFirst(".video-info-actor");
                if (actorBox != null) vod.setVodDirector(actorBox.text().trim());
            } else if (titleText.contains("主演")) {
                Element actorBox = item.selectFirst(".video-info-actor");
                if (actorBox != null) vod.setVodActor(actorBox.text().trim());
            } else if (titleText.contains("上映")) {
                Element val = item.selectFirst(".video-info-item");
                if (val != null) vod.setVodYear(val.text().trim());
            } else if (titleText.contains("状态")) {
                Element status = item.selectFirst(".pink-text");
                if (status != null) vod.setVodRemarks(status.text().trim());
            }
        }

        // 简介
        Element desc = doc.selectFirst("#desc .detail-content");
        if (desc != null) vod.setVodContent(desc.text().trim());

        // 播放列表
        Elements epItems = doc.select(".epitr a.epBtn");
        List<String> names = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (Element a : epItems) {
            String href = a.attr("href");
            String text = a.text().trim();
            if (!TextUtils.isEmpty(href) && !TextUtils.isEmpty(text)) {
                names.add("第" + text + "集");
                urls.add(href);
            }
        }

        if (!urls.isEmpty()) {
            vod.setVodPlayFrom("202dy");
            StringBuilder playUrl = new StringBuilder();
            for (int i = 0; i < urls.size(); i++) {
                playUrl.append(names.get(i)).append("$").append(urls.get(i));
                if (i < urls.size() - 1) playUrl.append("#");
            }
            vod.setVodPlayUrl(playUrl.toString());
        }

        return Result.get().vod(vod).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return Result.get().vod(new ArrayList<Vod>()).page(1, 1, 0, 0).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String playUrl = id.startsWith("http") ? id : host + id;
        Map<String, String> headers = new HashMap<>(baseHeaders);
        headers.put("Referer", host + "/");

        String playHtml = OkHttp.string(playUrl, headers);
        if (TextUtils.isEmpty(playHtml)) return Result.error("播放页请求失败");

        Document doc = Jsoup.parse(playHtml);
        Element iframe = doc.selectFirst("iframe#myIframe");
        if (iframe == null) return Result.error("未找到播放器");

        String playerSrc = iframe.attr("src");
        if (!playerSrc.startsWith("http")) {
            playerSrc = playerSrc.startsWith("/") ? host + playerSrc : host + "/" + playerSrc;
        }

        String playerHtml = OkHttp.string(playerSrc, headers);
        if (TextUtils.isEmpty(playerHtml)) return Result.error("获取播放器失败");

        Pattern p = Pattern.compile("url:\\s*\"(https?://[^\"]+)\"");
        Matcher m = p.matcher(playerHtml);
        if (!m.find()) return Result.error("提取播放地址失败");

        String m3u8 = m.group(1);
        Map<String, String> playHeaders = new HashMap<>();
        playHeaders.put("User-Agent", ua);
        playHeaders.put("Referer", playerSrc);
        return Result.get().url(m3u8).header(playHeaders).string();
    }
}
