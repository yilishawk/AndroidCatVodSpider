package com.github.catvod.spider;

import android.content.Context;
import android.util.Base64;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 123影院 (com.app.app471d8505) —— 苹果CMS 风格爬虫
 *
 * 接口（均 POST form）：
 *   /api.php/getappapi.index/init       首页初始化：banner_list / recommend_list / type_list / hot_search_list
 *   /api.php/getappapi.index/rankList   分类片单：type_id + pg          → rank_list
 *   /api.php/getappapi.index/searchList 搜索：keywords + pg             → search_list
 *   /api.php/getappapi.index/vodDetail  详情：vod_id                    → vod + vod_play_list + same_list
 *   /api.php/getappapi.index/vodParse   播放解析（url 为哈希的源走这里）  → {"json":"{\"code\":200,\"url\":\"...\"}"}
 *
 * 请求签名头（hook 实锤）：
 *   app-api-verify-time = 秒级时间戳
 *   app-api-verify-sign = base64(AES-128-CBC(时间戳ASCII))
 *   密码：key = IV = VwsHxkCViDXXEExWa（libnmmp.so 0x2B07F 静态定位 + hook 双向实锤）
 *
 * 站点 ext 可覆盖：{"host":"https://ttss.langfeng888.com/api.php/getappapi.index","deviceId":"32位hex"}
 */
public class AppleBox extends Spider {

    private static final String KEY = "VwsHxkCViDXXEExWa"; // key = IV
    private static final String DEVICE_ID = "29227c815e45939da8cb0bbd13f3a3cde";
    private static final String DEFAULT_BASE = "https://ttss.langfeng888.com/api.php/getappapi.index";

    private String base = DEFAULT_BASE;
    private String deviceId = DEVICE_ID;

    @Override
    public void init(Context context, String extend) throws Exception {
        if (extend == null || extend.trim().isEmpty()) return;
        String ext = extend.trim();
        try {
            JSONObject j = new JSONObject(ext);
            if (j.has("host")) base = j.getString("host");
            if (j.has("deviceId")) deviceId = j.getString("deviceId");
        } catch (Exception ignored) {
            base = ext;
        }
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
    }

    // ---------- 基础设施 ----------

    private Map<String, String> header() throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("app-user-device-id", deviceId);
        h.put("app-version-code", "101");
        h.put("app-ui-mode", "light");
        h.put("User-Agent", "okhttp/3.14.9");
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        h.put("app-api-verify-time", ts);
        h.put("app-api-verify-sign", sign(ts));
        return h;
    }

    /** 签名 = base64(AES-128-CBC(时间戳 ASCII 串)) */
    private String sign(String ts) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY.getBytes("UTF-8"), "AES"), new IvParameterSpec(KEY.getBytes("UTF-8")));
        return Base64.encodeToString(c.doFinal(ts.getBytes("UTF-8")), Base64.NO_WRAP);
    }

    private String decrypt(String data) throws Exception {
        if (data == null || data.trim().isEmpty()) return "";
        byte[] ct = Base64.decode(data.replaceAll("\\s+", ""), Base64.NO_WRAP);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY.getBytes("UTF-8"), "AES"), new IvParameterSpec(KEY.getBytes("UTF-8")));
        return new String(c.doFinal(ct), "UTF-8");
    }

    /** 调接口并返回解密后的内层 JSON；空数据返回空 JSONObject */
    private JSONObject api(String action, Map<String, String> params) throws Exception {
        String body = OkHttp.post(base + "/" + action, params, header()).getBody();
        if (body == null || body.trim().isEmpty()) return new JSONObject();
        JSONObject outer = new JSONObject(body);
        Object data = outer.opt("data");
        if (data instanceof String) {
            String pt = decrypt((String) data);
            if (pt.isEmpty()) return new JSONObject();
            return new JSONObject(pt);
        }
        return new JSONObject(); // data 为数组/null = 空结果
    }

    private Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private List<Vod> toVods(JSONArray arr) {
        List<Vod> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Vod v = new Vod();
            v.setVodId(o.optString("vod_id"));
            v.setVodName(o.optString("vod_name"));
            v.setVodPic(o.optString("vod_pic"));
            v.setVodRemarks(o.optString("vod_remarks"));
            v.setVodYear(o.optString("vod_year"));
            v.setVodArea(o.optString("vod_area"));
            v.setVodActor(o.optString("vod_actor"));
            v.setVodDirector(o.optString("vod_director"));
            String blurb = o.optString("vod_blurb");
            if (blurb.isEmpty()) blurb = o.optString("vod_content");
            v.setVodContent(blurb);
            list.add(v);
        }
        return list;
    }

    // ---------- Spider 生命周期 ----------

    @Override
    public String homeContent(boolean filter) throws Exception {
        JSONObject init = api("init", new HashMap<String, String>());
        List<Class> classes = new ArrayList<>();
        JSONArray types = init.optJSONArray("type_list");
        if (types != null) {
            for (int i = 0; i < types.length(); i++) {
                JSONObject t = types.optJSONObject(i);
                if (t == null) continue;
                classes.add(new Class(t.optString("type_id"), t.optString("type_name")));
            }
        }
        List<Vod> vods = new ArrayList<>();
        vods.addAll(toVods(init.optJSONArray("recommend_list")));
        vods.addAll(toVods(init.optJSONArray("banner_list")));
        return Result.get().classes(classes).vod(vods).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = 1;
        try {
            page = Integer.parseInt(pg);
        } catch (Exception ignored) {
        }
        JSONObject d = api("rankList", map("type_id", tid, "pg", String.valueOf(page)));
        List<Vod> vods = toVods(d.optJSONArray("rank_list"));
        boolean more = vods.size() >= 20;
        return Result.get().vod(vods).page(page, more ? page + 1 : page, 20, 20).string();
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        int page = 1;
        try {
            page = Integer.parseInt(pg);
        } catch (Exception ignored) {
        }
        JSONObject d = api("searchList", map("keywords", key, "pg", String.valueOf(page)));
        List<Vod> vods = toVods(d.optJSONArray("search_list"));
        boolean more = vods.size() >= 20;
        return Result.get().vod(vods).page(page, more ? page + 1 : page, 20, 20).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return Result.error("缺少视频ID");
        JSONObject d = api("vodDetail", map("vod_id", ids.get(0)));
        JSONObject vj = d.optJSONObject("vod");
        if (vj == null) return Result.error("详情获取失败");
        Vod vod = new Vod();
        vod.setVodId(vj.optString("vod_id"));
        vod.setVodName(vj.optString("vod_name"));
        String pic = vj.optString("vod_pic");
        if (pic.isEmpty()) pic = vj.optString("vod_pic_thumb");
        vod.setVodPic(pic);
        vod.setVodRemarks(vj.optString("vod_remarks"));
        vod.setVodYear(vj.optString("vod_year"));
        vod.setVodArea(vj.optString("vod_area"));
        vod.setVodActor(vj.optString("vod_actor"));
        vod.setVodDirector(vj.optString("vod_director"));
        String blurb = vj.optString("vod_blurb");
        if (blurb.isEmpty()) blurb = vj.optString("vod_content");
        vod.setVodContent(blurb);

        List<String> froms = new ArrayList<>();
        List<String> urlGroups = new ArrayList<>();
        JSONArray plays = d.optJSONArray("vod_play_list");
        if (plays != null) {
            for (int i = 0; i < plays.length(); i++) {
                JSONObject p = plays.optJSONObject(i);
                if (p == null) continue;
                JSONObject info = p.optJSONObject("player_info");
                String show = info != null ? info.optString("show") : "";
                if (show.isEmpty()) show = "线路" + (i + 1);
                String parse = info != null ? info.optString("parse") : "";
                JSONArray urls = p.optJSONArray("urls");
                if (urls == null || urls.length() == 0) continue;
                List<String> eps = new ArrayList<>();
                for (int j = 0; j < urls.length(); j++) {
                    JSONObject u = urls.optJSONObject(j);
                    if (u == null) continue;
                    String name = u.optString("name");
                    if (name.isEmpty()) name = String.format("第%02d集", j + 1);
                    String url = u.optString("url");
                    if (url != null && url.startsWith("http")) {
                        eps.add(name + "$" + url); // 直链，直接播
                    } else {
                        // 哈希 url：打包解析所需字段，占位由 playerContent 调 vodParse
                        // 格式: p@vod_id@nid@index@parse@url@from@token
                        StringBuilder sb = new StringBuilder();
                        sb.append("p@").append(vj.optString("vod_id")).append("@");
                        sb.append(u.optString("nid")).append("@");
                        sb.append(String.valueOf(j)).append("@");
                        sb.append(parse).append("@");
                        sb.append(url == null ? "" : url).append("@");
                        sb.append(u.optString("from")).append("@");
                        sb.append(u.optString("token"));
                        eps.add(name + "$" + sb.toString());
                    }
                }
                froms.add(show);
                urlGroups.add(join(eps, "#"));
            }
        }
        vod.setVodPlayFrom(join(froms, "$$$"));
        vod.setVodPlayUrl(join(urlGroups, "$$$"));
        return Result.get().vod(vod).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (id == null || id.isEmpty()) return Result.error("播放地址为空");
        if (id.startsWith("http")) {
            Result r = Result.get().url(id);
            if (id.contains("m3u8")) r.m3u8();
            return r.string();
        }
        if (!id.startsWith("p@")) return Result.error("播放地址无效");
        String[] a = id.split("@");
        if (a.length < 8) return Result.error("播放信息不完整");
        String vid = a[1], nid = a[2], idx = a[3], parse = a[4], url = a[5], from = a[6], token = a[7];
        String real = tryParse(vid, nid, idx, parse, url, from, token);
        if (real == null || real.isEmpty()) return Result.error("解析失败");
        Result r = Result.get().url(real);
        if (real.contains("m3u8")) r.m3u8();
        return r.string();
    }

    /**
     * vodParse 参数组合未完全实锤（请求在 native VM 内发出，TLS 遮断），
     * 这里按抓包推断的候选组合依次尝试，命中即返回。
     */
    private String tryParse(String vid, String nid, String idx, String parse, String url, String from, String token) throws Exception {
        String apiUrl = parse + url; // parse_api_url = parse 段 + url 段
        String[][] combos = {
                {"url", apiUrl, "vod_id", vid, "nid", nid, "url_position", idx, "from", from, "token", token},
                {"url", url, "parse", parse, "vod_id", vid, "nid", nid, "url_position", idx, "from", from, "token", token},
                {"parse_api_url", apiUrl, "vod_id", vid, "nid", nid, "url_position", idx, "from", from, "token", token},
                {"vod_id", vid, "player_source_id", parse, "episode_index", idx, "url", url, "nid", nid}
        };
        for (int i = 0; i < combos.length; i++) {
            JSONObject d = api("vodParse", map(combos[i]));
            String u = pickUrl(d);
            if (u != null && !u.isEmpty()) return u;
        }
        return null;
    }

    /** vodParse 响应可能双层 JSON 字符串：{"json":"{\"code\":200,\"url\":\"...\"}"} */
    private String pickUrl(JSONObject d) throws Exception {
        if (d == null) return null;
        Object j = d.opt("json");
        if (j instanceof String) {
            try {
                d = new JSONObject((String) j);
            } catch (Exception e) {
                return null;
            }
        } else if (j instanceof JSONObject) {
            d = (JSONObject) j;
        }
        String u = d.optString("url");
        if (u != null && u.startsWith("http")) return u;
        return null;
    }

    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
