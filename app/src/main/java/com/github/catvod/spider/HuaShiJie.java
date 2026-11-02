package com.github.catvod.spider;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 华视界影视 (huasj.mobile.com) CatVod 蜘蛛
 * <p>
 * 数据源: https://api.huashijie.tv/api.php/   （全部 GET，明文，无签名、无加密，gzip 压缩）
 * <p>
 * 接口（2026-11-01 MITM 实锤 + PC 直连复现）:
 * v2.vod/androidtypes         无参                      → [{type_id,type_name,classes[],areas[],years[]}]  1电影 2连续剧 3综艺 4动漫
 * v2.vod/androidfilter        type_id+page+area+year+class → 列表，20 条/页（data 是数组，无 total）
 * v2.vod/androiddetail        vod_id                    → data.{...,urls:[{type,key,url}]}
 * v2.vod/androidsearch10086   wd+page                   → 列表
 * v2.main/androidhome         无参                      → 首页推荐（壳子定制不要推荐流，忽略）
 * <p>
 * 关键事实（实测）:
 * 1. 全部明文 GET，无签名无加密。UA 用 okhttp/5.0.0-alpha.14 复现抓包。
 * 2. 详情 urls[] 直接是 m3u8 直链，无需二次解析、无需 302 追踪 —— playerContent 直接 parse=0。
 * 3. urls 形态: 电影 = 1 条（key 如"蓝光"，整部）；连续剧 = N 条（key 为"01".."NN"集号，每集一个 m3u8）。
 *    统一处理: 单条当整部；多条数字 key 当分集（同一 CDN 线路）。
 * 4. androidfilter 的 data 是数组、无 total/pageinfo，只能用"本页是否满 20 条"推下一页。
 * 5. area=大陆 / year=2025 等筛选参数实测生效。
 * <p>
 * 定制（凯哥 2026-11-01 指定）: 首页不要推荐流（list 恒 []）；国产剧置顶并锁 area=大陆；
 * 过滤成人内容；搜索完全匹配（服务端逐字模糊，本地再筛一遍）。
 */
public class HuaShiJie extends Spider {

    private static final String BASE = "https://api.huashijie.tv/api.php";
    private static final String UA = "okhttp/5.0.0-alpha.14";

    /** 服务端每页固定 20 条，data 无 total，靠"满页"推下一页 */
    private static final int PAGE_SIZE = 20;

    /**
     * 顶层分类：国产剧置顶并锁定 area=大陆，用 "type_id_地区" 复合编码表达。
     * androidtypes 的 type_id 顺序: 1电影 2连续剧 3综艺 4动漫。
     */
    private static final String[][] CATES = {
            {"2_大陆", "国产剧"},
            {"1", "电影"},
            {"2", "连续剧"},
            {"3", "综艺"},
            {"4", "动漫"},
    };

    /** 筛选维度（华视界只有 class/area/year，无 lang/sort） */
    private static final String[][] DIM = {
            {"class", "类型"},
            {"area", "地区"},
            {"year", "年份"},
    };

    /** 成人/敏感内容过滤词 */
    private static final String[] BLOCK = {
            "色情", "情色", "成人片", "三级片", "伦理片", "男同", "女同", "同性", "淫", "裸体", "AV片", "福利"
    };

    private String host = BASE;

    /** androidtypes 缓存: type_id(String) -> {classes:[],areas:[],years:[]} */
    private JSONObject typeExtendCache;

    // ==================== 生命周期 ====================

    @Override
    public void init(Context context, String extend) throws Exception {
        if (extend == null || extend.trim().isEmpty()) return;
        try {
            JSONObject j = new JSONObject(extend.trim());
            if (j.has("host")) {
                host = j.getString("host");
                while (host.endsWith("/")) host = host.substring(0, host.length() - 1);
            }
        } catch (Exception ignored) {
        }
    }

    // ==================== 基础设施 ====================

    private void log(String msg) {
        try {
            SpiderDebug.log("[华视界] " + msg);
        } catch (Exception ignored) {
        }
    }

    private Map<String, String> header() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        return h;
    }

    /** GET 请求并取 data 段；data 为数组时包成 {list:[]}，为对象时原样返回；失败返回 null */
    private JSONObject getJson(String path, Map<String, String> params) {
        try {
            OkResult res = OkHttp.get(host + path,
                    params == null ? new HashMap<String, String>() : params, header());
            String body = res.getBody();
            if (body == null || body.trim().isEmpty()) {
                log(path + " 空响应");
                return null;
            }
            JSONObject o;
            try {
                o = new JSONObject(body);
            } catch (Exception e) {
                log(path + " 非 JSON: " + body.substring(0, Math.min(120, body.length())));
                return null;
            }
            if (o.optInt("code", -1) != 200) {
                log(path + " code=" + o.optInt("code", -1) + " msg=" + o.optString("msg"));
                return null;
            }
            Object data = o.opt("data");
            if (data instanceof JSONObject) return (JSONObject) data;
            if (data instanceof JSONArray) {
                JSONObject w = new JSONObject();
                w.put("list", data);
                return w;
            }
            log(path + " data 既非对象也非数组");
            return null;
        } catch (Exception e) {
            log(path + " 异常 " + e);
            return null;
        }
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static int toInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String norm(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\u3000·・:：!！?？,，.。'\"“”‘’\\-—_()（）\\[\\]【】《》<>~～]", "").toLowerCase();
    }

    /** 完全匹配：片名与关键词一致，或只差一个季/部/集后缀（庆余年 vs 庆余年第二季） */
    private static boolean exactHit(String name, String key) {
        String n = norm(name), k = norm(key);
        if (k.isEmpty()) return true;
        if (n.equals(k)) return true;
        if (!n.startsWith(k)) return false;
        String rest = n.substring(k.length());
        return rest.isEmpty()
                || rest.matches("^(第)?[0-9一二三四五六七八九十]+(季|部|集|期)?$")
                || rest.matches("^[ivx]+$");
    }

    private static boolean isAdult(String name, String cls) {
        String s = (name == null ? "" : name) + (cls == null ? "" : cls);
        for (String w : BLOCK) {
            if (s.contains(w)) return true;
        }
        return false;
    }

    // ==================== 分类 / 筛选 ====================

    private JSONObject typeExtend() {
        if (typeExtendCache != null) return typeExtendCache;
        JSONObject out = new JSONObject();
        JSONObject d = getJson("/v2.vod/androidtypes", null);
        if (d == null) {
            log("androidtypes 失败，筛选项降级为空");
            typeExtendCache = out;
            return out;
        }
        JSONArray arr = d.optJSONArray("list");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.optJSONObject(i);
                if (t == null) continue;
                String tid = t.optString("type_id", "");
                if (tid.isEmpty()) continue;
                JSONObject ext = new JSONObject();
                ext.put("classes", t.optJSONArray("classes"));
                ext.put("areas", t.optJSONArray("areas"));
                ext.put("years", t.optJSONArray("years"));
                out.put(tid, ext);
            }
        }
        typeExtendCache = out;
        log("androidtypes 加载完成，共 " + out.length() + " 个分类");
        return out;
    }

    private static JSONArray toVals(JSONArray src) {
        JSONArray vals = new JSONArray();
        JSONObject all = new JSONObject();
        all.put("n", "全部");
        all.put("v", "");
        vals.put(all);
        if (src != null) {
            for (int i = 0; i < src.length(); i++) {
                String v = src.optString(i, "");
                if (v.isEmpty()) continue;
                JSONObject o = new JSONObject();
                o.put("n", v);
                o.put("v", v);
                vals.put(o);
            }
        }
        return vals;
    }

    private JSONObject buildFilters() {
        JSONObject out = new JSONObject();
        JSONObject types = typeExtend();
        try {
            for (String[] c : CATES) {
                String tid = c[0];
                boolean locked = tid.contains("_");
                String base = locked ? tid.substring(0, tid.indexOf('_')) : tid;
                JSONObject ext = types.optJSONObject(base);
                JSONArray dims = new JSONArray();
                for (String[] dn : DIM) {
                    String key = dn[0];
                    // 国产剧频道已锁地区，不再给地区维度
                    if (locked && "area".equals(key)) continue;
                    if (ext == null) continue;
                    JSONArray src = ext.optJSONArray(key + "es"); // classes / areas / years
                    if (src == null || src.length() == 0) continue;
                    JSONObject dim = new JSONObject();
                    dim.put("key", key);
                    dim.put("name", dn[1]);
                    dim.put("value", toVals(src));
                    dims.put(dim);
                }
                out.put(tid, dims);
            }
        } catch (Exception e) {
            log("buildFilters " + e);
        }
        return out;
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
        // 凯哥定制: 首页不要推荐流，只给分类 + 筛选项
        JSONObject root = new JSONObject();
        root.put("class", classes);
        root.put("filters", buildFilters());
        root.put("list", new JSONArray());
        return root.toString();
    }

    // ==================== 列表 ====================

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend)
            throws Exception {
        String type = tid;
        String forcedArea = null;
        if (tid != null && tid.contains("_")) {
            type = tid.substring(0, tid.indexOf('_'));
            forcedArea = tid.substring(tid.indexOf('_') + 1);
        }
        int page = toInt(pg, 1);

        Map<String, String> p = new LinkedHashMap<>();
        p.put("type_id", type);
        p.put("page", String.valueOf(page));
        if (forcedArea != null) p.put("area", forcedArea);

        if (extend != null) {
            for (String k : new String[]{"class", "area", "year"}) {
                if (forcedArea != null && "area".equals(k)) continue;
                String v = extend.get(k);
                if (v != null && !v.trim().isEmpty() && !"全部".equals(v.trim())) p.put(k, v.trim());
            }
        }

        JSONObject d = getJson("/v2.vod/androidfilter", p);
        List<Vod> list = new ArrayList<>();
        if (d != null) {
            JSONArray arr = d.optJSONArray("list");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    if (it == null) continue;
                    String name = it.optString("name", "");
                    if (isAdult(name, it.optString("className", ""))) continue;
                    Vod v = new Vod();
                    v.setVodId(it.optString("id"));
                    v.setVodName(name);
                    v.setVodPic(it.optString("pic"));
                    String year = it.optString("year", "");
                    String upd = it.optString("updateInfo", "");
                    v.setVodRemarks(upd.isEmpty() ? year : upd);
                    v.setVodYear(year);
                    v.setVodArea(it.optString("area"));
                    list.add(v);
                }
            }
        }
        // 无 total，满 20 条才有下一页
        boolean more = list.size() >= PAGE_SIZE;
        int pageCount = more ? page + 1 : page;
        int total = (page - 1) * PAGE_SIZE + list.size();
        log("category tid=" + tid + " page=" + page + " n=" + list.size() + " more=" + more);
        return Result.get().vod(list).page(page, pageCount, PAGE_SIZE, total).string();
    }

    // ==================== 详情 ====================

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return "";
        String id = ids.get(0).trim();
        JSONObject d = getJson("/v2.vod/androiddetail", map("vod_id", id));
        if (d == null) return "";

        String name = d.optString("name", "");
        if (isAdult(name, d.optString("className", ""))) return "";

        Vod v = new Vod();
        v.setVodId(d.optString("id", id));
        v.setVodName(name);
        v.setVodPic(d.optString("pic"));
        v.setVodYear(d.optString("year"));
        v.setVodArea(d.optString("area"));
        v.setVodActor(d.optString("actor"));
        v.setVodDirector(d.optString("director"));
        String cls = d.optString("className", "");
        v.setTypeName(cls);
        v.setVodTag(cls);
        String content = d.optString("content", "").replaceAll("<[^>]+>", "").trim();
        v.setVodContent(content);
        v.setVodRemarks(d.optString("state"));

        // urls[]: 电影 1 条（整部）、连续剧 N 条（集号 key，每集 m3u8）。统一当单 CDN 线路处理。
        List<String> epList = new ArrayList<>();
        JSONArray urls = d.optJSONArray("urls");
        if (urls != null) {
            for (int i = 0; i < urls.length(); i++) {
                JSONObject u = urls.optJSONObject(i);
                if (u == null) continue;
                String uu = u.optString("url", "");
                if (!uu.startsWith("http")) continue;
                String k = u.optString("key", "");
                String ep;
                if (k.matches("\\d+")) ep = "第" + k + "集";
                else if (k.isEmpty()) ep = "第" + (i + 1) + "集";
                else ep = k;
                epList.add(ep + "$" + uu);
            }
        }
        v.setVodPlayFrom("华视界");
        v.setVodPlayUrl(join(epList, "#"));
        log("detail id=" + id + " name=" + name + " 播放地址=" + epList.size());
        return Result.get().vod(v).string();
    }

    // ==================== 搜索 ====================

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        int page = toInt(pg, 1);
        JSONObject d = getJson("/v2.vod/androidsearch10086", map("wd", key, "page", String.valueOf(page)));
        List<JSONObject> cands = new ArrayList<>();
        if (d != null) {
            JSONArray arr = d.optJSONArray("list");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    if (it == null) continue;
                    String name = it.optString("name", "");
                    if (isAdult(name, it.optString("className", ""))) continue;
                    cands.add(it);
                }
            }
        }
        // 服务端逐字模糊匹配，本地只留完全匹配；不相干的一律丢掉（凯哥要求）
        List<JSONObject> pick = new ArrayList<>();
        for (JSONObject it : cands) {
            if (exactHit(it.optString("name"), key)) pick.add(it);
        }

        List<Vod> list = new ArrayList<>();
        for (JSONObject it : pick) {
            Vod v = new Vod();
            v.setVodId(it.optString("id"));
            v.setVodName(it.optString("name"));
            v.setVodPic(it.optString("pic"));
            String year = it.optString("year", "");
            v.setVodRemarks(year);
            v.setVodYear(year);
            v.setVodArea(it.optString("area"));
            list.add(v);
        }
        log("search " + key + " page=" + page + " 服务端=" + cands.size() + " 完全匹配=" + list.size());
        return Result.get().vod(list).string();
    }

    // ==================== 播放 ====================

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (id == null || id.trim().isEmpty()) return Result.error("播放地址为空");
        String url = id.trim();
        // 详情里拼的就是 m3u8 直链，直接 parse=0 丢给壳子播，不走解析
        if (url.startsWith("http")) {
            Result r = Result.get().url(url).parse(0);
            if (url.toLowerCase().contains("m3u8")) r.m3u8();
            return r.string();
        }
        return Result.error("播放地址异常");
    }
}对接口没有分类标题
