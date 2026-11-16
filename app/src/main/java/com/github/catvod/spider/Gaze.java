package com.github.catvod.spider;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Gaze（gaze.red / gaze.host）CatVod Spider —— WebView 中继版 v5
 * 目标工程：yilishawk/AndroidCatVodSpider（multiThread 分支）
 * ===========================================================================
 * 【为什么必须用 WebView，而不是纯 HTTP】
 *   站点三道锁，全部实测（2026-11-16，本机 Chrome + CDP 抓包）：
 *   1) /filter、/play 等路径首次访问返回「访问验证」页（CAP PoW，hashwx.wasm）。
 *      只有点一下 cap-widget（shadow DOM 里 [part=checkbox]）才会过；脚本模拟点击
 *      可以过，但必须在真实浏览器环境里跑。
 *   2) 列表接口 /filter_movielist 的两个 proof header 名与值都随机，且**绑定 body**：
 *      实测把 body 改成 page=2 或 title=xx → 419「页面验证已过期」；
 *      只有 body 一字不改地重放才回数据（且 HTTP 状态是 403，body 正常）。
 *      ⇒ 翻页 / 筛选 / 搜索在服务端无法用改参数的方式拿到。
 *   3) 播放链：/play_source_group（同样签名）→ /gaze_file_vl/pb/..._info
 *      → gzip → base64 → 加密层（MEV4 头，熵 7.98，AES-CTR，密钥由页面 wasm 派生）
 *      → m3u8。m3u8 里的分片名是**混淆串**（如 !_0D55nU~2~R~...），
 *      真实地址 file.icve.com.cn/rc/xx/yyy/{32hex}.m4s 只存在于 worker 的映射表里。
 *
 * 【v5 修正（针对「所有分类列表一样 / 播放点了不能播」）】
 *   · 分类：/filter?mform=<值> 的 **URL 参数会驱动首屏 POST body**，且因为是页面自己
 *     发的请求，proof header 天然合法。实测：mform=1 → 电影 1322 条，mform=2 → 电视剧
 *     902 条，mform=bangumi → 番剧 318 条，mform=chinese_cartoon → 国漫 75 条。
 *     v4 的 categoryContent 完全没用 tid，全站都拿「全部」第 1 页 ⇒ 所有 tab 一样。
 *   · 播放：m3u8 明文能拿到（页面已解密），但分片名是 **per-segment 混淆串**
 *     （如 !_0D55nU~2~R~...），真实地址 file.icve.com.cn/rc/{a}/{b}/{32HEX}.m4s
 *     只在 hls.js 的自定义 loader 内部现算，长度与明文不等长、逐字符映射破不出来。
 *     ⇒ 外部播放器即使拿到 m3u8 也放不了。v5 改为 parse=1 + 播放页 URL，
 *       让壳子用 WebView 打开站点自己的播放器（页面本身实测能播）。
 *
 * 【本版能做到 / 做不到（如实标注）】
 *   能做到：首页/分类第 1 页列表（各分类已区分）、详情页真实集数、剧名与封面、
 *           点播放时用网页模式打开站点播放页。
 *   做不到：翻页、搜索（proof header 绑 body，改参数会被 419）；
 *           把播放地址交给外部原生播放器（分片名混淆，未逆出 loader 的映射）。
 *
 * 【接口约定（已对照 webhtv-android6 源码核过）】
 *   · 类名必须是 com.github.catvod.spider.<api 去掉 csp_>；api=csp_Gaze → Gaze
 *   · JarLoader 用 DexClassLoader 加载，JAR 里必须是 classes.dex
 *   · 返回值直接是 JSON 字符串，壳子用 Result.fromJson 解析，
 *     所以本类**不依赖任何 bean 类**（webhtv-android6 里没有 com.github.catvod.bean.Result）
 */
public class Gaze extends Spider {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12; K30) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    private static final String DEFAULT_BASE = "https://gaze.red";

    /** 等 onPageFinished 的上限；CF/慢网下等不到也继续（后面靠轮询）。 */
    private static final long PAGE_READY_MS = 12000;
    /** 首页列表总预算：壳子对 homeContent 常有 10s 级超时，宁可早失败也别拖死。 */
    private static final long LIST_BUDGET_MS = 11000;
    /** 详情：CAP 通过后选集按钮渲染的等待上限。 */
    private static final long DETAIL_BUDGET_MS = 22000;

    private String base = DEFAULT_BASE;
    private Context appCtx;
    private Handler main;
    private WebView wv;
    private final Object lock = new Object();

    /** 单条缓存：同一个 tid 连续被调（壳子常连着问 page=1、page=2）时不重复加载页面。
     *  只留最近一个分类，切 tab 必然重新抓，避免拿到别的分类的旧数据。 */
    private String lastTid;
    private String lastRaw;

    // ===================================================================
    // init
    // ===================================================================
    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        appCtx = context;
        main = new Handler(Looper.getMainLooper());
        if (extend != null) {
            String e = extend.trim();
            if (e.startsWith("{")) {
                try {
                    String b = new JSONObject(e).optString("base", "");
                    if (b.startsWith("http")) base = trimSlash(b);
                } catch (Exception ignored) {
                }
            } else if (e.startsWith("http")) {
                base = trimSlash(e);
            }
        }
        dlog("init base=" + base);
    }

    private static String trimSlash(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    // ===================================================================
    // 注入脚本
    //   window.__gz        = {lists:[], blobs:[], media:[]}
    //   window.__gzCap()   = 点 cap-widget 的勾选框（shadow DOM）
    //   window.__gzList()  = 最近一次 filter_movielist 响应
    //   window.__gzEps()   = 选集按钮 [{p,t}]
    //   window.__gzClick(p)= 点第 p 集
    //   window.__gzMeta()  = {t:标题, p:封面}
    // ===================================================================
    private static final String CAP_JS =
            "(function(){" +
            " if(window.__gz) return;" +
            " var g={lists:[],blobs:[],media:[]}; window.__gz=g;" +
            " function pushList(t){ try{ if(t && t.indexOf('mlist')>=0) g.lists.push(t); }catch(e){} }" +
            " try{" +
            "  var of=window.fetch;" +
            "  if(of){ window.fetch=function(i,n){" +
            "    var u=(typeof i==='string')?i:((i&&i.url)||'');" +
            "    var p=of.apply(this,arguments);" +
            "    try{ p.then(function(r){ if(u.indexOf('filter_movielist')>=0){ r.clone().text().then(pushList).catch(function(){}); } }).catch(function(){}); }catch(e){}" +
            "    return p; };" +
            "  }" +
            " }catch(e){}" +
            " try{" +
            "  var oo=XMLHttpRequest.prototype.open, os=XMLHttpRequest.prototype.send;" +
            "  XMLHttpRequest.prototype.open=function(m,u){ this.__u=String(u); return oo.apply(this,arguments); };" +
            "  XMLHttpRequest.prototype.send=function(b){" +
            "    var s=this;" +
            "    s.addEventListener('load',function(){ try{ if((s.__u||'').indexOf('filter_movielist')>=0) pushList(s.responseText||''); }catch(e){} });" +
            "    return os.apply(this,arguments); };" +
            " }catch(e){}" +
            " try{" +
            "  var oc=window.URL&&window.URL.createObjectURL;" +
            "  if(oc) window.URL.createObjectURL=function(o){" +
            "    try{ if(o&&typeof o.text==='function'){ o.text().then(function(t){ if(t&&t.indexOf('#EXTM3U')>=0) g.blobs.push(t); }).catch(function(){}); } }catch(e){}" +
            "    return oc.apply(this,arguments); };" +
            " }catch(e){}" +
            " window.__gzCap=function(){" +
            "  var w=document.querySelector('cap-widget'); if(!w||!w.shadowRoot) return 'no-widget';" +
            "  var b=w.shadowRoot.querySelector('[part=checkbox]')||w.shadowRoot.querySelector('.captcha-trigger');" +
            "  if(!b) return 'no-box';" +
            "  ['mousedown','mouseup','click'].forEach(function(t){ try{ b.dispatchEvent(new MouseEvent(t,{bubbles:true,composed:true})); }catch(e){} });" +
            "  return 'clicked'; };" +
            " window.__gzList=function(){ return g.lists.length? g.lists[g.lists.length-1] : ''; };" +
            " window.__gzEps=function(){" +
            "  var n=document.querySelectorAll('#g8f256a3e34dd0c56 [data-path]');" +
            "  if(!n.length) n=document.querySelectorAll('[data-path]');" +
            "  var o=[]; for(var i=0;i<n.length;i++){ var e=n[i]; o.push({p:e.getAttribute('data-path'),t:(e.textContent||'').trim()}); }" +
            "  return JSON.stringify(o); };" +
            " window.__gzClick=function(p){" +
            "  var n=document.querySelectorAll('#g8f256a3e34dd0c56 [data-path]');" +
            "  if(!n.length) n=document.querySelectorAll('[data-path]');" +
            "  for(var i=0;i<n.length;i++){ if(n[i].getAttribute('data-path')===String(p)){" +
            "    var e=n[i]; ['mousedown','mouseup','click'].forEach(function(t){ try{ e.dispatchEvent(new MouseEvent(t,{bubbles:true,composed:true})); }catch(x){} });" +
            "    try{ e.click(); }catch(x){} return 'ok'; } }" +
            "  return 'not-found'; };" +
            " window.__gzMeta=function(){" +
            "  var m=document.querySelector('meta[property=\"og:image\"]');" +
            "  return JSON.stringify({t:document.title||'',p:m?m.content:''}); };" +
            "})();";

    // ===================================================================
    // WebView 编排
    //   所有 WebView 操作都在主线程；所有 await 都发生在调用方（spider）线程。
    // ===================================================================
    private WebView ensureWv() {
        if (wv != null) return wv;
        try {
            CookieManager.getInstance().setAcceptCookie(true);
        } catch (Throwable ignored) {
        }
        WebView w = new WebView(appCtx);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(UA);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        try {
            s.setMediaPlaybackRequiresUserGesture(false);
        } catch (Throwable ignored) {
        }
        try {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        } catch (Throwable ignored) {
        }
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return false;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap f) {
                inject(v);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                inject(v);
            }
        });
        wv = w;
        return wv;
    }

    private static void inject(WebView v) {
        try {
            v.evaluateJavascript(CAP_JS, null);
        } catch (Throwable t) {
            dlog("inject err " + t);
        }
    }

    /** 加载 url，并等 onPageFinished（最多 waitMs；等不到也返回 true，后面靠轮询）。 */
    private boolean load(final String url, long waitMs) {
        if (main == null) return false;
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] ok = new boolean[1];
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    WebView w = ensureWv();
                    w.loadUrl(url);
                    ok[0] = true;
                } catch (Throwable t) {
                    dlog("load err " + t);
                } finally {
                    done.countDown();
                }
            }
        });
        try {
            done.await(5000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        if (!ok[0]) return false;
        // 给新文档一点时间起来；随后由调用方轮询（CAP 点击 / 列表 / 选集）
        sleep(Math.min(waitMs, 1500));
        dlog("load " + url);
        return true;
    }

    /** 确认注入脚本在（Chromium 偶发丢弃 evaluateJavascript），不在就补一次。 */
    private boolean ensureHook() {
        String v = ev("window.__gz?1:0", 1500);
        if ("1".equals(v)) return true;
        ev(CAP_JS, 2500);
        return "1".equals(ev("window.__gz?1:0", 1500));
    }

    /** 在主线程执行 js，返回其原始返回值（字符串会被 JSON 引号包住）。 */
    private String ev(final String js, long timeoutMs) {
        if (main == null) return null;
        final String[] out = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    try {
                        if (wv == null) {
                            latch.countDown();
                            return;
                        }
                        wv.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String v) {
                                out[0] = v;
                                latch.countDown();
                            }
                        });
                    } catch (Throwable t) {
                        latch.countDown();
                    }
                }
            }
        });
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException e) {
            return null;
        }
        return unq(out[0]);
    }

    /** evaluateJavascript 对字符串返回带引号的 JSON，用 org.json 还原（含 \\u 转义）。 */
    private static String unq(String v) {
        if (v == null) return null;
        if (v.length() >= 2 && v.charAt(0) == '"') {
            try {
                return new JSONArray("[" + v + "]").getString(0);
            } catch (Exception e) {
                return v;
            }
        }
        return v;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static void dlog(String s) {
        try {
            SpiderDebug.log("[GAZE] " + s);
        } catch (Throwable ignored) {
        }
    }

    // ===================================================================
    // 列表：/filter[?mform=..] 页 → 点 CAP → 页面自己发 filter_movielist → 抓响应
    //   query 形如 "?mform=1"；空串表示「全部」。
    //   不同 query 是不同文档，window.__gz 会被重建，所以不会串到上一个分类的数据。
    // ===================================================================
    private String fetchList(String query) {
        String url = base + "/filter" + (query == null ? "" : query);
        if (!load(url, PAGE_READY_MS)) return null;
        long deadline = System.currentTimeMillis() + LIST_BUDGET_MS;
        int round = 0;
        while (System.currentTimeMillis() < deadline) {
            round++;
            if (round == 1 || round % 3 == 0) ensureHook();
            String cap = ev("window.__gzCap?window.__gzCap():'no-hook'", 2000);
            String list = ev("window.__gzList?window.__gzList():''", 2500);
            if (list != null && list.indexOf("mlist") >= 0) {
                dlog("list ok " + query + " round=" + round + " cap=" + cap + " len=" + list.length());
                return list;
            }
            if (round == 1) dlog("list wait " + query + " cap=" + cap);
            sleep(900);
        }
        dlog("list timeout " + query);
        return null;
    }

    /** tid → 站点 filter 页的 query。只映射实测过的取值，没验证过的一律不瞎编。 */
    private static String mformOf(String tid) {
        if ("movie".equals(tid)) return "?mform=1";
        if ("tv".equals(tid)) return "?mform=2";
        if ("bangumi".equals(tid)) return "?mform=bangumi";
        if ("cartoon".equals(tid)) return "?mform=chinese_cartoon";
        return ""; // all：不带参数
    }

    // ===================================================================
    // homeContent / categoryContent
    // ===================================================================
    @Override
    public String homeContent(boolean filter) throws Exception {
        JSONObject out = new JSONObject();
        try {
            out.put("class", buildClasses());
            String raw = fetchList("");
            lastTid = "all";
            lastRaw = raw;
            out.put("list", parseList(raw));
            dlog("home items=" + out.optJSONArray("list").length());
        } catch (Throwable t) {
            dlog("home err " + t);
            try {
                out.put("list", new JSONArray());
            } catch (Exception ignored) {
            }
        }
        return out.toString();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) throws Exception {
        JSONObject out = new JSONObject();
        String t = (tid == null || tid.isEmpty()) ? "all" : tid;
        try {
            int page = parseInt(pg, 1);
            if (page > 1) {
                // proof header 与请求体绑定，改 page 会被 419 拒；与其把第 1 页重复给出去，
                // 不如直接告诉壳子没有更多。
                out.put("page", page);
                out.put("pagecount", 1);
                out.put("limit", 20);
                out.put("total", 0);
                out.put("list", new JSONArray());
                return out.toString();
            }
            String raw;
            if (t.equals(lastTid) && lastRaw != null) {
                raw = lastRaw;
                dlog("category hit cache tid=" + t);
            } else {
                raw = fetchList(mformOf(t));
                lastTid = t;
                lastRaw = raw;
            }
            JSONArray list = parseList(raw);
            int total = 0;
            if (raw != null) {
                JSONObject j = jsonOf(raw);
                if (j != null) total = j.optInt("total", 0);
            }
            out.put("page", 1);
            out.put("pagecount", 1);
            out.put("limit", 20);
            out.put("total", total > 0 ? total : list.length());
            out.put("list", list);
            dlog("category tid=" + t + " q=" + mformOf(t) + " items=" + list.length() + " total=" + total);
        } catch (Throwable e) {
            dlog("category err " + e);
            try {
                out.put("page", 1);
                out.put("pagecount", 1);
                out.put("limit", 20);
                out.put("total", 0);
                out.put("list", new JSONArray());
            } catch (Exception ignored) {
            }
        }
        return out.toString();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        // 搜索参数 title= 会改变 body，proof header 不匹配 → 419。
        // 不拿首页数据冒充搜索结果（那会误导），直接说明。
        JSONObject v = new JSONObject();
        v.put("vod_id", "__gaze_no_search__");
        v.put("vod_name", "[GAZE] 搜索不可用");
        v.put("vod_pic", "");
        v.put("vod_remarks", "站点的 proof 签名与请求体绑定，改 title 会被 419 拒绝；请用分类浏览");
        JSONArray arr = new JSONArray();
        arr.put(v);
        JSONObject out = new JSONObject();
        out.put("list", arr);
        return out.toString();
    }

    private JSONArray buildClasses() throws Exception {
        JSONArray cls = new JSONArray();
        // 这 5 个 tid 都有实测数据支撑；v4 里的「综艺」没有验证过取值，已去掉，
        // 免得又一个 tab 拿到「全部」的列表。
        String[][] tabs = {{"all", "全部"}, {"movie", "电影"}, {"tv", "电视剧"},
                {"bangumi", "番剧"}, {"cartoon", "国漫"}};
        for (String[] t : tabs) {
            JSONObject c = new JSONObject();
            c.put("type_id", t[0]);
            c.put("type_name", t[1]);
            cls.put(c);
        }
        return cls;
    }

    private JSONArray parseList(String raw) {
        JSONArray out = new JSONArray();
        JSONObject j = jsonOf(raw);
        if (j == null) return out;
        JSONArray ml = j.optJSONArray("mlist");
        if (ml == null) ml = j.optJSONArray("data");
        if (ml == null) return out;
        for (int i = 0; i < ml.length(); i++) {
            JSONObject o = ml.optJSONObject(i);
            if (o == null) continue;
            // 实证：播放页 URL 用 mid（32hex），列表里的数字 id 拼 /play/ 是 404
            String mid = o.optString("mid", "");
            String title = o.optString("title", "");
            if (mid.isEmpty() || title.isEmpty()) continue;
            try {
                JSONObject v = new JSONObject();
                v.put("vod_id", mid);
                v.put("vod_name", title);
                String pic = o.optString("cover_img", "");
                if (pic.isEmpty()) pic = o.optString("pic", "");
                v.put("vod_pic", pic);
                v.put("vod_remarks", o.optString("grade", ""));
                out.put(v);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static JSONObject jsonOf(String s) {
        if (s == null) return null;
        try {
            int i = s.indexOf('{');
            if (i < 0) return null;
            return new JSONObject(s.substring(i));
        } catch (Exception e) {
            return null;
        }
    }

    // ===================================================================
    // detailContent：/play/{mid} → 点 CAP → 等选集按钮 → 读 [data-path]
    // ===================================================================
    @Override
    public String detailContent(List<String> ids) throws Exception {
        JSONObject out = new JSONObject();
        JSONArray list = new JSONArray();
        try {
            if (ids == null || ids.isEmpty()) {
                out.put("list", list);
                return out.toString();
            }
            String mid = ids.get(0);
            if (!load(base + "/play/" + mid, PAGE_READY_MS)) {
                out.put("list", list);
                return out.toString();
            }
            JSONArray eps = null;
            String meta = null;
            long deadline = System.currentTimeMillis() + DETAIL_BUDGET_MS;
            int round = 0;
            while (System.currentTimeMillis() < deadline) {
                round++;
                if (round == 1 || round % 3 == 0) ensureHook();
                ev("window.__gzCap?window.__gzCap():'no-hook'", 2000);
                String raw = ev("window.__gzEps?window.__gzEps():'[]'", 2500);
                if (raw != null && raw.length() > 4) {
                    try {
                        JSONArray a = new JSONArray(raw);
                        if (a.length() > 0) {
                            eps = a;
                            meta = ev("window.__gzMeta?window.__gzMeta():'{}'", 2000);
                            dlog("detail eps=" + a.length() + " round=" + round);
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                }
                sleep(900);
            }
            JSONObject v = new JSONObject();
            v.put("vod_id", mid);
            String name = mid;
            String pic = "";
            JSONObject m = jsonOf(meta);
            if (m != null) {
                String t = m.optString("t", "");
                // "光阴之外 在线播放 - 注视影视" / "光阴之外 在线播放-注视影视"
                int k = t.indexOf(" 在线播放");
                if (k > 0) t = t.substring(0, k);
                if (!t.isEmpty()) name = t.trim();
                pic = m.optString("p", "");
            }
            v.put("vod_name", name);
            v.put("vod_pic", pic);
            v.put("vod_content", "");
            if (eps != null && eps.length() > 0) {
                // [data-path] 是倒序渲染的（path=47 显示"第48集"），按 path 升序还原
                String[] names = new String[eps.length()];
                int maxPath = -1;
                for (int i = 0; i < eps.length(); i++) {
                    JSONObject e = eps.optJSONObject(i);
                    if (e == null) continue;
                    int p = parseInt(e.optString("p", "-1"), -1);
                    if (p < 0) continue;
                    if (p >= names.length) {
                        String[] bigger = new String[p + 1];
                        System.arraycopy(names, 0, bigger, 0, names.length);
                        names = bigger;
                    }
                    names[p] = e.optString("t", "");
                    if (p > maxPath) maxPath = p;
                }
                StringBuilder sb = new StringBuilder();
                int count = 0;
                for (int p = 0; p <= maxPath; p++) {
                    String n = names[p];
                    if (n == null || n.isEmpty()) n = "第" + (p + 1) + "集";
                    if (count > 0) sb.append('#');
                    sb.append(n).append('$').append(mid).append('|').append(p);
                    count++;
                }
                v.put("vod_play_from", "gaze");
                v.put("vod_play_url", sb.toString());
                v.put("vod_remarks", "共 " + count + " 集");
            } else {
                v.put("vod_remarks", "未取到集数（CAP 未过或页面结构变化）");
            }
            list.put(v);
        } catch (Throwable t) {
            dlog("detail err " + t);
        }
        out.put("list", list);
        return out.toString();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    // ===================================================================
    // playerContent
    //   为什么不能直接给 m3u8：
    //     页面的 m3u8 明文能拿到（blob），但分片 URI 是 per-segment 混淆串，
    //     真实地址（file.icve.com.cn/rc/{a}/{b}/{32HEX}.m4s）由 hls.js 的自定义
    //     loader 现算。实测：混淆串长度与明文路径不等长（79~83 vs 46~47），
    //     逐字符映射不成立，外部播放器拿到 m3u8 只会 404。
    //   所以返回 parse=1 + 播放页 URL：壳子会走 ParseJob 的 type=0（WebView），
    //   打开站点自己的播放器。页面自身实测能播（readyState=4、currentTime 正常推进）。
    //   注意：ParseJob 里最终 URL = parse.getUrl() + result.getUrl()，
    //   因此 url 放播放页、playUrl 必须留空，否则会拼成 .../play/x.../play/x。
    // ===================================================================
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        JSONObject out = new JSONObject();
        String mid = id == null ? "" : id;
        int ep = -1;
        int bar = mid.indexOf('|');
        if (bar > 0) {
            ep = parseInt(mid.substring(bar + 1), -1);
            mid = mid.substring(0, bar);
        }
        String page = base + "/play/" + mid;
        out.put("parse", 1);
        out.put("url", page);
        out.put("playUrl", "");
        out.put("header", new JSONObject());
        dlog("player -> web parse " + page + " ep=" + ep);
        return out.toString();
    }

    // ===================================================================
    // destroy
    // ===================================================================
    @Override
    public void destroy() {
        if (main == null) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    if (wv != null) {
                        try {
                            wv.destroy();
                        } catch (Throwable ignored) {
                        }
                        wv = null;
                    }
                }
            }
        });
    }
}
