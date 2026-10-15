package com.github.catvod.spider;

import android.content.Context;
import android.util.Base64;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;

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
 * TVB云播 (com.hktvyb.app) —— 直播源/点播爬虫
 *
 * 接口（均 POST form，无签名）：
 *   /api/vod/init       首页初始化：banner_list / hot_search_list / recommend_list / type_list(分类)
 *   /api/vod/rankList   分类片单：type_id + pg          → rank_list
 *   /api/vod/searchList 搜索：keywords + pg             → search_list
 *   /api/vod/vodDetail  详情：vod_id                    → vod + player_source_list + same_list
 *   /api/vod/vodParse   播放解析：vod_id + player_source_id + episode_index → {play_url, danmu_url}
 *
 * 响应：{"code":0,"msg":"","data":"<base64(AES-128-CBC/PKCS5)>"}
 * 密码：key = IV = MickwrKuMickwrKu（libnmmp.so 静态定位 + hook 双向实锤）
 *
 * 站点 ext 可覆盖默认域名，例如：
 *   {"host":"http://app.tuxianimg.com"}            默认
 *   {"host":"http://104.233.148.70"}               本机 DNS 解析不了域名时走 IP（自动带 Host 头）
 */
public class Tvyb extends Spider {

    private static final String KEY = "MickwrKuMickwrKu"; // key = IV
    private static final String DEVICE_ID = "29227c815e45939da8cb0bbd13f3a3cde";
    private static final String DEFAULT_HOST = "app.tuxianimg.com";
    private static final String DEFAULT_IP = "104.233.148.70";

    private String base = "http://" + DEFAULT_HOST;
    private String hostHeader = DEFAULT_HOST; // 走 IP 直连时用于 Host 头

    @Override
    public void init(Context context, String extend) throws Exception {
        if (extend != null) {
            String host = extend.trim();
            if (!host.isEmpty()) {
                try {
                    JSONObject j = new JSONObject(host);
                    if (j.has("host")) host = j.getString("host");
                } catch (Exception ignored) {
                    // ext 直接就是地址
                }
                while (host.endsWith("/")) host = host.substring(0, host.length() - 1);
                if (host.startsWith("http")) {
                    base = host;
                    String h = host.substring(host.indexOf("//") + 2);
                    int slash = h.indexOf('/');
                    hostHeader = slash > 0 ? h.substring(0, slash) : h;
                }
            }
        }
    }

    // ---------- 基础设施 ----------

    private void logger(String msg) {
        try {
            SpiderDebug.log("[Tvyb] " + msg);
        } catch (Exception ignored) {
        }
    }

    private Map<String, String> header() {
        Map<String, String> h = new HashMap<>();
        h.put("App-Device-Id", DEVICE_ID);
        h.put("App-Os-Type", "android");
        h.put("App-Ui-Mode", "light");
        h.put("App-Version-Code", "100");
        h.put("User-Agent", "okhttp/3.14.9");
        if (isIp(hostHeader)) h.put("Host", hostHeader);
        return h;
    }

    private static boolean isIp(String s) {
        if (s == null || s.isEmpty()) return false;
        String[] p = s.split("\\.");
        if (p.length != 4) return false;
        for (int i = 0; i < 4; i++) {
            try {
                int n = Integer.parseInt(p[i]);
                if (n < 0 || n > 255) return false;
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    private String decrypt(String data) throws Exception {
        if (data == null || data.trim().isEmpty()) return "";
        byte[] ct = Base64.decode(data.replaceAll("\\s+", ""), Base64.NO_WRAP);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY.getBytes("UTF-8"), "AES"), new IvParameterSpec(KEY.getBytes("UTF-8")));
        return new String(c.doFinal(ct), "UTF-8");
    }

    /** 调接口并返回解密后的内层 JSON；失败/空数据返回空 JSONObject */
    private JSONObject api(String action, Map<String, String> params) throws Exception {
        OkResult res = OkHttp.post(base + "/api/vod/" + action, params, header());
        int code = res.getCode();
        String body = res.getBody();
        logger(action + " → HTTP " + code + " len=" + (body == null ? 0 : body.length()) + (body != null && body.length() > 120 ? " head=" + body.substring(0, 120) : ""));
        if (body == null || body.trim().isEmpty()) {
            // 域名解析失败兜底：走 IP + Host 头
            if (!isIp(hostHeader)) {
                logger(action + " 响应为空，尝试 IP 直连兜底");
                hostHeader = DEFAULT_IP;
                Map<String, String> h = header();
                res = OkHttp.post("http://" + DEFAULT_IP + "/api/vod/" + action, params, h);
                code = res.getCode();
                body = res.getBody();
                logger(action + " IP兜底 → HTTP " + code + " len=" + (body == null ? 0 : body.length()));
            }
        }
        if (body == null || body.trim().isEmpty()) {
            logger(action + " 响应为空（网络失败或被拦截）");
            return new JSONObject();
        }
        JSONObject outer;
        try {
            outer = new JSONObject(body);
        } catch (Exception e) {
            logger(action + " 响应非 JSON: " + body.substring(0, Math.min(120, body.length())));
            return new JSONObject();
        }
        Object data = outer.opt("data");
        if (!(data instanceof String)) {
            logger(action + " data 非字符串（code=" + outer.opt("code") + " msg=" + outer.opt("msg") + "）");
            return new JSONObject();
        }
        String pt;
        try {
            pt = decrypt((String) data);
        } catch (Exception e) {
            logger(action + " 解密失败: " + e.getMessage());
            return new JSONObject();
        }
        if (pt.isEmpty()) {
            logger(action + " 解密结果为空");
            return new JSONObject();
        }
        return new JSONObject(pt);
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
        logger("homeContent: classes=" + classes.size() + " vods=" + vods.size());
        Result r = Result.get().classes(classes).vod(vods);
        if (classes.isEmpty()) r.msg("TVB init 无分类（详见 logcat SpiderDebug）");
        return r.string();
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
        vod.setVodPic(vj.optString("vod_pic"));
        vod.setVodRemarks(vj.optString("vod_remarks"));
        vod.setVodYear(vj.optString("vod_year"));
        vod.setVodArea(vj.optString("vod_area"));
        vod.setVodActor(vj.optString("vod_actor"));
        vod.setVodDirector(vj.optString("vod_director"));
        String blurb = vj.optString("vod_blurb");
        if (blurb.isEmpty()) blurb = vj.optString("vod_content");
        vod.setVodContent(blurb);

        // player_source_list: player_code -> player_name
        Map<String, String> code2name = new HashMap<>();
        JSONArray srcs = d.optJSONArray("player_source_list");
        if (srcs != null) {
            for (int i = 0; i < srcs.length(); i++) {
                JSONObject s = srcs.optJSONObject(i);
                if (s != null) code2name.put(s.optString("player_code"), s.optString("player_name"));
            }
        }

        List<String> froms = new ArrayList<>();
        List<String> urlGroups = new ArrayList<>();
        JSONArray plays = d.optJSONArray("vod_play_url_list");
        if (plays != null) {
            for (int i = 0; i < plays.length(); i++) {
                JSONObject p = plays.optJSONObject(i);
                if (p == null) continue;
                String code = p.optString("player_code");
                String name = code2name.get(code);
                if (name == null || name.isEmpty()) name = "线路" + code;
                JSONArray urls = p.optJSONArray("urls");
                if (urls == null || urls.length() == 0) continue;
                List<String> eps = new ArrayList<>();
                for (int j = 0; j < urls.length(); j++) {
                    JSONObject u = urls.optJSONObject(j);
                    if (u == null) continue;
                    int idx = u.optInt("episode_index", j);
                    String url = u.optString("url");
                    // url 为空 = 详情不下发直链，占位后由 playerContent 调 vodParse 解析
                    // 占位格式: vod_id@player_code@episode_index
                    String playId = (url == null || url.isEmpty())
                            ? (vj.optString("vod_id") + "@" + code + "@" + idx) : url;
                    eps.add(String.format("第%02d集$%s", idx + 1, playId));
                }
                froms.add(name);
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
            // 详情直链（部分源直下发）
            Result r = Result.get().url(id);
            if (id.contains("m3u8")) r.m3u8();
            return r.string();
        }
        // 占位 id: vod_id@player_code@episode_index
        String[] a = id.split("@");
        if (a.length < 3) return Result.error("播放地址无效");
        JSONObject d = api("vodParse", map("vod_id", a[0], "player_source_id", a[1], "episode_index", a[2]));
        String url = d.optString("play_url");
        if (url.isEmpty()) return Result.error("解析失败：" + d.optString("msg"));
        Result r = Result.get().url(url);
        if (url.contains("m3u8")) r.m3u8();
        return r.string();
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
