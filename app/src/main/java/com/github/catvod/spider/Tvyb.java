package com.github.catvod.spider;

import android.content.Context;
import android.util.Base64;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * TVB云播 (com.hktvyb.app) —— 点播爬虫
 *
 * 接口（均 POST form，无签名）：
 *   /api/vod/init       首页初始化：type_list(分类+filter_type_list 筛选项) / banner_list / hot_search_list
 *   /api/vod/rankList   分类片单：type_id + pg          → rank_list
 *   /api/vod/searchList 搜索：keywords + pg             → search_list
 *   /api/vod/vodDetail  详情：vod_id                    → vod + player_source_list + vod_play_url_list
 *   /api/vod/vodParse   播放解析：vod_id + player_source_id(数字id) + episode_index → {play_url}
 *
 * 响应：{"code":0,"msg":"","data":"<base64(AES-128-CBC/PKCS5)>"}
 * 密码：key = IV = MickwrKuMickwrKu（libnmmp.so 静态定位 + hook 双向实锤）
 *
 * 说明：
 *   1) 服务端 rankList 只认 type_id + pg，筛选/排序参数原样忽略（实测 class/area/
 *      lang/year/sort/order/by 全部无效），故筛选与排序在本地完成——rank_list 条目
 *      自带 vod_class / vod_area / vod_lang / vod_year / vod_hits(_day/week/month) /
 *      vod_score 字段，筛选项取 init 的 filter_type_list。
 *   2) 播放地址：详情 vod_play_url_list 的 url 全为空，必须调 vodParse。
 *      player_source_id 必须传 player_source_list 的数字 id（1/14/13...），
 *      传 player_code 字符串（"mp4"/"co"）服务端返回"参数错误"。
 *   3) 站点 ext 可覆盖默认域名：
 *      {"host":"http://app.tuxianimg.com"}            默认
 *      {"host":"http://104.233.148.70"}               本机 DNS 解析不了域名时走 IP（自动带 Host 头）
 */
public class Tvyb extends Spider {

    private static final String KEY = "MickwrKuMickwrKu"; // key = IV
    private static final String DEVICE_ID = "29227c815e45939da8cb0bbd13f3a3cde";
    private static final String DEFAULT_HOST = "app.tuxianimg.com";
    private static final String DEFAULT_IP = "104.233.148.70";

    /** 本地筛选支持的 key → rank_list 条目的对应字段 */
    private static final String[] FILTER_KEYS = {"class", "area", "lang", "year", "sort"};

    private String base = "http://" + DEFAULT_HOST;
    private String hostHeader = DEFAULT_HOST; // 走 IP 直连时用于 Host 头

    @Override
    public void init(Context context, String extend) throws Exception {
        if (extend != null) {
            String host = extend.trim();
            if (!host.isEmpty()) {
                try {
                    JSONObject j = new JSONObject(host);
                    if (j.has("host")) host = j.getString("host");
                } catch (Exception ignored) {
                    // ext 直接就是地址
                }
                while (host.endsWith("/")) host = host.substring(0, host.length() - 1);
                if (host.startsWith("http")) {
                    base = host;
                    String h = host.substring(host.indexOf("//") + 2);
                    int slash = h.indexOf('/');
                    hostHeader = slash > 0 ? h.substring(0, slash) : h;
                }
            }
        }
    }

    // ---------- 基础设施 ----------

    private void logger(String msg) {
        try {
            SpiderDebug.log("[Tvyb] " + msg);
        } catch (Exception ignored) {
        }
    }

    private Map<String, String> header() {
        Map<String, String> h = new HashMap<>();
        h.put("App-Device-Id", DEVICE_ID);
        h.put("App-Os-Type", "android");
        h.put("App-Ui-Mode", "light");
        h.put("App-Version-Code", "100");
        h.put("User-Agent", "okhttp/3.14.9");
        if (isIp(hostHeader)) h.put("Host", hostHeader);
        return h;
    }

    private static boolean isIp(String s) {
        if (s == null || s.isEmpty()) return false;
        String[] p = s.split("\\.");
        if (p.length != 4) return false;
        for (int i = 0; i < 4; i++) {
            try {
                int n = Integer.parseInt(p[i]);
                if (n < 0 || n > 255) return false;
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    private String decrypt(String data) throws Exception {
        if (data == null || data.trim().isEmpty()) return "";
        byte[] ct = Base64.decode(data.replaceAll("\\s+", ""), Base64.NO_WRAP);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY.getBytes("UTF-8"), "AES"), new IvParameterSpec(KEY.getBytes("UTF-8")));
        return new String(c.doFinal(ct), "UTF-8");
    }

    /** 调接口并返回解密后的内层 JSON；失败/空数据返回空 JSONObject */
    private JSONObject api(String action, Map<String, String> params) throws Exception {
        OkResult res = OkHttp.post(base + "/api/vod/" + action, params, header());
        int code = res.getCode();
        String body = res.getBody();
        logger(action + " → HTTP " + code + " len=" + (body == null ? 0 : body.length()) + (body != null && body.length() > 120 ? " head=" + body.substring(0, 120) : ""));
        if (body == null || body.trim().isEmpty()) {
            // 域名解析失败兜底：走 IP + Host 头
            if (!isIp(hostHeader)) {
                logger(action + " 响应为空，尝试 IP 直连兜底");
                hostHeader = DEFAULT_IP;
                Map<String, String> h = header();
                res = OkHttp.post("http://" + DEFAULT_IP + "/api/vod/" + action, params, h);
                code = res.getCode();
                body = res.getBody();
                logger(action + " IP兜底 → HTTP " + code + " len=" + (body == null ? 0 : body.length()));
            }
        }
        if (body == null || body.trim().isEmpty()) {
            logger(action + " 响应为空（网络失败或被拦截）");
            return new JSONObject();
        }
        JSONObject outer;
        try {
            outer = new JSONObject(body);
        } catch (Exception e) {
            logger(action + " 响应非 JSON: " + body.substring(0, Math.min(120, body.length())));
            return new JSONObject();
        }
        Object data = outer.opt("data");
        if (!(data instanceof String)) {
            logger(action + " data 非字符串（code=" + outer.opt("code") + " msg=" + outer.opt("msg") + "）");
            return new JSONObject();
        }
        String pt;
        try {
            pt = decrypt((String) data);
        } catch (Exception e) {
            logger(action + " 解密失败: " + e.getMessage());
            return new JSONObject();
        }
        if (pt.isEmpty()) {
            logger(action + " 解密结果为空");
            return new JSONObject();
        }
        return new JSONObject(pt);
    }

    private Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private List<Vod> toVods(JSONArray arr) {
        List<Vod> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Vod v = new Vod();
            v.setVodId(o.optString("vod_id"));
            v.setVodName(o.optString("vod_name"));
            v.setVodPic(o.optString("vod_pic"));
            v.setVodRemarks(o.optString("vod_remarks"));
            v.setVodYear(o.optString("vod_year"));
            v.setVodArea(o.optString("vod_area"));
            v.setVodActor(o.optString("vod_actor"));
            v.setVodDirector(o.optString("vod_director"));
            String blurb = o.optString("vod_blurb");
            if (blurb.isEmpty()) blurb = o.optString("vod_content");
            v.setVodContent(blurb);
            list.add(v);
        }
        return list;
    }

    // ---------- 首页 ----------

    @Override
    public String homeContent(boolean filter) throws Exception {
        JSONObject init = api("init", new HashMap<String, String>());
        JSONArray types = init.optJSONArray("type_list");

        // 分类排序：剧集（国产剧集）→ 全部 → 电影 → 综艺 → 动漫 → 短剧，其余按原序垫后
        List<JSONObject> sorted = new ArrayList<>();
        if (types != null) {
            for (int i = 0; i < types.length(); i++) {
                JSONObject t = types.optJSONObject(i);
                if (t != null) sorted.add(t);
            }
        }
        Collections.sort(sorted, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return typeOrder(a.optString("type_id")) - typeOrder(b.optString("type_id"));
            }
        });

        // 手动拼返回 JSON（class + filters，list 故意为空 = 去掉首页推荐）
        StringBuilder sb = new StringBuilder("{\"class\":[");
        for (int i = 0; i < sorted.size(); i++) {
            JSONObject t = sorted.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"type_id\":\"").append(t.optString("type_id"))
              .append("\",\"type_name\":\"").append(t.optString("type_name")).append("\"}");
        }
        sb.append("]");
        // filters：逐分类带筛选项（key 与 extend 回传 key 一致）
        JSONObject filters = new JSONObject();
        try {
            for (int i = 0; i < sorted.size(); i++) {
                JSONObject t = sorted.get(i);
                JSONArray ftl = t.optJSONArray("filter_type_list");
                if (ftl == null || ftl.length() == 0) continue;
                JSONArray arr = new JSONArray();
                for (int j = 0; j < ftl.length(); j++) {
                    JSONObject f = ftl.optJSONObject(j);
                    if (f == null) continue;
                    String key = f.optString("name");
                    if (key.isEmpty()) continue;
                    JSONArray vals = new JSONArray();
                    JSONArray opts = f.optJSONArray("list");
                    if (opts != null) {
                        for (int k = 0; k < opts.length(); k++) {
                            String n = opts.optString(k);
                            if (n == null || n.isEmpty()) continue;
                            vals.put(new JSONObject().put("n", n).put("v", n));
                        }
                    }
                    arr.put(new JSONObject().put("key", key).put("name", filterName(key)).put("value", vals));
                }
                if (arr.length() > 0) filters.put(t.optString("type_id"), arr);
            }
        } catch (Exception e) {
            logger("filters 组装失败: " + e.getMessage());
        }
        sb.append(",\"filters\":").append(filters.toString());
        sb.append(",\"list\":[]}");
        logger("homeContent: classes=" + sorted.size() + " filters_keys=" + filters.length() + "（已去推荐）");
        return sb.toString();
    }

    /** 分类显示顺序权重，越小越前 */
    private static int typeOrder(String id) {
        if (id == null) return 99;
        if ("2".equals(id)) return 0;   // 剧集（国产剧集）
        if ("0".equals(id)) return 1;   // 全部
        if ("1".equals(id)) return 2;   // 电影
        if ("3".equals(id)) return 3;   // 综艺
        if ("4".equals(id)) return 4;   // 动漫
        if ("5".equals(id)) return 5;   // 短剧
        return 90;
    }

    private static String filterName(String key) {
        if ("class".equals(key)) return "类型";
        if ("area".equals(key)) return "地区";
        if ("lang".equals(key)) return "语言";
        if ("year".equals(key)) return "年份";
        if ("sort".equals(key)) return "排序";
        return key;
    }

    // ---------- 分类（本地筛选 + 本地排序） ----------

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = 1;
        try {
            page = Integer.parseInt(pg);
        } catch (Exception ignored) {
        }
        // 从 extend 取本地筛选值（"全部"/空 视为不筛）
        final Map<String, String> cond = new HashMap<>();
        if (extend != null) {
            for (int i = 0; i < FILTER_KEYS.length; i++) {
                String v = extend.get(FILTER_KEYS[i]);
                if (v != null && !v.trim().isEmpty() && !"全部".equals(v.trim())) {
                    cond.put(FILTER_KEYS[i], v.trim());
                }
            }
        }

        // 服务端 rankList 固定只下发 20 条且 pg/limit 均无效（实测 pg=1..50、limit/pageSize
        // 等参数全部返回同一份数据），故只请求一次，不做本地翻页；more 固定为 false。
        JSONObject d = api("rankList", map("type_id", tid, "pg", String.valueOf(page)));
        JSONArray arr = d.optJSONArray("rank_list");
        List<JSONObject> items = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && match(o, cond) && !dup(items, o)) items.add(o);
            }
        }
        sortItems(items, cond.get("sort"));

        JSONArray out = new JSONArray();
        for (int i = 0; i < items.size(); i++) out.put(items.get(i));
        boolean more = false; // 服务端只下发一页（20 条），无更多页
        logger("categoryContent tid=" + tid + " cond=" + cond + " → " + items.size() + " 条（服务端固定一页）");
        return Result.get().vod(toVods(out)).page(page, page, 20, 20).string();
    }

    /** 按 vod_id 去重（服务端偶发重复下发） */
    private static boolean dup(List<JSONObject> items, JSONObject o) {
        String id = o.optString("vod_id");
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(items.get(i).optString("vod_id"))) return true;
        }
        return false;
    }

    /** 本地筛选：class/area/lang 为包含匹配，year 为相等 */
    private static boolean match(JSONObject o, Map<String, String> cond) {
        for (Map.Entry<String, String> e : cond.entrySet()) {
            String k = e.getKey(), v = e.getValue();
            if ("year".equals(k)) {
                if (!v.equals(o.optString("vod_year"))) return false;
            } else if ("class".equals(k)) {
                if (!o.optString("vod_class").contains(v)) return false;
            } else if ("area".equals(k)) {
                if (!o.optString("vod_area").contains(v)) return false;
            } else if ("lang".equals(k)) {
                if (!o.optString("vod_lang").contains(v)) return false;
            } else if ("sort".equals(k)) {
                // 排序不是过滤条件
            }
        }
        return true;
    }

    /** 本地排序：最新=原序；最热=vod_hits；最赞=vod_score；日/周/月榜=hits_* */
    private static void sortItems(List<JSONObject> items, final String sort) {
        if (sort == null || sort.isEmpty() || "最新".equals(sort)) return;
        final String field;
        if ("最热".equals(sort)) field = "vod_hits";
        else if ("最赞".equals(sort)) field = "vod_score";
        else if ("日榜".equals(sort)) field = "vod_hits_day";
        else if ("周榜".equals(sort)) field = "vod_hits_week";
        else if ("月榜".equals(sort)) field = "vod_hits_month";
        else return;
        Collections.sort(items, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Double.compare(num(b, field), num(a, field));
            }
        });
    }

    private static double num(JSONObject o, String field) {
        try {
            return Double.parseDouble(o.optString(field, "0"));
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- 搜索 ----------

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        int page = 1;
        try {
            page = Integer.parseInt(pg);
        } catch (Exception ignored) {
        }
        JSONObject d = api("searchList", map("keywords", key, "pg", String.valueOf(page)));
        JSONArray arr = d.optJSONArray("search_list");
        List<Vod> vods = toVods(arr);
        boolean more = vods.size() >= 20;
        logger("searchContent key=" + key + " pg=" + page + " → " + vods.size() + " 条");
        return Result.get().vod(vods).page(page, more ? page + 1 : page, 20, 20).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    // ---------- 详情 ----------

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return Result.error("缺少视频ID");
        JSONObject d = api("vodDetail", map("vod_id", ids.get(0)));
        JSONObject vj = d.optJSONObject("vod");
        if (vj == null) return Result.error("详情获取失败");
        Vod vod = new Vod();
        vod.setVodId(vj.optString("vod_id"));
        vod.setVodName(vj.optString("vod_name"));
        vod.setVodPic(vj.optString("vod_pic"));
        vod.setVodRemarks(vj.optString("vod_remarks"));
        vod.setVodYear(vj.optString("vod_year"));
        vod.setVodArea(vj.optString("vod_area"));
        vod.setVodActor(vj.optString("vod_actor"));
        vod.setVodDirector(vj.optString("vod_director"));
        String blurb = vj.optString("vod_blurb");
        if (blurb.isEmpty()) blurb = vj.optString("vod_content");
        vod.setVodContent(blurb);

        // 播放源按 sort 升序（sort 小的排前，如"极速主力" sort=0）
        List<JSONObject> srcs = new ArrayList<>();
        JSONArray srcArr = d.optJSONArray("player_source_list");
        if (srcArr != null) {
            for (int i = 0; i < srcArr.length(); i++) {
                JSONObject s = srcArr.optJSONObject(i);
                if (s != null) srcs.add(s);
            }
        }
        Collections.sort(srcs, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Integer.compare(a.optInt("sort", 99), b.optInt("sort", 99));
            }
        });

        // 全部源的数字 id（playerContent 解析失败时按序 fallback）
        StringBuilder idList = new StringBuilder();
        for (int i = 0; i < srcs.size(); i++) {
            if (i > 0) idList.append(";");
            idList.append(srcs.get(i).optString("id"));
        }

        // player_code -> 剧集列表
        Map<String, JSONArray> code2urls = new HashMap<>();
        JSONArray plays = d.optJSONArray("vod_play_url_list");
        if (plays != null) {
            for (int i = 0; i < plays.length(); i++) {
                JSONObject p = plays.optJSONObject(i);
                if (p != null) code2urls.put(p.optString("player_code"), p.optJSONArray("urls"));
            }
        }

        List<String> froms = new ArrayList<>();
        List<String> urlGroups = new ArrayList<>();
        for (int i = 0; i < srcs.size(); i++) {
            JSONObject s = srcs.get(i);
            String name = s.optString("player_name");
            if (name.isEmpty()) name = "线路" + s.optString("id");
            JSONArray urls = code2urls.get(s.optString("player_code"));
            if (urls == null || urls.length() == 0) continue;
            List<String> eps = new ArrayList<>();
            for (int j = 0; j < urls.length(); j++) {
                JSONObject u = urls.optJSONObject(j);
                if (u == null) continue;
                int idx = u.optInt("episode_index", j);
                String epName = u.optString("name");
                if (epName.isEmpty()) epName = String.format("第%02d集", idx + 1);
                String url = u.optString("url");
                // url 非空 = 详情直发直链；为空 = 占位（playerContent 调 vodParse，源列表 fallback）
                String playId = (url != null && url.startsWith("http"))
                        ? url : (vj.optString("vod_id") + "@" + idx + "@" + idList);
                eps.add(epName + "$" + playId);
            }
            froms.add(name);
            urlGroups.add(join(eps, "#"));
        }
        vod.setVodPlayFrom(join(froms, "$$$"));
        vod.setVodPlayUrl(join(urlGroups, "$$$"));
        logger("detailContent vod=" + ids.get(0) + " 源数=" + froms.size() + "（源id: " + idList + "）");
        return Result.get().vod(vod).string();
    }

    // ---------- 播放 ----------

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (id == null || id.isEmpty()) return Result.error("播放地址为空");
        if (id.startsWith("http")) {
            Result r = Result.get().url(id);
            if (id.contains("m3u8")) r.m3u8();
            return r.string();
        }
        // 占位 id: vod_id@episode_index@id1;id2;id3...
        String[] a = id.split("@");
        if (a.length < 3 || a[2].isEmpty()) return Result.error("播放信息不完整");
        String vid = a[0], idx = a[1];
        String[] sourceIds = a[2].split(";");
        String lastErr = "";
        for (int i = 0; i < sourceIds.length; i++) {
            String sid = sourceIds[i].trim();
            if (sid.isEmpty()) continue;
            try {
                JSONObject d = api("vodParse", map("vod_id", vid, "player_source_id", sid, "episode_index", idx));
                String url = d.optString("play_url");
                if (url != null && url.startsWith("http")) {
                    logger("playerContent vod=" + vid + " ep=" + idx + " 源=" + sid + " → " + url.substring(0, Math.min(80, url.length())));
                    Result r = Result.get().url(url);
                    if (url.contains("m3u8")) r.m3u8();
                    return r.string();
                }
                lastErr = "源" + sid + "返回空地址";
            } catch (Exception e) {
                lastErr = "源" + sid + "异常: " + e.getMessage();
            }
        }
        logger("playerContent vod=" + vid + " ep=" + idx + " 全部源失败: " + lastErr);
        return Result.error("解析失败（" + sourceIds.length + " 个播放源均无地址）");
    }

    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
