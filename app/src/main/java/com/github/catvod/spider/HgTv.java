package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 黄果漫剧（hgtv.apk / com.hg.app）CatVod Spider
 * -------------------------------------------------------------------------
 * 逆向来源：APK → dex → AppSeeds（域名种子）→ 远端 H5 分包 app-CSkUOT_7.js（API 客户端）
 * 报告：hgtv/hgtv_api_分析.md
 *
 * 接口要点（2026-10-09 活接口 200 实探确认）：
 *  - 鉴权：Bearer Token 制，无请求签名。POST /app/auth/device（公开端点）带
 *    {"device_id":"web_<24hex>"} 拿 token，之后所有请求 Header 带 Authorization: Bearer <token>。
 *  - 响应信封：{ "data": {...} }，统一取 data 字段。
 *  - 域名轮换：客户端多域容错（5xx 切下一域、401 清 token 重认证重试）。本 Spider 复刻该逻辑，
 *    并支持在 init 时用 extend 传入自定义基址；默认内置多个写死的 API 基址兜底，
 *    启动时还会尝试拉 /app/config 用 XOR 密钥解码出服务端下发的轮换域（首插优先）。
 *  - 域名混淆：config 下发的域是 "h1." 前缀 + base64(XOR(明文, "HgApiCache#e0ae36"))，
 *    见 decodeDomain()。密钥随版本可能更换（边界）。
 *  - 播放：/app/play/{episodeId} 直接返回 play_url（实测为直链 m3u8，如
 *    https://haguapi.huangguo.top/storage/2144/index.m3u8），故 playerContent 直接解析真地址
 *    （parse=0），无需像 Avdb 那样推壳子嗅探。Referer/Origin 取 play_url 自身域名，避免 CDN 403。
 *
 * 依赖：com.github.catvod.* / android.* / org.json.*（与项目内 Avdb.java 同款风格）。
 */
public class HgTv extends Spider {

    /** XOR 混淆密钥（域名轮换）。随 APK 版本可能更换。 */
    private static final String XOR_KEY = "HgApiCache#e0ae36";

    /** 内置 API 基址兜底（统一带 /api 后缀）。以 /app/config 下发为准，这里仅兜底。 */
    private static final String[] DEFAULT_BASES = {
            "https://rules.googlexml.com/api",
            "https://hgxml.00api-agy5u.com/api",
            "https://awsapi.ipa001-7hzktt.com/api"
    };

    /**
     * 图片 CDN 兜底（不带 /api 后缀）。
     * 实探确认：部分分类（AI短剧 / 擦边短剧）的 cover 是【相对路径】（uploads/...jpg），
     * 它只在 API 域下返回真图（image/jpeg）；H5 域（google.huge* / hgtv.kvk9*）全是 SPA HTML 兜底。
     * 故相对封面统一拼到 API 域。轮换域随 config.domains.api 更新时，这里跟着换。
     */
    private static final String IMG_BASE_FALLBACK = "https://hgxml.00api-agy5u.com";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 11; SM-G9910) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36";

    private static final int PAGE_SIZE = 20;

    /** 公开端点：不带 token，且 401 不触发重认证。 */
    private static final String[] PUBLIC_PATHS = {
            "/app/auth/device", "/app/auth/login", "/app/auth/bind", "/app/config"
    };

    private List<String> baseList = new ArrayList<>();
    private String token = "";
    private String deviceId = "";

    // ===================================================================
    // 初始化 & 鉴权
    // ===================================================================

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        deviceId = genDeviceId();

        baseList = new ArrayList<>();
        if (!TextUtils.isEmpty(extend) && extend.trim().startsWith("http")) {
            // 用户自定义基址（可传域名或完整 https://x.x/api）
            baseList.add(normalizeBase(extend.trim()));
        } else {
            for (String b : DEFAULT_BASES) baseList.add(b);
        }

        auth();          // 拿 token
        refreshDomains(); // 可选：用 /app/config 下发的轮换域刷新优先列表
    }

    private String genDeviceId() {
        try {
            SecureRandom r = new SecureRandom();
            StringBuilder sb = new StringBuilder("web_");
            for (int i = 0; i < 24; i++) sb.append(Integer.toHexString(r.nextInt(16)));
            return sb.toString();
        } catch (Exception e) {
            return "web_" + System.currentTimeMillis();
        }
    }

    /** 设备认证拿 token（公开端点，无需 token）。逐基址尝试。 */
    private void auth() {
        for (String base : baseList) {
            try {
                Map<String, String> h = new HashMap<>();
                h.put("User-Agent", UA);
                h.put("Accept", "application/json");
                String body = "{\"device_id\":\"" + deviceId + "\"}";
                Resp r = _post(base + "/app/auth/device", h, body);
                if (r.code == 200 && !TextUtils.isEmpty(r.body)) {
                    JSONObject j = new JSONObject(strip(r.body));
                    String t = j.optString("token", "");
                    if (!TextUtils.isEmpty(t)) {
                        token = t;
                        return;
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** 拉 /app/config，用 XOR 密钥解码 domains.api 轮换域，首插优先。 */
    private void refreshDomains() {
        try {
            JSONObject d = api("/app/config", "GET", null);
            if (d == null) return;
            JSONObject domains = d.optJSONObject("domains");
            if (domains == null) return;
            JSONArray apiArr = domains.optJSONArray("api");
            if (apiArr == null) return;
            List<String> fresh = new ArrayList<>();
            for (int i = 0; i < apiArr.length(); i++) {
                String dec = decodeDomain(apiArr.optString(i));
                if (!TextUtils.isEmpty(dec)) {
                    String b = normalizeBase(dec);
                    if (!TextUtils.isEmpty(b)) fresh.add(b);
                }
            }
            if (fresh.isEmpty()) return;
            List<String> merged = new ArrayList<>(fresh);
            for (String b : baseList) if (!merged.contains(b)) merged.add(b);
            baseList = merged;
        } catch (Exception ignored) {
        }
    }

    // ===================================================================
    // 通用请求封装（复刻 JS 客户端 q()：多域容错 / 401 重认证 / 5xx 切域 / 取 data）
    // ===================================================================

    private Map<String, String> baseHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Accept", "application/json");
        if (!TextUtils.isEmpty(token)) h.put("Authorization", "Bearer " + token);
        return h;
    }

    private static boolean isPublic(String path) {
        for (String p : PUBLIC_PATHS) if (path.startsWith(p)) return true;
        return false;
    }

    /**
     * 统一 API 调用。返回响应信封里的 data（取不到则回退整个对象）。
     * 逻辑：逐基址尝试；遇 401 且非公开端点 → 清 token、重认证、用新 token 重试同基址一次；
     *       遇 5xx 或有异常且非最后一个基址 → 切下一基址；否则返回结果。
     */
    private JSONObject api(String path, String method, String body) {
        for (int i = 0; i < baseList.size(); i++) {
            String base = baseList.get(i);
            boolean last = (i == baseList.size() - 1);
            try {
                Map<String, String> h = baseHeaders();
                String url = base + path;
                Resp r = doReq(url, h, method, body);
                if (r.code == 401 && !isPublic(path) && !TextUtils.isEmpty(token)) {
                    token = "";
                    auth();
                    h = baseHeaders();
                    r = doReq(url, h, method, body);
                }
                if (r.code >= 500 && !last) continue;
                if (TextUtils.isEmpty(r.body) && !last) continue;
                if (TextUtils.isEmpty(r.body)) return null;
                JSONObject j = new JSONObject(strip(r.body));
                JSONObject data = j.optJSONObject("data");
                return data != null ? data : j;
            } catch (Exception ex) {
                if (last) return null;
            }
        }
        return null;
    }

    private Resp doReq(String url, Map<String, String> headers, String method, String body) throws Exception {
        if ("POST".equals(method)) return _post(url, headers, body);
        return _get(url, headers);
    }

    private Resp _get(String url, Map<String, String> headers) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        Resp r = new Resp();
        r.code = c.getResponseCode();
        r.body = readStream(r.code >= 400 ? c.getErrorStream() : c.getInputStream());
        c.disconnect();
        return r;
    }

    private Resp _post(String url, Map<String, String> headers, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        if (!TextUtils.isEmpty(body)) {
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }
        }
        Resp r = new Resp();
        r.code = c.getResponseCode();
        r.body = readStream(r.code >= 400 ? c.getErrorStream() : c.getInputStream());
        c.disconnect();
        return r;
    }

    private static String readStream(InputStream in) {
        if (in == null) return "";
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 去响应体里可能的非 JSON 前缀，安全取首个 { 起。 */
    private static String strip(String s) {
        if (TextUtils.isEmpty(s)) return "";
        int i = s.indexOf('{');
        return i > 0 ? s.substring(i) : s;
    }

    /** 把域名规范成 https://host/api。 */
    private static String normalizeBase(String raw) {
        if (TextUtils.isEmpty(raw)) return "";
        String s = raw.trim();
        if (!s.startsWith("http")) s = "https://" + s;
        if (!s.endsWith("/api")) s = s.replaceAll("/+$", "") + "/api";
        return s;
    }

    /** 解码 config 下发的 "h1." 混淆域名：去前缀 → base64 解 → 循环异或 XOR_KEY → UTF-8。 */
    private String decodeDomain(String enc) {
        try {
            if (TextUtils.isEmpty(enc)) return "";
            if (!enc.startsWith("h1.")) return enc;
            String b64 = enc.substring(3);
            byte[] raw = Base64.decode(b64, Base64.DEFAULT);
            byte[] key = XOR_KEY.getBytes("UTF-8");
            byte[] out = new byte[raw.length];
            for (int i = 0; i < raw.length; i++) out[i] = (byte) (raw[i] ^ key[i % key.length]);
            return new String(out, "UTF-8");
        } catch (Exception e) {
            return enc;
        }
    }

    // ===================================================================
    // 列表 / 详情解析
    // ===================================================================

    /**
     * 修复封面：cover 可能是【相对路径】（uploads/...jpg，AI短剧/擦边短剧常见），需拼 API 域才出真图；
     * 也可能是完整 https://...（成人漫剧常见），原样保留。
     */
    private String fixPic(String pic) {
        if (TextUtils.isEmpty(pic)) return pic;
        if (pic.startsWith("http://") || pic.startsWith("https://")) return pic;
        String base = currentImgBase();
        return base + "/" + pic.replaceFirst("^/+", "");
    }

    /** 从 m3u8 播放链接里抽"目录号"（.../storage/1620/index.m3u8 → 1620）。 */
    private static final Pattern M3U8_DIR_RE = Pattern.compile("/(\\d+)/index\\.m3u8");

    private static int m3u8Dir(String url) {
        if (TextUtils.isEmpty(url)) return -1;
        Matcher m = M3U8_DIR_RE.matcher(url);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

    /** m3u8 存储基址（.../storage）。兜底为实探确认的 CDN。 */
    private static final String M3U8_STORAGE_FALLBACK = "https://haguapi.huangguo.top/storage";

    private String currentM3u8Base() {
        return M3U8_STORAGE_FALLBACK;
    }

    /** 当前生效的 API 域（不带 /api 后缀），用于拼相对封面；取不到用兜底域。 */
    private String currentImgBase() {
        for (String b : baseList) {
            if (b.startsWith("http")) {
                try {
                    String host = new java.net.URL(b).getHost();
                    if (!TextUtils.isEmpty(host)) return "https://" + host;
                } catch (Exception ignored) {
                }
            }
        }
        return IMG_BASE_FALLBACK;
    }

    private List<Vod> parseList(JSONArray arr) {
        List<Vod> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = String.valueOf(o.opt("id"));
            if (TextUtils.isEmpty(id) || "null".equals(id)) continue;
            String name = o.optString("title", "");
            if (TextUtils.isEmpty(name)) continue;

            Vod v = new Vod();
            v.setVodId(id);
            v.setVodName(name);
            String pic = fixPic(o.optString("cover", ""));
            if (!TextUtils.isEmpty(pic)) v.setVodPic(pic);

            int ep = o.optInt("episode_count", 0);
            String badge = o.optString("badge", "");
            StringBuilder rem = new StringBuilder();
            if (!TextUtils.isEmpty(badge)) rem.append(badge);
            if (ep > 0) {
                if (rem.length() > 0) rem.append(" · ");
                rem.append(ep + "集");
            }
            if (rem.length() > 0) v.setVodRemarks(rem.toString());
            list.add(v);
        }
        return list;
    }

    private List<Class> parseCategories(JSONObject filters) {
        List<Class> classes = new ArrayList<>();
        if (filters == null) return classes;
        JSONArray cats = filters.optJSONArray("categories");
        if (cats != null) {
            for (int i = 0; i < cats.length(); i++) {
                JSONObject c = cats.optJSONObject(i);
                if (c == null) continue;
                String id = String.valueOf(c.opt("id"));
                String name = c.optString("name", id);
                if (!TextUtils.isEmpty(id) && !"null".equals(id)) classes.add(new Class(id, name));
            }
        }
        if (classes.isEmpty()) {
            classes.add(new Class("1", "成人漫剧"));
            classes.add(new Class("2", "AI短剧"));
            classes.add(new Class("18", "擦边短剧"));
        }
        return classes;
    }

    /** 从 filters schema 构建 CatVod 筛选（badge / status / lang）。 */
    private List<Filter> buildFilterList(JSONObject filters) {
        List<Filter> fs = new ArrayList<>();

        List<Filter.Value> badges = new ArrayList<>();
        badges.add(new Filter.Value("全部", ""));
        JSONArray b = filters.optJSONArray("badges");
        if (b != null) for (int i = 0; i < b.length(); i++) {
            String v = b.optString(i);
            if (!TextUtils.isEmpty(v)) badges.add(new Filter.Value(v, v));
        }

        List<Filter.Value> status = new ArrayList<>();
        status.add(new Filter.Value("全部", ""));
        JSONArray st = filters.optJSONArray("status");
        if (st != null) for (int i = 0; i < st.length(); i++) {
            JSONObject o = st.optJSONObject(i);
            if (o == null) continue;
            String v = o.optString("value");
            String n = o.optString("label", v);
            if (!TextUtils.isEmpty(v)) status.add(new Filter.Value(n, v));
        }

        List<Filter.Value> langs = new ArrayList<>();
        langs.add(new Filter.Value("全部", ""));
        JSONArray lg = filters.optJSONArray("langs");
        if (lg != null) for (int i = 0; i < lg.length(); i++) {
            String v = lg.optString(i);
            if (!TextUtils.isEmpty(v)) langs.add(new Filter.Value(v, v));
        }

        fs.add(new Filter("badge", "标签", badges));
        fs.add(new Filter("status", "状态", status));
        fs.add(new Filter("lang", "语言", langs));
        return fs;
    }

    // ===================================================================
    // CatVod 接口实现
    // ===================================================================

    @Override
    public String homeContent(boolean filter) throws Exception {
        JSONObject home = api("/app/home?page=1&pageSize=" + PAGE_SIZE, "GET", null);
        JSONArray arr = home != null ? home.optJSONArray("latest") : null;
        if (arr == null || arr.length() == 0) arr = home != null ? home.optJSONArray("recommend") : null;
        List<Vod> list = parseList(arr);

        JSONObject cfg = api("/app/filter", "GET", null);
        JSONObject filters = cfg != null ? cfg.optJSONObject("filters") : null;
        List<Class> classes = parseCategories(filters);

        LinkedHashMap<String, List<Filter>> fmap = new LinkedHashMap<>();
        if (filter && filters != null) {
            List<Filter> fs = buildFilterList(filters);
            for (Class c : classes) fmap.put(c.getTypeId(), fs);
        }
        return Result.string(classes, list, fmap);
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

        StringBuilder q = new StringBuilder();
        q.append("/app/filter?page=").append(page).append("&pageSize=").append(PAGE_SIZE);
        if (!TextUtils.isEmpty(tid) && !"0".equals(tid))
            q.append("&category_id=").append(URLEncoder.encode(tid, "UTF-8"));
        if (extend != null) {
            String badge = extend.get("badge");
            if (!TextUtils.isEmpty(badge)) q.append("&badge=").append(URLEncoder.encode(badge, "UTF-8"));
            String status = extend.get("status");
            if (!TextUtils.isEmpty(status)) q.append("&status=").append(URLEncoder.encode(status, "UTF-8"));
            String lang = extend.get("lang");
            if (!TextUtils.isEmpty(lang)) q.append("&lang=").append(URLEncoder.encode(lang, "UTF-8"));
        }

        JSONObject d = api(q.toString(), "GET", null);
        JSONArray rawList = d != null ? d.optJSONArray("list") : null;
        int total = d != null ? d.optInt("total", 0) : 0;
        boolean hasMore = d != null && d.optBoolean("hasMore", false);

        // ★ 翻页修复（凯哥 2026-10-09）：/app/filter 实测恒返回 total=0、hasMore=true。
        //   旧逻辑 pageCount=(total+sz-1)/sz → total=0 时恒为 1，壳子以为只有一页就不翻。
        //   改为按"是否有下一页"(hasMore) + "本页满页(说明还有下一页)"推算 pageCount，
        //   让壳子翻到第 page+1 页时 hasMore=false 或不满页才停。
        int pageSize = PAGE_SIZE;
        int pageCount;
        if (hasMore || total > 0) {
            if (total > 0) {
                pageCount = Math.max((total + pageSize - 1) / pageSize, page);
            } else {
                // total=0：满页(本页数据量==pageSize)视为还有下一页，pageCount 至少 page+1；
                // 不满页(最后一页)则 pageCount=page。
                int filled = rawList != null ? rawList.length() : 0;
                pageCount = (filled >= pageSize) ? page + 1 : page;
            }
        } else {
            pageCount = Math.max(1, page);
        }
        return Result.get().vod(parseList(rawList))
                .page(page, pageCount, pageSize, total)
                .string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return Result.get().string();
        String id = ids.get(0);

        JSONObject d = api("/app/dramas/" + id, "GET", null);
        JSONObject ep = api("/app/dramas/" + id + "/episodes", "GET", null);

        Vod v = new Vod();
        v.setVodId(id);
        v.setVodName(d != null ? d.optString("title", "") : "");
        String pic = d != null ? fixPic(d.optString("cover", "")) : "";
        if (!TextUtils.isEmpty(pic)) v.setVodPic(pic);
        if (d != null) {
            v.setVodContent(d.optString("description", d.optString("intro", "")));
            StringBuilder rem = new StringBuilder();
            String badge = d.optString("badge", "");
            if (!TextUtils.isEmpty(badge)) rem.append(badge);
            int ec = d.optInt("episode_count", 0);
            if (ec > 0) {
                if (rem.length() > 0) rem.append(" · ");
                rem.append(ec + "集");
            }
            String status = d.optString("status", "");
            if (!TextUtils.isEmpty(status)) {
                if (rem.length() > 0) rem.append(" · ");
                rem.append(status);
            }
            if (rem.length() > 0) v.setVodRemarks(rem.toString());
            v.setVodYear(d.optString("year", ""));
        }

        // 集数：ep.list = [{id, ep, title, need_vip}]
        if (ep != null) {
            JSONArray list = ep.optJSONArray("list");
            if (list != null && list.length() > 0) {
                int n = list.length();
                int[] epNo = new int[n];      // 集号
                String[] epid = new String[n]; // episode_id
                String[] title = new String[n];
                String[] realUrl = new String[n]; // 有链接的集: /app/play 返回的真实 m3u8

                for (int i = 0; i < n; i++) {
                    JSONObject e = list.optJSONObject(i);
                    if (e == null) continue;
                    epNo[i] = e.optInt("ep", i + 1);
                    epid[i] = String.valueOf(e.opt("id"));
                    title[i] = e.optString("title", "第" + epNo[i] + "集");
                    // 只对"有链接"的集取真实 m3u8（VIP 集 /app/play 会返回空 play_url，不取）
                    JSONObject p = api("/app/play/" + epid[i], "GET", null);
                    String pu = p != null ? p.optString("play_url", "") : "";
                    if (TextUtils.isEmpty(pu)) pu = "";
                    realUrl[i] = p.optBoolean("can_play", false) ? pu : "";
                }

                // 收集各集真实 m3u8 目录号，用于推断缺链接的集
                int[] dir = new int[n];
                for (int i = 0; i < n; i++) dir[i] = m3u8Dir(realUrl[i]);

                // 缺链接的集：取"集号最接近的已知目录号"做锚点，按 ±(集号差) 类推
                String m3u8Base = currentM3u8Base();
                List<String> eps = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    String playRef;
                    if (!TextUtils.isEmpty(realUrl[i])) {
                        playRef = realUrl[i]; // 有链接: 直接存 m3u8
                    } else {
                        int bestDir = -1, bestGap = Integer.MAX_VALUE;
                        for (int j = 0; j < n; j++) {
                            if (dir[j] < 0) continue;
                            int gap = Math.abs(epNo[i] - epNo[j]);
                            if (gap < bestGap) {
                                bestGap = gap;
                                bestDir = dir[j] + (epNo[i] - epNo[j]);
                            }
                        }
                        playRef = bestDir > 0 ? (m3u8Base + "/" + bestDir + "/index.m3u8") : epid[i];
                    }
                    // ★ 播放换集逻辑不动：仅当"缺链接"时用类推 m3u8 补链接（存 m3u8）。
                    //   有链接的集仍存真实 m3u8；playerContent 见 $ 后是 http 即直接播。
                    eps.add(title[i] + "$" + playRef);
                }
                if (!eps.isEmpty()) {
                    v.setVodPlayFrom("默认线路");
                    v.setVodPlayUrl(TextUtils.join("#", eps));
                }
            }
        }
        return Result.string(v);
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
        if (page < 1) page = 1;
        String path = "/app/search?q=" + URLEncoder.encode(key, "UTF-8")
                + "&page=" + page + "&pageSize=" + PAGE_SIZE;
        JSONObject d = api(path, "GET", null);
        List<Vod> list = parseList(d != null ? d.optJSONArray("list") : null);
        int total = d != null ? d.optInt("total", list.size()) : list.size();
        int pageCount = total > 0 ? (total + PAGE_SIZE - 1) / PAGE_SIZE : page;
        return Result.get().vod(list).page(page, pageCount, PAGE_SIZE, total).string();
    }

    /**
     * 播放：id 形如 "第01集$2369"（detailContent 写入的 episodeId）。
     * 调用 /app/play/{episodeId} 取 play_url（实测为直链 m3u8），直接返回真地址（parse=0）。
     * Referer/Origin 取 play_url 自身域名，避免 CDN 403。
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (TextUtils.isEmpty(id)) return Result.error("播放地址为空");

        String epId = id;
        int dollar = epId.indexOf('$');
        if (dollar >= 0 && dollar < epId.length() - 1) epId = epId.substring(dollar + 1);

        // ★ 缺链接集的类推 m3u8：detailContent 对"只有集数没链接"的集已按邻近集目录号
        //   补成 .../storage/NNNN/index.m3u8，直接透传播放（不再走 /app/play）。
        //   注意：这只在 epId 已是 http 时生效；"episode_id 数字"（有链接集/正常换集）仍走 /app/play，逻辑不变。
        if (epId.startsWith("http://") || epId.startsWith("https://")) {
            String origin = epId;
            try {
                java.net.URL u = new java.net.URL(epId);
                origin = u.getProtocol() + "://" + u.getHost();
            } catch (Exception ignored) {
            }
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", UA);
            h.put("Referer", origin + "/");
            h.put("Origin", origin);
            h.put("Accept", "*/*");
            return Result.get().parse(0).url(epId).header(h).string();
        }

        JSONObject p = api("/app/play/" + epId, "GET", null);
        if (p == null) return Result.error("播放信息获取失败");

        boolean canPlay = p.optBoolean("can_play", false);
        String playUrl = p.optString("play_url", "");
        if (!canPlay || TextUtils.isEmpty(playUrl)) {
            String reason = p.optString("reason", p.optString("message", ""));
            return Result.error(TextUtils.isEmpty(reason) ? "该集暂不可播放" : reason);
        }

        // Referer / Origin = 播放链接（play_url）自身域名
        String origin = playUrl;
        try {
            java.net.URL u = new java.net.URL(playUrl);
            origin = u.getProtocol() + "://" + u.getHost();
        } catch (Exception ignored) {
        }

        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Origin", origin);
        h.put("Accept", "*/*");

        // parse=0：play_url 已是直链，直接交给播放器
        return Result.get().parse(0).url(playUrl).header(h).string();
    }

    // ===================================================================
    // 内部辅助
    // ===================================================================

    private static class Resp {
        int code;
        String body;
    }
}
