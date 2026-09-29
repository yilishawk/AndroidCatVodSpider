package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AVDB API (avdbapi.com) + PasswordGate 门禁
 * 文档: https://avdbapi.com/zh/index.php/label/help.html
 * 接口: https://avdbapi.com/zh/api.php/provide/vod
 *
 * init 时走 PasswordGate，未解锁则源加载失败。
 * 依赖同包 PasswordGate.java（密码当前为 123456789 的 SHA-256）。
 */
public class Avdb extends Spider {

    private String host = "https://avdbapi.com";
    private String api = "https://avdbapi.com/zh/api.php/provide/vod";

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final int PAGE_SIZE = 24;

    private boolean unlocked = false;
    private Map<String, String> headers;

    public Avdb() {
        headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "application/json");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Referer", host + "/");
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);

        if (!TextUtils.isEmpty(extend) && extend.trim().startsWith("http")) {
            String e = extend.trim().replaceAll("/+$", "");
            if (e.contains("api.php")) {
                api = e;
                int i = e.indexOf("/api.php");
                if (i > 0) host = e.substring(0, i);
            } else {
                host = e;
                api = host + "/zh/api.php/provide/vod";
            }
            headers.put("Referer", host + "/");
        }

        this.unlocked = PasswordGate.ensureUnlocked(context);
        if (!this.unlocked) {
            throw new Exception("Password verification failed. Source initialization aborted.");
        }
    }

    private String get(String url) {
        try {
            String body = OkHttp.string(url, headers);
            return body == null ? "" : body;
        } catch (Exception e) {
            return "";
        }
    }

    private String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private String joinArr(JSONArray arr) {
        if (arr == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            String v = arr.optString(i, "").trim();
            if (TextUtils.isEmpty(v) || "Updating".equalsIgnoreCase(v)) continue;
            if (sb.length() > 0) sb.append("、");
            sb.append(v);
        }
        return sb.toString();
    }

    private String strField(JSONObject o, String... keys) {
        for (String k : keys) {
            if (!o.has(k) || o.isNull(k)) continue;
            Object v = o.opt(k);
            if (v instanceof JSONArray) {
                String j = joinArr((JSONArray) v);
                if (!TextUtils.isEmpty(j)) return j;
            } else {
                String s = String.valueOf(v).trim();
                if (!TextUtils.isEmpty(s) && !"null".equals(s) && !"Updating".equalsIgnoreCase(s)) {
                    return s;
                }
            }
        }
        return "";
    }

    /**
     * ★ 修改 1 (2026-09-29, 凯哥拍板):
     * 原代码取 strField(o, "year"), 但 avdbapi 实测 "year" 字段是脏数据 ("2 May, 202" 这种截断值, 不是 4 位年份),
     * 真年份在 vod_pubdate ("2 May, 2025"). 改成优先 vod_pubdate, 正则抽 4 位.
     * 影响面: 列表/详情里年份列 + 筛选 "年份" 的传参. 回退: 把 yearOf 调用点改回 strField(o, "year") 即可.
     */
    private static final Pattern YEAR_4_RE = Pattern.compile("(19|20)\\d{2}");
    private String yearOf(JSONObject o) {
        String raw = strField(o, "vod_pubdate", "created_at", "year");
        if (TextUtils.isEmpty(raw)) return "";
        Matcher m = YEAR_4_RE.matcher(raw);
        return m.find() ? m.group() : "";
    }

    private JSONObject apiGet(String query) {
        String body = get(api + (query.startsWith("?") ? query : "?" + query));
        if (TextUtils.isEmpty(body)) return null;
        try {
            int br = body.indexOf('{');
            if (br > 0) body = body.substring(br);
            JSONObject j = new JSONObject(body);
            if (j.optInt("code", 0) != 1) return null;
            return j;
        } catch (Exception e) {
            return null;
        }
    }

    private List<Vod> parseList(JSONArray arr) {
        List<Vod> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                String id = String.valueOf(o.opt("id"));
                if (TextUtils.isEmpty(id) || "null".equals(id) || "0".equals(id)) continue;

                String name = strField(o, "name", "origin_name");
                if (TextUtils.isEmpty(name)) continue;

                String pic = strField(o, "poster_url", "thumb_url");
                String code = strField(o, "movie_code");
                String typeName = strField(o, "type_name");
                String quality = strField(o, "quality");
                String year = yearOf(o);   // ★ 修改 1: 走 yearOf (vod_pubdate 优先)

                StringBuilder remark = new StringBuilder();
                if (!TextUtils.isEmpty(code)) remark.append(code);
                if (!TextUtils.isEmpty(typeName)) {
                    if (remark.length() > 0) remark.append(" · ");
                    remark.append(typeName);
                }
                if (!TextUtils.isEmpty(quality)) {
                    if (remark.length() > 0) remark.append(" · ");
                    remark.append(quality);
                }
                if (!TextUtils.isEmpty(year) && remark.indexOf(year) < 0) {
                    if (remark.length() > 0) remark.append(" · ");
                    remark.append(year);
                }

                Vod vod = new Vod();
                vod.setVodId(id);
                vod.setVodName(name);
                if (!TextUtils.isEmpty(pic)) vod.setVodPic(pic);
                if (remark.length() > 0) vod.setVodRemarks(remark.toString());
                list.add(vod);
            } catch (Exception ignored) {
            }
        }
        return list;
    }

    private void fillPlay(Vod vod, JSONObject o) {
        Object epObj = o.opt("episodes");
        if (epObj == null) return;

        List<String> froms = new ArrayList<>();
        List<String> urls = new ArrayList<>();

        try {
            if (epObj instanceof JSONObject) {
                JSONObject ep = (JSONObject) epObj;
                String serverName = ep.optString("server_name", "VIP");
                JSONObject data = ep.optJSONObject("server_data");
                if (data != null) {
                    List<String> eps = new ArrayList<>();
                    JSONArray names = data.names();
                    if (names != null) {
                        for (int i = 0; i < names.length(); i++) {
                            String key = names.optString(i);
                            JSONObject one = data.optJSONObject(key);
                            if (one == null) continue;
                            String link = one.optString("link_embed", "");
                            if (TextUtils.isEmpty(link)) link = one.optString("link", "");
                            if (TextUtils.isEmpty(link)) continue;
                            String play = extractPlayUrl(link);
                            String title = TextUtils.isEmpty(key) ? "正片" : key;
                            eps.add(title + "$" + play);
                        }
                    }
                    if (!eps.isEmpty()) {
                        froms.add(TextUtils.isEmpty(serverName) ? "线路1" : serverName);
                        urls.add(TextUtils.join("#", eps));
                    }
                }
            } else if (epObj instanceof JSONArray) {
                JSONArray arr = (JSONArray) epObj;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject ep = arr.optJSONObject(i);
                    if (ep == null) continue;
                    String serverName = ep.optString("server_name", "线路" + (i + 1));
                    JSONObject data = ep.optJSONObject("server_data");
                    if (data == null) continue;
                    List<String> eps = new ArrayList<>();
                    JSONArray names = data.names();
                    if (names == null) continue;
                    for (int j = 0; j < names.length(); j++) {
                        String key = names.optString(j);
                        JSONObject one = data.optJSONObject(key);
                        if (one == null) continue;
                        String link = one.optString("link_embed", "");
                        if (TextUtils.isEmpty(link)) continue;
                        eps.add(key + "$" + extractPlayUrl(link));
                    }
                    if (!eps.isEmpty()) {
                        froms.add(serverName);
                        urls.add(TextUtils.join("#", eps));
                    }
                }
            }
        } catch (Exception ignored) {
        }

        if (!froms.isEmpty()) {
            vod.setVodPlayFrom(TextUtils.join("$$$", froms));
            vod.setVodPlayUrl(TextUtils.join("$$$", urls));
        }
    }

    /** 从 link_embed 解出 ?s=<encoded> 里的真 m3u8 url */
    private String extractPlayUrl(String link) {
        if (TextUtils.isEmpty(link)) return link;
        try {
            int idx = link.indexOf("?s=");
            if (idx < 0) idx = link.indexOf("&s=");
            if (idx >= 0) {
                String s = link.substring(idx + 3);
                int amp = s.indexOf('&');
                if (amp > 0) s = s.substring(0, amp);
                s = java.net.URLDecoder.decode(s, "UTF-8");
                if (s.startsWith("http")) return s;
            }
        } catch (Exception ignored) {
        }
        // ★ 修改 3 (2026-09-29): upload18 播放页型 link_embed 没有 ?s=, 上面直接 return link.
        // 这里识别 upload18 域, 走二级解析抠 PLAYER_CONFIG.m3u8; 抠不到则原样返回 link_embed 让壳子嗅探兜底.
        try {
            String hostName = new java.net.URL(link).getHost().toLowerCase();
            if (hostName.endsWith("upload18.org") || hostName.equals("upload18.cc")
                    || hostName.equals("upload18.com")) {
                String m3u8 = extractM3u8FromUpload18(link);
                if (!TextUtils.isEmpty(m3u8)) return m3u8;
            }
        } catch (Exception ignored) {
        }
        return link;
    }

    /**
     * ★ 修改 3 (2026-09-29): upload18 播放页二级解析
     * 服务端把真 m3u8 (helvid.com/m/<base64>?e=&h=&s=&x=&d=&i=&v=&k=) 明文挂在
     * window.PLAYER_CONFIG.m3u8, 前端 u18_*.js 只是透传 + worker 轮换 (_wd/_rt), 不加密.
     * 所以纯 Java GET 一次播放页 HTML, 正则抠出 "m3u8":"..." 即可.
     * 注意: JSON 里的 "/" 是 "\/" 形式, 抠出来要 replace("\\/", "/").
     * 代价: URL 带 e= (epoch 秒, 短 TTL), 过期需重取. 壳子侧若发现 403/404 应再次调用 playerContent.
     */
    private static final Pattern U18_M3U8_RE =
            Pattern.compile("\"m3u8\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*?)\"");

    private String extractM3u8FromUpload18(String playPageUrl) {
        if (TextUtils.isEmpty(playPageUrl)) return "";
        try {
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", UA);
            h.put("Referer", playPageUrl);
            h.put("Origin", "https://" + new java.net.URL(playPageUrl).getHost());
            h.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            h.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            String html = OkHttp.string(playPageUrl, h);
            if (TextUtils.isEmpty(html)) return "";
            Matcher m = U18_M3U8_RE.matcher(html);
            if (m.find()) {
                String v = m.group(1).replace("\\/", "/");
                if (v.startsWith("http")) return v;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private List<Class> parseClasses(JSONObject j) {
        List<Class> classes = new ArrayList<>();
        JSONArray arr = j != null ? j.optJSONArray("class") : null;
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c == null) continue;
                String id = String.valueOf(c.opt("type_id"));
                String name = c.optString("type_name", id);
                if (!TextUtils.isEmpty(id) && !"null".equals(id)) {
                    classes.add(new Class(id, name));
                }
            }
        }
        if (classes.isEmpty()) {
            classes.add(new Class("1", "有码"));
            classes.add(new Class("2", "无码"));
            classes.add(new Class("3", "无码流出"));
            classes.add(new Class("4", "素人"));
            classes.add(new Class("5", "国产"));
            classes.add(new Class("6", "动画"));
            classes.add(new Class("7", "英字"));
        }
        return classes;
    }

    private List<Filter> buildFilters() {
        List<Filter> filters = new ArrayList<>();
        List<Filter.Value> years = new ArrayList<>();
        years.add(new Filter.Value("全部", ""));
        for (int y = 2026; y >= 2015; y--) {
            years.add(new Filter.Value(String.valueOf(y), String.valueOf(y)));
        }
        filters.add(new Filter("year", "年份", years));

        List<Filter.Value> sorts = new ArrayList<>();
        sorts.add(new Filter.Value("最新", "desc"));
        sorts.add(new Filter.Value("最早", "asc"));
        filters.add(new Filter("sort_direction", "排序", sorts));
        return filters;
    }

    private String emptyHome() {
        return Result.get().classes(new ArrayList<Class>()).string();
    }

    private String emptyList(int page) {
        return Result.get().vod(new ArrayList<Vod>()).page(page, page, 0, 0).string();
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        if (!unlocked) return emptyHome();

        JSONObject j = apiGet("ac=list&pagesize=" + PAGE_SIZE + "&pg=1");
        List<Class> classes = parseClasses(j);
        List<Vod> list = j != null ? parseList(j.optJSONArray("list")) : new ArrayList<Vod>();

        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();
        if (filter) {
            List<Filter> fs = buildFilters();
            for (Class c : classes) {
                filters.put(c.getTypeId(), fs);
            }
        }
        return Result.string(classes, list, filters);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) throws Exception {
        int page = 1;
        try {
            page = Integer.parseInt(pg);
        } catch (Exception ignored) {
        }
        if (page < 1) page = 1;
        if (!unlocked) return emptyList(page);

        StringBuilder q = new StringBuilder();
        q.append("ac=detail&pagesize=").append(PAGE_SIZE).append("&pg=").append(page);
        if (!TextUtils.isEmpty(tid) && !"0".equals(tid)) {
            q.append("&t=").append(enc(tid));
        }
        if (extend != null) {
            String year = extend.get("year");
            if (!TextUtils.isEmpty(year)) q.append("&year=").append(enc(year));
            String sort = extend.get("sort_direction");
            if (!TextUtils.isEmpty(sort)) q.append("&sort_direction=").append(enc(sort));
        }

        JSONObject j = apiGet(q.toString());
        List<Vod> list = j != null ? parseList(j.optJSONArray("list")) : new ArrayList<Vod>();
        int pagecount = j != null ? Math.max(1, j.optInt("pagecount", page)) : page;
        int total = j != null ? j.optInt("total", 0) : 0;

        return Result.get()
                .vod(list)
                .page(page, pagecount, PAGE_SIZE, total)
                .string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (!unlocked) return Result.get().string();
        if (ids == null || ids.isEmpty()) return Result.error("id 为空");

        String id = ids.get(0);
        JSONObject j = apiGet("ac=detail&ids=" + enc(id));
        if (j == null) return Result.error("详情拉取失败");
        JSONArray arr = j.optJSONArray("list");
        if (arr == null || arr.length() == 0) return Result.error("详情为空");

        JSONObject o = arr.getJSONObject(0);
        Vod vod = new Vod();
        vod.setVodId(String.valueOf(o.opt("id")));
        vod.setVodName(strField(o, "name", "origin_name"));
        vod.setVodPic(strField(o, "poster_url", "thumb_url"));
        vod.setVodYear(yearOf(o));                          // ★ 修改 1: 走 yearOf
        vod.setVodArea(strField(o, "country"));
        vod.setVodActor(strField(o, "actor"));
        vod.setVodDirector(strField(o, "director"));
        vod.setVodContent(strField(o, "description"));
        vod.setTypeName(strField(o, "type_name"));
        vod.setVodRemarks(strField(o, "movie_code", "quality", "time"));
        vod.setVodTag(strField(o, "category", "tag"));

        fillPlay(vod, o);
        return Result.string(vod);
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        if (!unlocked) return emptyList(1);
        int page = 1;
        try {
            page = Integer.parseInt(pg);
        } catch (Exception ignored) {
        }
        String q = "ac=detail&wd=" + enc(key) + "&pagesize=" + PAGE_SIZE + "&pg=" + page;
        JSONObject j = apiGet(q);
        List<Vod> list = j != null ? parseList(j.optJSONArray("list")) : new ArrayList<Vod>();
        int pagecount = j != null ? Math.max(1, j.optInt("pagecount", 1)) : 1;
        int total = j != null ? j.optInt("total", list.size()) : list.size();
        return Result.get().vod(list).page(page, pagecount, PAGE_SIZE, total).string();
    }

    /**
     * ★ 修改 2 (2026-09-29, 凯哥拍板):
     * 原代码有 "if (url.contains(\"avdbapi.com/player\")) parse(1) 分支" — 死代码:
     * extractPlayUrl 已经把 ?s= 解掉, url 此时是 stream 域名 (如 avdb.stream27.com/.../playlist.m3u8),
     * 不可能再含 "avdbapi.com/player". 且 player/?s= 被 Cloudflare 拦 403, parse(1) 嗅探也出不了.
     * 改成恒 parse(0) 直连透传. 回退: 把 parse(0) 改回 parse(1) 即可.
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (!unlocked) return Result.get().url("").string();
        if (TextUtils.isEmpty(id)) return Result.error("播放地址为空");

        String url = id;
        int dollar = url.indexOf('$');
        if (dollar >= 0 && dollar < url.length() - 1) url = url.substring(dollar + 1);
        url = extractPlayUrl(url);

        // ★ 修改 4 (2026-09-29): Origin/Referer 按推出去 URL 的真实 host 生成.
        // 直连型 link_embed -> stream27.com 等; upload18 型 -> helvid.com.
        // 之前写死 host (avdbapi.com) 会让真地址的 Referer 域对不上, 服务端校验可能 403.
        String origin = host;
        String referer = host + "/";
        if (!TextUtils.isEmpty(url) && url.startsWith("http")) {
            try {
                java.net.URL u = new java.net.URL(url);
                String proto = u.getProtocol();
                String hst = u.getHost();
                if (!TextUtils.isEmpty(hst)) {
                    origin = proto + "://" + hst;
                    referer = origin + "/";
                }
            } catch (Exception ignored) {
            }
        }

        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", referer);
        h.put("Origin", origin);
        h.put("Accept", "*/*");

        return Result.get().parse(0).url(url).header(h).string();
    }
}
