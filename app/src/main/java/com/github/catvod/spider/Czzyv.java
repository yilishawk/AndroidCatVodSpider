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
import java.util.*;
import java.util.regex.*;

public class Czzyv extends Spider {

    private static final String HOST = "https://czzy.top";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

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
        try { Proxy.log("[Czzyv] " + msg); } catch (Exception ignored) {}
    }

    private Map<String, String> getHeaders(String referer) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", TextUtils.isEmpty(referer) ? HOST + "/" : referer);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        return headers;
    }

    private String get(String url, String referer) {
        try {
            return OkHttp.string(url, getHeaders(referer));
        } catch (Exception e) {
            logger("请求失败: " + url + " → " + e.getMessage());
            return "";
        }
    }

    // ──────────────────────────────────────────────
    // 雷池/CF 静默验证（透明 WebView）
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

    private void silentWAFVerify() {
        try {
            FrameLayout container = new FrameLayout(Init.context());
            container.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            
            WebView webView = new WebView(Init.context());
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setUserAgentString(UA);
            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);
            settings.setCacheMode(WebSettings.LOAD_DEFAULT);
            
            webView.setAlpha(0f);
            webView.setVisibility(View.VISIBLE);
            
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1, 1);
            webView.setLayoutParams(params);
            
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
            
            final android.os.Handler handler = new android.os.Handler();
            Runnable timeoutRunnable = () -> {
                logger("⏰ 雷池验证超时(20s)");
                try {
                    if (webView != null) webView.destroy();
                    if (container != null) {
                        ViewGroup parent = (ViewGroup) container.getParent();
                        if (parent != null) parent.removeView(container);
                    }
                } catch (Exception ignored) {}
            };
            
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    logger("🌐 页面加载完成: " + url);
                    
                    String cookie = CookieManager.getInstance().getCookie(HOST);
                    boolean verified = false;
                    
                    if (!TextUtils.isEmpty(cookie)) {
                        verified = cookie.contains("cf_clearance")
                                || cookie.contains("雷池")
                                || cookie.contains("waf_verify")
                                || cookie.contains("_waf_captcha");
                    }
                    
                    if (!verified && view.getUrl() != null) {
                        String currentUrl = view.getUrl();
                        verified = !currentUrl.contains("challenge") 
                                && !currentUrl.contains("verify")
                                && !currentUrl.contains("waf");
                    }
                    
                    if (verified) {
                        logger("✅ 雷池/CF 静默验证成功！");
                        handler.removeCallbacks(timeoutRunnable);
                        
                        Init.run(() -> {
                            try {
                                Thread.sleep(1000);
                                view.destroy();
                                if (container != null) {
                                    ViewGroup parent = (ViewGroup) container.getParent();
                                    if (parent != null) parent.removeView(container);
                                }
                                logger("🔒 WebView 已销毁");
                            } catch (Exception ignored) {}
                        });
                    } else {
                        logger("⏳ 等待验证完成...");
                    }
                }
                
                @Override
                public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                    logger("🚨 WebView 错误: " + errorCode + " - " + description);
                }
            });
            
            container.addView(webView);
            
            if (Init.getActivity() != null) {
                ViewGroup root = (ViewGroup) Init.getActivity().getWindow().getDecorView();
                root.addView(container);
                logger("👻 启动静默验证（透明 WebView）→ " + HOST);
            }
            
            webView.loadUrl(HOST);
            handler.postDelayed(timeoutRunnable, 20000);
            
        } catch (Exception e) {
            logger("🚨 静默验证失败: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────
    // 提取视频地址
    // ──────────────────────────────────────────────

    /**
     * 提取 const mysvg 的值
     */
    private String extractMysvgValue(String html) {
        if (TextUtils.isEmpty(html)) return null;
        
        Pattern mysvgPattern = Pattern.compile("const\\s+mysvg\\s*=\\s*['\"]([^'\"]+)['\"]");
        Matcher mysvgMatcher = mysvgPattern.matcher(html);
        if (mysvgMatcher.find()) {
            return mysvgMatcher.group(1);
        }
        return null;
    }

    /**
     * 核心方法：先提取 iframe src，访问它，再从返回数据中提取 const mysvg
     */
    private String extractVideoUrlFromPlayPage(String playUrl) {
        try {
            // 第一步：获取播放页 HTML
            String html = get(playUrl, HOST + "/");
            if (TextUtils.isEmpty(html)) return null;
            
            // 第二步：提取 iframe src
            Pattern iframePattern = Pattern.compile("<iframe[^>]+src=[\"']([^\"']+)[\"']");
            Matcher iframeMatcher = iframePattern.matcher(html);
            
            if (iframeMatcher.find()) {
                String iframeUrl = iframeMatcher.group(1);
                logger("iframe URL: " + iframeUrl);
                
                // 相对路径补全
                if (iframeUrl.startsWith("/")) {
                    iframeUrl = HOST + iframeUrl;
                }
                
                // 第三步：访问 iframe URL，携带 Referer（播放页地址）
                Map<String, String> iframeHeaders = new HashMap<>();
                iframeHeaders.put("User-Agent", UA);
                iframeHeaders.put("Referer", playUrl);
                iframeHeaders.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                
                String iframeHtml = OkHttp.string(iframeUrl, iframeHeaders);
                if (TextUtils.isEmpty(iframeHtml)) {
                    logger("iframe 页面获取失败");
                    return null;
                }
                
                // 第四步：从 iframe 返回的数据中提取 const mysvg 的值
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

    // ──────────────────────────────────────────────
    // 生命周期
    // ──────────────────────────────────────────────

    @Override
    public void init(Context context, String extend) {
        logger("🚀 初始化...");
        
        String testHtml = get(HOST, "");
        if (!isWAFBlocked(testHtml)) {
            logger("✅ 无需雷池/CF 验证，直接通过");
            return;
        }
        
        logger("⚠️ 检测到雷池/CF 盾，启动静默验证...");
        silentWAFVerify();
        
        try {
            Thread.sleep(3000);
        } catch (Exception ignored) {}
    }

    // ──────────────────────────────────────────────
    // 首页分类
    // ──────────────────────────────────────────────

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        for (Map.Entry<String, String> entry : CATEGORY_MAP.entrySet()) {
            classes.add(new Class(entry.getValue(), entry.getKey()));
        }
        return Result.string(classes, new ArrayList<>());
    }

    // ──────────────────────────────────────────────
    // 分类列表
    // ──────────────────────────────────────────────

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
            
            return Result.string(list);
        } catch (Exception e) {
            logger("分类异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    // ──────────────────────────────────────────────
    // 详情页
    // ──────────────────────────────────────────────

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

    // ──────────────────────────────────────────────
    // 播放
    // ──────────────────────────────────────────────

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String playUrl = id.startsWith("http") ? id : HOST + id;
            logger("播放页: " + playUrl);
            
            String videoUrl = extractVideoUrlFromPlayPage(playUrl);
            
            if (TextUtils.isEmpty(videoUrl)) {
                logger("未找到视频地址，交由壳子嗅探");
                return Result.get().parse(1).url(playUrl).string();
            }
            
            logger("真实视频地址: " + videoUrl);
            
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA);
            headers.put("Referer", HOST + "/");
            headers.put("Origin", HOST);
            
            return Result.get().url(videoUrl).header(headers).string();
            
        } catch (Exception e) {
            logger("播放异常: " + e.getMessage());
            return Result.get().parse(1).url(id).string();
        }
    }

    // ──────────────────────────────────────────────
    // 搜索
    // ──────────────────────────────────────────────

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
