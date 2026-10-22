package com.github.catvod.spider;

import android.content.Context;
import android.util.Base64;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 瓜子影视（com.l247ce5e1b.p8b3173c28.xef737a9da20261022 v3.0.6.2）CatVod 蜘蛛
 * ============================================================================
 * 协议（2026-10-22 frida 实机验证，见 E:/rea/hg/gz_spy.txt / gz_pid_map.txt）
 * ============================================================================
 *
 * 【请求】 POST https://{apiHost}/12.2.4/9/8541/<接口>
 *   Header: User-Agent: okhttp/3.12.0
 *           X-App-Key: 65dc336895b14f599d2a6fca
 *           X-Channel: GZ0313
 *   Body  : 裸 JSON，只有 pid（+可选 page/limit/tag），不带 appKey/channel 前缀
 *            电影   {"pid":"3"}
 *            国产剧 {"pid":"4"}
 *            综艺   {"pid":"6"}   (tag: 22大陆 23港台 24日韩 25欧美)
 *            短剧   {"pid":"16"}
 *            榜单   {"pid":"81515"}
 *            分页   {"limit":"30","pid":"3","page":"1"}
 *
 * 【响应】 明文 JSON 框架 {"code":200, ...}，数据字段 AES/CBC/PKCS5Padding 加密
 *   密钥  : 会话级 key/iv（16 字节），服务端每次响应下发；
 *            ★ 诚实边界：能解「分类大包」的主 key 由 App Cipher.init 注入，
 *              frida 只挂 doFinal 没抓到 init 的 key 字节。
 *            下方 masterKey/masterIv 为占位，部署后需用 frida 挂
 *            Cipher.init 抓一次真实主 key 填入（或走手机侧实时解）。
 *
 * 【pid 大类】(frida 实机点出来)
 *   1 首页/全部  3 电影  4 电视剧(国产剧)  6 综艺  16 短剧  81515 榜单
 *
 * 【域名池】本地 NXDOMAIN，需手机侧 / hosts 映射部署。
 */
public class Guazi extends Spider {

    // ==================== 常量（frida / dex 抓取） ====================
    private static final String APPKEY  = "65dc336895b14f599d2a6fca";
    private static final String CHANNEL = "GZ0313";

    /** 会话主 key/iv（16 字节，base64 或 ASCII）。部署需替换为 frida 抓 Cipher.init 的真实值。 */
    private String masterKey; // 通过 init(extend) 注入，不可 final
    private String masterIv;

    // 域名池（轮换）
    private static final String[] API_HOSTS = {
            "scapi.tdqtnuvpci.com",
            "sdapi.r4u3kw.com", "sdapi.ug2wcj.com", "sdapi.wjwyms.com",
            "sdapi.tymnpk4.com", "sdapi.m5rwgtj.com", "sdapi.gkfun2x.com",
            "sdapi.6ne6zmh.com",
    };
    private static final String CDN_HOST = "images.jjawa.com";

    // pid 大类
    private static final int PID_HOME  = 1;
    private static final int PID_MOVIE = 3;
    private static final int PID_TV    = 4;
    private static final int PID_ZY    = 6;
    private static final int PID_DJ    = 16;
    private static final int PID_BILL  = 81515;

    private String host;

    // ==================== 生命周期 ====================

    @Override
    public void init(Context context, String extend) {
        host = pickHost();
        // extend 可传 {"masterKey":"...","masterIv":"..."} 覆盖会话密钥
        if (extend != null && !extend.isEmpty()) {
            try {
                JSONObject o = new JSONObject(extend);
                if (o.has("masterKey")) masterKey = o.getString("masterKey");
                if (o.has("masterIv"))  masterIv = o.getString("masterIv");
            } catch (Exception ignore) {
            }
        }
    }

    private static String pickHost() {
        return API_HOSTS[ThreadLocalRandom.current().nextInt(API_HOSTS.length)];
    }

    // ==================== CatVod 接口 ====================

    /** 首页分类（电影 / 国产剧 / 综艺 / 短剧 / 榜单） */
    @Override
    public String homeContent(boolean filter) {
        JSONArray classes = new JSONArray();
        String[][] std = {
                {String.valueOf(PID_MOVIE), "电影"},
                {String.valueOf(PID_TV), "国产剧"},
                {String.valueOf(PID_ZY), "综艺"},
                {String.valueOf(PID_DJ), "短剧"},
                {String.valueOf(PID_BILL), "榜单"},
        };
        for (String[] s : std) {
            try {
                JSONObject c = new JSONObject();
                c.put("type_id", s[0]);
                c.put("type_name", s[1]);
                classes.put(c);
            } catch (Exception ignore) {
            }
        }
        JSONObject result = new JSONObject();
        try {
            result.put("class", classes);
        } catch (Exception ignore) {
        }
        return result.toString();
    }

    /** 分类列表（电影/国产剧/综艺/短剧/榜单），tid = pid 字符串 */
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        JSONObject result = new JSONObject();
        JSONArray list = new JSONArray();
        int pid = parsePid(tid);
        String body = buildCatBody(pid, parsePg(pg), 30);
        try {
            String json = apiPost(pid, body);
            JSONObject data = decryptIfNeeded(json);
            // 解析 分类->list[] 作品
            JSONArray cats = data.optJSONArray("list");
            if (cats != null) {
                for (int i = 0; i < cats.length(); i++) {
                    JSONObject cat = cats.optJSONObject(i);
                    if (cat == null) continue;
                    JSONArray vods = cat.optJSONArray("list");
                    if (vods == null) continue;
                    String typeName = cat.optString("type", "");
                    for (int k = 0; k < vods.length(); k++) {
                        JSONObject v = vods.optJSONObject(k);
                        if (v == null) continue;
                        JSONObject vod = new JSONObject();
                        vod.put("vod_id", v.optString("vod_id", ""));
                        vod.put("vod_name", v.optString("c_name", ""));
                        vod.put("vod_pic", v.optString("c_pic", ""));
                        vod.put("vod_remarks", v.optString("new_continue", ""));
                        vod.put("vod_content", v.optString("cf_name", ""));
                        vod.put("vod_year", "");
                        vod.put("vod_actor", "");
                        vod.put("type_name", typeName);
                        // 评分
                        String score = v.optString("vod_douban_score", "");
                        if (!score.isEmpty()) {
                            vod.put("vod_score", score);
                        }
                        list.put(vod);
                    }
                }
            }
            result.put("list", list);
            // 分页信息
            result.put("page", parsePg(pg));
            result.put("pagecount", 99); // 瓜子无总页数，留 99
        } catch (Exception e) {
            SpiderDebug.log("[Guazi] categoryContent pid=" + pid + " 失败: " + e);
        }
        return result.toString();
    }

    /** 详情（瓜子无独立详情接口，回 vod 本身；点播地址由 playerContent 解析） */
    @Override
    public String detailContent(List<String> ids) {
        JSONObject result = new JSONObject();
        result.put("list", new JSONArray().put(ids == null ? "" : ids.get(0)));
        return result.toString();
    }

    /** 搜索 */
    @Override
    public String searchContent(String key, boolean quick) {
        JSONObject result = new JSONObject();
        result.put("list", new JSONArray());
        return result.toString();
    }

    /** 播放地址（瓜子播放走 CDN + 临时鉴权，未实采；先回空，部署后补） */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        JSONObject result = new JSONObject();
        JSONArray plist = new JSONArray();
        JSONObject p = new JSONObject();
        try {
            p.put("flag", "瓜子");
            JSONArray urls = new JSONArray();
            urls.put("第1集|https://" + CDN_HOST + "/" + id); // 占位，部署后抓真实播放 URL 替换
            p.put("urls", urls);
            plist.put(p);
        } catch (Exception ignore) {
        }
        result.put("list", plist);
        return result.toString();
    }

    @Override
    public boolean isVideoFormat(String url) {
        return url != null && url.matches(".*\\.(mp4|m3u8|ts|flv|mp3|m4a)$");
    }

    // ==================== 请求构造 ====================

    private static int parsePid(String tid) {
        if (tid == null) return PID_HOME;
        String s = tid.replaceFirst("^[^\\d]*", "");
        try {
            return Integer.parseInt(s.isEmpty() ? "1" : s);
        } catch (Exception e) {
            return PID_HOME;
        }
    }

    private static int parsePg(String pg) {
        try {
            return pg == null || pg.isEmpty() ? 1 : Integer.parseInt(pg);
        } catch (Exception e) {
            return 1;
        }
    }

    /** 实锤请求体：裸 pid */
    private static String buildCatBody(int pid, int pg, int limit) {
        if (limit <= 0) return "{\"pid\":\"" + pid + "\"}";
        return "{\"limit\":\"" + limit + "\",\"pid\":\"" + pid + "\",\"page\":\"" + pg + "\"}";
    }

    // ==================== HTTP + 解密 ====================

    /** POST 业务接口，返回明文框架 JSON（含 AES 密文字段则就地解密） */
    private String apiPost(int pid, String body) throws Exception {
        String host = this.host != null ? this.host : pickHost();
        // 真实路径 12.2.4/9/8541/<接口名>，瓜子接口名未在 frida 直接抓，
        // 用 pid 语义最接近的 category 路径；部署后按实机路径校正
        String url = "https://" + host + "/12.2.4/9/8541/category";

        Request.Builder rb = new Request.Builder()
                .url(url)
                .post(RequestBody.create(MediaType.parse("application/json"), body))
                .header("User-Agent", "okhttp/3.12.0")
                .header("X-App-Key", APPKEY)
                .header("X-Channel", CHANNEL);
        Response r = OkHttp.newCall(rb.build());
        try {
            return r.body() == null ? "" : r.body().string();
        } finally {
            r.close();
        }
    }

    /** 响应含 AES 密文(enc 字段)时用主 key 解密；否则原样返回 */
    private JSONObject decryptIfNeeded(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            String enc = obj.optString("enc", "");
            if (!enc.isEmpty() && masterKey != null && masterIv != null) {
                byte[] ct = Base64.decode(enc, Base64.DEFAULT);
                byte[] pt = aesDecrypt(ct, masterKey, masterIv);
                return new JSONObject(new String(pt, StandardCharsets.UTF_8));
            }
            // 有些结构 key/iv 内嵌
            String key = obj.optString("key", "");
            String iv = obj.optString("iv", "");
            if (!enc.isEmpty() && !key.isEmpty() && !iv.isEmpty()) {
                byte[] ct = Base64.decode(enc, Base64.DEFAULT);
                byte[] pt = aesDecrypt(ct, key, iv);
                return new JSONObject(new String(pt, StandardCharsets.UTF_8));
            }
            return obj;
        } catch (Exception e) {
            try {
                return new JSONObject(json);
            } catch (Exception e2) {
                return new JSONObject();
            }
        }
    }

    /** AES/CBC/PKCS5Padding，key/iv 为 base64 或 16 字节 ASCII */
    static byte[] aesDecrypt(byte[] data, String key, String iv) throws Exception {
        byte[] kb = decodeFlexible(key);
        byte[] ivb = decodeFlexible(iv);
        SecretKeySpec ks = new SecretKeySpec(kb, "AES");
        IvParameterSpec ivs = new IvParameterSpec(ivb);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, ks, ivs);
        return c.doFinal(data);
    }

    /** 16 字节直接用；否则按 base64 解 */
    private static byte[] decodeFlexible(String s) {
        if (s == null) return new byte[0];
        if (s.length() == 16) return s.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] b = Base64.decode(s, Base64.DEFAULT);
            if (b.length >= 16) return b;
        } catch (Exception ignore) {
        }
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
