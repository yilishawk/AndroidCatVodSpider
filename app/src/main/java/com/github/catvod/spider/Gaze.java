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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Gaze（gaze.red / gaze.host）CatVod Spider —— WebView 中继版 v8
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
 *      → gzip → base64 → AES-CBC（头 MEV4，key=前 16 字节）→ Brotli → m3u8。
 *      m3u8 里的分片名是**混淆串**（如 !_0D55nU~2~R~...），真实地址
 *      file.icve.com.cn/rc/xx/yyy/{32hex}.{ext} 由 wasm（protect.wasm，TinyGo
 *      编译、符号已 strip）派生。
 *
 * 【v6：分片名混淆已完全破解，wasm 不用跑】
 *   逆向结论（本机 Chrome + CDP 抓到明文 wasm，202,131 B；再按真实请求逐条比对）：
 *     · 混淆名是 7 段、以 "~R" 分隔（"~R" 本身代表 "/"）：
 *         p0 固定 "!_0D55nU~2"；p1 空；p2 → file.icve.com.cn；p3 → rc；
 *         p4/p5/p6 是逐字符单表替换（22 个字符，一一对应）。
 *     · **不是加密，只是单表替换**，所以能在 Java 里离线还原，不需要执行 wasm。
 *     · 实测校验：.m4s 那批 303/303 解码、真实 URL 命中 11/12（漏的那条是另一集的
 *       预取）；.png 那批 317/317 解码、真实 URL 命中 27/27，且序号与 seek 位置
 *       （0-9、18-19、35-41、59-66）逐一对上。
 *   ⇒ playerContent 返回 parse=0 + 改写好的 m3u8（挂在 127.0.0.1 小服务上）。
 *     页面仍负责最重的解密链（gzip/base64/AES-CBC/Brotli 都在页面里跑），
 *     本类只 hook URL.createObjectURL 把解好密的 m3u8 明文捞出来。
 *
 * 【v7：分片容器搞清楚了——两批都是明文，一批要剥壳】
 *   · .m4s 那批 = **裸 fMP4**（前 8 字节 = size + styp/sidx）→ 直链直接播。
 *   · .png 那批 = **PNG 外壳 + 纯 MPEG-TS + IEND**，**没有加密**：
 *       offset 0..7    PNG 签名
 *       offset 8..32   IHDR（1x1）
 *       offset 33..60  IDAT（16 字节，只装一张 1x1 图）
 *       offset 61..    3880 个 188 字节 TS 包（0x47 全部命中，0 错位）
 *       末尾 12 字节   IEND
 *     实测 PID 分布：0(PAT)×2、4096(PMT)×2、256 视频×2786、257 音频×1089、17(SDT)×1
 *     ⇒ 剥掉头 61 字节和尾 12 字节就是标准 TS，播放器直接吃。
 *   （v6 曾把这批误判成"加密"，是因为只搜了 ftyp/moof/styp，没往 TS 上想。）
 *
 * 【v8：还有一条更短的路——整片直链】
 *   站点自己的播放器会 POST /fetch_<随机>_murl（body: id=集&mid=片&bubble=64hex，
 *   外加两个随机名的 proof 头 + 一个 %40%23%2A… 头），返回：
 *     {"code":1,"url":"https://lf9-csp-sign.bytetos.com/....mp4?x-expires=..&x-signature=..&name=bgm.mp4"}
 *   实测这个 "bgm.mp4" 是**伪装名**，它就是正片：
 *     Content-Type video/mp4、Content-Length 319,488,448（304MB）、Accept-Ranges: bytes、
 *     ftyp(0) + moov(32) → faststart，moov 在文件头，边下边播不用等；
 *     moov/trak/mdia 里 hdlr=vide、stsd=avc1，mvhd duration 2725s（45 分钟）。
 *   ⇒ 拿到这条链接就**完全不需要** AES/Brotli/wasm/分片剥壳，parse=0 直接丢给 ExoPlayer。
 *   代价：x-expires 只给 1 小时（实测 04:08 生成、04:54 过期），所以每次播放都得现场取，
 *        不能缓存；proof 头与 bubble 都是页面即时算的，本地复算不了（重放旧包 → 403 请求无效）。
 *   ⇒ 做法：hook 页面自己的 fetch/XHR，只把 _murl 的响应里那条 url 抄下来。
 *
 * 【v7 播放路径】
 *   页面仍负责最重的解密链（gzip/base64/AES-CBC/Brotli 都在页面里跑），
 *   本类只 hook URL.createObjectURL 把 m3u8 明文捞出来，然后：
 *     · 裸 fMP4 → m3u8 里写真实直链（零代理，最快）
 *     · TS      → m3u8 里写 http://127.0.0.1:port/s/{token}/{i}.ts
 *                 本地服务回源拉整段、剥壳、按 video/mp2t 返回
 *   两种都是 parse=0，直接交给 ExoPlayer。
 *
 * 【本版能做到 / 做不到（如实标注）】
 *   能做到：首页/分类第 1 页列表（各分类已区分）、详情页真实集数、剧名与封面、
 *           两批分片都能给出可播的 m3u8（fMP4 直链 / TS 剥壳代理）。
 *   做不到：翻页、搜索（proof header 绑 body，改参数会被 419）。
 *   风险点：替换表若被站点换掉，decodeSegment 会返回 null，自动退回网页播放模式；
 *           TS 代理不支持 Range（剥壳后长度变了），seek 会整段重下，不影响能播。
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
    /** 播放：从打开播放页到拿到 m3u8 明文的总预算（含 CAP、点集、解密、hls 起播）。
     *  正常路径下 detailContent 已经把页面留在播放页且 CAP 已过，这里只需点集 + 等解密，
     *  实测 3~8s；28s 是给慢机/重加载留的余量，同时别撞上壳子对 playerContent 的超时。 */
    private static final long PLAY_BUDGET_MS = 28000;

    /** grabM3u8 的返回前缀：带它表示「整片直链」，不带就是 m3u8 明文。 */
    private static final String MP4_PREFIX = "mp4:";

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
    //   window.__gzClear() = 清掉已抓到的 m3u8（换集前必须清，否则拿到上一集的）
    //   window.__gzM3u8()  = 最近一次解好密的 m3u8 明文（分片名仍是混淆串）
    // ===================================================================
    private static final String CAP_JS =
            "(function(){" +
            " if(window.__gz) return;" +
            " var g={lists:[],blobs:[],media:[],murls:[]}; window.__gz=g;" +
            " function pushList(t){ try{ if(t && t.indexOf('mlist')>=0) g.lists.push(t); }catch(e){} }" +
            // _murl 接口回的是 {"code":1,"url":"https://lf9-csp-sign.bytetos.com/....mp4?x-signature=..."}
            // 这是**整片直链**（faststart，moov 在前，支持 Range），比 m3u8 那条链子简单得多。
            // 接口路径带随机前缀（如 fetch_189c_murl），所以只按后缀 _murl 匹配。
            " function pushMurl(t){ try{" +
            "  if(!t) return;" +
            "  var i=t.indexOf('\"url\"'); if(i<0) return;" +
            "  var a=t.indexOf('\"', i+5); if(a<0) return;" +
            "  var b=t.indexOf('\"', a+1); if(b<0) return;" +
            "  var u=t.slice(a+1,b).split('\\\\/').join('/');" +
            "  if(u.indexOf('http')!==0) return;" +
            "  g.murls.push(u); if(g.murls.length>4) g.murls.shift();" +
            " }catch(e){} }" +
            " try{" +
            "  var of=window.fetch;" +
            "  if(of){ window.fetch=function(i,n){" +
            "    var u=(typeof i==='string')?i:((i&&i.url)||'');" +
            "    var p=of.apply(this,arguments);" +
            "    try{ p.then(function(r){ if(u.indexOf('filter_movielist')>=0){ r.clone().text().then(pushList).catch(function(){}); }" +
            "     else if(u.indexOf('_murl')>=0){ r.clone().text().then(pushMurl).catch(function(){}); } }).catch(function(){}); }catch(e){}" +
            "    return p; };" +
            "  }" +
            " }catch(e){}" +
            " try{" +
            "  var oo=XMLHttpRequest.prototype.open, os=XMLHttpRequest.prototype.send;" +
            "  XMLHttpRequest.prototype.open=function(m,u){ this.__u=String(u); return oo.apply(this,arguments); };" +
            "  XMLHttpRequest.prototype.send=function(b){" +
            "    var s=this;" +
            "    s.addEventListener('load',function(){ try{ if((s.__u||'').indexOf('filter_movielist')>=0) pushList(s.responseText||'');" +
            "     else if((s.__u||'').indexOf('_murl')>=0) pushMurl(s.responseText||''); }catch(e){} });" +
            "    return os.apply(this,arguments); };" +
            " }catch(e){}" +
            " try{" +
            "  var oc=window.URL&&window.URL.createObjectURL;" +
            "  if(oc) window.URL.createObjectURL=function(o){" +
            "    try{" +
            "     if(o&&typeof o.size==='number'){" +
            "      var keep=function(t){ try{ if(t&&t.indexOf('#EXTM3U')>=0){ g.blobs.push(t); if(g.blobs.length>4) g.blobs.shift(); } }catch(e){} };" +
            "      if(typeof o.text==='function'){ o.text().then(keep).catch(function(){}); }" +
            "      else if(window.FileReader){ var fr=new FileReader(); fr.onload=function(){ keep(String(fr.result||'')); }; fr.readAsText(o); }" +
            "     }" +
            "    }catch(e){}" +
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
            " window.__gzClear=function(){ g.blobs.length=0; return 1; };" +
            " window.__gzCount=function(){ return g.blobs.length; };" +
            " window.__gzM3u8=function(){ return g.blobs.length? g.blobs[g.blobs.length-1] : ''; };" +
            " window.__gzMurl=function(){ return g.murls.length? g.murls[g.murls.length-1] : ''; };" +
            " window.__gzMurlCount=function(){ return g.murls.length; };" +
            // 诊断用：一条字符串就能区分「hook 没抓到」和「抓到了但我清空了」
            " window.__gzDiag=function(){" +
            "  var v=document.querySelector('video');" +
            "  return JSON.stringify({hook:1,n:g.blobs.length,m:g.murls.length," +
            "   v:v?{rs:v.readyState,t:Math.round(v.currentTime||0),src:(v.currentSrc||'').slice(0,40)}:null," +
            "   href:(location.href||'').slice(-48)}); };" +
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

            /** 纯观察，不改请求：把页面自己发的关键请求记进日志。
             *  _murl 的路径前缀是随机的（fetch_189c_murl），日志里看到才知道它长什么样。 */
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView v, android.webkit.WebResourceRequest r) {
                try {
                    String u = r.getUrl() == null ? "" : r.getUrl().toString();
                    if (u.indexOf("_murl") >= 0 || u.indexOf("gaze_file_vl") >= 0
                            || u.indexOf("play_source_group") >= 0) {
                        dlog("net " + r.getMethod() + " " + u);
                    }
                } catch (Throwable ignored) {
                }
                return null;
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
    // 分片名解码（wasm 逆向结果，实测 303/303 命中）
    //
    //   混淆名结构：7 段，用 "~R" 分隔（"~R" 本身就代表 "/"）：
    //     p0 = "!_0D55nU~2"        固定前缀（会话内不变）
    //     p1 = ""                  空段（因为 ~R~R）
    //     p2 = "7oVWtoNBWtNC6tNe"  → file.icve.com.cn（整串字典映射，不是逐字符）
    //     p3 = "wN"                → rc（整串字典映射）
    //     p4 = {a}  p5 = {b}  p6 = {32HEX}.m4s    ← 只有这三段是逐字符替换
    //
    //   替换表（22 个字符，双向唯一）。注意：前 19 个只够解 .m4s，
    //   站点还有一批分片伪装成 .png（内容是加密的，见下），需要 p/n/g 三条：
    //     4→0  c→1  A→2  l→3  J→4  u→5  S→6  3→7  b→8  .→9
    //     R→A  2→B  a→C  _→D  j→E  H→F  t→.  6→m  U→s
    //     n→p  e→n  f→g
    //   实测：.m4s 那批 303/303 解码、11/12 与真实 URL 命中；
    //         .png 那批 317/317 解码、27/27 与真实 URL 命中。
    // ===================================================================
    private static final String SEG_FROM = "4cAlJuS3b.R2a_jHt6Unef";
    private static final String SEG_TO = "0123456789ABCDEF.mspng";
    private static final String HOST_TOKEN = "7oVWtoNBWtNC6tNe";
    private static final String RC_TOKEN = "wN";
    private static final String SEG_SEP = "~R";

    /** 逐字符还原；出现表外字符说明混淆表变了，返回 null 让调用方回退。 */
    private static String segChars(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            int k = SEG_FROM.indexOf(s.charAt(i));
            if (k < 0) return null;
            sb.append(SEG_TO.charAt(k));
        }
        return sb.toString();
    }

    /** 混淆分片名 → 真实直链；不是本格式（或表变了）返回 null。 */
    private static String decodeSegment(String name) {
        String n = name == null ? "" : name.trim();
        if (n.length() < 20 || n.indexOf('~') < 0) return null;
        String[] p = n.split(SEG_SEP, -1);
        if (p.length != 7) return null;
        if (!HOST_TOKEN.equals(p[2]) || !RC_TOKEN.equals(p[3])) return null;
        String a = segChars(p[4]);
        String b = segChars(p[5]);
        String f = segChars(p[6]);
        if (a == null || b == null || f == null) return null;
        return "https://file.icve.com.cn/rc/" + a + "/" + b + "/" + f;
    }

    /**
     * 把 m3u8 里的混淆分片名全部换成真实直链。
     * stat[0]=替换成功数，stat[1]=没认出来的分片行数。
     */
    private static String rewriteM3u8(String m3u8, int[] stat) {
        // 站点明文首行前面是一串 NBSP（U+00A0，实测 13 个），不是普通空格，trim() 去不掉；
        // 不归一化的话首行 #EXTM3U 会被当成 URI 去解码（实测确实如此）。
        String src = m3u8.replace('\u00A0', ' ').replace("\uFEFF", "");
        String[] lines = src.split("\r?\n");
        StringBuilder sb = new StringBuilder(m3u8.length() + 8192);
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.charAt(0) == '#') {
                sb.append(t).append('\n');
                continue;
            }
            String u = decodeSegment(t);
            if (u != null) {
                sb.append(u).append('\n');
                stat[0]++;
            } else {
                sb.append(t).append('\n');
                stat[1]++;
            }
        }
        return sb.toString();
    }

    /** 取改写后 m3u8 里的第一个分片直链（跳过 #EXT-X-MAP 之外的注释行）。 */
    private static String firstUri(String m3u8) {
        String[] lines = m3u8.split("\n");
        for (String l : lines) {
            String t = l.trim();
            if (t.startsWith("http")) return t;
        }
        return null;
    }

    // ===================================================================
    // 分片容器探测
    //   站点两批分片，**都是明文**，只是容器不同（2026-11-17 实测）：
    //     · .m4s → 裸 fMP4：前 8 字节 = size + "styp"/"sidx"
    //     · .png → PNG 外壳 + 纯 MPEG-TS + IEND：
    //         offset 0..7    PNG 签名
    //         offset 8..32   IHDR（1x1）
    //         offset 33..60  IDAT（16 字节，只装一张 1x1 图）
    //         offset 61..    3880 个 188 字节 TS 包（0x47 全部命中，0 错位）
    //         末尾 12 字节   IEND
    //       TS 证据：PID 0(PAT)×2、PID 4096(PMT)×2、PID 256 视频×2786、
    //               PID 257 音频×1089、PID 17(SDT)×1
    //   ⇒ .png 那批**不用解密，剥壳就行**，剥完就是标准 TS，播放器直接吃。
    // ===================================================================
    private static final int SEG_UNKNOWN = 0;
    private static final int SEG_FMP4 = 1;
    private static final int SEG_TS_RAW = 2;
    private static final int SEG_TS_PNG = 3;

    /** 返回 {类型, 头部要跳过的字节数}；探测不出来返回 null。 */
    private static int[] probeSeg(String url) {
        java.net.HttpURLConnection c = null;
        java.io.InputStream in = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Range", "bytes=0-511");
            c.setRequestProperty("User-Agent", UA);
            if (c.getResponseCode() / 100 != 2) return null;
            in = c.getInputStream();
            byte[] b = new byte[512];
            int n = 0;
            while (n < 512) {
                int r = in.read(b, n, 512 - n);
                if (r <= 0) break;
                n += r;
            }
            if (n < 8) return null;

            boolean png = (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
            if (png) {
                // 在头部附近找 TS 同步点：0x47 且隔 188、376 还是 0x47
                int lim = Math.min(n - 1, 256);
                for (int o = 8; o < lim; o++) {
                    if ((b[o] & 0xFF) != 0x47) continue;
                    if (o + 188 < n && (b[o + 188] & 0xFF) != 0x47) continue;
                    if (o + 376 < n && (b[o + 376] & 0xFF) != 0x47) continue;
                    return new int[]{SEG_TS_PNG, o};
                }
                String box = new String(b, 4, 4, "ISO-8859-1");
                if ("styp".equals(box) || "ftyp".equals(box)) return new int[]{SEG_FMP4, 0};
                return null;
            }
            String box = new String(b, 4, 4, "ISO-8859-1");
            if ("styp".equals(box) || "ftyp".equals(box)
                    || "sidx".equals(box) || "moof".equals(box)) {
                return new int[]{SEG_FMP4, 0};
            }
            if ((b[0] & 0xFF) == 0x47 && n > 188 && (b[188] & 0xFF) == 0x47) {
                return new int[]{SEG_TS_RAW, 0};
            }
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
            try {
                if (c != null) c.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    /** PNG 尾部那 12 字节 IEND（length=0 + "IEND" + crc）。 */
    private static boolean isIend(byte[] b, int o) {
        return b[o] == 0 && b[o + 1] == 0 && b[o + 2] == 0 && b[o + 3] == 0
                && b[o + 4] == 'I' && b[o + 5] == 'E' && b[o + 6] == 'N' && b[o + 7] == 'D';
    }

    /** 把混淆 m3u8 改写成走本地代理的版本，同时把真实直链按序存进 SEGS。 */
    private static String rewriteToProxy(String m3u8, String token, int port, int[] stat) {
        String src = m3u8.replace('\u00A0', ' ').replace("\uFEFF", "");
        String[] lines = src.split("\r?\n");
        StringBuilder sb = new StringBuilder(m3u8.length() + 8192);
        List<String> urls = new java.util.ArrayList<String>();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.charAt(0) == '#') {
                sb.append(t).append('\n');
                continue;
            }
            String u = decodeSegment(t);
            if (u == null) {
                sb.append(t).append('\n');
                stat[1]++;
                continue;
            }
            urls.add(u);
            sb.append("http://127.0.0.1:").append(port).append("/s/").append(token)
                    .append('/').append(urls.size() - 1).append(".ts\n");
            stat[0]++;
        }
        SEGS.put(token, urls.toArray(new String[0]));
        return sb.toString();
    }

    // ===================================================================
    // 本地服务（m3u8 + 分片剥壳代理）
    //   播放器只认 URL，不认字符串，所以起一个 127.0.0.1 的只读小服务：
    //     /{token}.m3u8      改写好的播放列表
    //     /s/{token}/{i}.ts  分片：拉源站 → 剥掉 PNG 外壳 → 按 TS 返回
    //   裸 fMP4 那批不需要代理，m3u8 里直接写真实直链。
    // ===================================================================
    private static final Map<String, String> DOCS = new ConcurrentHashMap<String, String>();
    private static final Map<String, String[]> SEGS = new ConcurrentHashMap<String, String[]>();
    private static final Map<String, Integer> SEG_SKIP = new ConcurrentHashMap<String, Integer>();
    private static volatile java.util.concurrent.ExecutorService segPool;
    private static volatile ServerSocket srvSocket;
    private static volatile int srvPort = -1;

    private static synchronized int ensureServer() {
        if (srvPort > 0) return srvPort;
        try {
            ServerSocket ss = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
            srvSocket = ss;
            srvPort = ss.getLocalPort();
            // 分片要回源拉 700KB+，单线程会让起播卡住，给 4 个线程
            segPool = java.util.concurrent.Executors.newFixedThreadPool(4,
                    new java.util.concurrent.ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread th = new Thread(r, "gaze-seg");
                            th.setDaemon(true);
                            return th;
                        }
                    });
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        ServerSocket cur = srvSocket;
                        if (cur == null || cur.isClosed()) break;
                        final Socket s;
                        try {
                            s = cur.accept();
                        } catch (Throwable e) {
                            if (cur.isClosed()) break;
                            continue;
                        }
                        java.util.concurrent.ExecutorService pool = segPool;
                        if (pool == null) {
                            try {
                                s.close();
                            } catch (Throwable ignored) {
                            }
                            continue;
                        }
                        try {
                            pool.execute(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        serveDoc(s);
                                    } catch (Throwable e) {
                                        try {
                                            s.close();
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                }
                            });
                        } catch (Throwable e) {
                            try {
                                s.close();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }, "gaze-m3u8");
            t.setDaemon(true);
            t.start();
            dlog("server 127.0.0.1:" + srvPort);
            return srvPort;
        } catch (Throwable e) {
            dlog("server fail " + e);
            return -1;
        }
    }

    private static void serveDoc(Socket s) throws Exception {
        s.setSoTimeout(8000);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
        String req = in.readLine();
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            // 丢掉请求头
        }
        String path = "/";
        if (req != null) {
            String[] parts = req.split(" ");
            if (parts.length >= 2) path = parts[1];
        }
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        String key = path.startsWith("/") ? path.substring(1) : path;
        OutputStream os = s.getOutputStream();
        if (key.startsWith("s/")) {
            serveSeg(key.substring(2), os);
            os.flush();
            try {
                s.close();
            } catch (Throwable ignored) {
            }
            return;
        }
        int dot = key.lastIndexOf(".m3u8");
        if (dot > 0) key = key.substring(0, dot);
        String body = DOCS.get(key);
        if (body == null) {
            byte[] b = "not found".getBytes("UTF-8");
            os.write(("HTTP/1.1 404 Not Found\r\nContent-Length: " + b.length
                    + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            os.write(b);
        } else {
            byte[] b = body.getBytes("UTF-8");
            os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl"
                    + "\r\nContent-Length: " + b.length
                    + "\r\nAccess-Control-Allow-Origin: *"
                    + "\r\nCache-Control: no-store"
                    + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            os.write(b);
        }
        os.flush();
        try {
            s.close();
        } catch (Throwable ignored) {
        }
    }

    /** /s/{token}/{i}.ts：回源拉整段 → 剥 PNG 外壳和尾部 IEND → 按 TS 返回。 */
    private static void serveSeg(String key, OutputStream os) throws Exception {
        int slash = key.indexOf('/');
        if (slash <= 0) {
            writePlain(os, 404, "not found");
            return;
        }
        String token = key.substring(0, slash);
        String rest = key.substring(slash + 1);
        int dot = rest.indexOf('.');
        if (dot > 0) rest = rest.substring(0, dot);
        int idx = parseInt(rest, -1);
        String[] urls = SEGS.get(token);
        if (urls == null || idx < 0 || idx >= urls.length) {
            writePlain(os, 404, "no seg");
            return;
        }
        Integer sk = SEG_SKIP.get(token);
        byte[] data = fetchStrip(urls[idx], sk == null ? 0 : sk);
        if (data == null || data.length == 0) {
            writePlain(os, 502, "fetch fail");
            return;
        }
        os.write(("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t"
                + "\r\nContent-Length: " + data.length
                + "\r\nAccess-Control-Allow-Origin: *"
                + "\r\nCache-Control: no-store"
                + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        os.write(data);
    }

    private static void writePlain(OutputStream os, int code, String msg) throws Exception {
        byte[] b = msg.getBytes("UTF-8");
        os.write(("HTTP/1.1 " + code + " X\r\nContent-Type: text/plain"
                + "\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        os.write(b);
    }

    /**
     * 拉一整段并剥壳。
     *   skip=0 表示裸 fMP4，原样返回。
     *   skip>0 表示 PNG 外壳，跳过头 + 去掉尾部 IEND。
     * 不转发 Range：剥壳后长度变了，Range 会错位；TS 分片本来就是整段消费。
     */
    private static byte[] fetchStrip(String url, int skip) {
        java.net.HttpURLConnection c = null;
        java.io.InputStream in = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(25000);
            c.setRequestProperty("User-Agent", UA);
            if (c.getResponseCode() / 100 != 2) return null;
            int len = c.getContentLength();
            java.io.ByteArrayOutputStream bos =
                    new java.io.ByteArrayOutputStream(len > 0 ? len : 262144);
            in = c.getInputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            byte[] all = bos.toByteArray();
            int from = (skip > 0 && all.length > skip) ? skip : 0;
            int to = all.length;
            if (from > 0 && to - from > 12 && isIend(all, to - 12)) to -= 12;
            if (from == 0 && to == all.length) return all;
            byte[] out = new byte[to - from];
            System.arraycopy(all, from, out, 0, out.length);
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
            try {
                if (c != null) c.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    // ===================================================================
    // 播放页里把 m3u8 明文取出来
    //   页面自己完成 gzip → base64 → AES-CBC → Brotli 全链（即 wasm 的 servility），
    //   最后交给 hls.js 的是一个 blob，所以 hook URL.createObjectURL 就能拿到明文。
    //   拿到的明文里分片名仍是混淆串，交给 rewriteM3u8 还原。
    // ===================================================================
    private String grabM3u8(String mid, int ep) {
        String page = "/play/" + mid;
        boolean needLoad = true;
        // detailContent 刚走过同一个播放页时，页面还在，省一次重新加载（也省一次 CAP）
        String path = ev("(location.pathname||'')", 1200);
        if (path != null && path.indexOf(page) >= 0) {
            String raw = ev("window.__gzEps?window.__gzEps():'[]'", 1500);
            if (raw != null && raw.length() > 4) needLoad = false;
        }
        if (needLoad && !load(base + page, PAGE_READY_MS)) return null;

        // 进来时页面可能已经在播某一集（detailContent 刚打开过同一个播放页），
        // 此时 m3u8 早就生成好了。**不能清空**——清了再点「正在播的那一集」，
        // 播放器认为没换集、不会重新加载，就会一直等不到新 blob。
        int n0 = parseInt(ev("window.__gzCount?window.__gzCount():0", 1500), 0);
        String pre = null;
        if (n0 > 0) {
            String p = ev("window.__gzM3u8?window.__gzM3u8():''", 6000);
            if (p != null && p.length() > 200 && p.indexOf("#EXTINF") >= 0) pre = p;
        }
        // 整片直链（_murl 接口）优先级最高：不代理、不剥壳、不占内存，播放器自己 Range 拖动
        int m0 = parseInt(ev("window.__gzMurlCount?window.__gzMurlCount():0", 1500), 0);
        String mpre = null;
        if (m0 > 0) {
            String u = ev("window.__gzMurl?window.__gzMurl():''", 3000);
            if (u != null && u.length() > 20 && u.indexOf("http") == 0) mpre = u;
        }
        dlog("grab start ep=" + ep + " needLoad=" + needLoad + " n0=" + n0
                + " pre=" + (pre == null ? -1 : pre.length())
                + " m0=" + m0 + " mpre=" + (mpre == null ? -1 : mpre.length()));

        // 页面默认播第 1 集，所以 ep<=0 时进来已有的那条就是它，不用再点（省 3~8s）
        if (ep <= 0 && mpre != null) {
            dlog("murl pre hit ep=" + ep);
            return MP4_PREFIX + mpre;
        }

        long deadline = System.currentTimeMillis() + PLAY_BUDGET_MS;
        boolean clicked = false;
        int round = 0;
        while (System.currentTimeMillis() < deadline) {
            round++;
            if (round == 1 || round % 4 == 0) ensureHook();
            ev("window.__gzCap?window.__gzCap():'no-hook'", 2000);
            if (!clicked) {
                // 必须等选集按钮真的渲染出来再点：元素先出现、点击处理器后绑定，
                // 太早点会「点了个空」，拿到的仍是默认那一集的 m3u8（静默错集）。
                String eps = ev("window.__gzEps?window.__gzEps():'[]'", 2000);
                if (eps != null && eps.length() > 4) {
                    int target = ep < 0 ? 0 : ep;
                    String r = ev("window.__gzClick?window.__gzClick(" + target + "):'no-hook'", 2000);
                    if (r != null && r.indexOf("ok") >= 0) {
                        clicked = true;
                        dlog("play click ep=" + target + " round=" + round + " n0=" + n0);
                    }
                }
            } else {
                // 先看整片直链：新出现的才算（条数没涨说明是换集前那条）
                String mu = ev("window.__gzMurl?window.__gzMurl():''", 3000);
                if (mu != null && mu.length() > 20 && mu.indexOf("http") == 0) {
                    int mc = parseInt(ev("window.__gzMurlCount?window.__gzMurlCount():-1", 1500), -1);
                    if (mc > m0 || mc < 0) {
                        dlog("murl new n=" + mc + " len=" + mu.length() + " round=" + round);
                        return MP4_PREFIX + mu;
                    }
                }
                String m = ev("window.__gzM3u8?window.__gzM3u8():''", 6000);
                if (m != null && m.length() > 200 && m.indexOf("#EXTINF") >= 0) {
                    int n = parseInt(ev("window.__gzCount?window.__gzCount():-1", 1500), -1);
                    if (n > n0 || n < 0) {
                        dlog("m3u8 new n=" + n + " len=" + m.length() + " round=" + round);
                        return m;
                    }
                    // 条数没涨：播放器没重新加载，可能点的就是当前集，继续等一会儿
                }
                if (round % 6 == 0) dlog("grab diag " + ev("window.__gzDiag?window.__gzDiag():'no-diag'", 1500));
            }
            sleep(700);
        }
        // 超时兜底：只有第 1 集才敢复用「进来时已有的那条」——
        // 页面默认播的就是第 1 集，所以它和 ep=0 是同一集。
        // ep>0 时页面当前集未知，复用可能给错集，宁可退回网页模式。
        if (mpre != null && ep <= 0) {
            dlog("murl reuse pre len=" + mpre.length());
            return MP4_PREFIX + mpre;
        }
        if (pre != null && ep <= 0) {
            dlog("m3u8 reuse pre len=" + pre.length());
            return pre;
        }
        dlog("play url timeout clicked=" + clicked + " n0=" + n0 + " m0=" + m0);
        return null;
    }

    // ===================================================================
    // playerContent
    //   v6：分片名混淆已逆出（单表替换），所以直接给播放器一条改写好的 m3u8。
    //       流程：打开播放页 → 过 CAP → 点第 ep 集 → 抓页面解好密的 m3u8 明文
    //            → 还原分片名 → 挂到 127.0.0.1 小服务 → parse=0 直接播。
    //   取不到就退回 v5 的网页模式（parse=1 + 播放页），至少页面自己还能播。
    //   注意：ParseJob 里最终 URL = parse.getUrl() + result.getUrl()，
    //   所以 parse=0 时 url 放直链、playUrl 必须留空。
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
        try {
            String got = grabM3u8(mid, ep);
            // 最优：整片直链（_murl 接口给的签名 MP4，faststart + Range）
            //   不代理、不剥壳、不吃内存，播放器自己拖进度条。
            //   签名 1 小时过期，所以每次播放都现场取，不能缓存。
            if (got != null && got.startsWith(MP4_PREFIX)) {
                String direct = got.substring(MP4_PREFIX.length());
                JSONObject hd = new JSONObject();
                hd.put("User-Agent", UA);
                hd.put("Referer", base + "/");
                out.put("parse", 0);
                out.put("url", direct);
                out.put("playUrl", "");
                out.put("header", hd);
                dlog("player mp4 direct len=" + direct.length());
                return out.toString();
            }
            String m3u8 = got;
            if (m3u8 != null) {
                int[] stat = new int[2];
                String fixed = rewriteM3u8(m3u8, stat);
                String first = stat[0] > 0 ? firstUri(fixed) : null;
                int[] probe = first == null ? null : probeSeg(first);
                int kind = probe == null ? SEG_UNKNOWN : probe[0];
                int port = kind != SEG_UNKNOWN ? ensureServer() : -1;
                if (port > 0) {
                    String token = Long.toHexString(System.currentTimeMillis())
                            + Integer.toHexString(mid.hashCode());
                    String body;
                    if (kind == SEG_FMP4) {
                        // 裸 fMP4：直链，不过代理，最省事也最快
                        body = fixed;
                    } else {
                        // TS（含 PNG 外壳）：分片改走本地剥壳代理
                        int[] s2 = new int[2];
                        body = rewriteToProxy(m3u8, token, port, s2);
                        SEG_SKIP.put(token, probe[1]);
                        dlog("seg proxy skip=" + probe[1] + " n=" + s2[0] + " miss=" + s2[1]);
                    }
                    DOCS.put(token, body);
                    // 只留最近 8 份，防止长时间使用把内存堆满
                    for (String k : DOCS.keySet()) {
                        if (DOCS.size() <= 8) break;
                        if (k.equals(token)) continue;
                        DOCS.remove(k);
                        SEGS.remove(k);
                        SEG_SKIP.remove(k);
                    }
                    out.put("parse", 0);
                    out.put("url", "http://127.0.0.1:" + port + "/" + token + ".m3u8");
                    out.put("playUrl", "");
                    out.put("header", new JSONObject());
                    dlog("player direct kind=" + kind + " seg=" + stat[0] + " miss=" + stat[1]);
                    return out.toString();
                }
                dlog("player fallback kind=" + kind + " seg=" + stat[0] + " miss=" + stat[1]
                        + " first=" + first);
            }
        } catch (Throwable t) {
            dlog("player err " + t);
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
        DOCS.clear();
        SEGS.clear();
        SEG_SKIP.clear();
        try {
            if (segPool != null) segPool.shutdownNow();
        } catch (Throwable ignored) {
        }
        segPool = null;
        try {
            if (srvSocket != null) srvSocket.close();
        } catch (Throwable ignored) {
        }
        srvSocket = null;
        srvPort = -1;
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
