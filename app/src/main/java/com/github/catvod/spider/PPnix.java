package com.github.catvod.spider;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkResult;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;

public class PPnix extends Spider {

    private static final String HOST      = "https://www.ppnix.com";
    private static final String UA        = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";
    private static final String PLAY_FROM = "PPnix";

    private AlertDialog cfDialog;

    // 本地HTTP 服务器相关
    private static ServerSocket localServer = null;
    private static Map<String, String> m3u8Cache = new ConcurrentHashMap<>();
    private static int localPort = 0;
    private static boolean serverStarted = false;

    // ──────────────────────────────────────────────
    // 工具方法
    // ──────────────────────────────────────────────

    private void logger(String msg) {
        try { Proxy.log(msg); } catch (Exception ignored) {}
    }

    private Map<String, String> baseHeaders(String referer) {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", TextUtils.isEmpty(referer) ? HOST + "/" : referer);
        return h;
    }

    private String get(String url, String referer) {
        try {
            return KaiGeNet.smartRequest(HOST, "get", url, null, baseHeaders(referer)).getBody();
        } catch (Exception e) {
            logger("🚨 [请求失败] " + url + " → " + e.getMessage());
            return "";
        }
    }

    /** 判断响应是否被 CF 拦截 */
    private boolean isCFBlocked(String html) {
        if (TextUtils.isEmpty(html)) return true;
        return html.contains("cf-browser-verification")
            || html.contains("Just a moment")
            || html.contains("Checking your browser")
            || html.contains("challenge-platform");
    }

    /** 从 CookieManager 取出全部 Cookie 并注入 KaiGeNet */
    private boolean injectCFCookie() {
        try {
            String cookie = CookieManager.getInstance().getCookie(HOST);
            if (!TextUtils.isEmpty(cookie) && cookie.contains("cf_clearance")) {
                KaiGeNet.putCookie(HOST, cookie);
                logger("🍪 [CF] Cookie 注入成功: " + cookie);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ──────────────────────────────────────────────
    // 生命周期
    // ──────────────────────────────────────────────

    @Override
    public void init(Context context, String extend) {
        logger("🚀 [PPnix] 初始化...");
        startLocalHttpServer(); // 启动本地服务器

        if (injectCFCookie()) {
            logger("✅ [PPnix] 已有 CF Cookie，跳过 WebView");
            return;
        }

        String testHtml = get(HOST, "");
        if (!isCFBlocked(testHtml)) {
            logger("✅ [PPnix] 无需 CF 验证，直接通过");
            return;
        }

        logger("⚠️ [PPnix] 检测到 CF 盾，弹出 WebView 验证...");
        Init.run(this::showCFWebView);
    }

    // ──────────────────────────────────────────────
    // 本地 HTTP 服务器（提供修改后的 M3U8）
    // ──────────────────────────────────────────────

    private synchronized void startLocalHttpServer() {
        if (serverStarted) return;
        serverStarted = true;
        new Thread(() -> {
            try {
                localServer = new ServerSocket(0); // 自动分配端口
                localPort = localServer.getLocalPort();
                logger("✅ [本地服务器] 启动成功，端口: " + localPort);
                while (true) {
                    try (Socket client = localServer.accept()) {
                        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                        String requestLine = in.readLine();
                        if (requestLine == null) continue;
                        String[] parts = requestLine.split(" ");
                        if (parts.length < 2) continue;
                        String path = parts[1];
                        
                        if (path.startsWith("/m3u8/")) {
                            String id = path.substring("/m3u8/".length());
                            String content = m3u8Cache.get(id);
                            if (content != null) {
                                byte[] data = content.getBytes("UTF-8");
                                OutputStream out = client.getOutputStream();
                                out.write(("HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: application/vnd.apple.mpegurl\r\n" +
                                        "Content-Length: " + data.length + "\r\n" +
                                        "Connection: close\r\n\r\n").getBytes());
                                out.write(data);
                                out.flush();
                                logger("📤 [M3U8服务] 提供内容，id=" + id);
                            } else {
                                client.getOutputStream().write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
                            }
                        } else {
                            client.getOutputStream().write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
                        }
                    } catch (Exception e) {
                        logger("🚨 [本地服务器] 请求处理异常: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                logger("🚨 [本地服务器] 启动失败: " + e.getMessage());
                serverStarted = false;
            }
        }).start();
    }

    /** 注册 M3U8 内容，返回本地访问 URL */
    private String registerM3u8(String content) {
        if (!serverStarted || localPort == 0) {
            logger("⚠️ [注册] 本地服务器未就绪");
            return null;
        }
        String id = System.currentTimeMillis() + "_" + new Random().nextInt(10000);
        m3u8Cache.put(id, content);
        // 简单清理：保留最近 50 个
        if (m3u8Cache.size() > 50) {
            String first = m3u8Cache.keySet().iterator().next();
            m3u8Cache.remove(first);
        }
        return "http://127.0.0.1:" + localPort + "/m3u8/" + id;
    }

    // ──────────────────────────────────────────────
    // CF WebView 弹窗（保持原有，可隐藏但暂不修改）
    // ──────────────────────────────────────────────

    private void showCFWebView() {
        try {
            WebView webView = new WebView(Init.context());
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setUserAgentString(UA);
            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);

            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    if (injectCFCookie()) {
                        logger("✅ [CF WebView] 自动通过 CF 验证");
                        Init.run(() -> dismissCFDialog());
                    }
                }
            });

            webView.loadUrl(HOST);

            FrameLayout frame = new FrameLayout(Init.context());
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
            frame.addView(webView, lp);

            cfDialog = new AlertDialog.Builder(Init.getActivity())
                .setTitle("请完成 CF 人机验证")
                .setView(frame)
                .setPositiveButton("完成", (d, w) -> {
                    injectCFCookie();
                    d.dismiss();
                })
                .setOnDismissListener(d -> {
                    try { webView.destroy(); } catch (Exception ignored) {}
                })
                .create();

            cfDialog.show();
            cfDialog.getWindow().setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            cfDialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);

        } catch (Exception e) {
            logger("🚨 [CF WebView] 弹窗失败: " + e.getMessage());
        }
    }

    private void dismissCFDialog() {
        try {
            if (cfDialog != null && cfDialog.isShowing()) cfDialog.dismiss();
        } catch (Exception ignored) {}
    }

    // ──────────────────────────────────────────────
    // 首页分类
    // ──────────────────────────────────────────────

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONArray classes = new JSONArray();
            classes.put(makeClass("电影",   "movie"));
            classes.put(makeClass("电视剧", "tv"));

            JSONObject result = new JSONObject();
            result.put("class", classes);
            result.put("list",  new JSONArray());
            return result.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private JSONObject makeClass(String name, String id) throws Exception {
        JSONObject o = new JSONObject();
        o.put("type_name", name);
        o.put("type_id",   id);
        return o;
    }

    @Override
    public String homeVideoContent() {
        return "{\"list\":[]}";
    }

    // ──────────────────────────────────────────────
    // 分类列表
    // ──────────────────────────────────────────────

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            int page      = Integer.parseInt(pg);
            int pageIndex = page - 1;
            String url    = HOST + "/cn/" + tid + "/---" + pageIndex + "-.html";

            logger("📂 [分类] " + tid + " 第" + page + "页 → " + url);
            String html = get(url, HOST + "/");
            if (TextUtils.isEmpty(html)) return "{\"list\":[]}";

            Document doc  = Jsoup.parse(html);
            JSONArray list = new JSONArray();

            for (Element li : doc.select(".lists-content ul li")) {
                Element thumbA = li.selectFirst("a.thumbnail");
                if (thumbA == null) continue;

                String vodId = thumbA.attr("href");
                if (TextUtils.isEmpty(vodId)) continue;
                if (!vodId.startsWith("/")) vodId = "/" + vodId;

                Element img = thumbA.selectFirst("img");
                String pic  = img != null ? (img.hasAttr("src") ? img.attr("src") : img.attr("data-src")) : "";

                Element yearSpan = li.selectFirst(".countrie .orange");
                String remarks   = yearSpan != null ? yearSpan.text().trim() : "";

                Element titleA = li.selectFirst("h2 a");
                String name    = titleA != null ? titleA.text().trim() : "";

                JSONObject vod = new JSONObject();
                vod.put("vod_id",      vodId);
                vod.put("vod_name",    name);
                vod.put("vod_pic",     pic);
                vod.put("vod_remarks", remarks);
                list.put(vod);
            }

            logger("✅ [分类] 获取到 " + list.length() + " 条");
            JSONObject result = new JSONObject();
            result.put("list", list);
            result.put("page", page);
            return result.toString();
        } catch (Exception e) {
            logger("🚨 [分类异常] " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    // ──────────────────────────────────────────────
    // 详情页
    // ──────────────────────────────────────────────

    @Override
    public String detailContent(List<String> ids) {
        try {
            String id  = ids.get(0);
            String url = id.startsWith("http") ? id : HOST + id;

            logger("📄 [详情] → " + url);
            String html = get(url, HOST + "/");
            if (TextUtils.isEmpty(html)) return "{\"list\":[]}";

            Document doc = Jsoup.parse(html);

            String name = "", year = "";
            Element titleElem = doc.selectFirst("h1.product-title");
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

            String pic = "";
            Element picElem = doc.selectFirst(".product-header img.thumb");
            if (picElem != null) {
                pic = picElem.attr("src");
                if (pic.startsWith("/")) pic = HOST + pic;
            }

            String director = extractLinks(doc, "导演");
            String actor    = extractLinks(doc, "主演");
            String area     = extractLinks(doc, "国家");
            String content  = "";
            Element descElem = doc.selectFirst(".product-excerpt:contains(简介) span");
            if (descElem != null) content = descElem.text().trim();

            String scriptText = "";
            for (Element script : doc.select("script")) {
                String s = script.html();
                if (s.contains("infoid") && s.contains("m3u8")) {
                    scriptText = s;
                    break;
                }
            }

            String   infoid   = "";
            String[] episodes = new String[0];

            if (!TextUtils.isEmpty(scriptText)) {
                Matcher mId = Pattern.compile("infoid\\s*=\\s*(\\d+)").matcher(scriptText);
                if (mId.find()) infoid = mId.group(1);

                Matcher mArr = Pattern.compile("m3u8\\s*=\\s*\\[(.*?)]").matcher(scriptText);
                if (mArr.find()) {
                    String arrContent = mArr.group(1);
                    List<String> epList = new ArrayList<>();
                    Matcher mEp = Pattern.compile("['\"]?(\\d+)['\"]?").matcher(arrContent);
                    while (mEp.find()) epList.add(mEp.group(1));
                    episodes = epList.toArray(new String[0]);
                }
            }

            String playFrom = "";
            String playUrl  = "";
            if (!TextUtils.isEmpty(infoid) && episodes.length > 0) {
                String categoryType = id.contains("/movie/") ? "movie" : "tv";
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < episodes.length; i++) {
                    if (i > 0) sb.append("#");
                    sb.append("第").append(episodes[i]).append("集")
                      .append("$")
                      .append("/info/m3u8/").append(infoid).append("/").append(episodes[i]).append(".m3u8?type=").append(categoryType);
                }
                playFrom = PLAY_FROM;
                playUrl  = sb.toString();
                logger("✅ [详情] infoid=" + infoid + " 共" + episodes.length + "集");
            } else {
                logger("⚠️ [详情] 未找到播放源");
            }

            JSONObject vod = new JSONObject();
            vod.put("vod_id",        id);
            vod.put("vod_name",      name);
            vod.put("vod_pic",       pic);
            vod.put("vod_year",      year);
            vod.put("vod_area",      area);
            vod.put("vod_director",  director);
            vod.put("vod_actor",     actor);
            vod.put("vod_content",   content);
            vod.put("vod_remarks",   TextUtils.isEmpty(year) ? "" : year + "年");
            vod.put("vod_play_from", playFrom);
            vod.put("vod_play_url",  playUrl);

            JSONArray list = new JSONArray();
            list.put(vod);
            JSONObject result = new JSONObject();
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            logger("🚨 [详情异常] " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    private String extractLinks(Document doc, String label) {
        try {
            Element span = doc.selectFirst(".product-excerpt:contains(" + label + ") span");
            if (span == null) return "";
            List<String> texts = new ArrayList<>();
            for (Element a : span.select("a")) texts.add(a.text().trim());
            return TextUtils.join(", ", texts);
        } catch (Exception e) {
            return "";
        }
    }

    // ──────────────────────────────────────────────
    // 播放（核心：下载 M3U8 -> 替换 TS 域名 -> 本地 HTTP 服务）
    // ──────────────────────────────────────────────

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String m3u8Url = id.startsWith("http") ? id : HOST + id;

            // 构建 Referer
            String referer = HOST + "/";
            Matcher mInfo = Pattern.compile("/info/m3u8/(\\d+)/").matcher(id);
            if (mInfo.find()) {
                String categoryType = id.contains("type=movie") ? "movie" : "tv";
                referer = HOST + "/cn/" + categoryType + "/" + mInfo.group(1) + ".html";
            }

            logger("▶️ [播放] 原始 M3U8: " + m3u8Url);
            logger("🔗 [播放] Referer: " + referer);

            // 下载并修改 M3U8 内容
            String modifiedContent = downloadAndModifyM3u8(m3u8Url, referer);
            String finalUrl;

            if (modifiedContent != null) {
                finalUrl = registerM3u8(modifiedContent);
                if (finalUrl != null) {
                    logger("✅ [播放] 使用本地代理: " + finalUrl);
                } else {
                    finalUrl = m3u8Url;
                    logger("⚠️ [播放] 本地代理失效，回退原始 URL");
                }
            } else {
                finalUrl = m3u8Url;
                logger("⚠️ [播放] M3U8 修改失败，使用原始 URL");
            }

            // 构造请求头（用于播放器请求 M3U8，但对于本地 URL 其实不需要，但保留无害）
            JSONObject headersObj = new JSONObject();
            headersObj.put("User-Agent", UA);
            headersObj.put("Accept", "*/*");
            headersObj.put("Accept-Language", "zh-CN,zh;q=0.9");
            headersObj.put("Accept-Encoding", "gzip, deflate, br");
            headersObj.put("Connection", "keep-alive");
            headersObj.put("Cache-Control", "no-cache");
            headersObj.put("Referer", referer);
            headersObj.put("Origin", HOST);
            headersObj.put("origin", HOST);
            headersObj.put("Sec-Fetch-Site", "same-origin");
            headersObj.put("Sec-Fetch-Mode", "cors");
            headersObj.put("Sec-Fetch-Dest", "empty");

            // Cookie 注入（对 TS 分片请求有效）
            try {
                String cookie = CookieManager.getInstance().getCookie(HOST);
                if (!TextUtils.isEmpty(cookie)) {
                    headersObj.put("Cookie", cookie);
                    headersObj.put("cookie", cookie);
                    logger("🍪 [播放] Cookie 已注入");
                } else {
                    logger("⚠️ [播放] 无 Cookie");
                }
            } catch (Exception e) {
                logger("🚨 [播放] Cookie 异常: " + e.getMessage());
            }

            JSONObject result = new JSONObject();
            result.put("parse", 0);
            result.put("url", finalUrl);
            result.put("header", headersObj.toString());
            return result.toString();

        } catch (Exception e) {
            logger("🚨 [播放异常] " + e.getMessage());
            e.printStackTrace();
            return "{}";
        }
    }

    /**
     * 下载 M3U8 文件并替换 TS 分片域名
     * ipfs.ppnix.com -> 随机数字.ppnix.com
     */
    private String downloadAndModifyM3u8(String m3u8Url, String referer) {
        try {
            String content = get(m3u8Url, referer);
            if (TextUtils.isEmpty(content)) {
                logger("⚠️ [M3U8] 下载失败");
                return null;
            }

            logger("📥 [M3U8] 原始内容长度: " + content.length());
            String[] lines = content.split("\n");
            StringBuilder modified = new StringBuilder();
            Random random = new Random();
            int replaceCount = 0;

            for (String line : lines) {
                if (line.startsWith("#")) {
                    modified.append(line).append("\n");
                } else if (line.trim().startsWith("http")) {
                    String newLine = line;
                    if (line.contains("ipfs.ppnix.com")) {
                        int num = random.nextInt(16) + 1;
                        newLine = line.replace("ipfs.ppnix.com", num + ".ppnix.com");
                        replaceCount++;
                        if (replaceCount <= 5) {
                            logger("🔀 [域名替换] " + line.substring(0, Math.min(80, line.length())) + " -> " + num + ".ppnix.com");
                        }
                    }
                    modified.append(newLine).append("\n");
                } else {
                    modified.append(line).append("\n");
                }
            }
            logger("✅ [M3U8] 完成，共替换 " + replaceCount + " 个 TS 域名");
            return modified.toString();
        } catch (Exception e) {
            logger("🚨 [M3U8] 处理异常: " + e.getMessage());
            return null;
        }
    }

    // ──────────────────────────────────────────────
    // 搜索
    // ──────────────────────────────────────────────

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String url  = HOST + "/search.php?searchword=" + java.net.URLEncoder.encode(key, "UTF-8");
            logger("🔍 [搜索] → " + url);
            String html = get(url, HOST + "/");
            if (TextUtils.isEmpty(html)) return "{\"list\":[]}";

            Document doc  = Jsoup.parse(html);
            JSONArray list = new JSONArray();

            for (Element li : doc.select(".lists-content ul li")) {
                Element thumbA = li.selectFirst("a.thumbnail");
                if (thumbA == null) continue;

                String vodId = thumbA.attr("href");
                if (!vodId.startsWith("/")) vodId = "/" + vodId;

                Element img = thumbA.selectFirst("img");
                String pic  = img != null ? (img.hasAttr("src") ? img.attr("src") : img.attr("data-src")) : "";

                Element titleA = li.selectFirst("h2 a");
                String name    = titleA != null ? titleA.text().trim() : "";

                Element yearSpan = li.selectFirst(".countrie .orange");
                String remarks   = yearSpan != null ? yearSpan.text().trim() : "";

                JSONObject vod = new JSONObject();
                vod.put("vod_id",      vodId);
                vod.put("vod_name",    name);
                vod.put("vod_pic",     pic);
                vod.put("vod_remarks", remarks);
                list.put(vod);
            }

            logger("✅ [搜索] 共 " + list.length() + " 条结果");
            JSONObject result = new JSONObject();
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            logger("🚨 [搜索异常] " + e.getMessage());
            return "{\"list\":[]}";
        }
    }
}
