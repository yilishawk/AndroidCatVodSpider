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
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gaze（gazes.host）CatVod Spider —— WebView 中继版
 * ---------------------------------------------------------------------------
 * 【实现路线（2026-10-19 实测定稿；域名 2026-10-21 凯哥抓包更正为 gazes.host）】
 *   站点 = gazes.host，播放链四层：play 页 → GET /gaze_file_vl/pb/{片名}/{集}+gazes_v-@_info
 *   → gzip/base64 解 → nihilism 自定义链 → brotli → m3u8（fMP4，段名为混淆串）。
 *   其中：
 *     · /filter_movielist 列表接口：两个 proof header 名与值都是【每次动态】的，纯服务端
 *       复现不了 → 本实现不手搓，而是让 WebView 里的页面自己发一次请求，我们把它
 *       的 URL + headers + body 原样抓下来，再改 body 参数重放（fetch  credentials:'include'）。
 *     · 播放链的 nihilism 层：密钥派生未逆出（边界，见下）→ 同样交给 WebView：加载
 *       /play/{id} 后由页面自己解包，我们只 hook URL.createObjectURL / XHR / fetch，
 *       把真实段 URL（file.icve.com.cn/rc/xx/yy/{32hex}.m4s）抓出来，在 Java 侧重写成
 *       一份标准 m3u8 落到缓存目录，返回本地播放列表。
 *
 * 【已实证的事实（不是推测）】
 *   · 2026-10-19 Playwright 实测：hook XHR.open 后播放 3 分钟收到 75 个真实段 URL，
 *     均为 file.icve.com.cn/rc/{a}/{b}/{32HEX}.m4s，公开 CDN，免 Referer、支持 206。
 *   · m3u8 = fMP4：EXT-X-MAP 指向真实 mp4 init，后接 322 段。
 *   · 列表接口响应形如 {"code":1,"total":2546,"mlist":[{id,title,grade,cover_img,mid}]}。
 *   · 详情页集数挂在元素属性 data-baseid 上（取值是混淆串，本实现【不做解码】，
 *     原样当作 /play/{baseid} 的 id 用即可）。
 *
 * 【边界（如实标注）】
 *   ★ nihilism / metaphysics 密钥派生【未逆出】：8069 层已 44/44 全破解（AES-256-CTR，
 *     固定密钥），但 nihilism 在本机 Node wasm 环境里连已知密钥的 8069 都返回空，
 *     说明派生用到了抓不到的环境分量。因此【不能】做纯服务端版，必须走 WebView 中继。
 *   ★ 本版播放依赖 WebView 内真实播放器跑起来（会真的请求段），所以 playerContent
 *     需要等若干秒；超时/段数不足时返回错误信息而不是假链接。
 *   ★ CF：除 / 外全部路径有交互式验证；WebView 由真实内核过，headless 不行。
 */
public class Gaze extends Spider {

    // ★ 2026-10-21 凯哥抓包实证的真实域名（enc.TXT）：gazes.host（带 s）。
    //   旧版写成 gaze.host（无 s）→ 整个 webLoad/重放/播放链全开在错域，推荐页全空。
    private static final String BASE = "https://gazes.host";
    private static final String FILTER_URL = BASE + "/filter";
    private static final String LIST_API = "/filter_movielist";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12; K30) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Mobile Safari/537.36";

    private static final long LOAD_TIMEOUT_MS = 30000;   // 页面加载 + CF 挑战（注释用，实际见 webLoad）
    // 壳子对 homeContent 常见有 10s 级超时；这两个值直接决定首页能不能出，
    // 宁可早失败走兜底，也别拖到壳子把结果整个丢掉。
    private static final long LIST_WAIT_MS = 3000;       // 等页面自发一次 filter_movielist
    private static final long REPLAY_TIMEOUT_MS = 6000;  // 重放请求等待
    /** 等 onPageFinished 的上限（兜底值）。
     *  ★ 列表链路（fetchList）故意只用 PAGE_READY_LIST_MS=6s：homeContent 最坏
     *    6(ready) + 4.5(capture) ≈ 10.5s，仍守在壳子超时以内。全给 15s 会和
     *    FETCH_BUDGET_MS 打架，把首页又拖过超时线。 */
    private static final long PAGE_READY_MS = 15000;
    private static final long PAGE_READY_LIST_MS = 6000;
    /** fetchList 总预算：超了就立刻走"吃已抓响应"的兜底，绝不让壳子等到丢结果。 */
    private static final long FETCH_BUDGET_MS = 9000;
    private static final long PLAY_WAIT_MS = 90000;      // 播放抓段总等待（段播得慢，给足）
    private static final int  PLAY_MIN_SEG = 3;          // 少于这么多段视为没抓到

    private Context appCtx;
    private Handler webHandler;
    private WebView webView;
    private final Object webLock = new Object();

    /** true = 上一次 fetchList 没走重放，吃的是页面第一次自发请求的响应（第一页数据）。 */
    private volatile boolean lastWasFallback = false;

    // ===================================================================
    // init：WebView 挂在【主线程 Looper】。
    //   ★ 绝不能在自建 HandlerThread 上创建 WebView（Android 6 / TV 盒实测 native 崩溃）。
    //   ★ 不在 init 同步创建，改为首次用时在主线程懒创建，避免主线程 await 死锁。
    // ===================================================================
    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
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
        w.getSettings().setMediaPlaybackRequiresUserGesture(false); // 播放页自动起播
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }
        });
        return w;
    }

    /**
     * JS 桥（备份通道，非必需）。
     *
     * 2026-10-20 真机实证：凯哥这台机器上 addJavascriptInterface 注入的对象在页面里
     * 取不到（见 injectCapJs / webBridge 的注释）。这里保留只为"万一别的壳子可用"，
     * 主通路已经改成 evaluateJavascript 的返回值通道，不依赖它。
     */
    public static class JsBridge {
        @JavascriptInterface
        public void onResult(String s) { /* 主通道够用，备份仅记录不处理 */ }
    }

    // ===================================================================
    // JS 回传包装：把"自行负责回传结果"的脚本原样执行并同步返回。
    //
    //   主通道：evaluateJavascript 的 ValueCallback。Chromium 会把顶层 Promise
    //   的 resolve 值喂回回调（2026-10-20 真机日志 injectCapJs -> 4:null 证明
    //   回调确实回来了）。所以这里【不要】再套一层 Promise.resolve().then() ——
    //   对 __gzReplay / waitListCapture 这种本来就返回 Promise 的调用，嵌套链会让
    //   主通道拿到 "{}" 而非 resolve 值。直接透传即可。
    // ===================================================================

    /** 把 JS 原样包成 try/catch 形式，异常也回成 JSON，避免 evaluateJavascript 拿到 "undefined"。 */
    private static String wrapJs(String js) {
        return "(function(){try{return (" + js + ");}"
             + "}catch(e){return JSON.stringify({error:String(e)});}})();";
    }

    // ===================================================================
    // 注入脚本：抓页面自己发的请求 / blob / window.allnothing
    //   window.__gz      = { list:[{url,headers,body,resp}], media:[url...], info:[...],
    //                        m3u8:'', blob:'', allnothing:'', epi:'' }
    //   window.__gzSnap  = () => JSON 快照
    //   window.__gzReplay= (formBody) => Promise<string>  复用抓到的 headers 重放
    //   window.__gzKick  = () => 点播放按钮
    // ===================================================================
    private static final String CAP_JS = String.join("\n", new String[]{
        "(function(){",
        " if(window.__gz) return;",
        " var cap={list:[],media:[],info:[],m3u8:'',blob:'',mapUri:'',allnothing:'',epi:''};",
        " window.__gz=cap;",
        " function keep(k,o){var a=cap[k]; if(a.length<900)a.push(o);}",
        " var oo=XMLHttpRequest.prototype.open, os=XMLHttpRequest.prototype.send, oh=XMLHttpRequest.prototype.setRequestHeader;",
        " XMLHttpRequest.prototype.open=function(m,u){this.__m=m;this.__u=String(u);return oo.apply(this,arguments);};",
        " XMLHttpRequest.prototype.setRequestHeader=function(k,v){try{if(!this.__h)this.__h={};this.__h[k]=v;}catch(e){}return oh.apply(this,arguments);};",
        " XMLHttpRequest.prototype.send=function(b){",
        "  var s=this; s.__h=s.__h||{}; try{if(b!=null)s.__b=String(b);}catch(e){}",
        "  s.addEventListener('load',function(){",
        "   var u=s.__u||'';",
        "   try{",
        "    if(u.indexOf('filter_movielist')>=0) keep('list',{url:u,headers:s.__h,body:s.__b||'',resp:s.responseText||''});",
        "    else if(u.indexOf('gaze_file_vl')>=0||u.indexOf('_info')>=0) keep('info',{url:u});",
        "    else if(/\\.(m4s|mp4|ts|m3u8)(\\?|$)/.test(u)) keep('media',u);",
        "   }catch(e){}",
        "  });",
        "  return os.apply(this,arguments);",
        " };",
        " if(window.fetch){",
        "  var of=window.fetch;",
        "  window.fetch=function(input,init){",
        "   var u=(typeof input==='string')?input:((input&&input.url)||'');",
        "   var b=(init&&init.body!=null)?String(init.body):'';",
        "   var hd={};",
        "   try{var h=(init&&init.headers); if(h){ if(h.forEach){h.forEach(function(v,k){hd[k]=v;});} else {for(var kk in h)hd[kk]=h[kk];} } }catch(e){}",
        "   var p=of.apply(this,arguments);",
        "   p.then(function(r){",
        "    try{",
        "     if(u.indexOf('filter_movielist')>=0){ r.clone().text().then(function(t){keep('list',{url:u,headers:hd,body:b,resp:t});}); }",
        "     else if(u.indexOf('gaze_file_vl')>=0||u.indexOf('_info')>=0){ keep('info',{url:u}); }",
        "     else if(/\\.(m4s|mp4|ts|m3u8)(\\?|$)/.test(u)){ keep('media',u); }",
        "    }catch(e){}",
        "   }).catch(function(){});",
        "   return p;",
        "  };",
        " }",
        " if(window.URL&&window.URL.createObjectURL){",
        "  var oc=window.URL.createObjectURL;",
        "  window.URL.createObjectURL=function(bl){",
        "   try{ if(bl&&typeof bl.text==='function'){ bl.text().then(function(t){cap.blob=t; if(t.indexOf('#EXTM3U')>=0)cap.m3u8=t; var m=/EXT-X-MAP:URI=\"([^\"]+)\"/.exec(t); if(m)cap.mapUri=m[1]; }); } }catch(e){}",
        "   return oc.apply(this,arguments);",
        "  };",
        " }",
        " window.__gzSnap=function(){",
        "  var o={m3u8:cap.m3u8,blob:cap.blob,mapUri:cap.mapUri,media:cap.media,info:cap.info,listCount:cap.list.length};",
        "  try{if(typeof window.allnothing==='string')o.allnothing=window.allnothing;}catch(e){}",
        "  try{if(typeof window.epi==='function')o.epi=String(window.epi());}catch(e){}",
        "  return JSON.stringify(o);",
        " };",
        " window.__gzReplay=function(fd){",
        "  var r=cap.list[0]; if(!r) return Promise.resolve('');",
        "  var h={}; for(var k in r.headers) h[k]=r.headers[k];",
        "  h['content-type']='application/x-www-form-urlencoded;charset=UTF-8';",
        "  return fetch(r.url,{method:'POST',headers:h,body:fd,credentials:'include'})",
        "   .then(function(x){",
        "     if(!x.ok){ return JSON.stringify({__gzHttp:x.status,__gzUrl:r.url}); }",
        "     return x.text();",
        "   })",
        "   .catch(function(e){ return JSON.stringify({__gzErr:String(e)}); });",
        " };",
        " window.__gzKick=function(){",
        "  var cs=['.playbtn','.play-btn','[class*=play]','.v-play','.episode','.star-uborder','#player'];",
        "  for(var i=0;i<cs.length;i++){var e=document.querySelector(cs[i]); if(e){try{e.click();}catch(err){}}}",
        " };",
        "})();"
    });

    // ===================================================================
    // WebView 编排：装 hook → 加载 → 等 → 执行 js → 桥回传
    //   全部 WebView 操作 post 到 webHandler（主线程）；
    //   所有 latch.await 都发生在【调用方线程】，绝不阻塞主线程，否则 JS 回调跑不起来。
    // ===================================================================

    /**
     * 装 hook + 加载 url，并【等到页面加载完成】才返回。
     *
     * 为什么必须等（真机实测 2026-10-20）：原实现 loadUrl 后立刻返回，紧接着的
     * webBridge 就在 document 还没建好的空页面上 evaluateJavascript，Chromium 直接
     * 静默丢弃 —— 表现就是 js 一行没跑、既不回 cap-not-injected 也不回数据，
     * 一路干等到 bridge timeout。所以这里用第二个 latch 等 onPageFinished。
     */
    private boolean webLoad(final String url) { return webLoad(url, PAGE_READY_MS); }

    /** @param readyMs 等 onPageFinished 的上限。列表链路给短值，保证总预算闭合。 */
    private boolean webLoad(final String url, final long readyMs) {
        if (webHandler == null) return false;
        final boolean[] ok = new boolean[1];
        final String[] finishedUrl = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        final CountDownLatch pageDone = new CountDownLatch(1);
        webHandler.post(() -> {
            synchronized (webLock) {
                try {
                    if (webView == null) webView = createWebView();
                    if (webView == null) { latch.countDown(); pageDone.countDown(); return; }
                    webView.addJavascriptInterface(new JsBridge(), "GazeBridge");
                    webView.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageStarted(WebView view, String u, android.graphics.Bitmap f) {
                            injectCapJs(view);
                        }
                        @Override
                        public void onPageFinished(WebView view, String u) {
                            // onPageFinished 时 document 一定 ready，保底再注入一次。
                            // CAP_JS 自带幂等（if(window.__gz) return）。
                            injectCapJs(view);
                            finishedUrl[0] = String.valueOf(u);
                            pageDone.countDown();
                        }
                    });
                    webView.loadUrl(url);
                    ok[0] = true;
                } catch (Throwable t) {
                    dlog("webLoad failed: " + t);
                } finally {
                    latch.countDown();
                }
            }
        });
        try { latch.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        if (!ok[0]) return false;
        // 等页面 ready。CF 挑战/慢网络下给足；等不到也继续（后面 webWithCap 会兜），
        // 但把结果打进日志，便于区分"页面没加载完"和"js 没执行"。
        boolean finished;
        try { finished = pageDone.await(readyMs, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { finished = false; }
        dlog("webLoad " + url + " pageFinished=" + finished + " u=" + finishedUrl[0]);
        return true;
    }

    /**
     * 注入 CAP_JS（幂等）。任何一次失败都只写日志，不抛。
     *
     * ★ 第三轮真机日志（2026-10-20）推翻了我上一轮的读法，这里记下来别再踩：
     *   injectCapJs -> 4:null 不是"Chromium 静默丢弃了 evaluateJavascript"，
     *   而是"脚本跑了，返回值就是 undefined"——CAP_JS 本身是个 IIFE，没有 return，
     *   evaluateJavascript 把 undefined 序列化成 JSON 字符串 "null"（长度正好 4）。
     *   它反过来证明【evaluateJavascript 这条通路是好的】。
     *   而同一轮里既没有 cap-not-injected 也没有 no-list-capture，只剩一个
     *   bridge timeout —— 唯一的自洽解释是：waitListCapture 的 js 跑了，
     *   但 GazeBridge.onResult(...) 抛 ReferenceError（页面里根本没有这个对象），
     *   被 webBridge 外层 try/catch 抓住，兜底分支又去调一次 GazeBridge、再抛一次，
     *   最后被 catch(e2){} 吞干净。所以坏的只是 addJavascriptInterface 注入的对象。
     * 因此这里在 CAP_JS 后面拼一个诊断表达式，一次 evaluateJavascript 同时完成
     * "装 hook" + "回报环境实情"。hasGz / hasBridge 打出来，一行定位。
     */
    private static void injectCapJs(WebView view) {
        // 回调结果打出来：只在 latch 上 countDown 是看不出所以然的，必须把 v 留下。
        String diag = CAP_JS + "\n;JSON.stringify({"
                + "hasGz:(typeof window.__gz)!=='undefined',"
                + "hasBridge:(typeof window.GazeBridge)!=='undefined',"
                + "hasBridgeFn:!!(window.GazeBridge&&window.GazeBridge.onResult),"
                + "hasReplay:(typeof window.__gzReplay)!=='undefined',"
                + "hasSnap:(typeof window.__gzSnap)!=='undefined'"
                + "});";
        try { view.evaluateJavascript(diag, v -> dlog("injectCapJs -> " + shortv(v))); }
        catch (Throwable t) { dlog("injectCapJs: " + t); }
    }

    private static String shortv(String v) {
        if (v == null) return "null";
        int n = Math.min(v.length(), 300);
        return n + ":" + v.substring(0, n);
    }

    /**
     * 显式确保 CAP_JS 已注入，再执行 js。
     *
     * 为什么不能只依赖 onPageStarted/onPageFinished：页面自发请求可能发生在两者之间，
     * 也可能页面始终没跑我们的 hook。这里在执行任何采集脚本【之前】先注入一次，
     * 至少保证 window.__gz / window.__gzSnap / window.__gzReplay 一定存在，
     * 让「hook 没装上」从「静默等到超时」变成「可观测」。
     */
    private String webWithCap(final String js, final long timeoutMs) {
        final CountDownLatch injected = new CountDownLatch(1);
        if (webHandler != null) {
            webHandler.post(() -> {
                synchronized (webLock) {
                    try { if (webView != null) injectCapJs(webView); } catch (Throwable ignored) {}
                    injected.countDown();
                }
            });
            try { injected.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
        return webBridge(js, timeoutMs);
    }

    /** 在主线程 evaluateJavascript，返回其原始返回值（JSON 编码的字符串）。 */
    private String webEval(final String js, final long timeoutMs) {
        final String[] out = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        if (webHandler == null) return null;
        webHandler.post(() -> {
            synchronized (webLock) {
                try {
                    if (webView == null) { latch.countDown(); return; }
                    webView.evaluateJavascript(js, v -> { out[0] = v; latch.countDown(); });
                } catch (Throwable t) { latch.countDown(); }
            }
        });
        try { if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null; } catch (InterruptedException e) { return null; }
        return out[0];
    }

    /** webEval 版：先确保 CAP_JS 已注入，再执行并取返回值。 */
    private String webEvalWithCap(final String js, final long timeoutMs) {
        final CountDownLatch injected = new CountDownLatch(1);
        if (webHandler != null) {
            webHandler.post(() -> {
                synchronized (webLock) {
                    try { if (webView != null) injectCapJs(webView); } catch (Throwable ignored) {}
                    injected.countDown();
                }
            });
            try { injected.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
        return webEval(js, timeoutMs);
    }

    /** 执行一段【自行负责回传】的 JS。主通道（evaluateJavascript 返回值）为主。 */
    private String webBridge(final String js, final long timeoutMs) {
        final String[] out = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        webHandler.post(() -> {
            synchronized (webLock) {
                try {
                    if (webView == null) { latch.countDown(); return; }
                    webView.evaluateJavascript(wrapJs(js),
                            v -> { dlog("eval -> " + shortv(v)); out[0] = v; latch.countDown(); });
                } catch (Throwable t) { dlog("webBridge: " + t); latch.countDown(); }
            }
        });
        long deadline = System.currentTimeMillis() + timeoutMs + 3000;
        try { latch.await(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS); }
        catch (InterruptedException ignored) {}

        // 1) 主通道：evaluateJavascript 的 ValueCallback。
        //    真机 Chromium 会把顶层 Promise 的 resolve 值喂回回调（v4 已实证）。
        String r = norm(out[0]);
        if (r != null) { dlog("bridge: got via eval-callback"); return r; }
        dlog("bridge: timeout js=" + head(js));
        return "{\"error\":\"bridge timeout\",\"eval\":" + JSONObject.quote(String.valueOf(out[0])) + "}";
    }

    /** evaluateJavascript 返回值规整：剥 JSON 字符串引号；空值一律返回 null（表示没拿到）。 */
    private static String norm(String v) {
        String s = stripQuotes(v);
        if (TextUtils.isEmpty(s) || "null".equals(s) || "undefined".equals(s)) return null;
        return s;
    }

    /**
     * 把 evaluateJavascript 回调里那份【JSON 编码字符串】解回原始串。
     *
     * ★ 这里必须一次遍历正解开转义，不能像老代码那样 .replace("\\n","\n")
     *   再 .replace("\\\\","\\") 顺序收尾 —— 那样遇到原始串里的字面反斜杠
     *   （JSON 里是 "\\\\"）会被前面的 \n 规则先啃掉一半，把 a\nb 变成 a<换行>b。
     *   列表响应 / m3u8 文本里都可能带反斜杠，踩上就是静默数据损坏。
     *   norm() 负责在其上判空，这里只管解码。
     */
    private static String stripQuotes(String v) {
        if (v == null) return null;
        if (v.length() < 2 || v.charAt(0) != '"' || v.charAt(v.length() - 1) != '"') return v;
        StringBuilder sb = new StringBuilder(v.length());
        int end = v.length() - 1;
        for (int i = 1; i < end; i++) {
            char c = v.charAt(i);
            if (c != '\\') { sb.append(c); continue; }
            if (++i >= end) break;
            char e = v.charAt(i);
            switch (e) {
                case 'n':  sb.append('\n'); break;
                case 'r':  sb.append('\r'); break;
                case 't':  sb.append('\t'); break;
                case 'b':  sb.append('\b'); break;
                case 'f':  sb.append('\f'); break;
                case '/':  sb.append('/');  break;
                case '"':  sb.append('"');  break;
                case '\\': sb.append('\\'); break;
                case 'u':
                    if (i + 4 < end) {
                        try { sb.append((char) Integer.parseInt(v.substring(i + 1, i + 5), 16)); i += 4; }
                        catch (Exception ignore) { sb.append('u'); }
                    } else sb.append('u');
                    break;
                default: sb.append(e);
            }
        }
        return sb.toString();
    }

    private static void dlog(String s) {
        try { SpiderDebug.log("[GAZE] " + s); } catch (Throwable ignored) {}
    }

    private static String head(String s) {
        if (s == null) return "null";
        int n = Math.min(s.length(), 700);
        return s.length() + "::" + s.substring(0, n);
    }

    // ===================================================================
    // 列表：/filter_movielist（抓页面自发请求的 headers，再重放改参数）
    // ===================================================================

    /**
     * 等页面自己发出至少一次 filter_movielist（拿动态 proof headers）。
     *
     * 改成【返回 Promise】：轮询在 setTimeout 链里跑，命中/超时就 resolve，
     * 值由 evaluateJavascript 的 ValueCallback 收回来（2026-10-20 实证可用）。
     * 仍然先做 window.__gz 存在性检查：不存在就【立刻】resolve 错误，
     * 而不是静默轮询到超时。
     */
    private String waitListCapture() {
        String js = "new Promise(function(res){var t0=Date.now();"
                + "if(!window.__gz){res(JSON.stringify({error:'cap-not-injected'}));return;}"
                + "(function tick(){"
                + " if(window.__gz.list.length>0){res(JSON.stringify(window.__gz.list[0]));return;}"
                + " if(Date.now()-t0>" + LIST_WAIT_MS + "){res(JSON.stringify({error:'no-list-capture',n:window.__gz.list.length,media:window.__gz.media.length}));return;}"
                + " setTimeout(tick,150);"
                + "})();})";
        return webWithCap(js, LIST_WAIT_MS + 1500);
    }

    /** 重放：改 form body，复用抓到的 headers。__gzReplay 本身就返回 Promise，直接透传。 */
    private String replayList(String formBody) {
        String js = "window.__gzReplay(" + JSONObject.quote(formBody) + ")";
        return webWithCap(js, REPLAY_TIMEOUT_MS);
    }

    private static String buildBody(String mform, String mcountry, String genre,
                                    String years, String album, String sort, String title, String page) {
        StringBuilder b = new StringBuilder();
        b.append("mform=").append(enc(def(mform, "all")));
        b.append("&mcountry=").append(enc(def(mcountry, "all")));
        b.append("&genre_arr=").append(enc(def(genre, "all")));
        b.append("&page=").append(enc(def(page, "1")));
        b.append("&sort=").append(enc(def(sort, "updatetime")));
        b.append("&album=").append(enc(def(album, "all")));
        b.append("&title=").append(enc(def(title, "")));
        b.append("&years=").append(enc(def(years, "all")));
        return b.toString();
    }

    private static String def(String s, String d) { return TextUtils.isEmpty(s) ? d : s; }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    /**
     * 拉一页列表。
     *
     * 顺序是死的：抓 cap → 试重放（翻页/筛选/搜索）→ 重放不可用就吃 cap.resp。
     *
     * 为什么 resp 兜底是主路径而不是备用：抓包实证（gaze_filter_api.txt）里 proof
     * header 是两个【随机名 + 32/64hex】的键（4bebc287f6f12aeccf9894b1336fcf41 /
     * x-gaze-6e0b905bb46323574c3277be），这形态极像一次性签名，同一组 header 第二次
     * 发很可能被服务端拒。所以第一次自发请求的响应（cap.resp）才是可靠数据源；
     * 重放只负责改参数（page/title/genre…），拿不到也不至于首页全空。
     */
    private String fetchList(String mform, String mcountry, String genre, String years,
                             String album, String sort, String title, String page) {
        if (!webLoad(FILTER_URL, PAGE_READY_LIST_MS)) return "{\"error\":\"webLoad failed\"}";
        final long budget = System.currentTimeMillis() + FETCH_BUDGET_MS;
        String cap = waitListCapture();
        if (System.currentTimeMillis() > budget && cap != null && cap.contains("resp")) {
            // 预算已烧穿但抓到了响应：跳过重放，直接吃兜底
            dlog("budget exhausted, skip replay");
            try {
                JSONObject c = safeJson(cap);
                String r = c != null ? c.optString("resp", "") : "";
                if (r != null && !r.isEmpty()) { lastWasFallback = true; return r; }
            } catch (Exception ignored) {}
        }
        dlog("capture: " + head(cap));
        String capturedResp = null;
        if (cap != null && !cap.isEmpty() && !"\"\"".equals(cap)) {
            JSONObject c = safeJson(cap);
            if (c != null) {
                String r = c.optString("resp", "");
                if (r != null && !r.isEmpty()) capturedResp = r;
            }
        }
        String body = buildBody(mform, mcountry, genre, years, album, sort, title, page);
        String out = replayList(body);
        if (out != null && !out.isEmpty() && out.contains("mlist")) {
            lastWasFallback = false;
            return out;
        }
        // 重放失败：把失败原因分型写进日志。老代码这里只有一个 "replay unusable"，
        // 分不清是 419 被拒 / 网络错 / 没抓到 cap，排查时只能靠猜。
        // __gzReplay 现在会把 HTTP 状态和异常回成 JSON，这里就能读出来。
        dlog("replay failed: " + head(out));
        if (out != null && out.contains("\"__gzHttp\"")) {
            dlog("replay rejected by server (proof header is one-shot) -> pagination/search unavailable");
        } else if (out != null && out.contains("\"__gzErr\"")) {
            dlog("replay network error -> check CF / connectivity");
        }
        // 重放失败（很可能是 proof header 一次性）：回落第一页真实响应
        if (capturedResp != null) {
            lastWasFallback = true;
            dlog("replay unusable, fallback to captured resp (page forced 1)");
            return capturedResp;
        }
        lastWasFallback = false;
        return "{\"error\":\"no list data\",\"detail\":\"replay failed and no captured resp\",\"replay\":"
                + JSONObject.quote(head(out)) + ",\"cap\":" + JSONObject.quote(head(cap)) + "}";
    }

    // ===================================================================
    // 详情：/play/{id} → 抠 data-baseid 作集数 id（不解码混淆串，原样回传）
    // ===================================================================
    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return Result.get().string();
        String id = ids.get(0);
        String url = BASE + "/play/" + id;
        if (!webLoad(url)) return Result.error("WebView 加载失败： " + url);
        // 等页面把集数渲染出来（data-baseid 是 SSR 就有的，通常很快）
        String picked = null;
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            // 同步脚本：直接 return JSON，由 evaluateJavascript 的 ValueCallback 收。
            // ★ 不再调 GazeBridge.onResult —— 2026-10-20 真机实证那个对象在页面里取不到。
            String raw = webWithCap(
                    "(function(){var o=[];var es=document.querySelectorAll('[data-baseid]');"
                            + "for(var i=0;i<es.length;i++){var e=es[i];var b=e.getAttribute('data-baseid');"
                            + "var t=(e.innerText||e.textContent||'').trim();"
                            + "if(b)o.push({n:t||('第'+(i+1)+'集'),id:b});}"
                            + "return JSON.stringify(o);})();", 5000);
            picked = raw;
            if (raw != null && raw.contains("\"id\"")) break;
            try { Thread.sleep(600); } catch (InterruptedException ignored) {}
        }
        Vod v = new Vod();
        v.setVodId(id);
        v.setVodName(id);
        try {
            JSONArray arr = new JSONArray(picked);
            List<String> eps = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String n = o.optString("n", "第" + (i + 1) + "集");
                String bid = o.optString("id", "");
                if (bid.isEmpty()) continue;
                eps.add(n + "$" + bid);
            }
            if (!eps.isEmpty()) {
                v.setVodPlayFrom("默认线路");
                v.setVodPlayUrl(TextUtils.join("#", eps));
                v.setVodRemarks("共 " + eps.size() + " 集");
            } else {
                v.setVodRemarks("[GAZE] 未抠到 data-baseid，原始: " + head(picked));
            }
        } catch (Exception e) {
            v.setVodRemarks("[GAZE] 解析失败: " + e + " raw=" + head(picked));
        }
        dlog("detail id=" + id + " picked=" + head(picked));
        return Result.string(v);
    }

    // ===================================================================
    // 播放：WebView 起播 → 抓真实段 URL → 本地重写 m3u8
    // ===================================================================
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (TextUtils.isEmpty(id)) return Result.error("播放 id 为空");
        // id 形如 "第1集$d11CRlpcVV9VQQ=="
        String name = "";
        String playId = id;
        int d = id.indexOf('$');
        if (d >= 0) { name = id.substring(0, d); playId = id.substring(d + 1); }
        if (playId.startsWith("http://") || playId.startsWith("https://")) {
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", UA);
            h.put("Referer", BASE + "/");
            return Result.get().parse(0).url(playId).header(h).string();
        }

        String url = BASE + "/play/" + playId;
        if (!webLoad(url)) return Result.error("WebView 加载失败： " + url);
        // 起点播（多处候选选择器都点一遍）
        webEvalWithCap("(function(){try{window.__gzKick&&window.__gzKick();}catch(e){}})();", 4000);
        // 轮询抓段
        String best = null;
        int bestN = 0;
        long deadline = System.currentTimeMillis() + PLAY_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            String raw = webEvalWithCap("(function(){try{return window.__gzSnap();}catch(e){return '';}})();", 5000);
            String snap = stripQuotes(raw);
            if (snap != null && !snap.isEmpty()) {
                try {
                    JSONObject o = new JSONObject(snap);
                    JSONArray media = o.optJSONArray("media");
                    int n = media == null ? 0 : media.length();
                    if (n > bestN) { bestN = n; best = snap; }
                } catch (Exception ignored) {}
            }
            // 段数够就收（多等一会儿让播放器把前段都请求完）
            if (bestN >= PLAY_MIN_SEG) {
                try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
                String raw2 = webEvalWithCap("(function(){try{return window.__gzSnap();}catch(e){return '';}})();", 5000);
                String s2 = stripQuotes(raw2);
                if (s2 != null && !s2.isEmpty()) {
                    try {
                        JSONObject o2 = new JSONObject(s2);
                        JSONArray m2 = o2.optJSONArray("media");
                        if (m2 != null && m2.length() >= bestN) best = s2;
                    } catch (Exception ignored) {}
                }
                break;
            }
            try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
        }
        dlog("player playId=" + playId + " segs=" + bestN);
        if (best == null || bestN < PLAY_MIN_SEG) {
            return Result.error("未抓到足够分片（" + bestN + "/" + PLAY_MIN_SEG
                    + "）。可能 CF 没过或播放器没起播。抓到的快照: " + head(best));
        }

        File f = writePlaylist(playId, best);
        if (f == null) return Result.error("分片已抓到但 playlist 写出失败");

        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", BASE + "/");
        // 本地 m3u8：parse=0，壳子/播放器直接当 HLS 播
        return Result.get().parse(0).url(f.getAbsolutePath()).header(h).string();
    }

    /** 用抓到的真实段 URL + EXT-X-MAP 重写一份标准本地 m3u8。 */
    private File writePlaylist(String playId, String snap) {
        try {
            JSONObject o = new JSONObject(snap);
            String m3u8 = o.optString("m3u8", "");
            String blob = o.optString("blob", "");
            if (m3u8.isEmpty()) m3u8 = blob;
            JSONArray media = o.optJSONArray("media");

            LinkedHashSet<String> all = new LinkedHashSet<>();
            if (media != null) for (int i = 0; i < media.length(); i++) {
                String u = media.optString(i, "").trim();
                if (u.startsWith("http")) all.add(u);
            }
            if (all.isEmpty() && !m3u8.isEmpty()) {
                for (String line : m3u8.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("http")) all.add(t);
                }
            }
            if (all.isEmpty()) return null;

            // EXT-X-MAP（init mp4）优先级：JS 同步抠到的 mapUri > m3u8 文本里的 > media 里的 .mp4
            String mapUri = o.optString("mapUri", "");
            if (TextUtils.isEmpty(mapUri)) {
                Matcher mm = Pattern.compile("#EXT-X-MAP:URI=\"([^\"]+)\"").matcher(m3u8);
                if (mm.find()) mapUri = mm.group(1);
            }
            if (TextUtils.isEmpty(mapUri)) mapUri = firstOf(all, ".mp4");

            // .mp4 是 init 段，不能当普通分片发
            LinkedHashSet<String> segs = new LinkedHashSet<>();
            for (String s : all) if (!s.endsWith(".mp4")) segs.add(s);
            if (segs.isEmpty()) return null;

            StringBuilder sb = new StringBuilder();
            sb.append("#EXTM3U\n");
            sb.append("#EXT-X-VERSION:7\n");
            sb.append("#EXT-X-MEDIA-SEQUENCE:0\n");
            sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n");
            sb.append("#EXT-X-INDEPENDENT-SEGMENTS\n");
            sb.append("#EXT-X-TARGETDURATION:15\n");
            if (mapUri != null) sb.append("#EXT-X-MAP:URI=\"").append(mapUri).append("\"\n");
            for (String s : segs) {
                sb.append("#EXTINF:5.000000,\n");
                sb.append(s).append("\n");
            }
            File dir = appCtx.getCacheDir();
            File out = new File(dir, "gaze_" + safeName(playId) + ".m3u8");
            FileOutputStream fos = new FileOutputStream(out);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
            dlog("playlist written " + out.getAbsolutePath() + " segs=" + segs.size());
            return out;
        } catch (Throwable t) {
            dlog("writePlaylist err " + t);
            return null;
        }
    }

    private static String firstOf(LinkedHashSet<String> set, String suffix) {
        for (String s : set) if (s.endsWith(suffix)) return s;
        return null;
    }

    private static String safeName(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // ===================================================================
    // 首页 / 分类 / 搜索
    // ===================================================================
    @Override
    public String homeContent(boolean filter) throws Exception {
        String raw = fetchList("all", "all", "all", "all", "all", "updatetime", "", "1");
        JSONObject j = safeJson(raw);
        List<Vod> list = new ArrayList<>();
        if (j != null) {
            JSONArray arr = pickArray(j);
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                Vod v = parseItem(arr.optJSONObject(i));
                if (v != null) list.add(v);
            }
        }
        dlog("home items=" + list.size() + " raw=" + head(raw));
        if (list.isEmpty()) list.add(debugVod(raw));
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
        String raw = fetchList(mm[0], mm[1], genre, years, "all", "updatetime", "", String.valueOf(page));
        JSONObject j = safeJson(raw);
        List<Vod> list = new ArrayList<>();
        int total = 0;
        int serverPages = 0;   // ★ 服务器响应里现成的 pages 字段（enc.TXT：gazes.host 有 "pages":72）
        if (j != null) {
            JSONArray arr = pickArray(j);
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                Vod v = parseItem(arr.optJSONObject(i));
                if (v != null) list.add(v);
            }
            total = j.optInt("total", arr != null ? arr.length() : 0);
            serverPages = j.optInt("pages", 0);
        }
        // ★ 真实页长 = 本响应 mlist 条数（enc.TXT 实测 35，旧版硬编 20 是错的）。
        //   拿不到就用当前 list 长度兜底，避免 limit=0。
        int realLimit = list.size() > 0 ? list.size() : 20;
        dlog("category tid=" + tid + " page=" + page + " fallback=" + lastWasFallback
                + " items=" + list.size() + " total=" + total + " serverPages=" + serverPages
                + " raw=" + head(raw));
        if (list.isEmpty()) list.add(debugVod(raw));
        // total 不传 0：真机实测壳子会把 0 显示成 2147483647（无限翻页），
        // 列表为空时直接给 list.size()，至少自洽。
        int reportTotal = total > 0 ? total : list.size();
        if (lastWasFallback) {
            // 重放不可用 → 手里只有第一页数据。谎报 pageCount 会让用户翻页翻到重复内容，
            // 所以老老实实停在 1 页，并把原因写进 remarks。
            return Result.get().vod(list).page(1, 1, realLimit, reportTotal).string();
        }
        // ★ pageCount 优先用服务器现成的 pages；没有才按 真实页长 推。
        //   旧版按 (total+19)/20 推，但真实页长是 35（每页 35 条），20 是错的 → 分页全错。
        int pageCount;
        if (serverPages > 0) pageCount = Math.max(serverPages, page);
        else if (reportTotal > 0 && realLimit > 0) pageCount = Math.max((reportTotal + realLimit - 1) / realLimit, page);
        else pageCount = page + (list.size() >= realLimit ? 1 : 0);
        return Result.get().vod(list).page(page, pageCount, realLimit, reportTotal).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        String raw = fetchList("all", "all", "all", "all", "all", "updatetime", key, "1");
        JSONObject j = safeJson(raw);
        List<Vod> list = new ArrayList<>();
        if (j != null) {
            JSONArray arr = pickArray(j);
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                Vod v = parseItem(arr.optJSONObject(i));
                if (v != null) list.add(v);
            }
        }
        if (lastWasFallback) {
            // search 的 body 只有 title 一个参数变了，若重放不可用，回落的是【第一页全部
            // 影片】而不是搜索结果 —— 拿它冒充搜索结果会误导，不如直接说失败。
            dlog("search fallback -> report failure instead of wrong data");
            Vod v = new Vod();
            v.setVodId("__gaze_debug__");
            v.setVodName("[GAZE] 搜索不可用（重放被拒，未回落假数据）");
            v.setVodRemarks("keywords=" + key + "；raw=" + head(raw));
            return Result.get().vod(list(v)).page(1, 1, Math.max(list(v).size(), 1), 1).string();
        }
        if (list.isEmpty()) list.add(debugVod(raw));
        int sLimit = list.size() > 0 ? list.size() : 20;
        return Result.get().vod(list).page(1, 1, sLimit, list.size()).string();
    }

    private static List<Vod> list(Vod v) {
        List<Vod> l = new ArrayList<>();
        l.add(v);
        return l;
    }

    public void destroy() {
        if (webHandler != null) {
            webHandler.post(() -> {
                synchronized (webLock) {
                    if (webView != null) { try { webView.destroy(); } catch (Throwable ignored) {} webView = null; }
                }
            });
        }
    }

    // ===================================================================
    // 工具
    // ===================================================================
    private JSONArray pickArray(JSONObject j) {
        if (j == null) return null;
        // gazes.host 实测字段是 mlist
        JSONArray a = j.optJSONArray("mlist");
        if (a != null) return a;
        for (String k : new String[]{"data", "list", "items", "results", "vod"}) {
            Object o = j.opt(k);
            if (o instanceof JSONArray) return (JSONArray) o;
            if (o instanceof JSONObject) {
                JSONArray a2 = ((JSONObject) o).optJSONArray("list");
                if (a2 == null) a2 = ((JSONObject) o).optJSONArray("vod");
                if (a2 == null) a2 = ((JSONObject) o).optJSONArray("items");
                if (a2 != null) return a2;
            }
        }
        return null;
    }

    private Vod parseItem(JSONObject o) {
        if (o == null) return null;
        // 实证：gazes.host 播放页 URL = /play/{mid}（见 gaze_play_real.html 里的
        // href="/play/cddda20389b70ff53287c064bc680104"，正好等于列表响应里的 mid）。
        // 列表里的数字 id（如 2342）拼 /play/ 是 404，所以 Vod id 必须取 mid。
        String id = pickStr(o, new String[]{"mid", "id", "vod_id", "movie_id", "tid"});
        String name = pickStr(o, new String[]{"title", "name", "vod_name", "vod_title"});
        String pic = pickStr(o, new String[]{"cover_img", "pic", "cover", "img", "vod_pic", "poster", "thumb"});
        if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name)) return null;
        Vod v = new Vod();
        v.setVodId(id);
        v.setVodName(name);
        if (!TextUtils.isEmpty(pic)) v.setVodPic(absUrl(pic));
        String remark = pickStr(o, new String[]{"grade", "remarks", "vod_remarks", "note", "year"});
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

    private static String firstNonNull(String... xs) {
        for (String x : xs) if (!TextUtils.isEmpty(x)) return x;
        return "";
    }

    private static JSONObject safeJson(String s) {
        if (TextUtils.isEmpty(s)) return null;
        try {
            int i = s.indexOf('{');
            return i >= 0 ? new JSONObject(s.substring(i)) : null;
        } catch (Exception e) { return null; }
    }

    private static Vod debugVod(String raw) {
        Vod v = new Vod();
        v.setVodId("__gaze_debug__");
        v.setVodName("[GAZE调试] 列表为空，点此看原因");
        String h = head(raw);
        if (h.length() > 500) h = h.substring(0, 500);
        v.setVodRemarks(h);
        return v;
    }

    /**
     * 首页分类 tab。
     *
     * 【边界，如实说】目前只有 "all"/"all" 这一组 (mform,mcountry) 是实证可用的
     * （来自 gaze_filter_api.txt 里 gaze.host 页面自己发的那次请求）。其余分类项
     * 没有 filter 页 option 值域的样本，所以 tid 先都映射到 all/all —— 效果是
     * 这几个 tab 标题不同、内容一样，但至少有内容、不会空页。
     * 等拿到 filter 页 <option> 值域（或抓一次真实筛选请求）再逐项填真值。
     */
    private List<Class> buildClasses() {
        List<Class> cs = new ArrayList<>();
        cs.add(new Class("all", "全部"));
        cs.add(new Class("tv", "电视剧"));
        cs.add(new Class("movie", "电影"));
        cs.add(new Class("bangumi", "番剧"));
        cs.add(new Class("variety", "综艺"));
        return cs;
    }

    /**
     * tid → (mform, mcountry)。除 all/all 外其余均未实证，见 buildClasses 注释。
     */
    private String[] mapTid(String tid) {
        switch (TextUtils.isEmpty(tid) ? "" : tid) {
            default:        return new String[]{"all", "all"};
        }
    }
}
