package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 凡客影视 (fktv.me) —— CatVod 爬虫
 *
 * 站点本质是 Next.js (React) SPA，数据走两种通道：
 *   1) /ysapi 加密 API：POST，请求体 = Base64( AES-128-ECB( JSON, key ) )，无 IV；
 *      key = "9ed1a661a6ab787a" (16 字节，AES-128)。响应若以 { 或 [ 开头即为明文 JSON，
 *      否则 Base64 解码后 AES 解密得到明文 JSON。
 *   2) Next.js RSC：列表/首页由服务端渲染的频道页 (/channel/<slug>) 承载，没有 /ysapi 列表端点。
 *
 * 最关键的踩坑点（旧版"无法使用"的根因）：
 *   /ysapi 对 payload 里的 deviceId 做格式校验，必须是「32 位十六进制」，否则返回
 *   「设备信息错误 errorCode 2001」。旧版把 deviceId 硬编码成了一个含非 hex 字符的 32 位串，
 *   导致播放链路 (playerContent 二次请求 movie/detail) 永远被拒 → 点开即失败。
 *   本版 deviceId 用 MD5(域名) 生成，保证是合法 32-hex。
 *
 * 播放模型：detail 阶段一次性拿到 links(集) + play_links(线路)；playerContent 用合法 deviceId
 *   再请求一次 movie/detail {id, link_id, is_simple:"y"} 取该线路该集的已签 m3u8 直链后直出。
 */
public class FKTV extends Spider {

    private static final String HOST = "https://fktv.me";
    private static final String API = HOST + "/ysapi";
    private static final String KEY = "9ed1a661a6ab787a"; // 16 字节 -> AES-128
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36";

    private String deviceId;
    private Map<String, String> header;

    @Override
    public void init(Context context, String extend) throws Exception {
        // deviceId 必须 32 位 hex，否则 /ysapi 报「设备信息错误 2001」
        deviceId = md5Hex("fktv.me");
        header = new HashMap<>();
        header.put("User-Agent", UA);
        header.put("Referer", HOST + "/");
        header.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
        header.put("Accept-Language", "zh-CN,zh;q=0.9");
        // 注意：不要手动设置 Accept-Encoding，否则 OkHttp 会关闭透明 gzip 解压拿到压缩字节。
    }

    // ========================= 首页 / 分类导航 =========================
    // tid 直接使用频道 slug，categoryContent 据此抓 /channel/<slug> 的 RSC 页。
    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("movie", "电影"));
        classes.add(new Class("tv", "连续剧"));
        classes.add(new Class("zy", "综艺"));
        classes.add(new Class("comic", "动漫"));
        classes.add(new Class("short_tv", "短剧"));
        classes.add(new Class("js", "解说"));
        return Result.get().classes(classes).string();
    }

    // ========================= 分类列表 =========================
    // fktv 列表走 RSC 频道页，URL 不分页 (?page= 无效，page1==page2)，这里如实返回当前可见影片。
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = safeInt(pg, 1);
        String url = HOST + "/channel/" + tid;
        String html = OkHttp.string(url, header);
        List<Vod> list = parseChannel(html);
        return Result.get().vod(list)
                .page(page, page + 1, list.size(), list.size() * 10)
                .string();
    }

    // ========================= 搜索 =========================
    // /ysapi/movie/smartSearch：仅支持关键词，不支持分类 / 分页。
    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    public String searchContent(String key, boolean quick, String pg) throws Exception {
        if (TextUtils.isEmpty(key)) {
            return Result.get().vod(new ArrayList<Vod>()).string();
        }
        JSONObject data = new JSONObject();
        data.put("keywords", key);
        JSONObject resp = apiPost("movie/smartSearch", data);

        List<Vod> list = new ArrayList<>();
        JSONArray arr = resp.optJSONArray("data");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id");
                String name = o.optString("name");
                if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name)) continue;
                Vod v = new Vod();
                v.setVodId(id);
                v.setVodName(name);
                v.setVodPic(o.optString("img"));
                v.setVodRemarks(o.optString("release_at"));
                list.add(v);
            }
        }
        if (quick && list.size() > 10) {
            list = new ArrayList<>(list.subList(0, 10));
        }
        return Result.get().vod(list).string();
    }

    // ========================= 详情 =========================
    // /ysapi/movie/detail {id} -> 基本信息 + links(集) + play_links(线路)
    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0);
        JSONObject data = new JSONObject();
        data.put("id", id);
        JSONObject resp = apiPost("movie/detail", data);
        JSONObject d = resp.optJSONObject("data");
        if (d == null) {
            return Result.get().vod(new ArrayList<Vod>()).string();
        }

        String name = d.optString("name");
        String pic = d.optString("img_x");
        if (TextUtils.isEmpty(pic)) pic = d.optString("img_y");
        String actor = joinNames(d.opt("actor"));
        String director = d.optString("director");
        String area = d.optString("area");
        String year = yearOf(d.optString("release_at"));
        String content = d.optString("description");
        String typeName = d.optString("categories", d.optString("child_title", ""));

        JSONArray links = d.optJSONArray("links");
        JSONArray playLinks = d.optJSONArray("play_links");

        List<String> fromList = new ArrayList<>();
        List<String> urlList = new ArrayList<>();

        if (playLinks != null && links != null) {
            for (int i = 0; i < playLinks.length(); i++) {
                JSONObject line = playLinks.optJSONObject(i);
                if (line == null) continue;
                String lineName = line.optString("name", "线路" + (i + 1));
                fromList.add(lineName);

                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < links.length(); j++) {
                    JSONObject ep = links.optJSONObject(j);
                    if (ep == null) continue;
                    String epName = ep.optString("name", "第" + (j + 1) + "集");
                    String linkId = ep.optString("id");
                    if (j > 0) sb.append("#");
                    // 播放时 playerContent 用 movieId@linkId 二次请求取精确 m3u8
                    sb.append(epName).append("$").append(id).append("@").append(linkId);
                }
                urlList.add(sb.toString());
            }
        }
        if (fromList.isEmpty()) {
            fromList.add("线路1");
            urlList.add("1$" + id + "@");
        }

        Vod v = new Vod();
        v.setVodId(id);
        v.setVodName(name);
        v.setVodPic(pic);
        v.setTypeName(typeName);
        v.setVodRemarks(links != null ? ("共 " + links.length() + " 集") : "");
        v.setVodActor(actor);
        v.setVodDirector(director);
        v.setVodArea(area);
        v.setVodYear(year);
        v.setVodContent(content);
        v.setVodPlayFrom(TextUtils.join("$$$", fromList));
        v.setVodPlayUrl(TextUtils.join("$$$", urlList));
        return Result.get().vod(v).string();
    }

    // ========================= 播放解析 =========================
    // id 形如 movieId@linkId：用合法 deviceId 再请求 movie/detail 取该线路该集的已签 m3u8 直链后直出。
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (id.startsWith("http")) {
            return okPlay(id);
        }
        if (!id.contains("@")) {
            return failPlay();
        }
        try {
            String[] sp = id.split("@", 2);
            String movieId = sp[0];
            String linkId = sp[1];

            JSONObject data = new JSONObject();
            data.put("id", movieId);
            data.put("link_id", linkId);
            data.put("is_simple", "y");
            JSONObject resp = apiPost("movie/detail", data);
            JSONObject d = resp.optJSONObject("data");
            if (d == null) return failPlay();

            String url = null;
            JSONArray pl = d.optJSONArray("play_links");
            if (pl != null && pl.length() > 0) {
                if (!TextUtils.isEmpty(flag)) {
                    for (int i = 0; i < pl.length(); i++) {
                        JSONObject l = pl.optJSONObject(i);
                        if (l == null) continue;
                        if (flag.equals(l.optString("name")) || flag.equals(String.valueOf(l.opt("id")))) {
                            url = optM3u8(l);
                            break;
                        }
                    }
                }
                if (TextUtils.isEmpty(url)) url = optM3u8(pl.optJSONObject(0));
            }
            // 最终降级
            if (TextUtils.isEmpty(url)) url = d.optString("m3u8_url", null);

            if (!TextUtils.isEmpty(url) && url.startsWith("http")) {
                return okPlay(url);
            }
            return failPlay();
        } catch (Exception e) {
            return failPlay();
        }
    }

    private String optM3u8(JSONObject l) {
        if (l == null) return null;
        String u = l.optString("m3u8_url", null);
        if (TextUtils.isEmpty(u)) u = l.optString("hevc_m3u8_url", null);
        return u;
    }

    // ========================= 私有工具 =========================

    /** 取 m3u8 直链并带 Referer 直出 (parse=0)。 */
    private String okPlay(String url) {
        Map<String, String> h = new HashMap<>();
        h.put("Referer", HOST + "/");
        h.put("User-Agent", UA);
        return Result.get().url(url).header(h).string();
    }

    private String failPlay() {
        return Result.get().url("").string();
    }

    /** 统一 /ysapi 调用：AES-128-ECB 加密请求体，解密响应。 */
    private JSONObject apiPost(String endpoint, JSONObject data) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("deviceId", deviceId);
        payload.put("token", "");
        payload.put("domain", "fktv.me");
        payload.put("referer", "");
        payload.put("user_agent", UA);
        payload.put("shareCode", "");
        payload.put("channel", "");
        payload.put("ip", "");
        payload.put("data", data);

        String body = encrypt(payload.toString());

        Map<String, String> h = new HashMap<>();
        h.put("version", "1.0");
        h.put("deviceType", "pc");
        h.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()));
        h.put("user-agent", UA);
        h.put("referer", HOST + "/");
        h.put("origin", HOST);
        h.put("cookie", "_did=" + deviceId);

        RequestBody rb = RequestBody.create(MediaType.parse("application/octet-stream"), body);
        Request req = new Request.Builder().url(API + "/" + endpoint).post(rb).headers(Headers.of(h)).build();
        Response res = OkHttp.newCall(req);
        if (res == null || !res.isSuccessful() || res.body() == null) {
            return new JSONObject();
        }
        String txt = res.body().string().trim();
        if (TextUtils.isEmpty(txt)) {
            return new JSONObject();
        }
        return new JSONObject(decrypt(txt));
    }

    private String encrypt(String text) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY.getBytes("UTF-8"), "AES"));
        return Base64.encodeToString(c.doFinal(text.getBytes("UTF-8")), Base64.NO_WRAP);
    }

    private String decrypt(String text) throws Exception {
        if (text.startsWith("{") || text.startsWith("[")) return text;
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY.getBytes("UTF-8"), "AES"));
        return new String(c.doFinal(Base64.decode(text, Base64.NO_WRAP)), "UTF-8");
    }

    private static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] b = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return "30303030303030303030303030303030";
        }
    }

    private static String yearOf(String s) {
        if (s != null && s.length() >= 4) return s.substring(0, 4);
        return "";
    }

    private static String joinNames(Object o) {
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            List<String> names = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.optJSONObject(i);
                if (x != null) names.add(x.optString("name", ""));
            }
            return TextUtils.join(",", names);
        }
        return o == null ? "" : o.toString();
    }

    private static int safeInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 频道 RSC 页抠影片：以 "id":"<16hex>" 为中心，双向 600 字符窗口必须同窗含 img_x + canonical_path，过滤 React key 噪声。 */
    private List<Vod> parseChannel(String html) {
        List<Vod> list = new ArrayList<>();
        if (TextUtils.isEmpty(html)) return list;
        String clean = html.replace("\\\"", "\"").replace("\\/", "/");

        Pattern p = Pattern.compile("\"id\":\"([0-9a-f]{16})\"");
        Matcher m = p.matcher(clean);
        while (m.find()) {
            int s = Math.max(0, m.start() - 600);
            int e = Math.min(clean.length(), m.end() + 600);
            String win = clean.substring(s, e);
            if (!win.contains("img_x") || !win.contains("canonical_path")) continue;

            String id = m.group(1);
            String name = findField(win, "name");
            if (TextUtils.isEmpty(name)) name = findField(win, "movie_name");
            if (TextUtils.isEmpty(name)) name = findField(win, "title");
            String pic = findField(win, "img_x");
            if (TextUtils.isEmpty(pic)) pic = findField(win, "img_y");
            String path = findField(win, "canonical_path");
            if (TextUtils.isEmpty(name)) continue;

            Vod v = new Vod();
            v.setVodId(id);
            v.setVodName(name);
            v.setVodPic(pic);
            v.setVodRemarks(path);
            list.add(v);
        }
        return list;
    }

    private static String findField(String win, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(win);
        return m.find() ? m.group(1) : "";
    }
}
