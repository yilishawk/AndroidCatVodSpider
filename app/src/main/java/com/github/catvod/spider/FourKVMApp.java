package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 4K影视 App 接口爬虫（完整可用版）
 *
 * ============================================================================
 * 一、数据源
 * ============================================================================
 *   host = https://ant.xhssapp.com        （真实 API 域名，抓包坐实）
 *   明文接口：/app/v1/config、/app/v1/version   → 真 GET，无需加密
 *   加密接口：其余全部 /app/v1/*           → POST 信封 + AES-GCM 加密体
 *
 * ============================================================================
 * 二、加密协议（全部实测打通，非推断）
 * ============================================================================
 *  1) 握手  POST /app/v1/exchange   Content-Type: application/json
 *       req  {"client_public_key":"<b64 32B X25519 公钥>",
 *             "signature":"<b64 HMAC-SHA256>",
 *             "timestamp":<10 位秒>}
 *       sig  = base64( HMAC-SHA256( "mymovie_encryption_secret_2025_v1",
 *                                   client_public_key + "|" + timestamp ) )
 *       resp {"code":200,"message":"密钥交换成功",
 *             "data":{"server_public_key":"<b64 32B>","session_id":"<32 hex>"}}
 *
 *      ★ 关键点：签名是「对称 HMAC + 硬编码密钥」，不是公钥签名。
 *        所以客户端可以自己造密钥对，**不需要**从 App 里抠私钥。
 *
 *  2) 会话密钥 = X25519(自己的 32B 私钥, 服务端 32B 公钥)，直接用裸 32 字节，
 *     **没有 KDF**（实测：加任何 KDF 都会解密失败）。
 *
 *  3) 请求/响应体二进制帧（Content-Type: application/octet-stream）：
 *         [ 37B 头 ][ 12B nonce ][ AES-GCM 密文 + 16B tag ]
 *      37B 头 = 4E 4D 01 00  +  0x20  +  session_id 的 32 个 ASCII 字节
 *      （注意：不是 45B，也不带 base64；头里的 session_id 就是握手返回的那个）
 *
 *  4) 真实请求头（无 X-Sign / 无 nonce / 无 cookie）：
 *      Accept: application/json,application/json
 *      User-Agent: 4K影视 Android App
 *      X-Platform: android
 *      X-Device-Id / X-Device-Name / X-App-Version
 *      X-NB-Method: GET | POST     ← 真 GET 语义包在 POST 信封里
 *      Content-Type: application/octet-stream
 *
 * ============================================================================
 * 三、真实路由与参数（逐条实测，2026-10 有效）
 * ============================================================================
 *  明文 GET  /app/v1/config                     → categories / tv_categories
 *  明文 GET  /app/v1/version
 *  加密 POST /app/v1/filter/options   {}        → areas/classifys/tags/tvclasses/types/years
 *  加密 POST /app/v1/filter  {"classify":"1","offset":"0","limit":"20",
 *                             "areas":"7","years":"1","types":"1","tags":"1","tvclasses":"20"}
 *                             ↑ 分页键是 offset/limit，**page 无效**；筛选键全是复数
 *  加密 POST /app/v1/movie/{id}       {}        → vod_info + play_urls(episodes[].pid)
 *  加密 POST /app/v1/search/{关键词}?offset=0&limit=20   {}   ← 关键词在**路径**里
 *  加密 POST /app/v1/play/{pid}  {"vid":"3244","nid":"1","ep":"1","q":"1080","user_id":"0"}
 *                             ↑ pid 来自 detail 的 play_urls[].episodes[].pid（不是 vid）
 *  加密 POST /app/v1/home / movie-page / tv-page / anime-page / richlist
 *
 *  说明：搜索路径写成 /app/v1/search 或带 ?keyword= 一律 404「接口不存在」，
 *        必须是 /app/v1/search/{关键词}。play 同理必须是 /app/v1/play/{pid}。
 *
 * ============================================================================
 * 四、播放
 * ============================================================================
 *  play 返回 quality_urls[]：
 *    - 非 VIP 档（如 1080p）locked=false，url 就是 oss.douyinbit.com 的 m3u8 直链
 *    - VIP 档（4K/蓝光）locked=true、url 是占位符 "1"，需要会员态才会下发
 *  本爬虫取「未锁定的最高码率」直链，parse=0 直接播。
 *
 * ============================================================================
 * 五、打包说明（单文件自包含）
 * ============================================================================
 *  本文件**不依赖任何同包自定义类**：X25519 已内联为私有静态内部类 X25519Impl
 *  （见文件末尾）。在线打包时**只上传 FourKVMApp.java 这一个文件即可**。
 *
 *  宿主提供的类（不用上传）：com.github.catvod.crawler.Spider / SpiderDebug、
 *                            com.github.catvod.net.OkHttp
 *  第三方依赖：okhttp3（CatVod 项目本身就带，打包环境一般都会提供）
 *
 *  为什么不能拆成两个文件：在线打包器若只收单文件，拆出去的 X25519 会报
 *  "找不到符号: 类 X25519"（实测 2 个错误）。
 *  为什么不用 KeyPairGenerator.getInstance("X25519")：那是 API 33+ 才有的，
 *  CatVod minSdk 16，所以必须自带实现。
 */
public class FourKVMApp extends Spider {

    // ---------------- 常量 ----------------

    private static final String DEFAULT_HOST = "https://ant.xhssapp.com";
    /** libgojni.so rodata 0x3bdd6 里的硬编码 HMAC 密钥（明文） */
    private static final String HMAC_SECRET = "mymovie_encryption_secret_2025_v1";
    /**
     * ⚠ 必须是纯 ASCII。
     *
     * App 原始 UA 是 "4K影视 Android App"（含中文「影」），但 okhttp 的
     * Headers.checkValue() 会拒绝任何非 ASCII 头值，直接抛
     *   IllegalArgumentException: Unexpected char 0x5f71 at 2 in User-Agent value
     * 异常被 catch 掉后表现为"所有请求都拿不到数据"（首页/分类全空）。
     *
     * 实测服务端**不校验 UA**：中文 UA / 纯 ASCII / okhttp 默认 / 完全不发 UA，
     * 返回的数据完全一致（都是 10392B）。所以这里用 ASCII 版本即可。
     */
    private static final String DEFAULT_UA = "4K Android App";
    private static final byte[] MAGIC = {0x4E, 0x4D, 0x01, 0x00};
    private static final int HEADER_LEN = 37;
    private static final int NONCE_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int PAGE_SIZE = 20;
    /**
     * 剧集快捷分类的 tid 前缀。
     * 例如 tid="tv20" → classify=2（电视剧）+ tvclasses=20（国产剧）。
     * 用前缀是为了和 classify 的 "1"/"2"/"3" 绝对不冲突。
     */
    private static final String TV_PREFIX = "tv";
    /** 要屏蔽的类型 / 标签关键词：筛选选项里出现就删掉，影片命中就不展示 */
    private static final String[] BLOCK_WORDS = {"情色", "色情", "成人", "情欲", "三级"};
    /** 会话有效期（服务端 session_expire_time 约 30 分钟，这里保守取 10 分钟主动重握手） */
    private static final long SESSION_TTL_MS = 10 * 60 * 1000L;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset ASCII = Charset.forName("US-ASCII");

    // ---------------- 状态 ----------------

    private String host = DEFAULT_HOST;
    private String appVersion = "1.1.3";
    private String deviceId;
    private String deviceName = "Redmi K30 5G";
    private String quality = "1080";
    private String userId = "0";
    private String userAgent = DEFAULT_UA;

    private final SecureRandom random = new SecureRandom();
    private Map<String, String> baseHeaders;

    /** 会话：X25519 私钥 + 协商出的 32B 会话密钥 + session_id */
    private byte[] privKey;
    private byte[] sessionKey;
    private String sessionId;
    private long sessionAt;

    public FourKVMApp() {
        // 稳定设备指纹（同一进程内固定即可，服务端只做标识）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(String.format(Locale.US, "%02x", (int) (Math.random() * 256)));
        deviceId = sb.toString();
    }

    // ================================================================
    // 初始化
    // ================================================================

    private boolean inited;
    private String pendingExtend;

    @Override
    public void init(Context context, String extend) {
        pendingExtend = extend;
        ensureInited();
        SpiderDebug.log("[4KApp] init host=" + host + " ver=" + appVersion
                + " dev=" + deviceId + " ua=" + userAgent);
    }

    /**
     * 懒初始化：某些宿主不调 init 就直接 homeContent，
     * 那样 baseHeaders 会是 null，导致 OkHttp.newCall(url, null) 之后
     * encHeaders() 里 new HashMap<>(null) 抛 NPE，表现为"没有视频数据"。
     */
    private void ensureInited() {
        if (inited) return;
        inited = true;
        applyExtend(pendingExtend);
        buildHeaders();
    }

    private void applyExtend(String extend) {
        if (TextUtils.isEmpty(extend)) return;
        try {
            JSONObject cfg = new JSONObject(extend);
            String h = cfg.optString("host", "");
            if (!TextUtils.isEmpty(h)) host = h.replaceAll("/+$", "");
            appVersion = cfg.optString("appVersion", appVersion);
            String d = cfg.optString("deviceId", "");
            if (!TextUtils.isEmpty(d)) deviceId = d;
            String dn = cfg.optString("deviceName", "");
            if (!TextUtils.isEmpty(dn)) deviceName = dn;
            quality = cfg.optString("q", quality);
            userId = cfg.optString("user_id", userId);
            String ua = cfg.optString("ua", "");
            if (!TextUtils.isEmpty(ua)) userAgent = ua;
        } catch (Exception e) {
            SpiderDebug.log("[4KApp] extend 解析失败: " + e.getMessage());
        }
    }

    private void buildHeaders() {
        baseHeaders = new HashMap<>();
        // ⚠ 所有头值都过 asciiOnly：okhttp 的 Headers.checkValue 拒绝非 ASCII，
        //   一旦有中文（例如 UA、或 extend 里传了中文 deviceName）就直接抛异常，
        //   表现为"所有请求都失败 / 没有视频数据"。
        baseHeaders.put("Accept", "application/json,application/json");
        baseHeaders.put("Accept-Charset", "UTF-8");
        baseHeaders.put("User-Agent", asciiOnly(userAgent));
        baseHeaders.put("X-Platform", "android");
        baseHeaders.put("X-App-Version", asciiOnly(appVersion));
        baseHeaders.put("X-Device-Id", asciiOnly(deviceId));
        baseHeaders.put("X-Device-Name", asciiOnly(deviceName));
    }

    /** 把非 ASCII / 控制字符替换成下划线，保证能安全放进 HTTP 头 */
    private static String asciiOnly(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= 0x20 && c < 0x7f ? c : '_');
        }
        return sb.toString();
    }

    // ================================================================
    // 会话 / 加解密
    // ================================================================

    private boolean sessionValid() {
        return sessionKey != null && sessionKey.length == 32
                && !TextUtils.isEmpty(sessionId)
                && System.currentTimeMillis() - sessionAt < SESSION_TTL_MS;
    }

    /** 确保会话可用；失败返回 false（不会抛异常） */
    private synchronized boolean ensureSession() {
        if (sessionValid()) return true;
        sessionKey = null;
        sessionId = null;
        try {
            // 1) 本地生成 X25519 密钥对（纯 Java 实现，minSdk 16 可用）
            privKey = new byte[32];
            random.nextBytes(privKey);
            byte[] pub = X25519Impl.publicKey(privKey);
            String pubB64 = b64(pub);

            // 2) 签名 = HMAC-SHA256(硬编码密钥, 公钥 + "|" + 秒级时间戳)
            long ts = System.currentTimeMillis() / 1000;
            String sig = b64(hmacSha256((pubB64 + "|" + ts).getBytes(UTF8)));

            // 3) 握手
            String body = "{\"client_public_key\":\"" + pubB64 + "\",\"signature\":\"" + sig
                    + "\",\"timestamp\":" + ts + "}";
            Map<String, String> h = new HashMap<>(baseHeaders);
            h.put("Content-Type", "application/json");
            String resp = postText(host + "/app/v1/exchange", body, h);
            if (TextUtils.isEmpty(resp)) {
                SpiderDebug.log("[4KApp] exchange 空响应");
                return false;
            }
            JSONObject j = new JSONObject(resp);
            if (j.optInt("code", 0) != 200) {
                SpiderDebug.log("[4KApp] exchange 失败: " + resp);
                return false;
            }
            JSONObject data = j.optJSONObject("data");
            if (data == null) data = j;
            String spkB64 = data.optString("server_public_key", "");
            String sid = data.optString("session_id", "");
            if (TextUtils.isEmpty(spkB64) || TextUtils.isEmpty(sid)) {
                SpiderDebug.log("[4KApp] exchange 缺字段: " + resp);
                return false;
            }

            // 4) 裸 X25519 协商（无 KDF）
            byte[] spk = Base64.decode(spkB64, Base64.DEFAULT);
            if (spk.length != 32) {
                SpiderDebug.log("[4KApp] 服务端公钥长度异常: " + spk.length);
                return false;
            }
            sessionKey = X25519Impl.scalarmult(privKey, spk);
            sessionId = sid;
            sessionAt = System.currentTimeMillis();
            SpiderDebug.log("[4KApp] 握手成功 sid=" + sid);
            return true;
        } catch (Throwable e) {
            SpiderDebug.log("[4KApp] 握手异常: " + e);
            return false;
        }
    }

    /** 组 37B 头：4E 4D 01 00 + 0x20 + session_id 的 32 个 ASCII 字节 */
    private byte[] sessionHeader() {
        byte[] h = new byte[HEADER_LEN];
        h[0] = MAGIC[0];
        h[1] = MAGIC[1];
        h[2] = MAGIC[2];
        h[3] = MAGIC[3];
        h[4] = 0x20;
        byte[] s = sessionId.getBytes(ASCII);
        System.arraycopy(s, 0, h, 5, Math.min(32, s.length));
        return h;
    }

    /** 明文 JSON → [37B 头][12B nonce][密文+tag] */
    private byte[] encryptBody(String plain) throws Exception {
        byte[] nonce = new byte[NONCE_LEN];
        random.nextBytes(nonce);
        Cipher c = gcm(Cipher.ENCRYPT_MODE, sessionKey, nonce);
        byte[] ct = c.doFinal(plain.getBytes(UTF8));
        ByteArrayOutputStream bo = new ByteArrayOutputStream(HEADER_LEN + NONCE_LEN + ct.length);
        bo.write(sessionHeader());
        bo.write(nonce);
        bo.write(ct);
        return bo.toByteArray();
    }

    /** [37B 头][12B nonce][密文+tag] → 明文 JSON；不是加密帧则返回 null */
    private String decryptBody(byte[] raw) {
        if (raw == null || raw.length < HEADER_LEN + NONCE_LEN + 16) return null;
        if (raw[0] != MAGIC[0] || raw[1] != MAGIC[1] || raw[2] != MAGIC[2] || raw[3] != MAGIC[3]) return null;
        try {
            byte[] nonce = new byte[NONCE_LEN];
            System.arraycopy(raw, HEADER_LEN, nonce, 0, NONCE_LEN);
            Cipher c = gcm(Cipher.DECRYPT_MODE, sessionKey, nonce);
            byte[] pt = c.doFinal(raw, HEADER_LEN + NONCE_LEN, raw.length - HEADER_LEN - NONCE_LEN);
            return new String(pt, UTF8);
        } catch (Throwable e) {
            SpiderDebug.log("[4KApp] 解密失败: " + e);
            return null;
        }
    }

    /** AES-GCM；API<19 无 GCMParameterSpec 时退回 IvParameterSpec */
    private static Cipher gcm(int mode, byte[] key, byte[] nonce) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec ks = new SecretKeySpec(key, "AES");
        try {
            c.init(mode, ks, new GCMParameterSpec(TAG_BITS, nonce));
        } catch (Throwable ignore) {
            c.init(mode, ks, new IvParameterSpec(nonce));
        }
        return c;
    }

    // ================================================================
    // 网络
    // ================================================================

    private static final MediaType OCTET = MediaType.get("application/octet-stream");
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private String postText(String url, String body, Map<String, String> headers) {
        try {
            Request.Builder rb = new Request.Builder().url(url)
                    .post(RequestBody.create(JSON_TYPE, body));
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) rb.addHeader(e.getKey(), e.getValue());
            Response r = OkHttp.newCall(rb.build());
            try {
                return r.body() == null ? "" : r.body().string();
            } finally {
                r.close();
            }
        } catch (Throwable e) {
            SpiderDebug.log("[4KApp] postText 失败 " + url + " : " + e);
            return "";
        }
    }

    /** 发二进制体，返回 (code, rawBytes)；rawBytes 可能为 null */
    private Object[] postBinary(String url, byte[] body, Map<String, String> headers) {
        try {
            Request.Builder rb = new Request.Builder().url(url)
                    .post(RequestBody.create(OCTET, body));
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) rb.addHeader(e.getKey(), e.getValue());
            Response r = OkHttp.newCall(rb.build());
            try {
                byte[] bytes = r.body() == null ? new byte[0] : r.body().bytes();
                return new Object[]{r.code(), bytes};
            } finally {
                r.close();
            }
        } catch (Throwable e) {
            SpiderDebug.log("[4KApp] postBinary 失败 " + url + " : " + e);
            return new Object[]{0, new byte[0]};
        }
    }

    private Map<String, String> encHeaders(String nbMethod) {
        Map<String, String> h = new HashMap<>(baseHeaders);
        h.put("Content-Type", "application/octet-stream");
        h.put("X-NB-Method", TextUtils.isEmpty(nbMethod) ? "GET" : nbMethod);
        return h;
    }

    /**
     * 加密接口调用。
     *
     * @param path      /app/v1/xxx 或 /app/v1/xxx?offset=0&limit=20
     * @param plainJson 明文 JSON（一般为 "{}"）
     * @param nbMethod  X-NB-Method 语义（GET / POST）
     * @return 解析后的 JSONObject；失败返回空 JSONObject（绝不抛异常）
     */
    private JSONObject apiEnc(String path, String plainJson, String nbMethod) {
        ensureInited();
        for (int attempt = 0; attempt < 2; attempt++) {
            if (!ensureSession()) return new JSONObject();
            try {
                byte[] body = encryptBody(TextUtils.isEmpty(plainJson) ? "{}" : plainJson);
                Object[] res = postBinary(host + path, body, encHeaders(nbMethod));
                int code = (Integer) res[0];
                byte[] raw = (byte[]) res[1];

                // 1) 加密帧 → 解密
                String plain = decryptBody(raw);
                if (plain != null) {
                    try {
                        return new JSONObject(plain);
                    } catch (Exception e) {
                        SpiderDebug.log("[4KApp] " + path + " 解密后非 JSON: " + head(plain, 200));
                        return new JSONObject();
                    }
                }

                // 2) 明文（错误信息等）
                String text = raw == null ? "" : new String(raw, UTF8);
                if (!TextUtils.isEmpty(text)) {
                    try {
                        JSONObject j = new JSONObject(text);
                        int c = j.optInt("code", 0);
                        // 会话失效 → 重握手再试一次
                        if ((code == 401 || code == 403 || c == 401 || c == 403) && attempt == 0) {
                            SpiderDebug.log("[4KApp] " + path + " 会话失效(" + code + "/" + c + ")，重新握手");
                            sessionKey = null;
                            continue;
                        }
                        if (c != 200) SpiderDebug.log("[4KApp] " + path + " 返回 " + code + " : " + head(text, 200));
                        return j;
                    } catch (Exception ignore) {
                    }
                }
                SpiderDebug.log("[4KApp] " + path + " 无有效响应 code=" + code);
                return new JSONObject();
            } catch (Throwable e) {
                SpiderDebug.log("[4KApp] " + path + " 异常: " + e);
                return new JSONObject();
            }
        }
        return new JSONObject();
    }

    /** 明文 GET（config / version 等免加密接口） */
    private JSONObject apiPlain(String path) {
        ensureInited();
        try {
            Response r = OkHttp.newCall(host + path, baseHeaders);
            try {
                String s = r.body() == null ? "" : r.body().string();
                if (TextUtils.isEmpty(s)) return new JSONObject();
                return new JSONObject(s);
            } finally {
                r.close();
            }
        } catch (Throwable e) {
            SpiderDebug.log("[4KApp] 明文 GET 失败 " + path + " : " + e);
            return new JSONObject();
        }
    }

    // ================================================================
    // CatVod 契约
    // ================================================================

    @Override
    public String homeContent(boolean filter) {
        JSONArray classes = new JSONArray();
        // 「国产剧」作为快捷入口放第一位（tid=tv20 → classify=2 + tvclasses=20）
        try {
            JSONObject c = new JSONObject();
            c.put("type_id", TV_PREFIX + "20");
            c.put("type_name", "国产剧");
            classes.put(c);
        } catch (Exception ignore) {
        }
        try {
            JSONObject cfg = apiPlain("/app/v1/config");
            JSONObject cdata = cfg.optJSONObject("data");
            if (cdata == null) cdata = cfg;
            JSONArray cats = cdata.optJSONArray("categories");
            if (cats != null) {
                for (int i = 0; i < cats.length(); i++) {
                    JSONObject o = cats.optJSONObject(i);
                    if (o == null) continue;
                    String id = o.optString("id", "");
                    String name = o.optString("name", "");
                    // id=0 是「首页」，CatVod 不需要
                    if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name) || "0".equals(id)) continue;
                    JSONObject c = new JSONObject();
                    c.put("type_id", id);
                    c.put("type_name", name);
                    classes.put(c);
                }
            }
        } catch (Throwable e) {
            SpiderDebug.log("[4KApp] homeContent 分类失败: " + e);
        }
        // 兜底分类（classes 至少已有「国产剧」，只有 config 完全失败时才补全）
        if (classes.length() <= 1) {
            String[][] std = {{"1", "电影"}, {"2", "电视剧"}, {"3", "动漫"}};
            for (String[] s : std) {
                try {
                    JSONObject c = new JSONObject();
                    c.put("type_id", s[0]);
                    c.put("type_name", s[1]);
                    classes.put(c);
                } catch (Exception ignore) {
                }
            }
        }

        JSONObject result = new JSONObject();
        try {
            result.put("class", classes);
            if (filter) result.put("filters", buildFilters());
        } catch (Exception ignore) {
        }
        return result.toString();
    }

    /**
     * 首页推荐列表。
     *
     * ⚠ 不实现这个方法，CatVod 首页就只有分类按钮、视频区是空的（"没有视频数据"）。
     *   数据源 /app/v1/home：carousel_items（4 条轮播）+ home_modules（9 个模块，每个含 vods[]）
     *   + ranking_items（12 条榜单）。这里取轮播 + 前几个模块，去重后最多 40 条。
     */
    @Override
    public String homeVideoContent() {
        JSONObject result = new JSONObject();
        JSONArray list = new JSONArray();
        try {
            JSONObject resp = apiEnc("/app/v1/home", "{}", "GET");
            JSONObject d = resp.optJSONObject("data");
            if (d == null) d = resp;
            Set<String> seen = new HashSet<>();

            // 1) 轮播（字段名和列表项不同：movie_id / movie_name / custom_bg）
            JSONArray carousel = d.optJSONArray("carousel_items");
            if (carousel != null) {
                for (int i = 0; i < carousel.length(); i++) {
                    JSONObject o = carousel.optJSONObject(i);
                    if (o == null) continue;
                    String id = o.optString("movie_id", "");
                    if (TextUtils.isEmpty(id) || seen.contains(id)) continue;
                    if (isBlockedVod(o)) continue;
                    seen.add(id);
                    JSONObject v = new JSONObject();
                    v.put("vod_id", id);
                    v.put("vod_name", o.optString("movie_name", ""));
                    v.put("vod_pic", o.optString("custom_bg", ""));
                    String rating = o.optString("rating", "");
                    String promo = o.optString("promo_text", "");
                    if (!TextUtils.isEmpty(promo)) v.put("vod_remarks", promo);
                    else if (!TextUtils.isEmpty(rating)) v.put("vod_remarks", "评分 " + rating);
                    list.put(v);
                }
            }

            // 2) 各模块的 vods（去重，封顶 40 条）
            JSONArray modules = d.optJSONArray("home_modules");
            if (modules != null) {
                for (int i = 0; i < modules.length() && list.length() < 40; i++) {
                    JSONObject m = modules.optJSONObject(i);
                    if (m == null) continue;
                    JSONArray vods = m.optJSONArray("vods");
                    if (vods == null) continue;
                    for (int k = 0; k < vods.length() && list.length() < 40; k++) {
                        JSONObject o = vods.optJSONObject(k);
                        if (o == null) continue;
                        String id = o.optString("id", "");
                        if (TextUtils.isEmpty(id) || seen.contains(id)) continue;
                        seen.add(id);
                        JSONObject v = toVod(o);
                        if (v != null) list.put(v);
                    }
                }
            }

            // 3) 榜单兜底（万一上面都是空）
            if (list.length() == 0) {
                JSONArray ranking = d.optJSONArray("ranking_items");
                if (ranking != null) {
                    for (int i = 0; i < ranking.length(); i++) {
                        JSONObject v = toVod(ranking.optJSONObject(i));
                        if (v != null) list.put(v);
                    }
                }
            }

            SpiderDebug.log("[4KApp] 首页推荐 " + list.length() + " 条");
        } catch (Throwable e) {
            SpiderDebug.log("[4KApp] homeVideoContent 错误: " + e);
        }
        try {
            result.put("list", list);
        } catch (Exception ignore) {
        }
        return result.toString();
    }

    /** 用 /app/v1/filter/options 的实时数据构造筛选器（键名必须与接口一致：全复数） */
    private JSONObject buildFilters() {
        JSONObject filters = new JSONObject();
        JSONObject resp = apiEnc("/app/v1/filter/options", "{}", "GET");
        JSONObject d = resp.optJSONObject("data");
        if (d == null) d = resp;

        JSONArray areas = kv(d.optJSONArray("areas"));
        JSONArray years = kv(d.optJSONArray("years"));
        JSONArray types = kv(d.optJSONArray("types"));
        JSONArray tags = kv(d.optJSONArray("tags"));
        JSONArray tvclasses = kv(d.optJSONArray("tvclasses"));

        try {
            // 电影
            JSONArray movie = new JSONArray();
            movie.put(block("areas", "地区", areas));
            movie.put(block("years", "年份", years));
            movie.put(block("types", "类型", types));
            movie.put(block("tags", "标签", tags));
            filters.put("1", movie);

            // 电视剧（多一个剧集分类）
            JSONArray tv = new JSONArray();
            tv.put(block("tvclasses", "剧集", tvclasses));
            tv.put(block("areas", "地区", areas));
            tv.put(block("years", "年份", years));
            tv.put(block("types", "类型", types));
            tv.put(block("tags", "标签", tags));
            filters.put("2", tv);

            // 动漫
            JSONArray anime = new JSONArray();
            anime.put(block("areas", "地区", areas));
            anime.put(block("years", "年份", years));
            anime.put(block("types", "类型", types));
            anime.put(block("tags", "标签", tags));
            filters.put("3", anime);

            // 国产剧快捷入口（tv20）：剧集已固定，不再给「剧集」筛选
            JSONArray cn = new JSONArray();
            cn.put(block("areas", "地区", areas));
            cn.put(block("years", "年份", years));
            cn.put(block("types", "类型", types));
            cn.put(block("tags", "标签", tags));
            filters.put(TV_PREFIX + "20", cn);
        } catch (Exception e) {
            SpiderDebug.log("[4KApp] 构造筛选器失败: " + e);
        }
        return filters;
    }

    /** [{"id":5,"name":"美国"}] → [{"n":"美国","v":"5"}]，并补一个「全部」；命中屏蔽词的直接丢掉 */
    private JSONArray kv(JSONArray src) {
        JSONArray out = new JSONArray();
        try {
            JSONObject all = new JSONObject();
            all.put("n", "全部");
            all.put("v", "");
            out.put(all);
            if (src == null) return out;
            for (int i = 0; i < src.length(); i++) {
                JSONObject o = src.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name", "");
                String id = o.optString("id", "");
                if (TextUtils.isEmpty(name) || TextUtils.isEmpty(id)) continue;
                if (isBlocked(name)) continue;
                JSONObject j = new JSONObject();
                j.put("n", name);
                j.put("v", id);
                out.put(j);
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    /** 命中屏蔽词？（模糊：用于标题、筛选项名称） */
    private static boolean isBlocked(String s) {
        if (TextUtils.isEmpty(s)) return false;
        for (String w : BLOCK_WORDS) {
            if (s.contains(w)) return true;
        }
        return false;
    }

    /** 数组里是否有元素**正好等于**屏蔽词（精确：用于 types / tags，避免《成人礼》被误伤） */
    private static boolean hasBlockedItem(JSONArray arr) {
        if (arr == null) return false;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            for (String w : BLOCK_WORDS) {
                if (w.equals(s)) return true;
            }
        }
        return false;
    }

    /**
     * 影片本身是否该屏蔽。
     * 标题用模糊匹配（标题里出现"情色"基本就是那类片），
     * 类型 / 标签用精确匹配（否则《成人礼》《三级警戒》这种正常片名会被误杀）。
     */
    private boolean isBlockedVod(JSONObject o) {
        if (o == null) return false;
        if (isBlocked(o.optString("title", ""))) return true;
        if (isBlocked(o.optString("vod_name", ""))) return true;
        if (isBlocked(o.optString("movie_name", ""))) return true;   // 轮播项用这个字段
        if (hasBlockedItem(o.optJSONArray("types"))) return true;
        if (hasBlockedItem(o.optJSONArray("tags"))) return true;
        if (hasBlockedItem(o.optJSONArray("tv_classes"))) return true;
        return false;
    }

    private JSONObject block(String key, String name, JSONArray value) throws Exception {
        JSONObject o = new JSONObject();
        o.put("key", key);
        o.put("name", name);
        o.put("value", value);
        return o;
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        int page = parseInt(pg, 1);
        int offset = (page - 1) * PAGE_SIZE;
        JSONObject result = new JSONObject();
        try {
            // tid 可能是 "1"/"2"/"3"（classify），也可能是 "tv20"（classify=2 + tvclasses=20）
            String classify = TextUtils.isEmpty(tid) ? "1" : tid;
            String fixedTvclass = null;
            if (classify.startsWith(TV_PREFIX)) {
                fixedTvclass = classify.substring(TV_PREFIX.length());
                classify = "2";
            }
            JSONObject body = new JSONObject();
            body.put("classify", classify);
            body.put("offset", String.valueOf(offset));
            body.put("limit", String.valueOf(PAGE_SIZE));
            if (!TextUtils.isEmpty(fixedTvclass)) body.put("tvclasses", fixedTvclass);
            // 筛选条件（键名与接口一致，空值不发）
            if (extend != null) {
                for (String k : new String[]{"areas", "years", "types", "tags", "tvclasses"}) {
                    String v = extend.get(k);
                    // tv 前缀的分类已经固定了剧集，不允许被 extend 覆盖
                    if ("tvclasses".equals(k) && !TextUtils.isEmpty(fixedTvclass)) continue;
                    if (!TextUtils.isEmpty(v)) body.put(k, v);
                }
            }
            JSONObject resp = apiEnc("/app/v1/filter", body.toString(), "GET");
            JSONObject d = resp.optJSONObject("data");
            if (d == null) d = resp;
            JSONArray results = d.optJSONArray("results");
            JSONArray list = normalizeList(results);
            int total = d.optInt("total", 0);
            int pagecount = total > 0 ? (total + PAGE_SIZE - 1) / PAGE_SIZE : page;

            result.put("list", list);
            result.put("page", page);
            result.put("pagecount", pagecount);
            result.put("limit", PAGE_SIZE);
            result.put("total", total);
        } catch (Throwable e) {
            SpiderDebug.log("[4KApp] categoryContent 错误: " + e);
            try {
                result.put("list", new JSONArray());
                result.put("page", page);
            } catch (Exception ignore) {
            }
        }
        return result.toString();
    }

    @Override
    public String detailContent(List<String> ids) {
        JSONObject result = new JSONObject();
        JSONArray list = new JSONArray();
        try {
            if (ids == null || ids.isEmpty()) {
                result.put("list", list);
                return result.toString();
            }
            String vodId = ids.get(0);
            JSONObject resp = apiEnc("/app/v1/movie/" + vodId, "{}", "GET");
            JSONObject d = resp.optJSONObject("data");
            if (d == null) d = resp;
            JSONObject info = d.optJSONObject("vod_info");
            if (info == null) {
                SpiderDebug.log("[4KApp] detail 无 vod_info: " + resp);
                result.put("list", list);
                return result.toString();
            }
            // 命中屏蔽词（情色/色情/成人…）的片子不返回
            if (isBlockedVod(info)) {
                SpiderDebug.log("[4KApp] 详情命中屏蔽词，已过滤: " + info.optString("title", "") + " / "
                        + join(info.optJSONArray("types"), ","));
                result.put("list", list);
                return result.toString();
            }
            // 注意：play_urls 在 vod_info 里，不在 data 顶层
            list.put(buildVod(info, info.optJSONArray("play_urls"), vodId));
            result.put("list", list);
        } catch (Throwable e) {
            SpiderDebug.log("[4KApp] detailContent 错误: " + e);
        }
        try {
            result.put("list", list);
        } catch (Exception ignore) {
        }
        return result.toString();
    }

    /** vod_info + play_urls → CatVod vod 对象 */
    private JSONObject buildVod(JSONObject info, JSONArray playUrls, String fallbackId) throws Exception {
        JSONObject o = new JSONObject();
        String vid = info.optString("id", fallbackId);
        o.put("vod_id", vid);
        o.put("vod_name", info.optString("title", ""));
        String pic = info.optString("poster", "");
        if (TextUtils.isEmpty(pic)) pic = info.optString("cover", "");
        o.put("vod_pic", pic);
        o.put("vod_year", info.optString("year", ""));
        o.put("vod_area", join(info.optJSONArray("areas"), ","));
        o.put("vod_actor", info.optString("actor", ""));
        o.put("vod_director", info.optString("director", ""));
        o.put("vod_content", info.optString("description", ""));
        o.put("vod_remarks", buildRemarks(info));
        o.put("type_name", join(info.optJSONArray("types"), ","));

        // 播放线路：play_urls[] 每条是 line，episodes[] 里 pid 才是 play 接口要的 id
        List<String> froms = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        if (playUrls != null) {
            for (int i = 0; i < playUrls.length(); i++) {
                JSONObject line = playUrls.optJSONObject(i);
                if (line == null) continue;
                JSONArray eps = line.optJSONArray("episodes");
                if (eps == null || eps.length() == 0) continue;
                String lineName = line.optString("line_name", "");
                if (TextUtils.isEmpty(lineName)) lineName = "线路" + (i + 1);
                List<String> items = new ArrayList<>();
                for (int k = 0; k < eps.length(); k++) {
                    JSONObject ep = eps.optJSONObject(k);
                    if (ep == null) continue;
                    String pid = ep.optString("pid", "");
                    if (TextUtils.isEmpty(pid)) continue;
                    int num = ep.optInt("num", k + 1);
                    String name = ep.optString("name", "");
                    if (TextUtils.isEmpty(name)) name = "第" + num + "集";
                    // id 编码：vid|pid|集数
                    items.add(name + "$" + vid + "|" + pid + "|" + num);
                }
                if (items.isEmpty()) continue;
                froms.add(lineName);
                groups.add(joinList(items, "#"));
            }
        }
        o.put("vod_play_from", joinList(froms, "$$$"));
        o.put("vod_play_url", joinList(groups, "$$$"));
        return o;
    }

    private String buildRemarks(JSONObject info) {
        String remark = info.optString("remark", "");
        if (!TextUtils.isEmpty(remark)) return remark;
        int updated = info.optInt("updated_episodes", 0);
        int total = info.optInt("total", 0);
        if (total > 0 && updated > 0) return (updated >= total ? "全" : "更新至") + total + "集";
        if (total > 0) return "共" + total + "集";
        String score = info.optString("rating", "");
        if (!TextUtils.isEmpty(score)) return "评分 " + score;
        return "";
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        int page = parseInt(pg, 1);
        int offset = (page - 1) * PAGE_SIZE;
        JSONObject result = new JSONObject();
        JSONArray list = new JSONArray();
        try {
            if (TextUtils.isEmpty(key)) {
                result.put("list", list);
                return result.toString();
            }
            // 关键词在路径里（URL 编码），分页走 query
            String path = "/app/v1/search/" + urlEncode(key) + "?offset=" + offset + "&limit=" + PAGE_SIZE;
            JSONObject resp = apiEnc(path, "{}", "GET");
            JSONObject d = resp.optJSONObject("data");
            if (d == null) d = resp;
            list = normalizeList(d.optJSONArray("results"));
            int total = d.optInt("total", list.length());
            int pagecount = total > 0 ? (total + PAGE_SIZE - 1) / PAGE_SIZE : 1;
            result.put("list", list);
            result.put("page", page);
            result.put("pagecount", pagecount);
            result.put("limit", PAGE_SIZE);
            result.put("total", total);
        } catch (Throwable e) {
            SpiderDebug.log("[4KApp] searchContent 错误: " + e);
            try {
                result.put("list", list);
            } catch (Exception ignore) {
            }
        }
        return result.toString();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        JSONObject result = new JSONObject();
        try {
            // id 形如 vid|pid|集数
            String vid = id;
            String pid = "";
            String ep = "1";
            if (!TextUtils.isEmpty(id) && id.contains("|")) {
                String[] parts = id.split("\\|");
                if (parts.length >= 1) vid = parts[0];
                if (parts.length >= 2) pid = parts[1];
                if (parts.length >= 3) ep = parts[2];
            }
            if (TextUtils.isEmpty(pid)) pid = vid;

            JSONObject body = new JSONObject();
            body.put("vid", vid);
            body.put("nid", "1");
            body.put("ep", ep);
            body.put("q", quality);
            body.put("user_id", userId);

            JSONObject resp = apiEnc("/app/v1/play/" + pid, body.toString(), "GET");
            JSONObject d = resp.optJSONObject("data");
            String url = pickPlayUrl(d);

            if (!TextUtils.isEmpty(url)) {
                result.put("parse", 0);
                result.put("url", url);
                // 直链直接推给壳子，不带 Referer（CDN 不需要，带了反而可能被某些播放器拦）
                JSONObject header = new JSONObject();
                header.put("User-Agent", asciiOnly(userAgent));
                result.put("header", header);
            } else {
                SpiderDebug.log("[4KApp] play 无可用直链(可能仅 VIP 档): " + resp);
                result.put("parse", 1);
                result.put("url", "");
                result.put("msg", "该集仅提供 VIP 清晰度");
            }
        } catch (Throwable e) {
            SpiderDebug.log("[4KApp] playerContent 错误: " + e);
            try {
                result.put("parse", 1);
                result.put("url", "");
            } catch (Exception ignore) {
            }
        }
        return result.toString();
    }

    /** 从 quality_urls 里挑「未锁定 + 最高码率」的直链 */
    private String pickPlayUrl(JSONObject data) {
        if (data == null) return "";
        JSONArray qs = data.optJSONArray("quality_urls");
        if (qs == null) return "";
        String best = "";
        int bestBitrate = -1;
        for (int i = 0; i < qs.length(); i++) {
            JSONObject q = qs.optJSONObject(i);
            if (q == null) continue;
            if (q.optBoolean("locked", false)) continue;
            String u = q.optString("url", "");
            if (TextUtils.isEmpty(u) || !u.startsWith("http")) continue;
            int bitrate = q.optInt("bitrate", 0);
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate;
                best = u;
            }
        }
        return best;
    }

    @Override
    public boolean isVideoFormat(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.US);
        return u.contains(".m3u8") || u.contains(".mp4") || u.contains(".flv")
                || u.contains("/hls/") || u.contains("dash");
    }

    // ================================================================
    // 解析辅助
    // ================================================================

    /** 列表项 → CatVod vod 精简对象（用于分类页 / 搜索页） */
    private JSONArray normalizeList(JSONArray arr) {
        JSONArray out = new JSONArray();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            JSONObject r = toVod(o);
            if (r != null) out.put(r);
        }
        return out;
    }

    /** 列表项 → CatVod vod 精简对象；id 为空或命中屏蔽词返回 null */
    private JSONObject toVod(JSONObject o) {
        if (o == null) return null;
        if (isBlockedVod(o)) return null;
        try {
            String id = o.optString("id", "");
            if (TextUtils.isEmpty(id)) return null;
            JSONObject r = new JSONObject();
            r.put("vod_id", id);
            r.put("vod_name", o.optString("title", ""));
            String pic = o.optString("poster", "");
            if (TextUtils.isEmpty(pic)) pic = o.optString("cover", "");
            r.put("vod_pic", pic);
            r.put("vod_remarks", listRemarks(o));
            r.put("vod_year", o.optString("year", ""));
            r.put("vod_area", join(o.optJSONArray("areas"), ","));
            String rating = o.optString("rating", "");
            if (!TextUtils.isEmpty(rating)) r.put("vod_score", rating);
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private String listRemarks(JSONObject o) {
        int updated = o.optInt("updated_episodes", 0);
        int total = o.optInt("vod_total", 0);
        if (total > 0 && updated > 0) return (updated >= total ? "全" : "更新至") + total + "集";
        if (total > 0) return "共" + total + "集";
        String rating = o.optString("rating", "");
        if (!TextUtils.isEmpty(rating)) return rating;
        return "";
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String join(JSONArray arr, String sep) {
        if (arr == null || arr.length() == 0) return "";
        List<String> l = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (!TextUtils.isEmpty(s)) l.add(s);
        }
        return joinList(l, sep);
    }

    private static String joinList(List<String> l, String sep) {
        if (l == null || l.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    private static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    private static byte[] hmacSha256(byte[] msg) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HMAC_SECRET.getBytes(UTF8), "HmacSHA256"));
        return mac.doFinal(msg);
    }

    private static String head(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    // ================================================================
    // 内联的 X25519（Curve25519 标量乘法）
    // ================================================================
    /**
     * 为什么内联在这里，而不是单独一个 X25519.java 文件：
     *   在线打包（spider jar/dex）很多时候只收一个 .java，多文件会编译失败
     *   （找不到符号: 类 X25519）。内联成静态内部类后，本文件**自包含**，
     *   上传单个 FourKVMApp.java 即可打包。
     *
     * 为什么不用 KeyPairGenerator.getInstance("X25519")：
     *   那是 API 33+ 才有的；CatVod minSdk 16，得自己实现。
     *   本实现只用 long 运算，无任何依赖，minSdk 16 可用。
     *
     * 移植自 TweetNaCl 的 crypto_scalarmult，已用 RFC 7748 §6.1 官方测试向量验证：
     *   Alice priv 77076d0a… → pub 8520f009…          ✔
     *   Bob   priv 5dab087e… → pub de9edb7d…          ✔
     *   双向共享密钥 = 4a5d9d5b…1e161742               ✔
     * 并用它走完了真实服务端握手 + 6 个接口的加解密。
     *
     * 注意：项目里另有一份等价的独立文件 X25519.java（供其它 spider 复用），
     *       本类不依赖它；两者内容一致，改动时请同步。
     */
    private static final class X25519Impl {

        /** 基点 u=9 */
        private static final byte[] BASEPOINT = new byte[32];

        static {
            BASEPOINT[0] = 9;
        }

        private static final long[] _121665 = {0xDB41, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

        private X25519Impl() {
        }

        /** gf 元素归一化（16 个 16-bit limb，带进位） */
        private static void car25519(long[] o) {
            long c = 1;
            for (int i = 0; i < 16; i++) {
                long v = o[i] + c + 65535;
                c = v >> 16;
                o[i] = v - (c << 16);
            }
            o[0] += c - 1 + 37 * (c - 1);
        }

        /** 常数时间条件交换：b==1 时交换 p、q */
        private static void sel25519(long[] p, long[] q, int b) {
            long c = ~(b - 1);
            for (int i = 0; i < 16; i++) {
                long t = c & (p[i] ^ q[i]);
                p[i] ^= t;
                q[i] ^= t;
            }
        }

        private static void pack25519(byte[] o, long[] n) {
            long[] m = new long[16];
            long[] t = new long[16];
            System.arraycopy(n, 0, t, 0, 16);
            car25519(t);
            car25519(t);
            car25519(t);
            for (int j = 0; j < 2; j++) {
                m[0] = t[0] - 0xffed;
                for (int i = 1; i < 15; i++) {
                    m[i] = t[i] - 0xffff - ((m[i - 1] >> 16) & 1);
                    m[i - 1] &= 0xffff;
                }
                m[15] = t[15] - 0x7fff - ((m[14] >> 16) & 1);
                int b = (int) ((m[15] >> 16) & 1);
                m[14] &= 0xffff;
                sel25519(t, m, 1 - b);
            }
            for (int i = 0; i < 16; i++) {
                o[2 * i] = (byte) (t[i] & 0xff);
                o[2 * i + 1] = (byte) ((t[i] >> 8) & 0xff);
            }
        }

        private static void unpack25519(long[] o, byte[] n) {
            for (int i = 0; i < 16; i++) {
                o[i] = (n[2 * i] & 0xff) + ((long) (n[2 * i + 1] & 0xff) << 8);
            }
            o[15] &= 0x7fff;
        }

        private static void A(long[] o, long[] a, long[] b) {
            for (int i = 0; i < 16; i++) o[i] = a[i] + b[i];
        }

        private static void Z(long[] o, long[] a, long[] b) {
            for (int i = 0; i < 16; i++) o[i] = a[i] - b[i];
        }

        private static void M(long[] o, long[] a, long[] b) {
            long[] t = new long[31];
            for (int i = 0; i < 16; i++) {
                for (int j = 0; j < 16; j++) {
                    t[i + j] += a[i] * b[j];
                }
            }
            for (int i = 0; i < 15; i++) t[i] += 38 * t[i + 16];
            System.arraycopy(t, 0, o, 0, 16);
            car25519(o);
            car25519(o);
        }

        private static void S(long[] o, long[] a) {
            M(o, a, a);
        }

        private static void inv25519(long[] o, long[] i) {
            long[] c = new long[16];
            System.arraycopy(i, 0, c, 0, 16);
            for (int a = 253; a >= 0; a--) {
                S(c, c);
                if (a != 2 && a != 4) M(c, c, i);
            }
            System.arraycopy(c, 0, o, 0, 16);
        }

        /**
         * X25519 标量乘法：q = n * p
         *
         * @param n 32 字节标量（私钥，内部会 clamp）
         * @param p 32 字节 u 坐标（对方公钥）
         * @return 32 字节结果（共享密钥）
         */
        static byte[] scalarmult(byte[] n, byte[] p) {
            byte[] q = new byte[32];
            long[] x = new long[16];
            long[] a = new long[16], b = new long[16], c = new long[16];
            long[] d = new long[16], e = new long[16], f = new long[16];

            // 标量裁剪：clamp
            System.arraycopy(n, 0, q, 0, 32);
            q[31] = (byte) ((q[31] & 127) | 64);
            q[0] &= (byte) 248;

            unpack25519(x, p);
            for (int i = 0; i < 16; i++) {
                b[i] = x[i];
                d[i] = 0;
                a[i] = 0;
                c[i] = 0;
            }
            a[0] = 1;
            d[0] = 1;

            for (int i = 254; i >= 0; --i) {
                int r = (q[i >>> 3] >>> (i & 7)) & 1;
                sel25519(a, b, r);
                sel25519(c, d, r);
                A(e, a, c);
                Z(a, a, c);
                A(c, b, d);
                Z(b, b, d);
                S(d, e);
                S(f, a);
                M(a, c, a);
                M(c, b, e);
                A(e, a, c);
                Z(a, a, c);
                S(b, a);
                Z(c, d, f);
                M(a, c, _121665);
                A(a, a, d);
                M(c, c, a);
                M(a, d, f);
                M(d, b, x);
                S(b, e);
                sel25519(a, b, r);
                sel25519(c, d, r);
            }

            inv25519(c, c);
            M(a, a, c);
            pack25519(q, a);
            return q;
        }

        /** 由 32 字节私钥推 32 字节公钥 */
        static byte[] publicKey(byte[] priv) {
            return scalarmult(priv, BASEPOINT);
        }
    }
}

