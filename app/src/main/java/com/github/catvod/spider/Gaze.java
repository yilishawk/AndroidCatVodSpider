package com.github.catvod.spider;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gaze（gaze.red）CatVod Spider —— WebView 版
 * -------------------------------------------------------------------------
 * 【为什么用 WebView】2026-10-14 实测破译：gaze 的筛选接口 POST /filter_movielist 被两道门挡：
 *   ① Cloudflare Turnstile（cf_clearance cookie，IP/会话绑定、会过期）—— 纯 HTTP 拿不到；
 *   ② 业务逻辑“proof 头”：页面 wasm 二进制确定性算出的静态常量 {name:value} + x-gaze 动态 token
 *      （从 /filter SSR 的 DOM 抠）。只带 token 不带 proof → 419；两者齐了才过。
 *   纯服务端 Java 强依赖 clearance 生命周期（实测已从可用变 expired）；WebView 由真实浏览器内核
 *   自动过 CF 挑战、自动执行 wasm 算 proof、自动渲染 DOM token —— 鲁棒性最高，是 CatVod 处理
 *   CF 站的标准姿势。
 *
 * 【proof 复用机制（已逆出，非手搓 wasm）】
 *   页面脚本（classic <script>）顶层有 `var _UOKm2GSn6pXP=[]`，wasm 跑完被赋值为
 *   { "b14ac33575f435ebeefa6c5b9c46011b": {name: value} }。该 var 在全局（window）可读。
 *   Java 注入 JS：读 window._UOKm2GSn6pXP[PROOF_KEY] 作 proofHeaders 初值，再追加
 *   x-gaze 名(静态): DOM token(从 [TOKEN_ATTR] 抠)，然后 fetch('/filter_movielist', {...})。
 *
 * 【静态常量（随站点改版会变，419 复现时优先检查这里）】
 *   X_GAZE_NAME = "x-gaze-bc61db603d9b3c49a9bf08ed"  （header 名，来自 SSR 变量 _XRpelZVCo2N7o）
 *   TOKEN_ATTR  = "gdf1d1463b03c78005074"            （DOM 上 token 属性名，来自 _B9Uz64o）
 *   PROOF_KEY   = "b14ac33575f435ebeefa6c5b9c46011b" （proofHeaders 初值的 key）
 *
 * 【边界（如实标注，不胡诌能取数）】
 *   ★ filter_movielist 的真实响应 JSON 字段名【未实采】（clearance 过期 + 本机跑不了 Android
 *     WebView，拿不到成功样本）。下面 parseList 用“多候选 key 防御性解析”，在设备上跑一次、看到
 *     真实 JSON 后按实际字段校正即可（见 HOME/FILTER 的 debug 日志 hook）。
 *   ★ CF Turnstile 能否在 WebView 内静默通过，需设备上验证（Turnstile 本就为真实浏览器/WebView
 *     设计，CatVod 大量 CF 站靠 WebView 过，预期可过）。
 *   ★ homeContent 直接复用 /filter 默认参数拉“最近更新”流（与首页推荐等价），不走 / 首页 DOM 抓取，
 *     避免再引入一套未知选择器。
 */
public class Gaze extends Spider {

    private static final String BASE = "https://gaze.red";
    private static final String FILTER_URL = BASE + "/filter";
    private static final String LIST_API = "/filter_movielist";

    // 静态常量（站点改版需同步刷新，见上）
    private static final String X_GAZE_NAME = "x-gaze-bc61db603d9b3c49a9bf08ed";
    private static final String TOKEN_ATTR = "gdf1d1463b03c78005074";
    private static final String PROOF_KEY = "b14ac33575f435ebeefa6c5b9c46011b";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12; K30) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Mobile Safari/537.36";

    private static final long LOAD_TIMEOUT_MS = 25000;   // 页面加载+CF挑战 超时
    private static final long PROOF_TIMEOUT_MS = 15000;  // 等 wasm proof 就绪超时
    private static final long FETCH_TIMEOUT_MS = 20000;  // 等 fetch 返回超时

    private Context appCtx;
    private Handler webHandler;
    private WebView webView;
    private final Object webLock = new Object();

    // ===================================================================
    // init：WebView 必须挂在【主线程 Looper】上。
    // 注意：
    //   ★ 绝不能在后台线程（如自建 HandlerThread）创建 WebView —— Android 6 / TV 盒 /
    //     多进程 WebView 在那个场景会 native 崩溃（凯哥在 webhtv-android6 与手机均实测崩溃）。
    //   ★ 不在 init 同步创建 WebView，改为首次 webRun 时在主线程“懒创建”，
    //     避免在主线程上 await 导致死锁（CatVod 调用 spider 方法走的是后台线程，await 安全）。
    // ===================================================================
    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        // 主线程 Handler：所有 WebView 创建 / 加载 / evaluateJavascript 都 post 到这里执行。
        webHandler = new Handler(Looper.getMainLooper());
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWebView() {
        WebView w = new WebView(appCtx);
        w.getSettings().setJavaScriptEnabled(true);
        w.getSettings().setDomStorageEnabled(true);
        w.getSettings().setDatabaseEnabled(true);
        w.getSettings().setUserAgentString(UA);
        w.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false; // 同站导航交给 WebView（用 String 重载以兼容 Android 6 / API23）
            }
        });
        return w;
    }

    // ===================================================================
    // WebView 核心：加载 url → 等 proof 就绪 → 跑注入 JS（异步，经 JsBridge 回传字符串）
    // ===================================================================

    /** JS 桥：注入的 JS 通过 GazeBridge.onResult(String) 把结果（或错误）回传 Java。 */
    private static class JsBridge {
        volatile String result;
        volatile boolean done;
        @JavascriptInterface
        public void onResult(String s) {
            result = s;
            done = true;
        }
    }

    /**
     * 在【调用方线程】执行编排，WebView 实际操作 post 到 webHandler（WebView 的 Looper 线程）：
     *   post(loadUrl) → 等 onPageFinished → 等 proof 就绪 → post(evaluateJavascript 注入JS) → 等桥回传。
     * 关键：等待（latch.await）发生在【调用方线程】，绝不阻塞 webHandler 线程，否则 JS 回调永远跑不起来。
     * js 约定：自行调用 GazeBridge.onResult(字符串结果)；出错也调 onResult(JSON.stringify({error}))。
     * 返回注入 JS 经桥回传的字符串；超时/异常返回以 {"error":...} 包裹的 JSON。
     */
    private String webRun(final String url, final String waitProof, final String js) {
        final String[] out = new String[1];
        final JsBridge bridge = new JsBridge();
        // 1) 触发加载（post 到主线程；首次调用懒创建 WebView）
        final CountDownLatch loadLatch = new CountDownLatch(1);
        webHandler.post(() -> {
            synchronized (webLock) {
                try {
                    if (webView == null) webView = createWebView();   // 主线程创建，符合 WebView 线程约束
                    if (webView == null) { out[0] = "{\"error\":\"webView create failed\"}"; loadLatch.countDown(); return; }
                    webView.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(WebView view, String u) { loadLatch.countDown(); }
                    });
                    webView.addJavascriptInterface(bridge, "GazeBridge");
                    webView.loadUrl(url);
                } catch (Throwable t) {
                    out[0] = "{\"error\":\"webView init failed: " + String.valueOf(t.getMessage()) + "\"}";
                    loadLatch.countDown();
                }
            }
        });
        try {
            if (!loadLatch.await(LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                out[0] = "{\"error\":\"page load timeout: " + url + "\"}";
                return out[0];
            }
            // 2) 等 proof 就绪（仅 FILTER 页需要；首页/play 传 null 跳过）
            if (!TextUtils.isEmpty(waitProof)) {
                long deadline = System.currentTimeMillis() + PROOF_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline) {
                    final String[] r = new String[1];
                    final CountDownLatch rl = new CountDownLatch(1);
                    webHandler.post(() -> {
                        synchronized (webLock) {
                            try {
                                if (webView != null) webView.evaluateJavascript(
                                        "(!window." + waitProof + "||!window." + waitProof + "['" + PROOF_KEY + "'])?0:1",
                                        v -> { r[0] = stripQuotes(v); rl.countDown(); });
                            } catch (Throwable t) { rl.countDown(); }
                        }
                    });
                    rl.await(3, TimeUnit.SECONDS);
                    if ("1".equals(r[0])) break;
                    Thread.sleep(200);
                }
            }
            // 3) 注入并执行异步 JS（回调经桥回传）
            bridge.done = false; bridge.result = null;
            webHandler.post(() -> {
                synchronized (webLock) {
                    try {
                        if (webView != null) webView.evaluateJavascript(
                                "(function(){try{" + js + "}catch(e){GazeBridge.onResult(JSON.stringify({error:String(e)}));}})();",
                                null);
                    } catch (Throwable t) { /* 失败由下方超时兜底 */ }
                }
            });
            long deadline = System.currentTimeMillis() + FETCH_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline && !bridge.done) {
                Thread.sleep(150);
            }
            out[0] = bridge.done ? bridge.result : "{\"error\":\"fetch timeout\"}";
        } catch (Exception e) {
            out[0] = "{\"error\":\"" + e.getMessage() + "\"}";
        } finally {
            webHandler.post(() -> { synchronized (webLock) { if (webView != null) webView.removeJavascriptInterface("GazeBridge"); } });
        }
        return out[0] == null ? "{\"error\":\"webRun interrupted\"}" : out[0];
    }

    /** evaluateJavascript 返回值是 JSON 字符串（带引号），这里去首尾引号。 */
    private static String stripQuotes(String v) {
        if (v == null) return "0";
        if (v.startsWith("\"") && v.endsWith("\"")) return v.substring(1, v.length() - 1);
        return v;
    }

    /**
     * 构造 /filter_movielist 的 POST body（与页面一致：form-urlencoded）。
     * 关键参数：mcountry=cn 筛国产剧；mform 区分剧集/电影/番剧；sort=updatetime；page 翻页。
     */
    private static String buildBody(String mform, String mcountry, String genre, String years, String page) {
        StringBuilder b = new StringBuilder();
        b.append("mform=").append(enc(mform.isEmpty() ? "all" : mform));
        b.append("&mcountry=").append(enc(mcountry.isEmpty() ? "all" : mcountry));
        b.append("&genre_arr=").append(enc(genre.isEmpty() ? "all" : genre));
        b.append("&page=").append(enc(page.isEmpty() ? "1" : page));
        b.append("&sort=").append("updatetime");
        b.append("&album=").append("all");
        b.append("&title=").append("");
        b.append("&years=").append(enc(years.isEmpty() ? "all" : years));
        return b.toString();
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    /**
     * 注入 JS：复用页面算好的 proof + 抠 DOM token，POST /filter_movielist，回传原始响应文本。
     * 注：X_GAZE_NAME / TOKEN_ATTR / PROOF_KEY 均为页面静态常量（站点改版需同步）。
     */
    private static final String FETCH_JS =
            "var ph = (window._UOKm2GSn6pXP && window._UOKm2GSn6pXP['" + PROOF_KEY + "']) || {};"
            + "var tok = ''; try{ tok = document.querySelector('[" + TOKEN_ATTR + "]').getAttribute('" + TOKEN_ATTR + "')||''; }catch(e){}"
            + "ph['" + X_GAZE_NAME + "'] = tok;"
            + "var body = BODY_PLACEHOLDER;"
            + "fetch('" + LIST_API + "',{method:'POST',credentials:'same-origin',"
            + "headers:Object.assign({'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},ph),"
            + "body:body}).then(function(r){return r.text();}).then(function(t){GazeBridge.onResult(t);})"
            + ".catch(function(e){GazeBridge.onResult(JSON.stringify({error:String(e)}));});";

    private String fetchList(String mform, String mcountry, String genre, String years, String page) {
        String body = buildBody(mform, mcountry, genre, years, page);
        String js = FETCH_JS.replace("BODY_PLACEHOLDER", JSONObject.quote(body));
        return webRun(FILTER_URL, "_UOKm2GSn6pXP", js);
    }

    // ===================================================================
    // 防御性 JSON 解析（★ 字段名待设备实采校正，见上边界说明）
    // ===================================================================

    /** 从 filter_movielist 响应里抽出条目数组（多候选 key）。 */
    private JSONArray pickArray(JSONObject j) {
        if (j == null) return null;
        for (String k : new String[]{"data", "list", "items", "results", "vod"}) {
            Object o = j.opt(k);
            if (o instanceof JSONArray) return (JSONArray) o;
            if (o instanceof JSONObject) { // data 可能是 {list:[...]} 或 {vod:[...]}
                for (String k2 : new String[]{"list", "vod", "items"}) {
                    JSONArray a = ((JSONObject) o).optJSONArray(k2);
                    if (a != null) return a;
                }
            }
        }
        return null;
    }

    /** 防御性取 id / 标题 / 封面（多候选 key）。 */
    private Vod parseItem(JSONObject o) {
        if (o == null) return null;
        String id = pickStr(o, new String[]{"id", "vod_id", "mid", "movie_id", "tid"});
        String name = pickStr(o, new String[]{"title", "name", "vod_name", "vod_title"});
        String pic = pickStr(o, new String[]{"pic", "cover", "img", "vod_pic", "poster", "thumb"});
        if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name)) return null;
        Vod v = new Vod();
        v.setVodId(id);
        v.setVodName(name);
        if (!TextUtils.isEmpty(pic)) v.setVodPic(absUrl(pic));
        String remark = pickStr(o, new String[]{"remarks", "vod_remarks", "note", "year"});
        if (!TextUtils.isEmpty(remark)) v.setVodRemarks(remark);
        return v;
    }

    private String pickStr(JSONObject o, String[] keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!TextUtils.isEmpty(v) && !"null".equals(v)) return v;
        }
        return "";
    }

    private String absUrl(String u) {
        if (TextUtils.isEmpty(u)) return u;
        if (u.startsWith("http://") || u.startsWith("https://")) return u;
        return BASE + "/" + u.replaceFirst("^/+", "");
    }

    // ===================================================================
    // CatVod 接口
    // ===================================================================

    /**
     * 分类：国产剧放最前（mcountry=cn），其余按形式。
     * 顺序即壳子里的分类顺序 → 国产剧天然在最前。
     */
    private List<Class> buildClasses() {
        List<Class> cs = new ArrayList<>();
        cs.add(new Class("cn", "国产剧"));          // mcountry=cn —— 置顶
        cs.add(new Class("all", "全部"));
        cs.add(new Class("tv", "电视剧"));          // mform=1
        cs.add(new Class("movie", "电影"));         // mform=2
        cs.add(new Class("bangumi", "番剧"));       // mform=bangumi
        return cs;
    }

    /** tid → (mform, mcountry) 映射。 */
    private String[] mapTid(String tid) {
        switch (TextUtils.isEmpty(tid) ? "" : tid) {
            case "cn":     return new String[]{"all", "cn"};
            case "tv":     return new String[]{"1", "all"};
            case "movie":  return new String[]{"2", "all"};
            case "bangumi":return new String[]{"bangumi", "all"};
            default:       return new String[]{"all", "all"};
        }
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        // 首页推荐 = /filter 默认参数拉“最近更新”流（与首页推荐等价），并带分类（国产剧置顶）
        String raw = fetchList("all", "all", "all", "all", "1");
        JSONObject j = safeJson(raw);
        List<Vod> list = new ArrayList<>();
        if (j != null) {
            JSONArray arr = pickArray(j);
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                Vod v = parseItem(arr.optJSONObject(i));
                if (v != null) list.add(v);
            }
        }
        return Result.string(buildClasses(), list);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                 HashMap<String, String> extend) throws Exception {
        int page = 1;
        try { page = Integer.parseInt(pg); } catch (Exception ignored) {}
        if (page < 1) page = 1;
        String[] mm = mapTid(tid);
        String genre = extend != null ? firstNonNull(extend.get("genre"), extend.get("类型"), "") : "";
        String years = extend != null ? firstNonNull(extend.get("years"), extend.get("年份"), "") : "";
        String raw = fetchList(mm[0], mm[1], genre, years, String.valueOf(page));
        JSONObject j = safeJson(raw);
        List<Vod> list = new ArrayList<>();
        int total = 0, pageCount = page;
        if (j != null) {
            JSONArray arr = pickArray(j);
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                Vod v = parseItem(arr.optJSONObject(i));
                if (v != null) list.add(v);
            }
            total = j.optInt("total", arr != null ? arr.length() : 0);
        }
        if (total > 0) pageCount = Math.max((total + 20 - 1) / 20, page);
        else pageCount = page + (list.size() >= 20 ? 1 : 0);
        return Result.get().vod(list).page(page, pageCount, 20, total).string();
    }

    private static String firstNonNull(String... xs) {
        for (String x : xs) if (!TextUtils.isEmpty(x)) return x;
        return "";
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return Result.get().string();
        String id = ids.get(0);
        // ★ 播放页 /play/{id} 同样 CF+proof 门；先用 WebView 加载并抠集数/选集/播放链接。
        // 因未实采 play 页结构，这里走“加载 /play/{id} → 抠 a[href*=m3u8] / video src”的防御性提取，
        // 字段与选择器需设备实采校正。
        String url = BASE + "/play/" + id;
        String js = "var out=[];try{"
                + "document.querySelectorAll('a[href]').forEach(function(a){var h=a.getAttribute('href')||'';if(/\\.m3u8/i.test(h))out.push(a.innerText.trim()+'$'+h);});"
                + "var vs=document.querySelectorAll('video');vs.forEach(function(v){var s=v.src||'';if(s)out.push('播放$'+s);});"
                + "}catch(e){}GazeBridge.onResult(JSON.stringify(out));";
        String raw = webRun(url, null, js);
        Vod v = new Vod();
        v.setVodId(id);
        try {
            JSONArray arr = new JSONArray(raw);
            if (arr.length() > 0) {
                List<String> eps = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) eps.add(arr.optString(i));
                v.setVodPlayFrom("默认线路");
                v.setVodPlayUrl(TextUtils.join("#", eps));
            }
        } catch (Exception ignored) {}
        return Result.string(v);
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        // gaze 搜索：/filter_movielist 的 title 参数即关键字（页面 body 含 title=）。
        String titleBody = "mform=all&mcountry=all&genre_arr=all&page=1&sort=updatetime&album=all&title="
                + enc(key) + "&years=all";
        String js = FETCH_JS.replace("BODY_PLACEHOLDER", JSONObject.quote(titleBody));
        String raw = webRun(FILTER_URL, "_UOKm2GSn6pXP", js);
        JSONObject j = safeJson(raw);
        List<Vod> list = new ArrayList<>();
        if (j != null) {
            JSONArray arr = pickArray(j);
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                Vod v = parseItem(arr.optJSONObject(i));
                if (v != null) list.add(v);
            }
        }
        return Result.get().vod(list).page(1, 1, 20, list.size()).string();
    }

    /**
     * 播放：id 形如 "标题$url"（detailContent 写入的播放引用）。
     * 若已是 http(s) 直链 → 直接播（parse=0）；否则回退按 id 再走 /play 提取（极少触发）。
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (TextUtils.isEmpty(id)) return Result.error("播放地址为空");
        String url = id;
        int d = id.indexOf('$');
        if (d >= 0 && d < id.length() - 1) url = id.substring(d + 1);
        if (url.startsWith("http://") || url.startsWith("https://")) {
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", UA);
            h.put("Referer", BASE + "/");
            return Result.get().parse(0).url(url).header(h).string();
        }
        return Result.error("未能解析播放地址");
    }

    // ===================================================================
    // 工具
    // ===================================================================
    private static JSONObject safeJson(String s) {
        if (TextUtils.isEmpty(s)) return null;
        try {
            int i = s.indexOf('{');
            return i >= 0 ? new JSONObject(s.substring(i)) : null;
        } catch (Exception e) { return null; }
    }

    /** 进程退出前销毁 WebView（CatVod 通常不会调，但留个口）。 */
    public void destroy() {
        if (webHandler != null) {
            webHandler.post(() -> {
                synchronized (webLock) {
                    if (webView != null) { try { webView.destroy(); } catch (Throwable ignored) {} webView = null; }
                }
            });
        }
    }
}
