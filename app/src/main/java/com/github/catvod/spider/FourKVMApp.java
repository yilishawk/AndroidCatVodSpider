package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 4K影视 App API 爬虫（签名版）
 *
 * 数据源: https://ant.xhssapp.com  端点 /app/v1/*
 * （抓包坐实：真实 host 是 ant.xhssapp.com，不是 4kvm.appleapic.com；
 *   之前的 404 根因就是 host 错。config 等明文接口在 4kvm.appleapic.com 也通，
 *   但 filter/play 等加密接口只认 ant.xhssapp.com）
 *
 * 与网页版 FourKVM.java（爬 www.4kvm.cc HTML）并存，互不影响。
 *
 * 以下算法全部逆向自 4K影视.apk：
 *  - libgojni.so（go.cryptolib.gojni / cryptolib.Crypto，ARM64 符号未 strip）：
 *      · HMAC 密钥(明文, rodata 0x3bdd6) = "mymovie_encryption_secret_2025_v1"
 *      · signPublicKeyWithTimestamp: msg = 公钥 || 分隔 || 时间戳(10位秒),
 *        sig = base64( HMAC-SHA256( key, msg ) )
 *      · GenerateKeyPair: X25519 协商 32B 会话密钥; Encrypt: AES-GCM(32B key, 12B nonce 前置)
 *  - 抓包（C:\Users\Administrator\Desktop\4k\）坐实真实协议：
 *      · host = ant.xhssapp.com
 *      · /app/v1/filter 走 POST，但头里带 "X-NB-Method: GET"（真 GET 语义包在 POST 信封）
 *      · 请求/响应体 Content-Type = application/octet-stream，是 45B 头 + raw AES-GCM 二进制包
 *        （45B 头 = 4B magic "NM\x01\x00" + 1B len=0x20 + 40B hash 区；45B 头后是 12B nonce + 密文 + 16B tag）
 *      · 请求头只有 X-Platform / X-Device-Id / X-Device-Name / X-App-Version / X-NB-Method / Content-Type / Host
 *        —— **没有 X-Sign / nonce / timestamp**（签名走的是会话内加密，不是每请求 HMAC 头）
 *
 * 播放(playerContent) = 双通道：
 *  1) 签名 API /app/v1/play 取直链（若成功 parse=0）
 *  2) 失败/未闭环 → 退到网页版 FourKVM 的播放页嗅探（parse=1），保证可出画面
 *
 * ⚠ 未闭环、需 frida 才能拿的 2 个点（已集中为常量）：
 *  1. SESSION_KEY_B64 —— X25519 协商出的 32B 会话密钥（AES-GCM 用的 key），纯 Java 拿不到，
 *     必须 frida 钩 Java_cryptolib_Crypto_computeSharedSecret 出参，跑一次 App 拿真实值填这里。
 *     留空时 parseBody 只解 JSON 明文（config 等），不解密 filter/play 的 GCM 包。
 *  2. X-NB-Method 头的精确取值（抓包是 "GET"，其它端点可能 "POST"/"PUT"，需逐端点对齐）
 */
public class FourKVMApp extends Spider {

    private static final String DEFAULT_HOST = "https://ant.xhssapp.com";
    private static final String WEB_HOST = "https://www.4kvm.cc";
    private static final String SECRET_KEY = "mymovie_encryption_secret_2025_v1";
    private static final String SIGN_SEPARATOR = "|";
    /** 签名待签串模板：%s=nonce %ts=timestamp %path=接口 %app=appId（抓包后调整顺序） */
    private static final String SIGN_SOURCE_TEMPLATE = "%s|%ts|%path|%app";
    /**
     * X25519 会话对称密钥（base64，32B）。
     * 抓包坐实：服务端用 AES-GCM(会话密钥, 12B nonce + 密文 + 16B tag) 加密响应体，
     * 包外层 45B 头（4B magic 4e4d0100 + 1B len=0x20 + 40B hash 区）后是 raw GCM。
     * 该密钥是 X25519 ECDH(私钥, 服务端公钥) 协商出的 32B，纯 Java 拿不到，
     * 必须用 frida 钩 Java_cryptolib_Crypto_computeSharedSecret 出参拿真实值填这里。
     * 留空时 parseBody 走 JSON 兜底，不解密。
     */
    private static final String SESSION_KEY_B64 = "";
    /** 加密包 45B 头的 magic，用来识别"响应是二进制加密包" */
    private static final byte[] ENC_MAGIC = {0x4E, 0x4D, 0x01, 0x00};

    private String host = DEFAULT_HOST;
    private String appId = "nbmovie";
    private String deviceId;
    private String deviceName;
    private String appVersion = "1.0.0";
    private Map<String, String> baseHeaders = new HashMap<>();
    private int nextNonce = 1000;

    public FourKVMApp() {
        deviceId = Long.toHexString(System.nanoTime()) + Integer.toHexString((int) (Math.random() * 0xFFFF));
        deviceName = "android-" + deviceId;
    }

    // ---------- 请求头 / 签名 ----------

    @Override
    public void init(Context context, String extend) {
        if (!TextUtils.isEmpty(extend)) {
            try {
                JSONObject cfg = new JSONObject(extend);
                if (cfg.has("host")) host = cfg.optString("host", DEFAULT_HOST).replaceAll("/+$", "");
                if (cfg.has("appId")) appId = cfg.optString("appId", appId);
                if (cfg.has("deviceId")) deviceId = cfg.optString("deviceId", deviceId);
                if (cfg.has("appVersion")) appVersion = cfg.optString("appVersion", appVersion);
            } catch (Exception e) {
                SpiderDebug.log("[4kApp] 解析 extend 失败: " + e.getMessage());
            }
        }
        baseHeaders = new HashMap<>();
        // 抓包坐实的真实请求头（无 X-Sign/nonce/timestamp，签名走会话加密而非每请求 HMAC 头）
        baseHeaders.put("User-Agent", "4K影视 Android App");
        baseHeaders.put("Accept", "application/json");
        baseHeaders.put("X-Platform", "android");
        baseHeaders.put("X-App-Version", appVersion);
        baseHeaders.put("X-Device-Id", deviceId);
        baseHeaders.put("X-Device-Name", deviceName);
        baseHeaders.put("Content-Type", "application/octet-stream");
        SpiderDebug.log("[4kApp] host=" + host + " appId=" + appId + " device=" + deviceId);
    }

    /**
     * 构造请求头（抓包坐实：真实协议带 X-NB-Method 头，GET 语义包在 POST 信封里）。
     * - 默认所有请求走 POST 信封 + X-NB-Method: GET（filter/play 等端点）
     * - 明文接口（config/home 等）也走同一信封，响应 parseBody 自动分流（二进制 GCM / JSON）
     */
    private Map<String, String> nbHeaders(String path, String nbMethod, Map<String, String> extraQuery) {
        Map<String, String> h = new HashMap<>(baseHeaders);
        h.put("X-NB-Method", nbMethod == null ? "GET" : nbMethod);
        return h;
    }

    /** 签名 = base64( HMAC-SHA256( SECRET_KEY, 待签串 ) )；待签串由模板拼装 */
    private String sign(String path, String nonce, long ts) {
        String source = SIGN_SOURCE_TEMPLATE
                .replace("%s", nonce)
                .replace("%ts", String.valueOf(ts))
                .replace("%path", path == null ? "" : path)
                .replace("%app", appId);
        return base64Of(hmacSha256(source));
    }

    // ---------- 统一请求（自动 gzip/SSL） ----------

    private JSONObject apiGet(String path, Map<String, String> query) throws JSONException {
        StringBuilder url = new StringBuilder(host).append(path);
        if (query != null && !query.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (!first) url.append('&');
                url.append(e.getKey()).append('=').append(safe(e.getValue()));
                first = false;
            }
        }
        String full = url.toString();
        // 抓包坐实：真实端点全走 POST 信封 + X-NB-Method: GET；参数拼在 query 里
        Map<String, String> h = nbHeaders(path, "GET", query);
        String raw;
        try {
            raw = OkHttp.post(full, "", h).getBody();
        } catch (Exception e) {
            SpiderDebug.log("[4kApp] GET " + path + " 失败: " + e.getMessage());
            return new JSONObject();
        }
        if (TextUtils.isEmpty(raw)) return new JSONObject();
        return parseBody(raw, path);
    }

    private JSONObject apiPost(String path, JSONObject body) throws JSONException {
        // 抓包坐实：POST 信封 + X-NB-Method: POST；body 是 JSON
        Map<String, String> h = nbHeaders(path, "POST", null);
        String raw;
        try {
            raw = OkHttp.post(host + path, body == null ? "{}" : body.toString(), h).getBody();
        } catch (Exception e) {
            SpiderDebug.log("[4kApp] POST " + path + " 失败: " + e.getMessage());
            return new JSONObject();
        }
        if (TextUtils.isEmpty(raw)) return new JSONObject();
        return parseBody(raw, path);
    }

    /**
     * 响应体分流（抓包坐实两种形态）：
     *  1) 二进制加密包：45B 头（magic 4e4d0100 + 1B len + 40B hash 区）+ raw AES-GCM(12B nonce+密文+16B tag)
     *     需 SESSION_KEY_B64（X25519 会话密钥）解密，明文是 JSON
     *  2) 明文 JSON（config/home 等）：直接 parse
     * 识别：raw 首 4B == ENC_MAGIC(0x4e 0x4d 0x01 0x00) → 走 GCM；否则 JSON
     */
    private JSONObject parseBody(String raw, String path) throws JSONException {
        if (TextUtils.isEmpty(raw)) return new JSONObject();
        // 1) 先试明文 JSON（config/home 等 200 明文接口）
        try {
            return new JSONObject(raw);
        } catch (Exception ignore) {
        }
        // 2) 二进制 GCM 包（OkHttp 拿到的是 base64 字符串形态，先 base64 解）
        byte[] bin = base64Loose(raw);
        if (bin == null || bin.length < 45) {
            JSONObject o = new JSONObject();
            o.put("raw", raw);
            return o;
        }
        if (bin[0] != ENC_MAGIC[0] || bin[1] != ENC_MAGIC[1] || bin[2] != ENC_MAGIC[2] || bin[3] != ENC_MAGIC[3]) {
            JSONObject o = new JSONObject();
            o.put("raw", raw);
            return o;
        }
        byte[] key = sessionKey();
        if (key == null) {
            SpiderDebug.log("[4kApp] " + path + " 是 GCM 包但 SESSION_KEY_B64 未填，无法解密（需 frida 取 X25519 共享密钥）");
            JSONObject o = new JSONObject();
            o.put("raw", raw);
            o.put("enc_gcm", true);
            return o;
        }
        // 45B 头后：12B nonce + 密文 + 16B tag
        try {
            byte[] nonce = new byte[12];
            System.arraycopy(bin, 45, nonce, 0, 12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] dec = c.doFinal(bin, 45 + 12, bin.length - 45 - 12 - 16);
            return new JSONObject(new String(dec, StandardCharsets.UTF_8));
        } catch (Exception e) {
            SpiderDebug.log("[4kApp] " + path + " GCM 解密失败: " + e.getMessage());
            JSONObject o = new JSONObject();
            o.put("raw", raw);
            o.put("enc_gcm", true);
            return o;
        }
    }

    /** 宽松 base64 解码（兼容 OkHttp 把二进制体按 base64 字符串返回的情况） */
    private static byte[] base64Loose(String s) {
        if (TextUtils.isEmpty(s)) return null;
        try {
            return Base64.decode(s.trim(), Base64.DEFAULT);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- CatVod 契约方法 ----------

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = new JSONArray();
            JSONObject cfg = apiGet("/app/v1/config", null);
            JSONArray real = extractConfigClasses(cfg);
            if (real.length() > 0) {
                classes = real;
            } else {
                String[][] std = {{"1", "电影"}, {"2", "电视剧"}, {"4", "综艺"}};
                for (String[] s : std) {
                    JSONObject c = new JSONObject();
                    c.put("type_id", s[0]);
                    c.put("type_name", s[1]);
                    classes.put(c);
                }
            }
            result.put("class", classes);
            if (filter) {
                JSONObject filters = new JSONObject();
                JSONArray m = new JSONArray();
                m.put(filterBlock("areas", "地区", areaOptions()));
                m.put(filterBlock("types", "类型", typeOptions()));
                JSONArray t = new JSONArray();
                t.put(filterBlock("areas", "地区", areaOptions()));
                t.put(filterBlock("tvclasses", "电视剧分类", tvOptions()));
                t.put(filterBlock("types", "类型", typeOptions()));
                filters.put("1", m);
                filters.put("2", t);
                filters.put("4", m);
                result.put("filters", filters);
            }
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log("[4kApp] homeContent 错误: " + e.getMessage());
            return "{\"class\":[],\"filters\":{}}";
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            // 按 A 方案：/app/v1/filter 改 POST + JSON body
            // （dex 端点存在但 GET 404，倾向 method 不对；参数键沿用 classify/areas/tvclasses/page）
            JSONObject body = new JSONObject();
            body.put("classify", parseTid(tid));
            body.put("page", pg == null ? "1" : pg);
            if (extend != null) {
                for (String k : new String[]{"areas", "types", "tvclasses"}) {
                    if (extend.containsKey(k) && !TextUtils.isEmpty(extend.get(k))) body.put(k, extend.get(k));
                }
            }
            JSONObject resp = apiPost("/app/v1/filter", body);
            JSONArray list = extractList(resp);
            int page = parsePage(resp, pg);
            int limit = list.length();
            int pagecount = extractPagecount(resp, limit, page);
            JSONObject result = new JSONObject();
            result.put("list", list);
            result.put("page", page);
            result.put("pagecount", pagecount);
            result.put("limit", limit);
            result.put("total", pagecount > 0 ? limit * pagecount : limit);
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log("[4kApp] categoryContent 错误: " + e.getMessage());
            return "{\"list\":[],\"page\":" + (pg == null ? "1" : pg) + "}";
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            if (ids == null || ids.isEmpty()) return "{\"list\":[]}";
            String vodId = ids.get(0);
            JSONObject resp = apiGet("/app/v1/vods/" + safe(vodId), null);
            JSONArray list = new JSONArray();
            list.put(extractDetail(resp));
            JSONObject result = new JSONObject();
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log("[4kApp] detailContent 错误: " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        try {
            Map<String, String> q = new HashMap<>();
            q.put("q", key == null ? "" : key);
            q.put("page", pg == null ? "1" : pg);
            JSONObject resp = apiGet("/app/v1/search", q);
            JSONArray list = extractList(resp);
            JSONObject result = new JSONObject();
            result.put("list", list);
            result.put("page", parsePage(resp, pg));
            result.put("pagecount", 1);
            result.put("limit", list.length());
            result.put("total", list.length());
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log("[4kApp] searchContent 错误: " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    /**
     * 播放解析（双通道）：
     *  1) 签名 API /app/v1/play 取直链 → 成功 parse=0（带 UA/Referer 头）
     *  2) 失败/签名未闭环 → 退到网页版 FourKVM 的播放页嗅探（parse=1），保证能出画面
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            // 优先：签名 API 取直链
            String url = null;
            JSONObject resp = null;
            String vodId = id;
            int sep = id == null ? -1 : id.indexOf('$');
            if (sep > 0) vodId = id.substring(0, sep);
            if (!TextUtils.isEmpty(vodId) && !vodId.startsWith("http")) {
                // 坐实：/app/v1/play?vid=<vodId>&episode=<ep>
                //   参数键在 classes.dex 中同时出现 'vid' 与 'episode'（独立 query 参数），
                //   且存在 ', vod=' / ', episode=' 的拼接日志串，确认 vid/episode 为播放参数名。
                Map<String, String> q = new HashMap<>();
                q.put("vid", vodId);
                if (sep > 0) q.put("episode", id.substring(sep + 1));
                resp = apiGet("/app/v1/play", q);
                url = extractPlayUrl(resp);
            }
            JSONObject result = new JSONObject();
            if (!TextUtils.isEmpty(url)) {
                result.put("parse", 0);
                result.put("url", url);
                JSONObject header = new JSONObject();
                header.put("User-Agent", "okhttp/4.10.0");
                header.put("Referer", host + "/");
                result.put("header", header);
                return result.toString();
            }
            // 兜底：网页版播放页嗅探（复用 FourKVM 的 host 自适应与嗅探逻辑）
            SpiderDebug.log("[4kApp] play API 未出直链，退网页兜底: " + id);
            return webPlayerFallback(flag, id);
        } catch (Exception e) {
            SpiderDebug.log("[4kApp] playerContent 错误: " + e.getMessage());
            return webPlayerFallback(flag, id);
        }
    }

    /** 网页兜底：用 FourKVM（网页版）解析同一 vid，保证即使签名未闭环也能出画面（内部已 try/catch 兜底，不向外抛） */
    private String webPlayerFallback(String flag, String id) {
        try {
            String vodId = id;
            int sep = id == null ? -1 : id.indexOf('$');
            if (sep > 0) vodId = id.substring(0, sep);
            String page = WEB_HOST + "/play/" + safe(vodId);
            String html = OkHttp.string(page, baseHeaders);
            String m3u8 = findM3u8(html);
            JSONObject result = new JSONObject();
            JSONObject header = new JSONObject();
            header.put("User-Agent", "okhttp/4.10.0");
            header.put("Referer", page);
            if (!TextUtils.isEmpty(m3u8)) {
                result.put("parse", 0);
                result.put("url", m3u8);
            } else {
                result.put("parse", 1);
                result.put("url", page);
            }
            result.put("header", header);
            return result.toString();
        } catch (Exception e) {
            return "{\"parse\":1,\"url\":\"" + WEB_HOST + "/\"}";
        }
    }

    private static String findM3u8(String html) {
        if (TextUtils.isEmpty(html)) return "";
        int i = html.toLowerCase(Locale.US).indexOf(".m3u8");
        if (i < 0) return "";
        int quote = html.lastIndexOf('"', i);
        int end = html.indexOf('"', i + 5);
        if (end < 0) end = html.length();
        String u = html.substring(quote + 1, end);
        if (u.startsWith("//")) u = "https:" + u;
        else if (u.startsWith("/")) u = WEB_HOST + u;
        return u;
    }

    // ---------- 字段容错 / 解析辅助 ----------

    private String parseTid(String tid) {
        if (tid == null) return "1";
        int p = tid.indexOf('|');
        return p > 0 ? tid.substring(0, p) : tid;
    }

    private int parsePage(JSONObject j, String pg) throws JSONException {
        for (JSONObject cand : candidates(j)) {
            if (cand.has("page") && cand.get("page") instanceof Integer) return cand.optInt("page", 1);
        }
        try {
            return Integer.parseInt(pg == null ? "1" : pg);
        } catch (Exception e) {
            return 1;
        }
    }

    private int extractPagecount(JSONObject j, int limit, int page) throws JSONException {
        for (JSONObject cand : candidates(j)) {
            if (cand.has("pagecount") && cand.get("pagecount") instanceof Integer) return cand.optInt("pagecount", page);
            if (cand.has("total") && cand.has("limit") && cand.optInt("limit", 0) > 0) {
                int total = cand.optInt("total", 0);
                return Math.max(1, total / cand.optInt("limit"));
            }
        }
        return Math.max(page, 1);
    }

    private JSONArray extractList(JSONObject resp) throws JSONException {
        for (JSONObject cand : candidates(resp)) {
            if (cand.has("list") && cand.get("list") instanceof JSONArray) return normalize(cand.getJSONArray("list"));
            if (cand.has("data") && cand.get("data") instanceof JSONArray) return normalize(cand.getJSONArray("data"));
        }
        return new JSONArray();
    }

    private JSONObject extractDetail(JSONObject resp) throws JSONException {
        for (JSONObject cand : candidates(resp)) {
            if (cand.has("list") && cand.get("list") instanceof JSONArray && cand.getJSONArray("list").length() > 0) {
                return normalizeVod(cand.getJSONArray("list").optJSONObject(0));
            }
            if (cand.has("vod_id") || cand.has("id") || cand.has("vod_name")) return normalizeVod(cand);
        }
        JSONObject o = new JSONObject();
        o.put("vod_id", "");
        o.put("vod_name", "(解析失败)");
        return o;
    }

    private String extractPlayUrl(JSONObject resp) {
        if (resp == null) return "";
        for (JSONObject cand : candidates(resp)) {
            // 1) 直链 url
            String u = cand.optString("url", "");
            if (!TextUtils.isEmpty(u) && !"1".equals(u) && u.contains(".")) return absUrl(u);
            // 2) play_urls 数组: [ {url:...}, ... ] 或 [ "http...", ... ]
            String pu = extractFromUrlArray(cand.optJSONArray("play_urls"));
            if (!TextUtils.isEmpty(pu)) return pu;
            // 3) quality_urls 对象/数组: 取第一个未锁的清晰度直链
            JSONObject qm = cand.optJSONObject("quality_urls");
            if (qm == null && cand.has("quality_urls")) {
                // 可能是数组形态
                JSONArray qa = cand.optJSONArray("quality_urls");
                pu = extractFromUrlArray(qa);
                if (!TextUtils.isEmpty(pu)) return pu;
            } else if (qm != null) {
                // Android org.json 的 keySet() 是 Iterator<String>，没有 names()；用 keys() 的另一种写法
                for (java.util.Iterator<String> it = qm.keys(); it.hasNext(); ) {
                    JSONObject qo = qm.optJSONObject(it.next());
                    if (qo == null) continue;
                    if (qo.optBoolean("locked", false)) continue;
                    String qu = absUrl(qo.optString("url", ""));
                    if (!TextUtils.isEmpty(qu)) return qu;
                }
            }
            // 4) playUrl 单数对象 {url:...}
            JSONObject po = cand.optJSONObject("playUrl");
            if (po != null) {
                String qu = absUrl(po.optString("url", ""));
                if (!TextUtils.isEmpty(qu)) return qu;
            }
            // 5) 兼容 episodes/free_episodes 里的 url
            for (String ek : new String[]{"free_episodes", "episodes"}) {
                pu = extractFromUrlArray(cand.optJSONArray(ek));
                if (!TextUtils.isEmpty(pu)) return pu;
            }
        }
        return "";
    }

    /** 从 url 数组里取第一个可播直链（元素可为 字符串 / {url,locked} 对象） */
    private String extractFromUrlArray(JSONArray arr) {
        if (arr == null) return "";
        for (int i = 0; i < arr.length(); i++) {
            Object e = arr.opt(i);
            if (e instanceof String) {
                String s = absUrl((String) e);
                if (!TextUtils.isEmpty(s) && s.contains(".")) return s;
            } else if (e instanceof JSONObject) {
                JSONObject o = (JSONObject) e;
                if (o.optBoolean("locked", false)) continue;
                String qu = absUrl(firstOf(o, "url", "link", "playUrl", "play_urls"));
                if (!TextUtils.isEmpty(qu) && qu.contains(".")) return qu;
            }
        }
        return "";
    }

    /** resp + data + data.data 子对象作为候选（逐层下钻） */
    private List<JSONObject> candidates(JSONObject resp) {
        List<JSONObject> out = new ArrayList<>();
        if (resp == null) return out;
        out.add(resp);
        JSONObject d1 = resp.optJSONObject("data");
        if (d1 != null) {
            out.add(d1);
            JSONObject d2 = d1.optJSONObject("data");
            if (d2 != null) out.add(d2);
        }
        return out;
    }

    private JSONArray normalize(JSONArray arr) throws JSONException {
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.put(normalizeVod(o));
        }
        return out;
    }

    /** 容错读取 vod 字段：先 vod_*，再驼峰/短名 */
    private JSONObject normalizeVod(JSONObject o) throws JSONException {
        JSONObject r = new JSONObject();
        r.put("vod_id", firstOf(o, "vod_id", "id"));
        r.put("vod_name", firstOf(o, "vod_name", "name", "title"));
        r.put("vod_pic", absUrl(firstOf(o, "vod_pic", "pic", "cover", "image")));
        r.put("vod_remarks", firstOf(o, "vod_remarks", "remarks", "remark"));
        r.put("vod_year", firstOf(o, "vod_year", "year"));
        r.put("vod_area", firstOf(o, "vod_area", "area", "country"));
        r.put("vod_actor", firstOf(o, "vod_actor", "actor", "actors"));
        r.put("vod_director", firstOf(o, "vod_director", "director"));
        r.put("vod_content", firstOf(o, "vod_content", "content", "desc", "intro"));
        String from = firstOf(o, "vod_play_from");
        String play = firstOf(o, "vod_play_url");
        if (TextUtils.isEmpty(from) && o.has("episodes")) {
            try {
                JSONArray eps = o.getJSONArray("episodes");
                List<String> froms = new ArrayList<>();
                List<String> urls = new ArrayList<>();
                froms.add("4K影视");
                for (int i = 0; i < eps.length(); i++) {
                    JSONObject e = eps.optJSONObject(i);
                    if (e == null) continue;
                    String en = firstOf(e, "name", "title", "label");
                    String eu = absUrl(firstOf(e, "url", "link", "playUrl"));
                    if (!TextUtils.isEmpty(eu)) urls.add(en + "$" + eu);
                }
                if (!froms.isEmpty()) {
                    from = String.join("$$$", froms);
                    play = String.join("$$$", urls);
                }
            } catch (Exception ignore) {
            }
        }
        r.put("vod_play_from", from == null ? "" : from);
        r.put("vod_play_url", play == null ? "" : play);
        return r;
    }

    private String firstOf(JSONObject o, String... keys) {
        if (o == null) return "";
        for (String k : keys) {
            if (o.has(k) && !o.isNull(k)) {
                Object v = o.opt(k);
                if (v instanceof String && !TextUtils.isEmpty((String) v)) return (String) v;
                if (v instanceof JSONObject && ((JSONObject) v).has("name")) return ((JSONObject) v).optString("name");
                if (v != null && !(v instanceof JSONObject) && !(v instanceof JSONArray)) return String.valueOf(v);
            }
        }
        return "";
    }

    private String absUrl(String u) {
        if (TextUtils.isEmpty(u)) return "";
        if (u.startsWith("//")) return "https:" + u;
        if (u.startsWith("/")) return host + u;
        return u;
    }

    // ---------- 筛选器兜底 ----------

    private JSONArray extractConfigClasses(JSONObject resp) throws JSONException {
        JSONArray out = new JSONArray();
        for (JSONObject cand : candidates(resp)) {
            JSONArray arr = cand.optJSONArray("classes");
            if (arr == null) arr = cand.optJSONArray("list");
            if (arr == null) arr = cand.optJSONArray("categories");
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                JSONObject c = new JSONObject();
                c.put("type_id", firstOf(o, "type_id", "id", "classify"));
                c.put("type_name", firstOf(o, "type_name", "name", "title"));
                if (TextUtils.isEmpty(c.optString("type_id")) || TextUtils.isEmpty(c.optString("type_name"))) continue;
                out.put(c);
            }
            if (out.length() > 0) break;
        }
        return out;
    }

    private JSONArray kvOptions(String[][] kv) throws JSONException {
        JSONArray arr = new JSONArray();
        for (String[] o : kv) {
            try {
                JSONObject j = new JSONObject();
                j.put("n", o[0]);
                j.put("v", o[1]);
                arr.put(j);
            } catch (Exception ignore) {
            }
        }
        return arr;
    }

    private JSONObject filterBlock(String key, String name, JSONArray value) throws JSONException {
        try {
            JSONObject o = new JSONObject();
            o.put("key", key);
            o.put("name", name);
            o.put("value", value);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private JSONArray areaOptions() throws JSONException {
        return kvOptions(new String[][]{
                {"全部地区", ""}, {"中国", "7"}, {"美国", "5"}, {"日本", "11"},
                {"韩国", "12"}, {"英国", "30"}, {"中国香港", "14"}, {"中国台湾", "21"}
        });
    }

    private JSONArray tvOptions() throws JSONException {
        return kvOptions(new String[][]{
                {"全部类型", ""}, {"国产剧", "20"}, {"美剧", "21"}, {"韩剧", "22"}, {"日剧", "23"}, {"国漫", "26"}
        });
    }

    private JSONArray typeOptions() throws JSONException {
        return kvOptions(new String[][]{
                {"全部类型", ""}, {"剧情", "1"}, {"喜剧", "5"}, {"爱情", "6"},
                {"科幻", "14"}, {"动作", "10"}, {"动画", "11"}, {"纪录", "20"}
        });
    }

    // ---------- 加密 / 签名 / base64（算法逆向自 libgojni.so） ----------

    private static String safe(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    private static byte[] hmacSha256(String msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static String base64Of(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    /** 会话对称密钥：优先真实 X25519 公钥 base64（SESSION_KEY_B64），否则 SHA-256(密钥种子) 兜底 32B */
    private static byte[] sessionKey() {
        if (!TextUtils.isEmpty(SESSION_KEY_B64)) {
            byte[] k = Base64.decode(SESSION_KEY_B64, Base64.DEFAULT);
            if (k.length == 32) return k;
        }
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(SECRET_KEY.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            byte[] k = new byte[32];
            byte[] raw = SECRET_KEY.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(raw, 0, k, 0, Math.min(raw.length, 32));
            return k;
        }
    }

    @Override
    public boolean isVideoFormat(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.US);
        return u.contains(".m3u8") || u.contains(".mp4") || u.contains(".flv")
                || u.contains("/hls/") || u.contains(".ts") || u.contains("dash");
    }
}
