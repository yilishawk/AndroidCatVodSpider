package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Xszav2 extends Spider {

    private static final String HOST = "https://cn.xszav2.com";
    private boolean unlocked = false;

    // ==================== 分类映射（ID → 中文标题） ====================
    private static final Map<String, String> CATEGORY_MAP = new LinkedHashMap<>();
    static {
        CATEGORY_MAP.put("アナル", "肛交");
        CATEGORY_MAP.put("香港粵語三級電影", "三级片");
        CATEGORY_MAP.put("日本无码", "日本无码");
        CATEGORY_MAP.put("無修正リーク", "無修正リーク");
        CATEGORY_MAP.put("無修正流出", "無修正流出");
        CATEGORY_MAP.put("無修正リーク 熟女", "無修正熟女");
        CATEGORY_MAP.put("国产自拍", "国产自拍");
        CATEGORY_MAP.put("昭和ラブホテル盗撮", "昭和");
        CATEGORY_MAP.put("摄像头", "摄像头");
        CATEGORY_MAP.put("麻豆", "麻豆");
        CATEGORY_MAP.put("五十路 無修正リーク", "五十路 無修正リーク");
    }

    private void logger(String msg) {
        try {
            com.github.catvod.spider.Proxy.log("[Xszav2] " + msg);
        } catch (Exception e) {
            System.out.println("[Xszav2] " + msg);
        }
    }

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.put("Referer", HOST + "/");
        return headers;
    }

    private String get(String targetUrl) {
        logger("请求: " + targetUrl);
        try {
            String html = OkHttp.string(targetUrl, getHeaders());
            if (!TextUtils.isEmpty(html) && html.length() > 200) {
                logger("成功，长度: " + html.length());
                return html;
            }
            logger("返回空或过短，长度: " + (html == null ? 0 : html.length()));
        } catch (Exception e) {
            logger("请求异常: " + e.getMessage());
        }
        return "";
    }

    @Override
    public void init(Context context, String extend) {
        try {
            super.init(context, extend);
        } catch (Exception e) {
            logger("super.init 异常: " + e.getMessage());
        }
        logger("🚀 初始化 Xszav2（直连模式）...");
        this.unlocked = PasswordGate.ensureUnlocked(context);
        if (!this.unlocked) {
            logger("密码门禁未通过，初始化终止");
        } else {
            logger("密码门禁通过");
        }
    }

    @Override
    public String homeContent(boolean filter) {
        if (!unlocked) {
            return Result.get().classes(new ArrayList<Class>()).string();
        }
        List<Class> classes = new ArrayList<>();
        for (Map.Entry<String, String> entry : CATEGORY_MAP.entrySet()) {
            classes.add(new Class(entry.getKey(), entry.getValue()));
        }
        logger("首页分类数量: " + classes.size());
        return Result.get().classes(classes).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        if (!unlocked) {
            int page = Integer.parseInt(pg);
            return Result.get().vod(new ArrayList<Vod>()).page(page, 0, 0, 0).string();
        }
        try {
            int page = Integer.parseInt(pg);
            String encoded = URLEncoder.encode(tid, StandardCharsets.UTF_8.name()).replace("+", "%20");
            String url = HOST + "/search/videos/" + encoded;
            if (page > 1) {
                url += "?page=" + page;
            }
            logger("分类请求 tid=" + tid + " page=" + page);
            String html = get(url);
            List<Vod> list = parseVideoList(html);
            logger("分类解析结果数量: " + list.size());

            int totalPage = page;
            if (list.size() >= 20) {          // 根据实际每页数量调整
                totalPage = page + 1;
            }
            return Result.get().vod(list).page(page, totalPage, 20, 2000).string();
        } catch (Exception e) {
            logger("categoryContent 异常: " + e.getMessage());
            return Result.get().vod(new ArrayList<Vod>()).string();
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        if (!unlocked) {
            return Result.get().string();
        }
        try {
            String id = ids.get(0);
            String detailUrl = HOST + "/video/" + id;
            logger("详情请求: " + detailUrl);
            String html = get(detailUrl);
            logger("详情页长度: " + (html == null ? 0 : html.length()));

            Vod vod = new Vod();
            vod.setVodId(id);

            // 标题
            Pattern pTitle = Pattern.compile("alt=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
            Matcher mTitle = pTitle.matcher(html == null ? "" : html);
            String title = mTitle.find() ? mTitle.group(1) : "Video " + id;
            // 备用 title 属性
            if (title.startsWith("Video ")) {
                Pattern pTitle2 = Pattern.compile("title=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
                Matcher mTitle2 = pTitle2.matcher(html);
                if (mTitle2.find()) title = mTitle2.group(1);
            }
            vod.setVodName(title);
            logger("标题: " + title);

            // 封面
            Pattern pPic = Pattern.compile("src=\"(https://img\\.xszav2\\.com[^\"]+)\"", Pattern.CASE_INSENSITIVE);
            Matcher mPic = pPic.matcher(html == null ? "" : html);
            if (mPic.find()) {
                vod.setVodPic(mPic.group(1));
            }

            vod.setVodPlayFrom("Xszav2");
            vod.setVodPlayUrl("立即播放$" + id);

            return Result.get().vod(vod).string();
        } catch (Exception e) {
            logger("detailContent 异常: " + e.getMessage());
            return Result.get().string();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        if (!unlocked) {
            return Result.get().vod(new ArrayList<Vod>()).string();
        }
        try {
            // 搜索与分类格式完全相同
            String encoded = URLEncoder.encode(key, StandardCharsets.UTF_8.name()).replace("+", "%20");
            String url = HOST + "/search/videos/" + encoded;
            logger("搜索请求: " + key);
            String html = get(url);
            List<Vod> list = parseVideoList(html);
            logger("搜索结果数量: " + list.size());
            return Result.get().vod(list).string();
        } catch (Exception e) {
            logger("searchContent 异常: " + e.getMessage());
            return Result.get().vod(new ArrayList<Vod>()).string();
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String detailUrl = HOST + "/video/" + id;
            logger("播放解析: " + detailUrl);
            String html = get(detailUrl);
            logger("播放页长度: " + (html == null ? 0 : html.length()));

            String playUrl = "";

            // 真实地址：<video src="/media/videos/xxxxx.m3u8">
            Pattern pSrc = Pattern.compile(
                    "<video[^>]+src=[\"']([^\"']+\\.m3u8)[\"']",
                    Pattern.CASE_INSENSITIVE
            );
            Matcher mSrc = pSrc.matcher(html == null ? "" : html);
            if (mSrc.find()) {
                String src = mSrc.group(1).trim();
                if (src.startsWith("/")) {
                    playUrl = HOST + src;
                } else if (src.startsWith("http")) {
                    playUrl = src;
                } else {
                    playUrl = HOST + "/" + src;
                }
                logger("匹配到 m3u8: " + playUrl);
            }

            if (TextUtils.isEmpty(playUrl)) {
                logger("❌ 未提取到播放地址");
            }

            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            headers.put("Referer", HOST + "/");
            return Result.get().url(playUrl).header(headers).string();
        } catch (Exception e) {
            logger("playerContent 异常: " + e.getMessage());
            return Result.get().url("").string();
        }
    }

    // ==================== 列表页解析 ====================
    private List<Vod> parseVideoList(String html) {
        List<Vod> list = new ArrayList<>();
        if (TextUtils.isEmpty(html)) {
            logger("parseVideoList: html 为空");
            return list;
        }

        // 匹配每个视频卡片
        Pattern pBlock = Pattern.compile(
                "<div class=\"relative aspect-w-16 aspect-h-9[\\s\\S]*?</div>\\s*<div class=\"my-2 text-sm truncate\">[\\s\\S]*?</div>",
                Pattern.CASE_INSENSITIVE
        );
        Matcher mBlock = pBlock.matcher(html);
        int blockCount = 0;

        while (mBlock.find()) {
            blockCount++;
            String block = mBlock.group(0);

            // 视频ID
            Pattern pId = Pattern.compile("cn\\.xszav2\\.com/video/(\\d+)", Pattern.CASE_INSENSITIVE);
            Matcher mId = pId.matcher(block);
            if (!mId.find()) continue;
            String id = mId.group(1);

            // 标题
            String title = "Video " + id;
            Pattern pTitle = Pattern.compile("title=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
            Matcher mTitle = pTitle.matcher(block);
            if (mTitle.find()) {
                title = mTitle.group(1);
            } else {
                Pattern pAlt = Pattern.compile("alt=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
                Matcher mAlt = pAlt.matcher(block);
                if (mAlt.find()) title = mAlt.group(1);
            }

            // 封面
            String img = "";
            Pattern pImg = Pattern.compile("data-src=\"(https://img\\.xszav2\\.com[^\"]+)\"", Pattern.CASE_INSENSITIVE);
            Matcher mImg = pImg.matcher(block);
            if (mImg.find()) {
                img = mImg.group(1);
            }

            // 时长
            String duration = "";
            Pattern pDur = Pattern.compile("<span[^>]*>\\s*([0-9:]+)\\s*</span>", Pattern.CASE_INSENSITIVE);
            Matcher mDur = pDur.matcher(block);
            if (mDur.find()) {
                duration = mDur.group(1).trim();
            }

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
