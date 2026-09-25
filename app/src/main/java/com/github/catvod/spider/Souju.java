package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 搜剧AI souju2.ai (API 爬虫, HMAC-SHA256 请求签名)
 *
 * A 方案: 干净单线路 —— 每集只取第 1 条 m3u8 直链。
 * 多线路版见 Souju2。
 *
 * 签名 secret / build / protocol 写在前端 bundle, 发版会失效。
 */
public class Souju extends Spider {

    private String host = "https://souju2.ai";

    private static final String CLIENT_NAME = "movie-search-frontend";
    private static final String CLIENT_VERSION = "1.0.0";
    private static final String BUILD_VERSION =
            "aimovie-v2026.09.24.4-4f6353a71c35-4f6353a71c35-4f6353a71c35";
    private static final String PROTOCOL_VERSION = "2026-07-05.library-v2.playback-v1";
    private static final String SIGN_SECRET =
            "f39d73aa7a6426203cdee1ef17b31d3b7ea8c23f4c59c62a3a8aa0f39ee5e79d";

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36";

    private static final Map<String, String> TID_TO_KIND = new HashMap<>();
    static {
        TID_TO_KIND.put("1", "movie");
        TID_TO_KIND.put("2", "series");
        TID_TO_KIND.put("bangumi", "anime");
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        if (extend != null && extend.trim().startsWith("http")) {
            host = extend.trim().replaceAll("/$", "");
        }
    }

    // ==================== 签名 ====================

    private static String randomNonce() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String hmacSha256(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"));
        byte[] raw = mac.doFinal(payload.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte x : raw) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private String signatureOf(String method, String fullPath, String timestamp, String nonce)
            throws Exception {
        String payload = method + "\n" + fullPath + "\n" + timestamp + "\n" + nonce;
        return hmacSha256(SIGN_SECRET, payload);
    }

    private Map<String, String> signedHeaders(String method, String fullPath) throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = randomNonce();
        String sig = signatureOf(method, fullPath, ts, nonce);

        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Accept", "application/json");
        h.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        h.put("Referer", host + "/");
        h.put("Origin", host);
        h.put("x-ai-movie-client-name", CLIENT_NAME);
        h.put("x-ai-movie-client-version", CLIENT_VERSION);
        h.put("x-ai-movie-build-version", BUILD_VERSION);
        h.put("x-ai-movie-protocol-version", PROTOCOL_VERSION);
        h.put("x-ai-movie-timestamp", ts);
        h.put("x-ai-movie-nonce", nonce);
        h.put("x-ai-movie-signature", sig);
        return h;
    }

    private String getSigned(String fullPath) {
        try {
            Map<String, String> h = signedHeaders("GET", fullPath);
            String abs = fullPath.startsWith("http") ? fullPath : host + fullPath;
            String body = OkHttp.string(abs, h);
            return body == null ? "" : body;
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 工具 ====================

    private String firstNonEmpty(JSONObject c, String... keys) {
        for (String k : keys) {
            try {
                if (!c.has(k) || c.isNull(k)) continue;
                Object o = c.opt(k);
                if (o == null) continue;
                String v = String.valueOf(o).trim();
                if (!TextUtils.isEmpty(v) && !"null".equals(v)) return v;
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static String joinTop(JSONArray arr, int n) {
        if (arr == null) return "";
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        for (int i = 0; i < arr.length() && taken < n; i++) {
            String v = arr.optString(i, "");
            if (TextUtils.isEmpty(v)) continue;
            if (sb.length() > 0) sb.append("、");
            sb.append(v);
            taken++;
        }
        return sb.toString();
    }

    private String encPath(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private String kindOf(String tid) {
        String k = TID_TO_KIND.get(tid);
        return (k != null) ? k : "series";
    }

    // ==================== 列表解析 ====================

    private List<Vod> parseCards(JSONObject json) {
        List<Vod> list = new ArrayList<>();
        if (json == null || !json.has("cards")) return list;
        try {
            JSONArray arr = json.getJSONArray("cards");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.getJSONObject(i);
                String name = firstNonEmpty(c, "title", "normalized_title", "name");
                String pic = firstNonEmpty(c,
                        "poster_url", "carousel_url", "backdrop_url", "poster", "image", "pic");
                String id = firstNonEmpty(c, "id", "work_id");
                if (TextUtils.isEmpty(id) && c.has("export_id") && !c.isNull("export_id")) {
                    id = String.valueOf(c.opt("export_id"));
                }
                if (TextUtils.isEmpty(name)) continue;
                if (TextUtils.isEmpty(id)) id = name + "_" + i;

                String remarks = firstNonEmpty(c, "remarks");
                if (TextUtils.isEmpty(remarks)) {
                    String year = firstNonEmpty(c, "year", "release_year");
                    String area = firstNonEmpty(c, "area");
                    if (!TextUtils.isEmpty(year) && !TextUtils.isEmpty(area)) {
                        remarks = year + " · " + area;
                    } else if (!TextUtils.isEmpty(year)) {
                        remarks = year;
                    } else {
                        remarks = area;
                    }
                }

                Vod vod = new Vod();
                vod.setVodId(id);
                vod.setVodName(name);
                if (!TextUtils.isEmpty(pic)) vod.setVodPic(pic);
                if (!TextUtils.isEmpty(remarks)) vod.setVodRemarks(remarks);
                list.add(vod);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private int[] parsePagination(JSONObject json, int page, int limit, int returned) {
        int pagecount = returned > 0 ? page + 1 : page;
        int total = 0;
        try {
            if (json != null && json.has("pagination")) {
                JSONObject p = json.getJSONObject("pagination");
                total = p.optInt("total", 0);
                if (p.has("has_more")) {
                    pagecount = p.optBoolean("has_more", false) ? page + 1 : page;
                } else if (total > 0 && limit > 0) {
                    pagecount = (total + limit - 1) / limit;
                }
            }
        } catch (Exception ignored) {
        }
        return new int[]{pagecount, total};
    }

    private static class BrowseResult {
        List<Vod> vods = new ArrayList<>();
        int pagecount = 1;
        int total = 0;
    }

    private BrowseResult browse(String kind, int page, int limit) {
        BrowseResult out = new BrowseResult();
        int offset = (page - 1) * limit;
        // kind 仅为 movie/series/anime，直接拼接
        String fullPath = "/v1/browse/catalog?sort=trending&window=day"
                + "&page=" + page
                + "&limit=" + limit
                + "&kind=" + kind
                + "&offset=" + offset;
        String jsonStr = getSigned(fullPath);
        if (TextUtils.isEmpty(jsonStr)) return out;
        try {
            JSONObject json = new JSONObject(jsonStr);
            out.vods = parseCards(json);
            int[] pt = parsePagination(json, page, limit, out.vods.size());
            out.pagecount = pt[0];
            out.total = pt[1];
        } catch (Exception ignored) {
        }
        return out;
    }

    // ==================== 播放环 (A: 单线路) ====================

    private static class PlayPlan {
        String playFroms = "";
        String playUrls = "";
    }

    /**
     * 只取第 1 条 m3u8 线路；逐集 resolve（各集路径独立）。
     * 拉全部分集（不再截断为 5 集）。
     */
    private PlayPlan buildPlayPlanSingle(String catalogId) {
        PlayPlan p = new PlayPlan();
        try {
            JSONArray eps = loadAllEpisodes(catalogId);
            if (eps.length() == 0) return p;

            JSONArray firstLines = resolveM3u8Lines(eps.getJSONObject(0).optString("token", ""));
            if (firstLines.length() == 0) return p;

            String mainName = firstNonEmpty(firstLines.getJSONObject(0),
                    "label", "display_label", "provider_name");
            if (TextUtils.isEmpty(mainName)) mainName = "主线路";
            p.playFroms = mainName;

            StringBuilder urls = new StringBuilder();
            for (int i = 0; i < eps.length(); i++) {
                JSONObject ep = eps.getJSONObject(i);
                String title = ep.optString("title", "第" + (i + 1) + "集");
                String u;
                if (i == 0) {
                    u = firstLines.getJSONObject(0).optString("url", "");
                } else {
                    u = resolveFirstM3u8Url(ep.optString("token", ""));
                }
                if (TextUtils.isEmpty(u)) continue;
                if (urls.length() > 0) urls.append("#");
                urls.append(title).append("$").append(u);
            }
            p.playUrls = urls.toString();
        } catch (Exception ignored) {
        }
        return p;
    }

    /** 分页拉取全部 episodes（每页 48） */
    private JSONArray loadAllEpisodes(String catalogId) {
        JSONArray all = new JSONArray();
        int limit = 48;
        int offset = 0;
        try {
            while (true) {
                String path = "/v1/catalog/" + encPath(catalogId)
                        + "/episodes?limit=" + limit + "&offset=" + offset;
                String epJson = getSigned(path);
                if (TextUtils.isEmpty(epJson)) break;
                JSONObject epj = new JSONObject(epJson);
                JSONArray eps = epj.optJSONArray("episodes");
                if (eps == null || eps.length() == 0) break;
                for (int i = 0; i < eps.length(); i++) all.put(eps.get(i));
                if (eps.length() < limit) break;
                offset += limit;
                // 安全上限，防止异常死循环
                if (offset > 500) break;
            }
        } catch (Exception ignored) {
        }
        return all;
    }

    private JSONArray resolveM3u8Lines(String episodeToken) {
        try {
            if (TextUtils.isEmpty(episodeToken)) return new JSONArray();
            String json = getSigned("/v1/playback/resolve/" + encPath(episodeToken) + "?view=compact");
            if (TextUtils.isEmpty(json)) return new JSONArray();
            JSONObject j = new JSONObject(json);
            JSONArray lo = j.optJSONArray("line_options");
            if (lo == null) return new JSONArray();
            JSONArray out = new JSONArray();
            for (int i = 0; i < lo.length(); i++) {
                JSONObject l = lo.getJSONObject(i);
                if ("m3u8".equals(l.optString("url_kind", ""))) out.put(l);
            }
            return out;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private String resolveFirstM3u8Url(String episodeToken) {
        JSONArray lines = resolveM3u8Lines(episodeToken);
        if (lines.length() == 0) return "";
        try {
            return lines.getJSONObject(0).optString("url", "");
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== Spider ====================

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("1", "电影"));
        classes.add(new Class("2", "电视剧"));
        classes.add(new Class("bangumi", "番剧"));

        BrowseResult br = browse("series", 1, 20);
        return Result.get()
                .classes(classes)
                .vod(br.vods)
                .page(1, br.pagecount, br.vods.size(), br.total)
                .string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) throws Exception {
        int page = TextUtils.isEmpty(pg) ? 1 : Integer.parseInt(pg);
        String kind = kindOf(tid);
        BrowseResult br = browse(kind, page, 20);
        return Result.get()
                .vod(br.vods)
                .page(page, br.pagecount, br.vods.size(), br.total)
                .string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return Result.error("id 为空");
        String id = ids.get(0);

        String detailJson = getSigned("/v1/catalog/" + encPath(id) + "/detail");
        if (TextUtils.isEmpty(detailJson)) {
            return Result.error("详情拉取失败 id=" + id);
        }

        Vod vod;
        try {
            JSONObject d = new JSONObject(detailJson);
            vod = new Vod();
            vod.setVodId(id);
            vod.setVodName(firstNonEmpty(d, "title", "normalized_title", "name"));
            vod.setVodYear(firstNonEmpty(d, "year"));
            vod.setVodArea(firstNonEmpty(d, "area"));
            vod.setVodRemarks(firstNonEmpty(d, "remarks", "release_year"));
            vod.setVodContent(firstNonEmpty(d, "description", "synopsis", "content"));
            vod.setVodActor(joinTop(d.optJSONArray("actors"), 8));
            vod.setVodDirector(joinTop(d.optJSONArray("directors"), 5));
            String pic = firstNonEmpty(d, "poster_url", "backdrop_url", "carousel_url");
            if (!TextUtils.isEmpty(pic)) vod.setVodPic(pic);
            vod.setTypeName(joinTop(d.optJSONArray("genres"), 4));
        } catch (Exception e) {
            return Result.error("详情解析失败 id=" + id);
        }

        PlayPlan plan = buildPlayPlanSingle(id);
        if (!TextUtils.isEmpty(plan.playFroms) && !TextUtils.isEmpty(plan.playUrls)) {
            vod.setVodPlayFrom(plan.playFroms);
            vod.setVodPlayUrl(plan.playUrls);
        }
        return Result.get().vod(vod).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return Result.get().vod(new ArrayList<>()).page(1, 1, 0, 0).string();
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return searchContent(key, quick);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (TextUtils.isEmpty(id)) return Result.error("播放 url 为空");

        String url = id;
        int sep = url.indexOf('$');
        if (sep >= 0 && sep < url.length() - 1) url = url.substring(sep + 1);
        if (!url.startsWith("http")) url = host + "/" + url;

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "*/*");
        headers.put("Referer", host + "/");
        headers.put("Origin", host);

        // 已是 resolve 后的 m3u8 直链，parse=0
        return Result.get()
                .parse(0)
                .url(url)
                .header(headers)
                .string();
    }
}
