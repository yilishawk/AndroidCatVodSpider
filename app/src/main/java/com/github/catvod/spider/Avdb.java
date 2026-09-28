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
            String e = extend.trim().replaceAll("/$", "");
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
                String year = strField(o, "year");

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
        return link;
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
        vod.setVodYear(strField(o, "year"));
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

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (!unlocked) return Result.get().url("").string();
        if (TextUtils.isEmpty(id)) return Result.error("播放地址为空");

        String url = id;
        int dollar = url.indexOf('$');
        if (dollar >= 0 && dollar < url.length() - 1) url = url.substring(dollar + 1);
        url = extractPlayUrl(url);

        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", host + "/");
        h.put("Origin", host);
        h.put("Accept", "*/*");

        if (url.contains("avdbapi.com/player")) {
            return Result.get().parse(1).url(url).header(h).string();
        }
        return Result.get().parse(0).url(url).header(h).string();
    }
}
