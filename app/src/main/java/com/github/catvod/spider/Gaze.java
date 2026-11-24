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
 * Gaze（gaze.red / gaze.host）CatVod Spider —— WebView 中继版 v16-probe
 * 目标工程：yilishawk/AndroidCatVodSpider（multiThread 分支）
 * ===========================================================================
 * 【v16-probe：纯诊断版，不改任何播放逻辑（2026-11-25）】
 *   根因已定位：电影走 vu/fMP4+MSE 链（play_source_group → gaze_file_vu/..._info
 *   → wasm 解密 → m3u8 → appendBuffer），不发 _murl、不产生 m3u8 文本 blob；
 *   v15 只认 _murl + createObjectURL 文本 blob，所以电影全空转到超时。
 *   vu 解密链依赖 wasm + 运行时守卫，Java 复刻代价大且易碎。
 *   本版只在注入脚本里加探针，回答三个问题，再决定 v17 怎么改：
 *     ① Android WebView 里 GazeHlsSupported 是多少（决定走 gazeManifest 还是 epi 分支）
 *     ② GazeBinary.utf8() 是否被调、能否抓到 #EXTM3U 明文
 *     ③ epi()/decodeFragmentURL()/appendBuffer 是否被调用、video.src 是什么
 *   探针数据通过 grabM3u8 里的 "VU-PROBE {...}" 日志输出。
 *   抓到后请把含 VU-PROBE 的整段日志发回。
 *
 * 【v13：把「很多片播不了」的成功率问题按根因修一遍（2026-11-21）】
 *   ① load() 名义上等 onPageFinished，实际只 sleep 1.5s。
 *      慢机上页面还没起来，后面的 capTick 全是 no-widget，28s 预算被白白烧掉。
 *      ⇒ 改成轮询 pageFinishedAt（由 WebViewClient.onPageFinished 打点，且只认目标 URL），
 *        真等页面加载完再往下走。
 *   ② CAP 组件压根没渲染出来时，原逻辑只会在原地空转。
 *      ⇒ detailContent / grabM3u8 各加一次「no-widget 且选集也没出来 → 重载页面」。
 *   ③ 选集按钮的合成 click 万一被页面忽略，原逻辑只会一直等到超时。
 *      ⇒ 新增 N.rectOf(path) 返回按钮坐标，点完 9s 没新数据就用真实触摸补一次
 *        （和过 CAP 同一套 dispatchTouchEvent）。
 *   ④ grabM3u8 失败直接回退网页模式（parse=1）—— 网页模式在壳子里同样过不了 CAP，
 *      必然播不了。⇒ 失败后先重试一次，仍失败才回退。
 *   ⑤ 直链推给壳子时**不再带 Referer**，只给 UA：bytetos 签名 URL 不依赖 Referer，
 *      带上反而可能撞某些 CDN 节点的 Referer 白名单。
 *
 * 【v11：退出播放后声音还在（2026-11-19 真机反馈）】
 *   根因不是本地代理在跑，是 ensureWv() 里开了
 *   setMediaPlaybackRequiresUserGesture(false) —— 播放页的 <video> 会在我们那个
 *   不可见的 WebView 里自动播放并出声；而 spider.destroy() 只在切站点时调用，
 *   「退出播放」根本不会触发它，所以声音一直响。
 *   ⇒ 两层修：① 注入脚本把 HTMLMediaElement.play 包一层强制 muted（页面逻辑照常，
 *     m3u8 blob 照常生成，只是不出声）；② playerContent 外层 finally 调 stopMedia()，
 *     不管走直链 / 本地代理 / 网页兜底哪条路，返回前都 pause + 静音。
 *
 * 【v10：列表全空的两个真凶（2026-11-19 现场二分定位）】
 *   ① 站点埋了个蜜罐：CAP 页里专门钓 window 上以 "__gz" 开头的全局。
 *      一旦发现，就把 cap-widget 从 DOM 里摘掉、正文换成「页面暂时不可用，请刷新后重试」，
 *      于是验证永远过不去 → filter_movielist 永远不发出 → 分类列表全空。
 *      证据（同一页面，只改注入内容）：
 *        window.__gz={} → cap-widget 消失；window.__gz2={} → 同样消失；
 *        window.__foo={} / window.hello123={} / window.gzs={} → 一切正常。
 *      ⇒ 现在每次进程随机命名空间（NS），且只挂一个对象 window.<NS>。
 *   ② cap.js 现在只认 isTrusted 的事件：
 *        合成 dispatchEvent(mousedown/mouseup/click) → 页面**一个请求都不发**
 *        （/event/cap/challenge 根本没出现），点了等于没点；
 *        真实鼠标点击 → POST /event/cap/challenge → 99% → POST /event/cap/redeem → 过。
 *      ⇒ 改成 JS 取勾选框中心坐标，Java 用 WebView.dispatchTouchEvent 发真实触摸
 *        （应用层派发，isTrusted=true）。
 *   顺带：CAP 冷启动要 13~14s，原来的 11s 预算必然超时，已放宽到 20s。
 *
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
    /** 首页列表总预算。首页现在走纯 HTTP（不需要 WebView/CAP），这条预算只影响分类页。
     *  分类页冷启动要过一次 CAP：实测 /event/cap/challenge 进度 0→99% 约 9s，
     *  再过 /event/cap/redeem 并跳转，到 filter_movielist 大约 13~14s。
     *  原来给 11s，冷启动必然超时 —— 这是「分类列表出不来」的第二个原因。 */
    private static final long LIST_BUDGET_MS = 20000;
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

    /** onPageFinished 的时刻。load() 靠它判断页面到底加载完没有。 */
    private volatile long pageFinishedAt;
    /** 目标 URL，用来过滤上一次页面迟到的 onPageFinished。 */
    private volatile String pageWant;

    /** 分类列表缓存：key = tid + 筛选串。壳子常连着问同一个分类，重复抓就要重过 CAP（13s+），
     *  这就是「分类加载太慢」的主因。按 key 分开缓存，切 tab / 切筛选互不污染，5 分钟有效。 */
    private static class ListCache {
        final String raw;
        final long at;
        ListCache(String raw, long at) { this.raw = raw; this.at = at; }
    }
    private final java.util.concurrent.ConcurrentHashMap<String, ListCache> listCaches =
            new java.util.concurrent.ConcurrentHashMap<String, ListCache>();
    private static final long LIST_CACHE_MS = 300000L;

    // ===================================================================
    // 首页的纯 HTTP 通道
    //   实测（2026-11-19，无 cookie、不跑 JS、不带 CAP）：
    //     GET https://gaze.red/  → 200，243 KB 服务端渲染 HTML
    //     每个条目是一个 <article>：
    //       <article class="or Dt"> ... <div class="lG badge-pill badge-default">豆瓣 8.7</div>
    //              <div class="lG badge-pill badge-danger">电视剧</div>
    //              <img data-src="https://tc-cdn.processon.com/..." alt="铁拳教育">
    //              <a href="play/6be8f9058d86abfc118d707883186ac2">
    //              <h3 class="Mo"><span class="Y">铁拳教育</span></h3> ...
    //   注意 href 是 "play/..."（**没有前导斜杠**），所以搜 "/play/" 是搜不到的。
    //   实测一页 153 条、封面 153/153、豆瓣分 153/153。
    //   ⇒ 首页/「全部」不再依赖 WebView + CAP，秒出；分类页拿不到时也用它兜底。
    // ===================================================================
    private JSONArray httpAll;
    private long httpAllAt;

    /** 取网页正文（纯 HTTP，不走 WebView）。失败返回 null。 */
    private String httpGet(String url) {
        java.net.HttpURLConnection c = null;
        java.io.InputStream in = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(10000);
            // 用桌面 UA：首页的 HTML 结构是按桌面版验证过的
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            c.setRequestProperty("Accept-Encoding", "identity"); // 自己不解压，省事
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                dlog("http " + code + " " + url);
                return null;
            }
            in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bos.write(b, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            dlog("http err " + t);
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

    /** 去掉标签取可见文本（只处理卡片里会出现的几个实体）。 */
    private static String htmlStrip(String x) {
        StringBuilder sb = new StringBuilder(x.length());
        boolean tag = false;
        for (int i = 0; i < x.length(); i++) {
            char ch = x.charAt(i);
            if (ch == '<') {
                tag = true;
            } else if (ch == '>') {
                tag = false;
            } else if (!tag) {
                sb.append(ch);
            }
        }
        return sb.toString()
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
                .replaceAll("\\s+", " ").trim();
    }

    /** 取 open（如 "<h3"）这个开标签到下一个 "</" 之间的可见文本。 */
    private static String htmlTagText(String blk, String open) {
        int i = blk.indexOf(open);
        if (i < 0) return null;
        int a = blk.indexOf('>', i);
        if (a < 0) return null;
        int z = blk.indexOf("</", a);
        if (z < 0) return null;
        return htmlStrip(blk.substring(a + 1, z));
    }

    /** 取 key（含结尾引号，如 "data-src=\""）后面的属性值。 */
    private static String htmlAttr(String blk, String key) {
        int i = blk.indexOf(key);
        if (i < 0) return null;
        int a = i + key.length();
        int z = blk.indexOf('"', a);
        if (z < 0) return null;
        return blk.substring(a, z);
    }

    private static boolean isHex32(String s) {
        if (s == null || s.length() != 32) return false;
        for (int i = 0; i < 32; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    /** 卡片里的播放页 id：href="play/{32hex}" 或 href="/play/{32hex}"。 */
    private static String htmlMid(String blk) {
        String[] keys = {"href=\"play/", "href=\"/play/"};
        for (String k : keys) {
            int i = blk.indexOf(k);
            if (i < 0) continue;
            int a = i + k.length();
            if (a + 32 > blk.length()) continue;
            String t = blk.substring(a, a + 32);
            if (isHex32(t)) return t;
        }
        return null;
    }

    /** 首页 HTML → 卡片数组（字段与 parseList 对齐，另带 _cat 供分类过滤）。 */
    private JSONArray parseHomeCards(String html) {
        JSONArray out = new JSONArray();
        if (html == null) return out;
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        int p = 0;
        while (true) {
            int a = html.indexOf("<article", p);
            if (a < 0) break;
            int z = html.indexOf("</article>", a);
            if (z < 0) break;
            p = z + 10;
            String blk = html.substring(a, z);
            String mid = htmlMid(blk);
            if (mid == null || !seen.add(mid)) continue;
            String title = htmlTagText(blk, "<h3");
            if (title == null || title.isEmpty()) title = htmlAttr(blk, "alt=\"");
            if (title == null || title.isEmpty()) continue;
            String pic = htmlAttr(blk, "data-src=\"");
            if (pic == null || pic.indexOf("colorful.svg") >= 0) pic = htmlAttr(blk, "src=\"");
            try {
                JSONObject v = new JSONObject();
                v.put("vod_id", mid);
                v.put("vod_name", title);
                v.put("vod_pic", pic == null ? "" : pic);
                String rem = htmlTagText(blk, "badge-default\">");
                v.put("vod_remarks", rem == null ? "" : rem);
                String cat = htmlTagText(blk, "badge-danger\">");
                v.put("_cat", cat == null ? "" : cat); // 壳子忽略未知字段
                out.put(v);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** 首页卡片（5 分钟缓存）。失败时返回上一次的结果（可能为 null）。 */
    private JSONArray homeCards(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && httpAll != null && now - httpAllAt < 300000L) return httpAll;
        String html = httpGet(base + "/");
        JSONArray arr = html == null ? null : parseHomeCards(html);
        if (arr == null || arr.length() == 0) {
            dlog("home http miss len=" + (html == null ? -1 : html.length()));
            return httpAll;
        }
        httpAll = arr;
        httpAllAt = now;
        dlog("home http cards=" + arr.length());
        return arr;
    }

    /** tid → 首页卡片上的分类标签。 */
    private static String catNameOf(String tid) {
        if ("movie".equals(tid)) return "电影";
        if ("tv".equals(tid)) return "电视剧";
        if ("bangumi".equals(tid)) return "番剧";
        if ("cartoon".equals(tid)) return "国漫";
        return null;
    }

    private static JSONArray filterCat(JSONArray cards, String tid) {
        JSONArray out = new JSONArray();
        String want = catNameOf(tid);
        if (want == null || cards == null) return out;
        for (int i = 0; i < cards.length(); i++) {
            JSONObject o = cards.optJSONObject(i);
            if (o != null && want.equals(o.optString("_cat", ""))) out.put(o);
        }
        return out;
    }

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
    //   全部挂在 window.<NS> 这个对象上，不散落全局：
    //     <NS>.cap()      点 cap-widget 的勾选框（shadow DOM），返回 clicked/no-widget/no-box
    //     <NS>.list()     最近一次 filter_movielist 响应
    //     <NS>.eps()      选集按钮 [{p,t}]
    //     <NS>.click(p)   点第 p 集
    //     <NS>.meta()     {t:标题, p:封面}
    //     <NS>.count()    m3u8 blob 条数
    //     <NS>.m3u8()     最近一次解好密的 m3u8 明文
    //     <NS>.murl()     _murl 给的整片直链
    //     <NS>.murlCount()直链条数
    //     <NS>.diag()     诊断串
    //
    // ⚠ 为什么名字要随机（2026-11-19 实测）：
    //   站点在 CAP 页里专门钓 window 上以 "__gz" 开头的全局 —— 一旦发现，就把
    //   cap-widget 从 DOM 里摘掉、正文换成「页面暂时不可用，请刷新后重试」，
    //   于是验证永远过不去、filter_movielist 永远不发出、分类列表全空。
    //   二分证据（同一个页面，只改注入内容）：
    //     window.__gz={}      → cap-widget 消失，正文「页面暂时不可用」
    //     window.__gz2={}     → 同样消失
    //     window.__foo={}     → 正常
    //     window.hello123={}  → 正常
    //     window.gzs={}       → 正常
    //   所以不能再赌「换个固定名字就安全」，改成每次进程随机，只挂一个对象。
    // ===================================================================
    private static final String NS = "g" + Long.toHexString(System.nanoTime() & 0xffffffL);

    private static final String CAP_JS_T =
            "(function(){" +
            // 判据用 list 而不是对象本身：对象建好了但方法没挂全，也算没装好
            " if(window.__NS__ && window.__NS__.list) return;" +
            " var g={lists:[],blobs:[],media:[],murls:[]};" +
            " var N=window.__NS__={};" +
            // ===== v16-probe：vu 链诊断（纯观察，不改页面行为）=====
            // 抓 GazeBinary.utf8 的 #EXTM3U 输出 / epi 输出 / appendBuffer / video.src，
            // 记在 g.vu，由 Java 侧定期读走打日志。全部 try/catch 包住，失败不影响原逻辑。
            " g.vu={v:1};" +
            " try{" +
            "  var V=g.vu;" +
            "  V.hls=(typeof window.GazeHlsSupported);" +   // boolean / undefined → 决定分支
            "  var gbHook=function(v){" +
            "   try{" +
            "    V.gb=1;" +
            "    if(v.utf8 && !v.utf8.__pg){" +
            "     var o=v.utf8; v.utf8=function(){" +
            "      var r=o.apply(this,arguments);" +
            "      try{ if(typeof r==='string' && r.indexOf('#EXTM3U')>=0){" +
            "        V.u8=1; V.u8len=(r.length); V.u8head=(r.slice(0,220));" +
            "      }}catch(e){}" +
            "      return r; };" +
            "     v.utf8.__pg=1;" +
            "    }" +
            "    if(v.decodeFragmentURL && !v.decodeFragmentURL.__pg){" +
            "     var d=v.decodeFragmentURL; v.decodeFragmentURL=function(){" +
            "      V.fg=(V.fg||0)+1;" +
            "      if(!V.fgs) V.fgs=(String(arguments[0]||'').slice(0,40)+'>'+String(d.apply(this,arguments)).slice(0,90));" +
            "      return d.apply(this,arguments); };" +
            "     v.decodeFragmentURL.__pg=1;" +
            "    }" +
            "   }catch(e){}" +
            "  };" +
            // GazeBinary 用 defineProperty(value,writable:false) 挂的：拦截不到 setter，
            // 只能定时轮询，一旦发现就直接包它上面的方法。
            "  var t=setInterval(function(){" +
            "   try{ if(window.GazeBinary) gbHook(window.GazeBinary); }catch(e){}" +
            "   try{ if(typeof window.GazeBinary!=='undefined') V.gbt=1; }catch(e){}" +
            "   try{ V.epi=(typeof window.epi); }catch(e){}" +
            "   try{ V.dim=(typeof window.DimGive); }catch(e){}" +
            "   try{ if(typeof window.epi==='function' && !window.epi.__pg){" +
            "     var e0=window.epi; window.epi=function(){" +
            "      var r=e0.apply(this,arguments);" +
            "      try{ V.epin=(V.epin||0)+1; V.epilen=String(r||'').length; V.epihead=String(r||'').slice(0,120); }catch(ex){}" +
            "      return r; }; window.epi.__pg=1;" +
            "   }}catch(e){}" +
            "   try{ if(typeof window.DimGive==='function' && !window.DimGive.__pg){" +
            "     var d0=window.DimGive; window.DimGive=function(){" +
            "      var r=d0.apply(this,arguments); V.dimn=(V.dimn||0)+1; return r; }; window.DimGive.__pg=1;" +
            "   }}catch(e){}" +
            "  },400);" +
            "  setTimeout(function(){try{clearInterval(t);}catch(e){}},90000);" +
            // appendBuffer：统计次数 + 首片魔数
            "  try{ var AB=SourceBuffer.prototype.appendBuffer;" +
            "   SourceBuffer.prototype.appendBuffer=function(b){" +
            "    V.ab=(V.ab||0)+1;" +
            "    try{ if(!V.abh){ var u=new Uint8Array(b),h=''; for(var i=0;i<Math.min(12,u.length);i++) h+=('0'+u[i].toString(16)).slice(-2); V.abh=h+'/'+b.byteLength; } }catch(e){}" +
            "    return AB.apply(this,arguments); };" +
            "  }catch(e){}" +
            // video.src / currentSrc
            "  try{ var vd=document.querySelector('video'); if(vd) V.vsrc=(vd.currentSrc||vd.src||'').slice(0,70); }catch(e){}" +
            " }catch(e){ g.vu.err=String(e); }" +
            " N.vu=function(){" +
            "  try{ var v=document.querySelector('video'); if(v) g.vu.vsrc=(v.currentSrc||v.src||'').slice(0,70); }catch(e){}" +
            "  try{ g.vu.path=(location.pathname||'').slice(-44); }catch(e){}" +
            "  return JSON.stringify(g.vu);" +
            " };" +
            // ===== end v16-probe =====
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
            // 每条都打上「当时所在页面」的 pathname。SPA 跳转时 window.<NS> 不会重建，
            // 不打标签就会把上一部片子的直链当成这一部的（串台 + 签名过期播不了）。
            "  g.murls.push({u:u,p:(location.pathname||'')}); if(g.murls.length>8) g.murls.shift();" +
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
            "      var keep=function(t){ try{ if(t&&t.indexOf('#EXTM3U')>=0){ g.blobs.push({t:t,p:(location.pathname||'')}); if(g.blobs.length>8) g.blobs.shift(); } }catch(e){} };" +
            "      if(typeof o.text==='function'){ o.text().then(keep).catch(function(){}); }" +
            "      else if(window.FileReader){ var fr=new FileReader(); fr.onload=function(){ keep(String(fr.result||'')); }; fr.readAsText(o); }" +
            "     }" +
            "    }catch(e){}" +
            "    return oc.apply(this,arguments); };" +
            " }catch(e){}" +
            // 【静音 —— 修「退出播放后声音还在」】
            //   这个 WebView 是不挂到窗口上的，但 ensureWv() 里开了
            //   setMediaPlaybackRequiresUserGesture(false)，播放页的 <video> 会自动播放并出声。
            //   spider 的 destroy() 在「退出播放」时根本不会被调用，所以声音会一直响。
            //   这里把 play() 包一层强制 muted：页面逻辑照常跑（m3u8 blob 照常生成、选集照常切），
            //   但永远不出声；再用 play / volumechange 事件兜住页面自己把声音改回来的情况。
            " try{" +
            "  var mp=window.HTMLMediaElement&&HTMLMediaElement.prototype.play;" +
            "  if(mp) HTMLMediaElement.prototype.play=function(){" +
            "   try{ this.muted=true; this.volume=0; }catch(e){} return mp.apply(this,arguments); };" +
            "  var mute=function(v){ try{ if(v&&v.muted!==true) v.muted=true; if(v&&v.volume!==0) v.volume=0; }catch(e){} };" +
            "  document.addEventListener('play',function(e){ mute(e.target); },true);" +
            "  document.addEventListener('volumechange',function(e){ mute(e.target); },true);" +
            "  document.addEventListener('loadedmetadata',function(e){ mute(e.target); },true);" +
            " }catch(e){}" +
            " N.cap=function(){" +
            "  var w=document.querySelector('cap-widget'); if(!w||!w.shadowRoot) return 'no-widget';" +
            "  var b=w.shadowRoot.querySelector('[part=checkbox]')||w.shadowRoot.querySelector('.captcha-trigger');" +
            "  if(!b) return 'no-box';" +
            "  ['mousedown','mouseup','click'].forEach(function(t){ try{ b.dispatchEvent(new MouseEvent(t,{bubbles:true,composed:true})); }catch(e){} });" +
            "  return 'clicked'; };" +
            // 勾选框中心点（CSS px）+ 视口尺寸，交给 Java 换算成 WebView 坐标后用真实触摸事件点。
            // 为什么必须真实事件：2026-11-19 实测，cap.js 只认 isTrusted 的事件 ——
            //   合成 dispatchEvent → 页面一个请求都不发（/event/cap/challenge 根本没出现）；
            //   CDP 真实鼠标点击   → POST /event/cap/challenge → 99% → POST /event/cap/redeem → 过。
            " N.rect=function(){" +
            "  var w=document.querySelector('cap-widget'); if(!w||!w.shadowRoot) return '';" +
            "  var b=w.shadowRoot.querySelector('[part=checkbox]')||w.shadowRoot.querySelector('.captcha-trigger');" +
            "  if(!b) return '';" +
            "  var r=b.getBoundingClientRect(); if(!r||r.width<=0||r.height<=0) return '';" +
            "  return JSON.stringify({x:r.x+r.width/2,y:r.y+r.height/2,w:innerWidth,h:innerHeight}); };" +
            " N.list=function(){ return g.lists.length? g.lists[g.lists.length-1] : ''; };" +
            " N.eps=function(){" +
            "  var n=document.querySelectorAll('#g8f256a3e34dd0c56 [data-path]');" +
            "  if(!n.length) n=document.querySelectorAll('[data-path]');" +
            "  var o=[]; for(var i=0;i<n.length;i++){ var e=n[i]; o.push({p:e.getAttribute('data-path'),t:(e.textContent||'').trim()}); }" +
            "  return JSON.stringify(o); };" +
            " N.click=function(p){" +
            "  var n=document.querySelectorAll('#g8f256a3e34dd0c56 [data-path]');" +
            "  if(!n.length) n=document.querySelectorAll('[data-path]');" +
            "  for(var i=0;i<n.length;i++){ if(n[i].getAttribute('data-path')===String(p)){" +
            "    var e=n[i]; ['mousedown','mouseup','click'].forEach(function(t){ try{ e.dispatchEvent(new MouseEvent(t,{bubbles:true,composed:true})); }catch(x){} });" +
            "    try{ e.click(); }catch(x){} return 'ok'; } }" +
            "  return 'not-found'; };" +
            // 选集按钮的中心坐标（CSS px）+ 视口尺寸。
            // 合成 click 万一被页面忽略（有些站也校验 isTrusted），Java 可以拿这个坐标
            // 用 WebView.dispatchTouchEvent 补一次真实触摸 —— 和过 CAP 用的是同一套。
            " N.rectOf=function(p){" +
            "  var n=document.querySelectorAll('#g8f256a3e34dd0c56 [data-path]');" +
            "  if(!n.length) n=document.querySelectorAll('[data-path]');" +
            "  for(var i=0;i<n.length;i++){ if(n[i].getAttribute('data-path')===String(p)){" +
            "    var e=n[i]; try{ e.scrollIntoView({block:'center'}); }catch(x){}" +
            "    var r=e.getBoundingClientRect(); if(!r||r.width<=0||r.height<=0) return '';" +
            "    return JSON.stringify({x:r.x+r.width/2,y:r.y+r.height/2,w:innerWidth,h:innerHeight}); } }" +
            "  return ''; };" +
            " N.meta=function(){" +
            "  var m=document.querySelector('meta[property=\"og:image\"]');" +
            "  return JSON.stringify({t:document.title||'',p:m?m.content:''}); };" +
            " var cur=function(){ return location.pathname||''; };" +
            // 全部按「当前页面」过滤：SPA 跳转后 window.<NS> 不重建，旧数据还在数组里。
            " N.count=function(){ var p=cur(),n=0; for(var i=0;i<g.blobs.length;i++) if(g.blobs[i].p===p) n++; return n; };" +
            " N.m3u8=function(){ var p=cur(); for(var i=g.blobs.length-1;i>=0;i--) if(g.blobs[i].p===p) return g.blobs[i].t; return ''; };" +
            " N.murl=function(){ var p=cur(); for(var i=g.murls.length-1;i>=0;i--) if(g.murls[i].p===p) return g.murls[i].u; return ''; };" +
            " N.videoUrl=function(){ var v=document.querySelector('video'); return v?(v.currentSrc||v.src||''):''; };" +
            " N.murlCount=function(){ var p=cur(),n=0; for(var i=0;i<g.murls.length;i++) if(g.murls[i].p===p) n++; return n; };" +
            " N.path=function(){ return cur(); };" +
            // 清掉上一部片子留下的 m3u8 / murl。
            // 必须清：页面之间是 SPA 跳转时 window.<NS> 不会重建，g.murls 里还留着上一部的直链，
            // grabM3u8 的 "ep<=0 直接复用 mpre" 分支就会把上一部（且可能已过签名期）的 URL 交出去
            // —— 表现就是「点 B 播出来的是 A」以及「无法播放」。
            " N.reset=function(){ g.blobs=[]; g.murls=[]; g.lists=[]; return 1; };" +
            // 诊断用：一条字符串就能区分「hook 没抓到」和「抓到了但我清空了」
            " N.diag=function(){" +
            "  var v=document.querySelector('video');" +
            "  return JSON.stringify({hook:1,n:g.blobs.length,m:g.murls.length," +
            "   v:v?{rs:v.readyState,t:Math.round(v.currentTime||0),src:(v.currentSrc||'').slice(0,40)}:null," +
            "   href:(location.href||'').slice(-48)}); };" +
            "})();";

    private static String capJs() {
        return CAP_JS_T.replace("__NS__", NS);
    }

    /** 调注入脚本里的方法：g("cap()", 2000) → window.<NS>.cap()。没装好会返回 null。 */
    private String g(String call, long timeoutMs) {
        return ev("window." + NS + "." + call, timeoutMs);
    }

    /** 注入脚本是否可用（要求 list 已挂上）。 */
    private boolean hookReady() {
        return "1".equals(ev("(window." + NS + "&&window." + NS + ".list)?1:0", 1500));
    }

    // ===================================================================
    // CAP 点击
    //   站点 2026-11-19 起只认 isTrusted 的事件：
    //     合成 dispatchEvent → 不发 /event/cap/challenge（点了等于没点）
    //     真实鼠标/触摸事件   → /challenge → 99% → /redeem → 页面跳转，验证通过
    //   ⇒ 先用 JS 拿勾选框中心（CSS px）+ 视口尺寸，再由 Java 换算成 WebView 坐标，
    //     用 WebView.dispatchTouchEvent 发 ACTION_DOWN/UP —— 这条路径的事件是
    //     应用层派发的，isTrusted=true，和用户手点等价。
    //   JS 的合成点击只留作老版本 cap.js 的退路。
    // ===================================================================
    /** 上次真实点击的时间。挑战要 9s 左右，期间每 700ms 点一次等于反复打断它。 */
    private volatile long lastCapAt;

    private String capTick() {
        String rect = g("rect()", 1500);
        if (rect != null && rect.length() > 8) {
            long now = System.currentTimeMillis();
            if (now - lastCapAt < 2500) return "wait"; // 挑战进行中，别打断
            lastCapAt = now;
            if (touchTap(rect)) return "touch";
        }
        String r = g("cap()", 2000);
        return r == null ? "no-hook" : r;
    }

    /** 按 CSS 坐标点一下 WebView。rect 是 {x,y,w,h}：x/y 为 CSS px，w/h 为视口 CSS 尺寸。 */
    private boolean touchTap(String rectJson) {
        if (main == null) return false;
        JSONObject r = jsonOf(rectJson);
        if (r == null) return false;
        final double cx = r.optDouble("x", -1), cy = r.optDouble("y", -1);
        final double vw = r.optDouble("w", 0), vh = r.optDouble("h", 0);
        if (cx < 0 || cy < 0 || vw <= 0 || vh <= 0) return false;
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = new boolean[1];
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (wv == null) return;
                    // 坐标换算：CSS px → WebView 像素。优先用实际宽高比，
                    // WebView 还没量出尺寸时退回 density。
                    float sx, sy;
                    if (wv.getWidth() > 0 && wv.getHeight() > 0) {
                        sx = (float) (wv.getWidth() / vw);
                        sy = (float) (wv.getHeight() / vh);
                    } else {
                        float d = wv.getResources().getDisplayMetrics().density;
                        sx = d;
                        sy = d;
                    }
                    final float x = (float) (cx * sx), y = (float) (cy * sy);
                    long t = android.os.SystemClock.uptimeMillis();
                    android.view.MotionEvent down = android.view.MotionEvent.obtain(
                            t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
                    down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                    wv.dispatchTouchEvent(down);
                    down.recycle();
                    // 按下和抬起之间留一点间隔，太快的 DOWN/UP 有些组件会当误触
                    main.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (wv != null) {
                                    long t2 = android.os.SystemClock.uptimeMillis();
                                    android.view.MotionEvent up = android.view.MotionEvent.obtain(
                                            t2, t2, android.view.MotionEvent.ACTION_UP, x, y, 0);
                                    up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                                    wv.dispatchTouchEvent(up);
                                    up.recycle();
                                    ok[0] = true;
                                }
                            } catch (Throwable th) {
                                dlog("touch up err " + th);
                            } finally {
                                latch.countDown();
                            }
                        }
                    }, 80);
                    return;
                } catch (Throwable th) {
                    dlog("touch err " + th);
                }
                latch.countDown();
            }
        });
        try {
            latch.await(3000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        return ok[0];
    }

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
        // WebView 不挂到窗口上，也要给它一个真实尺寸：
        //   1) 坐标换算要用 getWidth()/getHeight()；
        //   2) dispatchTouchEvent 做命中测试也要有视口尺寸。
        // 不 measure/layout 的话宽高是 0，换算只能退回 density，精度差。
        try {
            android.util.DisplayMetrics dm = appCtx.getResources().getDisplayMetrics();
            int ww = dm.widthPixels > 0 ? dm.widthPixels : 1280;
            int hh = dm.heightPixels > 0 ? dm.heightPixels : 720;
            w.measure(android.view.View.MeasureSpec.makeMeasureSpec(ww, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(hh, android.view.View.MeasureSpec.EXACTLY));
            w.layout(0, 0, ww, hh);
            dlog("wv size=" + w.getWidth() + "x" + w.getHeight() + " screen=" + ww + "x" + hh);
        } catch (Throwable t) {
            dlog("wv layout err " + t);
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
                // 只认「目标 URL」的 finished：重定向/SPA 时会收到别的页面的回调，
                // 认错了就等于没等，后面又是老问题。
                String want = pageWant;
                if (want == null || (url != null && url.indexOf(want) >= 0)) {
                    pageFinishedAt = System.currentTimeMillis();
                }
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
            v.evaluateJavascript(capJs(), null);
        } catch (Throwable t) {
            dlog("inject err " + t);
        }
    }

    /** 加载 url，并**真的等 onPageFinished**（最多 waitMs）。
     *  之前只 sleep 1.5s 就返回：慢机上页面还没起来，后面的 capTick 全是 no-widget，
     *  CAP 预算被白白烧掉 —— 这是「很多片播不了」的一个主要原因。
     *  超时也返回 true，交给调用方（waitPath / capTick 轮询）继续判断。 */
    private boolean load(final String url, long waitMs) {
        if (main == null) return false;
        final long t0 = System.currentTimeMillis();
        pageWant = url;
        pageFinishedAt = 0;
        final boolean[] ok = new boolean[1];
        final CountDownLatch posted = new CountDownLatch(1);
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
                    posted.countDown();
                }
            }
        });
        try {
            posted.await(5000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        if (!ok[0]) return false;
        long end = t0 + waitMs;
        while (System.currentTimeMillis() < end && pageFinishedAt < t0) {
            sleep(200);
        }
        boolean fin = pageFinishedAt >= t0;
        // onPageFinished 之后 DOM/JS 还要跑一下（CAP 组件是前端渲染出来的）
        sleep(fin ? 700 : 1200);
        dlog("load " + url + " fin=" + fin + " " + (System.currentTimeMillis() - t0) + "ms");
        return true;
    }

    /** 确认注入脚本在（Chromium 偶发丢弃 evaluateJavascript），不在就补一次。 */
    private boolean ensureHook() {
        if (hookReady()) return true;
        ev(capJs(), 2500);
        return hookReady();
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
    //   不同 query 是不同文档，注入对象会被重建，所以不会串到上一个分类的数据。
    // ===================================================================
    private String fetchList(String query) {
        String url = base + "/filter" + (query == null ? "" : query);
        if (!load(url, PAGE_READY_MS)) return null;
        long deadline = System.currentTimeMillis() + LIST_BUDGET_MS;
        int round = 0;
        boolean reloaded = false;
        while (System.currentTimeMillis() < deadline) {
            round++;
            if (round == 1 || round % 3 == 0) ensureHook();
            String cap = capTick();
            String list = g("list()", 2500);
            if (list == null) list = "";
            if (list.indexOf("mlist") >= 0) {
                dlog("list ok " + query + " round=" + round + " cap=" + cap + " len=" + list.length());
                return list;
            }
            if (round == 1) dlog("list wait " + query + " cap=" + cap);
            // cap-widget 没渲染出来（站点偶尔给「页面暂时不可用」）→ 重载一次，
            // 否则后面十几秒都在点空气。只重载一次，别把预算耗在刷新上。
            if (!reloaded && round >= 4 && "no-widget".equals(cap)) {
                reloaded = true;
                dlog("list reload " + query);
                load(url, PAGE_READY_MS);
                continue;
            }
            sleep(900);
        }
        dlog("list timeout " + query + " round=" + round);
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
            out.put("filters", buildFilters());
            // 先走纯 HTTP：首页是服务端渲染的，不需要 CAP，也不受注入时序影响
            JSONArray cards = homeCards(false);
            if (cards == null || cards.length() == 0) {
                dlog("home -> webview");
                String raw = fetchList("");
                if (raw != null) listCaches.put("all|", new ListCache(raw, System.currentTimeMillis()));
                cards = parseList(raw);
            }
            out.put("list", cards);
            dlog("home items=" + cards.length());
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
            String fq = filterQuery(extend);
            String cacheKey = t + "|" + fq;
            JSONArray list = null;
            int total = 0;
            // 只有「全部 + 无筛选」才走首页 SSR 卡片（秒开）；带筛选必须用 filter 页的数据
            if ("all".equals(t) && fq.isEmpty()) {
                JSONArray cards = homeCards(false);
                if (cards != null && cards.length() > 0) {
                    list = cards;
                    total = cards.length();
                }
            }
            if (list == null) {
                String raw;
                ListCache c = listCaches.get(cacheKey);
                if (c != null && c.raw != null
                        && System.currentTimeMillis() - c.at < LIST_CACHE_MS) {
                    raw = c.raw;
                    dlog("category hit cache key=" + cacheKey
                            + " age=" + ((System.currentTimeMillis() - c.at) / 1000) + "s");
                } else {
                    long tFetch = System.currentTimeMillis();
                    raw = fetchList(mformOf(t) + fq);
                    if (raw != null) listCaches.put(cacheKey,
                            new ListCache(raw, System.currentTimeMillis()));
                    dlog("category fetch key=" + cacheKey
                            + " " + (System.currentTimeMillis() - tFetch) + "ms");
                }
                list = parseList(raw);
                if (raw != null) {
                    JSONObject j = jsonOf(raw);
                    if (j != null) total = j.optInt("total", 0);
                }
                if (list.length() == 0) {
                    // WebView 这条路挂了（CAP 点不过 / 注入没进 / 站点改了）→
                    // 用首页卡片按分类标签兜底，至少不是空列表。
                    JSONArray cards = homeCards(false);
                    JSONArray sub = filterCat(cards, t);
                    if (sub.length() > 0) {
                        list = sub;
                        total = sub.length();
                        dlog("category fallback http tid=" + t + " items=" + sub.length());
                    }
                }
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

    // ===================================================================
    // 筛选（2026-11-23 过 CAP 实测）
    //   /filter_movielist 的 body：
    //     mform=2&mcountry=all&genre_arr=all&page=1&sort=updatetime&album=all&title=&years=all
    //   URL 上实测生效的参数：mform / mcountry / genre / years / sort
    //   （注意 URL 叫 genre，body 里叫 genre_arr；album/title 从 URL 传不进去，只有 UI 输入）
    //   页面按 URL 参数生成 body，proof header 也跟着 body 算，所以拼 URL 不需要碰 proof。
    // ===================================================================
    private static final String[][] FILTER_AREAS = {
            {"1","中国大陆"},{"2","中国台湾"},{"3","中国香港"},{"4","韩国"},{"5","俄罗斯"},
            {"6","美国"},{"7","日本"},{"8","印度"},{"9","英国"},{"10","德国"},
            {"11","法国"},{"12","意大利"},{"13","泰国"},{"14","爱沙尼亚"},{"15","哈萨克斯坦"},
            {"16","西班牙"},{"17","黎巴嫩"},{"18","巴西"},{"19","澳大利亚"},{"20","丹麦"},
            {"21","瑞典"},{"22","以色列"},{"23","荷兰"},{"24","伊朗"},{"25","墨西哥"},
            {"26","奥地利"},{"27","智利"},{"28","马来西亚"},{"29","哥伦比亚"},{"30","挪威"},
            {"31","爱尔兰"},{"32","罗马尼亚"},{"33","比利时"},{"34","瑞士"},{"35","加拿大"},
            {"36","波兰"}
    };
    private static final String[][] FILTER_GENRES = {
            {"1","剧情"},{"2","动作"},{"3","喜剧"},{"4","爱情"},{"5","科幻"},{"6","悬疑"},
            {"7","惊悚"},{"8","恐怖"},{"9","犯罪"},{"10","音乐"},{"11","冒险"},{"12","历史"},
            {"13","战争"},{"14","奇幻"},{"15","黑帮"},{"16","文艺"},{"17","传记"},{"18","运动"},
            {"19","同性"},{"20","情色"}
    };

    private static JSONArray filterValues(String[][] pairs, boolean withAll) {
        JSONArray a = new JSONArray();
        if (withAll) {
            JSONObject o = new JSONObject();
            try { o.put("v", "all"); o.put("n", "全部"); } catch (Exception ignored) { }
            a.put(o);
        }
        for (String[] p : pairs) {
            JSONObject o = new JSONObject();
            try { o.put("v", p[0]); o.put("n", p[1]); } catch (Exception ignored) { }
            a.put(o);
        }
        return a;
    }

    private JSONObject buildFilters() {
        JSONArray groups = new JSONArray();
        try {
            JSONObject sort = new JSONObject();
            sort.put("key", "sort");
            sort.put("name", "排序");
            String[][] so = {{"updatetime","最近更新"},{"grade","评分排序"},{"name","名称排序"}};
            JSONArray sv = new JSONArray();
            for (String[] p : so) {
                JSONObject o = new JSONObject(); o.put("v", p[0]); o.put("n", p[1]); sv.put(o);
            }
            sort.put("value", sv);
            groups.put(sort);

            JSONObject year = new JSONObject();
            year.put("key", "years");
            year.put("name", "年份");
            JSONArray yv = new JSONArray();
            JSONObject ya = new JSONObject(); ya.put("v", "all"); ya.put("n", "全部"); yv.put(ya);
            for (int y = 2026; y >= 1980; y--) {
                JSONObject o = new JSONObject(); o.put("v", String.valueOf(y)); o.put("n", String.valueOf(y)); yv.put(o);
            }
            year.put("value", yv);
            groups.put(year);

            JSONObject area = new JSONObject();
            area.put("key", "mcountry");
            area.put("name", "地区");
            area.put("value", filterValues(FILTER_AREAS, true));
            groups.put(area);

            JSONObject genre = new JSONObject();
            genre.put("key", "genre");
            genre.put("name", "类型");
            genre.put("value", filterValues(FILTER_GENRES, true));
            groups.put(genre);
        } catch (Exception ignored) {
        }
        JSONObject f = new JSONObject();
        String[] tids = {"all", "movie", "tv", "bangumi", "cartoon"};
        for (String t : tids) {
            try { f.put(t, groups); } catch (Exception ignored) { }
        }
        return f;
    }

    /** 壳子传来的筛选值 → /filter 的 query。值都来自上面固定的枚举，无需 URL 编码。 */
    private static String filterQuery(HashMap<String, String> extend) {
        if (extend == null || extend.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        String[] keys = {"mcountry", "genre", "years", "sort"};
        for (String k : keys) {
            String v = extend.get(k);
            if (v == null || v.isEmpty() || "all".equals(v)) continue;
            if ("sort".equals(k) && "updatetime".equals(v)) continue;
            sb.append('&').append(k).append('=').append(v);
        }
        return sb.toString();
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
            // 同上：load() 不保证页面真的切过去了，慢机 / SPA 下会读到上一部片子的选集和标题
            if (!waitPath(mid, 6000)) {
                dlog("detail path miss mid=" + mid);
                if (!load(base + "/play/" + mid, PAGE_READY_MS) || !waitPath(mid, 6000)) {
                    dlog("detail path fail mid=" + mid);
                    out.put("list", list);
                    return out.toString();
                }
            }
            ensureHook();
            JSONArray eps = null;
            String meta = null;
            long deadline = System.currentTimeMillis() + DETAIL_BUDGET_MS;
            boolean reloaded = false;
            int round = 0;
            while (System.currentTimeMillis() < deadline) {
                round++;
                if (round == 1 || round % 3 == 0) ensureHook();
                String cap = capTick();
                String raw = g("eps()", 2500);
                // CAP 组件没渲染出来、选集也没出来 → 原地等没用，重载一次
                if (!reloaded && round >= 5 && "no-widget".equals(cap)
                        && (raw == null || raw.length() <= 4)) {
                    reloaded = true;
                    dlog("detail reload no-widget mid=" + mid + " round=" + round);
                    load(base + "/play/" + mid, PAGE_READY_MS);
                    ensureHook();
                    continue;
                }
                if (raw != null && raw.length() > 4) {
                    try {
                        JSONArray a = new JSONArray(raw);
                        if (a.length() > 0) {
                            eps = a;
                            meta = g("meta()", 2000);
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

    /**
     * 检查 _murl 签名直链是否仍可用。
     * gaze 当前返回的签名通常带 x-expires（Unix 秒）；至少留 15 秒余量，
     * 避免 URL 刚交给播放器就过期。没有该参数时不擅自拒绝 CDN 地址。
     */
    private static boolean isUsableMurl(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return false;
        int q = url.indexOf('?');
        if (q < 0) return true;
        String query = url.substring(q + 1);
        String[] parts = query.split("&");
        for (int i = 0; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            String key = eq >= 0 ? parts[i].substring(0, eq) : parts[i];
            if (!"x-expires".equals(key)) continue;
            String value = eq >= 0 ? parts[i].substring(eq + 1) : "";
            try {
                long expires = Long.parseLong(value);
                // 当前站点使用 Unix 秒；若遇到毫秒时间戳也兼容。
                if (expires > 100000000000L) expires /= 1000L;
                return expires > System.currentTimeMillis() / 1000L + 15L;
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    /** 只返回签名剩余时间，不把完整 token 写进日志。 */
    private static String murlExpiryState(String url) {
        if (url == null) return "null";
        int q = url.indexOf('?');
        if (q >= 0) {
            String[] parts = url.substring(q + 1).split("&");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].startsWith("x-expires=")) {
                    try {
                        long exp = Long.parseLong(parts[i].substring(10));
                        if (exp > 100000000000L) exp /= 1000L;
                        return "remain=" + (exp - System.currentTimeMillis() / 1000L) + "s";
                    } catch (Exception ignored) {
                        return "bad-expiry";
                    }
                }
            }
        }
        return "expiry-unknown";
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
    /** 等 WebView 真的停在 /play/<mid> 上。load() 不等这个，慢机/SPA 下会串台。 */
    private boolean waitPath(String mid, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            String p = ev("(location.pathname||'')", 1200);
            if (p != null && p.indexOf(mid) >= 0) return true;
            sleep(300);
        }
        return false;
    }

    private String grabM3u8(String mid, int ep) {
        String page = "/play/" + mid;
        boolean needLoad = true;
        // detailContent 刚走过同一个播放页时，页面还在，省一次重新加载（也省一次 CAP）
        String path = ev("(location.pathname||'')", 1200);
        boolean pathOk = path != null && path.indexOf(mid) >= 0;
        if (pathOk) {
            String raw = g("eps()", 1500);
            if (raw != null && raw.length() > 4) needLoad = false;
        }
        if (!pathOk) {
            // 当前页面不是这一部片子：先把上一部留下的 m3u8/murl 清掉。
            // 不清的话，下面「ep<=0 直接复用 mpre」会把上一部的直链交出去
            // —— 表现就是「点 B 播出来的是 A」，而且那条签名 1 小时就过期，还会「无法播放」。
            ev("window." + NS + ".reset&&window." + NS + ".reset()", 1500);
        }
        if (needLoad) {
            if (!load(base + page, PAGE_READY_MS)) return null;
            // load() 只保证 loadUrl 提交了、给了 1.5s，并不保证页面真的切过去。
            // 慢机 / SPA 时这里可能还是上一部片子的 DOM，直接取 eps/murl 就会串台。
            if (!waitPath(mid, 6000)) {
                dlog("grab path miss mid=" + mid + " now=" + ev("(location.pathname||'')", 1200));
                if (!load(base + page, PAGE_READY_MS) || !waitPath(mid, 6000)) {
                    dlog("grab path fail mid=" + mid);
                    return null;
                }
            }
            ev("window." + NS + ".reset&&window." + NS + ".reset()", 1500);
            ensureHook();
        }

        // 进来时页面可能已经在播某一集（detailContent 刚打开过同一个播放页），
        // 此时 m3u8 早就生成好了。**不能清空**——清了再点「正在播的那一集」，
        // 播放器认为没换集、不会重新加载，就会一直等不到新 blob。
        int n0 = parseInt(g("count()", 1500), 0);
        String pre = null;
        if (n0 > 0) {
            String p = g("m3u8()", 6000);
            if (p != null && p.length() > 200 && p.indexOf("#EXTINF") >= 0) pre = p;
        }
        // 整片直链（_murl 接口）优先级最高：不代理、不剥壳、不占内存，播放器自己 Range 拖动
        int m0 = parseInt(g("murlCount()", 1500), 0);
        String mpre = null;
        if (m0 > 0) {
            String u = g("murl()", 3000);
            if (u != null && u.length() > 20 && u.indexOf("http") == 0) mpre = u;
        }
        // fetch/XHR hook 偶尔漏掉 _murl 响应，但 video.currentSrc 已经是实际 MP4。
        // 记录点击前的值，后续只有 URL 变化时才把它认作新选集，避免串到上一集。
        String vpre = g("videoUrl()", 2000);
        if (vpre != null && !vpre.startsWith("http")) vpre = null;
        dlog("grab start ep=" + ep + " needLoad=" + needLoad + " n0=" + n0
                + " pre=" + (pre == null ? -1 : pre.length())
                + " m0=" + m0 + " mpre=" + (mpre == null ? -1 : mpre.length())
                + " vpre=" + (vpre == null ? -1 : vpre.length()));

        // 第 1 集优先复用页面当前地址，但必须先检查签名有效期。
        if (ep <= 0) {
            String firstUrl = isUsableMurl(mpre) ? mpre : (isUsableMurl(vpre) ? vpre : null);
            if (firstUrl != null) {
                dlog("murl pre hit ep=" + ep + " " + murlExpiryState(firstUrl));
                return MP4_PREFIX + firstUrl;
            }
            if (mpre != null || vpre != null) dlog("murl pre stale " + murlExpiryState(mpre != null ? mpre : vpre));
        }

        long deadline = System.currentTimeMillis() + PLAY_BUDGET_MS;
        boolean clicked = false;
        boolean reloaded = false;
        boolean retapped = false;
        long clickedAt = 0;
        int target = ep < 0 ? 0 : ep;
        int round = 0;
        String lastVu = null; // v16-probe
        while (System.currentTimeMillis() < deadline) {
            round++;
            if (round == 1 || round % 4 == 0) ensureHook();
            String cap = capTick();
            String eps = null;
            if (!clicked) eps = g("eps()", 2000);
            // CAP 组件压根没渲染出来（不是"点了没过"，是 DOM 里没有），而且选集也没出来：
            // 这时候在原地空转 28s 没意义，重载一次页面。CAP 已过时 eps 会有值，不会误重载。
            if (!clicked && !reloaded && round >= 5 && "no-widget".equals(cap)
                    && (eps == null || eps.length() <= 4)) {
                reloaded = true;
                dlog("play reload no-widget mid=" + mid + " round=" + round);
                if (!load(base + page, PAGE_READY_MS)) return null;
                ensureHook();
                continue;
            }
            if (!clicked) {
                // 必须等选集按钮真的渲染出来再点：元素先出现、点击处理器后绑定，
                // 太早点会「点了个空」，拿到的仍是默认那一集的 m3u8（静默错集）。
                if (eps != null && eps.length() > 4) {
                    String r = g("click(" + target + ")", 2000);
                    if (r != null && r.indexOf("ok") >= 0) {
                        clicked = true;
                        clickedAt = System.currentTimeMillis();
                        dlog("play click ep=" + target + " round=" + round + " n0=" + n0);
                    }
                }
            } else {
                // 点完 9s 还没新数据：合成 click 可能被页面忽略，用真实触摸补一次
                if (!retapped && System.currentTimeMillis() - clickedAt > 9000) {
                    retapped = true;
                    String rc = g("rectOf(" + target + ")", 2000);
                    dlog("play retap ep=" + target + " rect=" + (rc == null ? "null" : String.valueOf(rc.length())));
                    if (rc != null && rc.length() > 8) touchTap(rc);
                }
                // 先看整片直链：新出现的才算（条数没涨说明是换集前那条）
                String mu = g("murl()", 3000);
                int mc = parseInt(g("murlCount()", 1500), -1);
                boolean murlChanged = mu != null && mu.length() > 20 && mu.startsWith("http")
                        && !mu.equals(mpre);
                if (murlChanged && isUsableMurl(mu) && (mc > m0 || mc < 0 || !mu.equals(vpre))) {
                    dlog("murl new n=" + mc + " len=" + mu.length() + " "
                            + murlExpiryState(mu) + " round=" + round);
                    return MP4_PREFIX + mu;
                }
                if (murlChanged && !isUsableMurl(mu)) {
                    dlog("murl stale " + murlExpiryState(mu) + " round=" + round);
                }
                // 第二层兜底：接口 hook 漏抓时直接读 video.currentSrc。
                String vu = g("videoUrl()", 1800);
                if (vu != null && vu.length() > 20 && vu.startsWith("http")
                        && !vu.equals(vpre) && !vu.equals(mpre)) {
                    if (isUsableMurl(vu)) {
                        dlog("video currentSrc hit len=" + vu.length() + " "
                                + murlExpiryState(vu) + " round=" + round);
                        return MP4_PREFIX + vu;
                    }
                    dlog("video currentSrc stale " + murlExpiryState(vu) + " round=" + round);
                }
                String m = g("m3u8()", 6000);
                if (m != null && m.length() > 200 && m.indexOf("#EXTINF") >= 0) {
                    int n = parseInt(g("count()", 1500), -1);
                    if (n > n0 || n < 0) {
                        dlog("m3u8 new n=" + n + " len=" + m.length() + " round=" + round);
                        return m;
                    }
                    // 条数没涨：播放器没重新加载，可能点的就是当前集，继续等一会儿
                }
                if (round % 6 == 0) {
                    String diag = g("diag()", 1500);
                    dlog("grab diag " + (diag == null ? "no-diag" : diag));
                }
            }
            // v16-probe：每轮都读一次 vu 探针（很便宜），变化时打日志，确认 WebView 走哪条分支
            String vu = g("vu()", 1200);
            if (vu != null && vu.length() > 4 && !vu.equals(lastVu)) {
                lastVu = vu;
                dlog("VU-PROBE " + vu);
            }
            sleep(700);
        }
        // 超时兜底：只有第 1 集才敢复用「进来时已有的那条」——
        // 页面默认播的就是第 1 集，所以它和 ep=0 是同一集。
        // ep>0 时页面当前集未知，复用可能给错集，宁可退回网页模式。
        if (ep <= 0) {
            String reuse = isUsableMurl(mpre) ? mpre : (isUsableMurl(vpre) ? vpre : null);
            if (reuse != null) {
                dlog("murl reuse pre len=" + reuse.length() + " " + murlExpiryState(reuse));
                return MP4_PREFIX + reuse;
            }
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
    public synchronized String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // 不管走哪条返回路径（直链 / 本地代理 / 网页兜底），出来前都把 WebView 里的媒体停掉：
        // 抓 m3u8 时页面确实在播，不停就会一直出声，而 destroy() 在退出播放时不会被调用。
        try {
            return playerContent0(flag, id, vipFlags);
        } finally {
            stopMedia();
        }
    }

    /** playerContent 的真身；外层统一负责把 WebView 里的媒体停掉。 */
    private String playerContent0(String flag, String id, List<String> vipFlags) throws Exception {
        JSONObject out = new JSONObject();
        String mid = id == null ? "" : id;
        int ep = -1;
        int bar = mid.indexOf('|');
        if (bar > 0) {
            ep = parseInt(mid.substring(bar + 1), -1);
            mid = mid.substring(0, bar);
        }
        try {
            long tGrab = System.currentTimeMillis();
            String got = grabM3u8(mid, ep);

            // 【v15：同一个片子有时能播有时不能的根因】
            //   v14 拿到过期签名只是 got=null 然后掉进网页兜底 —— 页面里那条已经旧了，
            //   不重新加载就永远拿到同一条旧的。表现就是「第一次播不了、再点一次又好了」。
            //   ⇒ 过期就重载播放页重新取，最多两次；仍过期才真的拒掉。
            for (int fresh = 0; fresh < 2 && got != null && got.startsWith(MP4_PREFIX)
                    && !isUsableMurl(got.substring(MP4_PREFIX.length())); fresh++) {
                String stale = got.substring(MP4_PREFIX.length());
                dlog("player stale reload mid=" + mid + " " + murlExpiryState(stale)
                        + " try=" + (fresh + 1));
                load(base + "/play/" + mid, PAGE_READY_MS);
                got = grabM3u8(mid, ep);
            }
            if (got == null) {
                // 没拿到：多半是 CAP 没过 / 页面没起来。
                // 重试一次，比直接回退网页模式强 —— 网页模式在壳子里同样过不了 CAP，必然播不了。
                dlog("player retry mid=" + mid + " first=" + (System.currentTimeMillis() - tGrab) + "ms");
                got = grabM3u8(mid, ep);
            }
            // 最优：整片直链（_murl 接口给的签名 MP4，faststart + Range）
            //   不代理、不剥壳、不吃内存，播放器自己拖进度条。
            //   签名有时效，所以每次播放都现场取，不能缓存。
            if (got != null && got.startsWith(MP4_PREFIX)) {
                String direct = got.substring(MP4_PREFIX.length());
                if (!isUsableMurl(direct)) {
                    // 重载两次仍是过期/无效签名，才真的放弃，不把坏 URL 交给播放器。
                    dlog("player reject stale/invalid mp4 " + murlExpiryState(direct));
                    got = null;
                } else {
                    // 只给 UA，**不带 Referer**：bytetos 的签名 URL 不依赖 Referer，
                    // 带上反而可能被某些 CDN 节点的 Referer 白名单拦掉（真机反馈「播不了」）。
                    JSONObject hd = new JSONObject();
                    hd.put("User-Agent", UA);
                    out.put("parse", 0);
                    out.put("url", direct);
                    out.put("playUrl", "");
                    out.put("header", hd);
                    dlog("player mp4 direct mid=" + mid + " len=" + direct.length()
                            + " " + murlExpiryState(direct));
                    return out.toString();
                }
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
                    dlog("player direct mid=" + mid + " kind=" + kind + " seg=" + stat[0] + " miss=" + stat[1]);
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
    // 停掉 WebView 里的媒体
    //   播放页的 <video> 会在我们那个不可见 WebView 里自动播放（见 ensureWv 的
    //   setMediaPlaybackRequiresUserGesture(false)）。spider.destroy() 只在切站点时调用，
    //   退出播放不会调 —— 所以必须自己在 playerContent 返回前停一次。
    //   只 pause + 静音，不删 src：页面 JS 还活着，下次点选集能正常重新加载。
    // ===================================================================
    private void stopMedia() {
        final WebView w = wv;
        if (w == null || main == null) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    w.evaluateJavascript("(function(){try{var a=document.querySelectorAll('video,audio');"
                            + "for(var i=0;i<a.length;i++){try{a[i].pause();a[i].muted=true;a[i].volume=0;}"
                            + "catch(e){}}}catch(e){}})()", null);
                } catch (Throwable ignored) {
                }
            }
        });
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