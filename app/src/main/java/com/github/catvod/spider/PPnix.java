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
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PPnix
 * WebView 打开域名过 CF 取 Cookie → OkHttp 带 Cookie 访问分类/详情
 * 播放：直链 m3u8，header 带 Cookie + 原逻辑 Referer
 */
public class PPnix extends Spider {

    private String host = "https://www.ppnix.com";
    private String commonUa =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    private static final long CF_TIMEOUT_SEC = 30L;
    private static final long COOKIE_TTL_MS = 20 * 60 * 1000L;

    private Context appContext;
    private boolean unlocked = false;

    private static volatile String cachedCookie = "";
    private static volatile long cachedCookieAt = 0L;
    private static final Object CF_LOCK = new Object();

    private void log(String msg) {
        try {
            Proxy.log("[PPnix] " + msg);
        } catch (Exception e) {
            System.out.println("[PPnix] " + msg);
        }
    }

    private String clip(String s, int max) {
        if (s == null) return "null";
        s = s.replace("\n", " ").replace("\r", " ");
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ==================== Cookie / 请求 ====================

    private boolean hasFreshCookie() {
        return !TextUtils.isEmpty(cachedCookie)
                && (System.currentTimeMillis() - cachedCookieAt) < COOKIE_TTL_MS;
    }

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", commonUa);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.put("Referer", host + "/");
        headers.put("Upgrade-Insecure-Requests", "1");
        if (!TextUtils.isEmpty(cachedCookie)) {
            headers.put("Cookie", cachedCookie);
            log("OkHttp Cookie len=" + cachedCookie.length()
                    + " clearance=" + cachedCookie.contains("cf_clearance"));
        } else {
            log("OkHttp 无 Cookie");
        }
        return headers;
    }

    private boolean isChallenge(String html) {
        if (TextUtils.isEmpty(html)) return true;
        String h = html.toLowerCase();
        if (h.contains("lists-content") || h.contains("product-title") || h.contains("/info/m3u8/")) {
            return false;
        }
        if (h.contains("<title>just a moment</title>")) return true;
        if (h.contains("just a moment") && h.contains("cdn-cgi")) return true;
        if (h.contains("cf-browser-verification")) return true;
        if (h.contains("verify you are human") && h.contains("cdn-cgi")) return true;
        return false;
    }

    private String get(String targetUrl) {
        log("请求: " + targetUrl);
        try {
            if (!hasFreshCookie()) {
                log("Cookie 无效/过期，WebView 取 Cookie…");
                boolean ok = ensureCookieByWebView();
                log("取 Cookie 结果=" + ok + " len=" + (cachedCookie == null ? 0 : cachedCookie.length()));
                if (!ok && TextUtils.isEmpty(cachedCookie)) {
                    log("无可用 Cookie");
                    return "";
                }
            }
            String html = OkHttp.string(targetUrl, getHeaders());
            log("OkHttp len=" + (html == null ? 0 : html.length()));
            if (isChallenge(html)) {
                log("仍像挑战页，强制刷新 Cookie 再试");
                cachedCookie = "";
                cachedCookieAt = 0;
                if (!ensureCookieByWebView()) {
                    log("刷新 Cookie 失败");
                    return "";
                }
                html = OkHttp.string(targetUrl, getHeaders());
                log("重试 OkHttp len=" + (html == null ? 0 : html.length()));
                if (isChallenge(html)) {
                    log("重试后仍是挑战页");
                    return "";
                }
            }
            return html == null ? "" : html;
        } catch (Exception e) {
            log("get 异常: " + e.getMessage());
            return "";
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private boolean ensureCookieByWebView() {
        synchronized (CF_LOCK) {
            if (hasFreshCookie()) {
                log("使用缓存 Cookie");
                return true;
            }
            if (appContext == null) {
                try {
                    appContext = Init.context();
                } catch (Throwable t) {
                    log("Init.context 失败: " + t.getMessage());
                }
            }
            if (appContext == null) {
                log("appContext 为空");
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

                    WebView webView = new WebView(appContext);
                    webRef.set(webView);
                    WebSettings s = webView.getSettings();
                    s.setJavaScriptEnabled(true);
                    s.setDomStorageEnabled(true);
                    s.setDatabaseEnabled(true);
                    s.setUserAgentString(commonUa);
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
                                    String cookie = CookieManager.getInstance().getCookie(host);
                                    log("collect from=" + from
                                            + " null=" + (cookie == null)
                                            + " len=" + (cookie == null ? 0 : cookie.length())
                                            + " clearance=" + (cookie != null && cookie.contains("cf_clearance")));
                                    if (!TextUtils.isEmpty(cookie)
                                            && (cookie.contains("cf_clearance")
                                            || cookie.contains("SITE_TOTAL_ID")
                                            || cookie.length() > 20)) {
                                        done = true;
                                        cachedCookie = cookie;
                                        cachedCookieAt = System.currentTimeMillis();
                                        success.set(true);
                                        log("域名 Cookie 已缓存: " + clip(cookie, 90));
                                        latch.countDown();
                                        destroyWeb(webRef);
                                    }
                                } catch (Exception e) {
                                    log("collect 异常: " + e.getMessage());
                                }
                            }, 1200);
                        }

                        @Override
                        public void onPageFinished(WebView view, String url) {
                            log("WebView onPageFinished: " + url);
                            view.evaluateJavascript(
                                    "(function(){return document.title||'';})();",
                                    raw -> {
                                        String title = decodeJs(raw);
                                        log("域名页 title=" + title);
                                        if (title != null && title.toLowerCase().contains("just a moment")) {
                                            log("仍在 CF 挑战，继续等…");
                                            return;
                                        }
                                        tryCollect("onPageFinished");
                                    }
                            );
                        }

                        @Override
                        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                            log("WebView error " + errorCode + " " + description);
                        }
                    });

                    log("WebView 打开域名: " + host + "/");
                    webView.loadUrl(host + "/");
                } catch (Exception e) {
                    log("创建 WebView 失败: " + e.getMessage());
                    latch.countDown();
                    destroyWeb(webRef);
                }
            });

            try {
                boolean finished = latch.await(CF_TIMEOUT_SEC, TimeUnit.SECONDS);
                if (!finished) {
                    log("WebView 等 Cookie 超时，最后读一次 CookieManager");
                    final CountDownLatch last = new CountDownLatch(1);
                    main.post(() -> {
                        try {
                            String c = CookieManager.getInstance().getCookie(host);
                            log("超时后 Cookie: " + clip(c, 90));
                            if (!TextUtils.isEmpty(c)) {
                                cachedCookie = c;
                                cachedCookieAt = System.currentTimeMillis();
                                success.set(c.contains("cf_clearance")
                                        || c.contains("SITE_TOTAL_ID")
                                        || c.length() > 20);
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
            log("ensureCookieByWebView 结束 ok=" + ok);
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

    // ==================== Spider ====================

    @Override
    public void init(Context context, String extend) throws Exception {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
        if (appContext == null) {
            try {
                appContext = Init.context();
            } catch (Throwable ignored) {
            }
        }
        log("初始化 host=" + host + " context=" + (appContext != null));

        unlocked = PasswordGate.ensureUnlocked(context);
        if (!unlocked) {
            throw new Exception("密码验证未通过，拒绝加载该源");
        }
        ensureCookieByWebView();
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        if (!unlocked) return Result.get().classes(new ArrayList<>()).string();
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("movie", "电影"));
        classes.add(new Class("tv", "电视剧"));
        return Result.get().classes(classes).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) throws Exception {
        if (!unlocked) {
            int page = Integer.parseInt(pg);
            return Result.get().vod(new ArrayList<>()).page(page, page, 0, 0).string();
        }
        int page = Integer.parseInt(pg);
        int pageIndex = page - 1;
        String url = host + "/cn/" + tid + "/---" + pageIndex + "-.html";
        String html = get(url);
        if (TextUtils.isEmpty(html)) {
            return Result.get().vod(new ArrayList<>()).page(page, page, 0, 0).string();
        }

        Document doc = Jsoup.parse(html);
        Elements items = doc.select(".lists-content ul li");
        List<Vod> videos = new ArrayList<>();
        for (Element li : items) {
            Element thumbA = li.selectFirst("a.thumbnail");
            if (thumbA == null) continue;
            String detailHref = thumbA.attr("href");
            if (TextUtils.isEmpty(detailHref)) continue;
            if (!detailHref.startsWith("/")) detailHref = "/" + detailHref;

            Element img = thumbA.selectFirst("img");
            String pic = "";
            if (img != null) {
                pic = img.attr("src");
                if (TextUtils.isEmpty(pic)) pic = img.attr("data-src");
                if (!TextUtils.isEmpty(pic) && pic.startsWith("/")) pic = host + pic;
            }

            Element yearSpan = li.selectFirst(".countrie .orange");
            String remarks = yearSpan != null ? yearSpan.text().trim() : "";

            Element titleA = li.selectFirst("h2 a");
            String name = titleA != null ? titleA.text().trim() : "";
            if (TextUtils.isEmpty(name)) continue;

            Vod vod = new Vod();
            vod.setVodId(detailHref);
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodRemarks(remarks);
            videos.add(vod);
        }

        int count = videos.size() > 0 ? page + 1 : page;
        return Result.get()
                .vod(videos)
                .page(page, count, videos.size(), 0)
                .string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (!unlocked) return Result.error("密码验证未通过");
        if (ids == null || ids.isEmpty()) return Result.error("id 为空");

        String id = ids.get(0);
        String url = id.startsWith("http") ? id : host + id;
        String html = get(url);
        if (TextUtils.isEmpty(html)) {
            return Result.error("请求详情失败");
        }

        Document doc = Jsoup.parse(html);

        Element titleElem = doc.selectFirst("h1.product-title");
        String name = "";
        String year = "";
        if (titleElem != null) {
            String fullText = titleElem.text().trim();
            Matcher m = Pattern.compile("(.+?)\\s*\\((\\d{4})\\)").matcher(fullText);
            if (m.find()) {
                name = m.group(1).trim();
                year = m.group(2);
            } else {
                name = fullText;
            }
        }

        Element picElem = doc.selectFirst(".product-header img.thumb");
        String pic = "";
        if (picElem != null) {
            pic = picElem.attr("src");
            if (!TextUtils.isEmpty(pic) && pic.startsWith("/")) pic = host + pic;
        }

        String director = "";
        String actor = "";
        String area = "";
        String content = "";
        Elements excerpts = doc.select(".product-excerpt");
        for (Element ex : excerpts) {
            String exText = ex.text();
            Element span = ex.selectFirst("span");
            if (span == null) continue;
            if (exText.contains("导演")) {
                Elements links = span.select("a");
                List<String> names = new ArrayList<>();
                for (Element a : links) names.add(a.text());
                director = TextUtils.join(", ", names);
            } else if (exText.contains("主演")) {
                Elements links = span.select("a");
                List<String> names = new ArrayList<>();
                for (Element a : links) names.add(a.text());
                actor = TextUtils.join(", ", names);
            } else if (exText.contains("国家")) {
                Elements links = span.select("a");
                List<String> names = new ArrayList<>();
                for (Element a : links) names.add(a.text());
                area = TextUtils.join(", ", names);
            } else if (exText.contains("简介")) {
                content = span.text().trim();
            }
        }

        String infoid = null;
        List<String> episodeNumbers = new ArrayList<>();
        for (Element script : doc.select("script")) {
            String js = script.html();
            if (js.contains("infoid") && js.contains("m3u8")) {
                Matcher infoidMatcher = Pattern.compile("infoid\\s*=\\s*(\\d+)").matcher(js);
                if (infoidMatcher.find()) {
                    infoid = infoidMatcher.group(1);
                }
                Matcher m3u8Matcher = Pattern.compile("m3u8\\s*=\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(js);
                if (m3u8Matcher.find()) {
                    String arrayContent = m3u8Matcher.group(1);
                    Matcher epMatcher = Pattern.compile("['\"]?(\\d+)['\"]?").matcher(arrayContent);
                    while (epMatcher.find()) {
                        episodeNumbers.add(epMatcher.group(1));
                    }
                }
                break;
            }
        }

        String vodPlayFrom = "";
        String vodPlayUrl = "";
        if (infoid != null && !episodeNumbers.isEmpty()) {
            List<String> urls = new ArrayList<>();
            for (String ep : episodeNumbers) {
                // 相对路径，播放时拼 host；集名用集数
                urls.add(ep + "$/info/m3u8/" + infoid + "/" + ep + ".m3u8");
            }
            vodPlayFrom = "PPnix";
            vodPlayUrl = TextUtils.join("#", urls);
        }

        Vod vod = new Vod();
        vod.setVodId(id);
        vod.setVodName(name);
        vod.setVodPic(pic);
        vod.setVodContent(content);
        vod.setVodPlayFrom(vodPlayFrom);
        vod.setVodPlayUrl(vodPlayUrl);
        vod.setVodDirector(director);
        vod.setVodActor(actor);
        vod.setVodArea(area);
        vod.setVodYear(year);
        vod.setVodRemarks(year.isEmpty() ? "" : year + "年");
        return Result.get().vod(vod).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        if (!unlocked) {
            return Result.get().vod(new ArrayList<>()).page(1, 1, 0, 0).string();
        }
        // 原逻辑未实现搜索
        return Result.get().vod(new ArrayList<>()).page(1, 1, 0, 0).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // 直链，不走本地 proxyM3u8
        String m3u8Url = id.startsWith("http") ? id : host + id;

        // Referer 保持原 Java 样式
        String referer = host + "/";
        Matcher m = Pattern.compile("/info/m3u8/(\\d+)/").matcher(id);
        if (m.find()) {
            referer = host + "/cn/tv/" + m.group(1) + ".html";
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", commonUa);
        headers.put("Referer", referer);
        headers.put("Origin", host);
        headers.put("Accept", "*/*");
        if (!TextUtils.isEmpty(cachedCookie)) {
            headers.put("Cookie", cachedCookie);
        }

        log("播放直链 " + m3u8Url + " Referer=" + referer
                + " cookie=" + (!TextUtils.isEmpty(cachedCookie)));

        return Result.get()
                .url(m3u8Url)
                .header(headers)
                .string();
    }
}
