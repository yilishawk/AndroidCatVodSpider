package com.github.catvod.spider;

import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 毒舌电影（com.dsdyC100000V261024.T181208 v3.5.0）
 *
 * 一、签名（PC 可直连，无需手机）
 *     sign = HMAC-SHA1( KEY, "get|path|排序后的query|ts|额外头|" )
 *     KEY = ksggsr4tp6difdo1c3im8fqd3g（native libijiamihelper.so）
 *     额外头 = appId=dsdy&deviceCreatedAt=..&deviceId=..&st=2&userId=..（顺序固定）
 *     ts 实测服务端不校验（可任意），但 header 里必须带
 *
 * 二、响应体：AES-256-CBC/PKCS5 解密，key/iv 固定（libijiamihelper.so 内常量）
 *     key = ayt5wy5afwmwrpb19k9s3psx3dymyd0n
 *     iv  = b3t069ijy7piw0j
 *     密文长度恒为 16 的倍数，无额外包头
 *
 * 三、接口
 *     vcache.qvfic.com/vod/channel/list.capi   分类列表  channelId/sort/category/area/language/year/next
 *     vcache.qvfic.com/v2/vod/detail.capi      详情+剧集+直链  vodId
 *     vcache.qvfic.com/v5/config/appInit.capi  筛选项（channelListQuery）
 *     vlogic.qvfic.com/vod/search/query        搜索（无 .capi 后缀）channelId/k/next/userChannel
 *
 * 定制：首页不出推荐流；国产剧置顶；过滤成人（福利频道整源剔除 + 标签二次过滤）
 */
public class DuShe extends Spider {

    // ==================== 常量 ====================

    private static final String HOST = "https://vcache.qvfic.com";
    private static final String HOST_LOGIC = "https://vlogic.qvfic.com";
    // 图片走 vres 域名（实测四个域名都返回真实 webp，且不需要 UA）。
    // 之前用 IP 103.39.111.180:51050 也能通，但域名更抗失效。
    private static final String IMG_HOST = "https://vres.xqdhkqt.cn";

    private static final String SIGN_KEY = "ksggsr4tp6difdo1c3im8fqd3g";
    private static final String AES_KEY = "ayt5wy5afwmwrpb19k9s3psx3dymyd0n";
    private static final String AES_IV = "b3t069ijy7pirw0j";

    private static final String APP_ID = "dsdy";
    private static final String PKG = "com.dsdyC100000V261024.T181208";
    private static final String APP_VER = "3.5.0";
    private static final String DEVICE_ID = "7e969990863cae93";
    private static final String DEVICE_CREATED_AT = "1792899792260";
    private static final String USER_ID = "56909655";
    private static final String CHANNEL = "c100000";
    private static final String UA = PKG + "/" + APP_VER
            + " Dalvik/2.1.0 (Linux; U; Android 12; Redmi K30 5G Build/SKQ1.211006.001)";
    private static final String X_TOKEN = "NTY5MDk2NTV8MTc5Mjg5OTgwOXw2MjE3YjgzNjNlZTIyY2Q0ODFmNjk4"
            + "NTg1NmM1YzBlMjYyM2I2ZmQwM2M2OWQzODQyM2U4OGYxNGFiYzM0ZGI3";
    private static final String DEVICE_INFO = "eyJicmFuZCI6IlhpYW9taSIsIm1vZGVsIjoiUmVkYWkgSzMwIDVHIiwidHlw"
            + "ZSI6InBob25lIiwicmVzb2x1dGlvblgiOiIxMDgwIiwicmVzb2x1dGlvblkiOiIyMjYxIiwib3JpZW50"
            + "YXRpb24iOiIxIiwib3NOYW1lIjoiYW5kcm9pZCIsIm9zVmVyc2lvbiI6IjEyIiwib3NMZXZlbCI6IjMx"
            + "IiwiYWJpIjoiYXJtNjQtdjhhLGFybWVhYmktdjdhLGFybWVhYmkiLCJhbmRyb2lkSWQiOiI3ZTk2OTk5"
            + "MDg2M2NhZTkzIiwidXVpZCI6ImExZTJkN2Q5LWQzOWEtNGE1ZC1iMDY2LTdjOGZhYWUyZTYyYyIsImdh"
            + "aWQiOiIifQ==";

    /** 参与签名的额外请求头，顺序固定 */
    private static final String SIGN_EXTRA = "appId=" + APP_ID
            + "&deviceCreatedAt=" + DEVICE_CREATED_AT
            + "&deviceId=" + DEVICE_ID
            + "&st=2"
            + "&userId=" + USER_ID;

    private static final int PAGE_SIZE = 21;

    /** 分类：{type_id, 名称, 锁定地区}；国产剧置顶，福利(channelId=5, isAdult)整源剔除 */
    private static final String[][] CATES = {
            {"2_中国大陆", "国产剧", "中国大陆"},
            {"1", "电影", ""},
            {"2", "剧集", ""},
            {"6", "短剧", ""},
            {"2002", "韩剧", ""},
            {"3", "动漫", ""},
            {"4", "综艺纪录", ""},
            {"2001", "院线上新", ""},
    };

    /** 成人内容关键词：筛选项剔除 + 结果二次过滤 */
    private static final String[] ADULT_WORDS = {
            "伦理", "倫理", "情色", "写真", "福利", "美女", "同性", "擦边",
            "成人", "三级", "無碼", "无码", "激情", "自慰", "偷拍",
    };

    private static JSONObject sAppInit;   // 缓存 appInit（筛选项来源）

    // ==================== 基础工具 ====================

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            int v = x & 0xFF;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    private static Map<String, String> header() {
        Map<String, String> h = new HashMap<>();
        h.put("user-agent", UA);
        h.put("appid", APP_ID);
        h.put("os", "android");
        h.put("appversion", APP_VER);
        h.put("package", PKG);
        h.put("deviceid", DEVICE_ID);
        h.put("devicecreatedat", DEVICE_CREATED_AT);
        h.put("userid", USER_ID);
        h.put("deviceinfo", DEVICE_INFO);
        h.put("channelid", CHANNEL);
        h.put("x-cdn", "1");
        h.put("x-d-video", "1");
        h.put("st", "2");
        h.put("x-token", X_TOKEN);
        return h;
    }

    /** sign = HMAC-SHA1(KEY, "get|path|排序query|ts|额外头|") */
    private static String sign(String path, Map<String, String> params, String ts) {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        List<String> kv = new ArrayList<>();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            kv.add(e.getKey() + "=" + e.getValue());
        }
        String plain = "get|" + path + "|" + join(kv, "&") + "|" + ts + "|" + SIGN_EXTRA + "|";
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(SIGN_KEY.getBytes("UTF-8"), "HmacSHA1"));
            return hex(mac.doFinal(plain.getBytes("UTF-8")));
        } catch (Exception e) {
            SpiderDebug.log("[毒舌] 签名异常 " + e);
            return "";
        }
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    private static byte[] httpBytes(String url, Map<String, String> headers) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) return null;
            return readAll(is);
        } catch (Exception e) {
            SpiderDebug.log("[毒舌] http 异常 " + e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] aesDec(byte[] data) {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(AES_KEY.getBytes("UTF-8"), "AES"),
                    new IvParameterSpec(AES_IV.getBytes("UTF-8")));
            return c.doFinal(data);
        } catch (Exception e) {
            SpiderDebug.log("[毒舌] 解密失败 " + e);
            return null;
        }
    }

    /** 发请求并解密；返回明文 JSON，失败返回 null */
    private static JSONObject api(String host, String path, Map<String, String> params) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("os", "android");
        p.put("appId", APP_ID);
        p.put("userLevel", "2");
        if (params != null) p.putAll(params);

        String ts = String.valueOf(System.currentTimeMillis());
        String sg = sign(path, p, ts);
        StringBuilder url = new StringBuilder(host).append(path).append('?');
        List<String> kv = new ArrayList<>();
        for (Map.Entry<String, String> e : new TreeMap<>(p).entrySet()) {
            kv.add(e.getKey() + "=" + enc(e.getValue()));
        }
        url.append(join(kv, "&"));

        Map<String, String> h = header();
        h.put("ts", ts);
        h.put("sign", sg);

        byte[] raw = httpBytes(url.toString(), h);
        if (raw == null || raw.length == 0) {
            SpiderDebug.log("[毒舌] " + path + " 空响应");
            return null;
        }
        byte[] plain = aesDec(raw);
        if (plain == null) return null;
        try {
            return new JSONObject(new String(plain, "UTF-8"));
        } catch (Exception e) {
            SpiderDebug.log("[毒舌] " + path + " JSON 解析失败 " + e);
            return null;
        }
    }

    private static boolean isAdult(String text) {
        if (text == null) return false;
        for (String w : ADULT_WORDS) {
            if (text.contains(w)) return true;
        }
        return false;
    }

    private static boolean isAdultItem(JSONObject it) {
        if (it == null) return true;
        // 福利频道
        if (it.optInt("channelId") == 5) return true;
        if (isAdult(it.optString("title"))) return true;
        JSONArray labels = it.optJSONArray("labels");
        if (labels != null) {
            for (int i = 0; i < labels.length(); i++) {
                JSONObject l = labels.optJSONObject(i);
                if (l != null && isAdult(l.optString("name"))) return true;
            }
        }
        return false;
    }

    /**
     * 广告 / 外链位。搜索与列表都会混进来，特征是 id<=0、
     * url 指向 browser 外链、或 labels 里带 google 标记。
     * 不过滤会在客户端显示成一条空白条目。
     */
    private static boolean isAd(JSONObject it) {
        if (it == null) return true;
        if (it.optInt("id") <= 0) return true;
        String u = it.optString("url");
        if (u.startsWith("browser") || u.startsWith("http")) return true;
        JSONArray labels = it.optJSONArray("labels");
        if (labels != null) {
            for (int i = 0; i < labels.length(); i++) {
                JSONObject l = labels.optJSONObject(i);
                if (l != null && "google".equals(l.optString("id"))) return true;
            }
        }
        return false;
    }

    private static String pic(JSONObject it) {
        String path = it.optString("imagePath");
        if (path.isEmpty()) return "";
        if (path.startsWith("http")) return path;
        String group = it.optString("imageGroup");
        return IMG_HOST + "/" + group + path;
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
        // 首页不出推荐流：list 恒为空，进源直接落分类页
        StringBuilder sb = new StringBuilder("{\"class\":");
        sb.append(classes.toString());
        sb.append(",\"filters\":").append(buildFilters().toString());
        sb.append(",\"list\":[]}");
        return sb.toString();
    }

    // ==================== 筛选项 ====================

    private static JSONObject appInit() {
        if (sAppInit != null) return sAppInit;
        JSONObject o = api(HOST, "/v5/config/appInit.capi", null);
        // 该接口响应顶层直接是 config/channelListQuery 等，没有 code 字段，
        // 所以不能按 code==200 判断，改看关键字段是否就位
        if (o != null && o.optJSONArray("channelListQuery") != null) {
            sAppInit = o;
        }
        return sAppInit;
    }

    /**
     * 筛选项。CatVod 是按 type_id 取 filters 的，所以键必须是 class 里的
     * type_id（如 "2_中国大陆"），不能直接用 channelId，否则带锁定的分类取不到。
     */
    private static JSONObject buildFilters() {
        JSONObject out = new JSONObject();
        JSONObject init = appInit();
        if (init == null) return out;
        JSONArray q = init.optJSONArray("channelListQuery");
        if (q == null) return out;

        // channelId -> items，便于按 CATES 里的 type_id 反查
        Map<String, JSONArray> byCid = new LinkedHashMap<>();
        for (int i = 0; i < q.length(); i++) {
            JSONObject ch = q.optJSONObject(i);
            if (ch == null) continue;
            int cid = ch.optInt("channelId");
            if (cid == 5) continue;  // 福利频道整源剔除
            JSONArray items = ch.optJSONArray("items");
            if (items != null) byCid.put(String.valueOf(cid), items);
        }

        for (String[] cate : CATES) {
            String tid = cate[0];
            boolean lockArea = tid.contains("_");
            String cid = lockArea ? tid.substring(0, tid.indexOf('_')) : tid;
            JSONArray items = byCid.get(cid);
            if (items == null) continue;
            try {
                JSONArray fl = new JSONArray();
                for (int j = 0; j < items.length(); j++) {
                    JSONObject it = items.optJSONObject(j);
                    if (it == null) continue;
                    String key = it.optString("query");
                    // 地区被锁死的分类（国产剧）不再暴露地区筛选，避免选了不生效
                    if (lockArea && "area".equals(key)) continue;
                    JSONArray data = it.optJSONArray("data");
                    if (data == null || data.length() == 0) continue;
                    JSONArray vals = new JSONArray();
                    for (int k = 0; k < data.length(); k++) {
                        JSONObject d = data.optJSONObject(k);
                        if (d == null) continue;
                        String name = d.optString("name");
                        if (isAdult(name)) continue;   // 筛选项剔除成人子分类
                        JSONObject v = new JSONObject();
                        v.put("n", name);
                        v.put("v", d.optString("id"));
                        vals.put(v);
                    }
                    if (vals.length() == 0) continue;
                    JSONObject f = new JSONObject();
                    f.put("key", key);
                    f.put("name", fname(key));
                    f.put("value", vals);
                    fl.put(f);
                }
                if (fl.length() > 0) out.put(tid, fl);
            } catch (Exception e) {
                SpiderDebug.log("[毒舌] 筛选项构建异常 " + tid + " " + e);
            }
        }
        return out;
    }

    private static String fname(String key) {
        if ("sort".equals(key)) return "排序";
        if ("category".equals(key)) return "类型";
        if ("area".equals(key)) return "地区";
        if ("year".equals(key)) return "年份";
        if ("language".equals(key)) return "语言";
        return key;
    }

    // ==================== 分类列表 ====================

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = 1;
        try {
            page = Integer.parseInt(pg);
        } catch (Exception ignored) {
        }
        String t = tid == null ? "" : tid;
        String lockedArea = "";
        if (t.contains("_")) {
            lockedArea = t.substring(t.indexOf('_') + 1);
            t = t.substring(0, t.indexOf('_'));
        }

        Map<String, String> p = new LinkedHashMap<>();
        p.put("channelId", t);
        p.put("sort", pick(extend, "sort", "3"));
        p.put("category", pick(extend, "category", ""));
        p.put("area", lockedArea.isEmpty() ? pick(extend, "area", "") : lockedArea);
        p.put("language", pick(extend, "language", ""));
        p.put("year", pick(extend, "year", ""));
        p.put("next", page <= 1 ? "" : "page=" + page);

        List<Vod> vods = new ArrayList<>();
        int rawSize = 0;
        String next = "";
        JSONObject o = api(HOST, "/vod/channel/list.capi", p);
        if (o != null && o.optInt("code") == 200) {
            JSONObject d = o.optJSONObject("data");
            if (d != null) {
                next = d.optString("next");   // 如 "page=2"，末页为空
                JSONArray items = d.optJSONArray("items");
                if (items != null) {
                    rawSize = items.length();
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject it = items.optJSONObject(i);
                        if (it == null || isAdultItem(it) || isAd(it)) continue;
                        vods.add(toVod(it));
                    }
                }
            }
        }
        // 翻页判断：接口直接给 next（如 "page=2"），末页为空。
        // 过滤后的条数不能用来判断，必须用原始条数/next 字段。
        boolean more = !next.isEmpty() && rawSize > 0;
        SpiderDebug.log("[毒舌] category tid=" + tid + " pg=" + page
                + " raw=" + rawSize + " out=" + vods.size() + " next=" + next);
        return Result.get().vod(vods).page(page, more ? page + 1 : page, PAGE_SIZE, 0).string();
    }

    private static String pick(HashMap<String, String> extend, String key, String def) {
        if (extend == null) return def;
        String v = extend.get(key);
        if (v == null || v.trim().isEmpty()) return def;
        return v;
    }

    private static Vod toVod(JSONObject it) {
        Vod v = new Vod();
        v.setVodId(String.valueOf(it.optInt("id")));
        v.setVodName(it.optString("title"));
        v.setVodPic(pic(it));
        v.setVodRemarks(it.optString("bottomLabel"));
        v.setVodTag(it.optString("topRightLabel"));
        return v;
    }

    // ==================== 详情 ====================

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return "";
        String id = ids.get(0).trim();

        Map<String, String> p = new LinkedHashMap<>();
        p.put("vodId", id);
        JSONObject o = api(HOST, "/v2/vod/detail.capi", p);
        if (o == null || o.optInt("code") != 200) {
            SpiderDebug.log("[毒舌] detail 失败 id=" + id);
            return "";
        }
        JSONObject d = o.optJSONObject("data");
        if (d == null) return "";

        Vod v = new Vod();
        v.setVodId(String.valueOf(d.optInt("id")));
        v.setVodName(d.optString("title"));
        v.setVodPic(pic(d));
        v.setVodContent(d.optString("summary"));
        v.setVodRemarks(d.optString("bottomLabel"));
        v.setVodArea(joinNames(d.optJSONArray("area")));
        v.setVodYear(yearOf(d));
        v.setVodActor(joinNames(d.optJSONArray("actors")));
        v.setVodDirector(joinNames(d.optJSONArray("directors")));

        // 线路 + 剧集直链
        List<String> froms = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        JSONArray sources = d.optJSONArray("playSources");
        if (sources != null) {
            for (int i = 0; i < sources.length(); i++) {
                JSONObject s = sources.optJSONObject(i);
                if (s == null) continue;
                JSONArray eps = s.optJSONArray("list");
                if (eps == null || eps.length() == 0) continue;
                List<String> epList = new ArrayList<>();
                for (int j = 0; j < eps.length(); j++) {
                    JSONObject ep = eps.optJSONObject(j);
                    if (ep == null) continue;
                    JSONArray pu = ep.optJSONArray("playUrls");
                    if (pu == null || pu.length() == 0) continue;
                    JSONObject u = pu.optJSONObject(0);
                    if (u == null) continue;
                    String link = u.optString("url");
                    if (link.isEmpty()) continue;
                    String name = ep.optString("title");
                    if (name.isEmpty()) name = "第" + (j + 1) + "集";
                    epList.add(name + "$" + link);
                }
                if (epList.isEmpty()) continue;
                froms.add(s.optString("name"));
                urls.add(join(epList, "#"));
            }
        }
        if (froms.isEmpty()) {
            SpiderDebug.log("[毒舌] detail 无可用线路 id=" + id);
            return "";
        }
        v.setVodPlayFrom(join(froms, "$$$"));
        v.setVodPlayUrl(join(urls, "$$$"));

        List<Vod> list = new ArrayList<>();
        list.add(v);
        SpiderDebug.log("[毒舌] detail id=" + id + " 线路=" + froms.size()
                + " 集数=" + urls.get(0).split("#").length);
        return Result.get().vod(list).string();
    }

    private static String yearOf(JSONObject d) {
        JSONObject y = d.optJSONObject("year");
        if (y != null) return y.optString("id");
        return "";
    }

    private static String joinNames(JSONArray arr) {
        if (arr == null) return "";
        List<String> l = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String n = o.optString("name");
            if (!n.isEmpty()) l.add(n);
        }
        return join(l, ",");
    }

    // ==================== 搜索 ====================

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("channelId", "0");
        p.put("k", key);
        p.put("next", "");
        p.put("userChannel", CHANNEL);

        JSONObject o = api(HOST_LOGIC, "/vod/search/query", p);
        if (o == null || o.optInt("code") != 200) {
            SpiderDebug.log("[毒舌] search 失败 key=" + key);
            return "";
        }
        JSONObject d = o.optJSONObject("data");
        if (d == null) return "";
        JSONArray items = d.optJSONArray("items");
        List<Vod> vods = new ArrayList<>();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null || isAdultItem(it) || isAd(it)) continue;
                String name = stripHtml(it.optString("title"));
                if (isAdult(name)) continue;
                Vod v = new Vod();
                v.setVodId(String.valueOf(it.optInt("id")));
                v.setVodName(name);
                v.setVodPic(pic(it));
                v.setVodRemarks(it.optString("bottomLabel"));
                vods.add(v);
            }
        }
        SpiderDebug.log("[毒舌] search key=" + key + " out=" + vods.size());
        return Result.get().vod(vods).string();
    }

    private static String stripHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", "");
    }

    // ==================== 播放 ====================

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        JSONObject o = new JSONObject();
        o.put("parse", 0);
        o.put("playUrl", "");
        o.put("url", id);
        JSONObject h = new JSONObject();
        h.put("user-agent", UA);
        o.put("header", h.toString());
        SpiderDebug.log("[毒舌] play flag=" + flag + " url=" + id);
        return o.toString();
    }
}
