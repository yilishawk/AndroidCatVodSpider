package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.FreeProxy;

import java.net.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class JavSiri extends Spider {

    private static final String HOST = "https://javsiri.cc";

    // 密码门禁状态
    private boolean unlocked = false;

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36");
        headers.put("Referer", HOST + "/");
        return headers;
    }

    /**
     * 带代理请求：先直连，失败再换代理重试
     */
    private String getWithProxy(String url, int maxRetry) {
        // 1. 先尝试直连
        try {
            String html = OkHttp.string(url, getHeaders());
            if (!TextUtils.isEmpty(html) && html.length() > 200) {
                return html;
            }
        } catch (Exception ignored) {
        }

        // 2. 直连失败，换代理重试
        for (int i = 0; i < maxRetry; i++) {
            String proxyStr = FreeProxy.getNext();
            if (TextUtils.isEmpty(proxyStr)) break;

            try {
                Proxy proxy = FreeProxy.toProxy(proxyStr);
                OkHttpClient client = new OkHttpClient.Builder()
                        .proxy(proxy)
                        .connectTimeout(12, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .writeTimeout(15, TimeUnit.SECONDS)
                        .build();

                Request.Builder rb = new Request.Builder().url(url);
                Map<String, String> headers = getHeaders();
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    rb.header(e.getKey(), e.getValue());
                }

                Response resp = client.newCall(rb.build()).execute();
                if (resp.isSuccessful() && resp.body() != null) {
                    String body = resp.body().string();
                    if (!TextUtils.isEmpty(body) && body.length() > 200) {
                        return body;
                    }
                }
            } catch (Exception e) {
                // 当前代理失败，继续下一个
            }
        }

        // 3. 全部失败，最后再试一次直连
        try {
            return OkHttp.string(url, getHeaders());
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);

        this.unlocked = PasswordGate.ensureUnlocked(context);
        if (!this.unlocked) {
            throw new Exception("Password verification failed. Source initialization aborted.");
        }

        // 预加载免费代理
        try {
            FreeProxy.ensureLoaded();
        } catch (Exception ignored) {
        }
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        if (!unlocked) {
            return Result.get().classes(new ArrayList<Class>()).string();
        }

        List<Class> classes = new ArrayList<Class>();
        classes.add(new Class("anal-sex", "肛交"));
        classes.add(new Class("big-tits", "大奶子"));
        classes.add(new Class("ntr", "NTR"));
        classes.add(new Class("slim-pixelated", "苗条身材"));
        classes.add(new Class("blowjob", "口交"));
        classes.add(new Class("promiscuity", "淫乱"));
        classes.add(new Class("nice-tits", "好奶子"));
        classes.add(new Class("fair-skin", "白皙皮肤"));
        classes.add(new Class("mature-woman", "熟女"));
        classes.add(new Class("orgy", "群交"));
        classes.add(new Class("high-school-girl", "高中女生"));
        classes.add(new Class("slim", "苗条"));
        classes.add(new Class("uncensored", "无码"));
        classes.add(new Class("uniform", "制服"));
        classes.add(new Class("squirting", "喷水"));
        classes.add(new Class("creampie", "内射"));
        classes.add(new Class("pretty-girl", "漂亮女孩"));
        classes.add(new Class("hd", "HD高画质"));
        classes.add(new Class("masturbation", "撸管/自慰"));

        return Result.get().classes(classes).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        if (!unlocked) {
            int page = Integer.parseInt(pg);
            return Result.get().vod(new ArrayList<Vod>()).page(page, 0, 0, 0).string();
        }

        int page = Integer.parseInt(pg);
        String url = HOST + "/zh/tags/" + tid + "/?mode=async&function=get_block&block_id=list_videos_common_videos_list&sort_by=post_date&from=" + page + "&_=" + System.currentTimeMillis();
        String html = getWithProxy(url, 5);

        List<Vod> list = parseVideoList(html);

        int totalPage = page;
        if (list.size() >= 10) {
            totalPage = page + 1;
        }

        return Result.get().vod(list).page(page, totalPage, 20, 2000).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (!unlocked) {
            return Result.get().string();
        }

        String id = ids.get(0);
        String detailUrl = HOST + "/zh/video/" + id;
        String html = getWithProxy(detailUrl, 5);

        Vod vod = new Vod();
        vod.setVodId(id);

        Pattern pTitle = Pattern.compile("video_title:\\s*'([^']+)'", Pattern.CASE_INSENSITIVE);
        Matcher mTitle = pTitle.matcher(html);
        String title = mTitle.find() ? mTitle.group(1) : "Video " + id;
        vod.setVodName(title);

        Pattern pPic = Pattern.compile("preview_url:\\s*'([^']+)'", Pattern.CASE_INSENSITIVE);
        Matcher mPic = pPic.matcher(html);
        if (mPic.find()) {
            vod.setVodPic(mPic.group(1));
        }

        vod.setVodPlayFrom("JavSiri主线");
        vod.setVodPlayUrl("立即播放$" + id);

        return Result.get().vod(vod).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        if (!unlocked) {
            return Result.get().vod(new ArrayList<Vod>()).string();
        }

        String url = HOST + "/zh/search/" + key + "/?mode=async&function=get_block&block_id=list_videos_videos_list_search_result&q=" + key + "&category_ids=&sort_by=post_date&from_videos=1&from_albums=1&_" + System.currentTimeMillis();
        String html = getWithProxy(url, 5);

        List<Vod> list = parseVideoList(html);
        return Result.get().vod(list).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String detailUrl = HOST + "/zh/video/" + id;
        String html = getWithProxy(detailUrl, 5);

        String playUrl = "";

        // 优先 720p
        Pattern pAltUrl = Pattern.compile("video_alt_url:\\s*'([^']+)'", Pattern.CASE_INSENSITIVE);
        Matcher mAltUrl = pAltUrl.matcher(html);
        if (mAltUrl.find()) {
            playUrl = mAltUrl.group(1);
        }

        // 降级 480p
        if (TextUtils.isEmpty(playUrl)) {
            Pattern pUrl = Pattern.compile("video_url:\\s*'([^']+)'", Pattern.CASE_INSENSITIVE);
            Matcher mUrl = pUrl.matcher(html);
            if (mUrl.find()) {
                playUrl = mUrl.group(1);
            }
        }

        return Result.get().url(playUrl).header(getHeaders()).string();
    }

    /**
     * 解析列表页视频
     */
    private List<Vod> parseVideoList(String html) {
        List<Vod> list = new ArrayList<Vod>();
        if (TextUtils.isEmpty(html)) {
            return list;
        }

        Pattern pBlock = Pattern.compile("<div class=\"thumb thumb_rel item[^\"]*\">([\\s\\S]*?)</div>\\s*</div>", Pattern.CASE_INSENSITIVE);
        Matcher mBlock = pBlock.matcher(html);

        while (mBlock.find()) {
            String block = mBlock.group(1);

            Pattern pId = Pattern.compile("href=\"https://javsiri.cc/zh/video/([^/]+/[^/]+)/\"", Pattern.CASE_INSENSITIVE);
            Matcher mId = pId.matcher(block);
            if (!mId.find()) continue;
            String id = mId.group(1);

            Pattern pImg = Pattern.compile("data-original=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
            Matcher mImg = pImg.matcher(block);
            String img = mImg.find() ? mImg.group(1) : "";

            Pattern pTitle = Pattern.compile("title=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
            Matcher mTitle = pTitle.matcher(block);
            String title = mTitle.find() ? mTitle.group(1) : "Video";

            Pattern pDur = Pattern.compile("<div class=\"time\">([^<]+)</div>", Pattern.CASE_INSENSITIVE);
            Matcher mDur = pDur.matcher(block);
            String duration = mDur.find() ? mDur.group(1).trim() : "";

            Vod vod = new Vod();
            vod.setVodId(id);
            vod.setVodName(title);
            vod.setVodPic(img);
            vod.setVodRemarks(duration);

            list.add(vod);
        }
        return list;
    }
}
