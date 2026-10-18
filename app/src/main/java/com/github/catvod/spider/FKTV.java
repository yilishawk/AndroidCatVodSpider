package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * 站点本质是 Next.js (React) SPA：
 *   1) 列表/首页：服务端渲染的 RSC 页 (/category/<cat_id>/<name>[/area/<地区>][/position/<位置>])，
 *      影片数据嵌在 self.__next_f.push([1,"..."]) 的文本里，每部影片是一个
 *      ["$","$L2e","<id>",{"item":{...}}] 片段；item 是扁平 JSON，含 id/name/img_x_source/
 *      canonical_path/cat_id/area/release_at 等全部字段。
 *      ⚠️ 图片只能用 img_x_source / img_y_source (.png)，不能用 img_x / img_y (.bnc)；
 *         且 fktv 图床校验 Origin/Referer 会 403 裂图，故统一用公共图片代理 https://wsrv.nl/?url= 转发。
 *   2) /ysapi 加密 API：POST，请求体 = Base64( AES-128-ECB( JSON, key ) )，无 IV；
 *      key = "9ed1a661a6ab787a" (16 字节，AES-128)。响应若以 { 或 [ 开头即为明文 JSON，
 *      否则 Base64 解码后 AES 解密得到明文 JSON。
 *
 * 关键踩坑（旧版"无法使用"的根因）：/ysapi 对 payload.deviceId 做格式校验，必须是「32 位十六进制」，
 *   否则返回「设备信息错误 errorCode 2001」。旧版把 deviceId 写成含非 hex 字符的 32 位串，导致播放
 *   链路 (playerContent 二次请求 movie/detail) 永远被拒。本版 deviceId 用 MD5(域名) 生成，保证合法。
 *
 * 播放模型：detail 阶段一次性拿到 links(集) + play_links(线路)；playerContent 用合法 deviceId 再请求一次
 *   movie/detail {id, link_id, is_simple:"y"} 取该线路该集的已签 m3u8 直链后直出。
 */
public class FKTV extends Spider {

    private static final String HOST = "https://fktv.me";
    private static final String API = HOST + "/ysapi";
    private static final String KEY = "9ed1a661a6ab787a"; // 16 字节 -> AES-128
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36";

    // cat_id -> 分类名(用于拼 URL 的 <name>)
    private static final Map<String, String> TYPE_NAME = new HashMap<>();
    static {
        TYPE_NAME.put("5", "连续剧");
        TYPE_NAME.put("6", "电影");
        TYPE_NAME.put("4", "综艺");
        TYPE_NAME.put("7", "动漫");
        TYPE_NAME.put("9", "短剧");
    }

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
        // 不要手动设置 Accept-Encoding，否则 OkHttp 会关闭透明 gzip 解压拿到压缩字节。
    }

    // ========================= 首页 / 分类导航 + 筛选 =========================
    // 注意：国产剧是连续剧(cat_id=5)下 area=内地剧 的子集，放在第一位；地区筛选只对连续剧生效。
    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("5_area_内地剧", "国产剧")); // 第一位：国产剧(内地剧)
        classes.add(new Class("5", "连续剧"));
        classes.add(new Class("6", "电影"));
        classes.add(new Class("4", "综艺"));
        classes.add(new Class("7", "动漫"));
        classes.add(new Class("9", "短剧"));

        if (!filter) {
            return Result.get().classes(classes).string();
        }

        // 地区筛选（连续剧词表：内地剧/美剧/日剧/韩剧/泰剧，内地剧置顶，显示名"国产剧"）
        List<Filter.Value> areaVals = new ArrayList<>();
        areaVals.add(new Filter.Value("国产剧", "内地剧"));
        areaVals.add(new Filter.Value("美剧", "美剧"));
        areaVals.add(new Filter.Value("日剧", "日剧"));
        areaVals.add(new Filter.Value("韩剧", "韩剧"));
        areaVals.add(new Filter.Value("泰剧", "泰剧"));
        Filter areaFilter = new Filter("area", "地区", areaVals);

        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();
        filters.put("5", Collections.singletonList(areaFilter));          // 连续剧
        filters.put("5_area_内地剧", Collections.singletonList(areaFilter)); // 国产剧子类同样可再筛

        return Result.get().classes(classes).filters(filters).string();
    }

    // ========================= 分类列表 =========================
    // tid: 分类 id；若带 "_area_<地区>" 则按地区筛选。extend 里也可带 area。
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = safeInt(pg, 1);
        String catId = tid;
        String area = null;
        if (tid.contains("_area_")) {
            String[] p = tid.split("_area_", 2);
            catId = p[0];
            area = p[1];
        } else if (extend != null) {
            area = extend.get("area");
        }
        String name = TYPE_NAME.get(catId);
        if (TextUtils.isEmpty(name)) name = tid;

        StringBuilder url = new StringBuilder(HOST + "/category/" + catId + "/" + urlEncode(name));
        if (!TextUtils.isEmpty(area)) url.append("/area/").append(urlEncode(area));
        // /category 路由支持 /page/N 翻页(实测第1/2页内容不同)；第1页用基础 URL，>1 才拼 /page/N
        if (page > 1) url.append("/page/").append(page);

        String html = OkHttp.string(url.toString(), header);
        // 服务端不按 /area/<值> 路径段筛选(fktv 在前端 JS 里过滤)，所以抓全量后在本地按 area 字段二次过滤
        List<Vod> list = parseItems(html, area);
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
                v.setVodPic(img(o.optString("img")));
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
        // ⚠️ 图片取 .png 的 source，不取 .bnc 的 img_x/img_y；外层 img() 已加 wsrv.nl 图片代理
        String pic = d.optString("img_x_source");
        if (TextUtils.isEmpty(pic)) pic = d.optString("img_y_source");
        pic = img(pic);
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

    /**
     * 图片不走 spider 取图代理，也不直连 fktv 图床（图床校验 Origin/Referer 会 403 裂图）。
     * 统一用公共图片代理 wsrv.nl 转发：壳子直连 wsrv.nl（不验证 referer），由它去抓原图返回给壳子。
     */
    private static String img(String url) {
        if (TextUtils.isEmpty(url) || !url.startsWith("http") || url.contains("wsrv.nl")) return url;
        return "https://wsrv.nl/?url=" + url;
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

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    /**
     * 分类 RSC 页抠影片：影片以 ["$","$L2e","<id>",{"item":{...}}] 形式嵌入。
     * item 是扁平 JSON（无嵌套对象），正则 "item":{...} 直接解析，字段全取。
     * ⚠️ 图片取 img_x_source / img_y_source (.png)，不取 img_x / img_y (.bnc)；统一经 img() 加 wsrv.nl 图片代理。
     * area 非空时按 item.area 字段在本地二次过滤（服务端不按 URL 路径段筛选）。
     */
    private List<Vod> parseItems(String html, String area) {
        List<Vod> list = new ArrayList<>();
        if (TextUtils.isEmpty(html)) return list;
        String clean = html.replace("\\\"", "\"").replace("\\/", "/");

        Pattern p = Pattern.compile("\"item\":\\{([^{}]*)\\}");
        Matcher m = p.matcher(clean);
        while (m.find()) {
            try {
                JSONObject it = new JSONObject("{" + m.group(1) + "}");
                String id = it.optString("id");
                String name = it.optString("name");
                if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name)) continue;
                // 本地按地区筛选（服务端路径段不生效）
                if (!TextUtils.isEmpty(area) && !area.equals(it.optString("area"))) continue;
                String pic = it.optString("img_x_source");
                if (TextUtils.isEmpty(pic)) pic = it.optString("img_y_source");
                pic = img(pic);
                String itemArea = it.optString("area");
                String year = it.optString("release_at");

                Vod v = new Vod();
                v.setVodId(id);
                v.setVodName(name);
                v.setVodPic(pic);
                v.setVodRemarks(TextUtils.isEmpty(itemArea) ? year : itemArea);
                list.add(v);
            } catch (Exception ignore) {
                // 个别 item 解析失败跳过，不影响其余
            }
        }
        return list;
    }
}
