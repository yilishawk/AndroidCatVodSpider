package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 搜剧AI souju2.ai (API 爬虫, HMAC-SHA256 请求签名)
 *
 * ★ 命名: 本类 = B 方案 (多线路可选, 解析线路+采集线路全收). 干净单线路版见 Souju (A 方案, 同签名协议).
 *   两者 browse/category/search 完全相同, 唯一差别在 detailContent 的播放环:
 *     B (本类): 收全部解析线路(resolve_ticket 二次 POST) + 采集线路(m3u8 直链),
 *               vod_play_from = "官方·线路名$$$采集线路名..." 可切线路;
 *               vod_play_url 每段存 "集名$episodeToken" (token 非真 url), 播放时按集 resolve/解票根
 *     A (Souju): 只取第 1 条可播 m3u8 线路, 单线路, 播放环干净 (真 m3u8 url 直接透传)
 *
 * 端点 (已实测 2026-09-25):
 *   - GET  /v1/browse/catalog?sort=trending&window=day&page=2&limit=20&kind=series&offset=20
 *     返回 JSON: {object, request, cards[], source_statuses, pagination, generated_at}
 *   - GET  /v1/catalog/{card.id}/detail                    详情 (object=catalog.detail, 不含 episodes)
 *   - GET  /v1/catalog/{card.id}/episodes?limit=48&offset=0
 *     返回 object=catalog.episodes, episodes[].token 即播放 key (每集独立)
 *   - GET  /v1/playback/resolve/{episode.token}?view=compact
 *     返回 25~31 条线路 (resolve_ticket 票根 + m3u8 直链), m3u8 各集路径独立
 *   - POST /v1/playback/resolve-line?view=full             ticket -> 真直链 (服务端 400/票根时效, 不实现)
 *   分类 kind: series(剧集) / movie(电影) / anime(番剧) 等 (browse 已实测 series, 其余未验证)
 *
 * 视频解析链路 (已实测, B 方案 = "播放哪集才 resolve 哪集"):
 *   1. detailContent:  GET /v1/catalog/{id}/detail + /v1/catalog/{id}/episodes
 *      -> 只 resolve 第 1 集一次 (GET /v1/playback/resolve/{token0}) 定线路名集合, 不逐集预 resolve
 *      -> B 方案: vod_play_from = "官方·线路名$$$采集线路名..." (解析线路在前, 采集线路在后),
 *                 vod_play_url   = 每线路一段 "第i集$episodeToken#..." (token 非真 url, 来自 /episodes)
 *   2. playerContent:  flag = 线路名(定下标), id = "第i集$episodeToken"(定集 i)
 *      -> 点哪集才 GET /v1/playback/resolve/{token_i} 拿该集 line_options
 *      -> flag 带 "官方·" 前缀: POST /v1/playback/resolve-line (ticket->真直链) 取 m3u8 url
 *      -> flag 无前缀(采集 m3u8 线路): 直接取该集 line_options 里同 label 的 m3u8 url
 *   边界: 官方票根有时效 (实测: 当时有效票根 200/201, 隔几分钟的缓存票根 POST 返 404 playback_line_unavailable).
 *     代码按"播放时新鲜 resolve 再解票根"设计, 能否出画面需真机日志确认; 票根失效时该线路当集不可播, 不崩.
 *
 * 签名协议 (已抠出 + 验证):
 *   前端电影 chunk (movie-card-runtime) 的 Na() 函数:
 *     payload   = METHOD + "\n" + path+query + "\n" + timestamp + "\n" + nonce
 *     signature = HMAC-SHA256(oi, payload)   // 小写 hex
 *     请求头:
 *       x-ai-movie-client-name     = "movie-search-frontend"  (常量)
 *       x-ai-movie-client-version  = "1.0.0"                  (常量)
 *       x-ai-movie-build-version   = "aimovie-v2026.09.24.4-4f6353a71c35×3" (常量, 随前端发版变)
 *       x-ai-movie-protocol-version= "2026-07-05.library-v2.playback-v1"     (常量, 随前端发版变)
 *       x-ai-movie-timestamp       = Date.now() 字符串 (13 位)
 *       x-ai-movie-nonce           = 16 字节随机 hex
 *       x-ai-movie-signature       = 上面的 HMAC-SHA256
 *
 * 边界 (诚实标注, 不兜底):
 *   - 签名 secret 是硬编码在前端 bundle 里的 (oi 常量), 前端发版就变, 纯 Java 写死会在前端更新后失效, 需定期重新扒。
 *   - GET + POST 签名协议均已实测 200/201; browse/catalog/resolve/resolve-line 全链路通。
 *   - B 方案代价: 线路名集合以"第 1 集"resolve 结果为准; 播放某集时按名匹配该集 line_options.
 *     若某集线路名/数量与第 1 集对不上 (线路数随时间浮动), 该集该线路静默缺档 -> 此线路该集不可播, 不崩.
 *     请求量: detail 只 resolve 第 1 集 1 次 (定线路名), 播放每集 +1 次, 不再逐集预 resolve.
 *   - 部分 m3u8 线路实际防盗链/时效短, 播放环不保证每条都能播 (线路多 = 选择多, 但也可能点到坏的)。
 *   - m3u8 playlist 明文可拉, ts 分片多为 AES-128 (EXT-X-KEY, key 在 playlist 同目录可公开取), 播放端需支持 HLS 加密分片, Java 侧不解密。
 *   - 该站带博彩导流广告位 (开元棋牌/PG 电子等, 域名 ddjzis.cn), 风险自负, 不建议生产使用。
 */
public class Souju extends Spider {

    private String host = "https://souju2.ai";
    private Context context; // init 传入, WebView 自动拿 session 用

    // 签名常量 (已实测 2026-09-25)
    // 风险: 随前端发版 (build-version / protocol-version / secret) 会变, 失效后需重新扒前端 bundle
    private static final String CLIENT_NAME     = "movie-search-frontend";
    private static final String CLIENT_VERSION  = "1.0.0";
    private static final String BUILD_VERSION   = "aimovie-v2026.09.24.4-4f6353a71c35-4f6353a71c35-4f6353a71c35";
    private static final String PROTOCOL_VERSION= "2026-07-05.library-v2.playback-v1";
    // 真正的 API 签名密钥 (硬编码在前端 bundle, 不是 bootstrap 里那个第三方埋点 secret)
    // ★ 登录 session (官方线路 resolve-line 必带, 否则 401 playback_user_session_required):
    //   从浏览器登录 souju2.ai 后抓 cookie 里的 ai_movie_session 值 (格式 ums_xxx),
    //   两站 (souju2/kanju2) IP 不同, session 不跨站, 必须用 souju2 域自己的.
    //   为空时官方线路 resolve-line 会 401, 采集线路 (m3u8 直链) 不受影响.
    private static final String SESSION_COOKIE = "";
    // ★ 兜底 cookie 整串 (凯哥 2026-09-26 从浏览器导出): ai_movie_home_address_visited_v1 + ai_movie_browser + ai_movie_session.
    //   策略 (凯哥): 先走 WebView (ensureSession) 拿现成 session; 拿不到 -> 用这套常量兜底.
    //   session 有时效 (ums_xxx), 失效后需重新导出. 采集线路 (m3u8) 不依赖此串.
    private static final String COOKIE_FALLBACK =
            "ai_movie_home_address_visited_v1=1; "
          + "ai_movie_browser=brw_SFhUu2h-j7ZJ2AhchN8BpdrX_ck1LCjEQ71SgOCeFpA; "
          + "ai_movie_session=ums_8HytFesALXqgVRhh3MPXBdusw3Gs1_Pe89uw8UBS2vY";
    // ★ 懒加载 session: WebView 先拿; 拿不到 -> COOKIE_FALLBACK. 拿到/兜底后缓存到这里 (首次后常驻, 后续秒取).
    private String liveSession = "";
    private static final String SIGN_SECRET     = "f39d73aa7a6426203cdee1ef17b31d3b7ea8c23f4c59c62a3a8aa0f39ee5e79d";

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36";

    // 类型映射: tid -> kind (browse 已实测 series; movie/anime 未验证, 按站点语义推测)
    private static final Map<String, String> TID_TO_KIND = new HashMap<>();
    static {
        TID_TO_KIND.put("1", "movie");
        TID_TO_KIND.put("2", "series");
        TID_TO_KIND.put("bangumi", "anime");
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        this.context = context; // 存 Context, WebView 自动拿 session 要用 (主线程)
        if (extend != null && extend.trim().startsWith("http")) {
            host = extend.trim();
        }
    }

    /**
     * 取官方线路要用的 cookie 整串: 策略 = 先走 WebView (ensureSession) 拿现成 cookie;
     * 拿不到 (context 为空 / WebView 异常 / 10 秒内没种上) -> 用 COOKIE_FALLBACK 兜底.
     * 结果缓存到 liveSession (首次后常驻, 后续秒取). 官方线路 + 搜索 POST /v1/threads 都走这里.
     */
    private String sessionValue() {
        if (!TextUtils.isEmpty(liveSession)) return liveSession;
        liveSession = ensureSession();      // 先 WebView 拿
        if (TextUtils.isEmpty(liveSession)) liveSession = COOKIE_FALLBACK; // 拿不到用已有常量兜底
        return liveSession;
    }

    /**
     * 用 WebView 访问 souju2 首页, 从 .souju2.ai 域 CookieManager 里拿**全部 cookie 整串**
     * (k1=v1; k2=v2; ...), 不止 ai_movie_session, 也带 ai_movie_browser 等 (与浏览器态一致).
     * 前提: 本爬虫能在 App 里跑, 且能拿到 Context (init 传入).
     * 边界 (诚实): 该站匿名访问可能不种 session cookie (实测匿名 / 与 /v1/runtime/bootstrap 都不 Set-Cookie),
     *   则 WebView 只能拿到手动登录态残留的 cookie; 首登需在 WebView 里手动登一次. 能否自动种需真机验证.
     * 返回拼好的整串; 拿不到返回 "" (不崩, 由 sessionValue() 回落 COOKIE_FALLBACK).
     */
    private String ensureSession() {
        if (context == null) return "";
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final String[] holder = new String[]{""};
            // CookieManager 必须在主线程 (UI) 操作, 用 Handler 投递
            android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        WebView wv = new WebView(context);
                        WebSettings ws = wv.getSettings();
                        ws.setJavaScriptEnabled(true);
                        ws.setDomStorageEnabled(true);
                        ws.setAllowFileAccess(false);
                        wv.setWebViewClient(new WebViewClient() {
                            @Override
                            public void onPageFinished(WebView view, String url) {
                                try {
                                    CookieManager cm = CookieManager.getInstance();
                                    // 取 souju2 域下全部 cookie, 拼成 "k=v; ..." 整串 (WebView 已自动 Set-Cookie, 直接 dump)
                                    String all = cm.getCookie(host);
                                    if (all != null && !all.trim().isEmpty()) {
                                        holder[0] = all.trim();
                                    }
                                } catch (Exception ignored) {}
                                latch.countDown();
                            }
                        });
                        wv.loadUrl(host + "/");
                    } catch (Exception e) {
                        latch.countDown();
                    }
                }
            });
            // 等页面加载完 (onPageFinished), 最多 10 秒
            latch.await(10, TimeUnit.SECONDS);
            return holder[0];
        } catch (Exception e) {
            // WebView/主线程不可用 -> 返空, 不崩 (sessionValue() 回落 COOKIE_FALLBACK)
            return "";
        }
    }

    // ==================== 签名 ====================

    /** 16 字节随机 hex (前端 La(): crypto.getRandomValues(Uint8Array(16)) -> hex, 无连字符) */
    private static String randomNonce() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** HMAC-SHA256(secret, payload) -> 小写 hex (前端 Da(): Web Crypto subtle.importKey raw HMAC SHA-256 + sign) */
    private static String hmacSha256(String secret, String payload) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"));
        byte[] raw = mac.doFinal(payload.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte x : raw) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** 按前端 Na() 拼签名串: METHOD\npath+query\ntimestamp\nnonce, 算 HMAC-SHA256 */
    private String signatureOf(String method, String fullPath, String timestamp, String nonce) throws Exception {
        String payload = method + "\n" + fullPath + "\n" + timestamp + "\n" + nonce;
        return hmacSha256(SIGN_SECRET, payload);
    }

    /** 构造一组带签名的 GET 请求头 (含 Accept/UA + 7 个 x-ai-movie-* 头) */
    private Map<String, String> signedHeaders(String method, String fullPath) throws Exception {
        String ts    = String.valueOf(System.currentTimeMillis());
        String nonce = randomNonce();
        String sig   = signatureOf(method, fullPath, ts, nonce);
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Accept", "application/json");
        h.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        h.put("Referer", host + "/");
        h.put("Origin", host);
        h.put("x-ai-movie-client-name", CLIENT_NAME);
        h.put("x-ai-movie-client-version", CLIENT_VERSION);
        h.put("x-ai-movie-build-version", BUILD_VERSION);
        h.put("x-ai-movie-protocol-version", PROTOCOL_VERSION);
        h.put("x-ai-movie-timestamp", ts);
        h.put("x-ai-movie-nonce", nonce);
        h.put("x-ai-movie-signature", sig);
        return h;
    }

    // ==================== 抓取 ====================

    /** 带签名的 GET; 返回 JSON 字符串, 失败返回 "" */
    private String getSigned(String fullPath) {
        try {
            Map<String, String> h = signedHeaders("GET", fullPath);
            String abs = fullPath.startsWith("http") ? fullPath : host + fullPath;
            String body = OkHttp.string(abs, h);
            return body == null ? "" : body;
        } catch (Exception e) {
            return "";
        }
    }

    /** 从 cards[] 里挑一个片名/海报字段 (字段名以实测 cards[0] 为准, 这里是通用兜底: 取 title/name + poster/image/pic) */
    private List<Vod> parseCards(JSONObject json) {
        List<Vod> list = new ArrayList<>();
        if (json == null) return list;
        try {
            // 实测顶层: {object, request, cards[], source_statuses, pagination, generated_at}
            if (!json.has("cards")) return list;
            JSONArray arr = json.getJSONArray("cards");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.getJSONObject(i);
                String name  = firstNonEmpty(c, "title", "normalized_title", "name", "show_name", "label");
                // 实测 cards[] 图片字段 = poster_url (百度 gimg 镜像, 非 poster/image/pic)
                String pic   = firstNonEmpty(c, "poster_url", "poster", "image", "pic", "cover", "media_url", "backdrop_url", "carousel_url");
                String id    = firstNonEmpty(c, "id", "vod_id", "uid", "card_id", "source_id");
                if (TextUtils.isEmpty(name)) continue;
                if (TextUtils.isEmpty(id)) id = name + "_" + i;

                Vod vod = new Vod();
                vod.setVodId(id);
                vod.setVodName(name);
                if (!TextUtils.isEmpty(pic)) vod.setVodPic(pic);
                // cards 实测含 year/area/remarks (如 "侠探杰克 第4季", 2026, 美国, 更新至8集), 一并透传
                vod.setVodYear(firstNonEmpty(c, "year", "release_year"));
                vod.setVodArea(firstNonEmpty(c, "area"));
                vod.setVodRemarks(firstNonEmpty(c, "remarks", "updated_at"));
                vod.setVodTag(joinTop(c.optJSONArray("genres"), 4));
                list.add(vod);
            }
        } catch (Exception e) {
            // 字段结构变了 -> 如实返回已解析的, 不崩
        }
        return list;
    }

    private String firstNonEmpty(JSONObject c, String... keys) {
        for (String k : keys) {
            try {
                if (c.has(k) && !c.isNull(k)) {
                    String v = c.optString(k, "");
                    if (!TextUtils.isEmpty(v)) return v;
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    // ==================== 分类映射 ====================

    private String kindOf(String tid) {
        String k = TID_TO_KIND.get(tid);
        return (k != null) ? k : "series";
    }

    // ==================== 播放环 (B 方案: 全线路可选) ====================

    /**
     * B 方案播放方案 (catvod 契约, 参照 FKTV/NG):
     *   线路间 $$$, 线路内集间 #, 每集 "名称$<episodeToken>" (token 非真 url)
     *   vod_play_from = "线路A$$$线路B$$$..."
     *   vod_play_url  = "第1集$token1#第2集$token2...$$$第1集$token1#第2集$token2..." (每线路一段, 段内集名对齐)
     *   playerContent(flag, id): flag = 选中线路名(定下标 j), id = "名称$token"(定集 i) -> 按集 resolve 取 m3u8 url.
     *
     * 只实现 url_kind=m3u8 直链; 官方 resolve_ticket 线路 (resolve-line 服务端 400/票根时效) 不实现, 不兜底.
     * 边界: m3u8 playlist 明文可拉, ts 分片多为 AES-128 (EXT-X-KEY, key 同目录可取), 播放端需支持 HLS 加密分片, Java 侧不解密.
     * 设计: detail 只 resolve 第 1 集 1 次定线路名集合, 播放时按"线路名+provider_id 兜底"逐集匹配该集 m3u8 url,
     *       避免旧版"第 1 集定线路、后续集按 index 取 url"的跨集线路数浮动静默错位.
     */
    private static class PlayPlan {
        String playFroms = "";
        String playUrls  = "";
        String[] lineNames = null; // 第 1 集 line_options 顺序, 与 playFroms 一一对应
    }

    /**
     * B 方案 (全线路可选, 播放哪集才 resolve 哪集):
     *   1) /episodes 拿各集 token (播放 key)
     *   2) 只 resolve 第 1 集 -> line_options (m3u8) 定线路名集合 (provider 顺序对全片一致)
     *   3) vod_play_from = 线路名 $$$ 拼接; vod_play_url 每线路一段 "第i集$token#..." (token 进串, 非真 url)
     *   真 url 延迟到 playerContent 按集 resolve + 按名匹配时才取, 不预取.
     */
    private PlayPlan buildPlayPlanAll(String catalogId) {
        PlayPlan p = new PlayPlan();
        try {
            String epJson = getSigned("/v1/catalog/" + URLEncoder.encode(catalogId, "UTF-8")
                    + "/episodes?limit=48&offset=0");
            if (TextUtils.isEmpty(epJson)) return p;
            JSONObject epj = new JSONObject(epJson);
            JSONArray eps = epj.optJSONArray("episodes");
            if (eps == null || eps.length() == 0) return p;

            // 只 resolve 第 1 集, 定线路名集合 (后续集不预 resolve, 播放时按集解析)
            JSONArray firstLines = resolveAllLines(eps.getJSONObject(0).optString("token", ""));
            if (firstLines.length() == 0) return p;

            int n = Math.min(eps.length(), 5);
            String[] lineNames = new String[firstLines.length()];
            for (int j = 0; j < firstLines.length(); j++) {
                JSONObject l = firstLines.getJSONObject(j);
                // 解析线路 (resolve_ticket) 加 "官方·" 前缀, 采集线路 (m3u8) 用原 label
                String name = "resolve_ticket".equals(l.optString("url_kind", ""))
                        ? officialLineName(l)
                        : firstNonEmpty(l, "label", "display_label", "provider_name");
                if (TextUtils.isEmpty(name)) name = "线路" + (j + 1);
                lineNames[j] = name;
                p.playFroms += (j == 0 ? "" : "$$$") + name;
            }
            p.lineNames = lineNames;

            // playUrls: 每条线路一段 "第i集$token#...". 各段内集数顺序 = 第1集..第n集.
            // 段内 token 取自 /episodes (第 i 集同一个 token 复用到所有线路段).
            StringBuilder urls = new StringBuilder();
            for (int j = 0; j < firstLines.length(); j++) {
                if (j > 0) urls.append("$$$");
                StringBuilder seg = new StringBuilder();
                int epDone = 0;
                for (int i = 0; i < n; i++) {
                    JSONObject ep = eps.getJSONObject(i);
                    String title = ep.optString("title", "第" + (i + 1) + "集");
                    String token = ep.optString("token", "");
                    if (TextUtils.isEmpty(token)) continue;
                    if (epDone > 0) seg.append("#");
                    seg.append(title).append("$").append(token);
                    epDone++;
                }
                if (epDone == 0) continue;
                urls.append(seg);
            }
            p.playUrls = urls.toString();
        } catch (Exception e) {
            // 字段结构变 -> 如实返回已拼的, 不崩
        }
        return p;
    }

    /**
     * resolve 一集, 返回全部线路 JSONArray (url_kind=m3u8 + resolve_ticket, 按 line_options 原序, 可能为空).
     * 解析线路(resolve_ticket)和采集线路(m3u8)都要, 供 playerContent 按 flag 前缀路由.
     * 同一部片 provider 顺序对全片一致 (已实测), 仅路径段随集变化, 故第 1 集定线路名集合.
     */
    private JSONArray resolveAllLines(String episodeToken) {
        try {
            String json = getSigned("/v1/playback/resolve/" + URLEncoder.encode(episodeToken, "UTF-8") + "?view=compact");
            if (TextUtils.isEmpty(json)) return new JSONArray();
            JSONObject j = new JSONObject(json);
            JSONArray lo = j.optJSONArray("line_options");
            if (lo == null) return new JSONArray();
            JSONArray out = new JSONArray();
            for (int i = 0; i < lo.length(); i++) {
                JSONObject l = lo.getJSONObject(i);
                String kind = l.optString("url_kind", "");
                if ("m3u8".equals(kind) || "resolve_ticket".equals(kind)) out.put(l);
            }
            return out;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** 官方解析线路名 (resolve_ticket), 用于 flag 前缀路由; 采集线路名 (m3u8) 无此标记. */
    private String officialLineName(JSONObject line) {
        String label = firstNonEmpty(line, "label", "display_label", "provider_name");
        if (TextUtils.isEmpty(label)) label = line.optString("provider_id", "");
        if (TextUtils.isEmpty(label)) label = "线路";
        return "官方·" + label;
    }

    /**
     * 把票根 (resolve_ticket) 二次解析成可播直链:
     * POST /v1/playback/resolve-line?view=compact, body={"ticket":"<剥 resolve:// 前缀的票根>"}.
     * 官方线路需要登录态: 带 SESSION_COOKIE (空时不带, 会 401 playback_user_session_required).
     * 返回 [url, url_kind]; url_kind 可能 m3u8/mp4/unknown (源质量各异, mp4 解出的可能是图片).
     * 边界: 票根有时效, 须用当时 resolve 的新鲜票根, 缓存票根大概率 404.
     */
    private String[] postResolveLine(String rawTicket) {
        if (TextUtils.isEmpty(rawTicket)) return new String[]{"", ""};
        String ticket = rawTicket.startsWith("resolve://") ? rawTicket.substring(10) : rawTicket;
        ticket = ticket.trim();
        if (TextUtils.isEmpty(ticket)) return new String[]{"", ""};
        try {
            String path = "/v1/playback/resolve-line?view=compact";
            String ts    = String.valueOf(System.currentTimeMillis());
            String nonce = randomNonce();
            String sig   = signatureOf("POST", path, ts, nonce);
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", UA);
            h.put("Accept", "application/json");
            h.put("Content-Type", "application/json");
            h.put("Referer", host + "/");
            h.put("Origin", host);
            String sess = sessionValue(); // WebView 先拿, 拿不到用 COOKIE_FALLBACK (整串 cookie, 非单个 session)
            if (!TextUtils.isEmpty(sess)) {
                // 官方线路需登录态: 整串透传 (含 ai_movie_session=ums_xxx + ai_movie_browser 等, 两站不跨)
                h.put("Cookie", sess);
            }
            h.put("x-ai-movie-client-name", CLIENT_NAME);
            h.put("x-ai-movie-client-version", CLIENT_VERSION);
            h.put("x-ai-movie-build-version", BUILD_VERSION);
            h.put("x-ai-movie-protocol-version", PROTOCOL_VERSION);
            h.put("x-ai-movie-timestamp", ts);
            h.put("x-ai-movie-nonce", nonce);
            h.put("x-ai-movie-signature", sig);
            String abs = host + path;
            String body = OkHttp.post(abs, "{\"ticket\":\"" + ticket + "\"}", h).getBody();
            if (TextUtils.isEmpty(body)) return new String[]{"", ""};
            JSONObject j = new JSONObject(body);
            // 401/404 错误响应没有 line 字段 -> 返空
            if (!"playback.line.resolve".equals(j.optString("object", ""))) {
                return new String[]{"", ""};
            }
            JSONObject line = j.optJSONObject("line");
            if (line == null) return new String[]{"", ""};
            String url  = line.optString("url", "");
            String kind = line.optString("url_kind", "");
            return new String[]{url, kind};
        } catch (Exception e) {
            // 401/404 / 票根失效 -> 返 "", 不崩
            return new String[]{"", ""};
        }
    }

    /** 直连透传给壳子: url + 4 个请求头 (UA/Referer/Origin/Accept), parse=0 (我们已解析好, 直接推直连; 失败才用 parse=1 让壳子再解析).
     *  按 url_kind 选 format: m3u8 -> application/x-mpegURL (壳子走 HLS); mp4/unknown -> application/octet-stream.
     *  Origin 不带斜杠 (HTTP 规范), Referer 带 / (凯哥样例). 注: 字节图床 mp4 真实响应标 image/jpeg 是源伪装, 壳子按 url 自己判容器. */
    private String passthrough(String url, String urlKind) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
    // headers.put("Referer", host + "/");
        headers.put("Origin", host);
        headers.put("Accept", "*/*");
        Result r = Result.get().parse(0).url(url).header(headers);
        if ("m3u8".equals(urlKind)) r.m3u8();
        else r.octet(); // mp4/unknown -> 普通流
        return r.string();
    }

    /** JSON 字符串转义 (中文 key/value 拼 body 用, 避免引号/反斜杠破坏 body). */
    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 带签名的 POST (JSON body). 签名串与 GET 同公式: METHOD\npath+query\ntimestamp\nnonce,
     * 但路径用 **不带 query 的 path** (实测 resolve-line POST 按 view 参数分开签, 这里 body 走 JSON 不走 query).
     * 返回 JSON 字符串, 失败返 "". 7 个 x-ai-movie-* 头与 GET 相同.
     */
    private String postSigned(String path, String jsonBody) {
        try {
            String ts    = String.valueOf(System.currentTimeMillis());
            String nonce = randomNonce();
            String sig   = signatureOf("POST", path, ts, nonce);
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", UA);
            h.put("Accept", "application/json");
            h.put("Content-Type", "application/json");
            h.put("Referer", host + "/");
            h.put("Origin", host);
            h.put("x-ai-movie-client-name", CLIENT_NAME);
            h.put("x-ai-movie-client-version", CLIENT_VERSION);
            h.put("x-ai-movie-build-version", BUILD_VERSION);
            h.put("x-ai-movie-protocol-version", PROTOCOL_VERSION);
            h.put("x-ai-movie-timestamp", ts);
            h.put("x-ai-movie-nonce", nonce);
            h.put("x-ai-movie-signature", sig);
            String body = OkHttp.post(host + path, jsonBody, h).getBody();
            return body == null ? "" : body;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 搜索第 1 步: POST /v1/threads 起一个搜索线程, 返回 thread_id (at_xxx). 拿不到返 "".
     * 抓包权威协议 (凯哥 2026-09-26):
     *   body = {"title":"<key>","metadata":{"search_fields":"all","search_scope_label":"综合",
     *           "search_mode":"fast","source":"movie_composer_route","submission_id":"<uuid>"}}
     *   返回 = {"id":"at_xxx","object":"thread"}
     * 注: 每次搜索都要新起 thread (thread_id 有时效, 换词即废), 不缓存.
     */
    private String openSearchThread(String key) {
        if (TextUtils.isEmpty(key)) return "";
        String k = jsonEscape(key.trim());
        String subId = "souju2-" + System.currentTimeMillis() + "-" + (int) (Math.random() * 0xFFFFFF);
        String body = "{\"title\":\"" + k
                + "\",\"metadata\":{\"search_fields\":\"all\",\"search_scope_label\":\"综合\""
                + ",\"search_mode\":\"fast\",\"source\":\"movie_composer_route\""
                + ",\"submission_id\":\"" + subId + "\"}}";
        String json = postSigned("/v1/threads", body);
        if (TextUtils.isEmpty(json)) return "";
        try {
            JSONObject j = new JSONObject(json);
            // 线程对象 = {"id":"at_xxx","object":"thread"}, id 字段即 thread_id
            String tid = j.optString("id", "");
            if (TextUtils.isEmpty(tid)) tid = j.optString("thread_id", "");
            return tid;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 搜索第 2 步: GET /v1/browse/catalog?query_mode=fast_v3&thread_id=<at>&search_fields=all&q=<key>
     * 返回的 cards[] 结构与 browse 完全一致 (id/title/poster_url/genres/year/area...), 直接 parseCards.
     * 翻页: 本步带 thread_id 复用同一搜索线程; 抓包 response 里有 continuation.response_id (ar_xxx),
     *   多页翻页机制 (游标) 未探明, 暂按"每页带 thread_id + page"实现, 真机确认游标后再优化.
     */
    private List<Vod> browseByThread(String key, int page) {
        String tid = openSearchThread(key);
        if (TextUtils.isEmpty(tid)) return new ArrayList<>();
        String qEnc;
        try {
            qEnc = URLEncoder.encode(key, "UTF-8");
        } catch (Exception e) {
            qEnc = key;
        }
        String query = "?query_mode=fast_v3&thread_id=" + tid + "&search_fields=all&limit=20"
                + "&page=" + page + "&q=" + qEnc;
        String json = getSigned("/v1/browse/catalog" + query);
        if (TextUtils.isEmpty(json)) return new ArrayList<>();
        try {
            return parseCards(new JSONObject(json));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 旧版兼容: 直链透传 (无 url_kind 时默认 m3u8). */
    private String passthroughM3u8(String m3u8Url) {
        return passthrough(m3u8Url, "m3u8");
    }

    // ==================== Spider ====================

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("1", "电影"));
        classes.add(new Class("2", "电视剧"));
        classes.add(new Class("bangumi", "番剧"));

        // 首页 = 默认分类第 1 页 (browse)
        List<Vod> vods = browse("2", "series", 1, 20);

        return Result.get()
                .classes(classes)
                .vod(vods)
                .page(1, 1, vods.size(), 0)
                .string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) throws Exception {
        int page = TextUtils.isEmpty(pg) ? 1 : Integer.parseInt(pg);
        String kind = kindOf(tid);
        List<Vod> vods = browse(tid, kind, page, 20);

        int next = vods.isEmpty() ? page : page + 1;
        int total = 0; // 站点 pagination 字段未暴露总页数, 保持 0 -> Result.page 0 值 -> MAX_VALUE
        return Result.get()
                .vod(vods)
                .page(page, next, vods.size(), total)
                .string();
    }

    /** 核心抓取: 带签名 GET /v1/browse/catalog, 解析 cards[] -> List<Vod> */
    private List<Vod> browse(String tid, String kind, int page, int limit) {
        String path = "/v1/browse/catalog";
        String kindEncoded;
        try {
            kindEncoded = URLEncoder.encode(kind, "UTF-8");
        } catch (Exception e) {
            kindEncoded = kind; // 兜底: 编码异常时透传原 kind (kind 实际是 series/movie/anime 英文, 无需编码)
        }
        String query = "?sort=trending&window=day&page=" + page
                + "&limit=" + limit + "&kind=" + kindEncoded
                + "&offset=" + (page - 1) * limit;
        String fullPath = path + query;
        String json = getSigned(fullPath);
        if (TextUtils.isEmpty(json)) return new ArrayList<>();
        try {
            return parseCards(new JSONObject(json));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return Result.error("id 为空");
        String id = ids.get(0);

        // 1) 详情 /v1/catalog/{id}/detail (实测 200): 取 title/year/area/actors/directors/poster/description/remarks
        String detailJson = getSigned("/v1/catalog/" + URLEncoder.encode(id, "UTF-8") + "/detail");
        if (TextUtils.isEmpty(detailJson)) {
            return Result.error("详情拉取失败 id=" + id);
        }

        Vod vod;
        try {
            JSONObject d = new JSONObject(detailJson);
            vod = new Vod();
            vod.setVodId(id);
            vod.setVodName(firstNonEmpty(d, "title", "normalized_title", "name"));
            vod.setVodYear(firstNonEmpty(d, "year"));
            vod.setVodArea(firstNonEmpty(d, "area"));
            vod.setVodRemarks(firstNonEmpty(d, "remarks", "release_year"));
            vod.setVodContent(firstNonEmpty(d, "description", "synopsis", "content"));

            // 演员/导演是数组, 取前几个 join
            vod.setVodActor(joinTop(d.optJSONArray("actors"), 8));
            vod.setVodDirector(joinTop(d.optJSONArray("directors"), 5));

            // 海报: poster_url 优先, 回退 backdrop/carousel
            String pic = firstNonEmpty(d, "poster_url", "backdrop_url", "carousel_url");
            if (!TextUtils.isEmpty(pic)) vod.setVodPic(pic);

            // 类型: genres 数组 join
            vod.setVodTag(joinTop(d.optJSONArray("genres"), 4));
        } catch (Exception e) {
            return Result.error("详情解析失败 id=" + id);
        }

        // 2) 播放环 (B 方案: 全线路可选) /v1/catalog/{id}/episodes + /v1/playback/resolve/{token}
        //    只实现 m3u8 直链线路; 官方 resolve_ticket 线路(resolve-line 400/票根时效) 不实现.
        PlayPlan plan = buildPlayPlanAll(id);
        if (!TextUtils.isEmpty(plan.playFroms)) {
            vod.setVodPlayFrom(plan.playFroms);
            vod.setVodPlayUrl(plan.playUrls);
        }

        return Result.get().vod(vod).string();
    }

    /** 取 JSONArray 前 n 个非空字符串, 逗号 join (null-safe). */
    private static String joinTop(JSONArray arr, int n) {
        if (arr == null) return "";
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        for (int i = 0; i < arr.length() && taken < n; i++) {
            String v = arr.optString(i, "");
            if (TextUtils.isEmpty(v)) continue;
            if (sb.length() > 0) sb.append("、");
            sb.append(v);
            taken++;
        }
        return sb.toString();
    }

    @Override
    public String searchContent(String key, boolean quick) {
        // 搜索两步协议 (凯哥 2026-09-26 抓包): POST /v1/threads 起线程 -> GET /v1/browse/catalog?thread_id=...
        // 第 1 页; 翻页见带 pg 的重载. 拿不到 thread / 无结果 -> 诚实空列表, 不崩.
        if (TextUtils.isEmpty(key)) return Result.get().vod(new ArrayList<>()).page(1, 1, 0, 0).string();
        List<Vod> vods = browseByThread(key, 1);
        int total = 0; // 搜索响应 continuation 未暴露总页数, 保持 0 -> 调用方按 hasNext 判断
        int next = vods.isEmpty() ? 1 : 2;
        return Result.get().vod(vods).page(1, next, vods.size(), total).string();
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        // 多页搜索: 每次都重新 POST /v1/threads 起新线程 (thread_id 有时效, 换词即废), 带 page 取该页.
        // 翻页机制 (是否用 continuation.response_id 游标) 真机确认后再优化, 暂按 page 数字.
        if (TextUtils.isEmpty(key)) return Result.get().vod(new ArrayList<>()).page(1, 1, 0, 0).string();
        int page = TextUtils.isEmpty(pg) ? 1 : Integer.parseInt(pg);
        List<Vod> vods = browseByThread(key, page);
        int total = 0;
        int next = vods.isEmpty() ? page : page + 1;
        return Result.get().vod(vods).page(page, next, vods.size(), total).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // B 方案: "播放哪集才 resolve 哪集".
        //   flag = 选中的线路名 (vod_play_from 里的 $$$ 段, 即第 1 集 line_options 的某条线路名)
        //   id   = "第i集$episodeToken" (vod_play_url 段里的某集, 取 $ 后的 token)
        //   流程: token -> GET /v1/playback/resolve/{token} -> line_options(m3u8)
        //         按 flag(线路名) 在该集 line_options 里定位 url, 透传 + Referer/Origin/UA, parse=1, format=m3u8.
        if (TextUtils.isEmpty(id)) return Result.error("播放 url 为空");

        String url = id;
        int sep = url.lastIndexOf('$'); // 名称$token 里的 $ 在前 (如 "第1集$av_xxx")
        if (sep >= 0 && sep < url.length() - 1) url = url.substring(sep + 1);
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) {
            // url 其实是 episodeToken (非直链) -> 按集 resolve 取 m3u8 url
            return resolveAndReturn(url, flag);
        }
        // 兜底: 若 detail 里已写好真 m3u8 url (旧版兼容), 直接透传
        return passthroughM3u8(url);
    }

    /** 按 episodeToken 调 /v1/playback/resolve/{token}, 按 flag 前缀路由: 官方线路解票根, 采集线路取 m3u8 url. */
    private String resolveAndReturn(String episodeToken, String flag) {
        try {
            String json = getSigned("/v1/playback/resolve/" + URLEncoder.encode(episodeToken, "UTF-8") + "?view=compact");
            if (TextUtils.isEmpty(json)) return Result.error("resolve 失败 token=" + episodeToken);
            JSONObject j = new JSONObject(json);
            JSONArray lo = j.optJSONArray("line_options");
            if (lo == null || lo.length() == 0) return Result.error("该集无可用线路");

            String targetLabel;
            boolean isOfficial = false;
            if (flag != null && flag.length() > 0) {
                if (flag.startsWith("官方·")) { isOfficial = true; targetLabel = flag.substring(3); }
                else targetLabel = flag;
            } else { targetLabel = ""; }

            // 1) 按线路名匹配 (官方线路: 找 resolve_ticket; 采集线路: 找 m3u8)
            JSONObject hit = null;
            if (targetLabel.length() > 0) {
                for (int i = 0; i < lo.length(); i++) {
                    JSONObject l = lo.getJSONObject(i);
                    String nm = firstNonEmpty(l, "label", "display_label", "provider_name");
                    String kind = l.optString("url_kind", "");
                    if (nm.equals(targetLabel)) {
                        if (isOfficial && "resolve_ticket".equals(kind)) { hit = l; break; }
                        if (!isOfficial && "m3u8".equals(kind)) { hit = l; break; }
                    }
                }
            }
            // 2) 兜底: 找不到目标线路名时, 按类型取第一条 (官方取 resolve_ticket, 采集取 m3u8)
            if (hit == null) {
                String wantKind = isOfficial ? "resolve_ticket" : "m3u8";
                for (int i = 0; i < lo.length(); i++) {
                    JSONObject l = lo.getJSONObject(i);
                    if (wantKind.equals(l.optString("url_kind", ""))) { hit = l; break; }
                }
            }
            if (hit == null) return Result.error("线路 " + flag + " 在该集无可用 url");

            String url = hit.optString("url", "");
            String kind = hit.optString("url_kind", "");
            if ("resolve_ticket".equals(kind)) {
                // 官方解析线路: 二次 POST 解票根拿真直链 (票根有时效, 此时用刚 resolve 的新鲜票根).
                // 需登录 session (SESSION_COOKIE); 未配或解不出 (401/404/票根失效) -> 回落采集线路.
                String[] r = postResolveLine(url);
                if (!TextUtils.isEmpty(r[0])) {
                    return passthrough(r[0], r[1]); // 官方线路成功, 按 url_kind 区分格式 (m3u8->m3u8(), 其他->octet())
                }
                // 回落: 该集取第一条 m3u8 采集线路 (不需要登录, 实测能播)
                String fallback = pickFirstM3u8(lo);
                if (TextUtils.isEmpty(fallback)) return Result.error("官方线路 " + flag + " 解析失败且无采集线路兜底");
                return passthrough(fallback, "m3u8");
            }
            // 采集线路: 直接透传 m3u8 直链
            if (TextUtils.isEmpty(url)) return Result.error("线路 " + flag + " 在该集无 m3u8 url");
            return passthrough(url, kind);
        } catch (Exception e) {
            return Result.error("resolve 失败: " + e.getMessage());
        }
    }

    /** 取 line_options 里第一条 m3u8 直链 (官方线路失败时的兜底, 采集线路不需要登录). */
    private String pickFirstM3u8(JSONArray lo) {
        if (lo == null) return "";
        for (int i = 0; i < lo.length(); i++) {
            try {
                JSONObject l = lo.getJSONObject(i);
                if ("m3u8".equals(l.optString("url_kind", ""))) {
                    String u = l.optString("url", "");
                    if (!TextUtils.isEmpty(u)) return u;
                }
            } catch (Exception ignored) {}
        }
        return "";
    }
}
