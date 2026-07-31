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
import com.github.catvod.net.OkHttp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Czzyv extends Spider {

    private static final String HOST = "https://czzy.top";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // 后台驻留的 WebView 实例（用于绕过雷池/CF 并代理请求）
    private static WebView sharedWebView;

    // 分类映射（国产剧排第一）
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

    private void logger(String msg) {
        try {
            Proxy.log("[Czzyv] " + msg);
        } catch (Exception ignored) {
        }
    }

    private Map<String, String> getHeaders(String referer) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", TextUtils.isEmpty(referer) ? HOST + "/" : referer);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");

        // 将 WebView 刷出的 Cookie 实时同步给 OkHttp
        try {
            String cookie = CookieManager.getInstance().getCookie(HOST);
            if (!TextUtils.isEmpty(cookie)) {
                headers.put("Cookie", cookie);
            }
        } catch (Exception ignored) {
        }

        return headers;
    }

    // ──────────────────────────────────────────────
    // 雷池/CF 绕过与请求代理的核心
    // ──────────────────────────────────────────────

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

    /**
     * 初始化共享的后台透明 WebView
     */
    private void initSharedWebView() {
        if (sharedWebView != null) return;

        Init.run(() -> {
            try {
                FrameLayout container = new FrameLayout(Init.context());
                container.setBackgroundColor(android.graphics.Color.TRANSPARENT);

                sharedWebView = new WebView(Init.context());
                WebSettings settings = sharedWebView.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setDatabaseEnabled(true);
                settings.setUserAgentString(UA);
                settings.setLoadWithOverviewMode(true);
                settings.setUseWideViewPort(true);
                settings.setCacheMode(WebSettings.LOAD_DEFAULT);

                sharedWebView.setAlpha(0.01f);
                sharedWebView.setVisibility(View.VISIBLE);
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1, 1);
                sharedWebView.setLayoutParams(params);

                CookieManager cookieManager = CookieManager.getInstance();
                cookieManager.setAcceptCookie(true);
                cookieManager.setAcceptThirdPartyCookies(sharedWebView, true);

                sharedWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                        super.onPageStarted(view, url, favicon);
                        // 抹去 Android 原生 WebView 特征
                        view.evaluateJavascript(
                                "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});", null
                        );
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        logger("🌐 WebView 载入完成: " + url);
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                            cookieManager.flush();
                        }
                    }
                });

                container.addView(sharedWebView);

                if (Init.getActivity() != null) {
                    ViewGroup root = (ViewGroup) Init.getActivity().getWindow().getDecorView();
                    root.addView(container);
                    logger("👻 启动后台透明 WebView 实例");
                    sharedWebView.loadUrl(HOST);
                }
            } catch (Exception e) {
                logger("🚨 初始化 WebView 异常: " + e.getMessage());
            }
        });
    }

    /**
     * 当 OkHttp 被阻断时，直接让 WebView 在后台发起 JS Fetch 请求（加强版）
     */
    private String getViaWebView(String targetUrl, String referer) {
        initSharedWebView();
        final String[] result = {""};
        final CountDownLatch latch = new CountDownLatch(1);

        Init.run(() -> {
            if (sharedWebView == null) {
                logger("WebView 实例为空，无法代理");
                latch.countDown();
                return;
            }

            // 清空旧结果
            sharedWebView.evaluateJavascript("window.androidResult = null;", null);

            String ref = TextUtils.isEmpty(referer) ? HOST + "/" : referer;

            String js = "(function() {" +
                    "  window.androidResult = null;" +
                    "  fetch('" + targetUrl.replace("'", "\\'") + "', {" +
                    "    method: 'GET'," +
                    "    headers: {" +
                    "      'Referer': '" + ref.replace("'", "\\'") + "'," +
                    "      'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'," +
                    "      'Accept-Language': 'zh-CN,zh;q=0.9'" +
                    "    }," +
                    "    credentials: 'include'" +
                    "  })" +
                    "  .then(function(res) { return res.text(); })" +
                    "  .then(function(text) { window.androidResult = text; })" +
                    "  .catch(function(err) { window.androidResult = 'ERROR:' + err; });" +
                    "})();";

            sharedWebView.evaluateJavascript(js, null);

            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            final int maxAttempts = 40; // 40 * 300ms ≈ 12 秒

            Runnable checkRunnable = new Runnable() {
                int attempts = 0;

                @Override
                public void run() {
                    attempts++;
                    if (sharedWebView == null) {
                        latch.countDown();
                        return;
                    }

                    sharedWebView.evaluateJavascript(
                            "(function(){ return window.androidResult; })();",
                            value -> {
                                try {
                                    if (value != null && !value.equals("null") && !value.equals("\"\"") && value.length() > 10) {
                                        String res = value;
                                        if (res.startsWith("\"") && res.endsWith("\"")) {
                                            res = res.substring(1, res.length() - 1);
                                        }
                                        // 更完整的反转义
                                        res = res.replace("\\\"", "\"")
                                                .replace("\\n", "\n")
                                                .replace("\\r", "\r")
                                                .replace("\\t", "\t")
                                                .replace("\\\\", "\\")
                                                .replace("\\/", "/");

                                        if (res.startsWith("ERROR:")) {
                                            logger("WebView fetch 出错: " + res);
                                            result[0] = "";
                                        } else {
                                            result[0] = res;
                                            logger("WebView 成功返回数据，长度: " + res.length());
                                        }
                                        latch.countDown();
                                    } else if (attempts < maxAttempts) {
                                        handler.postDelayed(this, 300);
                                    } else {
                                        logger("⏰ WebView fetch 超时（已等待约12秒）");
                                        latch.countDown();
                                    }
                                } catch (Exception e) {
                                    logger("解析 WebView 返回值异常: " + e.getMessage());
                                    latch.countDown();
                                }
                            }
                    );
                }
            };

            // 稍微延迟启动轮询，给 fetch 一点时间
            handler.postDelayed(checkRunnable, 400);
        });

        try {
            boolean finished = latch.await(15, TimeUnit.SECONDS);
            if (!finished) {
                logger("⏰ CountDownLatch 总超时");
            }
        } catch (InterruptedException ignored) {
        }

        return result[0] != null ? result[0] : "";
    }

    /**
     * 统一网络请求入口：自动检测并无缝切换
     */
    private String get(String url, String referer) {
        String html = "";
        try {
            html = OkHttp.string(url, getHeaders(referer));
        } catch (Exception e) {
            logger("OkHttp 异常: " + e.getMessage());
        }

        if (isWAFBlocked(html)) {
            logger("⚠️ OkHttp 被拦截，启动 WebView JS 代理获取: " + url);
            html = getViaWebView(url, referer);

            if (TextUtils.isEmpty(html)) {
                logger("❌ WebView 代理也返回空数据");
            } else if (isWAFBlocked(html)) {
                logger("❌ WebView 返回的内容仍然是盾页面");
            } else {
                logger("✅ WebView 代理成功，数据长度: " + html.length());
            }
        }
        return html;
    }

    // ──────────────────────────────────────────────
    // 视频播放与地址提取
    // ──────────────────────────────────────────────

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

                String iframeHtml = get(iframeUrl, playUrl);
                if (TextUtils.isEmpty(iframeHtml)) {
                    logger("iframe 页面获取失败");
                    return null;
                }

                String videoUrl = extractMysvgValue(iframeHtml);
                if (videoUrl != null) {
                    logger("提取到 mysvg 真实视频地址: " + videoUrl);
                    return videoUrl;
                }
            }
            return null;
        } catch (Exception e) {
            logger("提取视频地址失败: " + e.getMessage());
            return null;
        }
    }

    // ──────────────────────────────────────────────
    // 生命周期
    // ──────────────────────────────────────────────

    @Override
    public void init(Context context, String extend) {
        logger("🚀 初始化 Spider 插件...");

        // 预载初始化 WebView 引擎
        initSharedWebView();

        String testHtml = get(HOST, "");
        if (!isWAFBlocked(testHtml)) {
            logger("✅ 站点通畅，雷池/CF 验证通过");
        } else {
            logger("⚠️ 收到盾页面拦截，将在后台尝试自动刷新 Session...");
        }
    }

    // ──────────────────────────────────────────────
    // 业务接口（首页/分类/详情/播放/搜索）
    // ──────────────────────────────────────────────

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

            logger("分类请求: " + tid + " 页码: " + page + " → " + url);
            String html = get(url, HOST + "/");
            if (TextUtils.isEmpty(html) || isWAFBlocked(html)) return Result.string(new ArrayList<>());

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

            return Result.string(list);
        } catch (Exception e) {
            logger("分类解析异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String vodId = ids.get(0);
            String url = vodId.startsWith("http") ? vodId : HOST + vodId;

            logger("详情页请求: " + url);
            String html = get(url, HOST + "/");
            if (TextUtils.isEmpty(html) || isWAFBlocked(html)) return Result.string(new ArrayList<>());

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
            logger("详情解析异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String playUrl = id.startsWith("http") ? id : HOST + id;
            logger("播放页解析: " + playUrl);

            String videoUrl = extractVideoUrlFromPlayPage(playUrl);

            if (TextUtils.isEmpty(videoUrl)) {
                logger("未能拦截提取到视频直链，交由播放器嗅探");
                return Result.get().parse(1).url(playUrl).string();
            }

            logger("解析成功，真实视频地址: " + videoUrl);

            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA);
            headers.put("Origin", HOST);

            return Result.get().url(videoUrl).header(headers).string();

        } catch (Exception e) {
            logger("播放处理异常: " + e.getMessage());
            return Result.get().parse(1).url(id).string();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) {
            return Result.string(new ArrayList<>());
        }
        try {
            String url = HOST + "/boss1O1?q=" + URLEncoder.encode(key, "UTF-8");
            logger("搜索请求: " + url);

            String html = get(url, HOST + "/");
            if (TextUtils.isEmpty(html) || isWAFBlocked(html)) {
                logger("搜索页面获取失败或仍被拦截");
                return Result.string(new ArrayList<>());
            }

            logger("搜索页面长度: " + html.length());

            Document doc = Jsoup.parse(html);

            // 尝试多种可能的选择器
            Elements items = doc.select(".search_list ul li");
            if (items.isEmpty()) {
                items = doc.select(".bt_img ul li");
            }
            if (items.isEmpty()) {
                items = doc.select("ul li");
            }

            logger("找到候选条目数: " + items.size());

            List<Vod> resultList = new ArrayList<>();
            String normalizedKey = normalizeName(key).toLowerCase();

            for (Element item : items) {
                Element titleLink = item.selectFirst("h3.dytit a");
                if (titleLink == null) {
                    titleLink = item.selectFirst("h3 a");
                }
                if (titleLink == null) {
                    titleLink = item.selectFirst("a");
                }
                if (titleLink == null) continue;

                String title = titleLink.text().trim();
                if (TextUtils.isEmpty(title)) continue;

                String normTitle = normalizeName(title).toLowerCase();

                // 放宽匹配：包含关系即可
                if (!normTitle.contains(normalizedKey) && !normalizedKey.contains(normTitle)) {
                    continue;
                }

                logger("匹配到: " + title);

                String detailUrl = titleLink.attr("href");
                if (TextUtils.isEmpty(detailUrl)) continue;

                Element img = item.selectFirst("img");
                String picUrl = "";
                if (img != null) {
                    picUrl = img.hasAttr("data-original") ? img.attr("data-original") : img.attr("src");
                }

                Vod vod = new Vod();
                vod.setVodId(detailUrl);
                vod.setVodName(title);
                vod.setVodPic(picUrl);
                vod.setVodRemarks("");
                resultList.add(vod);
            }

            logger("搜索完成，返回匹配结果数: " + resultList.size());
            return Result.string(resultList);
        } catch (Exception e) {
            logger("搜索出现异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    private String normalizeName(String name) {
        if (name == null) return "";
        return name.trim();
    }
}
