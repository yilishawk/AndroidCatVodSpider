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
import com.github.catvod.net.OkHttp;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Xszav2 extends Spider {

    private static final String HOST = "https://en.xszav2.com";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /** WebView 过 CF 最长等待 */
    private static final long CF_TIMEOUT_SEC = 25L;
    /** Cookie 本地缓存有效期（毫秒），避免每次都开 WebView */
    private static final long COOKIE_TTL_MS = 25 * 60 * 1000L;

    private boolean unlocked = false;
    private Context appContext;

    private static volatile String cachedCookie = "";
    private static volatile long cachedCookieAt = 0L;
    private static final Object CF_LOCK = new Object();

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

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
        headers.put("Referer", HOST + "/");
        headers.put("Upgrade-Insecure-Requests", "1");
        String cookie = getValidCookie();
        if (!TextUtils.isEmpty(cookie)) {
            headers.put("Cookie", cookie);
            logger("请求头已带 Cookie，长度=" + cookie.length()
                    + " 含cf_clearance=" + cookie.contains("cf_clearance"));
        } else {
            logger("请求头无 Cookie（尚未过 CF 或缓存为空）");
        }
        return headers;
    }

    private String getValidCookie() {
        if (!TextUtils.isEmpty(cachedCookie)
                && System.currentTimeMillis() - cachedCookieAt < COOKIE_TTL_MS) {
            return cachedCookie;
        }
        return cachedCookie; // 过期仍先试，失败再 WebView 刷新
    }

    private boolean isChallenge(String html) {
        if (TextUtils.isEmpty(html)) return true;
        String h = html.toLowerCase();
        return h.contains("just a moment")
                || h.contains("cf-browser-verification")
                || h.contains("verify you are human")
                || h.contains("challenge-platform")
                || h.contains("performing security verification")
                || h.contains("cdn-cgi/challenge");
    }

    private String get(String targetUrl) {
        logger("请求: " + targetUrl);
        try {
            String html = OkHttp.string(targetUrl, getHeaders());
            if (TextUtils.isEmpty(html)) {
                logger("OkHttp 返回空");
                html = "";
            } else {
                logger("OkHttp 返回长度=" + html.length());
            }

            if (isChallenge(html)) {
                logger("检测到 CF 挑战页，启动 WebView 过验证…");
                boolean ok = ensureClearanceByWebView();
                logger("WebView 过 CF 结果: " + ok + " cookieLen="
                        + (cachedCookie == null ? 0 : cachedCookie.length()));
                if (!ok) {
                    logger("WebView 未能拿到有效 Cookie，放弃本次请求");
                    return "";
                }
                html = OkHttp.string(targetUrl, getHeaders());
                if (TextUtils.isEmpty(html)) {
                    logger("过 CF 后再次请求仍为空");
                    return "";
                }
                logger("过 CF 后再次请求长度=" + html.length());
                if (isChallenge(html)) {
                    logger("过 CF 后仍是挑战页（可能 IP/UA 不一致或验证失败）");
                    cachedCookie = "";
                    cachedCookieAt = 0;
                    return "";
                }
            }

            logger("业务 HTML 成功，长度=" + html.length());
            return html;
        } catch (Exception e) {
            logger("get 异常: " + e.getClass().getSimpleName() + " " + e.getMessage());
            return "";
        }
    }

    /**
     * 主线程创建 WebView 访问首页，等待 Cookie 出现 cf_clearance 且页面不是挑战页。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private boolean ensureClearanceByWebView() {
        synchronized (CF_LOCK) {
            // 并发时可能已被别的请求刷过
            if (!TextUtils.isEmpty(cachedCookie)
                    && System.currentTimeMillis() - cachedCookieAt < COOKIE_TTL_MS
                    && cachedCookie.contains("cf_clearance")) {
                logger("使用内存中已有的 cf_clearance 缓存");
                return true;
            }

            if (appContext == null) {
                try {
                    appContext = Init.context();
                } catch (Throwable t) {
                    logger("无法获取 Application Context: " + t.getMessage());
                }
            }
            if (appContext == null) {
                logger("appContext 为空，无法创建 WebView");
                return false;
            }

            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean success = new AtomicBoolean(false);
            final AtomicReference<WebView> webRef = new AtomicReference<>();
            final Handler main = new Handler(Looper.getMainLooper());

            main.post(() -> {
                WebView webView = null;
                try {
                    CookieManager cm = CookieManager.getInstance();
                    cm.setAcceptCookie(true);

                    webView = new WebView(appContext);
                    webRef.set(webView);
                    WebSettings settings = webView.getSettings();
                    settings.setJavaScriptEnabled(true);
                    settings.setDomStorageEnabled(true);
                    settings.setDatabaseEnabled(true);
                    settings.setUserAgentString(UA);
                    settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        cm.setAcceptThirdPartyCookies(webView, true);
                        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                    }

                    webView.setWebViewClient(new WebViewClient() {
                        private void tryFinish(String from) {
                            try {
                                String cookie = CookieManager.getInstance().getCookie(HOST);
                                logger("onPage 检查 from=" + from
                                        + " cookieNull=" + (cookie == null)
                                        + " hasClearance=" + (cookie != null && cookie.contains("cf_clearance")));
                                if (cookie != null && cookie.contains("cf_clearance")) {
                                    // 再等一下让站点自己的 session 也写上
                                    main.postDelayed(() -> {
                                        String c2 = CookieManager.getInstance().getCookie(HOST);
                                        if (c2 != null && c2.contains("cf_clearance")) {
                                            cachedCookie = c2;
                                            cachedCookieAt = System.currentTimeMillis();
                                            success.set(true);
                                            logger("CF Cookie 已缓存，片段: " + safeCookiePreview(c2));
                                        }
                                        latch.countDown();
                                        destroyWeb(webRef);
                                    }, 1500);
                                }
                            } catch (Exception e) {
                                logger("tryFinish 异常: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onPageFinished(WebView view, String url) {
                            logger("WebView onPageFinished: " + url);
                            view.evaluateJavascript(
                                    "(function(){return document.title||'';})();",
                                    title -> {
                                        logger("页面 title=" + title);
                                        if (title != null && title.toLowerCase().contains("just a moment")) {
                                            logger("仍在 CF 挑战页，继续等待…");
                                            return;
                                        }
                                        tryFinish("onPageFinished");
                                    }
                            );
                        }

                        @Override
                        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                            logger("WebView onReceivedError code=" + errorCode + " desc=" + description);
                        }
                    });

                    logger("WebView loadUrl: " + HOST + "/");
                    webView.loadUrl(HOST + "/");
                } catch (Exception e) {
                    logger("创建 WebView 失败: " + e.getMessage());
                    latch.countDown();
                    destroyWeb(webRef);
                }
            });

            try {
                boolean finished = latch.await(CF_TIMEOUT_SEC, TimeUnit.SECONDS);
                if (!finished) {
                    logger("WebView 等待超时 " + CF_TIMEOUT_SEC + "s");
                    // 超时再读一次 Cookie，有时挑战已过但回调没触发
                    main.post(() -> {
                        try {
                            String c = CookieManager.getInstance().getCookie(HOST);
                            logger("超时后 Cookie: " + safeCookiePreview(c));
                            if (c != null && c.contains("cf_clearance")) {
                                cachedCookie = c;
                                cachedCookieAt = System.currentTimeMillis();
                                success.set(true);
                            }
                        } catch (Exception ignored) {
                        }
                        destroyWeb(webRef);
                    });
                    // 再给一点时间写缓存
                    Thread.sleep(800);
                }
            } catch (InterruptedException e) {
                logger("latch 被中断");
                Thread.currentThread().interrupt();
            }

            boolean ok = success.get()
                    || (!TextUtils.isEmpty(cachedCookie) && cachedCookie.contains("cf_clearance"));
            logger("ensureClearanceByWebView 结束 ok=" + ok);
            return ok;
        }
    }

    private static void destroyWeb(AtomicReference<WebView> ref) {
        WebView w = ref.getAndSet(null);
        if (w == null) return;
        try {
            w.stopLoading();
            w.loadUrl("about:blank");
            w.destroy();
        } catch (Exception ignored) {
        }
    }

    private String safeCookiePreview(String cookie) {
        if (cookie == null) return "null";
        if (cookie.length() <= 80) return cookie;
        return cookie.substring(0, 40) + "..." + cookie.substring(cookie.length() - 20)
                + " (len=" + cookie.length() + ")";
    }

    @Override
    public void init(Context context, String extend) {
        try {
            super.init(context, extend);
        } catch (Exception e) {
            logger("super.init 异常: " + e.getMessage());
        }
        this.appContext = context != null ? context.getApplicationContext() : null;
        if (this.appContext == null) {
            try {
                this.appContext = Init.context();
            } catch (Throwable ignored) {
            }
        }
        logger("初始化 Xszav2 HOST=" + HOST + " context=" + (appContext != null));
        this.unlocked = PasswordGate.ensureUnlocked(context);
        logger("门禁: " + (this.unlocked ? "通过" : "未通过"));
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
            int page = Integer.parseInt(pg);
            return Result.get().vod(new ArrayList<Vod>()).page(page, 0, 0, 0).string();
        }
        try {
            int page = Integer.parseInt(pg);
            String encoded = URLEncoder.encode(tid, StandardCharsets.UTF_8.name()).replace("+", "%20");
            String url = HOST + "/search/videos/" + encoded;
            if (page > 1) url += "?page=" + page;
            logger("分类 tid=" + tid + " page=" + page + " url=" + url);
            String html = get(url);
            List<Vod> list = parseVideoList(html);
            logger("分类结果数: " + list.size());
            int totalPage = list.size() >= 15 ? page + 1 : page;
            return Result.get().vod(list).page(page, totalPage, 20, 2000).string();
        } catch (Exception e) {
            logger("categoryContent 异常: " + e.getMessage());
            return Result.get().vod(new ArrayList<Vod>()).string();
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        if (!unlocked) return Result.get().string();
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
            if (mTitle.find()) title = mTitle.group(1);
            vod.setVodName(title);
            Matcher mPic = Pattern.compile("src=\"(https://img\\.xszav2\\.com[^\"]+)\"", Pattern.CASE_INSENSITIVE)
                    .matcher(html == null ? "" : html);
            if (mPic.find()) vod.setVodPic(mPic.group(1));
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
        if (!unlocked) return Result.get().vod(new ArrayList<Vod>()).string();
        try {
            String encoded = URLEncoder.encode(key, StandardCharsets.UTF_8.name()).replace("+", "%20");
            String url = HOST + "/search/videos/" + encoded;
            logger("搜索: " + key + " → " + url);
            String html = get(url);
            List<Vod> list = parseVideoList(html);
            logger("搜索结果数: " + list.size());
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
            Matcher mSrc = Pattern.compile("<video[^>]+src=[\"']([^\"']+\\.m3u8)[\"']", Pattern.CASE_INSENSITIVE)
                    .matcher(html == null ? "" : html);
            if (mSrc.find()) {
                String src = mSrc.group(1).trim();
                if (src.startsWith("http")) playUrl = src;
                else if (src.startsWith("/")) playUrl = HOST + src;
                else playUrl = HOST + "/" + src;
                logger("匹配到 m3u8: " + playUrl);
            }
            if (TextUtils.isEmpty(playUrl)) {
                // 兜底：全局找 .m3u8
                Matcher m2 = Pattern.compile("(https?://[^\"'\\s]+\\.m3u8[^\"'\\s]*)", Pattern.CASE_INSENSITIVE)
                        .matcher(html == null ? "" : html);
                if (m2.find()) {
                    playUrl = m2.group(1);
                    logger("兜底匹配 m3u8: " + playUrl);
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
            String cookie = getValidCookie();
            if (!TextUtils.isEmpty(cookie)) headers.put("Cookie", cookie);
            return Result.get().url(playUrl).header(headers).string();
        } catch (Exception e) {
            logger("playerContent 异常: " + e.getMessage());
            return Result.get().url("").string();
        }
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
        int count = 0;
        while (mBlock.find()) {
            count++;
            Vod vod = extractFromBlock(mBlock.group(0));
            if (vod != null) list.add(vod);
        }
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
                String id = null, title = null;
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
                Matcher mImg = Pattern.compile("data-src=\"(https://img\\.xszav2\\.com[^\"]*" + id + "[^\"]*)\"",
                        Pattern.CASE_INSENSITIVE).matcher(html);
                if (mImg.find()) vod.setVodPic(mImg.group(1));
                map.put(id, vod);
            }
            list.addAll(map.values());
        }
        logger("parseVideoList 最终=" + list.size() + " 方法1块=" + count);
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
        Matcher mImg = Pattern.compile("data-src=\"(https://img\\.xszav2\\.com[^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(block);
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
