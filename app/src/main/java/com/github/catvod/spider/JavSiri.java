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
    private boolean unlocked = false;

    private void logger(String msg) {
        try {
            Proxy.log("[JavSiri] " + msg);
        } catch (Exception e) {
            System.out.println("[JavSiri] " + msg);
        }
    }

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36");
        headers.put("Referer", HOST + "/");
        return headers;
    }

    /**
     * 带代理请求 + 详细日志
     */
    private String getWithProxy(String url, int maxRetry) {
        logger("开始请求: " + url);

        // 1. 先直连
        try {
            String html = OkHttp.string(url, getHeaders());
            if (!TextUtils.isEmpty(html) && html.length() > 200) {
                logger("直连成功，长度: " + html.length());
                return html;
            }
            logger("直连返回空或过短，长度: " + (html == null ? 0 : html.length()));
        } catch (Exception e) {
            logger("直连异常: " + e.getMessage());
        }

        // 2. 代理重试
        int proxyCount = FreeProxy.size();
        logger("当前可用代理数量: " + proxyCount);

        for (int i = 0; i < maxRetry; i++) {
            String proxyStr = FreeProxy.getNext();
            if (TextUtils.isEmpty(proxyStr)) {
                logger("第 " + (i + 1) + " 次：没有更多代理");
                break;
            }

            logger("第 " + (i + 1) + " 次尝试代理: " + proxyStr);
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
                int code = resp.code();
                logger("代理响应码: " + code);

                if (resp.isSuccessful() && resp.body() != null) {
                    String body = resp.body().string();
                    if (!TextUtils.isEmpty(body) && body.length() > 200) {
                        logger("代理请求成功，长度: " + body.length());
                        return body;
                    }
                    logger("代理返回内容过短，长度: " + (body == null ? 0 : body.length()));
                } else {
                    logger("代理请求失败，code=" + code);
                }
            } catch (Exception e) {
                logger("代理异常 [" + proxyStr + "]: " + e.getMessage());
            }
        }

        // 3. 最后再试一次直连
        logger("所有代理失败，最后再试一次直连");
        try {
            String html = OkHttp.string(url, getHeaders());
            logger("最终直连长度: " + (html == null ? 0 : html.length()));
            return html == null ? "" : html;
        } catch (Exception e) {
            logger("最终直连也失败: " + e.getMessage());
            return "";
        }
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        logger("🚀 初始化 JavSiri...");

        this.unlocked = PasswordGate.ensureUnlocked(context);
        if (!this.unlocked) {
            throw new Exception("Password verification failed. Source initialization aborted.");
        }
        logger("密码门禁通过");

        try {
            FreeProxy.ensureLoaded();
            logger("FreeProxy 加载完成，数量: " + FreeProxy.size());
        } catch (Exception e) {
            logger("FreeProxy 加载失败: " + e.getMessage());
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

        logger("首页分类数量: " + classes.size());
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
        logger("分类请求 tid=" + tid + " page=" + page);

        String html = getWithProxy(url, 5);
        List<Vod> list = parseVideoList(html);
        logger("分类解析结果数量: " + list.size());

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
        logger("详情请求: " + detailUrl);

        String html = getWithProxy(detailUrl, 5);
        logger("详情页长度: " + (html == null ? 0 : html.length()));

        Vod vod = new Vod();
        vod.setVodId(id);

        Pattern pTitle = Pattern.compile("video_title:\\s*'([^']+)'", Pattern.CASE_INSENSITIVE);
        Matcher mTitle = pTitle.matcher(html == null ? "" : html);
        String title = mTitle.find() ? mTitle.group(1) : "Video " + id;
        vod.setVodName(title);
        logger("标题: " + title);

        Pattern pPic = Pattern.compile("preview_url:\\s*'([^']+)'", Pattern.CASE_INSENSITIVE);
        Matcher mPic = pPic.matcher(html == null ? "" : html);
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
        logger("搜索请求: " + key);

        String html = getWithProxy(url, 5);
        List<Vod> list = parseVideoList(html);
        logger("搜索结果数量: " + list.size());

        return Result.get().vod(list).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String detailUrl = HOST + "/zh/video/" + id;
        logger("播放解析: " + detailUrl);

        String html = getWithProxy(detailUrl, 5);
        logger("播放页长度: " + (html == null ? 0 : html.length()));

        String playUrl = "";

        Pattern pAltUrl = Pattern.compile("video_alt_url:\\s*'([^']+)'", Pattern.CASE_INSENSITIVE);
        Matcher mAltUrl = pAltUrl.matcher(html == null ? "" : html);
        if (mAltUrl.find()) {
            playUrl = mAltUrl.group(1);
            logger("匹配到 720p: " + playUrl);
        }

        if (TextUtils.isEmpty(playUrl)) {
            Pattern pUrl = Pattern.compile("video_url:\\s*'([^']+)'", Pattern.CASE_INSENSITIVE);
            Matcher mUrl = pUrl.matcher(html == null ? "" : html);
            if (mUrl.find()) {
                playUrl = mUrl.group(1);
                logger("匹配到 480p: " + playUrl);
            }
        }

        if (TextUtils.isEmpty(playUrl)) {
            logger("❌ 未提取到播放地址");
        }

        return Result.get().url(playUrl).header(getHeaders()).string();
    }

    private List<Vod> parseVideoList(String html) {
        List<Vod> list = new ArrayList<Vod>();
        if (TextUtils.isEmpty(html)) {
            logger("parseVideoList: html 为空");
            return list;
        }

        Pattern pBlock = Pattern.compile("<div class=\"thumb thumb_rel item[^\"]*\">([\\s\\S]*?)</div>\\s*</div>", Pattern.CASE_INSENSITIVE);
        Matcher mBlock = pBlock.matcher(html);

        int blockCount = 0;
        while (mBlock.find()) {
            blockCount++;
            String block = mBlock.group(1);

            Pattern pId = Pattern.compile("href=\"https://javsiri.cc/zh/video/([^/]+/[^/]+)/\"", Pattern.CASE_INSENSITIVE);
            Matcher mId = pId.matcher(block);
            if (!mId.find()) {
                // 兼容相对路径
                pId = Pattern.compile("href=\"/zh/video/([^/]+/[^/]+)/\"", Pattern.CASE_INSENSITIVE);
                mId = pId.matcher(block);
                if (!mId.find()) continue;
            }
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

        logger("parseVideoList: 匹配到 block=" + blockCount + "，有效视频=" + list.size());
        return list;
    }
}
