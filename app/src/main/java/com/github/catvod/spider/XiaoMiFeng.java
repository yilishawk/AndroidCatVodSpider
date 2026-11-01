package com.github.catvod.spider;

import android.content.Context;
import android.util.Base64;

import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 小蜜蜂影院 (com.vod.app1e80134b / com.toolboxvtwo.appleboxvtwo) CatVod 蜘蛛
 * <p>
 * 数据源: https://jiayehuizhan.com/api/vod/   （全部 POST + form，GET 一律 404）
 * <p>
 * 接口（2026-11-01 MITM 实锤）:
 * init                 空 body              → type_list / banner_list / hot_search_list / week_list
 * typeFilterVodList    type_id page area year class lang sort → recommend_list
 * vodDetail            vod_id               → vod + player_source_list + vod_play_url_list
 * vodParse             vod_id player_source_id episode_index scene=0 → play_url
 * searchList           keywords page        → search_list
 * rankList             page                 → rank_list
 * searchSuggestions    keywords             → keyword + list
 * <p>
 * 响应: {"code":0,"msg":"","data":"<AES-128-CBC 密文 Base64>"}
 * 密钥: key = IV = egTS6uiHDdpVUJAc（libnmmp.so 静态爆破坐实，明文可逆）
 * <p>
 * 播放链路: vodDetail 下发的 urls[].url 恒为空，必须再调 vodParse 拿 m3u8 直链。
 * player_source_id 取 player_source_list[].id（不是 player_code，传 code 服务端回"参数错误"）。
 * <p>
 * 线路顺序（凯哥 2026-11-01 指定）：4K蓝光 → 采集直链(jx_type=0) → 官网解析(jx_type=1)。
 * 官网解析源返回的 play_url 一律先 302 到 *.sviptq.com，且该 CDN 把 m3u8 的 Content-Type
 * 错标成 image/png，播放器不跟跳转就会"拿不到真实地址" —— 这里在 resolve() 里提前走完跳转。
 * <p>
 * 搜索: 服务端逐字模糊匹配（搜"庆余年"会回"陈泰庆""庆元宵"），本地按 exactHit() 只留完全匹配。
 * <p>
 * 站点 ext 可覆盖：{"host":"https://jiayehuizhan.com/api/vod","deviceId":"32位hex"}
 */
public class XiaoMiFeng extends Spider {

    /** AES 密钥，key = IV，16 字节 */
    private static final String KEY = "egTS6uiHDdpVUJAc";
    private static final String DEFAULT_HOST = "https://jiayehuizhan.com/api/vod";
    private static final String DEFAULT_DEVICE = "29227c815e45939da8cb0bbd13f3a3cde";
    private static final String UA = "okhttp/3.14.9";

    /** 服务端每页固定 36 条，且 total 恒为 0，只能靠"本页是否满页"判断还有没有下一页 */
    private static final int PAGE_SIZE = 36;

    /** 排序维度（服务端固定值；日榜/周榜/月榜实测返回同一份数据） */
    private static final String SORTS = "最新,最热,最赞,日榜,周榜,月榜";

    /**
     * 顶层分类：国产剧置顶并锁定 area=大陆，用 "type_id_地区" 编码表达。
     * init.type_list 只有 0全部 / 1电影 / 2剧集 / 3综艺 / 4动漫。
     */
    private static final String[][] CATES = {
            {"2_大陆", "国产剧"},
            {"2", "剧集"},
            {"1", "电影"},
            {"4", "动漫"},
            {"3", "综艺"},
    };

    /** 筛选项中文名 */
    private static final String[][] DIM_NAME = {
            {"class", "类型"},
            {"area", "地区"},
            {"lang", "语言"},
            {"year", "年份"},
            {"sort", "排序"},
    };

    /** 成人/敏感内容过滤词 */
    private static final String[] BLOCK = {
            "色情", "情色", "成人片", "三级片", "伦理片", "男同", "女同", "同性", "淫", "裸体", "AV片", "福利"
    };

    /**
     * 线路排序权重（2026-11-01 实测 743 个播放源得出）：
     * <p>
     * jx_type=0 → 采集直链（jsm3u8 极速云 / bfzym3u8 暴风），各 CDN 直连 200，播放稳。
     * jx_type=1 → 官网解析（co 4K蓝光 / qiyi / qq / youku / mgtv / bilibili），
     * play_url 先 302 到 *.sviptq.com，且该 CDN 把 m3u8 的 Content-Type 错标成 image/png，
     * 播放器不跟跳转或按类型判定就会"拿不到真实地址"。
     * <p>
     * 所以：4K蓝光最前（凯哥指定）→ 采集直链 → 官网解析垫底。
     */
    private static int lineRank(JSONObject s) {
        String code = s.optString("player_code", "");
        String name = s.optString("player_name", "");
        if ("co".equals(code) || name.contains("4K") || name.contains("蓝光")) return 0;
        if (s.optInt("jx_type", 0) == 0) return 1;
        return 2;
    }

    private String host = DEFAULT_HOST;
    private String deviceId = DEFAULT_DEVICE;

    /** init 的 type_list 缓存：type_id -> type_extend(JSONObject) */
    private JSONObject typeExtendCache;

    // ==================== 生命周期 ====================

    @Override
    public void init(Context context, String extend) throws Exception {
        if (extend == null || extend.trim().isEmpty()) return;
        try {
            JSONObject j = new JSONObject(extend.trim());
            if (j.has("host")) host = j.getString("host");
            if (j.has("deviceId")) deviceId = j.getString("deviceId");
        } catch (Exception ignored) {
        }
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);
    }

    // ==================== 基础设施 ====================

    private void log(String msg) {
        try {
            SpiderDebug.log("[小蜜蜂] " + msg);
        } catch (Exception ignored) {
        }
    }

    private Map<String, String> header() {
        Map<String, String> h = new HashMap<>();
        h.put("App-Device-Id", deviceId);
        h.put("App-Os-Type", "android");
        h.put("App-Ui-Mode", "light");
        h.put("App-Version-Code", "100");
        h.put("User-Agent", UA);
        return h;
    }

    private String decrypt(String data) throws Exception {
        if (data == null || data.trim().isEmpty()) return "";
        byte[] ct = Base64.decode(data.replaceAll("\\s+", ""), Base64.NO_WRAP);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(KEY.getBytes("UTF-8"), "AES"),
                new IvParameterSpec(KEY.getBytes("UTF-8")));
        return new String(c.doFinal(ct), "UTF-8");
    }

    /** 调接口并返回解密后的内层 JSON；失败返回 null */
    private JSONObject api(String action, Map<String, String> params) {
        try {
            OkResult res = OkHttp.post(host + "/" + action,
                    params == null ? new HashMap<String, String>() : params, header());
            String body = res.getBody();
            if (body == null || body.trim().isEmpty()) {
                log(action + " 空响应");
                return null;
            }
            JSONObject outer;
            try {
                outer = new JSONObject(body);
            } catch (Exception e) {
                log(action + " 非 JSON: " + body.substring(0, Math.min(120, body.length())));
                return null;
            }
            int code = outer.optInt("code", -1);
            if (code != 0) {
                log(action + " code=" + code + " msg=" + outer.optString("msg"));
                return null;
            }
            Object data = outer.opt("data");
            if (!(data instanceof String)) {
                log(action + " data 非字符串");
                return null;
            }
            String pt = decrypt((String) data);
            if (pt == null || pt.isEmpty()) {
                log(action + " 解密为空");
                return null;
            }
            return new JSONObject(pt);
        } catch (Exception e) {
            log(action + " 异常 " + e);
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

    /**
     * 把 302 跳转链提前走完，直接给播放器一个 200 的最终地址。
     * <p>
     * 官网解析源（co/qiyi/qq/youku/mgtv/bilibili）返回的 play_url 一律先 302 到 *.sviptq.com，
     * 且该 CDN 把 m3u8 的 Content-Type 错标成 image/png —— 播放器不跟跳转或按类型判定就会
     * 报"拿不到真实地址"。这里自己跟完跳转，并顺手校验正文确实是 #EXTM3U。
     *
     * @return 最终地址；正文不是播放列表（或跳转失败）时返回 ""，由调用方换下一个源
     */
    private String resolve(String url) {
        String cur = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = null;
            try {
                URL u = new URL(cur);
                c = (HttpURLConnection) u.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                c.setRequestMethod("GET");
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Accept", "*/*");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null || loc.trim().isEmpty()) break;
                    cur = new URL(u, loc.trim()).toString();
                    continue;
                }
                // 落到 200：只对 m3u8 做一次正文嗅探，CDN 的 Content-Type 不可信
                if (code == 200 && cur.toLowerCase().contains("m3u8")) {
                    InputStream in = c.getInputStream();
                    byte[] b = new byte[8];
                    int n = 0;
                    try {
                        n = in.read(b);
                    } finally {
                        in.close();
                    }
                    if (n >= 7 && !new String(b, 0, n, "UTF-8").startsWith("#EXTM3U")) {
                        log("resolve 正文不是播放列表: " + cur.substring(0, Math.min(70, cur.length())));
                        return "";
                    }
                }
                break;
            } catch (Exception e) {
                log("resolve 失败（用原地址）: " + e);
                break;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return cur;
    }

    /** 去掉空白与标点，用于片名比对 */
    private static String norm(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\u3000·・:：!！?？,，.。'\"“”‘’\\-—_()（）\\[\\]【】《》<>~～]", "").toLowerCase();
    }

    /**
     * 完全匹配：片名与关键词一致，或只差一个季/部/集后缀（庆余年 vs 庆余年第二季）。
     * 服务端是逐字模糊匹配（搜"庆余年"会回"陈泰庆""庆元宵"），必须本地再筛一遍。
     */
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

    private static boolean isAdult(JSONObject o) {
        if (o == null) return true;
        String s = o.optString("vod_name", "") + o.optString("vod_class", "")
                + o.optString("vod_tag", "") + o.optString("vod_blurb", "");
        for (String w : BLOCK) {
            if (s.contains(w)) return true;
        }
        return false;
    }

    // ==================== 分类 / 筛选 ====================

    /** init 只拉一次，缓存 type_list 里的 type_extend（是 JSON 字符串，需二次 parse） */
    private JSONObject typeExtend() {
        if (typeExtendCache != null) return typeExtendCache;
        JSONObject out = new JSONObject();
        JSONObject d = api("init", new HashMap<String, String>());
        if (d == null) {
            log("init 失败，筛选项降级为空");
            return out;
        }
        JSONArray arr = d.optJSONArray("type_list");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.optJSONObject(i);
                if (t == null) continue;
                String tid = t.optString("type_id", "");
                String ext = t.optString("type_extend", "");
                if (tid.isEmpty() || ext.isEmpty()) continue;
                try {
                    out.put(tid, new JSONObject(ext));
                } catch (Exception ignored) {
                }
            }
        }
        typeExtendCache = out;
        log("init 分类筛选项加载完成，共 " + out.length() + " 个分类");
        return out;
    }

    private static JSONObject dim(String key, String name, String values) {
        JSONObject o = new JSONObject();
        try {
            o.put("key", key);
            o.put("name", name);
            JSONArray vals = new JSONArray();
            JSONObject all = new JSONObject();
            all.put("n", "全部");
            all.put("v", "");
            vals.put(all);
            for (String v : values.split(",")) {
                v = v.trim();
                if (v.isEmpty()) continue;
                JSONObject vv = new JSONObject();
                vv.put("n", v);
                vv.put("v", v);
                vals.put(vv);
            }
            o.put("value", vals);
        } catch (Exception ignored) {
        }
        return o;
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
                for (String[] dn : DIM_NAME) {
                    String key = dn[0];
                    if ("sort".equals(key)) {
                        dims.put(dim("sort", "排序", SORTS));
                        continue;
                    }
                    if (ext == null) continue;
                    // 国产剧频道已锁定地区，不再给地区维度
                    if (locked && "area".equals(key)) continue;
                    String vals = ext.optString(key, "");
                    if (vals.isEmpty()) continue;
                    dims.put(dim(key, dn[1], vals));
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
        // 凯哥的定制要求：首页不要推荐流，只给分类 + 筛选项
        StringBuilder sb = new StringBuilder("{\"class\":");
        sb.append(classes.toString());
        sb.append(",\"filters\":").append(buildFilters().toString());
        sb.append(",\"list\":[]}");
        return sb.toString();
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
        p.put("class", "全部");
        p.put("area", forcedArea != null ? forcedArea : "全部");
        p.put("lang", "全部");
        p.put("year", "全部");
        p.put("sort", "最新");

        if (extend != null) {
            for (String[] dn : DIM_NAME) {
                String k = dn[0];
                if (forcedArea != null && "area".equals(k)) continue;
                String v = extend.get(k);
                if (v != null && !v.trim().isEmpty()) p.put(k, v.trim());
            }
        }

        JSONObject d = api("typeFilterVodList", p);
        List<Vod> list = new ArrayList<>();
        if (d != null) {
            JSONArray arr = d.optJSONArray("recommend_list");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    if (isAdult(it)) continue;
                    Vod v = new Vod();
                    v.setVodId(it.optString("vod_id"));
                    v.setVodName(it.optString("vod_name"));
                    v.setVodPic(it.optString("vod_pic"));
                    v.setVodRemarks(it.optString("vod_remarks"));
                    list.add(v);
                }
            }
        }
        // total 服务端恒为 0，只能用"本页是否满 36 条"推下一页
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
        JSONObject d = api("vodDetail", map("vod_id", id));
        if (d == null) return "";

        JSONObject v0 = d.optJSONObject("vod");
        if (v0 == null) v0 = new JSONObject();

        Vod v = new Vod();
        v.setVodId(v0.optString("vod_id", id));
        v.setVodName(v0.optString("vod_name"));
        v.setVodPic(v0.optString("vod_pic"));
        v.setVodRemarks(v0.optString("vod_remarks"));
        v.setVodYear(v0.optString("vod_year"));
        v.setVodArea(v0.optString("vod_area"));
        v.setVodActor(v0.optString("vod_actor"));
        v.setVodDirector(v0.optString("vod_director"));
        String cls = v0.optString("vod_class", "");
        v.setTypeName(cls);
        v.setVodTag(cls);
        String content = v0.optString("vod_content", "");
        if (content.isEmpty()) content = v0.optString("vod_blurb", "");
        String lang = v0.optString("vod_lang", "");
        if (!lang.isEmpty()) content = "语言：" + lang + "\n" + content;
        v.setVodContent(content.replaceAll("<[^>]+>", "").trim());

        // player_code -> {id, name, sort}
        Map<String, String[]> srcById = new LinkedHashMap<>();
        List<JSONObject> sources = new ArrayList<>();
        JSONArray srcArr = d.optJSONArray("player_source_list");
        if (srcArr != null) {
            for (int i = 0; i < srcArr.length(); i++) {
                JSONObject s = srcArr.optJSONObject(i);
                if (s == null) continue;
                String code = s.optString("player_code", "");
                if (code.isEmpty()) continue;
                srcById.put(code, new String[]{
                        s.optString("id", ""), s.optString("player_name", code), s.optString("sort", "99")
                });
                sources.add(s);
            }
        }
        Collections.sort(sources, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                int ra = lineRank(a), rb = lineRank(b);
                if (ra != rb) return ra - rb;
                return toInt(a.optString("sort", "99"), 99) - toInt(b.optString("sort", "99"), 99);
            }
        });

        // 按 sort 排好序的全部源 id，用于"选哪条线路优先解析哪条，失败顺延"
        List<String> allIds = new ArrayList<>();
        for (JSONObject s : sources) {
            String sid = s.optString("id", "");
            if (!sid.isEmpty()) allIds.add(sid);
        }

        // player_code -> 剧集列表
        Map<String, JSONArray> epsByCode = new LinkedHashMap<>();
        JSONArray groups = d.optJSONArray("vod_play_url_list");
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.optJSONObject(i);
                if (g == null) continue;
                String code = g.optString("player_code", "");
                JSONArray eps = g.optJSONArray("urls");
                if (!code.isEmpty() && eps != null && eps.length() > 0) epsByCode.put(code, eps);
            }
        }

        List<String> fromList = new ArrayList<>();
        List<String> urlList = new ArrayList<>();
        for (JSONObject s : sources) {
            String code = s.optString("player_code", "");
            String[] info = srcById.get(code);
            if (info == null) continue;
            String pid = info[0];
            String show = info[1];
            JSONArray eps = epsByCode.get(code);
            if (pid.isEmpty() || eps == null || eps.length() == 0) continue;

            // 本线路优先，其余源顺延
            List<String> pids = new ArrayList<>();
            pids.add(pid);
            for (String other : allIds) {
                if (!other.equals(pid)) pids.add(other);
            }
            String pidStr = join(pids, ";");

            List<String> epList = new ArrayList<>();
            for (int k = 0; k < eps.length(); k++) {
                JSONObject e = eps.optJSONObject(k);
                if (e == null) continue;
                String name = e.optString("name", "");
                if (name.isEmpty()) name = "第" + (k + 1) + "集";
                String idx = e.optString("episode_index", String.valueOf(k));
                epList.add(name + "$" + id + "@" + idx + "@" + pidStr);
            }
            if (epList.isEmpty()) continue;
            fromList.add(show);
            urlList.add(join(epList, "#"));
        }

        v.setVodPlayFrom(join(fromList, "$$$"));
        v.setVodPlayUrl(join(urlList, "$$$"));
        log("detail id=" + id + " 线路=" + fromList.size() + " 播放源=" + join(fromList, "/"));
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
        // 参数名是 keywords（复数）；传 keyword / wd 不报错但返回空列表
        JSONObject d = api("searchList", map("keywords", key, "page", String.valueOf(page)));
        List<JSONObject> cands = new ArrayList<>();
        if (d != null) {
            JSONArray arr = d.optJSONArray("search_list");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    if (it == null || isAdult(it)) continue;
                    cands.add(it);
                }
            }
        }

        // 服务端逐字模糊匹配，本地只留完全匹配；一个都没有时退回"包含"级，避免搜不到东西
        List<JSONObject> pick = new ArrayList<>();
        for (JSONObject it : cands) {
            if (exactHit(it.optString("vod_name"), key)) pick.add(it);
        }
        int mode = 0;
        if (pick.isEmpty()) {
            String k = norm(key);
            for (JSONObject it : cands) {
                if (norm(it.optString("vod_name")).contains(k)) pick.add(it);
            }
            mode = 1;
        }

        List<Vod> list = new ArrayList<>();
        for (JSONObject it : pick) {
            Vod v = new Vod();
            v.setVodId(it.optString("vod_id"));
            v.setVodName(it.optString("vod_name"));
            v.setVodPic(it.optString("vod_pic"));
            v.setVodRemarks(it.optString("vod_remarks"));
            v.setVodYear(it.optString("vod_year"));
            v.setVodArea(it.optString("vod_area"));
            list.add(v);
        }
        log("search " + key + " page=" + page + " 原始=" + cands.size()
                + " 命中=" + list.size() + (mode == 1 ? "（退到包含级）" : "（完全匹配）"));
        return Result.get().vod(list).string();
    }

    // ==================== 播放 ====================

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (id == null || id.trim().isEmpty()) return Result.error("播放地址为空");
        String pid = id.trim();
        if (pid.startsWith("http")) {
            Result r = Result.get().url(pid);
            if (pid.contains("m3u8")) r.m3u8();
            return r.string();
        }

        // 占位 id: vod_id@episode_index@源id1;源id2;源id3...
        String[] a = pid.split("@");
        if (a.length < 3 || a[2].isEmpty()) return Result.error("播放信息不完整");
        String vid = a[0], idx = a[1];
        String[] pids = a[2].split(";");
        String lastErr = "";
        for (String sid : pids) {
            sid = sid.trim();
            if (sid.isEmpty()) continue;
            try {
                JSONObject d = api("vodParse", map(
                        "vod_id", vid,
                        "player_source_id", sid,
                        "episode_index", idx,
                        "scene", "0"));
                if (d == null) {
                    lastErr = "源" + sid + "无响应";
                    continue;
                }
                String url = d.optString("play_url", "");
                if (url.startsWith("http")) {
                    // 官网解析源一律 302 到 sviptq.com 且 Content-Type 被错标成 image/png，
                    // 这里提前走完跳转，给播放器一个直连地址
                    String real = resolve(url);
                    if (real.isEmpty()) {
                        lastErr = "源" + sid + "解析结果不可用";
                        continue;
                    }
                    log("play vod=" + vid + " ep=" + idx + " 源=" + sid
                            + " 跳转" + (real.equals(url) ? "0" : "1") + "次 → "
                            + real.substring(0, Math.min(90, real.length())));
                    Result r = Result.get().url(real);
                    if (real.toLowerCase().contains("m3u8")) r.m3u8();
                    return r.string();
                }
                // 解析池空转会返回错误页，跳过换下一个源
                lastErr = "源" + sid + "返回空地址";
            } catch (Exception e) {
                lastErr = "源" + sid + "异常 " + e.getMessage();
            }
        }
        log("play vod=" + vid + " ep=" + idx + " 全部失败: " + lastErr);
        return Result.error("解析失败（" + pids.length + " 个播放源均无地址）");
    }
}
