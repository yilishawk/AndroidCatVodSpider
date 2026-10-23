package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 瓜子影视（api.anctjd.com）
 *
 * 加密链路：
 *   请求体 = AES-CBC(业务参数 JSON)  -> request_key（固定 key/iv）
 *   请求 keys = RSA公钥( {"iv","key"} )
 *   响应     = RSA私钥( keys ) 得 AES key/iv -> AES-CBC 解 response_key
 *   签名     = MD5( token_id,token,phone_type,request_key,app_id,time,keys 逗号拼接 + 盐 )
 */
public class GuaZi extends Spider {

    private static final String BASE = "https://api.anctjd.com";
    private static final String AES_KEY = "RhgUpnAK2agqxPjX";
    private static final String AES_IV = "sljblQdVvGrVeHKr";
    private static final String SALT = "*&zvdvdvddbfikkkumtmdwqppp?|4Y!s!2br";
    private static final String PKG = "com.l247ce5e1b.p8b3173c28.xef737a9da20261022";
    private static final String TOKEN = "25c6b32e6d6fcb7953a4c4f3e55aac92.abfa0d3364328876cea5957ec741f7e17fe1529"
            + "7b6f47b14e291170d02bfbb2a5bfa29003988eb364deecca57e3e30e68110483515e6b8d"
            + "c14c8039e0808647300718ba9c0a0b041a5e4a5fd5e775e28b28616c6300a0c92c746f2f"
            + "59d33ac49bc703976535e600b26494d78d4bd4526cf1d5590385f5f7ef6e01d1850974aa"
            + "220eb5178c89e61c24411af9b9a19435e.380ff1868615686ec8a7236bfa03f837b5cb4a"
            + "28f591aee22905de4e94fbe8e4";
    private static final String PUB_HEX = "30819f300d06092a864886f70d010101050003818d0030818902818100d4339fbfcbcb0f"
            + "b1691dd7f4504bae17db9f44530c455c51391e503ae4caabc673ecd09aa8491a23483cb9"
            + "421c2f44e95a0fa4f04501ca318d8e019e929d079426c0a14c414847da94930aecdff315"
            + "50cc63b2fe894ba39efe3b9c9722464e05660e1079e4469f5ec0f44906158ff4175ecc51"
            + "e9ec11e44da42f9db20000f8c90203010001";
    private static final String PRIV_B64 = "MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGAe6hKrWLi1zQmjTT1ozbE4QdF"
            + "eJGNxubxld6GrFGximxfMsMB6BpJhpcTouAqywAFppiKetUBBbXwYsYU1wNr648XVmPmCMCy"
            + "4rY8vdliFnbMUj086DU6Z+/oXBdWU3/b1G0DN3E9wULRSwcKZT3wj/cCI1vsCm3gj2R5SqkA"
            + "9Y0CAwEAAQKBgAJH+4CxV0/zBVcLiBCHvSANm0l7HetybTh/j2p0Y1sTXro4ALwAaCTUeqdB"
            + "jWiLSo9lNwDHFyq8zX90+gNxa7c5EqcWV9FmlVXr8VhfBzcZo1nXeNdXFT7tQ2yah/odtdcx"
            + "+vRMSGJd1t/5k5bDd9wAvYdIDblMAg+wiKKZ5KcdAkEA1cCakEN4NexkF5tHPRrR6XOY/XHf"
            + "kqXxEhMqmNbB9U34saTJnLWIHC8IXys6Qmzz30TtzCjuOqKRRy+FMM4TdwJBAJQZFPjsGC+R"
            + "qcG5UvVMiMPhnwe/bXEehShK86yJK/g/UiKrO87h3aEu5gcJqBygTq3BBBoH2md3pr/W+hUM"
            + "WBsCQQChfhTIrdDinKi6lRxrdBnn0Ohjg2cwuqK5zzU9p/N+S9x7Ck8wUI53DKm8jUJE8WAG"
            + "7WLj/oCOWEh+ic6NIwTdAkEAj0X8nhx6AXsgCYRql1klbqtVmL8+95KZK7PnLWG/IfjQUy3p"
            + "PGoSaZ7fdquG8bq8oyf5+dzjE/oTXcByS+6XRQJAP/5ciy1bL3NhUhsaOVy55MHXnPjdcTX0"
            + "FaLi+ybXZIfIQ2P4rb19mVq1feMbCXhz+L1rG8oat5lYKfpe8k83ZA==";

    private static final int PAGE_SIZE = 30;

    /** 国产剧置顶 */
    private static final String[][] CATES = {
            {"4", "国产剧"},
            {"3", "电影"},
            {"9", "海外剧"},
            {"5", "动漫"},
            {"6", "综艺"},
            {"2", "短剧"},
            {"15", "漫剧"},
            {"10", "儿童"},
    };

    /** 成人内容过滤 */
    private static final String[] ADULT_WORDS = {
            "同性", "耽美", "情色", "色情", "三级", "成人片", "福利片",
            "裸", "淫", "嫖", "艳照", "春宫", "肉蒲团", "金瓶梅"
    };
    private static final Set<String> ADULT_TAGS = new HashSet<>(Arrays.asList(
            "同性", "耽美", "情色", "三级片"
    ));

    @Override
    public void init(Context context) {
        super.init(context);
    }

    // ==================== 网络 ====================

    private Map<String, String> header() {
        Map<String, String> h = new HashMap<>();
        h.put("code", "GZ0313");
        h.put("lang", "zh_cn");
        h.put("Version", "2610013");
        h.put("Ver", "3.0.6.2");
        h.put("api-ver", "3.0.6.2");
        h.put("PackageName", PKG);
        h.put("Referer", BASE);
        h.put("User-Agent", "okhttp/3.12.0");
        h.put("Content-Type", "application/x-www-form-urlencoded");
        return h;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        int n = s.length() / 2;
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    private static String md5Upper(String s) throws Exception {
        return hex(MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"))).toUpperCase();
    }

    private static String aesEnc(String plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(AES_KEY.getBytes("UTF-8"), "AES"),
                new IvParameterSpec(AES_IV.getBytes("UTF-8")));
        return hex(c.doFinal(plain.getBytes("UTF-8"))).toUpperCase();
    }

    private static String aesDec(String hexCt, String key, String iv) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(key.getBytes("UTF-8"), "AES"),
                new IvParameterSpec(iv.getBytes("UTF-8")));
        return new String(c.doFinal(unhex(hexCt)), "UTF-8");
    }

    private static String rsaEnc(String plain) throws Exception {
        PublicKey pk = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(unhex(PUB_HEX)));
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, pk);
        return Base64.encodeToString(c.doFinal(plain.getBytes("UTF-8")), Base64.NO_WRAP);
    }

    private static String rsaDec(String b64Ct) throws Exception {
        byte[] raw = Base64.decode(b64Ct, Base64.NO_WRAP);
        PrivateKey pk = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(PRIV_B64, Base64.NO_WRAP)));
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.DECRYPT_MODE, pk);
        return new String(c.doFinal(raw), "UTF-8");
    }

    /** 统一请求：加密入参 -> 签名 -> POST -> 解响应 */
    private Object api(String path, Map<String, String> biz) {
        try {
            JSONObject bizJson = new JSONObject();
            if (biz != null) {
                for (Map.Entry<String, String> e : biz.entrySet()) bizJson.put(e.getKey(), e.getValue());
            }
            String requestKey = aesEnc(bizJson.toString());

            JSONObject keyInfo = new JSONObject();
            keyInfo.put("iv", AES_IV);
            keyInfo.put("key", AES_KEY);
            String keys = rsaEnc(keyInfo.toString());

            LinkedHashMap<String, String> kv = new LinkedHashMap<>();
            kv.put("token_id", "");
            kv.put("token", TOKEN);
            kv.put("phone_type", "1");
            kv.put("request_key", requestKey);
            kv.put("app_id", "1");
            kv.put("time", String.valueOf(System.currentTimeMillis() / 1000));
            kv.put("keys", keys);

            StringBuilder sign = new StringBuilder();
            for (Map.Entry<String, String> e : kv.entrySet()) {
                if (sign.length() > 0) sign.append(',');
                sign.append(e.getKey()).append('=').append(e.getValue());
            }
            kv.put("signature", md5Upper(sign.toString() + SALT));

            OkResult r = OkHttp.post(BASE + path, kv, header());
            String body = r.getBody();
            if (body == null || body.trim().isEmpty()) {
                SpiderDebug.log("[瓜子] " + path + " 空响应 http=" + r.getCode());
                return null;
            }
            JSONObject js = new JSONObject(body);
            int code = js.optInt("code");
            if (code != 200) {
                SpiderDebug.log("[瓜子] " + path + " code=" + code + " msg=" + js.optString("msg"));
                return null;
            }
            JSONObject data = js.optJSONObject("data");
            if (data == null) return null;

            JSONObject info = new JSONObject(rsaDec(data.optString("keys")));
            String plain = aesDec(data.optString("response_key"),
                    info.getString("key"), info.getString("iv")).trim();
            if (plain.startsWith("{")) return new JSONObject(plain);
            if (plain.startsWith("[")) return new JSONArray(plain);
            return null;
        } catch (Exception e) {
            SpiderDebug.log("[瓜子] " + path + " 异常 " + e);
            return null;
        }
    }

    // ==================== 首页 ====================

    @Override
    public String homeContent(boolean filter) throws Exception {
        JSONArray classes = new JSONArray();
        for (String[] c : CATES) {
            JSONObject o = new JSONObject();
            o.put("type_id", c[0]);
            o.put("type_name", c[1]);
            classes.put(o);
        }

        JSONArray list = new JSONArray();
        Map<String, String> p = new HashMap<>();
        p.put("page", "1");
        p.put("sort", "d_addtime");
        Object d = api("/App/IndexList/indexList", p);
        if (d instanceof JSONObject) {
            JSONArray arr = ((JSONObject) d).optJSONArray("list");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    if (it == null || isAdult(it)) continue;
                    list.put(toVodJson(it));
                }
            }
        }

        StringBuilder sb = new StringBuilder("{\"class\":");
        sb.append(classes.toString());
        sb.append(",\"filters\":").append(buildFilters().toString());
        sb.append(",\"list\":").append(list.toString());
        sb.append("}");
        SpiderDebug.log("[瓜子] " + "homeContent list=" + list.length());
        return sb.toString();
    }

    private JSONObject buildFilters() {
        JSONObject out = new JSONObject();
        Map<String, String> p = new HashMap<>();
        p.put("pid", "4");
        Object d = api("/App/IndexList/indexScreen", p);
        if (!(d instanceof JSONObject)) return out;
        JSONObject src = (JSONObject) d;
        try {
            JSONArray dims = new JSONArray();
            dims.put(dim(src, "area", "地区"));
            dims.put(dim(src, "year", "年份"));
            dims.put(dim(src, "column", "类型"));
            dims.put(dim(src, "sort", "排序"));
            for (String[] c : CATES) out.put(c[0], new JSONArray(dims.toString()));
        } catch (Exception ignored) {
        }
        return out;
    }

    private JSONObject dim(JSONObject src, String key, String name) {
        JSONObject o = new JSONObject();
        JSONArray vals = new JSONArray();
        try {
            o.put("key", key);
            o.put("name", name);
            JSONArray raw = src.optJSONArray(key);
            if (raw != null) {
                for (int i = 0; i < raw.length(); i++) {
                    JSONObject r = raw.optJSONObject(i);
                    if (r == null) continue;
                    JSONObject v = new JSONObject();
                    v.put("n", r.optString("name"));
                    v.put("v", r.optString("value"));
                    vals.put(v);
                }
            }
            o.put("value", vals);
        } catch (Exception ignored) {
        }
        return o;
    }

    // ==================== 分类 ====================

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = 1;
        try {
            page = Integer.parseInt(pg);
        } catch (Exception ignored) {
        }
        Map<String, String> p = new HashMap<>();
        p.put("pid", tid == null ? "" : tid);
        p.put("page", String.valueOf(page));
        p.put("area", pick(extend, "area", "0"));
        p.put("year", pick(extend, "year", "0"));
        p.put("column", pick(extend, "column", "0"));
        p.put("sort", pick(extend, "sort", "d_id"));

        List<Vod> vods = new ArrayList<>();
        int total = 0;
        int rawSize = 0;
        Object d = api("/App/IndexList/indexList", p);
        if (d instanceof JSONObject) {
            JSONObject o = (JSONObject) d;
            total = o.optInt("total");
            JSONArray arr = o.optJSONArray("list");
            if (arr != null) {
                rawSize = arr.length();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    if (it == null || isAdult(it)) continue;
                    vods.add(toVod(it));
                }
            }
        }
        boolean more = rawSize >= PAGE_SIZE;
        SpiderDebug.log("[瓜子] " + "category tid=" + tid + " pg=" + page + " total=" + total + " raw=" + rawSize + " out=" + vods.size());
        return Result.get().vod(vods).page(page, more ? page + 1 : page, PAGE_SIZE, total).string();
    }

    // ==================== 详情 ====================

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0);
        Vod vod = new Vod();
        vod.setVodId(id);

        Map<String, String> p1 = new HashMap<>();
        p1.put("vod_id", id);
        Object infoObj = api("/App/IndexPlay/playInfo", p1);
        if (infoObj instanceof JSONObject) {
            JSONObject vi = ((JSONObject) infoObj).optJSONObject("vodInfo");
            if (vi != null) {
                vod.setVodName(vi.optString("vod_name"));
                vod.setVodPic(vi.optString("vod_pic"));
                vod.setVodActor(vi.optString("vod_actor"));
                vod.setVodDirector(vi.optString("vod_director"));
                vod.setVodContent(vi.optString("vod_use_content"));
                vod.setVodYear(vi.optString("vod_year"));
                vod.setVodArea(vi.optString("vod_area"));
                vod.setVodRemarks(vi.optString("new_continue"));
            }
        }

        Map<String, String> p2 = new HashMap<>();
        p2.put("vod_d_id", id);
        Object vurlObj = api("/App/Resource/Vurl/show", p2);
        String playUrl = "";
        if (vurlObj instanceof JSONObject) {
            JSONArray arr = ((JSONObject) vurlObj).optJSONArray("list");
            if (arr != null && arr.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject ep = arr.optJSONObject(i);
                    if (ep == null) continue;
                    String title = ep.optString("title");
                    String vid = ep.optString("id");
                    if (title.isEmpty() || vid.isEmpty()) continue;
                    if (sb.length() > 0) sb.append('#');
                    sb.append(title).append('$').append(id).append('|').append(vid);
                }
                playUrl = sb.toString();
            }
        }
        if (!playUrl.isEmpty()) {
            vod.setVodPlayFrom("瓜子线路");
            vod.setVodPlayUrl(playUrl);
        }
        SpiderDebug.log("[瓜子] " + "detail id=" + id + " playLen=" + playUrl.length());
        return Result.get().vod(vod).string();
    }

    // ==================== 搜索 ====================

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        int page = 1;
        try {
            page = Integer.parseInt(pg);
        } catch (Exception ignored) {
        }
        Map<String, String> p = new HashMap<>();
        p.put("keywords", key);
        p.put("page", String.valueOf(page));

        List<Vod> vods = new ArrayList<>();
        Object d = api("/App/IndexList/indexList", p);
        if (d instanceof JSONObject) {
            JSONArray arr = ((JSONObject) d).optJSONArray("list");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    if (it == null || isAdult(it)) continue;
                    vods.add(toVod(it));
                }
            }
        }
        SpiderDebug.log("[瓜子] " + "search key=" + key + " pg=" + page + " out=" + vods.size());
        return Result.get().vod(vods).string();
    }

    // ==================== 播放 ====================

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String[] p = id.split("\\|");
        if (p.length < 2) return Result.get().url("").string();
        Map<String, String> q = new HashMap<>();
        q.put("vod_d_id", p[0]);
        q.put("vurl_id", p[1]);
        q.put("domain_type", "8");
        q.put("resolution", "1080");
        q.put("type", "play");
        Object d = api("/App/Resource/VurlDetail/showOne", q);
        String url = "";
        if (d instanceof JSONObject) url = ((JSONObject) d).optString("url");
        SpiderDebug.log("[瓜子] " + "player " + id + " -> " + url);
        return Result.get().url(url).string();
    }

    // ==================== 工具 ====================

    private Vod toVod(JSONObject o) {
        Vod v = new Vod();
        v.setVodId(o.optString("vod_id"));
        v.setVodName(o.optString("vod_name"));
        v.setVodPic(o.optString("vod_pic"));
        v.setVodRemarks(o.optString("new_continue"));
        v.setVodYear(o.optString("vod_year"));
        v.setVodArea(o.optString("vod_area"));
        return v;
    }

    private JSONObject toVodJson(JSONObject o) {
        JSONObject j = new JSONObject();
        try {
            j.put("vod_id", o.optString("vod_id"));
            j.put("vod_name", o.optString("vod_name"));
            j.put("vod_pic", o.optString("vod_pic"));
            j.put("vod_remarks", o.optString("new_continue"));
            j.put("vod_year", o.optString("vod_year"));
            j.put("vod_area", o.optString("vod_area"));
        } catch (Exception ignored) {
        }
        return j;
    }

    private static String pick(HashMap<String, String> ext, String key, String def) {
        if (ext == null) return def;
        String v = ext.get(key);
        return (v == null || v.trim().isEmpty()) ? def : v;
    }

    private static boolean isAdult(JSONObject o) {
        String name = o.optString("vod_name");
        for (String w : ADULT_WORDS) if (name.contains(w)) return true;
        JSONArray tags = o.optJSONArray("tags");
        if (tags != null) {
            for (int i = 0; i < tags.length(); i++) {
                if (ADULT_TAGS.contains(tags.optString(i))) return true;
            }
        }
        return false;
    }
}
