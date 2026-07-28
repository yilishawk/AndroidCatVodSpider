package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

import okhttp3.OkHttpClient;
import okhttp3.Response;

public class Czzyv extends Spider {

    private static final String HOST = "https://czzy.top";
    // 完整桌面 Chrome 149 UA（与指纹匹配）
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36";

    private static final LinkedHashMap<String, String> CATEGORY_MAP = new LinkedHashMap<>();
    static {
        CATEGORY_MAP.put("国产剧", "gcj");
        CATEGORY_MAP.put("美剧", "meijutt");
        CATEGORY_MAP.put("韩剧", "hanjutv");
        CATEGORY_MAP.put("番剧", "fanju");
        CATEGORY_MAP.put("剧场版", "dongmanjuchangban");
        CATEGORY_MAP.put("最新电影", "zuixindianying");
        CATEGORY_MAP.put("豆瓣电影Top250", "dbtop250");
    }

    private static OkHttpClient httpClient;
    private WebView webView;
    private FrameLayout container;
    private volatile boolean cookieReady = false;

    private void logger(String msg) {
        SpiderDebug.log("[Czzyv] " + msg);
    }

    // 只从 WebView 获取 Cookie，不硬编码
    private String getCookie() {
        try {
            String cookie = CookieManager.getInstance().getCookie(HOST);
            if (!TextUtils.isEmpty(cookie)) {
                logger("🍪 动态 Cookie: " + cookie.substring(0, Math.min(80, cookie.length())) + "...");
            }
            return cookie;
        } catch (Exception e) {
            return null;
        }
    }

    // 统一 GET 请求（携带完整指纹头 + 动态 Cookie）
    private String get(String url, String referer) {
        try {
            if (httpClient == null) {
                httpClient = new OkHttpClient.Builder().build();
            }
            okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Accept-Encoding", "gzip, deflate, br")
                    .header("Sec-Ch-Ua", "\"Google Chrome\";v=\"149\", \"Chromium\";v=\"149\", \"Not)A;Brand\";v=\"24\"")
                    .header("Sec-Ch-Ua-Mobile", "?0")
                    .header("Sec-Ch-Ua-Platform", "\"Windows\"")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .header("Upgrade-Insecure-Requests", "1")
                    .header("Cache-Control", "no-cache")
                    .header("Pragma", "no-cache");
            if (!TextUtils.isEmpty(referer)) {
                builder.header("Referer", referer);
            }

            String cookie = getCookie();
            if (!TextUtils.isEmpty(cookie)) {
                builder.header("Cookie", cookie);
            } else {
                logger("⚠️ 当前无 Cookie，请求可能被拦截");
            }

            try (Response response = httpClient.newCall(builder.build()).execute()) {
                int code = response.code();
                String body = response.body() != null ? response.body().string() : "";
                logger("请求 " + url + " → 状态码: " + code + ", 长度: " + body.length());
                if (code != 200) {
                    logger("⚠️ 非200响应，可能被拦截");
                    if (body.length() > 200) logger("前200字符: " + body.substring(0, 200));
                }
                return body;
            }
        } catch (Exception e) {
            logger("请求失败: " + url + " → " + e.getMessage());
            return "";
        }
    }

    private String getWithHeaders(String url, Map<String, String> headers) {
        try {
            if (httpClient == null) {
                httpClient = new OkHttpClient.Builder().build();
            }
            okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(url);
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
            String cookie = getCookie();
            if (!TextUtils.isEmpty(cookie)) builder.header("Cookie", cookie);
            try (Response response = httpClient.newCall(builder.build()).execute()) {
                return response.body() != null ? response.body().string() : "";
            }
        } catch (Exception e) {
            logger("请求失败（自定义headers）: " + url + " → " + e.getMessage());
            return "";
        }
    }

    private boolean isWAFBlocked(String html) {
        if (TextUtils.isEmpty(html)) return true;
        String lower = html.toLowerCase();
        return lower.contains("cf-browser-verification") || lower.contains("just a moment")
                || lower.contains("checking your browser") || lower.contains("challenge-platform")
                || lower.contains("雷池") || lower.contains("验证中心") || lower.contains("waf");
    }

    // ---------- 生命周期 ----------
    @Override
    public void init(Context context, String extend) {
        logger("🚀 初始化...");
        // 清除旧 Cookie
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();

        httpClient = new OkHttpClient.Builder().build();

        // 尝试直接访问
        String testHtml = get(HOST, "");
        if (!isWAFBlocked(testHtml)) {
            logger("✅ 无需验证，直接通过");
            return;
        }

        logger("⚠️ 检测到雷池/CF 盾，启动静默验证...");
        // 最多重试3次
        for (int attempt = 1; attempt <= 3; attempt++) {
            logger("🔄 第 " + attempt + " 次尝试获取 Cookie...");
            CountDownLatch latch = new CountDownLatch(1);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    startSilentWAFVerify(latch);
                } catch (Exception e) {
                    logger("🚨 启动 WebView 失败: " + e.getMessage());
                    latch.countDown();
                }
            });
            try {
                if (!latch.await(35, TimeUnit.SECONDS)) {
                    logger("⏰ 加载超时");
                }
            } catch (InterruptedException ignored) {}

            // 等待 Cookie 就绪
            boolean got = waitForCookie(20);
            if (got) {
                logger("✅ 成功获取 Cookie，验证通过");
                return;
            } else {
                logger("❌ 第 " + attempt + " 次尝试失败，稍后重试");
                destroyWebView();
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }
        logger("❌ 所有尝试均未获取到有效 Cookie，后续请求可能失败");
    }

    // 启动 WebView（增强指纹）
    private void startSilentWAFVerify(CountDownLatch latch) {
        try {
            container = new FrameLayout(Init.context());
            container.setBackgroundColor(android.graphics.Color.TRANSPARENT);

            webView = new WebView(Init.context());
            WebSettings settings = webView.getSettings();

            // ---------- 指纹模拟 ----------
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setUserAgentString(UA);
            settings.setLoadWithOverviewMode(false);
            settings.setUseWideViewPort(true);
            settings.setCacheMode(WebSettings.LOAD_DEFAULT);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setSupportZoom(false);
            settings.setBuiltInZoomControls(false);
            settings.setDisplayZoomControls(false);
            settings.setTextZoom(100);
            settings.setDefaultFontSize(16);
            settings.setDefaultFixedFontSize(16);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setLoadsImagesAutomatically(true);
            settings.setDatabaseEnabled(true);
            settings.setAppCacheEnabled(true);
            settings.setAppCachePath(Init.context().getCacheDir().getAbsolutePath());

            // 真实尺寸 1280x720
            webView.setAlpha(0f);
            webView.setVisibility(View.VISIBLE);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1280, 720);
            webView.setLayoutParams(params);

            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

            // 注入 JS 隐藏 webdriver 属性
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    logger("🌐 页面加载完成: " + url);
                    // 注入 JS 修改 navigator 属性
                    view.evaluateJavascript(
                        "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});" +
                        "Object.defineProperty(navigator, 'plugins', {get: () => [1,2,3,4,5]});" +
                        "Object.defineProperty(navigator, 'languages', {get: () => ['zh-CN','zh','en']});",
                        null
                    );
                    // 延迟刷新以触发挑战
                    view.postDelayed(() -> {
                        logger("🔄 刷新页面触发挑战...");
                        view.reload();
                    }, 2000);
                    // 不释放 latch，由 waitForCookie 控制
                }

                @Override
                public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                    logger("🚨 WebView 错误: " + errorCode + " - " + description);
                    latch.countDown();
                }
            });

            container.addView(webView);
            if (Init.getActivity() != null) {
                ViewGroup root = (ViewGroup) Init.getActivity().getWindow().getDecorView();
                root.addView(container);
                logger("👻 启动静默验证（透明 WebView）→ " + HOST);
            }
            webView.loadUrl(HOST);

            // 超时释放
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (latch.getCount() > 0) {
                    logger("⏰ WebView 加载超时，释放锁");
                    latch.countDown();
                }
            }, 25000);

        } catch (Exception e) {
            logger("🚨 启动 WebView 失败: " + e.getMessage());
            latch.countDown();
        }
    }

    // 轮询等待有效 Cookie
    private boolean waitForCookie(int maxWaitSeconds) {
        try {
            long start = System.currentTimeMillis();
            long end = start + maxWaitSeconds * 1000L;
            while (System.currentTimeMillis() < end) {
                String cookie = getCookie();
                if (!TextUtils.isEmpty(cookie) &&
                    (cookie.contains("sl-challenge-jwt") || cookie.contains("sl_jwt_session") || cookie.contains("cf_clearance"))) {
                    logger("✅ 检测到有效 Cookie: " + cookie.substring(0, Math.min(60, cookie.length())) + "...");
                    cookieReady = true;
                    destroyWebView();
                    return true;
                }
                Thread.sleep(800);
            }
            logger("⏰ 等待 Cookie 超时 (" + maxWaitSeconds + "s)");
            return false;
        } catch (Exception e) {
            logger("等待异常: " + e.getMessage());
            return false;
        }
    }

    private void destroyWebView() {
        try {
            if (webView != null) { webView.destroy(); webView = null; }
            if (container != null) {
                ViewGroup parent = (ViewGroup) container.getParent();
                if (parent != null) parent.removeView(container);
                container = null;
            }
            logger("🔒 WebView 已销毁");
        } catch (Exception ignored) {}
    }

    // ---------- 以下为业务方法，保持不变 ----------
    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        for (Map.Entry<String, String> entry : CATEGORY_MAP.entrySet()) {
            classes.add(new Class(entry.getValue(), entry.getKey()));
        }
        return Result.string(classes, new ArrayList<>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            int page = Integer.parseInt(pg);
            String url = (page == 1) ? HOST + "/" + tid : HOST + "/" + tid + "/page/" + page;
            logger("分类: " + tid + " 第" + page + "页 → " + url);
            String html = get(url, HOST + "/");
            if (TextUtils.isEmpty(html)) return Result.string(new ArrayList<>());
            if (isWAFBlocked(html)) {
                logger("⚠️ 被拦截，等待2秒后重试");
                Thread.sleep(2000);
                html = get(url, HOST + "/");
                if (TextUtils.isEmpty(html)) return Result.string(new ArrayList<>());
            }
            Document doc = Jsoup.parse(html);
            Elements items = doc.select(".bt_img ul li");
            List<Vod> list = new ArrayList<>();
            for (Element item : items) {
                Element link = item.selectFirst("a");
                if (link == null) continue;
                String href = link.attr("href");
                if (TextUtils.isEmpty(href)) continue;
                String vodId = href.replace(HOST, "");
                Element img = link.selectFirst("img");
                String pic = "";
                if (img != null) {
                    pic = img.hasAttr("data-original") ? img.attr("data-original") : img.attr("src");
                }
                Element jidi = link.selectFirst(".jidi span");
                String remarks = jidi != null ? jidi.text() : "";
                Element titleElem = item.selectFirst("h3.dytit a");
                String name = titleElem != null ? titleElem.text() : "";
                Vod vod = new Vod();
                vod.setVodId(vodId);
                vod.setVodName(name);
                vod.setVodPic(pic);
                vod.setVodRemarks(remarks);
                list.add(vod);
            }
            logger("分类 " + tid + " 第" + page + "页，获取到 " + list.size() + " 个视频");
            return Result.string(list);
        } catch (Exception e) {
            logger("分类异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String vodId = ids.get(0);
            String url = vodId.startsWith("http") ? vodId : HOST + vodId;
            logger("详情: " + url);
            String html = get(url, HOST + "/");
            if (TextUtils.isEmpty(html)) return Result.string(new ArrayList<>());
            Document doc = Jsoup.parse(html);
            String name = "", pic = "", area = "", year = "", director = "", actor = "", content = "";
            Element titleElem = doc.selectFirst(".moviedteail_tt h1");
            if (titleElem != null) name = titleElem.text();
            Element imgElem = doc.selectFirst(".dyimg img");
            if (imgElem != null) pic = imgElem.attr("src");
            Element areaElem = doc.selectFirst(".moviedteail_list li:contains(地区：)");
            if (areaElem != null) {
                Elements areaLinks = areaElem.select("a");
                List<String> areas = new ArrayList<>();
                for (Element a : areaLinks) areas.add(a.text());
                area = TextUtils.join(", ", areas);
            }
            Element yearElem = doc.selectFirst(".moviedteail_list li:contains(年份：) a");
            if (yearElem != null) year = yearElem.text();
            Element dirElem = doc.selectFirst(".moviedteail_list li:contains(导演：) span");
            if (dirElem != null) director = dirElem.text();
            Element actorElem = doc.selectFirst(".moviedteail_list li:contains(主演：)");
            if (actorElem != null) {
                Elements actorSpans = actorElem.select("span");
                List<String> actors = new ArrayList<>();
                for (Element span : actorSpans) actors.add(span.text());
                actor = TextUtils.join(", ", actors);
            }
            Element contentElem = doc.selectFirst(".yp_context");
            if (contentElem != null) content = contentElem.text().trim();
            List<String> playUrls = new ArrayList<>();
            Elements playLinks = doc.select(".paly_list_btn a");
            for (Element link : playLinks) {
                playUrls.add(link.text() + "$" + link.attr("href"));
            }
            Vod vod = new Vod();
            vod.setVodId(vodId);
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodYear(year);
            vod.setVodArea(area);
            vod.setVodDirector(director);
            vod.setVodActor(actor);
            vod.setVodContent(content);
            vod.setVodRemarks(year.isEmpty() ? "" : year + "年");
            if (!playUrls.isEmpty()) {
                vod.setVodPlayFrom("橙子影视");
                vod.setVodPlayUrl(TextUtils.join("#", playUrls));
            }
            return Result.string(vod);
        } catch (Exception e) {
            logger("详情异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    private String extractMysvgValue(String html) {
        if (TextUtils.isEmpty(html)) return null;
        Matcher m = Pattern.compile("const\\s+mysvg\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private String extractVideoUrlFromPlayPage(String playUrl) {
        try {
            String html = get(playUrl, HOST + "/");
            if (TextUtils.isEmpty(html)) return null;
            Matcher iframeMatcher = Pattern.compile("<iframe[^>]+src=[\"']([^\"']+)[\"']").matcher(html);
            if (iframeMatcher.find()) {
                String iframeUrl = iframeMatcher.group(1);
                if (iframeUrl.startsWith("/")) iframeUrl = HOST + iframeUrl;
                Map<String, String> iframeHeaders = new HashMap<>();
                iframeHeaders.put("User-Agent", UA);
                iframeHeaders.put("Referer", playUrl);
                iframeHeaders.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                String iframeHtml = getWithHeaders(iframeUrl, iframeHeaders);
                if (TextUtils.isEmpty(iframeHtml)) return null;
                String videoUrl = extractMysvgValue(iframeHtml);
                if (videoUrl != null) logger("从 iframe 提取到 mysvg: " + videoUrl);
                return videoUrl;
            }
            return null;
        } catch (Exception e) {
            logger("提取视频地址失败: " + e.getMessage());
            return null;
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String playUrl = id.startsWith("http") ? id : HOST + id;
            logger("播放页: " + playUrl);
            String videoUrl = extractVideoUrlFromPlayPage(playUrl);
            if (TextUtils.isEmpty(videoUrl)) {
                logger("⚠️ 未提取到视频地址，交由壳子嗅探");
                return Result.get().parse(1).url(playUrl).string();
            }
            logger("✅ 真实视频地址: " + videoUrl);
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA);
            headers.put("Origin", HOST);
            return Result.get().url(videoUrl).header(headers).string();
        } catch (Exception e) {
            logger("播放异常: " + e.getMessage());
            return Result.get().parse(1).url(id).string();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) return Result.string(new ArrayList<>());
        try {
            String url = HOST + "/boss1O1?q=" + URLEncoder.encode(key, "UTF-8");
            logger("搜索请求: " + url);
            String html = get(url, HOST + "/");
            if (TextUtils.isEmpty(html)) return Result.string(new ArrayList<>());
            Document doc = Jsoup.parse(html);
            Elements items = doc.select(".search_list ul li");
            List<Vod> resultList = new ArrayList<>();
            String normalizedKey = normalizeName(key);
            for (Element item : items) {
                Element titleLink = item.selectFirst("h3.dytit a");
                if (titleLink == null) continue;
                String title = titleLink.text().trim();
                if (TextUtils.isEmpty(title) || !normalizeName(title).equals(normalizedKey)) continue;
                logger("找到完全匹配: " + title);
                String detailUrl = titleLink.attr("href");
                Element img = item.selectFirst("img");
                String picUrl = (img != null) ? img.attr("src") : "";
                Vod vod = new Vod();
                vod.setVodId(detailUrl);
                vod.setVodName(title);
                vod.setVodPic(picUrl);
                resultList.add(vod);
                break;
            }
            logger("搜索完成，结果数: " + resultList.size());
            return Result.string(resultList);
        } catch (Exception e) {
            logger("搜索异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.trim();
    }
}
