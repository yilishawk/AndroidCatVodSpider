package com.github.catvod.spider;

import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 360影视（www.360kan.com）
 *
 * 一、接口（全部明文，无签名，PC 直连）
 *     api.web.360kan.com/v1/filter/list  分类列表  catid/rank/cat/year/area/size/pageno
 *     api.web.360kan.com/v1/detail       详情      cat/id[/site]
 *     api.so.360kan.com/index            搜索      kw/pageno（返回可能包 __jp(...)，需剥壳）
 *
 * 二、详情分三种形态（按 catid 分流，不能混着读）
 *     catid=1 电影   → playlinksdetail[site].default_url      一条正片
 *     catid=3 综艺   → defaultepisode[]（每集自带 url + name + pubdate）
 *     其它（2/4）    → playlink_sites[]，再逐站点请求 detail?site=xxx
 *                      拿 allepidetail[site][]；空则退回 playlinks[site] 单条
 *     —— 注意 detail 的 allepidetail 只填「默认站点」那一路，其余必须逐站点拉。
 *
 * 三、播放
 *     360kan 是纯聚合站：官方 /v1/detail 只给各平台「播放页 URL」
 *     （优酷/爱奇艺/腾讯/芒果/乐视），不提供 m3u8 直链——已在线逐个源实测确认，
 *     playlinksdetail[site].default_url / allepidetail[site][].url 全是第三方播放页，
 *     无一是 .m3u8/.mp4。
 *     所以 playerContent 一律返回 parse:1，把该播放页 URL 连同 Referer 交给
 *     客户端自己的解析器（TVBox 等客户端普遍内置优酷/爱奇艺/腾讯解析）。
 *     —— 曾经尝试改用 dmku.byteamone.cn/VIDEOINFO.php 自建解析，后证实那是
 *        「公共弹幕库」的弹幕元数据接口（引擎 funcGP 里 config.api = cg.dmku），
 *        响应只有 vod_name/vod_year/episode 等匹配字段、从不返回播放地址，
 *        故已移除。详见《分析报告》§5.4「接口定位纠错」。
 *
 * 定制：首页不出推荐流；国产剧置顶（锁 area=大陆）；过滤伦理/成人子分类。
 */
public class Vod360 extends Spider {

    // ==================== 常量 ====================

    private static final String HOST = "https://www.360kan.com";
    private static final String API = "https://api.web.360kan.com";
    private static final String SEARCH_API = "https://api.so.360kan.com";

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final int PAGE_SIZE = 35;

    /**
     * 分类：{type_id, 名称, catid, 锁定地区}
     * 国产剧用 "2_大陆" 表达「catid=2 + area 锁死」，排第一位。
     */
    private static final String[][] CATES = {
            {"2_大陆", "国产剧", "2", "大陆"},
            {"1", "电影", "1", ""},
            {"2", "电视剧", "2", ""},
            {"3", "综艺", "3", ""},
            {"4", "动漫", "4", ""},
    };

    /**
     * 子分类。只放实测有效的值（无效值会让接口返回 data=null，客户端表现为空列表）：
     * 剧的「军旅/家庭/青春」、动漫的「日漫」都是无效值，已剔除；
     * 动漫「国漫」只有 2 条，一并剔除；「伦理」是成人向，按定制要求不暴露。
     */
    private static final Map<String, String[]> CATS = new HashMap<>();

    static {
        CATS.put("1", new String[]{"动作", "喜剧", "爱情", "科幻", "剧情", "战争",
                "恐怖", "悬疑", "犯罪", "动画", "纪录片"});
        CATS.put("2", new String[]{"古装", "偶像", "悬疑", "言情", "都市", "武侠", "历史"});
        CATS.put("3", new String[]{"真人秀", "选秀", "脱口秀", "访谈", "搞笑"});
        CATS.put("4", new String[]{"热血", "少儿", "冒险"});
    }

    /** 地区：中文直传，实测有效 */
    private static final String[] AREAS = {"大陆", "香港", "台湾", "美国", "韩国", "日本"};

    /** 年份：只接受单一年份，区间（如 2010-2019）会让接口返回 data=null */
    private static final int YEAR_FROM = 2026;
    private static final int YEAR_TO = 2010;

    /** 排序：rankhot 最近热映 / ranklatest 最近上架 / rankpoint 最受好评 */
    private static final String[][] SORTS = {
            {"rankhot", "最近热映"},
            {"ranklatest", "最近上架"},
            {"rankpoint", "最受好评"},
    };

    /** 站点名 + 排序权重（越靠前越优） */
    private static final String[][] SITES = {
            {"qq", "腾讯视频", "1"},
            {"qiyi", "爱奇艺", "2"},
            {"imgo", "芒果TV", "3"},
            {"mgtv", "芒果TV", "3"},
            {"youku", "优酷", "4"},
            {"bilibili", "B站", "5"},
            {"bilibili1", "B站", "5"},
            {"sohu", "搜狐", "6"},
            {"leshi", "乐视", "7"},
            {"letv", "乐视", "7"},
            {"douyin", "抖音", "8"},
            {"cntv", "央视网", "9"},
            {"pptv", "PPTV", "10"},
            {"funshion", "风行", "11"},
    };

    /** 成人向关键词：筛选项剔除 + 结果二次过滤 */
    private static final String[] ADULT_WORDS = {
            "伦理", "倫理", "情色", "写真", "福利", "三级", "無碼", "无码", "擦边",
    };

    // ==================== 基础工具 ====================

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20");
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

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    private static String request(String url, String method, String body, Map<String, String> headers) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (body != null) {
                conn.setDoOutput(true);
                // 调用方在 headers 里带了 Content-Type 就不再用默认的 json
                if (headers == null || !headers.containsKey("Content-Type")) {
                    conn.setRequestProperty("Content-Type", "application/json");
                }
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.flush();
                os.close();
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) return "";
            return new String(readAll(is), "UTF-8");
        } catch (Exception e) {
            SpiderDebug.log("[360] http 异常 " + url + " " + e);
            return "";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static Map<String, String> header() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", HOST);
        h.put("Accept", "application/json, text/plain, */*");
        return h;
    }

    /** GET 并解析 JSON；失败返回 null */
    private static JSONObject getJson(String url) {
        String s = request(url, "GET", null, header());
        if (s.isEmpty()) return null;
        s = stripJsonp(s);
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            SpiderDebug.log("[360] JSON 解析失败 " + url + " " + e);
            return null;
        }
    }

    /** 搜索接口有时返回 __jp(...) 包裹，剥掉 */
    private static String stripJsonp(String s) {
        String t = s.trim();
        if (!t.startsWith("{")) {
            int l = t.indexOf('(');
            int r = t.lastIndexOf(')');
            if (l > 0 && r > l) return t.substring(l + 1, r);
        }
        return t;
    }

    private static String optStr(JSONObject o, String key) {
        if (o == null) return "";
        String v = o.optString(key);
        return v == null || "null".equals(v) ? "" : v;
    }

    private static String pic(String cover) {
        if (cover == null || cover.isEmpty()) return "";
        if (cover.startsWith("//")) return "https:" + cover;
        if (cover.startsWith("http")) return cover;
        return "https:" + cover;
    }

    private static String cleanUrl(String site, String url) {
        if (url == null || url.isEmpty()) return "";
        // 优酷的 vid 在 query 里，不能直接砍；其余站点统一去掉 query
        if ("youku".equals(site)) {
            String base = url.split("\\?")[0];
            int i = url.indexOf("vid=");
            if (i >= 0) {
                String vid = url.substring(i + 4);
                int amp = vid.indexOf('&');
                if (amp >= 0) vid = vid.substring(0, amp);
                return base + "?vid=" + vid;
            }
            return base;
        }
        return url.split("\\?")[0];
    }

    private static String siteName(String site) {
        for (String[] s : SITES) {
            if (s[0].equals(site)) return s[1];
        }
        return site.toUpperCase();
    }

    private static int siteRank(String site) {
        for (String[] s : SITES) {
            if (s[0].equals(site)) return Integer.parseInt(s[2]);
        }
        return 99;
    }

    private static boolean isAdult(String text) {
        if (text == null) return false;
        for (String w : ADULT_WORDS) {
            if (text.contains(w)) return true;
        }
        return false;
    }

    /** 条目是否成人向：标题或分类带关键词 */
    private static boolean isAdultItem(JSONObject it) {
        if (it == null) return true;
        if (isAdult(optStr(it, "title"))) return true;
        JSONArray mc = it.optJSONArray("moviecategory");
        if (mc != null) {
            for (int i = 0; i < mc.length(); i++) {
                if (isAdult(mc.optString(i))) return true;
            }
        }
        return false;
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

    /**
     * 筛选项。CatVod 按 type_id 取 filters，所以键必须是 class 里的 type_id
     * （如 "2_大陆"），不能只写 catid，否则带锁定的分类取不到筛选。
     */
    private static JSONObject buildFilters() {
        JSONObject out = new JSONObject();
        for (String[] cate : CATES) {
            String tid = cate[0];
            String catid = cate[2];
            boolean lockArea = !cate[3].isEmpty();
            try {
                JSONArray fl = new JSONArray();

                // 类型
                JSONArray cats = new JSONArray();
                cats.put(val("全部", ""));
                String[] cs = CATS.get(catid);
                if (cs != null) {
                    for (String c : cs) {
                        if (isAdult(c)) continue;   // 成人子分类不暴露
                        cats.put(val(c, c));
                    }
                }
                fl.put(field("class", "类型", cats));

                // 地区：锁死的分类（国产剧）不暴露，选了也不生效
                if (!lockArea) {
                    JSONArray areas = new JSONArray();
                    areas.put(val("全部", ""));
                    for (String a : AREAS) areas.put(val(a, a));
                    fl.put(field("area", "地区", areas));
                }

                // 年份：只给单一年份，接口不支持区间
                JSONArray years = new JSONArray();
                years.put(val("全部", ""));
                for (int y = YEAR_FROM; y >= YEAR_TO; y--) years.put(val(String.valueOf(y), String.valueOf(y)));
                fl.put(field("year", "年份", years));

                // 排序
                JSONArray sorts = new JSONArray();
                for (String[] s : SORTS) sorts.put(val(s[1], s[0]));
                fl.put(field("by", "排序", sorts));

                out.put(tid, fl);
            } catch (Exception e) {
                SpiderDebug.log("[360] 筛选项构建异常 " + tid + " " + e);
            }
        }
        return out;
    }

    private static JSONObject val(String n, String v) {
        JSONObject o = new JSONObject();
        o.put("n", n);
        o.put("v", v);
        return o;
    }

    private static JSONObject field(String key, String name, JSONArray values) {
        JSONObject o = new JSONObject();
        o.put("key", key);
        o.put("name", name);
        o.put("value", values);
        return o;
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
        String catid = t;
        String lockedArea = "";
        if (t.contains("_")) {
            catid = t.substring(0, t.indexOf('_'));
            lockedArea = t.substring(t.indexOf('_') + 1);
        }

        StringBuilder url = new StringBuilder(API).append("/v1/filter/list");
        url.append("?catid=").append(enc(catid));
        url.append("&rank=").append(enc(pick(extend, "by", "rankhot")));
        url.append("&cat=").append(enc(pick(extend, "class", "")));
        url.append("&year=").append(enc(pick(extend, "year", "")));
        url.append("&area=").append(enc(lockedArea.isEmpty() ? pick(extend, "area", "") : lockedArea));
        url.append("&size=").append(PAGE_SIZE);
        url.append("&pageno=").append(page);

        List<Vod> vods = new ArrayList<>();
        int total = 0;
        JSONObject o = getJson(url.toString());
        if (o != null) {
            JSONObject d = o.optJSONObject("data");
            if (d != null) {
                total = d.optInt("total");
                JSONArray items = d.optJSONArray("movies");
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject it = items.optJSONObject(i);
                        if (it == null || isAdultItem(it)) continue;
                        vods.add(toVod(catid, it));
                    }
                }
            }
        }
        // 翻页用接口 total 判断，不能用过滤后的条数
        boolean more = total > 0 && page * PAGE_SIZE < total;
        SpiderDebug.log("[360] category tid=" + tid + " pg=" + page
                + " total=" + total + " out=" + vods.size());
        return Result.get().vod(vods).page(page, more ? page + 1 : page, PAGE_SIZE, total).string();
    }

    private static String pick(HashMap<String, String> extend, String key, String def) {
        if (extend == null) return def;
        String v = extend.get(key);
        if (v == null || v.trim().isEmpty()) return def;
        return v;
    }

    private static Vod toVod(String catid, JSONObject it) {
        Vod v = new Vod();
        v.setVodId(catid + "|" + optStr(it, "id"));
        v.setVodName(optStr(it, "title"));
        v.setVodPic(pic(optStr(it, "cdncover").isEmpty()
                ? optStr(it, "cover") : optStr(it, "cdncover")));

        // 备注：电影给评分/年份，剧集给更新集数
        String remark;
        if ("1".equals(catid)) {
            String score = optStr(it, "doubanscore");
            if (score.isEmpty()) score = optStr(it, "score");
            remark = "0".equals(score) || score.isEmpty()
                    ? yearOf(optStr(it, "pubdate")) : score + "分";
        } else {
            String up = optStr(it, "upinfo");
            remark = up.isEmpty() ? "全集" : "更新至" + up + "集";
        }
        v.setVodRemarks(remark);
        return v;
    }

    private static String yearOf(String pubdate) {
        return pubdate != null && pubdate.length() >= 4 ? pubdate.substring(0, 4) : "";
    }

    // ==================== 详情 ====================

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return "";
        String idStr = ids.get(0).trim();
        String catid = "2";
        String vid = idStr;
        if (idStr.contains("|")) {
            catid = idStr.substring(0, idStr.indexOf('|'));
            vid = idStr.substring(idStr.indexOf('|') + 1);
        }

        JSONObject o = getJson(API + "/v1/detail?cat=" + enc(catid) + "&id=" + enc(vid));
        if (o == null) return "";
        JSONObject d = o.optJSONObject("data");
        if (d == null) {
            SpiderDebug.log("[360] detail 无数据 id=" + idStr);
            return "";
        }

        Vod v = new Vod();
        v.setVodId(idStr);
        v.setVodName(optStr(d, "title"));
        v.setVodPic(pic(optStr(d, "cdncover").isEmpty()
                ? optStr(d, "cover") : optStr(d, "cdncover")));
        v.setVodContent(optStr(d, "description"));
        v.setVodYear(yearOf(optStr(d, "pubdate")));
        v.setVodArea(joinArray(d.optJSONArray("area")));
        v.setVodActor(joinArray(d.optJSONArray("actor")));
        v.setVodDirector(joinArray(d.optJSONArray("director")));
        String up = optStr(d, "upinfo");
        v.setVodRemarks("1".equals(catid) || up.isEmpty() ? "" : ("更新至" + up + "集"));

        // 线路 + 剧集
        List<String[]> sources = new ArrayList<>();   // {site, name, urls}
        if ("1".equals(catid)) {
            sources.addAll(movieSources(d));
        } else if ("3".equals(catid)) {
            sources.addAll(zongyiSources(d));
        } else {
            sources.addAll(seriesSources(catid, vid, d));
        }
        if (sources.isEmpty()) {
            SpiderDebug.log("[360] detail 无可用线路 id=" + idStr);
            return "";
        }

        // 按站点优先级排序
        Collections.sort(sources, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return siteRank(a[0]) - siteRank(b[0]);
            }
        });

        List<String> froms = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (String[] s : sources) {
            froms.add(s[1]);
            urls.add(s[2]);
        }
        v.setVodPlayFrom(join(froms, "$$$"));
        v.setVodPlayUrl(join(urls, "$$$"));

        List<Vod> list = new ArrayList<>();
        list.add(v);
        SpiderDebug.log("[360] detail id=" + idStr + " 线路=" + froms.size()
                + " 首线路集数=" + urls.get(0).split("#").length);
        return Result.get().vod(list).string();
    }

    /** 电影：playlinksdetail[site].default_url，一条正片 */
    private static List<String[]> movieSources(JSONObject d) {
        List<String[]> out = new ArrayList<>();
        JSONObject pld = d.optJSONObject("playlinksdetail");
        if (pld == null) return out;
        java.util.Iterator<String> keys = pld.keys();
        while (keys.hasNext()) {
            String site = keys.next();
            JSONObject it = pld.optJSONObject(site);
            if (it == null) continue;
            String u = cleanUrl(site, optStr(it, "default_url"));
            if (u.isEmpty()) continue;
            out.add(new String[]{site, siteName(site), "正片$" + u});
        }
        return out;
    }

    /**
     * 综艺：defaultepisode[] 每集自带 url/name/pubdate。
     * 这里按站点分组，用 playlinks 里的站点给线路命名；
     * 实测综艺通常只有一个站点，取第一个能出集数的即可。
     */
    private static List<String[]> zongyiSources(JSONObject d) {
        List<String[]> out = new ArrayList<>();
        JSONArray eps = d.optJSONArray("defaultepisode");
        if (eps == null || eps.length() == 0) return out;

        JSONObject links = d.optJSONObject("playlinks");
        String site = "";
        if (links != null) {
            java.util.Iterator<String> keys = links.keys();
            if (keys.hasNext()) site = keys.next();
        }

        List<String> epList = new ArrayList<>();
        for (int i = 0; i < eps.length(); i++) {
            JSONObject ep = eps.optJSONObject(i);
            if (ep == null) continue;
            String u = cleanUrl(site, optStr(ep, "url"));
            if (u.isEmpty()) continue;
            String name = optStr(ep, "name");
            if (name.isEmpty()) {
                String num = optStr(ep, "playlink_num");
                name = num.isEmpty() ? ("第" + (i + 1) + "期") : ("第" + num + "期");
            }
            String pub = optStr(ep, "pubdate");
            if (!pub.isEmpty()) name = name + " (" + pub + ")";
            epList.add(name + "$" + u);
        }
        if (!epList.isEmpty()) {
            out.add(new String[]{site, siteName(site), join(epList, "#")});
        }
        return out;
    }

    /**
     * 电视剧 / 动漫：playlink_sites[] 逐站点请求。
     * detail 自带的 allepidetail 只填默认站点那一路，其余站点必须带 site 参数重新请求，
     * 响应里的 allepidetail[site] 才是该线路的完整剧集。
     */
    private static List<String[]> seriesSources(String catid, String vid, JSONObject d) {
        List<String[]> out = new ArrayList<>();
        JSONArray sites = d.optJSONArray("playlink_sites");
        if (sites == null) return out;

        for (int i = 0; i < sites.length(); i++) {
            String site = sites.optString(i);
            if (site == null || site.isEmpty()) continue;
            JSONObject sd = null;
            try {
                JSONObject o = getJson(API + "/v1/detail?cat=" + enc(catid)
                        + "&id=" + enc(vid) + "&site=" + enc(site));
                sd = o == null ? null : o.optJSONObject("data");
            } catch (Exception e) {
                SpiderDebug.log("[360] site 请求异常 " + site + " " + e);
            }

            List<String> epList = new ArrayList<>();
            JSONArray eps = null;
            if (sd != null) {
                JSONObject ae = sd.optJSONObject("allepidetail");
                if (ae != null) eps = ae.optJSONArray(site);
            }
            if (eps != null && eps.length() > 0) {
                for (int j = 0; j < eps.length(); j++) {
                    JSONObject ep = eps.optJSONObject(j);
                    if (ep == null) continue;
                    String u = cleanUrl(site, optStr(ep, "url"));
                    if (u.isEmpty()) continue;
                    String num = optStr(ep, "playlink_num");
                    epList.add("第" + (num.isEmpty() ? String.valueOf(j + 1) : num) + "集$" + u);
                }
            } else if (sd != null) {
                // 没有分集明细时退回单条播放页
                JSONObject links = sd.optJSONObject("playlinks");
                String u = links == null ? "" : cleanUrl(site, optStr(links, site));
                if (!u.isEmpty()) epList.add("播放$" + u);
            }
            if (epList.isEmpty()) continue;
            out.add(new String[]{site, siteName(site), join(epList, "#")});
        }
        return out;
    }

    private static String joinArray(JSONArray arr) {
        if (arr == null) return "";
        List<String> l = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i);
            if (s != null && !s.isEmpty()) l.add(s);
        }
        return join(l, ",");
    }

    // ==================== 搜索 ====================

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        String url = SEARCH_API + "/index?force_v=1&kw=" + enc(key)
                + "&from=&pageno=1&v_ap=1&tab=all";
        Map<String, String> h = header();
        h.put("Referer", "https://so.360kan.com/?kw=" + enc(key));

        String s = request(url, "GET", null, h);
        List<Vod> vods = new ArrayList<>();
        if (!s.isEmpty()) {
            JSONObject o = null;
            try {
                o = new JSONObject(stripJsonp(s));
            } catch (Exception e) {
                SpiderDebug.log("[360] search JSON 解析失败 " + e);
            }
            if (o != null) {
                JSONObject d = o.optJSONObject("data");
                JSONObject longData = d == null ? null : d.optJSONObject("longData");
                JSONArray rows = longData == null ? null : longData.optJSONArray("rows");
                if (rows != null) {
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject it = rows.optJSONObject(i);
                        if (it == null || isAdultItem(it)) continue;
                        String vid = optStr(it, "en_id");
                        if (vid.isEmpty()) continue;
                        String catid = optStr(it, "cat_id");
                        if (catid.isEmpty()) catid = "2";

                        String name = optStr(it, "titleTxt");
                        if (name.isEmpty()) name = stripHtml(optStr(it, "title"));
                        if (isAdult(name)) continue;

                        String remark;
                        if ("1".equals(catid)) {
                            remark = optStr(it, "year");
                        } else {
                            JSONObject ci = it.optJSONObject("coverInfo");
                            remark = ci == null ? "" : optStr(ci, "txt");
                            if (remark.isEmpty()) remark = optStr(it, "year");
                        }

                        Vod v = new Vod();
                        v.setVodId(catid + "|" + vid);
                        v.setVodName(name);
                        v.setVodPic(pic(optStr(it, "cover")));
                        v.setVodRemarks(remark);
                        vods.add(v);
                    }
                }
            }
        }
        SpiderDebug.log("[360] search key=" + key + " out=" + vods.size());
        return Result.get().vod(vods).string();
    }

    private static String stripHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", "");
    }

    // ==================== 播放 ====================

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String raw = id == null ? "" : id.trim();
        JSONObject o = new JSONObject();

        // 360kan 官方 API 只给第三方播放页 URL（优酷/爱奇艺/腾讯/芒果/乐视），
        // 不出 m3u8 直链；直链解析交给客户端解析器，这里只负责把 URL 和
        // Referer 递过去。曾经那条 POST dmku.byteamone.cn/VIDEOINFO.php 的
        // 自建解析已证实是弹幕元数据接口、永不返回播放地址，故移除。
        o.put("parse", 1);
        o.put("playUrl", "");
        o.put("url", raw);
        if (raw.isEmpty()) {
            o.put("header", "");
            SpiderDebug.log("[360] play 地址为空");
        } else {
            // 播放页在第三方站点上，Referer/Origin 用该页自己的源站
            String ref = originOf(raw);
            JSONObject h = new JSONObject();
            h.put("user-agent", UA);
            h.put("referer", ref);
            h.put("origin", ref);
            o.put("header", h.toString());
            SpiderDebug.log("[360] play 交客户端解析 " + raw);
        }
        o.put("danmaku", "https://yilishawk.de5.net/danmu?url=" + enc(raw));
        return o.toString();
    }

    /** 取 URL 的 origin（scheme://host），播放直链的 Referer 用它 */
    private static String originOf(String url) {
        try {
            URL u = new URL(url);
            return u.getProtocol() + "://" + u.getHost();
        } catch (Exception e) {
            return HOST;
        }
    }
}
