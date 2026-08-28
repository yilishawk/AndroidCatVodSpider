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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Xszav2
 * 流程：WebView 打开域名过 CF → 取出 Cookie → OkHttp 带 Cookie 访问分类/搜索/详情/播放
 */
public class Xszav2 extends Spider {

    private static final String HOST = "https://en.xszav2.com";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final long CF_TIMEOUT_SEC = 30L;
    /** Cookie 缓存时间，避免每个分类都开 WebView */
    private static final long COOKIE_TTL_MS = 20 * 60 * 1000L;

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

    // ==================== Cookie / CF ====================

    private boolean hasFreshCookie() {
        return !TextUtils.isEmpty(cachedCookie)
                && (System.currentTimeMillis() - cachedCookieAt) < COOKIE_TTL_MS;
    }

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.put("Referer", HOST + "/");
        headers.put("Upgrade-Insecure-Requests", "1");
        if (!TextUtils.isEmpty(cachedCookie)) {
            headers.put("Cookie", cachedCookie);
            logger("OkHttp Cookie len=" + cachedCookie.length()
                    + " clearance=" + cachedCookie.contains("cf_clearance")
                    + " hks=" + cachedCookie.contains("_xsz_hks")
                    + " session=" + cachedCookie.contains("xszav-session"));
        } else {
            logger("OkHttp 无 Cookie");
        }
        return headers;
    }

    /** 严格判断挑战页，避免 challenge-platform 误伤正常页 */
    private boolean isChallenge(String html) {
        if (TextUtils.isEmpty(html)) return true;
        String h = html.toLowerCase();
        if (h.contains("/video/") || h.contains("img.xszav2.com")) return false;
        if (h.contains("<title>just a moment</title>")) return true;
        if (h.contains("just a moment") && h.contains("cdn-cgi")) return true;
        if (h.contains("cf-browser-verification")) return true;
        if (h.contains("verify you are human") && h.contains("cdn-cgi")) return true;
        return false;
    }

    /**
     * 业务请求：确保已有会话 Cookie → OkHttp 访问分类等 URL
     */
    private String get(String targetUrl) {
        logger("请求: " + targetUrl);
        try {
            if (!hasFreshCookie()) {
                logger("Cookie 无效/过期，WebView 访问域名取 Cookie…");
                boolean ok = ensureCookieByWebView();
                logger("WebView 取 Cookie 结果=" + ok + " len="
                        + (cachedCookie == null ? 0 : cachedCookie.length()));
                if (!ok && TextUtils.isEmpty(cachedCookie)) {
                    logger("无可用 Cookie，放弃");
                    return "";
                }
            }

            String html = OkHttp.string(targetUrl, getHeaders());
            logger("OkHttp 返回 len=" + (html == null ? 0 : html.length()));

            if (isChallenge(html)) {
                logger("OkHttp 仍像挑战页，强制 WebView 刷新 Cookie 再试一次");
                cachedCookie = "";
                cachedCookieAt = 0;
                if (!ensureCookieByWebView()) {
                    logger("刷新 Cookie 失败");
                    return "";
                }
                html = OkHttp.string(targetUrl, getHeaders());
                logger("重试 OkHttp len=" + (html == null ? 0 : html.length()));
                if (isChallenge(html)) {
                    logger("重试后仍是挑战页");
                    return "";
                }
            }

            logger("业务页成功 len=" + html.length());
            return html;
        } catch (Exception e) {
            logger("get 异常: " + e.getMessage());
            return "";
        }
    }

    /**
     * 仅打开 HOST 域名，用 WebView 过 CF，把 CookieManager 里的 Cookie 缓存下来。
     * 成功条件：拿到任意站点 Cookie，且页面 title 不是 Just a moment
     * （不强制必须有 cf_clearance，你日志里 WebView 已进站但可能没有 clearance）
     */
    @SuppressLint("SetJavaScriptEnabled")
    private boolean ensureCookieByWebView() {
        synchronized (CF_LOCK) {
            if (hasFreshCookie()) {
                logger("使用缓存 Cookie");
                return true;
            }
            if (appContext == null) {
                try {
                    appContext = Init.context();
                } catch (Throwable t) {
                    logger("Init.context 失败: " + t.getMessage());
                }
            }
            if (appContext == null) {
                logger("appContext 为空");
                return false;
            }

            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean success = new AtomicBoolean(false);
            final AtomicReference<WebView> webRef = new AtomicReference<>();
            final Handler main = new Handler(Looper.getMainLooper());

            main.post(() -> {
                try {
                    CookieManager cm = CookieManager.getInstance();
                    cm.setAcceptCookie(true);
                    // 可选：清掉旧 CF 状态再走一遍
                    // cm.removeAllCookies(null);
                    // cm.flush();

                    WebView webView = new WebView(appContext);
                    webRef.set(webView);
                    WebSettings s = webView.getSettings();
                    s.setJavaScriptEnabled(true);
                    s.setDomStorageEnabled(true);
                    s.setDatabaseEnabled(true);
                    s.setUserAgentString(UA);
                    s.setCacheMode(WebSettings.LOAD_DEFAULT);
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        cm.setAcceptThirdPartyCookies(webView, true);
                        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                    }

                    webView.setWebViewClient(new WebViewClient() {
                        private boolean done = false;

                        private void tryCollect(String from) {
                            if (done) return;
                            main.postDelayed(() -> {
                                if (done) return;
                                try {
                                    String cookie = CookieManager.getInstance().getCookie(HOST);
                                    logger("collect from=" + from
                                            + " cookieNull=" + (cookie == null)
                                            + " len=" + (cookie == null ? 0 : cookie.length())
                                            + " clearance=" + (cookie != null && cookie.contains("cf_clearance"))
                                            + " hks=" + (cookie != null && cookie.contains("_xsz_hks")));

                                    // 有站点 Cookie 即可（不强制 cf_clearance）
                                    if (!TextUtils.isEmpty(cookie)
                                            && (cookie.contains("_xsz_hks")
                                            || cookie.contains("xszav-session")
                                            || cookie.contains("cf_clearance")
                                            || cookie.contains("XSRF-TOKEN"))) {
                                        done = true;
                                        cachedCookie = cookie;
                                        cachedCookieAt = System.currentTimeMillis();
                                        success.set(true);
                                        logger("域名 Cookie 已缓存: " + preview(cookie));
                                        latch.countDown();
                                        destroyWeb(webRef);
                                    }
                                } catch (Exception e) {
                                    logger("collect 异常: " + e.getMessage());
                                }
                            }, 1200);
                        }

                        @Override
                        public void onPageFinished(WebView view, String url) {
                            logger("WebView onPageFinished: " + url);
                            view.evaluateJavascript(
                                    "(function(){return document.title||'';})();",
                                    raw -> {
                                        String title = decodeJs(raw);
                                        logger("域名页 title=" + title);
                                        if (title != null && title.toLowerCase().contains("just a moment")) {
                                            logger("仍在 CF 挑战，继续等…");
                                            return;
                                        }
                                        // 已是真实站点标题 → 收 Cookie
                                        tryCollect("onPageFinished");
                                    }
                            );
                        }

                        @Override
                        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                            logger("WebView error " + errorCode + " " + description);
                        }
                    });

                    logger("WebView 打开域名: " + HOST + "/");
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
                    logger("WebView 等 Cookie 超时，尝试最后读一次 CookieManager");
                    final CountDownLatch last = new CountDownLatch(1);
                    main.post(() -> {
                        try {
                            String c = CookieManager.getInstance().getCookie(HOST);
                            logger("超时后 Cookie: " + preview(c));
                            if (!TextUtils.isEmpty(c)) {
                                cachedCookie = c;
                                cachedCookieAt = System.currentTimeMillis();
                                // 超时也尽量用上（你之前超时后已有 _xsz_hks）
                                success.set(c.contains("_xsz_hks")
                                        || c.contains("cf_clearance")
                                        || c.contains("xszav-session"));
                            }
                        } catch (Exception ignored) {
                        }
                        destroyWeb(webRef);
                        last.countDown();
                    });
                    try {
                        last.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            boolean ok = success.get() || hasFreshCookie();
            logger("ensureCookieByWebView 结束 ok=" + ok);
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

    private String decodeJs(String value) {
        if (value == null || "null".equals(value)) return "";
        try {
            Object o = new JSONTokener(value).nextValue();
            return o == null ? "" : String.valueOf(o);
        } catch (Exception e) {
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                return value.substring(1, value.length() - 1);
            }
            return value;
        }
    }

    private String preview(String cookie) {
        if (cookie == null) return "null";
        if (cookie.length() <= 90) return cookie;
        return cookie.substring(0, 40) + "..." + cookie.substring(cookie.length() - 20)
                + " (len=" + cookie.length() + ")";
    }

    // ==================== Spider 生命周期 ====================

    @Override
    public void init(Context context, String extend) {
        try {
            super.init(context, extend);
        } catch (Exception e) {
            logger("super.init: " + e.getMessage());
        }
        if (context != null) appContext = context.getApplicationContext();
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
            if (page > 1) url += "?page=" + page;
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
            Matcher mPic = Pattern.compile("(?:data-src|src)=\"(https://img\\.xszav2\\.com[^\"]+)\"",
                    Pattern.CASE_INSENSITIVE).matcher(html == null ? "" : html);
            if (mPic.find()) vod.setVodPic(mPic.group(1));
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
        if (!unlocked) return Result.get().vod(new ArrayList<Vod>()).string();
        try {
            String encoded = URLEncoder.encode(key, StandardCharsets.UTF_8.name()).replace("+", "%20");
            String url = HOST + "/search/videos/" + encoded;
            logger("搜索: " + key);
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
            String playUrl = "";
            Matcher m1 = Pattern.compile("<video[^>]+src=[\"']([^\"']+\\.m3u8[^\"']*)[\"']",
                    Pattern.CASE_INSENSITIVE).matcher(html == null ? "" : html);
            if (m1.find()) playUrl = absUrl(m1.group(1).trim());
            if (TextUtils.isEmpty(playUrl)) {
                Matcher m2 = Pattern.compile("(https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*)",
                        Pattern.CASE_INSENSITIVE).matcher(html == null ? "" : html);
                if (m2.find()) playUrl = m2.group(1);
            }
            if (TextUtils.isEmpty(playUrl)) {
                logger("未找到 m3u8");
                return Result.get().url("").string();
            }
            logger("播放地址: " + playUrl);
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA);
            headers.put("Referer", detailUrl);
            headers.put("Origin", HOST);
            headers.put("Accept", "*/*");
            if (!TextUtils.isEmpty(cachedCookie)) headers.put("Cookie", cachedCookie);
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

    // ==================== 列表解析 ====================

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
        int blocks = 0;
        while (mBlock.find()) {
            blocks++;
            Vod vod = extractFromBlock(mBlock.group(0));
            if (vod != null) list.add(vod);
        }
        if (list.isEmpty()) {
            logger("方法1无结果，通用解析 /video/");
            Pattern pLink = Pattern.compile(
                    "href=\"[^\"]*?/video/(\\d+)\"[^>]*(?:title|alt)=\"([^\"]+)\"|"
                            + "(?:title|alt)=\"([^\"]+)\"[^>]*href=\"[^\"]*?/video/(\\d+)\"|"
                            + "href=\"[^\"]*?/video/(\\d+)\"",
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
                Matcher mImg = Pattern.compile(
                        "(?:data-src|src)=\"(https://img\\.xszav2\\.com[^\"]*" + id + "[^\"]*)\"",
                        Pattern.CASE_INSENSITIVE).matcher(html);
                if (mImg.find()) vod.setVodPic(mImg.group(1));
                map.put(id, vod);
            }
            list.addAll(map.values());
        }
        logger("parseVideoList 最终=" + list.size() + " 块=" + blocks);
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
        Matcher mImg = Pattern.compile("(?:data-src|src)=\"(https://img\\.xszav2\\.com[^\"]+)\"",
                Pattern.CASE_INSENSITIVE).matcher(block);
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
