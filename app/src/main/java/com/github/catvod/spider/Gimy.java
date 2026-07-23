package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import com.github.catvod.crawler.Spider;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.net.OkHttp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Gimy extends Spider {

    private static final String HOST = "https://gimy.info";

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.put("Referer", HOST + "/");
        return headers;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("china-drama", "陆剧"));
        classes.add(new Class("anime", "动漫"));
        classes.add(new Class("korean-drama", "韩剧"));
        classes.add(new Class("us-drama", "美剧"));
        classes.add(new Class("hong-kong-drama", "港剧"));
        classes.add(new Class("taiwan-drama", "台剧"));
        classes.add(new Class("japanese-drama", "日剧"));
        classes.add(new Class("thailand-drama", "泰剧"));
        classes.add(new Class("shortdramas", "短剧"));
        classes.add(new Class("tv-show-2", "综艺"));
        classes.add(new Class("indonesia-drama-2", "印尼剧"));
        classes.add(new Class("short", "短片"));
        classes.add(new Class("tv-series-2", "电视系列"));

        return Result.get().classes(classes).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = Integer.parseInt(pg);
        String url;
        if (page == 1) {
            url = HOST + "/" + tid + "/";
        } else {
            url = HOST + "/" + tid + "/page/" + page + "/";
        }

        String html = OkHttp.string(url, getHeaders());
        List<Vod> list = parseVideoList(html);

        int totalPage = page;
        if (list.size() >= 8) {
            totalPage = page + 1;
        }

        return Result.get().vod(list).page(page, totalPage, 20, 2000).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String detailUrl = ids.get(0);
        if (!detailUrl.startsWith("http")) {
            detailUrl = HOST + "/" + detailUrl;
        }

        String html = OkHttp.string(detailUrl, getHeaders());

        Vod vod = new Vod();
        vod.setVodId(detailUrl);

        // 解析标题
        Pattern pTitle = Pattern.compile("<h1>([^<]+)</h1>", Pattern.CASE_INSENSITIVE);
        Matcher mTitle = pTitle.matcher(html);
        if (mTitle.find()) {
            vod.setVodName(mTitle.group(1).trim());
        }

        // 解析封面图片
        Pattern pPic = Pattern.compile("<div class=\"poster-wrap\">\\s*<img src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
        Matcher mPic = pPic.matcher(html);
        if (mPic.find()) {
            vod.setVodPic(mPic.group(1).trim());
        }

        // 解析简介
        Pattern pDesc = Pattern.compile("<div class=\"desc\">([\\s\\S]*?)</div>", Pattern.CASE_INSENSITIVE);
        Matcher mDesc = pDesc.matcher(html);
        if (mDesc.find()) {
            String desc = mDesc.group(1).replaceAll("<[^>]+>", "").trim();
            vod.setVodContent(desc);
        }

        // 解析剧集列表
        List<Episode> episodeList = new ArrayList<>();
        Pattern pEp = Pattern.compile("<a href=\"([^\"]+)\" class=\"episode-pill\" title=\"([^\"]+)\">\\s*([^<]+)\\s*</a>", Pattern.CASE_INSENSITIVE);
        Matcher mEp = pEp.matcher(html);

        while (mEp.find()) {
            String epUrl = mEp.group(1).trim();
            String epTitle = mEp.group(3).trim();
            episodeList.add(new Episode(epTitle, epUrl));
        }

        // 按剧集数字自然排序 (第1集 -> 第2集 -> 第10集)
        Collections.sort(episodeList, new Comparator<Episode>() {
            @Override
            public int compare(Episode o1, Episode o2) {
                int num1 = extractNumber(o1.name);
                int num2 = extractNumber(o2.name);
                return Integer.compare(num1, num2);
            }
        });

        // 拼接播放选集链接
        StringBuilder playUrlBuilder = new StringBuilder();
        for (int i = 0; i < episodeList.size(); i++) {
            Episode ep = episodeList.get(i);
            playUrlBuilder.append(ep.name).append("$").append(ep.url);
            if (i < episodeList.size() - 1) {
                playUrlBuilder.append("#");
            }
        }

        vod.setVodPlayFrom("Gimy在线");
        vod.setVodPlayUrl(playUrlBuilder.toString());

        return Result.get().vod(vod).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        String url = HOST + "/?s=" + key;
        String html = OkHttp.string(url, getHeaders());

        List<Vod> list = parseVideoList(html);
        return Result.get().vod(list).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String playPageUrl = id;
        if (!playPageUrl.startsWith("http")) {
            playPageUrl = HOST + "/" + playPageUrl;
        }

        String html = OkHttp.string(playPageUrl, getHeaders());
        String realPlayUrl = "";

        // 正则解析 <source src="..." type="video/mp4">
        Pattern pSource = Pattern.compile("<source[^>]+src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
        Matcher mSource = pSource.matcher(html);
        if (mSource.find()) {
            realPlayUrl = mSource.group(1).trim();
        }

        return Result.get().url(realPlayUrl).header(getHeaders()).string();
    }

    /**
     * 解析列表页与搜索页视频条目
     */
    private List<Vod> parseVideoList(String html) {
        List<Vod> list = new ArrayList<>();
        if (TextUtils.isEmpty(html)) {
            return list;
        }

        Pattern pCard = Pattern.compile("<a href=\"([^\"]+)\" class=\"movie-card\">[\\s\\S]*?<img src=\"([^\"]+)\" alt=\"([^\"]+)\"[\\s\\S]*?<div class=\"ep-count\">([^<]+)</div>", Pattern.CASE_INSENSITIVE);
        Matcher mCard = pCard.matcher(html);

        while (mCard.find()) {
            String href = mCard.group(1).trim();
            String img = mCard.group(2).trim();
            String name = mCard.group(3).trim();
            String remark = mCard.group(4).trim();

            Vod vod = new Vod();
            vod.setVodId(href);
            vod.setVodName(name);
            vod.setVodPic(img);
            vod.setVodRemarks(remark);
            list.add(vod);
        }
        return list;
    }

    /**
     * 提取文本中的数字，用于剧集自然排序
     */
    private int extractNumber(String text) {
        if (TextUtils.isEmpty(text)) return 0;
        Matcher matcher = Pattern.compile("\\d+").matcher(text);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group());
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    /**
     * 内部辅助类：存放剧集信息
     */
    private static class Episode {
        String name;
        String url;

        Episode(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }
}
