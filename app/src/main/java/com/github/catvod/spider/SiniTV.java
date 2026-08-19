package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import com.github.catvod.crawler.Spider;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.net.OkHttp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SiniTV extends Spider {

    private static final String HOST = "https://sinitv.cc";

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.put("Referer", HOST + "/");
        return headers;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("1", "电视剧"));
        classes.add(new Class("2", "电影"));

        return Result.get().classes(classes).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = Integer.parseInt(pg);
        // 分页URL格式: /vodshow/1--------2---.html
        String url = HOST + "/vodshow/" + tid + "--------" + page + "---.html";

        String html = OkHttp.string(url, getHeaders());
        List<Vod> list = parseVideoList(html);

        int totalPage = page;
        if (list.size() >= 10) {
            totalPage = page + 1;
        }

        return Result.get().vod(list).page(page, totalPage, 20, 2000).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String detailUrl = ids.get(0);
        if (!detailUrl.startsWith("http")) {
            detailUrl = HOST + detailUrl;
        }

        String html = OkHttp.string(detailUrl, getHeaders());

        Vod vod = new Vod();
        vod.setVodId(detailUrl);

        // 解析标题
        Pattern pTitle = Pattern.compile("<div class=\"this-desc-title\">([^<]+)</div>", Pattern.CASE_INSENSITIVE);
        Matcher mTitle = pTitle.matcher(html);
        if (mTitle.find()) {
            vod.setVodName(mTitle.group(1).trim());
        }

        // 解析封面图片
        Pattern pPic = Pattern.compile("<div class=\"this-pic-bj\">\\s*<img src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
        Matcher mPic = pPic.matcher(html);
        if (mPic.find()) {
            vod.setVodPic(mPic.group(1).trim());
        }

        // 解析主演
        Pattern pActor = Pattern.compile("<strong class=\"r6\">Pemeran:</strong>([\\s\\S]*?)</div>", Pattern.CASE_INSENSITIVE);
        Matcher mActor = pActor.matcher(html);
        if (mActor.find()) {
            String actorStr = mActor.group(1).replaceAll("<[^>]+>", "").replace("，", ",").trim();
            vod.setVodActor(actorStr);
        }

        // 解析简介
        Pattern pDesc = Pattern.compile("<div id=\"height_limit\" class=\"text\">([\\s\\S]*?)</div>", Pattern.CASE_INSENSITIVE);
        Matcher mDesc = pDesc.matcher(html);
        if (mDesc.find()) {
            String desc = mDesc.group(1).replaceAll("<[^>]+>", "").replace("Deskripsi:", "").trim();
            vod.setVodContent(desc);
        }

        // 解析播放线路与列表
        // 1. 提取所有播放源名称 (例如: XP)
        List<String> playFromList = new ArrayList<>();
        Pattern pFrom = Pattern.compile("<a class=\"swiper-slide\">[\\s\\S]*?&nbsp;([^<]+)<span", Pattern.CASE_INSENSITIVE);
        Matcher mFrom = pFrom.matcher(html);
        while (mFrom.find()) {
            playFromList.add(mFrom.group(1).trim());
        }

        // 2. 提取所有选集列表组
        List<String> playListGroup = new ArrayList<>();
        Pattern pListBox = Pattern.compile("<ul class=\"anthology-list-play[^\"]*\">([\\s\\S]*?)</ul>", Pattern.CASE_INSENSITIVE);
        Matcher mListBox = pListBox.matcher(html);

        while (mListBox.find()) {
            String ulContent = mListBox.group(1);
            List<String> epList = new ArrayList<>();
            Pattern pEp = Pattern.compile("<a [^>]*href=\"([^\"]+)\">([^<]+)</a>", Pattern.CASE_INSENSITIVE);
            Matcher mEp = pEp.matcher(ulContent);
            while (mEp.find()) {
                String epUrl = mEp.group(1).trim();
                String epName = mEp.group(2).trim();
                epList.add(epName + "$" + epUrl);
            }
            playListGroup.add(TextUtils.join("#", epList));
        }

        // 防错处理：如果线路数和播放组数量对不上，兜底处理
        if (playFromList.isEmpty()) {
            playFromList.add("默认播放");
        }

        vod.setVodPlayFrom(TextUtils.join("$$$", playFromList));
        vod.setVodPlayUrl(TextUtils.join("$$$", playListGroup));

        return Result.get().vod(vod).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        String url = HOST + "/vodsearch/-" + key + "------------.html";
        String html = OkHttp.string(url, getHeaders());

        List<Vod> list = parseVideoList(html);
        return Result.get().vod(list).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String playPageUrl = id;
        if (!playPageUrl.startsWith("http")) {
            playPageUrl = HOST + playPageUrl;
        }

        String html = OkHttp.string(playPageUrl, getHeaders());
        String realPlayUrl = "";

        // 正则解析 JS 对象 player_aaaa 中的 "url":"https:\/\/..."
        Pattern pUrl = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
        Matcher mUrl = pUrl.matcher(html);
        if (mUrl.find()) {
            realPlayUrl = mUrl.group(1).replace("\\/", "/");
        }

        return Result.get().url(realPlayUrl).header(getHeaders()).string();
    }

    /**
     * 解析列表页与搜索页中的视频条目
     */
    private List<Vod> parseVideoList(String html) {
        List<Vod> list = new ArrayList<>();
        if (TextUtils.isEmpty(html)) {
            return list;
        }

        Pattern pCard = Pattern.compile("<div class=\"public-list-box[^\"]*\">[\\s\\S]*?<a [^>]*href=\"([^\"]+)\" title=\"([^\"]+)\"[\\s\\S]*?<img [^>]*data-src=\"([^\"]+)\"[\\s\\S]*?(?:<span class=\"public-list-prb[^\"]*\">([^<]+)</span>)?", Pattern.CASE_INSENSITIVE);
        Matcher mCard = pCard.matcher(html);

        while (mCard.find()) {
            String href = mCard.group(1).trim();
            String title = mCard.group(2).trim();
            String img = mCard.group(3).trim();
            String remark = mCard.group(4) != null ? mCard.group(4).trim() : "";

            Vod vod = new Vod();
            vod.setVodId(href);
            vod.setVodName(title);
            vod.setVodPic(img);
            vod.setVodRemarks(remark);
            list.add(vod);
        }
        return list;
    }
}
