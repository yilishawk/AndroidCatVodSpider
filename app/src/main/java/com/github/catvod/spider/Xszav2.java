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

    // ==================== 分类映射 ====================
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
        // 额外加一个英文测试
        CATEGORY_MAP.put("anal", "anal(测试)");
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
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
        headers.put("Accept-Encoding", "gzip, deflate, br");
        headers.put("Sec-Ch-Ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"");
        headers.put("Sec-Ch-Ua-Mobile", "?1");
        headers.put("Sec-Ch-Ua-Platform", "\"Android\"");
        headers.put("Sec-Fetch-Dest", "document");
        headers.put("Sec-Fetch-Mode", "navigate");
        headers.put("Sec-Fetch-Site", "none");
        headers.put("Sec-Fetch-User", "?1");
        headers.put("Upgrade-Insecure-Requests", "1");
        headers.put("Cache-Control", "max-age=0");
        headers.put("Referer", HOST + "/");
        return headers;
    }

    private String get(String targetUrl) {
        logger("请求: " + targetUrl);
        try {
            String html = OkHttp.string(targetUrl, getHeaders());
            if (TextUtils.isEmpty(html)) {
                logger("返回空");
                return "";
            }
            // Cloudflare 检测
            if (html.contains("Just a moment") || html.contains("cf-browser-verification") ||
                html.contains("Verify you are human") || html.contains("challenge-platform") ||
                html.contains("Performing security verification")) {
                logger("❌ Cloudflare 拦截！返回了验证页，长度: " + html.length());
                return "";
            }
            logger("成功，长度: " + html.length());
            return html;
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
        logger("🚀 初始化 Xszav2...");
        this.unlocked = PasswordGate.ensureUnlocked(context);
        if (!this.unlocked) {
            logger("密码门禁未通过");
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
            logger("分类请求 tid=" + tid + " page=" + page + " → " + url);
            String html = get(url);
            List<Vod> list = parseVideoList(html);
            logger("分类解析结果数量: " + list.size());

            int totalPage = list.size() >= 15 ? page + 1 : page;
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

            Vod vod = new Vod();
            vod.setVodId(id);

            String title = "Video " + id;
            Pattern pTitle = Pattern.compile("(?:alt|title)=\"([^\"]{5,})\"", Pattern.CASE_INSENSITIVE);
            Matcher mTitle = pTitle.matcher(html == null ? "" : html);
            if (mTitle.find()) {
                title = mTitle.group(1);
            }
            vod.setVodName(title);

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

            String playUrl = "";
            Pattern pSrc = Pattern.compile("<video[^>]+src=[\"']([^\"']+\\.m3u8)[\"']", Pattern.CASE_INSENSITIVE);
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
                return Result.get().url("").string();
            }

            // Referer 必须是当前视频详情页
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            headers.put("Referer", detailUrl);
            headers.put("Origin", HOST);
            headers.put("Accept", "*/*");

            return Result.get().url(playUrl).header(headers).string();
        } catch (Exception e) {
            logger("playerContent 异常: " + e.getMessage());
            return Result.get().url("").string();
        }
    }

    // ==================== 更鲁棒的列表解析 ====================
    private List<Vod> parseVideoList(String html) {
        List<Vod> list = new ArrayList<>();
        if (TextUtils.isEmpty(html)) {
            logger("parseVideoList: html 为空");
            return list;
        }

        // 方法1：优先匹配带 class 的完整卡片（你之前提供的结构）
        Pattern pBlock = Pattern.compile(
                "<div class=\"relative aspect-w-16[\\s\\S]*?</div>\\s*<div class=\"my-2 text-sm truncate\">[\\s\\S]*?</div>",
                Pattern.CASE_INSENSITIVE
        );
        Matcher mBlock = pBlock.matcher(html);
        int count = 0;
        while (mBlock.find()) {
            count++;
            String block = mBlock.group(0);
            Vod vod = extractFromBlock(block);
            if (vod != null) list.add(vod);
        }

        // 方法2：如果方法1没匹配到，用通用方式（找所有 /video/数字）
        if (list.isEmpty()) {
            logger("方法1无结果，启用通用解析");
            Pattern pLink = Pattern.compile(
                    "href=\"[^\"]*?/video/(\\d+)\"[^>]*(?:title|alt)=\"([^\"]+)\"|" +
                    "(?:title|alt)=\"([^\"]+)\"[^>]*href=\"[^\"]*?/video/(\\d+)\"|" +
                    "href=\"[^\"]*?/video/(\\d+)\"",
                    Pattern.CASE_INSENSITIVE
            );
            Matcher mLink = pLink.matcher(html);
            Map<String, Vod> map = new LinkedHashMap<>();
            while (mLink.find()) {
                String id = null;
                String title = null;
                if (mLink.group(1) != null) {
                    id = mLink.group(1);
                    title = mLink.group(2);
                } else if (mLink.group(4) != null) {
                    id = mLink.group(4);
                    title = mLink.group(3);
                } else if (mLink.group(5) != null) {
                    id = mLink.group(5);
                }
                if (id == null || map.containsKey(id)) continue;

                Vod vod = new Vod();
                vod.setVodId(id);
                vod.setVodName(TextUtils.isEmpty(title) ? "Video " + id : title);
                // 尝试找封面
                Pattern pImg = Pattern.compile("data-src=\"(https://img\\.xszav2\\.com[^\"]*" + id + "[^\"]*)\"", Pattern.CASE_INSENSITIVE);
                Matcher mImg = pImg.matcher(html);
                if (mImg.find()) {
                    vod.setVodPic(mImg.group(1));
                }
                map.put(id, vod);
            }
            list.addAll(map.values());
        }

        logger("parseVideoList 最终数量: " + list.size() + " (方法1匹配块=" + count + ")");
        return list;
    }

    private Vod extractFromBlock(String block) {
        Pattern pId = Pattern.compile("/video/(\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher mId = pId.matcher(block);
        if (!mId.find()) return null;
        String id = mId.group(1);

        String title = "Video " + id;
        Pattern pTitle = Pattern.compile("(?:title|alt)=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
        Matcher mTitle = pTitle.matcher(block);
        if (mTitle.find()) title = mTitle.group(1);

        String img = "";
        Pattern pImg = Pattern.compile("data-src=\"(https://img\\.xszav2\\.com[^\"]+)\"", Pattern.CASE_INSENSITIVE);
        Matcher mImg = pImg.matcher(block);
        if (mImg.find()) img = mImg.group(1);

        String duration = "";
        Pattern pDur = Pattern.compile("<span[^>]*>\\s*([0-9:]+)\\s*</span>", Pattern.CASE_INSENSITIVE);
        Matcher mDur = pDur.matcher(block);
        if (mDur.find()) duration = mDur.group(1).trim();

        Vod vod = new Vod();
        vod.setVodId(id);
        vod.setVodName(title);
        vod.setVodPic(img);
        vod.setVodRemarks(duration);
        return vod;
    }
}
