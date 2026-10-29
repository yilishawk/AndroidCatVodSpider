package com.github.catvod.spider;

import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 红果免费漫剧（com.kylin.read / 字节番茄系 fqnovel 底座）
 *
 * 一、五个方法各自的接口
 *     homeContent      category/list/v1                       13 个分类（固定）
 *     categoryContent  new_category/landing/v1                分类列表（首选）
 *                      bookmall/cell/change/v1                降级：换一批
 *     detailContent    novel/player/video_detail/v1           详情 + 全部集数
 *     searchContent    search/suggest/v1                      联想搜索（含 series_id）
 *     playerContent    novel/player/video_model/v1            播放直链（MP4）
 *
 * 二、这个源最特殊的一点：接口会「静默降级」
 *     服务端对被判定为异常的调用，返回 **HTTP 200 + 0 字节 body**，
 *     不是 4xx、不是错误码、不是空数组 —— 就是彻底没有 body。
 *     实测（2026-10-29）：
 *       · bookmall/tab/v、category/list/v1、bookmall/cell/change/v1  稳定可用
 *       · new_category/front、new_category/landing、search/suggest、
 *         novel/player/video_detail                                   会整片降级为空
 *     触发条件是高频调用（同一台机器连续 30+ 次请求后命中），
 *     换 device_id / 换 UA / 加真机业务头（X-Xs-From-Web: 0）/ 换出口 IP
 *     （手机 4G 电信 IPv6 直连）**全部无效**，至少 4 分钟不恢复。
 *     ⇒ 所有请求都必须做空 body 检测，并当成「本页无数据」而不是异常。
 *
 * 三、播放是 MP4 直链，不是 m3u8
 *     video_model 返回 data.video_model —— 一个 **JSON 字符串**，要二次 parse。
 *     video_list[0].main_url 就是 CDN 直链，实测 HTTP 206 + ftypisom 头，
 *     编码 bytevc1（H.264 别名，兼容性好），竖屏剧 720x1278。
 *     地址带 url_expire（约 6 小时），不能缓存复用。
 *
 * 四、已知边界（不能假装有）
 *     · 分类树能拿到，但「按分类出列表」的接口（landing）参数只打通过一次，
 *       之后一直被降级，字段结构没能完整验证 —— 所以 categoryContent 里
 *       landing 的取值全部走 pick() 多字段名兼容，取不到就降级到 cell/change。
 *     · cell/change 不认任何分类参数（category_id / tag_id 全试过），
 *       它只负责「换一批」，所以降级模式下所有分类看到的是同一批内容。
 *     · cell/change 每次只返回 1 条，翻 20 条要 20 次请求（约 12 秒），
 *       所以降级模式一页只攒 10 条。
 *     · 搜索结果是联想接口，只给剧名 + series_id，没有封面/简介，
 *       列表页会是灰块，点进详情才有完整信息。
 *
 * 定制：首页不出推荐流（list 恒为 []）；现代都市置顶（短剧主力）；
 *       过滤成人向关键词。
 */
public class HongGuo extends Spider {

    // ==================== 常量 ====================

    private static final String HOST = "https://api5-normal.fqnovel.com";

    /** 备用节点（新加坡）。实测与主域名行为一致，主域名挂了再切。 */
    private static final String HOST_BAK = "https://api5-normal-sinfonlinea.fqnovel.com";

    /**
     * UA 必须是 com.dragon.read（番茄底座），不是 com.kylin.read。
     * 服务端认底座身份，写成 kylin 可能被拒或返回降级内容。
     */
    private static final String UA = "com.dragon.read/74032 (Linux; U; Android 12; zh_CN; "
            + "Redmi K30 5G; Build/SKQ1.211006.001; "
            + "Cronet/TTNetVersion:9a1b1f2b 2023-11-20)";

    /**
     * 公共参数。aid=8704 是红果漫剧的应用 ID（番茄小说是 1967，用错会返回小说的内容）。
     * device_id / iid 是设备指纹，这里用文档实测有效的那一组。
     */
    private static final Map<String, String> COMMON = new LinkedHashMap<>();

    static {
        COMMON.put("aid", "8704");
        COMMON.put("device_id", "2729412239307828");
        COMMON.put("iid", "1149457874313721");
        COMMON.put("device_type", "Redmi K30 5G");
        COMMON.put("device_platform", "android");
        COMMON.put("version_code", "74032");
        COMMON.put("version_name", "7.4.0.32");
        COMMON.put("manifest_version_code", "74032");
        COMMON.put("update_version_code", "74032");
        COMMON.put("os", "android");
        COMMON.put("os_version", "12");
        COMMON.put("channel", "xiaomi_8704_64");
        COMMON.put("resolution", "1080*2400");
        COMMON.put("dpi", "440");
        COMMON.put("language", "zh");
        COMMON.put("region", "CN");
        COMMON.put("tz_name", "Asia/Shanghai");
        COMMON.put("ac", "wifi");
        COMMON.put("ssmix", "a");
        // app_name=novelapp 返回内容最全；kylin_read / dragon_read 也能过但内容明显少
        COMMON.put("app_name", "novelapp");
    }

    /** 分类页 tab 类型，8 = 找剧（2 = 小说，不要） */
    private static final String TAB_TYPE = "8";

    /**
     * 分类。category_id 是 category/list/v1 实测返回的 13 个值。
     * 顺序按短剧实际热度排，现代都市放第一（这个源没有「国产剧」这个分类）。
     */
    private static final String[][] CATES = {
            {"1", "现代都市"},
            {"2", "异世玄幻"},
            {"6", "仙侠修真"},
            {"7", "历史穿越"},
            {"9", "悬疑灵异"},
            {"8", "军事战争"},
            {"3", "官场职场"},
            {"5", "武侠世界"},
            {"4", "魔法奇幻"},
            {"10", "科幻末世"},
            {"11", "游戏电竞"},
            {"12", "体育竞技"},
            {"13", "二次元"},
    };

    /** landing 一页条数（按接口 offset 分页推测，未完整验证） */
    private static final int PAGE_SIZE = 20;

    /**
     * 降级模式下 cell/change 一页攒几条。
     * 这个接口一次只回 1 条，每条都是一次独立请求 —— 数量直接等于请求数。
     * 实测高频请求会把服务端风控喂爆（连带详情/播放一起静默降级），所以压到 5。
     */
    private static final int FALLBACK_PAGE = 5;

    /** 成人向关键词：结果二次过滤 */
    private static final String[] ADULT_WORDS = {
            "伦理", "倫理", "情色", "成人", "写真", "福利", "三级", "無碼", "无码", "擦边",
    };

    /** cell/change 的会话链：接口要求带上一轮的 session_id 才能翻到下一条 */
    private static String sCellId = "";
    private static String sCellSession = "";
    private static String sCellOffset = "0";
    private static int sCellPage = 0;

    // ==================== 基础工具 ====================

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }

    private static String urlOf(String base, String path, Map<String, String> extra) {
        StringBuilder sb = new StringBuilder(base).append(path).append('?');
        for (Map.Entry<String, String> e : COMMON.entrySet()) {
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue())).append('&');
        }
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                if (e.getValue() == null) continue;
                sb.append(enc(e.getKey())).append('=').append(enc(e.getValue())).append('&');
            }
        }
        sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    /**
     * 统一请求。返回 null 表示「静默降级」（HTTP 200 但 body 为空），
     * 与「真的出错」区分开：降级是可以预期的，不要当异常抛。
     */
    private static String request(String base, String path, Map<String, String> extra,
                                  String jsonBody, boolean post) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(urlOf(base, path, extra));
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod(post ? "POST" : "GET");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "application/json; charset=utf-8");
            if (post) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] payload = (jsonBody == null ? "{}" : jsonBody).getBytes("UTF-8");
                conn.setFixedLengthStreamingMode(payload.length);
                OutputStream os = conn.getOutputStream();
                os.write(payload);
                os.flush();
                os.close();
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                SpiderDebug.log("[红果] HTTP " + code + " " + path);
                return null;
            }
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            if (bos.size() == 0) {
                // HTTP 200 + 0 字节 = 被静默降级，不是空结果
                SpiderDebug.log("[红果] 空 body（降级）" + path);
                return null;
            }
            return bos.toString("UTF-8");
        } catch (Exception e) {
            SpiderDebug.log("[红果] 请求异常 " + path + " " + e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String get(String path, Map<String, String> extra) {
        return request(HOST, path, extra, null, false);
    }

    private static String post(String path, Map<String, String> extra, String body) {
        return request(HOST, path, extra, body, true);
    }

    private static JSONObject parse(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(raw);
            // 内容接口一律 code=0 表示成功；有的接口压根没有 code 字段
            if (o.has("code") && o.optInt("code", -1) != 0) {
                SpiderDebug.log("[红果] code=" + o.optInt("code") + " msg=" + o.optString("message"));
                return null;
            }
            return o;
        } catch (Exception e) {
            SpiderDebug.log("[红果] JSON 解析失败 " + e);
            return null;
        }
    }

    /** 多字段名兼容取值：landing 的返回结构没能完整验证，靠这个兜 */
    private static String pick(JSONObject o, String... keys) {
        if (o == null) return "";
        for (String k : keys) {
            if (o.has(k) && !o.isNull(k)) {
                String v = o.optString(k, "");
                if (!v.isEmpty() && !"null".equals(v)) return v;
            }
        }
        return "";
    }

    private static boolean isAdult(String s) {
        if (s == null || s.isEmpty()) return false;
        for (String w : ADULT_WORDS) {
            if (s.contains(w)) return true;
        }
        return false;
    }

    /** 把 http 封面换成 https，避免部分客户端不加载明文图 */
    private static String pic(String url) {
        if (url == null) return "";
        if (url.startsWith("http://")) return "https://" + url.substring(7);
        return url;
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
        return "{\"class\":" + classes + ",\"filters\":{},\"list\":[]}";
    }

    // ==================== 分类列表 ====================

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) throws Exception {
        int page = 1;
        try {
            page = Integer.parseInt(pg);
        } catch (Exception ignored) {
        }
        String cid = tid == null || tid.trim().isEmpty() ? "1" : tid.trim();

        // 首选 landing（能按分类过滤）
        List<Vod> vods = categoryByLanding(cid, page);
        boolean usedLanding = vods != null;

        if (vods == null) {
            // landing 不可用时的降级链。
            // ★ 这里刻意不用「一页刷 N 次 cell/change」的老做法：那个接口一次只回 1 条，
            //   凑 20 条要 20 次请求，实测正是这种高频把服务端风控喂爆、连带详情和播放
            //   一起静默降级。所以第 1 页直接用 tab/v（1 次请求白拿 6 条），
            //   后续页才用 cell/change，且只取 FALLBACK_PAGE 条。
            if (page <= 1) {
                vods = categoryByTab();
                if (vods.isEmpty()) vods = categoryByCell(page);
                SpiderDebug.log("[红果] landing 不可用，降级 tab/v tid=" + cid + " out=" + vods.size());
            } else {
                vods = categoryByCell(page);
                SpiderDebug.log("[红果] landing 不可用，降级 cell/change tid=" + cid + " pg=" + page);
            }
        }
        if (vods == null) vods = new ArrayList<>();

        int perPage = usedLanding ? PAGE_SIZE : FALLBACK_PAGE;
        int total = page * perPage + (vods.size() >= perPage ? perPage : 0);
        boolean more = vods.size() >= perPage;
        return Result.get().vod(vods)
                .page(page, more ? page + 1 : page, vods.size(), total)
                .string();
    }

    /**
     * 降级方案一：从 tab/v 里白拿推荐流。
     * tab/v 一次返回 52KB，里面 6 个「双列短剧」cell 各内嵌 1 条 video_data ——
     * 也就是说 1 次请求就能拿 6 条，完全不用碰 cell/change。
     * 代价：这 6 条是固定的推荐位（实测 4 次请求只出现 7 个不同 ID），翻页拿不到新内容。
     */
    private static List<Vod> categoryByTab() {
        JSONObject root = parse(get("/reading/bookapi/bookmall/tab/v", null));
        if (root == null) return new ArrayList<>();
        List<Vod> out = new ArrayList<>();
        try {
            JSONArray tabs = root.getJSONObject("data").getJSONArray("tab_item");
            for (int i = 0; i < tabs.length(); i++) {
                JSONObject t = tabs.optJSONObject(i);
                if (t == null || t.optInt("tab_type", -1) != 8) continue;
                JSONArray cells = t.optJSONArray("cell_data");
                if (cells == null || cells.length() == 0) continue;
                JSONObject main = cells.optJSONObject(0);
                if (main == null) continue;
                // 顺便缓存 cell_id，省掉 ensureCellId() 那一次请求
                String cid = main.optString("cell_id", "");
                if (!cid.isEmpty() && !"0".equals(cid)) sCellId = cid;

                JSONArray subs = main.optJSONArray("cell_data");
                if (subs == null) continue;
                for (int j = 0; j < subs.length(); j++) {
                    JSONObject sub = subs.optJSONObject(j);
                    if (sub == null) continue;
                    JSONArray vd = sub.optJSONArray("video_data");
                    if (vd == null) continue;
                    for (int k = 0; k < vd.length(); k++) {
                        JSONObject it = vd.optJSONObject(k);
                        if (it == null) continue;
                        Vod v = toVod(it);
                        if (v != null) out.add(v);
                    }
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("[红果] tab 解析失败 " + e);
        }
        return out;
    }

    /**
     * landing 分类列表。
     * 返回 null 表示接口不可用（降级），返回空 List 表示可用但确实没数据。
     */
    private static List<Vod> categoryByLanding(String cid, int page) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("category_tab", TAB_TYPE);
        p.put("category_id", cid);
        if (page > 1) p.put("offset", String.valueOf((page - 1) * PAGE_SIZE));

        JSONObject root = parse(get("/reading/bookapi/new_category/landing/v1/", p));
        if (root == null) return null;
        JSONObject d = root.optJSONObject("data");
        if (d == null) return null;

        JSONArray arr = d.optJSONArray("book_info");
        if (arr == null) arr = d.optJSONArray("book_list");
        if (arr == null) arr = d.optJSONArray("items");
        if (arr == null) return new ArrayList<>();

        List<Vod> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it == null) continue;
            Vod v = toVod(it);
            if (v != null) out.add(v);
        }
        SpiderDebug.log("[红果] landing cid=" + cid + " pg=" + page + " out=" + out.size());
        return out;
    }

    /**
     * 降级：bookmall/cell/change。
     * 每次只返回 1 条，且必须带上一轮的 session_id 才能拿到下一条 ——
     * 不带 session_id 每次都返回同一条（看起来像「翻页无效」）。
     * 所以这里按页攒 FALLBACK_PAGE 条，并把 session 缓存在静态字段里。
     */
    private static List<Vod> categoryByCell(int page) {
        String cellId = ensureCellId();
        if (cellId.isEmpty()) return new ArrayList<>();

        // 页码不连续（用户跳页）就重置会话链，从头开始
        if (page != sCellPage + 1) {
            sCellSession = "";
            sCellOffset = "0";
            sCellPage = 0;
        }

        List<Vod> out = new ArrayList<>();
        for (int i = 0; i < FALLBACK_PAGE; i++) {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("cell_id", cellId);
            if (!sCellSession.isEmpty()) {
                p.put("session_id", sCellSession);
                p.put("next_offset", sCellOffset);
            }
            JSONObject root = parse(get("/reading/bookapi/bookmall/cell/change/v1/", p));
            if (root == null) break;
            JSONObject d = root.optJSONObject("data");
            if (d == null) break;

            JSONObject view = d.optJSONObject("cell_view");
            JSONArray arr = view == null ? null : view.optJSONArray("video_data");
            if (arr == null || arr.length() == 0) break;
            for (int j = 0; j < arr.length(); j++) {
                JSONObject it = arr.optJSONObject(j);
                if (it == null) continue;
                Vod v = toVod(it);
                if (v != null) out.add(v);
            }
            String sid = d.optString("session_id", "");
            if (sid.isEmpty()) break;
            sCellSession = sid;
            sCellOffset = d.optString("next_offset", "0");
            if (!d.optBoolean("has_more", false)) break;
            // 这个循环每次都是一次真实请求，节流一下，别让风控看到连发
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {
            }
        }
        sCellPage = page;
        SpiderDebug.log("[红果] cell 降级 pg=" + page + " out=" + out.size());
        return out;
    }

    /** 从 tab/v 拿剧场主 cell 的 cell_id（全站只有这一个） */
    private static String ensureCellId() {
        if (!sCellId.isEmpty()) return sCellId;
        JSONObject root = parse(get("/reading/bookapi/bookmall/tab/v", null));
        if (root == null) return "";
        try {
            JSONArray tabs = root.getJSONObject("data").getJSONArray("tab_item");
            for (int i = 0; i < tabs.length(); i++) {
                JSONObject t = tabs.optJSONObject(i);
                if (t == null || t.optInt("tab_type", -1) != 8) continue;
                JSONArray cells = t.optJSONArray("cell_data");
                if (cells == null || cells.length() == 0) continue;
                String cid = cells.optJSONObject(0).optString("cell_id", "");
                if (!cid.isEmpty() && !"0".equals(cid)) {
                    sCellId = cid;
                    return cid;
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("[红果] 取 cell_id 失败 " + e);
        }
        return "";
    }

    /** video_data / book_info 两种结构统一成一个 Vod */
    private static Vod toVod(JSONObject it) {
        String id = pick(it, "series_id", "book_id", "series_id_str", "id");
        String name = pick(it, "title", "book_name", "series_title", "name");
        if (id.isEmpty() || name.isEmpty()) return null;

        // 成人向二次过滤
        String cat = it.optString("category_schema", "") + it.optString("category", "");
        if (isAdult(name) || isAdult(cat)) return null;

        Vod v = new Vod();
        v.setVodId(id);
        v.setVodName(name);
        v.setVodPic(pic(pick(it, "cover", "horiz_cover", "series_cover", "book_cover")));

        String remark = pick(it, "sub_title");
        if (remark.isEmpty()) {
            int cnt = it.optInt("episode_cnt", 0);
            if (cnt > 0) remark = "共" + cnt + "集";
        }
        // 评分并进备注（Vod 没有独立的 score setter）
        String score = it.optString("score", "");
        if (!score.isEmpty() && !"0".equals(score) && !"0.0".equals(score)) {
            remark = remark.isEmpty() ? score + "分" : remark + " · " + score + "分";
        }
        v.setVodRemarks(remark);
        return v;
    }

    // ==================== 详情 ====================

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return "";
        String sid = ids.get(0).trim();

        Map<String, String> p = new LinkedHashMap<>();
        p.put("series_id", sid);
        JSONObject root = parse(post("/novel/player/video_detail/v1/", p,
                "{\"series_id\":\"" + sid + "\"}"));
        if (root == null) {
            SpiderDebug.log("[红果] 详情不可用（降级）" + sid);
            return "";
        }
        JSONObject d = root.optJSONObject("data");
        if (d == null) return "";
        JSONObject vd = d.optJSONObject("video_data");
        if (vd == null) return "";

        Vod v = new Vod();
        v.setVodId(sid);
        v.setVodName(pick(vd, "series_title", "book_name", "title"));
        v.setVodPic(pic(pick(vd, "series_cover", "cover", "book_cover")));
        v.setVodContent(pick(vd, "series_intro", "intro", "description", "book_abstract"));

        int cnt = vd.optInt("episode_cnt", 0);
        v.setVodRemarks(cnt > 0 ? "共" + cnt + "集" : "");

        String status = pick(vd, "series_status");
        if (!status.isEmpty()) v.setVodArea(status);

        JSONArray list = vd.optJSONArray("video_list");
        if (list == null || list.length() == 0) {
            SpiderDebug.log("[红果] 详情无剧集 " + sid);
            return "";
        }

        // 一集一个播放项，URL 里编码 "series_id|vid"，playerContent 再拆
        StringBuilder playUrl = new StringBuilder();
        int real = 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject ep = list.optJSONObject(i);
            if (ep == null) continue;
            String vid = ep.optString("vid", "");
            if (vid.isEmpty()) continue;
            // disable_play=true 是禁播集，但仍然列出来（有的集可能只是当前地区受限）
            int idx = ep.optInt("vid_index", i + 1);
            String title = ep.optString("title", "");
            // title 一般是整部剧的文案，不是单集名，所以只在它确实像单集名时才用
            String label = (title.length() > 0 && title.length() <= 12 && title.contains("第"))
                    ? title : ("第" + idx + "集");
            if (real > 0) playUrl.append('#');
            // 分隔符用 '_' 而不是 '|'：CatVod 系客户端会把 URL 里的 '|' 当成
            // 「url|header」的 header 分隔，导致播放地址被截断。
            // series_id 和 vid 都是纯数字，用 '_' 没有歧义。
            playUrl.append(label).append('$').append(sid).append('_').append(vid);
            real++;
        }
        if (real == 0) return "";

        v.setVodPlayFrom("红果");
        v.setVodPlayUrl(playUrl.toString());
        SpiderDebug.log("[红果] 详情 " + sid + " 集数=" + real);
        return Result.get().vod(v).string();
    }

    // ==================== 搜索 ====================

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("query", key);
        JSONObject root = parse(get("/reading/bookapi/search/suggest/v1/", p));
        if (root == null) {
            SpiderDebug.log("[红果] 搜索不可用（降级）" + key);
            return "";
        }
        JSONObject d = root.optJSONObject("data");
        if (d == null) return "";

        // query_result_v2 才带 series_id（keyword 字段就是它），query_result 只有剧名
        JSONArray arr = d.optJSONArray("query_result_v2");
        if (arr == null) return "";

        List<Vod> vods = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it == null) continue;
            String name = it.optString("name", "");
            String sid = it.optString("keyword", "");
            if (name.isEmpty() || sid.isEmpty()) continue;
            if (isAdult(name)) continue;
            Vod v = new Vod();
            v.setVodId(sid);
            v.setVodName(name);
            // 联想接口不给封面和简介，列表里只能是灰块，点进去才有完整信息
            v.setVodRemarks("红果");
            vods.add(v);
        }
        SpiderDebug.log("[红果] 搜索 " + key + " -> " + vods.size());
        return Result.get().vod(vods).string();
    }

    // ==================== 播放 ====================

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String raw = id == null ? "" : id.trim();
        JSONObject out = new JSONObject();

        // detailContent 里拼的是 "series_id_vid"（用 '_' 不用 '|'，理由见 detailContent）
        int bar = raw.lastIndexOf('_');
        if (bar <= 0 || bar >= raw.length() - 1) {
            SpiderDebug.log("[红果] 播放参数格式不对 " + raw);
            out.put("parse", 1);
            out.put("url", "");
            return out.toString();
        }
        String sid = raw.substring(0, bar);
        String vid = raw.substring(bar + 1);

        Map<String, String> p = new LinkedHashMap<>();
        p.put("video_id", vid);
        p.put("series_id", sid);
        JSONObject root = parse(post("/novel/player/video_model/v1/", p,
                "{\"video_id\":\"" + vid + "\",\"series_id\":\"" + sid + "\"}"));
        if (root == null) {
            SpiderDebug.log("[红果] 播放接口不可用（降级）" + vid);
            out.put("parse", 1);
            out.put("url", "");
            return out.toString();
        }

        JSONObject d = root.optJSONObject("data");
        String modelRaw = d == null ? "" : d.optString("video_model", "");
        if (modelRaw.isEmpty()) {
            SpiderDebug.log("[红果] video_model 为空");
            out.put("parse", 1);
            out.put("url", "");
            return out.toString();
        }

        // ★ video_model 是嵌套的 JSON 字符串，必须二次解析
        JSONObject model = parse(modelRaw);
        if (model == null) {
            out.put("parse", 1);
            out.put("url", "");
            return out.toString();
        }

        JSONArray vl = model.optJSONArray("video_list");
        if (vl == null || vl.length() == 0) {
            out.put("parse", 1);
            out.put("url", "");
            return out.toString();
        }

        // 优先挑非 bytevc2（H.265）的档位，兼容性更好；没有就退回第一条
        String mainUrl = "";
        String fallback = "";
        for (int i = 0; i < vl.length(); i++) {
            JSONObject one = vl.optJSONObject(i);
            if (one == null) continue;
            String u = one.optString("main_url", "");
            if (u.isEmpty()) continue;
            if (fallback.isEmpty()) fallback = u;
            JSONObject meta = one.optJSONObject("video_meta");
            String codec = meta == null ? "" : meta.optString("codec_type", "");
            if (!"bytevc2".equalsIgnoreCase(codec)) {
                mainUrl = u;
                break;
            }
        }
        if (mainUrl.isEmpty()) mainUrl = fallback;

        if (mainUrl.isEmpty()) {
            out.put("parse", 1);
            out.put("url", "");
            return out.toString();
        }

        out.put("parse", 0);
        out.put("url", mainUrl);
        JSONObject h = new JSONObject();
        h.put("User-Agent", UA);
        out.put("header", h.toString());
        SpiderDebug.log("[红果] play ok vid=" + vid);
        return out.toString();
    }
}
