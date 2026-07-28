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
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // 分类映射
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
    private boolean cookieReady = false;

    private void logger(String msg) {
        SpiderDebug.log("[Czzyv] " + msg);
    }

    // 获取当前 Cookie（从 android.webkit.CookieManager）
    private String getCookie() {
        try {
            String cookie = CookieManager.getInstance().getCookie(HOST);
            return cookie;
        } catch (Exception e) {
            return null;
        }
    }

    // 统一 GET 请求（自动携带 Cookie）
    private String get(String url, String referer) {
        try {
            if (httpClient == null) {
                httpClient = new OkHttpClient.Builder().build();
            }
            okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9");
            if (!TextUtils.isEmpty(referer)) {
                builder.header("Referer", referer);
            }
            
            // 🔑 手动添加 Cookie（从 WebView 共享）
            String cookie = getCookie();
            if (!TextUtils.isEmpty(cookie)) {
                builder.header("Cookie", cookie);
                logger("🍪 携带 Cookie: " + cookie.substring(0, Math.min(50, cookie.length())) + "...");
            } else {
                logger("⚠️ 无 Cookie");
            }
            
            try (Response response = httpClient.newCall(builder.build()).execute()) {
                int code = response.code();
                String body = response.body() != null ? response.body().string() : "";
                logger("请求 " + url + " → 状态码: " + code + ", 长度: " + body.length());
                if (code != 200) {
                    logger("⚠️ 非200响应，可能被拦截");
                    if (body.length() > 200) {
                        logger("前200字符: " + body.substring(0, 200));
                    }
                }
                return body;
            }
        } catch (Exception e) {
            logger("请求失败: " + url + " → " + e.getMessage());
            return "";
        }
    }

    // 带自定义 Headers 的 GET（用于 iframe 请求）
    private String getWithHeaders(String url, Map<String, String> headers) {
        try {
            if (httpClient == null) {
                httpClient = new OkHttpClient.Builder().build();
            }
            okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(url);
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
            // 手动添加 Cookie
            String cookie = getCookie();
            if (!TextUtils.isEmpty(cookie)) {
                builder.header("Cookie", cookie);
            }
            try (Response response = httpClient.newCall(builder.build()).execute()) {
                return response.body() != null ? response.body().string() : "";
            }
        } catch (Exception e) {
            logger("请求失败（自定义headers）: " + url + " → " + e.getMessage());
            return "";
        }
    }

    // 检测是否被 WAF 拦截
    private boolean isWAFBlocked(String html) {
        if (TextUtils.isEmpty(html)) return true;
        String lowerHtml = html.toLowerCase();
        return lowerHtml.contains("cf-browser-verification")
                || lowerHtml.contains("just a moment")
                || lowerHtml.contains("checking your browser")
                || lowerHtml.contains("challenge-platform")
                || lowerHtml.contains("雷池")
                || lowerHtml.contains("验证中心")
                || lowerHtml.contains("waf");
    }

    // ---------- 生命周期 ----------
    @Override
    public void init(Context context, String extend) {
        logger("🚀 初始化...");

        // 初始化 OkHttpClient
        httpClient = new OkHttpClient.Builder().build();

        // 先尝试直接访问首页
        String testHtml = get(HOST, "");
        if (!isWAFBlocked(testHtml)) {
            logger("✅ 无需验证，直接通过");
            return;
        }

        logger("⚠️ 检测到雷池/CF 盾，启动静默验证...");
        
        // 使用 CountDownLatch 等待验证完成
        CountDownLatch latch = new CountDownLatch(1);
        
        // 切换到主线程执行 WebView 操作
        Init.run(() -> {
            try {
                startSilentWAFVerify(latch);
            } catch (Exception e) {
                logger("🚨 静默验证启动失败: " + e.getMessage());
                e.printStackTrace();
                latch.countDown();
            }
        });

        // 等待 WebView 加载完成（最多 30 秒）
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                logger("⏰ WebView 加载超时（30秒）");
            }
        } catch (InterruptedException e) {
            logger("等待被中断");
        }

        // 等待 Cookie 就绪（轮询检测）
        waitForWAFCookie();
    }

    // 在主线程启动 WebView
    private void startSilentWAFVerify(CountDownLatch latch) {
        try {
            container = new FrameLayout(Init.context());
            container.setBackgroundColor(android.graphics.Color.TRANSPARENT);

            webView = new WebView(Init.context());
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setUserAgentString(UA);
            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);
            settings.setCacheMode(WebSettings.LOAD_DEFAULT);

            webView.setAlpha(0f);
            webView.setVisibility(View.VISIBLE);

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            webView.setLayoutParams(params);

            // 启用 Cookie
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    logger("🌐 页面加载完成: " + url);
                    String cookie = getCookie();
                    logger("📦 当前 Cookie: " + (cookie != null ? cookie : "null"));
                    latch.countDown();
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
            } else {
                logger("⚠️ 无法获取 Activity，使用 WebView 直接加载");
                // 即使没有 Activity，也尝试加载
            }

            webView.loadUrl(HOST);

        } catch (Exception e) {
            logger("🚨 启动 WebView 失败: " + e.getMessage());
            e.printStackTrace();
            latch.countDown();
        }
    }

    // 轮询等待验证 Cookie 就绪
    private void waitForWAFCookie() {
        try {
            long start = System.currentTimeMillis();
            int maxWait = 20; // 最多等 20 秒
            
            while (System.currentTimeMillis() - start < maxWait * 1000) {
                String cookie = getCookie();
                logger("🔍 轮询 Cookie (" + (System.currentTimeMillis() - start) / 1000 + "s): " + (cookie != null ? cookie.substring(0, Math.min(50, cookie.length())) + "..." : "null"));
                
                if (!TextUtils.isEmpty(cookie)) {
                    // 检查是否有雷池验证凭证
                    if (cookie.contains("sl-challenge-jwt") || 
                        cookie.contains("sl_jwt_session") ||
                        cookie.contains("cf_clearance") ||
                        cookie.contains("waf_verify")) {
                        logger("✅ 验证 Cookie 已就绪");
                        cookieReady = true;
                        destroyWebView();
                        return;
                    }
                }
                
                Thread.sleep(500);
            }
            
            logger("⏰ 等待验证 Cookie 超时（" + maxWait + "秒），后续请求可能失败");
            destroyWebView();
            
            // 即使超时，如果已经有 Cookie 也尝试继续
            String finalCookie = getCookie();
            if (!TextUtils.isEmpty(finalCookie)) {
                logger("⚠️ 虽然有 Cookie 但可能未完成验证: " + finalCookie.substring(0, Math.min(50, finalCookie.length())));
            }
            
        } catch (Exception e) {
            logger("等待异常: " + e.getMessage());
        }
    }

    private void destroyWebView() {
        try {
            if (webView != null) {
                webView.destroy();
                webView = null;
            }
            if (container != null) {
                ViewGroup parent = (ViewGroup) container.getParent();
                if (parent != null) {
                    parent.removeView(container);
                }
                container = null;
            }
            logger("🔒 WebView 已销毁");
        } catch (Exception ignored) {}
    }

    // ---------- 首页分类 ----------
    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        for (Map.Entry<String, String> entry : CATEGORY_MAP.entrySet()) {
            classes.add(new Class(entry.getValue(), entry.getKey()));
        }
        return Result.string(classes, new ArrayList<>());
    }

    // ---------- 分类列表 ----------
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

    // ---------- 详情页 ----------
    @Override
    public String detailContent(List<String> ids) {
        try {
            String vodId = ids.get(0);
            String url = vodId.startsWith("http") ? vodId : HOST + vodId;

            logger("详情: " + url);
            String html = get(url, HOST + "/");
            if (TextUtils.isEmpty(html)) return Result.string(new ArrayList<>());

            Document doc = Jsoup.parse(html);

            String name = "";
            Element titleElem = doc.selectFirst(".moviedteail_tt h1");
            if (titleElem != null) name = titleElem.text();

            String pic = "";
            Element imgElem = doc.selectFirst(".dyimg img");
            if (imgElem != null) pic = imgElem.attr("src");

            String area = "";
            Element areaElem = doc.selectFirst(".moviedteail_list li:contains(地区：)");
            if (areaElem != null) {
                Elements areaLinks = areaElem.select("a");
                List<String> areas = new ArrayList<>();
                for (Element a : areaLinks) areas.add(a.text());
                area = TextUtils.join(", ", areas);
            }

            String year = "";
            Element yearElem = doc.selectFirst(".moviedteail_list li:contains(年份：) a");
            if (yearElem != null) year = yearElem.text();

            String director = "";
            Element dirElem = doc.selectFirst(".moviedteail_list li:contains(导演：) span");
            if (dirElem != null) director = dirElem.text();

            String actor = "";
            Element actorElem = doc.selectFirst(".moviedteail_list li:contains(主演：)");
            if (actorElem != null) {
                Elements actorSpans = actorElem.select("span");
                List<String> actors = new ArrayList<>();
                for (Element span : actorSpans) actors.add(span.text());
                actor = TextUtils.join(", ", actors);
            }

            String content = "";
            Element contentElem = doc.selectFirst(".yp_context");
            if (contentElem != null) content = contentElem.text().trim();

            List<String> playUrls = new ArrayList<>();
            Elements playLinks = doc.select(".paly_list_btn a");
            for (Element link : playLinks) {
                String playHref = link.attr("href");
                String episodeName = link.text();
                playUrls.add(episodeName + "$" + playHref);
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

    // ---------- 提取视频地址 ----------
    private String extractMysvgValue(String html) {
        if (TextUtils.isEmpty(html)) return null;
        Pattern mysvgPattern = Pattern.compile("const\\s+mysvg\\s*=\\s*['\"]([^'\"]+)['\"]");
        Matcher mysvgMatcher = mysvgPattern.matcher(html);
        if (mysvgMatcher.find()) {
            return mysvgMatcher.group(1);
        }
        return null;
    }

    private String extractVideoUrlFromPlayPage(String playUrl) {
        try {
            String html = get(playUrl, HOST + "/");
            if (TextUtils.isEmpty(html)) return null;

            Pattern iframePattern = Pattern.compile("<iframe[^>]+src=[\"']([^\"']+)[\"']");
            Matcher iframeMatcher = iframePattern.matcher(html);

            if (iframeMatcher.find()) {
                String iframeUrl = iframeMatcher.group(1);
                logger("iframe URL: " + iframeUrl);

                if (iframeUrl.startsWith("/")) {
                    iframeUrl = HOST + iframeUrl;
                }

                Map<String, String> iframeHeaders = new HashMap<>();
                iframeHeaders.put("User-Agent", UA);
                iframeHeaders.put("Referer", playUrl);
                iframeHeaders.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");

                String iframeHtml = getWithHeaders(iframeUrl, iframeHeaders);
                if (TextUtils.isEmpty(iframeHtml)) {
                    logger("iframe 页面获取失败");
                    return null;
                }

                String videoUrl = extractMysvgValue(iframeHtml);
                if (videoUrl != null) {
                    logger("从 iframe 页面提取到 mysvg 值: " + videoUrl);
                    return videoUrl;
                }
            }
            return null;
        } catch (Exception e) {
            logger("提取视频地址失败: " + e.getMessage());
            return null;
        }
    }

    // ---------- 播放 ----------
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String playUrl = id.startsWith("http") ? id : HOST + id;
            logger("播放页: " + playUrl);

            String videoUrl = extractVideoUrlFromPlayPage(playUrl);

            if (TextUtils.isEmpty(videoUrl)) {
                logger("⚠️ 未提取到视频地址，将启用壳子嗅探");
                return Result.get().parse(1).url(playUrl).string();
            }

            logger("✅ 获取到视频地址: " + videoUrl);

            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA);
            headers.put("Origin", HOST);

            return Result.get().url(videoUrl).header(headers).string();

        } catch (Exception e) {
            logger("播放异常: " + e.getMessage());
            return Result.get().parse(1).url(id).string();
        }
    }

    // ---------- 搜索 ----------
    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) {
            return Result.string(new ArrayList<>());
        }
        try {
            String url = HOST + "/boss1O1?q=" + URLEncoder.encode(key, "UTF-8");
            logger("搜索请求: " + url);
            String html = get(url, HOST + "/");
            if (TextUtils.isEmpty(html)) {
                return Result.string(new ArrayList<>());
            }
            Document doc = Jsoup.parse(html);
            Elements items = doc.select(".search_list ul li");
            List<Vod> resultList = new ArrayList<>();
            String normalizedKey = normalizeName(key);
            for (Element item : items) {
                Element titleLink = item.selectFirst("h3.dytit a");
                if (titleLink == null) {
                    continue;
                }
                String title = titleLink.text().trim();
                if (TextUtils.isEmpty(title) || !normalizeName(title).equals(normalizedKey)) {
                    continue;
                }
                logger("找到完全匹配结果: " + title);
                String detailUrl = titleLink.attr("href");
                Element img = item.selectFirst("img");
                String picUrl = (img != null) ? img.attr("src") : "";
                Vod vod = new Vod();
                vod.setVodId(detailUrl);
                vod.setVodName(title);
                vod.setVodPic(picUrl);
                vod.setVodRemarks("");
                resultList.add(vod);
                break;
            }
            logger("完全匹配搜索完成，返回结果数: " + resultList.size());
            return Result.string(resultList);
        } catch (Exception e) {
            logger("搜索异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    private String normalizeName(String name) {
        if (name == null) return "";
        return name.trim();
    }
}
