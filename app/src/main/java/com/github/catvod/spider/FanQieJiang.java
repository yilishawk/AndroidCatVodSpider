package com.github.catvod.spider;

import android.util.Base64;

import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 番茄酱 (app.fanqiejiang.birthday) CatVod 蜘蛛
 * <p>
 * 数据源: https://fankei.cc  (备用镜像 https://fanqiecn.com)
 * <p>
 * 接口:
 * 免签: /request/catalog/content  (分类/筛选/搜索)
 * 免签: /request/content/detail   (详情+剧集)
 * 免签: /request/content/related  (相关推荐)
 * 免签: /request/app/config       (分类树)
 * 签名: /request/playback/resolve (播放解析, POST)
 * <p>
 * 签名算法:
 * msg = METHOD \n PATH \n QUERY \n SHA256(BODY) \n TS \n NONCE \n INSTALL_ID
 * sig = hex(HMAC_SHA256("Rg0qTuu2BltwOIdVnzNVJy9sCbiOD9P90uO0aDDbGa4", msg))
 * PATH 为去掉 /request 前缀的路径, QUERY 为空时保留空段
 */
public class FanQieJiang extends Spider {

    private static final String BASE = "https://fankei.cc";
    private static final String SIGN_KEY = "Rg0qTuu2BltwOIdVnzNVJy9sCbiOD9P90uO0aDDbGa4";
    private static final String INSTALL_ID = "e7df8ca3-25eb-40fa-8d5e-d5a131f4d261";
    private static final String APP_VER = "1.2.0";
    private static final String APP_VC = "105";
    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String FIELDS = "vod_id,vod_name,vod_sub,vod_pic,vod_actor,vod_director,vod_score,"
            + "vod_remarks,vod_blurb,vod_year,vod_tag,vod_class,vod_area,type_id,type_id_1";
    private static final int LIMIT = 21;

    // ==================== 瓜子源（"Temporary" 线路）====================
    // 2026-11-05 新增：App 1.2.0 的详情里出现 from=guazi / show=Temporary 的线路，
    // play_url 形如 `guazi|vod_d_id=49979&vurl_id=4788797&domain_type=8&resolution=1080&type=play`，
    // 不是 URL，playback/resolve 对它直接返回 422「播放器没有配置解析参数」。
    // 实测这条线路就是瓜子影视(api.anctjd.com)的资源，用 vod_d_id + vurl_id 调
    // /App/Resource/VurlDetail/showOne 能直接拿到 1080p m3u8（已验证）。
    private static final String GZ_BASE = "https://api.anctjd.com";
    private static final String GZ_AES_KEY = "RhgUpnAK2agqxPjX";
    private static final String GZ_AES_IV = "sljblQdVvGrVeHKr";
    private static final String GZ_SALT = "*&zvdvdvddbfikkkumtmdwqppp?|4Y!s!2br";
    private static final String GZ_PKG = "com.l247ce5e1b.p8b3173c28.xef737a9da20261022";
    private static final String GZ_TOKEN = "25c6b32e6d6fcb7953a4c4f3e55aac92.abfa0d3364328876cea5957ec741f7e17fe1529"
            + "7b6f47b14e291170d02bfbb2a5bfa29003988eb364deecca57e3e30e68110483515e6b8d"
            + "c14c8039e0808647300718ba9c0a0b041a5e4a5fd5e775e28b28616c6300a0c92c746f2f"
            + "59d33ac49bc703976535e600b26494d78d4bd4526cf1d5590385f5f7ef6e01d1850974aa"
            + "220eb5178c89e61c24411af9b9a19435e.380ff1868615686ec8a7236bfa03f837b5cb4a"
            + "28f591aee22905de4e94fbe8e4";
    private static final String GZ_PUB_HEX = "30819f300d06092a864886f70d010101050003818d0030818902818100d4339fbfcbcb0f"
            + "b1691dd7f4504bae17db9f44530c455c51391e503ae4caabc673ecd09aa8491a23483cb9"
            + "421c2f44e95a0fa4f04501ca318d8e019e929d079426c0a14c414847da94930aecdff315"
            + "50cc63b2fe894ba39efe3b9c9722464e05660e1079e4469f5ec0f44906158ff4175ecc51"
            + "e9ec11e44da42f9db20000f8c90203010001";
    private static final String GZ_PRIV_B64 = "MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGAe6hKrWLi1zQmjTT1ozbE4QdF"
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

    /** 顶层分类: 国产剧置顶 */
    private static final String[][] CATES = {
            {"2_国产剧", "国产剧"},
            {"2", "电视剧"},
            {"1", "电影"},
            {"3", "动漫"},
            {"4", "综艺"},
            {"5", "短剧"},
    };

    private static final String YEAR = "2026,2025,2024,2023,2022,2021,2020,2019,2018,2017,2016,2015";

    /** type_id, key, name, values —— 来自 /request/app/config 的 get_type */
    private static final String[][] DIMS = {
            {"1", "class", "类型", "动作片,喜剧片,爱情片,科幻片,恐怖片,剧情片,战争片,纪录片,悬疑,动画片,灾难"},
            {"1", "area", "地区", "大陆,香港,台湾,美国,韩国,日本,法国,英国,其他"},
            {"1", "lang", "语言", "国语,英语,粤语,韩语,日语"},
            {"1", "year", "年份", YEAR},

            {"2", "class", "类型", "国产剧,香港剧,韩国剧,欧美剧,台湾剧,日本剧,海外剧,泰国剧"},
            {"2", "area", "地区", "内地,韩国,香港,台湾,日本,美国,泰国,其他"},
            {"2", "lang", "语言", "国语,英语,粤语,韩语,日语"},
            {"2", "year", "年份", YEAR},

            {"3", "class", "类型", "国产动漫,日本动漫,欧美动漫,港台动漫,海外动漫"},
            {"3", "area", "地区", "国产,日本,欧美,其他"},
            {"3", "lang", "语言", "国语,日语,英语"},
            {"3", "year", "年份", YEAR},
            {"3", "version", "版本", "TV版,电影版,OVA"},

            {"4", "class", "类型", "大陆综艺,港台综艺,日韩综艺,欧美综艺"},
            {"4", "area", "地区", "内地,港台,日韩,欧美"},
            {"4", "lang", "语言", "国语,粤语,英语"},
            {"4", "year", "年份", YEAR},

            {"5", "class", "类型", "国产短剧,海外短剧,女频恋爱,反转爽剧,古装仙侠,年代穿越,现代都市"},
            {"5", "area", "地区", "内地,其他"},
            {"5", "lang", "语言", "国语"},
            {"5", "year", "年份", "2026,2025,2024,2023,2022,2021,2020"},
    };

    /** 成人/敏感内容过滤词 */
    private static final String[] BLOCK = {
            "色情", "情色", "成人片", "三级片", "伦理片", "男同", "女同", "同性", "淫", "裸体", "AV片"
    };

    // ==================== 网络层 ====================

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String buildQuery(Map<String, String> p) {
        if (p == null || p.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return sb.toString();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            String h = Integer.toHexString(x & 0xFF);
            if (h.length() == 1) sb.append('0');
            sb.append(h);
        }
        return sb.toString();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(s.getBytes("UTF-8")));
        } catch (Exception e) {
            return "";
        }
    }

    private static String hmac(String msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SIGN_KEY.getBytes("UTF-8"), "HmacSHA256"));
            return hex(mac.doFinal(msg.getBytes("UTF-8")));
        } catch (Exception e) {
            return "";
        }
    }

    private Map<String, String> headers(String method, String path, String query, String body) {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String msg = method + "\n" + path + "\n" + (query == null ? "" : query) + "\n"
                + sha256(body == null ? "" : body) + "\n" + ts + "\n" + nonce + "\n" + INSTALL_ID;
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Accept", "application/json, */*");
        h.put("Referer", "https://collect.fanqiecn.com/");
        h.put("X-App-Platform", "android");
        h.put("X-App-Version", APP_VER);
        h.put("X-App-Version-Code", APP_VC);
        h.put("X-Install-Id", INSTALL_ID);
        h.put("X-Request-Timestamp", ts);
        h.put("X-Request-Nonce", nonce);
        h.put("X-Request-Signature", hmac(msg));
        return h;
    }

    /** 发请求, 返回解包后的 JSONObject (自动展开 data 字符串) */
    private JSONObject call(String method, String path, Map<String, String> params) {
        String query = buildQuery(params);
        String url = BASE + "/request" + path + (query.isEmpty() ? "" : "?" + query);
        Map<String, String> h = headers(method, path, query, "");
        try {
            OkResult r;
            if ("POST".equals(method)) {
                r = OkHttp.post(url, new HashMap<String, String>(), h);
            } else {
                r = OkHttp.get(url, new HashMap<String, String>(), h);
            }
            String body = r.getBody();
            if (body == null || body.isEmpty()) {
                SpiderDebug.log("[番茄酱] 空响应 " + path);
                return null;
            }
            JSONObject j = new JSONObject(body);
            String data = j.optString("data", "");
            if (!data.isEmpty() && data.charAt(0) == '{') return new JSONObject(data);
            return j;
        } catch (Exception e) {
            SpiderDebug.log("[番茄酱] " + path + " 异常 " + e);
            return null;
        }
    }

    private static boolean isAdult(JSONObject o) {
        if (o == null) return true;
        String s = o.optString("vod_name", "") + o.optString("vod_class", "")
                + o.optString("vod_tag", "") + o.optString("vod_blurb", "");
        for (String w : BLOCK) {
            if (s.contains(w)) return true;
        }
        return false;
    }

    // ==================== 分类 ====================

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
        Map<String, String> p = new LinkedHashMap<>();
        p.put("type", "2");
        p.put("page", "1");
        p.put("limit", "12");
        p.put("fields", FIELDS);
        p.put("class", "国产剧");
        JSONObject d = call("GET", "/catalog/content", p);
        if (d != null) {
            JSONArray arr = d.optJSONArray("data");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    if (isAdult(it)) continue;
                    list.put(toVod(it));
                }
            }
        }

        StringBuilder sb = new StringBuilder("{\"class\":");
        sb.append(classes.toString());
        sb.append(",\"filters\":").append(buildFilters().toString());
        sb.append(",\"list\":").append(list.toString());
        sb.append("}");
        SpiderDebug.log("[番茄酱] homeContent list=" + list.length());
        return sb.toString();
    }

    private JSONObject buildFilters() {
        JSONObject out = new JSONObject();
        try {
            for (String[] c : CATES) {
                String tid = c[0];
                String base = tid.contains("_") ? tid.substring(0, tid.indexOf('_')) : tid;
                JSONArray dims = new JSONArray();
                for (String[] dim : DIMS) {
                    if (!dim[0].equals(base)) continue;
                    // 国产剧频道已锁定类型, 不再给类型维度
                    if (tid.contains("_") && "class".equals(dim[1])) continue;
                    JSONObject o = new JSONObject();
                    o.put("key", dim[1]);
                    o.put("name", dim[2]);
                    JSONArray vals = new JSONArray();
                    JSONObject all = new JSONObject();
                    all.put("n", "全部");
                    all.put("v", "");
                    vals.put(all);
                    for (String v : dim[3].split(",")) {
                        JSONObject vv = new JSONObject();
                        vv.put("n", v);
                        vv.put("v", v);
                        vals.put(vv);
                    }
                    o.put("value", vals);
                    dims.put(o);
                }
                out.put(tid, dims);
            }
        } catch (Exception e) {
            SpiderDebug.log("[番茄酱] buildFilters " + e);
        }
        return out;
    }

    private JSONObject toVod(JSONObject o) {
        JSONObject v = new JSONObject();
        try {
            v.put("vod_id", o.optString("vod_id"));
            v.put("vod_name", o.optString("vod_name"));
            v.put("vod_pic", o.optString("vod_pic"));
            v.put("vod_remarks", o.optString("vod_remarks"));
            v.put("vod_year", o.optString("vod_year"));
            v.put("vod_area", o.optString("vod_area"));
            String cls = o.optString("vod_class", "");
            if (cls.isEmpty()) cls = o.optString("vod_tag", "");
            v.put("vod_tag", cls);
        } catch (Exception ignored) {
        }
        return v;
    }

    // ==================== 列表 ====================

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend)
            throws Exception {
        String type = tid;
        String forcedClass = null;
        if (tid.contains("_")) {
            type = tid.substring(0, tid.indexOf('_'));
            forcedClass = tid.substring(tid.indexOf('_') + 1);
        }
        int page = 1;
        try {
            page = Integer.parseInt(pg);
        } catch (Exception ignored) {
        }

        Map<String, String> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("page", String.valueOf(page));
        p.put("limit", String.valueOf(LIMIT));
        p.put("fields", FIELDS);
        if (forcedClass != null) p.put("class", forcedClass);
        if (extend != null) {
            for (String k : new String[]{"class", "area", "lang", "year", "version"}) {
                String v = extend.get(k);
                if (v != null && !v.isEmpty()) p.put(k, v);
            }
        }

        JSONObject d = call("GET", "/catalog/content", p);
        List<Vod> list = new ArrayList<>();
        int total = 0, pageCount = 1;
        if (d != null) {
            total = d.optInt("total", 0);
            pageCount = d.optInt("last_page", 1);
            JSONArray arr = d.optJSONArray("data");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    if (isAdult(it)) continue;
                    Vod v = new Vod();
                    v.setVodId(it.optString("vod_id"));
                    v.setVodName(it.optString("vod_name"));
                    v.setVodPic(it.optString("vod_pic"));
                    v.setVodRemarks(it.optString("vod_remarks"));
                    v.setVodYear(it.optString("vod_year"));
                    v.setVodArea(it.optString("vod_area"));
                    String cls = it.optString("vod_class", "");
                    if (cls.isEmpty()) cls = it.optString("vod_tag", "");
                    v.setVodTag(cls);
                    list.add(v);
                }
            }
        }
        SpiderDebug.log("[番茄酱] category tid=" + tid + " page=" + page + " n=" + list.size() + " total=" + total);
        return Result.get().vod(list).page(page, pageCount, LIMIT, total).string();
    }

    // ==================== 详情 ====================

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return "";
        String id = ids.get(0);
        Map<String, String> p = new LinkedHashMap<>();
        p.put("id", id);
        p.put("__platform", "Android");
        p.put("__ic", "XX");
        JSONObject d = call("GET", "/content/detail", p);
        if (d == null) return "";

        Vod v = new Vod();
        v.setVodId(d.optString("vod_id", id));
        v.setVodName(d.optString("vod_name"));
        v.setVodPic(d.optString("vod_pic"));
        v.setVodRemarks(d.optString("vod_remarks"));
        v.setVodYear(d.optString("vod_year"));
        v.setVodArea(d.optString("vod_area"));
        v.setVodActor(d.optString("vod_actor"));
        v.setVodDirector(d.optString("vod_director"));
        String cls = d.optString("vod_class", "");
        if (cls.isEmpty()) cls = d.optString("vod_tag", "");
        v.setTypeName(cls);
        v.setVodTag(cls);
        String content = d.optString("vod_content", "");
        if (content.isEmpty()) content = d.optString("vod_blurb", "");
        v.setVodContent(content.replaceAll("<[^>]+>", "").trim());

        StringBuilder from = new StringBuilder();
        StringBuilder url = new StringBuilder();
        JSONArray groups = d.optJSONArray("vod_play_list");
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.optJSONObject(i);
                if (g == null) continue;
                JSONObject pi = g.optJSONObject("player_info");
                String src = pi == null ? "" : pi.optString("from", "");
                String show = pi == null ? "" : pi.optString("show", "");
                if (src.isEmpty()) continue;
                if (show.isEmpty()) show = src;

                JSONArray eps = g.optJSONArray("urls");
                if (eps == null || eps.length() == 0) continue;
                StringBuilder ep = new StringBuilder();
                for (int k = 0; k < eps.length(); k++) {
                    JSONObject e = eps.optJSONObject(k);
                    if (e == null) continue;
                    String u = e.optString("url", "");
                    if (u.isEmpty()) continue;
                    String nm = e.optString("name", "第" + (k + 1) + "集");
                    if (ep.length() > 0) ep.append('#');
                    // 用 "源|地址" 编码, playerContent 里拆开
                    ep.append(nm).append('$').append(src).append('|').append(u);
                }
                if (ep.length() == 0) continue;
                if (from.length() > 0) {
                    from.append("$$$");
                    url.append("$$$");
                }
                from.append(show);
                url.append(ep);
            }
        }
        v.setVodPlayFrom(from.toString());
        v.setVodPlayUrl(url.toString());
        SpiderDebug.log("[番茄酱] detail id=" + id + " 播放源=" + from);
        return Result.get().vod(v).string();
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
        Map<String, String> p = new LinkedHashMap<>();
        p.put("wd", key);
        p.put("page", String.valueOf(page));
        p.put("limit", String.valueOf(LIMIT));
        p.put("fields", FIELDS);
        JSONObject d = call("GET", "/catalog/content", p);
        List<Vod> list = new ArrayList<>();
        if (d != null) {
            JSONArray arr = d.optJSONArray("data");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    if (isAdult(it)) continue;
                    Vod v = new Vod();
                    v.setVodId(it.optString("vod_id"));
                    v.setVodName(it.optString("vod_name"));
                    v.setVodPic(it.optString("vod_pic"));
                    v.setVodRemarks(it.optString("vod_remarks"));
                    v.setVodYear(it.optString("vod_year"));
                    v.setVodArea(it.optString("vod_area"));
                    list.add(v);
                }
            }
        }
        SpiderDebug.log("[番茄酱] search " + key + " n=" + list.size());
        return Result.get().vod(list).string();
    }

    // ==================== 瓜子源工具（"Temporary" 线路）====================

    private static String gzHexUpper(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString().toUpperCase();
    }

    private static byte[] gzUnhex(String s) {
        int n = s.length() / 2;
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    private static String gzMd5Upper(String s) throws Exception {
        return gzHexUpper(MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8")));
    }

    private static String gzAesEnc(String plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(GZ_AES_KEY.getBytes("UTF-8"), "AES"),
                new IvParameterSpec(GZ_AES_IV.getBytes("UTF-8")));
        return gzHexUpper(c.doFinal(plain.getBytes("UTF-8")));
    }

    private static String gzAesDec(String hexCt, String key, String iv) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.getBytes("UTF-8"), "AES"),
                new IvParameterSpec(iv.getBytes("UTF-8")));
        return new String(c.doFinal(gzUnhex(hexCt)), "UTF-8");
    }

    private static String gzRsaEnc(String plain) throws Exception {
        PublicKey pk = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(gzUnhex(GZ_PUB_HEX)));
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, pk);
        return Base64.encodeToString(c.doFinal(plain.getBytes("UTF-8")), Base64.NO_WRAP);
    }

    private static String gzRsaDec(String b64Ct) throws Exception {
        PrivateKey pk = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(GZ_PRIV_B64, Base64.NO_WRAP)));
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.DECRYPT_MODE, pk);
        return new String(c.doFinal(Base64.decode(b64Ct, Base64.NO_WRAP)), "UTF-8");
    }

    private static Map<String, String> gzHeader() {
        Map<String, String> h = new HashMap<>();
        h.put("code", "GZ0313");
        h.put("lang", "zh_cn");
        h.put("Version", "2610013");
        h.put("Ver", "3.0.6.2");
        h.put("api-ver", "3.0.6.2");
        h.put("PackageName", GZ_PKG);
        h.put("Referer", GZ_BASE);
        h.put("User-Agent", "okhttp/3.12.0");
        h.put("Content-Type", "application/x-www-form-urlencoded");
        return h;
    }

    /** 瓜子统一请求: 加密入参 -> 签名 -> POST -> 解响应 */
    private static JSONObject gzApi(String path, Map<String, String> biz) {
        try {
            JSONObject bizJson = new JSONObject();
            if (biz != null) {
                for (Map.Entry<String, String> e : biz.entrySet()) bizJson.put(e.getKey(), e.getValue());
            }
            String requestKey = gzAesEnc(bizJson.toString());

            JSONObject keyInfo = new JSONObject();
            keyInfo.put("iv", GZ_AES_IV);
            keyInfo.put("key", GZ_AES_KEY);
            String keys = gzRsaEnc(keyInfo.toString());

            LinkedHashMap<String, String> kv = new LinkedHashMap<>();
            kv.put("token_id", "");
            kv.put("token", GZ_TOKEN);
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
            kv.put("signature", gzMd5Upper(sign.toString() + GZ_SALT));

            OkResult r = OkHttp.post(GZ_BASE + path, kv, gzHeader());
            String body = r.getBody();
            if (body == null || body.trim().isEmpty()) {
                SpiderDebug.log("[番茄酱] 瓜子 " + path + " 空响应 http=" + r.getCode());
                return null;
            }
            JSONObject js = new JSONObject(body);
            if (js.optInt("code") != 200) {
                SpiderDebug.log("[番茄酱] 瓜子 " + path + " code=" + js.optInt("code")
                        + " msg=" + js.optString("msg"));
                return null;
            }
            JSONObject data = js.optJSONObject("data");
            if (data == null) return null;

            JSONObject info = new JSONObject(gzRsaDec(data.optString("keys")));
            String plain = gzAesDec(data.optString("response_key"),
                    info.getString("key"), info.getString("iv")).trim();
            if (plain.startsWith("{")) return new JSONObject(plain);
            return null;
        } catch (Exception e) {
            SpiderDebug.log("[番茄酱] 瓜子 " + path + " 异常 " + e);
            return null;
        }
    }

    /** 从 `a=1&b=2` 里取参数值 */
    private static String qsParam(String qs, String key) {
        if (qs == null || qs.isEmpty()) return "";
        for (String seg : qs.split("&")) {
            int i = seg.indexOf('=');
            if (i > 0 && key.equals(seg.substring(0, i))) return seg.substring(i + 1);
        }
        return "";
    }

    /** 瓜子播放: vod_d_id + vurl_id -> m3u8 直链 */
    private static String gzPlay(String vodDId, String vurlId) {
        if (vodDId.isEmpty() || vurlId.isEmpty()) return "";
        Map<String, String> q = new HashMap<>();
        q.put("vod_d_id", vodDId);
        q.put("vurl_id", vurlId);
        q.put("domain_type", "8");
        q.put("resolution", "1080");
        q.put("type", "play");
        JSONObject d = gzApi("/App/Resource/VurlDetail/showOne", q);
        String url = d == null ? "" : d.optString("url");
        SpiderDebug.log("[番茄酱] 瓜子播放 " + vodDId + "/" + vurlId + " -> " + url);
        return url;
    }

    // ==================== 播放 ====================

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String src = "";
        String playUrl = id == null ? "" : id;
        int i = playUrl.indexOf('|');
        if (i > 0 && i < 40) {
            src = playUrl.substring(0, i);
            playUrl = playUrl.substring(i + 1);
        }

        // 瓜子源（线路名 "Temporary"）：play_url 是 `guazi|vod_d_id=..&vurl_id=..`，
        // 不是 URL，playback/resolve 对它返回 422「播放器没有配置解析参数」，
        // 必须自己走瓜子接口解析。
        if ("guazi".equals(src) || playUrl.startsWith("guazi|")) {
            String qs = playUrl.startsWith("guazi|")
                    ? playUrl.substring("guazi|".length()) : playUrl;
            String real = gzPlay(qsParam(qs, "vod_d_id"), qsParam(qs, "vurl_id"));
            if (!real.isEmpty()) {
                SpiderDebug.log("[番茄酱] Temporary(瓜子) -> " + real);
                return Result.get().url(real).string();
            }
            SpiderDebug.log("[番茄酱] Temporary(瓜子) 解析失败，尝试服务端解析");
        }

        if (!src.isEmpty() && !playUrl.isEmpty()) {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("key", src);
            p.put("label", "");
            p.put("play_url", playUrl);
            p.put("player_from", src);
            JSONObject j = call("POST", "/playback/resolve", p);
            if (j != null) {
                String real = j.optString("url", "");
                if (real.isEmpty()) real = j.optString("origin", "");
                if (!real.isEmpty()) {
                    SpiderDebug.log("[番茄酱] play " + src + " -> " + real);
                    return Result.get().url(real).string();
                }
            }
        }
        // 解析失败时的兜底：
        //  · http 直链可以直接交给播放器（部分源本身就是 m3u8，resolve 返回 422）
        //  · 非 http 的伪地址（czzy:8858:1 / JD-xxx / ApBhhk-1-1）丢给播放器只会报
        //    "格式不支持"，不如返回空让壳子明确提示播放失败、由用户换线路
        if (!playUrl.startsWith("http")) {
            SpiderDebug.log("[番茄酱] " + src + " 解析失败且非直链: " + playUrl);
            return Result.get().url("").string();
        }
        return Result.get().url(playUrl).string();
    }
}
