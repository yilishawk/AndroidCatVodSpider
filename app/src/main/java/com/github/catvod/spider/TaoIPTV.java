package com.github.catvod.spider;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 淘IPTV(taoiptv.com) 组播源抓取。
 * 该站点有 Cloudflare 认证，需要用 WebView 先跑一遍首页的 JS 挑战，
 * 再用同一个 WebView（保留 Cookie）跳转到搜索页拿最终渲染出的 HTML。
 *
 * 用法: http://127.0.0.1:9978/proxy?do=iptvzb            关键词默认"陕西"
 *      http://127.0.0.1:9978/proxy?do=iptvzb&kw=广东      指定关键词
 *
 * 注意：这个类需要在站点配置里注册（跟 ProxyIPTV 一样），
 * 框架加载它当"站点"时才会调用 init(Context, String) 给它 Context，
 * 没有这个 Context 就没法创建 WebView。
 */
public class TaoIPTV extends Spider {

    private static final String HOST = "https://taoiptv.com";
    private static final String DEFAULT_KEYWORD = "陕西";
    private static final String TARGET_CATEGORY = "组播源";
    private static final String TOKEN_PLACEHOLDER = "test12345";

    private static volatile Context appContext;
    private static volatile boolean loading = false;
    private static volatile boolean complete = false;
    private static volatile String cachedTxt = null;

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        appContext = context.getApplicationContext();
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("zb", "组播源"));
        return Result.get().classes(classes).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        return Result.get().string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        return Result.get().string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return Result.get().url(id).parse(0).string();
    }

    /** 供 Proxy.java 调用：do=iptvzb，不阻塞、按需触发抓取 */
    public static String getCache(String keyword) {
        triggerCrawlIfNeeded(keyword);
        return cachedTxt;
    }

    public static boolean isLoading() {
        return loading;
    }

    public static boolean isComplete() {
        return complete;
    }

    private static synchronized void triggerCrawlIfNeeded(String keyword) {
        if (cachedTxt != null || loading) return;
        loading = true;
        complete = false;
        final String kw = (keyword == null || keyword.isEmpty()) ? DEFAULT_KEYWORD : keyword;
        new Thread(() -> {
            try {
                cachedTxt = crawl(kw);
            } catch (Exception e) {
                Proxy.log("❌ TaoIPTV 抓取失败: " + e.getMessage() + "<br><pre>" + Proxy.getStackTrace(e) + "</pre>");
            } finally {
                loading = false;
                complete = true;
            }
        }, "TaoIPTV-Crawler").start();
    }

    private static String crawl(String keyword) throws Exception {
        if (appContext == null) {
            Proxy.log("❌ TaoIPTV 抓取失败: Context 尚未就绪（TaoIPTV 还没被当作站点 init 过）");
            return null;
        }

        String searchUrl = HOST + "/search/?s=" + URLEncoder.encode(keyword, "UTF-8");

        String html = loadWithWebView(searchUrl);
        if (html == null || html.isEmpty()) {
            Proxy.log("❌ TaoIPTV 抓取失败: WebView 未拿到页面内容");
            return null;
        }
        Proxy.log("🔍 TaoIPTV 搜索页(" + keyword + ") html长度=" + html.length());

        Document doc = Jsoup.parse(html);

        String token = null;
        Element tokenEl = doc.selectFirst("#copyToken");
        if (tokenEl != null) token = tokenEl.attr("data-clipboard-text");
        Proxy.log("🔍 TaoIPTV token=" + (token == null || token.isEmpty() ? "未提取到" : "已提取"));

        Pattern subPattern = Pattern.compile("https://taoiptv\\.com/lives/\\d+\\.txt\\?token=\\S+");

        StringBuilder merged = new StringBuilder();
        int matched = 0;
        for (Element post : doc.select("div[id^=post-]")) {
            String category = "";
            Element catEl = post.selectFirst(".entry-category a");
            if (catEl != null) category = catEl.text().trim();
            if (!category.contains(TARGET_CATEGORY)) continue;

            String title = "";
            Element titleEl = post.selectFirst(".entry-title a");
            if (titleEl != null) title = titleEl.text().trim();

            Element summaryEl = post.selectFirst(".entry-summary");
            if (summaryEl == null) continue;
            Matcher m = subPattern.matcher(summaryEl.text());
            if (!m.find()) continue;

            String subUrl = m.group();
            if (token != null && !token.isEmpty()) {
                subUrl = subUrl.replace(TOKEN_PLACEHOLDER, token);
            }
            Proxy.log("🔍 TaoIPTV [" + title + "] 订阅地址=" + subUrl);

            try {
                String content = OkHttp.string(subUrl);
                int len = content == null ? 0 : content.length();
                Proxy.log("🔍 TaoIPTV [" + title + "] 内容长度=" + len);
                if (content != null && !content.isEmpty()) {
                    merged.append(title).append(",#genre#\n");
                    merged.append(content.trim()).append("\n");
                    matched++;
                }
            } catch (Exception e) {
                Proxy.log("❌ TaoIPTV [" + title + "] 订阅内容请求失败: " + e.getMessage());
            }
        }

        Proxy.log("✅ TaoIPTV 抓取完成，组播源命中 " + matched + " 个");
        return merged.length() > 0 ? merged.toString() : null;
    }

    /**
     * 用 WebView 依次访问首页（过 Cloudflare）和搜索页，返回最终页面的 HTML。
     * WebView 必须在主线程创建/操作，这里用 Handler 切到主线程，用 CountDownLatch 桥接成同步调用，
     * 供 crawl() 这个后台线程方法直接 await 拿结果。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private static String loadWithWebView(String searchUrl) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        final String[] result = new String[1];

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                WebView webView = new WebView(appContext);
                WebSettings settings = webView.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

                webView.setWebViewClient(new WebViewClient() {
                    private boolean loadedHome = false;

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        if (!loadedHome) {
                            loadedHome = true;
                            // 首页加载完成后，再等几秒让 Cloudflare 的 JS 挑战跑完，再跳转搜索页
                            view.postDelayed(() -> view.loadUrl(searchUrl), 6000);
                        } else {
                            // 搜索页加载完成，再等一下确保渲染完整，然后取 HTML
                            view.postDelayed(() -> view.evaluateJavascript(
                                    "document.documentElement.outerHTML",
                                    value -> {
                                        result[0] = unescapeJs(value);
                                        latch.countDown();
                                        try {
                                            view.destroy();
                                        } catch (Exception ignored) {}
                                    }), 2000);
                        }
                    }
                });

                webView.loadUrl(HOST + "/");
            } catch (Exception e) {
                Proxy.log("❌ TaoIPTV WebView 初始化失败: " + e.getMessage() + "<br><pre>" + Proxy.getStackTrace(e) + "</pre>");
                latch.countDown();
            }
        });

        latch.await(30, TimeUnit.SECONDS);
        return result[0];
    }

    /** evaluateJavascript 回调的是 JSON 字符串（带转义和首尾引号），这里还原成普通 HTML 字符串 */
    private static String unescapeJs(String value) {
        if (value == null || "null".equals(value)) return null;
        String s = value;
        if (s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "")
                .replace("\\u003C", "<")
                .replace("\\u003E", ">");
    }
}
