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

public class EroTo extends Spider {

    private static final String HOST = "https://ero.to";

    // 1. 添加密码门禁状态变量
    private boolean unlocked = false;

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36");
        headers.put("Referer", HOST + "/");
        return headers;
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);

        // 2. 调用密码门禁，若未解锁则抛出异常阻止源加载
        this.unlocked = PasswordGate.ensureUnlocked(context);
        if (!this.unlocked) {
            throw new Exception("Password verification failed. Source initialization aborted.");
        }
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        // 3. 门禁检查：未解锁则返回空分类
        if (!unlocked) {
            return Result.get().classes(new ArrayList<Class>()).string();
        }

        List<Class> classes = new ArrayList<Class>();
        // 提取分类并将日文翻译成中文
        classes.add(new Class("vod", "成人视频"));
        classes.add(new Class("fc2", "FC2 PPV特区"));
        classes.add(new Class("vod_genre/%e3%83%a2%e3%82%b6%e3%82%a4%e3%82%af%e9%99%a4%e5%8e%bb", "无码AI去马赛克"));

        return Result.get().classes(classes).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        // 3. 门禁检查
        if (!unlocked) {
            int page = Integer.parseInt(pg);
            return Result.get().vod(new ArrayList<Vod>()).page(page, 0, 0, 0).string();
        }

        int page = Integer.parseInt(pg);
        String url;
        if (page == 1) {
            url = HOST + "/" + tid + "/";
        } else {
            url = HOST + "/" + tid + "/page/" + page + "/";
        }

        String html = OkHttp.string(url, getHeaders());
        List<Vod> list = parseVideoList(html);

        // 翻页判断：如果解析出来的数量较多（一般每页12-24个以上），则提供下一页
        int totalPage = page;
        if (list.size() >= 8) {
            totalPage = page + 1;
        }

        return Result.get().vod(list).page(page, totalPage, 20, 2000).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        // 3. 门禁检查
        if (!unlocked) {
            return Result.get().string();
        }

        String id = ids.get(0); // 传入的id包含"vod/155360"或"fc2/155353"
        String detailUrl = HOST + "/" + id + "/";
        String html = OkHttp.string(detailUrl, getHeaders());

        Vod vod = new Vod();
        vod.setVodId(id);

        // 提取视频标题，移除多余换行符
        Pattern pTitle = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE);
        Matcher mTitle = pTitle.matcher(html);
        String title = "Video " + id;
        if (mTitle.find()) {
            title = mTitle.group(1).replace(" - エロ動画ero.to", "").replace(" - FC2 PPVero.to", "").trim();
        }
        vod.setVodName(title);

        // 提取大图海报
        Pattern pPic = Pattern.compile("<img[^>]+src=\"(https://img.manyse.com/[^\"]+)\"", Pattern.CASE_INSENSITIVE);
        Matcher mPic = pPic.matcher(html);
        if (mPic.find()) {
            vod.setVodPic(mPic.group(1));
        }

        vod.setVodPlayFrom("高清原生线路");
        vod.setVodPlayUrl("立即播放$" + id);

        return Result.get().vod(vod).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        // 3. 门禁检查
        if (!unlocked) {
            return Result.get().vod(new ArrayList<Vod>()).string();
        }

        // 拼装搜索链接
        String url = HOST + "/?s=" + key + "&post_type%5B0%5D=vod";
        String html = OkHttp.string(url, getHeaders());

        List<Vod> list = parseVideoList(html);
        return Result.get().vod(list).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String detailUrl = HOST + "/" + id + "/";
        String html = OkHttp.string(detailUrl, getHeaders());

        String playUrl = "";

        // ---- 场景二：直接匹配 playUrls ----
        if (html.contains("playUrls")) {
            Pattern pUrls = Pattern.compile("const playUrls = \\[\\{\"label\":\"[^\"]+\",\"url\":\"([^\"]+)\"\\}\\];", Pattern.CASE_INSENSITIVE);
            Matcher mUrls = pUrls.matcher(html);
            if (mUrls.find()) {
                playUrl = mUrls.group(1).replace("\\/", "/"); // 还原转义的斜杠
            }
        }

        // ---- 场景一：匹配 sources + 变量组合 ----
        if (TextUtils.isEmpty(playUrl)) {
            String videoLink = "";
            String pndvd = "";
            String versionSuffix = "";

            Pattern pLink = Pattern.compile("const videoLink = \"([^\"]*)\";", Pattern.CASE_INSENSITIVE);
            Matcher mLink = pLink.matcher(html);
            if (mLink.find()) videoLink = mLink.group(1).trim();

            Pattern pPndvd = Pattern.compile("const pndvd = \"([^\"]*)\";", Pattern.CASE_INSENSITIVE);
            Matcher mPndvd = pPndvd.matcher(html);
            if (mPndvd.find()) pndvd = mPndvd.group(1).trim();

            Pattern pSuffix = Pattern.compile("const versionSuffix = \"([^\"]*)\";", Pattern.CASE_INSENSITIVE);
            Matcher mSuffix = pSuffix.matcher(html);
            if (mSuffix.find()) versionSuffix = mSuffix.group(1).trim();

            if (!TextUtils.isEmpty(videoLink) && !TextUtils.isEmpty(pndvd)) {
                // 默认拼接最优播放节点 FHD
                playUrl = "https://test.manyse.com/" + videoLink + "/" + pndvd + "/play.m3u8" + versionSuffix;
            }
        }

        return Result.get().url(playUrl).header(getHeaders()).string();
    }

    /**
     * 解析列表共用方法
     */
    private List<Vod> parseVideoList(String html) {
        List<Vod> list = new ArrayList<Vod>();
        if (TextUtils.isEmpty(html)) {
            return list;
        }

        // 使用正则提取卡片容器
        Pattern pCard = Pattern.compile("<div class=\"card text-white bg-dark mb-3\"[\\s\\S]*?<a href=\"https://ero.to/([^\"]+)\"[\\s\\S]*?<img [^>]*src=\"([^\"]+)\"[\\s\\S]*?<div style=\"display: -webkit-box;[^>]*>([\\s\\S]*?)</div>\\s*</div>", Pattern.CASE_INSENSITIVE);
        Matcher mCard = pCard.matcher(html);

        while (mCard.find()) {
            String id = mCard.group(1).trim(); // 例如 "vod/155360" 或者 "fc2/148355"
            // 清理末尾反斜杠以确保ID标准化
            if (id.endsWith("/")) {
                id = id.substring(0, id.length() - 1);
            }
            String img = mCard.group(2).trim();
            String title = mCard.group(3).trim();

            Vod vod = new Vod();
            vod.setVodId(id);
            vod.setVodName(title);
            vod.setVodPic(img);
            vod.setVodRemarks(""); // 此页面无直接时长显示，置空
            list.add(vod);
        }
        return list;
    }
}
