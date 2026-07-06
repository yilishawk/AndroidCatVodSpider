package com.github.catvod.spider;

import android.content.Context;
import android.os.Handler;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.github.catvod.crawler.Spider;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.net.OkHttp;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Dy202 extends Spider {

    private String host = "https://www.202dy.com";
    private String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.7727.56 Safari/537.36";
    private Map<String, String> baseHeaders;

    @Override
    public void init(Context context, String extend) throws Exception {
        // 通过 WebView 访问首页获取 Cookie
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] cookieContainer = new String[1];
        Handler mainHandler = new Handler(context.getMainLooper());
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                WebView webView = new WebView(context);
                WebSettings settings = webView.getSettings();
                settings.setJavaScriptEnabled(true);
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        String cookie = CookieManager.getInstance().getCookie(url);
                        cookieContainer[0] = cookie;
                        latch.countDown();
                    }
                });
                webView.loadUrl(host);
            }
        });
        // 等待最多10秒获取 Cookie
        latch.await(10, TimeUnit.SECONDS);

        baseHeaders = new HashMap<>();
        baseHeaders.put("User-Agent", ua);
        baseHeaders.put("Referer", host + "/");
        if (cookieContainer[0] != null && !cookieContainer[0].isEmpty()) {
            baseHeaders.put("Cookie", cookieContainer[0]);
        }
        // 注意：绝不设置 Accept-Encoding，否则 OkHttp 自动解压失效
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
        // 不设置 Accept-Encoding

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
                // 以下 setter 需确认项目中 Vod.java 是否存在，若缺少请删除或替换为已存在字段
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

        // 导演（查找包含“导演”的 .video-info-itemtitle）
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
                urls.add(href); // 相对路径，如 /play/jingchengqitan-1-1.html
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

        // 以上 setter 如 setVodDirector 等需与项目中 Vod.java 一致，若缺字段请自行增删
        return Result.get().vod(vod).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        // 搜索暂未实现
        return Result.get().vod(new ArrayList<Vod>()).page(1, 1, 0, 0).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // id 为播放页路径，如 /play/jingchengqitan-1-1.html
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

        // 请求真正的播放器页面
        String playerHtml = OkHttp.string(playerSrc, headers);
        if (TextUtils.isEmpty(playerHtml)) return Result.error("获取播放器失败");

        // 正则提取 url: "..."
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
