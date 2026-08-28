package com.github.catvod.spider;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;

import org.json.JSONTokener;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Xszav2：全程 WebView 拉取页面 HTML，用于绕过 Cloudflare。
 * 源配置 api 示例：csp_Xszav2
 */
public class Xszav2 extends Spider {

    private static final String HOST = "https://en.xszav2.com";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /** WebView 单次最长等待 */
    private static final long WEB_TIMEOUT_SEC = 30L;

    private boolean unlocked = false;
    private Context appContext;

    private static final Object WEB_LOCK = new Object();

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
        CATEGORY_MAP.put("anal", "anal(测试)");
    }

    private void logger(String msg) {
        try {
            com.github.catvod.spider.Proxy.log("[Xszav2] " + msg);
        } catch (Exception e) {
            System.out.println("[Xszav2] " + msg);
        }
    }

    /** 是否 Cloudflare 挑战页（避免用 challenge-platform 误判） */
    private boolean isChallenge(String html) {
        if (TextUtils.isEmpty(html)) return true;
        String h = html.toLowerCase();
        if (h.contains("<title>just a moment</title>")) return true;
        if (h.contains("just a moment") && h.contains("cdn-cgi")) return true;
        if (h.contains("cf-browser-verification")) return true;
        if (h.contains("verify you are human") && h.contains("cdn-cgi")) return true;
        // 有业务特征则认为不是挑战
        if (h.contains("/video/") || h.contains("img.xszav2.com")) return false;
        return false;
    }

    private static void destroyWeb(AtomicReference<WebView> ref) {
        WebView w = ref.getAndSet(null);
        if (w == null) return;
        try {
            w.stopLoading();
            w.loadUrl("about:blank");
            w.clearHistory();
            w.removeAllViews();
            w.destroy();
        } catch (Exception ignored) {
        }
    }

    /**
     * 用 WebView 打开完整 URL，返回 outerHTML。
     * 在主线程创建 WebView，后台线程等待结果。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private String getHtmlByWebView(String targetUrl) {
        synchronized (WEB_LOCK) {
            if (appContext == null) {
                try {
                    appContext = Init.context();
                } catch (Throwable t) {
                    logger("Init.context 失败: " + t.getMessage());
                }
            }
            if (appContext == null) {
                logger("appContext 为空，无法创建 WebView");
                return "";
            }

            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> htmlRef = new AtomicReference<>("");
            final AtomicReference<WebView> webRef = new AtomicReference<>();
            final Handler main = new Handler(Looper.getMainLooper());

            main.post(() -> {
                try {
                    CookieManager cm = CookieManager.getInstance();
                    cm.setAcceptCookie(true);

                    WebView webView = new WebView(appContext);
                    webRef.set(webView);

                    WebSettings settings = webView.getSettings();
                    settings.setJavaScriptEnabled(true);
                    settings.setDomStorageEnabled(true);
                    settings.setDatabaseEnabled(true);
                    settings.setUserAgentString(UA);
                    settings.setCacheMode(WebSettings.LOAD_DEFAULT);
                    settings.setMediaPlaybackRequiresUserGesture(true);
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        cm.setAcceptThirdPartyCookies(webView, true);
                        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                    }

                    webView.setWebViewClient(new WebViewClient() {
                        private boolean finished = false;
                        private int tryCount = 0;

                        private void grabHtml(WebView view, String from) {
                            if (finished) return;
                            tryCount++;
                            view.evaluateJavascript(
                                    "(function(){try{return document.documentElement.outerHTML||'';}catch(e){return ''}})();",
                                    value -> {
                                        if (finished) return;
                                        String html = decodeJsString(value);
                                        logger("grabHtml from=" + from + " try=" + tryCount
                                                + " len=" + (html == null ? 0 : html.length()));

                                        if (TextUtils.isEmpty(html) || html.length() < 800 || isChallenge(html)) {
                                            logger("仍是挑战/过短，等待后续 onPageFinished…");
                                            // 最后一次尝试仍不行，超时由 latch 处理
                                            if (tryCount >= 6) {
                                                finished = true;
                                                htmlRef.set(html == null ? "" : html);
                                                latch.countDown();
                                                main.post(() -> destroyWeb(webRef));
                                            }
                                            return;
                                        }

                                        finished = true;
                                        htmlRef.set(html);
                                        try {
                                            String cookie = CookieManager.getInstance().getCookie(HOST);
                                            logger("Cookie 同步: null=" + (cookie == null)
                                                    + " clearance=" + (cookie != null && cookie.contains("cf_clearance"))
                                                    + " len=" + (cookie == null ? 0 : cookie.length()));
                                        } catch (Exception ignored) {
                                        }
                                        latch.countDown();
                                        main.post(() -> destroyWeb(webRef));
                                    }
                            );
                        }

                        @Override
                        public void onPageFinished(WebView view, String url) {
                            logger("onPageFinished: " + url);
                            view.evaluateJavascript(
                                    "(function(){return document.title||'';})();",
                                    titleJson -> {
                                        String title = decodeJsString(titleJson);
                                        logger("title=" + title);
                                        if (title != null && title.toLowerCase().contains("just a moment")) {
                                            logger("挑战标题，继续等 CF…");
                                            return;
                                        }
                                        // 给前端/CF 脚本一点时间
                                        main.postDelayed(() -> grabHtml(view, "onPageFinished"), 1500);
                                    }
                            );
                        }

                        @Override
                        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                            logger("WebView error code=" + errorCode + " " + description + " url=" + failingUrl);
                        }
                    });

                    logger("WebView loadUrl: " + targetUrl);
                    webView.loadUrl(targetUrl);
                } catch (Exception e) {
                    logger("创建 WebView 异常: " + e.getMessage());
                    latch.countDown();
                    destroyWeb(webRef);
                }
            });

            try {
                boolean ok = latch.await(WEB_TIMEOUT_SEC, TimeUnit.SECONDS);
                if (!ok) {
                    logger("WebView 超时 " + WEB_TIMEOUT_SEC + "s url=" + targetUrl);
                    main.post(() -> destroyWeb(webRef));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger("WebView 等待被中断");
                main.post(() -> destroyWeb(webRef));
            }

            String html = htmlRef.get();
            logger("getHtmlByWebView 结束 len=" + (html == null ? 0 : html.length())
                    + " challenge=" + isChallenge(html));
            return html == null ? "" : html;
        }
    }

    /** evaluateJavascript 回调是 JSON 字符串，需要解码 */
    private String decodeJsString(String value) {
        if (value == null || "null".equals(value)) return "";
        try {
            Object obj = new JSONTokener(value).nextValue();
            return obj == null ? "" : String.valueOf(obj);
        } catch (Exception e) {
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                return value.substring(1, value.length() - 1)
                        .replace("\\n", "\n")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                        .replace("\\u003C", "<")
                        .replace("\\u003E", ">");
            }
            return value;
        }
    }

    /** 统一入口：域名相关页面全部走 WebView */
    private String get(String targetUrl) {
        logger("请求(WebView): " + targetUrl);
        String html = getHtmlByWebView(targetUrl);
        if (TextUtils.isEmpty(html) || isChallenge(html)) {
            logger("获取失败或仍是挑战页");
            return "";
        }
        logger("业务 HTML 成功 len=" + html.length());
        return html;
    }

    @Override
    public void init(Context context, String extend) {
        try {
            super.init(context, extend);
        } catch (Exception e) {
            logger("super.init: " + e.getMessage());
        }
        if (context != null) {
            appContext = context.getApplicationContext();
        }
        if (appContext == null) {
            try {
                appContext = Init.context();
            } catch (Throwable ignored) {
            }
        }
        logger("初始化 HOST=" + HOST + " context=" + (appContext != null));
        this.unlocked = PasswordGate.ensureUnlocked(context);
        logger("门禁: " + (unlocked ? "通过" : "未通过"));
    }

    @Override
    public String homeContent(boolean filter) {
        if (!unlocked) {
            return Result.get().classes(new ArrayList<Class>()).string();
        }
        List<Class> classes = new ArrayList<>();
        for (Map.Entry<String, String> e : CATEGORY_MAP.entrySet()) {
            classes.add(new Class(e.getKey(), e.getValue()));
        }
        logger("首页分类数: " + classes.size());
        return Result.get().classes(classes).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        if (!unlocked) {
            int page = 1;
            try {
                page = Integer.parseInt(pg);
            } catch (Exception ignored) {
            }
            return Result.get().vod(new ArrayList<Vod>()).page(page, 0, 0, 0).string();
        }
        try {
            int page = Integer.parseInt(pg);
            String encoded = URLEncoder.encode(tid, StandardCharsets.UTF_8.name()).replace("+", "%20");
            String url = HOST + "/search/videos/" + encoded;
            if (page > 1) {
                url += "?page=" + page;
            }
            logger("分类 tid=" + tid + " page=" + page + " → " + url);
            String html = get(url);
            List<Vod> list = parseVideoList(html);
            logger("分类结果数: " + list.size());
            int totalPage = list.size() >= 15 ? page + 1 : page;
            return Result.get().vod(list).page(page, totalPage, 20, 2000).string();
        } catch (Exception e) {
            logger("categoryContent: " + e.getMessage());
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
            logger("详情: " + detailUrl);
            String html = get(detailUrl);

            Vod vod = new Vod();
            vod.setVodId(id);

            String title = "Video " + id;
            Matcher mTitle = Pattern.compile("(?:alt|title)=\"([^\"]{5,})\"", Pattern.CASE_INSENSITIVE)
                    .matcher(html == null ? "" : html);
            if (mTitle.find()) {
                title = mTitle.group(1);
            }
            vod.setVodName(title);

            Matcher mPic = Pattern.compile(
                    "(?:data-src|src)=\"(https://img\\.xszav2\\.com[^\"]+)\"",
                    Pattern.CASE_INSENSITIVE
            ).matcher(html == null ? "" : html);
            if (mPic.find()) {
                vod.setVodPic(mPic.group(1));
            }

            vod.setVodPlayFrom("Xszav2");
            vod.setVodPlayUrl("立即播放$" + id);
            return Result.get().vod(vod).string();
        } catch (Exception e) {
            logger("detailContent: " + e.getMessage());
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
            logger("搜索: " + key + " → " + url);
            String html = get(url);
            List<Vod> list = parseVideoList(html);
            logger("搜索结果数: " + list.size());
            return Result.get().vod(list).string();
        } catch (Exception e) {
            logger("searchContent: " + e.getMessage());
            return Result.get().vod(new ArrayList<Vod>()).string();
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String detailUrl = HOST + "/video/" + id;
            logger("播放解析: " + detailUrl);
            String html = get(detailUrl);
            if (TextUtils.isEmpty(html)) {
                return Result.get().url("").string();
            }

            String playUrl = "";
            Matcher mSrc = Pattern.compile(
                    "<video[^>]+src=[\"']([^\"']+\\.m3u8[^\"']*)[\"']",
                    Pattern.CASE_INSENSITIVE
            ).matcher(html);
            if (mSrc.find()) {
                playUrl = absUrl(mSrc.group(1).trim());
                logger("video[src] m3u8: " + playUrl);
            }
            if (TextUtils.isEmpty(playUrl)) {
                Matcher m2 = Pattern.compile(
                        "(https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*)",
                        Pattern.CASE_INSENSITIVE
                ).matcher(html);
                if (m2.find()) {
                    playUrl = m2.group(1);
                    logger("全局 m3u8: " + playUrl);
                }
            }
            if (TextUtils.isEmpty(playUrl)) {
                Matcher m3 = Pattern.compile(
                        "source\\s*:\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']",
                        Pattern.CASE_INSENSITIVE
                ).matcher(html);
                if (m3.find()) {
                    playUrl = absUrl(m3.group(1).trim());
                    logger("source m3u8: " + playUrl);
                }
            }

            if (TextUtils.isEmpty(playUrl)) {
                logger("未提取到播放地址");
                return Result.get().url("").string();
            }

            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA);
            headers.put("Referer", detailUrl);
            headers.put("Origin", HOST);
            headers.put("Accept", "*/*");
            try {
                String cookie = CookieManager.getInstance().getCookie(HOST);
                if (!TextUtils.isEmpty(cookie)) {
                    headers.put("Cookie", cookie);
                }
            } catch (Exception ignored) {
            }
            return Result.get().url(playUrl).header(headers).string();
        } catch (Exception e) {
            logger("playerContent: " + e.getMessage());
            return Result.get().url("").string();
        }
    }

    private String absUrl(String src) {
        if (TextUtils.isEmpty(src)) return "";
        if (src.startsWith("http")) return src;
        if (src.startsWith("//")) return "https:" + src;
        if (src.startsWith("/")) return HOST + src;
        return HOST + "/" + src;
    }

    private List<Vod> parseVideoList(String html) {
        List<Vod> list = new ArrayList<>();
        if (TextUtils.isEmpty(html)) {
            logger("parseVideoList: html 为空");
            return list;
        }

        Pattern pBlock = Pattern.compile(
                "<div class=\"relative aspect-w-16[\\s\\S]*?</div>\\s*<div class=\"my-2 text-sm truncate\">[\\s\\S]*?</div>",
                Pattern.CASE_INSENSITIVE
        );
        Matcher mBlock = pBlock.matcher(html);
        int blockCount = 0;
        while (mBlock.find()) {
            blockCount++;
            Vod vod = extractFromBlock(mBlock.group(0));
            if (vod != null) list.add(vod);
        }

        if (list.isEmpty()) {
            logger("方法1无结果，启用通用 /video/ 解析");
            Pattern pLink = Pattern.compile(
                    "href=\"[^\"]*?/video/(\\d+)\"[^>]*(?:title|alt)=\"([^\"]+)\"|"
                            + "(?:title|alt)=\"([^\"]+)\"[^>]*href=\"[^\"]*?/video/(\\d+)\"|"
                            + "href=\"[^\"]*?/video/(\\d+)\"",
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
                Matcher mImg = Pattern.compile(
                        "(?:data-src|src)=\"(https://img\\.xszav2\\.com[^\"]*" + id + "[^\"]*)\"",
                        Pattern.CASE_INSENSITIVE
                ).matcher(html);
                if (mImg.find()) {
                    vod.setVodPic(mImg.group(1));
                }
                map.put(id, vod);
            }
            list.addAll(map.values());
        }

        logger("parseVideoList 最终=" + list.size() + " 方法1块=" + blockCount);
        return list;
    }

    private Vod extractFromBlock(String block) {
        Matcher mId = Pattern.compile("/video/(\\d+)", Pattern.CASE_INSENSITIVE).matcher(block);
        if (!mId.find()) return null;
        String id = mId.group(1);

        String title = "Video " + id;
        Matcher mTitle = Pattern.compile("(?:title|alt)=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(block);
        if (mTitle.find()) title = mTitle.group(1);

        String img = "";
        Matcher mImg = Pattern.compile(
                "(?:data-src|src)=\"(https://img\\.xszav2\\.com[^\"]+)\"",
                Pattern.CASE_INSENSITIVE
        ).matcher(block);
        if (mImg.find()) img = mImg.group(1);

        String duration = "";
        Matcher mDur = Pattern.compile("<span[^>]*>\\s*([0-9:]+)\\s*</span>", Pattern.CASE_INSENSITIVE).matcher(block);
        if (mDur.find()) duration = mDur.group(1).trim();

        Vod vod = new Vod();
        vod.setVodId(id);
        vod.setVodName(title);
        vod.setVodPic(img);
        vod.setVodRemarks(duration);
        return vod;
    }
}
