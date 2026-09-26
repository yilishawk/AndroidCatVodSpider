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

/**
 * 搜剧AI souju2.ai (API 爬虫, HMAC-SHA256 请求签名)
 *
 * ★ 命名: 本类 = B 方案 (多线路可选). 干净单线路版见 Souju (A 方案, 同签名协议).
 *   两者 browse/category/search 完全相同, 唯一差别在 detailContent 的播放环:
 *     B (本类): 收全部 m3u8 线路, vod_play_from 多线路 $$$ 分隔, 可切线路;
 *               vod_play_url 每段存 "集名$episodeToken" (token 非真 url), 播放时按集 resolve
 *     A (Souju): 只取第 1 条可播 m3u8 线路, 单线路, 播放环干净 (真 m3u8 url 直接透传)
 *
 * 端点 (已实测 2026-09-25):
 *   - GET  /v1/browse/catalog?sort=trending&window=day&page=2&limit=20&kind=series&offset=20
 *     返回 JSON: {object, request, cards[], source_statuses, pagination, generated_at}
 *   - GET  /v1/catalog/{card.id}/detail                    详情 (object=catalog.detail, 不含 episodes)
 *   - GET  /v1/catalog/{card.id}/episodes?limit=48&offset=0
 *     返回 object=catalog.episodes, episodes[].token 即播放 key (每集独立)
 *   - GET  /v1/playback/resolve/{episode.token}?view=compact
 *     返回 25~31 条线路 (resolve_ticket 票根 + m3u8 直链), m3u8 各集路径独立
 *   - POST /v1/playback/resolve-line?view=full             ticket -> 真直链 (服务端 400/票根时效, 不实现)
 *   分类 kind: series(剧集) / movie(电影) / anime(番剧) 等 (browse 已实测 series, 其余未验证)
 *
 * 视频解析链路 (已实测, B 方案 = "播放哪集才 resolve 哪集"):
 *   1. detailContent:  GET /v1/catalog/{id}/detail + /v1/catalog/{id}/episodes
 *      -> 只 resolve 第 1 集一次 (GET /v1/playback/resolve/{token0}) 定线路名集合, 不逐集预 resolve
 *      -> B 方案: vod_play_from = 线路名 $$$ 拼接 (按第 1 集 line_options 顺序),
 *                 vod_play_url   = 每线路一段 "第i集$episodeToken#..." (token 非真 url, 来自 /episodes)
 *   2. playerContent:  flag = 线路名(定下标 j), id = "第i集$episodeToken"(定集 i)
 *      -> 点哪集才 GET /v1/playback/resolve/{token_i} 拿该集 line_options,
 *         按"线路名 + provider_id 兜底"匹配第 j 条 m3u8 url 返回 (防跨集线路数浮动错位)
 *   不实现: 官方 resolve_ticket 线路 (resolve-line 服务端 400, 票根有时效), 不兜底.
 *
 * 签名协议 (已抠出 + 验证):
 *   前端电影 chunk (movie-card-runtime) 的 Na() 函数:
 *     payload   = METHOD + "\n" + path+query + "\n" + timestamp + "\n" + nonce
 *     signature = HMAC-SHA256(oi, payload)   // 小写 hex
 *     请求头:
 *       x-ai-movie-client-name     = "movie-search-frontend"  (常量)
 *       x-ai-movie-client-version  = "1.0.0"                  (常量)
 *       x-ai-movie-build-version   = "aimovie-v2026.09.24.4-4f6353a71c35×3" (常量, 随前端发版变)
 *       x-ai-movie-protocol-version= "2026-07-05.library-v2.playback-v1"     (常量, 随前端发版变)
 *       x-ai-movie-timestamp       = Date.now() 字符串 (13 位)
 *       x-ai-movie-nonce           = 16 字节随机 hex
 *       x-ai-movie-signature       = 上面的 HMAC-SHA256
 *
 * 边界 (诚实标注, 不兜底):
 *   - 签名 secret 是硬编码在前端 bundle 里的 (oi 常量), 前端发版就变, 纯 Java 写死会在前端更新后失效, 需定期重新扒。
 *   - GET + POST 签名协议均已实测 200/201; browse/catalog/resolve/resolve-line 全链路通。
 *   - B 方案代价: 线路名集合以"第 1 集"resolve 结果为准; 播放某集时按名匹配该集 line_options.
 *     若某集线路名/数量与第 1 集对不上 (线路数随时间浮动), 该集该线路静默缺档 -> 此线路该集不可播, 不崩.
 *     请求量: detail 只 resolve 第 1 集 1 次 (定线路名), 播放每集 +1 次, 不再逐集预 resolve.
 *   - 部分 m3u8 线路实际防盗链/时效短, 播放环不保证每条都能播 (线路多 = 选择多, 但也可能点到坏的)。
 *   - m3u8 playlist 明文可拉, ts 分片多为 AES-128 (EXT-X-KEY, key 在 playlist 同目录可公开取), 播放端需支持 HLS 加密分片, Java 侧不解密。
 *   - 该站带博彩导流广告位 (开元棋牌/PG 电子等, 域名 ddjzis.cn), 风险自负, 不建议生产使用。
 */
public class Souju extends Spider {

    private String host = "https://souju2.ai";

    // 签名常量 (已实测 2026-09-25)
    // 风险: 随前端发版 (build-version / protocol-version / secret) 会变, 失效后需重新扒前端 bundle
    private static final String CLIENT_NAME     = "movie-search-frontend";
    private static final String CLIENT_VERSION  = "1.0.0";
    private static final String BUILD_VERSION   = "aimovie-v2026.09.24.4-4f6353a71c35-4f6353a71c35-4f6353a71c35";
    private static final String PROTOCOL_VERSION= "2026-07-05.library-v2.playback-v1";
    // 真正的 API 签名密钥 (硬编码在前端 bundle, 不是 bootstrap 里那个第三方埋点 secret)
    private static final String SIGN_SECRET     = "f39d73aa7a6426203cdee1ef17b31d3b7ea8c23f4c59c62a3a8aa0f39ee5e79d";

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36";

    // 类型映射: tid -> kind (browse 已实测 series; movie/anime 未验证, 按站点语义推测)
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
            host = extend.trim();
        }
    }

    // ==================== 签名 ====================

    /** 16 字节随机 hex (前端 La(): crypto.getRandomValues(Uint8Array(16)) -> hex, 无连字符) */
    private static String randomNonce() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** HMAC-SHA256(secret, payload) -> 小写 hex (前端 Da(): Web Crypto subtle.importKey raw HMAC SHA-256 + sign) */
    private static String hmacSha256(String secret, String payload) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"));
        byte[] raw = mac.doFinal(payload.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte x : raw) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** 按前端 Na() 拼签名串: METHOD\npath+query\ntimestamp\nnonce, 算 HMAC-SHA256 */
    private String signatureOf(String method, String fullPath, String timestamp, String nonce) throws Exception {
        String payload = method + "\n" + fullPath + "\n" + timestamp + "\n" + nonce;
        return hmacSha256(SIGN_SECRET, payload);
    }

    /** 构造一组带签名的 GET 请求头 (含 Accept/UA + 7 个 x-ai-movie-* 头) */
    private Map<String, String> signedHeaders(String method, String fullPath) throws Exception {
        String ts    = String.valueOf(System.currentTimeMillis());
        String nonce = randomNonce();
        String sig   = signatureOf(method, fullPath, ts, nonce);
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

    // ==================== 抓取 ====================

    /** 带签名的 GET; 返回 JSON 字符串, 失败返回 "" */
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

    /** 从 cards[] 里挑一个片名/海报字段 (字段名以实测 cards[0] 为准, 这里是通用兜底: 取 title/name + poster/image/pic) */
    private List<Vod> parseCards(JSONObject json) {
        List<Vod> list = new ArrayList<>();
        if (json == null) return list;
        try {
            // 实测顶层: {object, request, cards[], source_statuses, pagination, generated_at}
            if (!json.has("cards")) return list;
            JSONArray arr = json.getJSONArray("cards");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.getJSONObject(i);
                String name  = firstNonEmpty(c, "title", "name", "vod_name", "show_name", "label");
                String pic   = firstNonEmpty(c, "poster", "image", "pic", "cover", "media_url");
                String id    = firstNonEmpty(c, "id", "vod_id", "uid", "card_id", "source_id");
                if (TextUtils.isEmpty(name)) continue;
                if (TextUtils.isEmpty(id)) id = name + "_" + i;

                Vod vod = new Vod();
                vod.setVodId(id);
                vod.setVodName(name);
                if (!TextUtils.isEmpty(pic)) vod.setVodPic(pic);
                // 备注/年份/地区等: 以实测 cards 字段为准, 这里先透传 remarks/year/area 若有
                vod.setVodRemarks(firstNonEmpty(c, "year", "release_year"));
                list.add(vod);
            }
        } catch (Exception e) {
            // 字段结构变了 -> 如实返回已解析的, 不崩
        }
        return list;
    }

    private String firstNonEmpty(JSONObject c, String... keys) {
        for (String k : keys) {
            try {
                if (c.has(k) && !c.isNull(k)) {
                    String v = c.optString(k, "");
                    if (!TextUtils.isEmpty(v)) return v;
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    // ==================== 分类映射 ====================

    private String kindOf(String tid) {
        String k = TID_TO_KIND.get(tid);
        return (k != null) ? k : "series";
    }

    // ==================== 播放环 (B 方案: 全线路可选) ====================

    /**
     * B 方案播放方案 (catvod 契约, 参照 FKTV/NG):
     *   线路间 $$$, 线路内集间 #, 每集 "名称$<episodeToken>" (token 非真 url)
     *   vod_play_from = "线路A$$$线路B$$$..."
     *   vod_play_url  = "第1集$token1#第2集$token2...$$$第1集$token1#第2集$token2..." (每线路一段, 段内集名对齐)
     *   playerContent(flag, id): flag = 选中线路名(定下标 j), id = "名称$token"(定集 i) -> 按集 resolve 取 m3u8 url.
     *
     * 只实现 url_kind=m3u8 直链; 官方 resolve_ticket 线路 (resolve-line 服务端 400/票根时效) 不实现, 不兜底.
     * 边界: m3u8 playlist 明文可拉, ts 分片多为 AES-128 (EXT-X-KEY, key 同目录可取), 播放端需支持 HLS 加密分片, Java 侧不解密.
     * 设计: detail 只 resolve 第 1 集 1 次定线路名集合, 播放时按"线路名+provider_id 兜底"逐集匹配该集 m3u8 url,
     *       避免旧版"第 1 集定线路、后续集按 index 取 url"的跨集线路数浮动静默错位.
     */
    private static class PlayPlan {
        String playFroms = "";
        String playUrls  = "";
        String[] lineNames = null; // 第 1 集 line_options 顺序, 与 playFroms 一一对应
    }

    /**
     * B 方案 (全线路可选, 播放哪集才 resolve 哪集):
     *   1) /episodes 拿各集 token (播放 key)
     *   2) 只 resolve 第 1 集 -> line_options (m3u8) 定线路名集合 (provider 顺序对全片一致)
     *   3) vod_play_from = 线路名 $$$ 拼接; vod_play_url 每线路一段 "第i集$token#..." (token 进串, 非真 url)
     *   真 url 延迟到 playerContent 按集 resolve + 按名匹配时才取, 不预取.
     */
    private PlayPlan buildPlayPlanAll(String catalogId) {
        PlayPlan p = new PlayPlan();
        try {
            String epJson = getSigned("/v1/catalog/" + URLEncoder.encode(catalogId, "UTF-8")
                    + "/episodes?limit=48&offset=0");
            if (TextUtils.isEmpty(epJson)) return p;
            JSONObject epj = new JSONObject(epJson);
            JSONArray eps = epj.optJSONArray("episodes");
            if (eps == null || eps.length() == 0) return p;

            // 只 resolve 第 1 集, 定线路名集合 (后续集不预 resolve, 播放时按集解析)
            JSONArray firstLines = resolveM3u8Lines(eps.getJSONObject(0).optString("token", ""));
            if (firstLines.length() == 0) return p;

            int n = Math.min(eps.length(), 5);
            String[] lineNames = new String[firstLines.length()];
            for (int j = 0; j < firstLines.length(); j++) {
                JSONObject l = firstLines.getJSONObject(j);
                String name = firstNonEmpty(l, "label", "display_label", "provider_name");
                if (TextUtils.isEmpty(name)) name = "线路" + (j + 1);
                lineNames[j] = name;
                p.playFroms += (j == 0 ? "" : "$$$") + name;
            }
            p.lineNames = lineNames;

            // playUrls: 每条线路一段 "第i集$token#...". 各段内集数顺序 = 第1集..第n集.
            // 段内 token 取自 /episodes (第 i 集同一个 token 复用到所有线路段).
            StringBuilder urls = new StringBuilder();
            for (int j = 0; j < firstLines.length(); j++) {
                if (j > 0) urls.append("$$$");
                StringBuilder seg = new StringBuilder();
                int epDone = 0;
                for (int i = 0; i < n; i++) {
                    JSONObject ep = eps.getJSONObject(i);
                    String title = ep.optString("title", "第" + (i + 1) + "集");
                    String token = ep.optString("token", "");
                    if (TextUtils.isEmpty(token)) continue;
                    if (epDone > 0) seg.append("#");
                    seg.append(title).append("$").append(token);
                    epDone++;
                }
                if (epDone == 0) continue;
                urls.append(seg);
            }
            p.playUrls = urls.toString();
        } catch (Exception e) {
            // 字段结构变 -> 如实返回已拼的, 不崩
        }
        return p;
    }

    /**
     * resolve 一集, 返回 url_kind=m3u8 的线路 JSONArray (按 line_options 原序, 可能为空).
     * 同一部片 provider 顺序对全片一致 (已实测), 仅路径段随集变化, 故第 1 集定线路名集合.
     */
    private JSONArray resolveM3u8Lines(String episodeToken) {
        try {
            String json = getSigned("/v1/playback/resolve/" + URLEncoder.encode(episodeToken, "UTF-8") + "?view=compact");
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

    /** 按"provider_id(主) + 线路名(兜底)"在该集 m3u8 线路里定位第 idx 条 url, 找不到返回 "". */
    private String pickM3u8Url(JSONArray allLines, int idx, String name) {
        if (allLines == null || allLines.length() == 0) return "";
        if (idx < 0 || idx >= allLines.length()) return "";
        JSONObject want = null;
        try {
            want = allLines.getJSONObject(idx);
        } catch (Exception ignored) {
            return "";
        }
        String wantPid = want.optString("provider_id", "");
        // 1) 主匹配: 按 provider_id (线路身份, 比 label 稳)
        for (int i = 0; i < allLines.length(); i++) {
            try {
                JSONObject l = allLines.getJSONObject(i);
                if ("m3u8".equals(l.optString("url_kind", ""))
                        && wantPid.length() > 0
                        && wantPid.equals(l.optString("provider_id", ""))) {
                    return l.optString("url", "");
                }
            } catch (Exception ignored) {}
        }
        // 2) 兜底: 按线路名 label (线路名跨集若一致命中)
        String wantLabel = firstNonEmpty(want, "label", "display_label", "provider_name");
        if (wantLabel.length() > 0) {
            for (int i = 0; i < allLines.length(); i++) {
                try {
                    JSONObject l = allLines.getJSONObject(i);
                    if (!"m3u8".equals(l.optString("url_kind", ""))) continue;
                    if (firstNonEmpty(l, "label", "display_label", "provider_name").equals(wantLabel)) {
                        return l.optString("url", "");
                    }
                } catch (Exception ignored) {}
            }
        }
        return "";
    }

    // ==================== Spider ====================

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("1", "电影"));
        classes.add(new Class("2", "电视剧"));
        classes.add(new Class("bangumi", "番剧"));

        // 首页 = 默认分类第 1 页 (browse)
        List<Vod> vods = browse("2", "series", 1, 20);

        return Result.get()
                .classes(classes)
                .vod(vods)
                .page(1, 1, vods.size(), 0)
                .string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) throws Exception {
        int page = TextUtils.isEmpty(pg) ? 1 : Integer.parseInt(pg);
        String kind = kindOf(tid);
        List<Vod> vods = browse(tid, kind, page, 20);

        int next = vods.isEmpty() ? page : page + 1;
        int total = 0; // 站点 pagination 字段未暴露总页数, 保持 0 -> Result.page 0 值 -> MAX_VALUE
        return Result.get()
                .vod(vods)
                .page(page, next, vods.size(), total)
                .string();
    }

    /** 核心抓取: 带签名 GET /v1/browse/catalog, 解析 cards[] -> List<Vod> */
    private List<Vod> browse(String tid, String kind, int page, int limit) {
        String path = "/v1/browse/catalog";
        String kindEncoded;
        try {
            kindEncoded = URLEncoder.encode(kind, "UTF-8");
        } catch (Exception e) {
            kindEncoded = kind; // 兜底: 编码异常时透传原 kind (kind 实际是 series/movie/anime 英文, 无需编码)
        }
        String query = "?sort=trending&window=day&page=" + page
                + "&limit=" + limit + "&kind=" + kindEncoded
                + "&offset=" + (page - 1) * limit;
        String fullPath = path + query;
        String json = getSigned(fullPath);
        if (TextUtils.isEmpty(json)) return new ArrayList<>();
        try {
            return parseCards(new JSONObject(json));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return Result.error("id 为空");
        String id = ids.get(0);

        // 1) 详情 /v1/catalog/{id}/detail (实测 200): 取 title/year/area/actors/directors/poster/description/remarks
        String detailJson = getSigned("/v1/catalog/" + URLEncoder.encode(id, "UTF-8") + "/detail");
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

            // 演员/导演是数组, 取前几个 join
            vod.setVodActor(joinTop(d.optJSONArray("actors"), 8));
            vod.setVodDirector(joinTop(d.optJSONArray("directors"), 5));

            // 海报: poster_url 优先, 回退 backdrop/carousel
            String pic = firstNonEmpty(d, "poster_url", "backdrop_url", "carousel_url");
            if (!TextUtils.isEmpty(pic)) vod.setVodPic(pic);

            // 类型: genres 数组 join
            vod.setVodTag(joinTop(d.optJSONArray("genres"), 4));
        } catch (Exception e) {
            return Result.error("详情解析失败 id=" + id);
        }

        // 2) 播放环 (B 方案: 全线路可选) /v1/catalog/{id}/episodes + /v1/playback/resolve/{token}
        //    只实现 m3u8 直链线路; 官方 resolve_ticket 线路(resolve-line 400/票根时效) 不实现.
        PlayPlan plan = buildPlayPlanAll(id);
        if (!TextUtils.isEmpty(plan.playFroms)) {
            vod.setVodPlayFrom(plan.playFroms);
            vod.setVodPlayUrl(plan.playUrls);
        }

        return Result.get().vod(vod).string();
    }

    /** 取 JSONArray 前 n 个非空字符串, 逗号 join (null-safe). */
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

    @Override
    public String searchContent(String key, boolean quick) {
        // 边界: 搜索端点未探明 (被签名挡), 不臆造, 诚实返回空。
        return Result.get().vod(new ArrayList<>()).page(1, 1, 0, 0).string();
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        return searchContent(key, quick);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // B 方案: "播放哪集才 resolve 哪集".
        //   flag = 选中的线路名 (vod_play_from 里的 $$$ 段, 即第 1 集 line_options 的某条线路名)
        //   id   = "第i集$episodeToken" (vod_play_url 段里的某集, 取 $ 后的 token)
        //   流程: token -> GET /v1/playback/resolve/{token} -> line_options(m3u8)
        //         按 flag(线路名) 在该集 line_options 里定位 url, 透传 + Referer/Origin/UA, parse=1, format=m3u8.
        if (TextUtils.isEmpty(id)) return Result.error("播放 url 为空");

        String url = id;
        int sep = url.lastIndexOf('$'); // 名称$token 里的 $ 在前 (如 "第1集$av_xxx")
        if (sep >= 0 && sep < url.length() - 1) url = url.substring(sep + 1);
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) {
            // url 其实是 episodeToken (非直链) -> 按集 resolve 取 m3u8 url
            return resolveAndReturn(url, flag);
        }
        // 兜底: 若 detail 里已写好真 m3u8 url (旧版兼容), 直接透传
        return passthroughM3u8(url);
    }

    /** 按 episodeToken 调 /v1/playback/resolve/{token}, 按线路名 flag 定位 m3u8 url, 透传返回. */
    private String resolveAndReturn(String episodeToken, String flag) {
        try {
            String json = getSigned("/v1/playback/resolve/" + URLEncoder.encode(episodeToken, "UTF-8") + "?view=compact");
            if (TextUtils.isEmpty(json)) return Result.error("resolve 失败 token=" + episodeToken);
            JSONObject j = new JSONObject(json);
            JSONArray lo = j.optJSONArray("line_options");
            if (lo == null || lo.length() == 0) return Result.error("该集无可用线路");

            String picked = "";
            // 1) 按线路名 flag 匹配 (flag = vod_play_from 里的线路名, 与第 1 集 line_options 同名)
            if (flag != null && flag.length() > 0) {
                for (int i = 0; i < lo.length(); i++) {
                    JSONObject l = lo.getJSONObject(i);
                    if (!"m3u8".equals(l.optString("url_kind", ""))) continue;
                    String nm = firstNonEmpty(l, "label", "display_label", "provider_name");
                    if (flag.equals(nm)) { picked = l.optString("url", ""); break; }
                }
            }
            // 2) 兜底: flag 未命中时取第一条 m3u8 (避免静默空)
            if (picked.length() == 0) {
                for (int i = 0; i < lo.length(); i++) {
                    JSONObject l = lo.getJSONObject(i);
                    if ("m3u8".equals(l.optString("url_kind", ""))) { picked = l.optString("url", ""); break; }
                }
            }
            if (picked.length() == 0) return Result.error("线路 " + flag + " 在该集无 m3u8 url");
            return passthroughM3u8(picked);
        } catch (Exception e) {
            return Result.error("resolve 失败: " + e.getMessage());
        }
    }

    /** 透传 m3u8 直链 + Referer/Origin/UA 头, parse=1, format=m3u8. */
    private String passthroughM3u8(String m3u8Url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "*/*");
        headers.put("Referer", host + "/");
        headers.put("Origin", host);
        return Result.get()
                .parse(1)
                .url(m3u8Url)
                .header(headers)
                .m3u8()
                .string();
    }
}
