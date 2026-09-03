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
 * PPnix
 * WebView 过盾取 Cookie → OkHttp 合并 Set-Cookie → 分类/详情
 * 播放：直链 m3u8（无本地代理），Referer 保持 /cn/tv/{id}.html
 */
public class PPnix extends Spider {

    private static final String HOST = "https://www.ppnix.com";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    private static final long CF_TIMEOUT_SEC = 45L;
    private static final long COOKIE_TTL_MS = 20 * 60 * 1000L;

    private Context appContext;

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

    // ==================== Cookie 工具 ====================

    private boolean hasFreshCookie() {
        return !TextUtils.isEmpty(cachedCookie)
                && (System.currentTimeMillis() - cachedCookieAt) < COOKIE_TTL_MS;
    }

    private boolean cookieReadyForPlay() {
        return !TextUtils.isEmpty(cachedCookie)
                && (cachedCookie.contains("SITE_TOTAL_ID")
                || cachedCookie.contains("cf_clearance"));
    }

    private String dedupeCookie(String raw) {
        if (TextUtils.isEmpty(raw)) return "";
        Map<String, String> map = new LinkedHashMap<>();
        for (String part : raw.split(";")) {
            String p = part.trim();
            if (p.isEmpty() || !p.contains("=")) continue;
            int i = p.indexOf('=');
            String k = p.substring(0, i).trim();
            String v = p.substring(i + 1).trim();
            if (!k.isEmpty()) map.put(k, v);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        return sb.toString();
    }

    private String readCookieManager(String pageUrl) {
        StringBuilder sb = new StringBuilder();
        try {
            CookieManager cm = CookieManager.getInstance();
            String[] urls = new String[]{
                    HOST,
                    HOST + "/",
                    pageUrl,
                    HOST + "/cn/tv/",
                    HOST + "/cn/movie/",
                    HOST + "/cn/tv/---0-.html"
            };
            for (String u : urls) {
                if (TextUtils.isEmpty(u)) continue;
                String c = cm.getCookie(u);
                if (!TextUtils.isEmpty(c)) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(c);
                }
            }
        } catch (Exception e) {
            log("readCookieManager 异常 " + e.getMessage());
        }
        return dedupeCookie(sb.toString());
    }

    private void saveCookie(String cookie, String from) {
        String merged = dedupeCookie(cachedCookie + "; " + cookie);
        if (TextUtils.isEmpty(merged)) return;
        cachedCookie = merged;
        cachedCookieAt = System.currentTimeMillis();
        log("保存 Cookie from=" + from
                + " len=" + merged.length()
                + " SITE=" + merged.contains("SITE_TOTAL_ID")
                + " cf=" + merged.contains("cf_clearance")
                + " preview=" + clip(merged, 100));
    }

    private void mergeSetCookie(okhttp3.Response resp) {
        if (resp == null) return;
        List<String> list = resp.headers("Set-Cookie");
        if (list == null || list.isEmpty()) return;
        StringBuilder extra = new StringBuilder();
        for (String sc : list) {
            if (TextUtils.isEmpty(sc)) continue;
            String one = sc.split(";")[0].trim();
            if (one.isEmpty()) continue;
            if (extra.length() > 0) extra.append("; ");
            extra.append(one);
            log("Set-Cookie ← " + one);
        }
        if (extra.length() > 0) saveCookie(extra.toString(), "Set-Cookie");
    }

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Referer", HOST + "/");
        headers.put("Upgrade-Insecure-Requests", "1");
        headers.put("sec-ch-ua", "\"Not=A?Brand\";v=\"99\", \"Google Chrome\";v=\"151\", \"Chromium\";v=\"151\"");
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        if (!TextUtils.isEmpty(cachedCookie)) {
            headers.put("Cookie", cachedCookie);
        }
        return headers;
    }

    private boolean isChallenge(String html) {
        if (TextUtils.isEmpty(html)) return true;
        String h = html.toLowerCase();
        if (h.contains("lists-content") || h.contains("product-title") || h.contains("infoid")) {
            return false;
        }
        if (h.contains("<title>just a moment</title>")) return true;
        if (h.contains("just a moment") && h.contains("cdn-cgi")) return true;
        if (h.contains("cf-browser-verification")) return true;
        if (h.contains("verify you are human") && h.contains("cdn-cgi")) return true;
        return false;
    }

    private String httpGet(String url) {
        log("GET " + url);
        try {
            if (!hasFreshCookie()) {
                ensureCookieByWebView();
            }
            okhttp3.Response resp = OkHttp.newCall(url, getHeaders());
            if (resp == null) {
                log("响应 null");
                return "";
            }
            mergeSetCookie(resp);
            String html = resp.body() != null ? resp.body().string() : "";
            resp.close();
            log("GET len=" + html.length()
                    + " SITE=" + cachedCookie.contains("SITE_TOTAL_ID")
                    + " cf=" + cachedCookie.contains("cf_clearance"));

            if (isChallenge(html)) {
                log("挑战页，刷新 WebView Cookie");
                cachedCookie = "";
                cachedCookieAt = 0;
                if (!ensureCookieByWebView()) return "";
                resp = OkHttp.newCall(url, getHeaders());
                if (resp == null) return "";
                mergeSetCookie(resp);
                html = resp.body() != null ? resp.body().string() : "";
                resp.close();
                if (isChallenge(html)) {
                    log("重试后仍是挑战页");
                    return "";
                }
            }
            return html;
        } catch (Exception e) {
            log("httpGet 异常 " + e.getMessage());
            return "";
        }
    }

    // ==================== WebView ====================

    @SuppressLint("SetJavaScriptEnabled")
    private boolean ensureCookieByWebView() {
        synchronized (CF_LOCK) {
            if (hasFreshCookie() && cookieReadyForPlay()) {
                log("复用可用 Cookie");
                return true;
            }
            if (appContext == null) {
                try {
                    appContext = Init.context();
                } catch (Throwable t) {
                    log("Init.context 失败 " + t.getMessage());
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
                    try {
                        cm.flush();
                    } catch (Throwable ignored) {
                    }

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
                        private int stage = 0; // 0 首页 → 1 业务页

                        private void collectAndMaybeFinish(WebView view, String from) {
                            main.postDelayed(() -> {
                                try {
                                    String cmCookie = readCookieManager(HOST + "/");
                                    view.evaluateJavascript(
                                            "(function(){return document.cookie||'';})();",
                                            raw -> {
                                                String jsCookie = decodeJs(raw);
                                                String merged = dedupeCookie(cmCookie + "; " + jsCookie);
                                                log("collect " + from
                                                        + " len=" + merged.length()
                                                        + " SITE=" + merged.contains("SITE_TOTAL_ID")
                                                        + " cf=" + merged.contains("cf_clearance")
                                                        + " " + clip(merged, 100));
                                                if (!TextUtils.isEmpty(merged)) {
                                                    saveCookie(merged, from);
                                                    if (cookieReadyForPlay() || stage >= 1) {
                                                        success.set(!TextUtils.isEmpty(cachedCookie));
                                                        latch.countDown();
                                                        destroyWeb(webRef);
                                                    }
                                                }
                                            }
                                    );
                                } catch (Exception e) {
                                    log("collect 异常 " + e.getMessage());
                                }
                            }, 1500);
                        }

                        @Override
                        public void onPageFinished(WebView view, String url) {
                            log("WebView finished stage=" + stage + " url=" + url);
                            view.evaluateJavascript(
                                    "(function(){return document.title||'';})();",
                                    raw -> {
                                        String title = decodeJs(raw);
                                        log("title=" + title);
                                        if (title != null && title.toLowerCase().contains("just a moment")) {
                                            log("CF 挑战中，继续等待…");
                                            // 挑战中稍后再次采集，不立刻跳转
                                            main.postDelayed(() -> {
                                                if (stage == 0) {
                                                    collectAndMaybeFinish(view, "cf-wait");
                                                }
                                            }, 5000);
                                            return;
                                        }
                                        if (stage == 0) {
                                            stage = 1;
                                            log("打开业务页补 Cookie");
                                            view.loadUrl(HOST + "/cn/tv/---0-.html");
                                            return;
                                        }
                                        collectAndMaybeFinish(view, "biz");
                                    }
                            );
                        }

                        @Override
                        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                            log("WebView error " + errorCode + " " + description);
                        }
                    });

                    log("WebView 打开 " + HOST + "/");
                    webView.loadUrl(HOST + "/");
                } catch (Exception e) {
                    log("WebView 创建失败 " + e.getMessage());
                    latch.countDown();
                    destroyWeb(webRef);
                }
            });

            try {
                boolean finished = latch.await(CF_TIMEOUT_SEC, TimeUnit.SECONDS);
                if (!finished) {
                    log("WebView 超时，最后读 CookieManager");
                    final CountDownLatch last = new CountDownLatch(1);
                    main.post(() -> {
                        try {
                            String c = readCookieManager(HOST + "/");
                            if (!TextUtils.isEmpty(c)) {
                                saveCookie(c, "timeout");
                                success.set(true);
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
            log("ensureCookieByWebView 结束 ok=" + ok
                    + " SITE=" + cachedCookie.contains("SITE_TOTAL_ID")
                    + " cf=" + cachedCookie.contains("cf_clearance"));
            return ok;
        }
    }

    /** 播放前打开 Referer 页，尽量补 SITE_TOTAL_ID */
    @SuppressLint("SetJavaScriptEnabled")
    private void ensureCookieOnPage(String pageUrl) {
        if (appContext == null) return;
        synchronized (CF_LOCK) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<WebView> webRef = new AtomicReference<>();
            final Handler main = new Handler(Looper.getMainLooper());
            main.post(() -> {
                try {
                    CookieManager.getInstance().setAcceptCookie(true);
                    WebView webView = new WebView(appContext);
                    webRef.set(webView);
                    WebSettings s = webView.getSettings();
                    s.setJavaScriptEnabled(true);
                    s.setDomStorageEnabled(true);
                    s.setUserAgentString(UA);
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
                    }
                    webView.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(WebView view, String url) {
                            main.postDelayed(() -> {
                                try {
                                    String cm = readCookieManager(pageUrl);
                                    view.evaluateJavascript(
                                            "(function(){return document.cookie||'';})();",
                                            raw -> {
                                                String js = decodeJs(raw);
                                                saveCookie(dedupeCookie(cm + "; " + js), "play-ref");
                                                latch.countDown();
                                                destroyWeb(webRef);
                                            }
                                    );
                                } catch (Exception e) {
                                    latch.countDown();
                                    destroyWeb(webRef);
                                }
                            }, 1200);
                        }
                    });
                    log("播放前 WebView " + pageUrl);
                    webView.loadUrl(pageUrl);
                } catch (Exception e) {
                    log("播放前 WebView 失败 " + e.getMessage());
                    latch.countDown();
                    destroyWeb(webRef);
                }
            });
            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // 再用 OkHttp 打一次 Referer，合并 Set-Cookie
            try {
                okhttp3.Response resp = OkHttp.newCall(pageUrl, getHeaders());
                if (resp != null) {
                    mergeSetCookie(resp);
                    if (resp.body() != null) resp.body().close();
                    resp.close();
                }
            } catch (Exception e) {
                log("播放前 OkHttp Referer 失败 " + e.getMessage());
            }
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
    public void init(Context context, String extend) {
        try {
            super.init(context, extend);
        } catch (Exception ignored) {
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
        log("init context=" + (appContext != null));
        ensureCookieByWebView();
    }

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("movie", "电影"));
        classes.add(new Class("tv", "电视剧"));
        return Result.get().classes(classes).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) throws Exception {
        int page = Integer.parseInt(pg);
        int pageIndex = page - 1;
        String url = HOST + "/cn/" + tid + "/---" + pageIndex + "-.html";
        String html = httpGet(url);
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
                if (!TextUtils.isEmpty(pic) && pic.startsWith("/")) pic = HOST + pic;
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

        int count = videos.isEmpty() ? page : page + 1;
        return Result.get().vod(videos).page(page, count, videos.size(), 0).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return Result.error("id 为空");
        String id = ids.get(0);
        String url = id.startsWith("http") ? id : HOST + id;
        String html = httpGet(url);
        if (TextUtils.isEmpty(html)) return Result.error("请求详情失败");

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
            if (!TextUtils.isEmpty(pic) && pic.startsWith("/")) pic = HOST + pic;
        }

        String director = "";
        String actor = "";
        String area = "";
        String content = "";
        for (Element ex : doc.select(".product-excerpt")) {
            String exText = ex.text();
            Element span = ex.selectFirst("span");
            if (span == null) continue;
            if (exText.contains("导演")) {
                List<String> names = new ArrayList<>();
                for (Element a : span.select("a")) names.add(a.text());
                director = TextUtils.join(", ", names);
            } else if (exText.contains("主演")) {
                List<String> names = new ArrayList<>();
                for (Element a : span.select("a")) names.add(a.text());
                actor = TextUtils.join(", ", names);
            } else if (exText.contains("国家")) {
                List<String> names = new ArrayList<>();
                for (Element a : span.select("a")) names.add(a.text());
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
                if (infoidMatcher.find()) infoid = infoidMatcher.group(1);
                Matcher m3u8Matcher = Pattern.compile("m3u8\\s*=\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(js);
                if (m3u8Matcher.find()) {
                    Matcher epMatcher = Pattern.compile("['\"]?(\\d+)['\"]?").matcher(m3u8Matcher.group(1));
                    while (epMatcher.find()) episodeNumbers.add(epMatcher.group(1));
                }
                break;
            }
        }

        String vodPlayFrom = "";
        String vodPlayUrl = "";
        if (infoid != null && !episodeNumbers.isEmpty()) {
            List<String> urls = new ArrayList<>();
            for (String ep : episodeNumbers) {
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
    public String searchContent(String key, boolean quick) {
        return Result.get().vod(new ArrayList<>()).page(1, 1, 0, 0).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String m3u8Url = id.startsWith("http") ? id : HOST + id;

        // Referer 保持原逻辑
        String referer = HOST + "/";
        Matcher m = Pattern.compile("/info/m3u8/(\\d+)/").matcher(id);
        if (m.find()) {
            referer = HOST + "/cn/tv/" + m.group(1) + ".html";
        }

        if (!cookieReadyForPlay()) {
            log("播放前 Cookie 不足，WebView 补全");
            ensureCookieByWebView();
        }
        ensureCookieOnPage(referer);

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", referer);
        headers.put("Origin", HOST);
        headers.put("Accept", "*/*");
        if (!TextUtils.isEmpty(cachedCookie)) {
            headers.put("Cookie", cachedCookie);
        }

        log("播放直链 " + m3u8Url
                + " SITE=" + cachedCookie.contains("SITE_TOTAL_ID")
                + " cf=" + cachedCookie.contains("cf_clearance"));

        return Result.get().url(m3u8Url).header(headers).string();
    }
}
