package com.github.catvod.spider;

import android.app.AlertDialog;
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
import com.github.catvod.utils.Notify;
import com.github.catvod.utils.ResUtil;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

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
 *   2. 把 ipfs.ppnix.com 替换成随机 {1-16}.ppnix.com
 *   3. 缓存修改后的 m3u8，通过 Proxy 返回给播放器
 *
 * Proxy.java 里需要加：
 *   if ("ppnix".equals(doParam)) return PPnix.handleProxy(params);
 */
public class PPnix extends Spider {

    private static final String HOST     = "https://www.ppnix.com";
    private static final String UA       = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String PASSWORD = "123456"; // ← 改成你想要的密码

    // ── 状态 ──
    private static volatile String  cfCookie = "";
    private static volatile boolean unlocked = false;

    // ── m3u8 缓存：key → 修改后的 m3u8 内容 ──
    private static final ConcurrentHashMap<String, String> m3u8Cache = new ConcurrentHashMap<>();

    // ──────────────────────────────────────────────
    // 工具
    // ──────────────────────────────────────────────

    private void logger(String msg) {
        try { Proxy.log(msg); } catch (Exception ignored) {}
    }

    private Map<String, String> baseHeaders(String referer) {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", TextUtils.isEmpty(referer) ? HOST + "/" : referer);
        h.put("Origin",  HOST);
        if (!TextUtils.isEmpty(cfCookie)) h.put("Cookie", cfCookie);
        return h;
    }

    private String getCookieFromManager() {
        try {
            String c = CookieManager.getInstance().getCookie(HOST);
            return c == null ? "" : c;
        } catch (Exception e) { return ""; }
    }

    // ──────────────────────────────────────────────
    // 密码验证
    // ──────────────────────────────────────────────

    private void showPasswordDialog() {
        try {
            int margin = ResUtil.dp2px(16);
            android.widget.FrameLayout frame = new android.widget.FrameLayout(Init.context());
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(margin, margin, margin, margin);
            android.widget.EditText input = new android.widget.EditText(Init.context());
            input.setHint("请输入访问密码");
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            frame.addView(input, lp);

            new AlertDialog.Builder(Init.getActivity())
                .setTitle("PPnix 访问验证")
                .setView(frame)
                .setCancelable(false) // 不可取消
                .setPositiveButton("确定", (d, w) -> {
                    String pwd = input.getText().toString().trim();
                    if (PASSWORD.equals(pwd)) {
                        unlocked = true;
                        Notify.show("✅ 验证通过");
                        Init.execute(this::initCore);
                    } else {
                        Notify.show("❌ 密码错误");
                        Init.run(this::showPasswordDialog);
                    }
                })
                .show(); // 无取消按钮
        } catch (Exception e) {
            logger("🚨 [PPnix] 密码弹窗失败: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────
    // WebView 获取 Cookie
    // ──────────────────────────────────────────────

    private void loadWebViewForCookie() {
        try {
            WebView webView = new WebView(Init.context());
            WebSettings s = webView.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setUserAgentString(UA);

            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    String cookie = getCookieFromManager();
                    if (!TextUtils.isEmpty(cookie)) {
                        cfCookie = cookie;
                        logger("🍪 [PPnix] Cookie 获取成功");
                    }
                    try { view.destroy(); } catch (Exception ignored) {}
                }
            });
            webView.loadUrl(HOST);
            logger("🌐 [PPnix] WebView 加载首页获取 Cookie...");
        } catch (Exception e) {
            logger("🚨 [PPnix] WebView 失败: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────
    // 核心初始化
    // ──────────────────────────────────────────────

    private void initCore() {
        String cookie = getCookieFromManager();
        if (!TextUtils.isEmpty(cookie)) {
            cfCookie = cookie;
            logger("✅ [PPnix] 已有 Cookie，跳过 WebView");
            return;
        }
        logger("🌐 [PPnix] 启动 WebView 获取 Cookie...");
        Init.run(this::loadWebViewForCookie);
    }

    // ──────────────────────────────────────────────
    // M3U8 处理：下载 → 替换域名 → 缓存 → 返回 Proxy 地址
    // ──────────────────────────────────────────────

    private String processM3u8(String m3u8Url, String referer) {
        try {
            String content = OkHttp.string(m3u8Url, baseHeaders(referer));
            if (TextUtils.isEmpty(content)) {
                logger("⚠️ [PPnix] M3U8 下载失败");
                return null;
            }

            Random rnd = new Random();
            String[] lines = content.split("\n");
            StringBuilder sb = new StringBuilder();
            int replaced = 0;

            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (!line.isEmpty() && !line.startsWith("#")
                        && line.contains("ipfs.ppnix.com")) {
                    int num = rnd.nextInt(16) + 1;
                    line = line.replace("ipfs.ppnix.com", num + ".ppnix.com");
                    replaced++;
                }
                sb.append(line.isEmpty() && !rawLine.isEmpty() ? rawLine : line).append("\n");
            }

            logger("✅ [PPnix] M3U8 替换域名=" + replaced);

            // 缓存，控制大小
            String key = String.valueOf(System.currentTimeMillis());
            m3u8Cache.put(key, sb.toString());
            if (m3u8Cache.size() > 20) {
                m3u8Cache.remove(m3u8Cache.keySet().iterator().next());
            }

            // 返回 Proxy 地址，Proxy.proxy() 里处理 do=ppnix
            return Proxy.getUrl() + "?do=ppnix&key=" + key;
        } catch (Exception e) {
            logger("🚨 [PPnix] M3U8 处理异常: " + e.getMessage());
            return null;
        }
    }

    /**
     * 供 Proxy.java 的 proxy() 方法调用
     * 在 Proxy.proxy() 里加：
     *   if ("ppnix".equals(doParam)) return PPnix.handleProxy(params);
     */
    public static Object[] handleProxy(Map<String, String> params) {
        String key     = params.get("key");
        String content = key != null ? m3u8Cache.get(key) : null;
        Map<String, String> headers = new HashMap<>();
        if (content == null) {
            headers.put("Content-Type", "text/plain");
            return new Object[]{404, headers, "not found"};
        }
        headers.put("Content-Type",                "application/vnd.apple.mpegurl");
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Connection",                  "close");
        return new Object[]{200, headers, content};
    }

    // ──────────────────────────────────────────────
    // 生命周期
    // ──────────────────────────────────────────────

    @Override
    public void init(Context context, String extend) {
        if (!unlocked) {
            Init.run(this::showPasswordDialog);
            return;
        }
        initCore();
    }

    // ──────────────────────────────────────────────
    // 首页
    // ──────────────────────────────────────────────

    @Override
    public String homeContent(boolean filter) {
        if (!unlocked) return Result.string(new ArrayList<>());
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
        if (!unlocked) return Result.string(new ArrayList<>());
        try {
            int pageIndex = Integer.parseInt(pg) - 1;
            String url  = String.format("%s/cn/%s/---%d-.html", HOST, tid, pageIndex);
            String html = OkHttp.string(url, baseHeaders(HOST + "/"));
            Document doc = Jsoup.parse(html);

            List<Vod> list = new ArrayList<>();
            for (Element li : doc.select(".lists-content ul li")) {
                Element thumbA = li.selectFirst("a.thumbnail");
                if (thumbA == null) continue;
                String vodId = thumbA.attr("href");
                if (TextUtils.isEmpty(vodId)) continue;
                if (!vodId.startsWith("/")) vodId = "/" + vodId;

                Element img     = thumbA.selectFirst("img");
                String  pic     = img != null ? (img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src")) : "";
                Element yearSpan = li.selectFirst(".countrie .orange");
                String  remarks  = yearSpan != null ? yearSpan.text().trim() : "";
                Element titleA   = li.selectFirst("h2 a");
                String  name     = titleA != null ? titleA.text().trim() : "";

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
        if (!unlocked) return Result.string(new ArrayList<>());
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
        if (!unlocked) return Result.get().url("").string();
        try {
            String originalUrl = id.startsWith("http") ? id : HOST + id;

            String referer = HOST + "/";
            Matcher m = Pattern.compile("/info/m3u8/(\\d+)/").matcher(id);
            if (m.find()) referer = HOST + "/cn/tv/" + m.group(1) + ".html";

            logger("▶️ [PPnix] 处理 M3U8: " + originalUrl);

            // 下载 m3u8，替换域名，返回 Proxy 地址
            String localUrl = processM3u8(originalUrl, referer);
            String finalUrl = localUrl != null ? localUrl : originalUrl;

            logger("✅ [PPnix] 最终地址: " + finalUrl);

            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA);
            headers.put("Referer",    referer);
            if (!TextUtils.isEmpty(cfCookie)) headers.put("Cookie", cfCookie);

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
