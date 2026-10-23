package com.github.catvod.spider;

import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
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
    private static final String APP_VER = "1.1.25";
    private static final String APP_VC = "103";
    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String FIELDS = "vod_id,vod_name,vod_sub,vod_pic,vod_actor,vod_director,vod_score,"
            + "vod_remarks,vod_blurb,vod_year,vod_tag,vod_class,vod_area,type_id,type_id_1";
    private static final int LIMIT = 21;

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
        // 解析失败时退回原地址(部分源本身就是 m3u8 直链)
        return Result.get().url(playUrl).string();
    }
}
