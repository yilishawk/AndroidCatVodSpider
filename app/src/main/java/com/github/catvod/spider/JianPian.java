package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 荐片 (com.ama.media.jp224) —— 全明文接口，无签名
 *
 * 抓包来源：WebView 调试端口 (webview_devtools_remote) + CDP Network，
 * 参数与取值均已逐项点击实测（见 opt_map.json / filter_options.json）。
 *
 * 接口一览：
 *   分类      GET /api/v2/settings/topCategory
 *              → [{id:1,name:电影},{id:2,电视剧},{id:67,短剧},{id:3,动漫},{id:4,综艺},{id:88,推荐}]
 *   筛选项    GET /api/crumb/filterOptions?fcate_pid=N
 *              → [{key:"type",data:[{id,name}]},{key:"area"},{key:"year"},{key:"sort"}]
 *              短剧(67) 返回 {key:"category_id"} + {key:"sort"}，维度不同
 *   列表      GET /api/crumb/list?fcate_pid=&category_id=&area=&year=&type=&sort=&page=
 *              每页固定 15 条，page 稳定递增（实测 page=50 仍有数据），无 total 字段
 *   短剧列表  GET /api/crumb/shortList?fcate_pid=67&category_id=&sort=&page=
 *   长视频详情 GET /api/video/detail?id=N
 *              → source_list_source[] / vip_source_list_source[]
 *                每条线路 {name, source_list:[{source_name, url(m3u8)}]}
 *   短剧详情  GET /api/detail?vid=N&type=1
 *              → playlist:[{title:"第1集", url(mp4)}] + url(当前集)
 *   搜索      GET /api/v2/search/videoV2?key=&page=&pageSize=20
 *   图片      https://img.cqkgy.com + 相对路径（域名来自 /api/v2/settings/resourceDomainConfig
 *              实测三个域名里只有 img.cqkgy.com 可达）
 *
 * 说明：
 *   - 长视频播放是 m3u8（部分线路 AES-128，key 由播放器自行请求）；短剧是 mp4 直链。
 *   - 本蜘蛛按需求过滤成人题材：类型 17=同性 / 19=情色，短剧分类 79=激情。
 *   - 分类顺序把「国产剧」放最前。
 */
public class JianPian extends Spider {

    private static final String BASE = "https://japi.zxfmj.com";
    private static final String IMG = "https://img.cqkgy.com";
    private static final String UA = "okhttp/3.12.0";
    private static final int PAGE_SIZE = 15;
    private static final int SEARCH_SIZE = 20;

    /** 成人题材过滤：类型 17=同性 / 19=情色，短剧分类 79=激情 */
    private static final Set<String> BLOCK_TYPE = new HashSet<>(Arrays.asList("17", "19"));
    private static final Set<String> BLOCK_SHORT_CATE = new HashSet<>(Arrays.asList("79"));
    private static final Set<String> BLOCK_TYPE_NAME = new HashSet<>(Arrays.asList("同性", "情色"));

    /**
     * 标题黑名单兜底：/api/crumb/list 的列表项不带 types 字段，无法按类型过滤，
     * 而默认（type 空）列表会混入三级片（如 area=18 韩国区出现「挑情丑闻」「幻想G点」）。
     * 这里只收高精度词，避免误杀正常剧名（如「裸婚时代」「欲望都市」不在此列）。
     */
    private static final String[] ADULT_WORDS = {
            "情色", "G点", "挑情", "肉蒲团", "潘金莲", "金瓶梅", "金瓶风月",
            "欲女", "淫", "嫖", "性爱", "做爱", "春宫", "艳史", "三级片"
    };

    private boolean isAdultTitle(String title) {
        if (title == null || title.isEmpty()) return false;
        for (String w : ADULT_WORDS) if (title.contains(w)) return true;
        return false;
    }

    /** 分类：id 里 "2_1" 表示 fcate_pid=2 且默认 area=1(国产) —— 国产剧前置 */
    private static final String[][] CLASSES = {
            {"2_1", "国产剧"},
            {"1", "电影"},
            {"2", "电视剧"},
            {"3", "动漫"},
            {"4", "综艺"},
            {"67", "短剧"},
    };

    private void log(String msg) {
        try {
            SpiderDebug.log("[荐片] " + msg);
        } catch (Exception ignored) {
        }
    }

    private Map<String, String> header() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Accept", "application/json, text/plain, */*");
        return h;
    }

    private String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** Android 低版本没有 String.join，自己拼 */
    private String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private JSONObject getJson(String path) {
        String url = BASE + path;
        try {
            OkResult r = OkHttp.get(url, header());
            String body = r.getBody();
            if (body == null || body.trim().isEmpty()) {
                log("空响应 code=" + r.getCode() + " " + path);
                return new JSONObject();
            }
            return new JSONObject(body);
        } catch (Exception e) {
            log("请求异常 " + path + " : " + e.getMessage());
            return new JSONObject();
        }
    }

    private String val(Map<String, String> ext, String key) {
        if (ext == null) return "";
        String v = ext.get(key);
        return v == null ? "" : v.trim();
    }

    private String pic(String path) {
        if (path == null) return "";
        String p = path.trim();
        if (p.isEmpty() || "null".equals(p)) return "";
        if (p.startsWith("http://") || p.startsWith("https://")) return p;
        return IMG + (p.startsWith("/") ? p : "/" + p);
    }

    // ---------- 筛选项 ----------

    private static String filterName(String key) {
        if ("type".equals(key)) return "类型";
        if ("area".equals(key)) return "地区";
        if ("year".equals(key)) return "年份";
        if ("sort".equals(key)) return "排序";
        if ("category_id".equals(key)) return "分类";
        return key;
    }

    /** 长视频（电影/电视剧/动漫/综艺）筛选：type / area / year / sort */
    private JSONArray buildLongFilters() {
        JSONObject d = getJson("/api/crumb/filterOptions?fcate_pid=1");
        return toFilters(d, false);
    }

    /** 短剧筛选：category_id / sort */
    private JSONArray buildShortFilters() {
        JSONObject d = getJson("/api/crumb/filterOptions?fcate_pid=67");
        return toFilters(d, true);
    }

    private JSONArray toFilters(JSONObject d, boolean shortMode) {
        JSONArray out = new JSONArray();
        JSONArray groups = d.optJSONArray("data");
        if (groups == null) return out;
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g == null) continue;
            String key = g.optString("key");
            JSONArray arr = g.optJSONArray("data");
            if (arr == null) continue;
            JSONArray vals = new JSONArray();
            for (int j = 0; j < arr.length(); j++) {
                JSONObject o = arr.optJSONObject(j);
                if (o == null) continue;
                String id = o.optString("id");
                String name = o.optString("name");
                if (!shortMode && "type".equals(key) && BLOCK_TYPE.contains(id)) continue;
                if (shortMode && "category_id".equals(key) && BLOCK_SHORT_CATE.contains(id)) continue;
                JSONObject v = new JSONObject();
                try {
                    v.put("n", name);
                    v.put("v", id);
                } catch (Exception ignored) {
                }
                vals.put(v);
            }
            JSONObject f = new JSONObject();
            try {
                f.put("key", key);
                f.put("name", filterName(key));
                f.put("value", vals);
            } catch (Exception ignored) {
            }
            out.put(f);
        }
        return out;
    }

    // ---------- 列表解析 ----------

    private boolean isAdult(JSONArray types) {
        if (types == null) return false;
        for (int i = 0; i < types.length(); i++) {
            String t = types.optString(i);
            if (t != null && BLOCK_TYPE_NAME.contains(t.trim())) return true;
        }
        return false;
    }

    /** /api/crumb/list 单条：id/title/score/path|tvimg/mask/finished/changed */
    private List<Vod> parseLongList(JSONArray arr) {
        List<Vod> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (isAdult(o.optJSONArray("types"))) continue;
            if (isAdultTitle(o.optString("title"))) continue;
            String id = o.optString("id");
            if (id.isEmpty() || "0".equals(id)) continue;
            Vod v = new Vod();
            v.setVodId(id);
            v.setVodName(o.optString("title"));
            String p = o.optString("tvimg");
            if (p.isEmpty() || "null".equals(p)) p = o.optString("path");
            v.setVodPic(pic(p));
            v.setVodRemarks(o.optString("mask"));
            v.setVodYear(o.optString("year"));
            list.add(v);
        }
        return list;
    }

    /** /api/crumb/shortList 单条：id/title/cover_image/mask/categories[] */
    private List<Vod> parseShortList(JSONArray arr) {
        List<Vod> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (isAdultTitle(o.optString("title"))) continue;
            String id = o.optString("id");
            if (id.isEmpty() || "0".equals(id)) continue;
            Vod v = new Vod();
            v.setVodId("s" + id); // 前缀 s 区分短剧，详情走 /api/detail?vid=&type=1
            v.setVodName(o.optString("title"));
            v.setVodPic(pic(o.optString("cover_image")));
            v.setVodRemarks(o.optString("mask"));
            list.add(v);
        }
        return list;
    }

    private List<Vod> longVods(String pid, String defArea, int page, Map<String, String> ext) {
        String cate = val(ext, "category_id");
        String area = val(ext, "area");
        if (area.isEmpty()) area = defArea == null ? "" : defArea;
        String year = val(ext, "year");
        String type = val(ext, "type");
        String sort = val(ext, "sort");
        if (BLOCK_TYPE.contains(type)) type = "";
        String q = "?fcate_pid=" + enc(pid)
                + "&category_id=" + enc(cate)
                + "&area=" + enc(area)
                + "&year=" + enc(year)
                + "&type=" + enc(type)
                + "&sort=" + enc(sort)
                + "&page=" + page;
        JSONObject d = getJson("/api/crumb/list" + q);
        List<Vod> list = parseLongList(d.optJSONArray("data"));
        log("list pid=" + pid + " area=" + area + " type=" + type + " page=" + page + " → " + list.size());
        return list;
    }

    private List<Vod> shortVods(int page, Map<String, String> ext) {
        String cate = val(ext, "category_id");
        if (BLOCK_SHORT_CATE.contains(cate)) cate = "";
        String sort = val(ext, "sort");
        String q = "?fcate_pid=67&category_id=" + enc(cate) + "&sort=" + enc(sort) + "&page=" + page;
        JSONObject d = getJson("/api/crumb/shortList" + q);
        List<Vod> list = parseShortList(d.optJSONArray("data"));
        log("shortList cate=" + cate + " page=" + page + " → " + list.size());
        return list;
    }

    // ---------- Spider 生命周期 ----------

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        for (String[] c : CLASSES) classes.add(new Class(c[0], c[1]));

        JSONObject filters = new JSONObject();
        JSONArray longF = buildLongFilters();
        JSONArray shortF = buildShortFilters();
        try {
            filters.put("2_1", longF);
            filters.put("1", longF);
            filters.put("2", longF);
            filters.put("3", longF);
            filters.put("4", longF);
            filters.put("67", shortF);
        } catch (Exception ignored) {
        }

        // 首页推荐：电影热榜
        Map<String, String> hot = new HashMap<>();
        hot.put("sort", "hot");
        List<Vod> vods = longVods("1", "", 1, hot);

        log("homeContent classes=" + classes.size() + " filters=" + filters.length() + " vods=" + vods.size());
        return Result.get().classes(classes).filters(filters.toString()).vod(vods).string();
    }

    @Override
    public String homeVideoContent() throws Exception {
        Map<String, String> hot = new HashMap<>();
        hot.put("sort", "hot");
        List<Vod> vods = longVods("1", "", 1, hot);
        return Result.get().vod(vods).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = 1;
        try {
            page = Integer.parseInt(pg);
        } catch (Exception ignored) {
        }
        String pid = tid == null ? "" : tid;
        String defArea = "";
        if (pid.contains("_")) {
            String[] a = pid.split("_");
            pid = a[0];
            if (a.length > 1) defArea = a[1];
        }
        List<Vod> vods;
        if ("67".equals(pid)) {
            vods = shortVods(page, extend);
        } else {
            vods = longVods(pid, defArea, page, extend);
        }
        boolean more = vods.size() >= PAGE_SIZE;
        return Result.get().vod(vods).page(page, more ? page + 1 : page, PAGE_SIZE, PAGE_SIZE).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return Result.error("缺少视频ID");
        String id = ids.get(0).trim();
        if (id.startsWith("s")) return shortDetail(id.substring(1));
        return longDetail(id);
    }

    private String joinNames(JSONArray arr) {
        if (arr == null) return "";
        List<String> names = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            String n = o == null ? arr.optString(i) : o.optString("name");
            if (n != null && !n.trim().isEmpty()) names.add(n.trim());
        }
        return join(names, ",");
    }

    private String longDetail(String id) {
        JSONObject d = getJson("/api/video/detail?id=" + enc(id));
        JSONObject v = d.optJSONObject("data");
        if (v == null) return Result.error("详情获取失败");

        Vod vod = new Vod();
        vod.setVodId(id);
        vod.setVodName(v.optString("title"));
        String p = v.optString("thumbnail");
        if (p.isEmpty() || "null".equals(p)) p = v.optString("tvimg");
        vod.setVodPic(pic(p));
        vod.setVodRemarks(v.optString("mask"));
        vod.setVodYear(v.optString("year"));
        vod.setVodArea(v.optString("area"));
        vod.setVodContent(v.optString("description"));
        vod.setVodActor(joinNames(v.optJSONArray("actors")));
        vod.setVodDirector(joinNames(v.optJSONArray("directors")));

        JSONArray lines = v.optJSONArray("source_list_source");
        if (lines == null) lines = v.optJSONArray("vip_source_list_source");

        List<String> froms = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (lines != null) {
            for (int i = 0; i < lines.length(); i++) {
                JSONObject line = lines.optJSONObject(i);
                if (line == null) continue;
                String lineName = line.optString("name");
                JSONArray eps = line.optJSONArray("source_list");
                if (eps == null || eps.length() == 0) continue;

                StringBuilder sb = new StringBuilder();
                StringBuilder sig = new StringBuilder();
                for (int j = 0; j < eps.length(); j++) {
                    JSONObject ep = eps.optJSONObject(j);
                    if (ep == null) continue;
                    String u = ep.optString("url");
                    if (u.isEmpty()) continue;
                    String name = ep.optString("source_name");
                    if (name.isEmpty()) name = "第" + (j + 1) + "集";
                    if (sb.length() > 0) sb.append("#");
                    sb.append(name).append("$").append(u);
                    sig.append(u).append("|");
                }
                if (sb.length() == 0) continue;
                // 相同播放地址的线路去重（详情接口会返回 20+ 条重复线路）
                if (!seen.add(sig.toString())) continue;
                if (lineName.isEmpty()) lineName = "线路" + (froms.size() + 1);
                froms.add(lineName);
                urls.add(sb.toString());
            }
        }
        if (froms.isEmpty()) return Result.error("无可用播放线路");
        vod.setVodPlayFrom(join(froms, "$$$"));
        vod.setVodPlayUrl(join(urls, "$$$"));
        log("detail id=" + id + " lines=" + froms.size() + " name=" + vod.getVodName());
        return Result.get().vod(vod).string();
    }

    private String shortDetail(String vid) {
        JSONObject d = getJson("/api/detail?vid=" + enc(vid) + "&type=1");
        JSONObject v = d.optJSONObject("data");
        if (v == null) return Result.error("短剧详情获取失败");

        Vod vod = new Vod();
        vod.setVodId("s" + vid);
        vod.setVodName(v.optString("title"));
        vod.setVodPic(pic(v.optString("cover_image")));
        vod.setVodRemarks("共" + v.optInt("episodes_count") + "集");
        vod.setVodContent(v.optString("description"));
        vod.setVodYear(v.optString("year"));

        JSONArray playlist = v.optJSONArray("playlist");
        StringBuilder sb = new StringBuilder();
        if (playlist != null) {
            for (int i = 0; i < playlist.length(); i++) {
                JSONObject ep = playlist.optJSONObject(i);
                if (ep == null) continue;
                String u = ep.optString("url");
                if (u.isEmpty()) continue;
                String name = ep.optString("title");
                if (name.isEmpty()) name = "第" + (i + 1) + "集";
                if (sb.length() > 0) sb.append("#");
                sb.append(name).append("$").append(u);
            }
        }
        if (sb.length() == 0) {
            String u = v.optString("url");
            if (u.isEmpty()) return Result.error("无可用播放地址");
            sb.append("正片$").append(u);
        }
        vod.setVodPlayFrom("短剧");
        vod.setVodPlayUrl(sb.toString());
        log("shortDetail vid=" + vid + " eps=" + (playlist == null ? 0 : playlist.length()));
        return Result.get().vod(vod).string();
    }

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
        JSONObject d = getJson("/api/v2/search/videoV2?key=" + enc(key) + "&page=" + page + "&pageSize=" + SEARCH_SIZE);
        JSONArray arr = d.optJSONArray("data");
        List<Vod> vods = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (isAdult(o.optJSONArray("types"))) continue;
                if (isAdultTitle(o.optString("title"))) continue;
                int id = o.optInt("id");
                if (id <= 0) continue;
                String top = "";
                JSONObject tc = o.optJSONObject("top_category");
                if (tc != null) top = tc.optString("name");
                Vod v = new Vod();
                v.setVodId("短剧".equals(top) ? "s" + id : String.valueOf(id));
                v.setVodName(o.optString("title"));
                String p = o.optString("thumbnail");
                if (p.isEmpty() || "null".equals(p)) p = o.optString("tvimg");
                v.setVodPic(pic(p));
                v.setVodRemarks(o.optString("mask"));
                JSONArray years = o.optJSONArray("years");
                if (years != null && years.length() > 0) {
                    JSONObject y0 = years.optJSONObject(0);
                    if (y0 != null) v.setVodYear(y0.optString("year"));
                }
                vods.add(v);
            }
        }
        log("search key=" + key + " page=" + page + " → " + vods.size());
        boolean more = vods.size() >= SEARCH_SIZE;
        return Result.get().vod(vods).page(page, more ? page + 1 : page, SEARCH_SIZE, SEARCH_SIZE).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (id == null || id.trim().isEmpty()) return Result.error("播放地址为空");
        // 长视频为 m3u8（部分线路 AES-128，key 由播放器请求）；短剧为 mp4 直链
        return Result.get().url(id.trim()).string();
    }
}
