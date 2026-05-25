package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PPnix 影视 (ppnix.com)
 * 播放流程：
 *   1. 下载原始 m3u8
 *   2. 把 m3u8 里的 ipfs.ppnix.com 替换成随机 {1-16}.ppnix.com
 *   3. 把 TS 片段地址改写成本地代理地址
 *   4. 本地代理转发 TS 请求（带上 Referer/UA）
 */
public class PPnix extends Spider {

    private static final String HOST      = "https://www.ppnix.com";
    private static final String UA        = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // ── CF Cookie ──
    private static volatile String cfCookie = "";

    // ── 本地代理服务器 ──
    private static volatile int     localPort   = 0;
    private static volatile boolean serverReady = false;
    private static final Object     serverLock  = new Object();
    // 缓存：key → m3u8内容 或 TS真实URL
    private static final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    // ──────────────────────────────────────────────
    // 工具
    // ──────────────────────────────────────────────

    private Map<String, String> baseHeaders(String referer) {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer",    TextUtils.isEmpty(referer) ? HOST + "/" : referer);
        h.put("Origin",     HOST);
        // ✅ 每次请求都带上 CF Cookie
        if (!TextUtils.isEmpty(cfCookie)) h.put("Cookie", cfCookie);
        return h;
    }

    private void logger(String msg) {
        try { Proxy.log(msg); } catch (Exception ignored) {}
    }

    // ──────────────────────────────────────────────
    // CF WebView 处理
    // ──────────────────────────────────────────────

    // ──────────────────────────────────────────────
    // WebView 获取 Cookie
    // ──────────────────────────────────────────────

    private String getCookieFromManager() {
        try {
            String cookie = CookieManager.getInstance().getCookie(HOST);
            return TextUtils.isEmpty(cookie) ? "" : cookie;
        } catch (Exception e) { return ""; }
    }

    /** 静默 WebView：后台加载首页，页面加载完自动提取 Cookie，不弹任何窗口 */
    private void loadWebViewForCookie() {
        try {
            WebView webView = new WebView(Init.context());
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setUserAgentString(UA);

            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    String cookie = getCookieFromManager();
                    if (!TextUtils.isEmpty(cookie)) {
                        cfCookie = cookie;
                        logger("🍪 [PPnix] Cookie 获取成功: "
                            + cookie.substring(0, Math.min(50, cookie.length())));
                    }
                    // 加载完成后销毁 WebView 释放内存
                    try { view.destroy(); } catch (Exception ignored) {}
                }
            });

            // 后台静默加载，不显示任何界面
            webView.loadUrl(HOST);
            logger("🌐 [PPnix] WebView 正在加载首页...");
        } catch (Exception e) {
            logger("🚨 [PPnix] WebView 失败: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────
    // 本地代理服务器
    // ──────────────────────────────────────────────

    private void ensureServer() {
        if (serverReady) return;
        synchronized (serverLock) {
            if (serverReady) return;
            try {
                ServerSocket ss = new ServerSocket(0);
                localPort = ss.getLocalPort();
                logger("✅ [PPnix] 本地代理服务器启动，端口: " + localPort);
                serverReady = true;
                new Thread(() -> runServer(ss)).start();
            } catch (Exception e) {
                logger("🚨 [PPnix] 服务器启动失败: " + e.getMessage());
            }
        }
    }

    private void runServer(ServerSocket ss) {
        while (true) {
            try {
                Socket client = ss.accept();
                new Thread(() -> handleRequest(client)).start();
            } catch (Exception e) {
                logger("🚨 [PPnix] accept异常: " + e.getMessage());
            }
        }
    }

    private void handleRequest(Socket client) {
        try (client) {
            BufferedReader in  = new BufferedReader(new InputStreamReader(client.getInputStream()));
            OutputStream   out = client.getOutputStream();

            String requestLine = in.readLine();
            if (requestLine == null) return;
            // 读完请求头
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {}

            // 解析路径：/m3u8/{key} 或 /ts/{key}
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String path = parts[1];
            if (path.contains("?")) path = path.substring(0, path.indexOf("?"));

            if (path.startsWith("/m3u8/")) {
                // 提供修改后的 m3u8 内容
                String key     = path.substring("/m3u8/".length());
                String content = cache.get(key);
                if (content != null) {
                    byte[] data = content.getBytes("UTF-8");
                    out.write(("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: application/vnd.apple.mpegurl\r\n"
                        + "Content-Length: " + data.length + "\r\n"
                        + "Access-Control-Allow-Origin: *\r\n"
                        + "Connection: close\r\n\r\n").getBytes());
                    out.write(data);
                    logger("📤 [PPnix] M3U8 已提供: " + key);
                } else {
                    out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".getBytes());
                }

            } else if (path.startsWith("/ts/")) {
                // 代理 TS 片段请求，带上正确请求头
                String key    = path.substring("/ts/".length());
                String tsUrl  = cache.get("ts_" + key);
                if (tsUrl == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".getBytes());
                    return;
                }
                proxyToClient(tsUrl, out);
                logger("📤 [PPnix] TS 已代理: " + tsUrl.substring(0, Math.min(60, tsUrl.length())));

            } else {
                out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".getBytes());
            }
            out.flush();
        } catch (Exception e) {
            logger("⚠️ [PPnix] 请求处理异常: " + e.getMessage());
        }
    }

    /**
     * 通过 HttpURLConnection 请求真实 TS 地址并转发给播放器
     */
    private void proxyToClient(String tsUrl, OutputStream out) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(tsUrl).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer",    HOST + "/");
            conn.setRequestProperty("Origin",     HOST);
            // ✅ TS 请求也带上 CF Cookie
            if (!TextUtils.isEmpty(cfCookie)) conn.setRequestProperty("Cookie", cfCookie);
            conn.setInstanceFollowRedirects(true);

            int code        = conn.getResponseCode();
            int contentLen  = conn.getContentLength();
            String mimeType = conn.getContentType();
            if (mimeType == null) mimeType = "video/MP2T";

            // 写响应头
            StringBuilder header = new StringBuilder();
            header.append("HTTP/1.1 ").append(code).append(" OK\r\n");
            header.append("Content-Type: ").append(mimeType).append("\r\n");
            if (contentLen > 0) header.append("Content-Length: ").append(contentLen).append("\r\n");
            header.append("Access-Control-Allow-Origin: *\r\n");
            header.append("Connection: close\r\n\r\n");
            out.write(header.toString().getBytes());

            // 转发数据
            InputStream is   = conn.getInputStream();
            byte[]      buf  = new byte[8192];
            int         read;
            while ((read = is.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            is.close();
            conn.disconnect();
        } catch (Exception e) {
            logger("⚠️ [PPnix] TS代理失败: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────
    // M3U8 处理：下载 → 替换域名 → 改写TS为本地代理地址 → 注册
    // ──────────────────────────────────────────────

    private String processM3u8(String m3u8Url, String referer) {
        try {
            String content = OkHttp.string(m3u8Url, baseHeaders(referer));
            if (TextUtils.isEmpty(content)) {
                logger("⚠️ [PPnix] M3U8下载失败: " + m3u8Url);
                return null;
            }

            Random rnd = new Random();
            String[] lines = content.split("\n");
            StringBuilder modified = new StringBuilder();
            int tsCount = 0, replaceCount = 0;

            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.startsWith("#")) {
                    modified.append(rawLine).append("\n");
                } else if (!line.isEmpty()) {
                    // 这是 TS 片段地址
                    String tsUrl = line;

                    // 替换 ipfs.ppnix.com → 随机子域
                    if (tsUrl.contains("ipfs.ppnix.com")) {
                        int num = rnd.nextInt(16) + 1;
                        tsUrl = tsUrl.replace("ipfs.ppnix.com", num + ".ppnix.com");
                        replaceCount++;
                    }

                    // 把 TS 地址存入缓存，改写为本地代理地址
                    String tsKey = "ts" + (tsCount++);
                    cache.put("ts_" + tsKey, tsUrl);
                    modified.append("http://127.0.0.1:")
                            .append(localPort)
                            .append("/ts/")
                            .append(tsKey)
                            .append("\n");
                } else {
                    modified.append(rawLine).append("\n");
                }
            }

            logger("✅ [PPnix] M3U8处理完成，TS=" + tsCount + " 替换域名=" + replaceCount);

            // 注册 m3u8，返回本地地址
            String m3u8Key = "m3u8_" + System.currentTimeMillis();
            cache.put(m3u8Key, modified.toString());

            // 清理过旧缓存
            if (cache.size() > 500) {
                String first = cache.keySet().iterator().next();
                cache.remove(first);
            }

            return "http://127.0.0.1:" + localPort + "/m3u8/" + m3u8Key;
        } catch (Exception e) {
            logger("🚨 [PPnix] M3U8处理异常: " + e.getMessage());
            return null;
        }
    }

    // ──────────────────────────────────────────────
    // 生命周期
    // ──────────────────────────────────────────────

    @Override
    public void init(Context context, String extend) {
        ensureServer();
        // ✅ 先检查 CookieManager 是否已有 Cookie
        if (!TextUtils.isEmpty(getCookieFromManager())) {
            cfCookie = getCookieFromManager();
            logger("✅ [PPnix] 已有 Cookie，跳过 WebView");
            return;
        }
        // 用 WebView 访问首页，自动拿到 Cookie
        logger("🌐 [PPnix] 启动 WebView 获取 Cookie...");
        Init.run(this::loadWebViewForCookie);
    }

    // ──────────────────────────────────────────────
    // 首页
    // ──────────────────────────────────────────────

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("movie", "电影"));
        classes.add(new Class("tv",    "电视剧"));
        return Result.string(classes, new ArrayList<>());
    }

    // ──────────────────────────────────────────────
    // 分类列表
    // ──────────────────────────────────────────────

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            int pageIndex = Integer.parseInt(pg) - 1;
            String url = String.format("%s/cn/%s/---%d-.html", HOST, tid, pageIndex);
            String html = OkHttp.string(url, baseHeaders(HOST + "/"));
            Document doc = Jsoup.parse(html);

            List<Vod> list = new ArrayList<>();
            for (Element li : doc.select(".lists-content ul li")) {
                Element thumbA = li.selectFirst("a.thumbnail");
                if (thumbA == null) continue;
                String vodId = thumbA.attr("href");
                if (TextUtils.isEmpty(vodId)) continue;
                if (!vodId.startsWith("/")) vodId = "/" + vodId;

                Element img  = thumbA.selectFirst("img");
                String  pic  = img != null ? (img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src")) : "";

                Element yearSpan = li.selectFirst(".countrie .orange");
                String  remarks  = yearSpan != null ? yearSpan.text().trim() : "";

                Element titleA = li.selectFirst("h2 a");
                String  name   = titleA != null ? titleA.text().trim() : "";

                Vod vod = new Vod();
                vod.setVodId(vodId);
                vod.setVodName(name);
                vod.setVodPic(pic);
                vod.setVodRemarks(remarks);
                list.add(vod);
            }
            return Result.string(list);
        } catch (Exception e) {
            logger("🚨 [PPnix] 分类异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    // ──────────────────────────────────────────────
    // 详情页
    // ──────────────────────────────────────────────

    @Override
    public String detailContent(List<String> ids) {
        try {
            String url  = ids.get(0).startsWith("http") ? ids.get(0) : HOST + ids.get(0);
            String html = OkHttp.string(url, baseHeaders(HOST + "/"));
            Document doc = Jsoup.parse(html);

            String name = "", year = "", pic = "", director = "", actor = "", area = "", content = "";

            Element titleElem = doc.selectFirst("h1.product-title");
            if (titleElem != null) {
                String fullText = titleElem.text().trim();
                Matcher m = Pattern.compile("(.+?)\\s*\\((\\d{4})\\)").matcher(fullText);
                if (m.find()) { name = m.group(1).trim(); year = m.group(2); }
                else name = fullText;
            }

            Element picElem = doc.selectFirst(".product-header img.thumb");
            if (picElem != null) {
                pic = picElem.attr("src");
                if (pic.startsWith("/")) pic = HOST + pic;
            }

            Element dirElem  = doc.selectFirst(".product-excerpt:contains(导演) span");
            if (dirElem  != null) director = joinLinks(dirElem);
            Element actElem  = doc.selectFirst(".product-excerpt:contains(主演) span");
            if (actElem  != null) actor    = joinLinks(actElem);
            Element areaElem = doc.selectFirst(".product-excerpt:contains(国家) span");
            if (areaElem != null) area     = joinLinks(areaElem);
            Element descElem = doc.selectFirst(".product-excerpt:contains(简介) span");
            if (descElem != null) content  = descElem.text().trim();

            // 从 script 提取 infoid 和集数
            String infoid = "";
            List<String> playUrls = new ArrayList<>();
            for (Element script : doc.select("script")) {
                String data = script.data();
                if (data.contains("infoid") && data.contains("m3u8")) {
                    Matcher mId = Pattern.compile("infoid\\s*=\\s*(\\d+)").matcher(data);
                    if (mId.find()) infoid = mId.group(1);
                    Matcher mArr = Pattern.compile("m3u8\\s*=\\s*\\[(.*?)]").matcher(data);
                    if (mArr.find()) {
                        Matcher mEp = Pattern.compile("['\"]?(\\d+)['\"]?").matcher(mArr.group(1));
                        while (mEp.find()) {
                            String ep = mEp.group(1);
                            playUrls.add("第" + ep + "集$/info/m3u8/" + infoid + "/" + ep + ".m3u8");
                        }
                    }
                    break;
                }
            }

            Vod vod = new Vod();
            vod.setVodId(ids.get(0));
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodYear(year);
            vod.setVodArea(area);
            vod.setVodDirector(director);
            vod.setVodActor(actor);
            vod.setVodContent(content);
            vod.setVodRemarks(year.isEmpty() ? "" : year + "年");
            if (!playUrls.isEmpty()) {
                vod.setVodPlayFrom("PPnix");
                vod.setVodPlayUrl(TextUtils.join("#", playUrls));
            }
            return Result.string(vod);
        } catch (Exception e) {
            logger("🚨 [PPnix] 详情异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    private String joinLinks(Element parent) {
        List<String> list = new ArrayList<>();
        for (Element a : parent.select("a")) list.add(a.text().trim());
        return TextUtils.join(", ", list);
    }

    // ──────────────────────────────────────────────
    // 播放
    // ──────────────────────────────────────────────

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            ensureServer();

            String originalUrl = id.startsWith("http") ? id : HOST + id;

            // 构建 Referer
            String referer = HOST + "/";
            Matcher m = Pattern.compile("/info/m3u8/(\\d+)/").matcher(id);
            if (m.find()) referer = HOST + "/cn/tv/" + m.group(1) + ".html";

            logger("▶️ [PPnix] M3U8: " + originalUrl);

            // 处理 m3u8：下载 → 替换域名 → TS改写为本地代理 → 注册
            String localUrl = processM3u8(originalUrl, referer);
            String finalUrl = localUrl != null ? localUrl : originalUrl;

            logger("✅ [PPnix] 最终地址: " + finalUrl);

            // 只给播放器 UA 和 Referer，TS 的请求头由本地代理负责带上
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA);
            headers.put("Referer",    referer);

            return Result.get().url(finalUrl).header(headers).string();
        } catch (Exception e) {
            logger("🚨 [PPnix] 播放异常: " + e.getMessage());
            return Result.get().url(id).string();
        }
    }

    // ──────────────────────────────────────────────
    // 搜索
    // ──────────────────────────────────────────────

    @Override
    public String searchContent(String key, boolean quick) {
        return Result.string(new ArrayList<>());
    }
}
