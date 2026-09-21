package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 剧圈圈在线 (www.jqqzx.one)
 * 必须使用移动端 UA，桌面 UA 会被跳转到 404。
 */
public class Jqqzx extends Spider {

    private String host = "https://www.jqqzx.one";
    private Map<String, String> headers;

    private static final String API = "/jx/api.php";
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 10; MiTV4-ANSM0) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Version/4.0 Chrome/89.0.4388.90 Mobile Safari/537.36";

    private static final String[][] TYPES = {
            {"juji", "剧集"}, {"dianying", "电影"}, {"dongman", "动漫"},
            {"zongyi", "综艺"}, {"duanju", "短剧"}
    };

    public Jqqzx() {
        headers = new HashMap<>();
        headers.put("User-Agent", MOBILE_UA);
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.put("Referer", host);
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        if (!TextUtils.isEmpty(extend)) {
            try {
                JSONObject cfg = new JSONObject(extend);
                if (cfg.has("host")) {
                    host = cfg.optString("host");
                    headers.put("Referer", host);
                    SpiderDebug.log("[剧圈圈] 使用配置 host: " + host);
                }
            } catch (Exception e) {
                SpiderDebug.log("[剧圈圈] 解析扩展配置失败: " + e.getMessage());
            }
        }
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        for (String[] t : TYPES) classes.add(new Class(t[0], t[1]));

        List<Vod> list = parsePosterItems(get(host + "/"));

        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();
        if (filter) {
            for (String[] t : TYPES) {
                try {
                    String libHtml = get(host + "/vodshow/id/" + t[0] + ".html");
                    if (!TextUtils.isEmpty(libHtml)) filters.put(t[0], buildFilters(libHtml, t[0]));
                } catch (Exception ignored) {
                }
            }
        }
        return Result.string(classes, list, filters);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String type = TextUtils.isEmpty(tid) ? "juji" : tid;
        Map<String, String> f = (extend != null) ? extend : new HashMap<>();
        // 类型筛选实际是切换 /vodshow/id/{subtype}.html
        if (!isEmpty(f.get("type"))) type = f.get("type");

        String url = buildVodshowUrl(type, f, pg);
        String html = get(url);
        List<Vod> list = TextUtils.isEmpty(html) ? new ArrayList<Vod>() : parsePosterItems(html);

        int page = parsePage(pg);
        int pagecount = page + 2;

        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();
        if (filter && !TextUtils.isEmpty(html)) filters.put(tid, buildFilters(html, tid));

        return Result.get()
                .vod(list)
                .filters(filters)
                .page(page, pagecount, 0, 0)
                .string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0);
        Document doc = Jsoup.parse(get(host + "/vod/" + id + ".html"));

        Vod vod = new Vod();
        vod.setVodId(id);
        Element h1 = doc.selectFirst("h1");
        vod.setVodName(h1 != null ? h1.text().trim() : doc.title().split("-")[0].trim());
        Element pic = doc.selectFirst(".module-item-pic img");
        if (pic != null) {
            String p = pic.attr("data-original");
            if (TextUtils.isEmpty(p)) p = pic.attr("src");
            vod.setVodPic(abs(p));
        }
        Element note = doc.selectFirst(".module-item-note");
        if (note != null) vod.setVodRemarks(note.text().trim());

        List<String> sourceNames = new ArrayList<>();
        for (Element tab : doc.select(".module-tab-item[data-dropdown-value]")) {
            String n = tab.attr("data-dropdown-value");
            if (!TextUtils.isEmpty(n) && !sourceNames.contains(n)) sourceNames.add(n);
        }

        Map<String, List<String>> sourceEps = new LinkedHashMap<>();
        for (Element a : doc.select("a.module-play-list-link[href]")) {
            String href = a.attr("href");
            if (!href.contains("/play/" + id + "-")) continue;
            int idx = href.indexOf("/play/" + id + "-");
            String tail = href.substring(idx + ("/play/" + id + "-").length());
            int dash = tail.indexOf('-');
            String sid = (dash < 0) ? tail : tail.substring(0, dash);
            String epName = a.text().trim();
            sourceEps.computeIfAbsent(sid, k -> new ArrayList<>()).add(epName + "$" + abs(href));
        }

        List<String> froms = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : sourceEps.entrySet()) {
            froms.add(sourceNameForSid(sourceNames, e.getKey()));
            urls.add(TextUtils.join("#", e.getValue()));
        }
        if (!froms.isEmpty()) {
            vod.setVodPlayFrom(TextUtils.join("$$$", froms));
            vod.setVodPlayUrl(TextUtils.join("$$$", urls));
        }
        return Result.string(vod);
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        String url = host + "/index.php/ajax/suggest.html?mid=1&wd=" + encode(key);
        String json = get(url);
        List<Vod> list = new ArrayList<>();
        if (TextUtils.isEmpty(json)) {
            return Result.get().vod(list).page(1, 1, 0, 0).string();
        }
        try {
            int br = json.indexOf('{');
            if (br > 0) json = json.substring(br);
            JSONObject root = new JSONObject(json);
            if (root.optInt("code", 0) != 1) {
                return Result.get().vod(list).page(1, 1, 0, 0).string();
            }
            JSONArray arr = root.optJSONArray("list");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.getJSONObject(i);
                    String id = item.has("id") ? String.valueOf(item.get("id")) : "";
                    String name = item.optString("name", "");
                    String pic = abs(item.optString("pic", ""));
                    if (!TextUtils.isEmpty(id) && !TextUtils.isEmpty(name)) {
                        list.add(new Vod(id, name, pic, ""));
                    }
                }
            }
            int page = root.optInt("page", 1);
            int pagecount = root.optInt("pagecount", 1);
            return Result.get().vod(list).page(page, pagecount, 0, root.optInt("total", list.size())).string();
        } catch (Exception e) {
            SpiderDebug.log("[剧圈圈] 搜索解析失败: " + e.getMessage());
            return Result.get().vod(list).page(1, 1, 0, 0).string();
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String playPath = extractVid(id);
        String playPage = host + "/play/" + playPath + ".html";

        JSONObject fallback = new JSONObject();
        fallback.put("parse", 1);
        fallback.put("url", host + "/jx/player.php?vid=" + encode(playPath));
        fallback.put("header", headerObj(playPage));

        String realUrl = "";
        try {
            String html = get(playPage);
            String realVid = extractPlayerUrl(html);
            if (TextUtils.isEmpty(realVid)) {
                SpiderDebug.log("[剧圈圈] 未找到 player_aaaa.url");
                return fallback.toString();
            }
            JSONObject data = postApi(realVid, playPage);
            if (data.has("url")) realUrl = sign(data.getString("url"));
        } catch (Exception e) {
            SpiderDebug.log("[剧圈圈] playerContent 失败: " + e.getMessage());
        }
        if (TextUtils.isEmpty(realUrl)) return fallback.toString();

        JSONObject out = new JSONObject();
        out.put("parse", 0);
        out.put("url", realUrl);
        out.put("header", headerObj(playPage));
        return out.toString();
    }

    private JSONObject headerObj(String referer) {
        JSONObject h = new JSONObject();
        try {
            h.put("User-Agent", MOBILE_UA);
            h.put("Referer", referer);
            h.put("Origin", host);
            h.put("Accept", "*/*");
        } catch (Exception ignored) {
        }
        return h;
    }

    private String extractPlayerUrl(String html) {
        Matcher m = Pattern.compile("player_aaaa\\s*=\\s*(\\{.*?\\})\\s*;?\\s*</script>", Pattern.DOTALL).matcher(html);
        if (!m.find()) {
            m = Pattern.compile("player_aaaa\\s*=\\s*(\\{.*?\\})\\s*;", Pattern.DOTALL).matcher(html);
            if (!m.find()) return "";
        }
        try {
            JSONObject data = new JSONObject(m.group(1));
            String url = data.optString("url", "");
            int encrypt = data.optInt("encrypt", 0);
            if (encrypt == 1) {
                url = java.net.URLDecoder.decode(url, "UTF-8");
            } else if (encrypt == 2) {
                url = new String(Base64.decode(url, Base64.DEFAULT), "UTF-8");
                url = java.net.URLDecoder.decode(url, "UTF-8");
            }
            return url;
        } catch (Exception e) {
            SpiderDebug.log("[剧圈圈] 解析 player_aaaa 失败: " + e.getMessage());
            return "";
        }
    }

    public static String sign(String encUrl) throws Exception {
        if (TextUtils.isEmpty(encUrl)) return "";
        byte[] b = Base64.decode(encUrl, Base64.NO_WRAP);
        String key = md5Hex("test");
        StringBuilder s2b = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            s2b.append((char) ((b[i] & 0xFF) ^ key.charAt(i % 32)));
        }
        byte[] l2 = Base64.decode(s2b.toString(), Base64.NO_WRAP);
        String[] parts = new String(l2, "ISO-8859-1").split("/", -1);
        if (parts.length < 2) throw new IllegalStateException("sign: 段数不足, 实际 " + parts.length);
        List<String> keyList = jsonStrArray(new String(Base64.decode(parts[0], Base64.NO_WRAP), "ISO-8859-1"));
        List<String> valList = jsonStrArray(new String(Base64.decode(parts[1], Base64.NO_WRAP), "ISO-8859-1"));
        StringBuilder targetB64 = new StringBuilder();
        for (int i = 2; i < parts.length; i++) {
            if (i > 2) targetB64.append('/');
            targetB64.append(parts[i]);
        }
        if (targetB64.length() == 0) return "";
        String target = new String(Base64.decode(targetB64.toString(), Base64.NO_WRAP), "ISO-8859-1");
        return deString(keyList, valList, target);
    }

    private static String deString(List<String> keyMap, List<String> valMap, String target) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            boolean letter = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
            if (letter) {
                int vk = valMap.indexOf(String.valueOf(c));
                out.append(vk >= 0 ? keyMap.get(vk) : String.valueOf(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static List<String> jsonStrArray(String json) throws Exception {
        List<String> list = new ArrayList<>();
        JSONArray arr = new JSONArray(json);
        for (int i = 0; i < arr.length(); i++) list.add(arr.getString(i));
        return list;
    }

    public static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("ISO-8859-1"));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String extractVid(String url) {
        int idx = url.indexOf("/play/");
        if (idx < 0) return url;
        String seg = url.substring(idx + 6);
        int end = seg.indexOf(".html");
        if (end > 0) seg = seg.substring(0, end);
        return seg;
    }

    private JSONObject postApi(String vid, String referer) throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", MOBILE_UA);
        h.put("Referer", referer);
        h.put("Origin", host);
        h.put("Content-Type", "application/x-www-form-urlencoded");

        RequestBody body = RequestBody.create(
                MediaType.parse("application/x-www-form-urlencoded"),
                "vid=" + encode(vid));
        Request request = new Request.Builder()
                .url(host + API)
                .post(body)
                .headers(Headers.of(h))
                .build();
        Response res = OkHttp.newCall(request);
        if (res == null || !res.isSuccessful() || res.body() == null) {
            throw new IllegalStateException("api HTTP 失败");
        }
        String resp = res.body().string();
        int br = resp.indexOf('{');
        if (br < 0) throw new IllegalStateException("api 无 JSON");
        JSONObject j = new JSONObject(resp.substring(br));
        if (j.optInt("code", -1) != 200) {
            throw new IllegalStateException("api code=" + j.optInt("code", -1) + " " + j.optString("msg"));
        }
        return j.getJSONObject("data");
    }

    private String get(String url) {
        String html = OkHttp.string(url, headers);
        return (html == null) ? "" : html;
    }

    private String buildVodshowUrl(String type, Map<String, String> f, String pg) {
        StringBuilder sb = new StringBuilder("/vodshow");
        if (!isEmpty(f.get("area"))) sb.append("/area/").append(encode(f.get("area")));
        if (!isEmpty(f.get("class"))) sb.append("/class/").append(encode(f.get("class")));
        if (!isEmpty(f.get("by"))) sb.append("/by/").append(f.get("by"));
        sb.append("/id/").append(type);
        if (!isEmpty(f.get("year"))) sb.append("/year/").append(f.get("year"));
        if (parsePage(pg) > 1) sb.append("/page/").append(pg);
        sb.append(".html");
        return host + sb;
    }

    /**
     * 类型: /vodshow/id/guochanju.html （切换 id）
     * 剧情/标签: /vodshow/class/xxx/id/{type}.html
     * 地区: /vodshow/area/xxx/id/{type}.html
     */
    private List<Filter> buildFilters(String html, String currentType) {
        List<Filter> filters = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        Filter typeFilter = buildTypeFilter(doc, currentType);
        if (typeFilter != null) filters.add(typeFilter);

        filters.add(buildFilterFromLinks(html, "class", "剧情", "class"));
        filters.add(buildFilterFromLinks(html, "area", "地区", "area"));
        filters.add(buildByFilter());
        filters.add(buildYearFilter(html));
        return filters;
    }

    /** 解析「类型」：/vodshow/id/guochanju.html、/vodshow/id/gangtaiju.html 等 */
    private Filter buildTypeFilter(Document doc, String currentType) {
        List<Filter.Value> items = new ArrayList<>();
        items.add(new Filter.Value("全部", ""));
        Set<String> seen = new LinkedHashSet<>();

        // 优先从「类型」模块取
        for (Element box : doc.select(".module-class-item")) {
            Element title = box.selectFirst(".module-item-title");
            if (title == null || !title.text().contains("类型")) continue;
            for (Element a : box.select(".module-item-box a[href]")) {
                String href = a.attr("href");
                Matcher m = Pattern.compile("/vodshow/id/([^/]+)\\.html").matcher(href);
                if (!m.find()) continue;
                String val = m.group(1);
                String name = a.attr("title");
                if (TextUtils.isEmpty(name)) name = a.text().trim();
                if (TextUtils.isEmpty(name) || "全部".equals(name)) continue;
                // 跳过纯数字 id（如 id/2.html）
                if (val.matches("\\d+")) continue;
                if (val.equals(currentType)) continue;
                if (!seen.add(val)) continue;
                items.add(new Filter.Value(name, val));
            }
        }

        // 兜底：全页扫描子分类 id
        if (items.size() <= 1) {
            for (Element a : doc.select("a[href*=/vodshow/id/]")) {
                Matcher m = Pattern.compile("/vodshow/id/([^/]+)\\.html").matcher(a.attr("href"));
                if (!m.find()) continue;
                String val = m.group(1);
                if (val.matches("\\d+")) continue;
                if (val.equals(currentType)) continue;
                if (isMainType(val)) continue;
                if (!seen.add(val)) continue;
                String name = a.attr("title");
                if (TextUtils.isEmpty(name)) name = a.text().trim();
                if (TextUtils.isEmpty(name) || "全部".equals(name)) continue;
                items.add(new Filter.Value(name, val));
            }
        }

        if (items.size() <= 1) return null;
        return new Filter("type", "类型", items);
    }

    private boolean isMainType(String val) {
        for (String[] t : TYPES) {
            if (t[0].equals(val)) return true;
        }
        return false;
    }

    private Filter buildFilterFromLinks(String html, String key, String name, String dim) {
        Pattern p = Pattern.compile("href=\"(/vodshow/" + dim + "/([^/\"]+)/id/[^/]+\\.html)\"");
        List<String> vals = new ArrayList<>();
        Map<String, String> labels = new HashMap<>();
        Matcher m = p.matcher(html);
        while (m.find()) {
            String v = m.group(2);
            try {
                v = java.net.URLDecoder.decode(v, "UTF-8");
            } catch (Exception ignored) {
            }
            if (!isEmpty(v) && !vals.contains(v)) {
                vals.add(v);
                labels.put(v, v);
            }
        }
        // 尝试用 title 作为显示名
        Document doc = Jsoup.parse(html);
        for (Element a : doc.select("a[href*=/vodshow/" + dim + "/]")) {
            Matcher hm = Pattern.compile("/vodshow/" + dim + "/([^/]+)/id/").matcher(a.attr("href"));
            if (!hm.find()) continue;
            String v = hm.group(1);
            try {
                v = java.net.URLDecoder.decode(v, "UTF-8");
            } catch (Exception ignored) {
            }
            String title = a.attr("title");
            if (TextUtils.isEmpty(title)) title = a.text().trim();
            if (!TextUtils.isEmpty(title) && !"全部".equals(title)) labels.put(v, title);
        }

        List<Filter.Value> items = new ArrayList<>();
        items.add(new Filter.Value("全部", ""));
        for (String v : vals) {
            String label = labels.containsKey(v) ? labels.get(v) : v;
            items.add(new Filter.Value(label, v));
        }
        return new Filter(key, name, items);
    }

    private Filter buildByFilter() {
        List<Filter.Value> items = new ArrayList<>();
        items.add(new Filter.Value("全部", ""));
        items.add(new Filter.Value("按时间", "time"));
        items.add(new Filter.Value("按评分", "score"));
        items.add(new Filter.Value("按人气", "hits"));
        return new Filter("by", "排序", items);
    }

    private Filter buildYearFilter(String html) {
        Pattern p = Pattern.compile("href=\"/vodshow/id/[^/]+/year/(\\d+)\\.html\"");
        List<String> vals = new ArrayList<>();
        Matcher m = p.matcher(html);
        while (m.find()) {
            if (!vals.contains(m.group(1))) vals.add(m.group(1));
        }
        List<Filter.Value> items = new ArrayList<>();
        items.add(new Filter.Value("全部", ""));
        for (String v : vals) items.add(new Filter.Value(v, v));
        return new Filter("year", "年份", items);
    }

    private List<Vod> parsePosterItems(String html) {
        List<Vod> list = new ArrayList<>();
        if (TextUtils.isEmpty(html)) return list;
        Set<String> seen = new LinkedHashSet<>();
        Document doc = Jsoup.parse(html);

        Elements cards = doc.select(".module-item, .module-card-item, .module-poster-item");
        if (cards != null && !cards.isEmpty()) {
            for (Element card : cards) {
                Element a = card.selectFirst("a[href^=/vod/]");
                if (a == null) continue;
                Matcher idm = Pattern.compile("/vod/(\\d+)\\.html").matcher(a.attr("href"));
                if (!idm.find()) continue;
                String id = idm.group(1);
                if (!seen.add(id)) continue;

                String name = a.attr("title");
                if (TextUtils.isEmpty(name)) {
                    Element t = card.selectFirst("h4, .module-item-title, .module-card-item-title, .title, strong");
                    name = t != null ? t.text().trim() : "";
                }

                String pic = "";
                Element im = card.selectFirst("img");
                if (im != null) {
                    pic = im.attr("data-original");
                    if (TextUtils.isEmpty(pic)) pic = im.attr("src");
                    pic = abs(pic);
                }

                String remark = "";
                Element note = card.selectFirst(".module-item-note");
                if (note != null) remark = note.text().trim();

                if (!TextUtils.isEmpty(name) || !TextUtils.isEmpty(pic)) {
                    list.add(new Vod(id, TextUtils.isEmpty(name) ? "" : name, pic, remark));
                }
            }
            if (!list.isEmpty()) return list;
        }

        for (Element a : doc.select("a[href^=/vod/]")) {
            Matcher idm = Pattern.compile("/vod/(\\d+)\\.html").matcher(a.attr("href"));
            if (!idm.find()) continue;
            String id = idm.group(1);
            if (!seen.add(id)) continue;

            String name = a.attr("title");
            if (TextUtils.isEmpty(name)) {
                Element t = a.selectFirst("h4, .module-item-title, .title, span");
                name = t != null ? t.text().trim() : "";
            }
            String pic = "";
            Element im = a.selectFirst("img");
            if (im != null) {
                pic = im.attr("data-original");
                if (TextUtils.isEmpty(pic)) pic = im.attr("src");
                pic = abs(pic);
            }
            String remark = "";
            Element parent = a.parent();
            if (parent != null) {
                Element note = parent.selectFirst(".module-item-note");
                if (note == null && parent.parent() != null) {
                    note = parent.parent().selectFirst(".module-item-note");
                }
                if (note != null) remark = note.text().trim();
            }
            if (!TextUtils.isEmpty(name) || !TextUtils.isEmpty(pic)) {
                list.add(new Vod(id, TextUtils.isEmpty(name) ? "" : name, pic, remark));
            }
        }
        return list;
    }

    private String sourceNameForSid(List<String> sourceNames, String sid) {
        int idx;
        try {
            idx = Integer.parseInt(sid) - 1;
        } catch (Exception e) {
            return "源" + sid;
        }
        if (idx >= 0 && idx < sourceNames.size()) return sourceNames.get(idx);
        return "源" + sid;
    }

    private String abs(String url) {
        if (TextUtils.isEmpty(url)) return "";
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("/")) return host + url;
        return url;
    }

    private boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private int parsePage(String pg) {
        try {
            return Integer.parseInt(pg);
        } catch (Exception e) {
            return 1;
        }
    }

    private String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
