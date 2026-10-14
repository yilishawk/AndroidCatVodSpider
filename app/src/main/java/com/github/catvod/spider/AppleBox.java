package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.bean.Class;
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

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AppleBox extends Spider {

    private static final String BASE = "https://ttss.langfeng888.com";
    private static final String API_PATH = "/api.php/getappapi.index";

    private static final String AES_KEY = "";
    private static final String AES_IV = "";

    private static final String VERSION_CODE = "101";
    private static final String UI_MODE = "light";
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; 2211133C) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private String deviceId = "";
    private String backupDomain = "";
    private JSONObject appConfig = null;

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        if (TextUtils.isEmpty(deviceId)) deviceId = genDeviceId();
        try {
            JSONObject j = apiGet("initV119", "");
            if (j != null) {
                appConfig = j;
                parseConfig(j);
            }
        } catch (Exception ignored) {
        }
    }

    private static String genDeviceId() {
        try {
            SecureRandom r = new SecureRandom();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 32; i++) sb.append(Integer.toHexString(r.nextInt(16)));
            return sb.toString();
        } catch (Exception e) {
            return "00000000000000000000000000000000";
        }
    }

    private void parseConfig(JSONObject cfg) {
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Content-Type", "application/json; charset=utf-8");
        h.put("app-user-device-id", deviceId);
        h.put("app-version-code", VERSION_CODE);
        h.put("app-ui-mode", UI_MODE);
        String ts = String.valueOf(System.currentTimeMillis() / 1000L);
        h.put("app-api-verify-time", ts);
        h.put("app-api-verify-sign", sign(ts));
        return h;
    }

    private static String aesEncrypt(String data, String key, String iv) throws Exception {
        SecretKeySpec ks = new SecretKeySpec(key.getBytes("UTF-8"), "AES");
        IvParameterSpec ivs = new IvParameterSpec(iv.getBytes("UTF-8"));
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, ks, ivs);
        return Base64.encodeToString(c.doFinal(data.getBytes("UTF-8")), Base64.NO_PADDING);
    }

    private static String aesDecrypt(String b64, String key, String iv) {
        try {
            SecretKeySpec ks = new SecretKeySpec(key.getBytes("UTF-8"), "AES");
            IvParameterSpec ivs = new IvParameterSpec(iv.getBytes("UTF-8"));
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, ks, ivs);
            return new String(c.doFinal(Base64.decode(b64, Base64.DEFAULT)), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String sign(String ts) {
        try {
            String s = aesEncrypt(ts, AES_KEY, AES_IV);
            while (s.length() % 4 != 0) s += "=";
            return s;
        } catch (Exception e) {
            return "";
        }
    }

    private static String decryptBody(String body) {
        if (TextUtils.isEmpty(body)) return "";
        try {
            String b64 = body.trim();
            while (b64.length() % 4 != 0) b64 += "=";
            String plain = aesDecrypt(b64, AES_KEY, AES_IV);
            if (!TextUtils.isEmpty(plain)) return plain;
        } catch (Exception ignored) {
        }
        return body;
    }

    private List<String> bases() {
        List<String> ls = new ArrayList<>();
        if (!TextUtils.isEmpty(backupDomain) && backupDomain.startsWith("http")) ls.add(norm(backupDomain));
        ls.add(BASE);
        return ls;
    }

    private static String norm(String raw) {
        String s = raw.trim();
        if (!s.startsWith("http")) s = "https://" + s;
        return s.replaceAll("/+$", "");
    }

    private JSONObject apiGet(String action, String query) {
        for (String base : bases()) {
            try {
                String url = base + API_PATH + "/" + action + (TextUtils.isEmpty(query) ? "" : ("?" + query));
                Resp r = _get(url, buildHeaders());
                if (r.code == 200 && !TextUtils.isEmpty(r.body)) {
                    return toJson(decryptBody(r.body));
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private JSONObject apiPost(String action, String jsonBody) {
        for (String base : bases()) {
            try {
                String url = base + API_PATH + "/" + action;
                Resp r = _post(url, buildHeaders(), jsonBody);
                if (r.code == 200 && !TextUtils.isEmpty(r.body)) {
                    return toJson(decryptBody(r.body));
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static JSONObject toJson(String s) {
        if (TextUtils.isEmpty(s)) return null;
        try {
            int i = s.indexOf('{');
            if (i < 0) return null;
            JSONObject j = new JSONObject(s.substring(i));
            JSONObject data = j.optJSONObject("data");
            return data != null ? data : j;
        } catch (Exception e) {
            return null;
        }
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
            try { in.close(); } catch (Exception ignored) { }
        }
    }

    private static class Resp {
        int code;
        String body;
    }

    private JSONArray pickArray(JSONObject j) {
        if (j == null) return null;
        for (String k : new String[]{"list", "data", "vod", "items", "videos", "episodes"}) {
            Object o = j.opt(k);
            if (o instanceof JSONArray) return (JSONArray) o;
            if (o instanceof JSONObject) {
                for (String k2 : new String[]{"list", "data", "vod", "items", "episodes"}) {
                    JSONArray a = ((JSONObject) o).optJSONArray(k2);
                    if (a != null) return a;
                }
            }
        }
        return null;
    }

    private String pickStr(JSONObject o, String[] keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!TextUtils.isEmpty(v) && !"null".equals(v)) return v;
        }
        return "";
    }

    private String absUrl(String u) {
        if (TextUtils.isEmpty(u)) return u;
        if (u.startsWith("http://") || u.startsWith("https://")) return u;
        return BASE + "/" + u.replaceFirst("^/+", "");
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = parseClasses(appConfig);
        if (classes.isEmpty()) classes = defaultClasses();
        List<Vod> list = new ArrayList<>();
        return Result.string(classes, list);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                 HashMap<String, String> extend) throws Exception {
        int page = 1;
        try { page = Integer.parseInt(pg); } catch (Exception ignored) { }
        if (page < 1) page = 1;
        String query = "type_id=" + URLEncoder.encode(tid, "UTF-8") + "&page=" + page + "&limit=20";
        JSONObject d = apiGet("vodList", query);
        JSONArray arr = pickArray(d);
        List<Vod> list = parseList(arr);
        int total = d != null ? d.optInt("total", 0) : 0;
        int pageSize = 20;
        int pageCount = total > 0 ? Math.max((total + pageSize - 1) / pageSize, page)
                : (list.size() >= pageSize ? page + 1 : page);
        return Result.get().vod(list).page(page, Math.max(pageCount, page), pageSize, total).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        int page = 1;
        try { page = Integer.parseInt(pg); } catch (Exception ignored) { }
        if (page < 1) page = 1;
        String query = "word=" + URLEncoder.encode(key, "UTF-8") + "&page=" + page;
        JSONObject d = apiGet("vodSearch", query);
        List<Vod> list = parseList(pickArray(d));
        int total = d != null ? d.optInt("total", list.size()) : list.size();
        int pageCount = total > 0 ? Math.max((total + 19) / 20, page) : page;
        return Result.get().vod(list).page(page, pageCount, 20, total).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return Result.get().string();
        String id = ids.get(0);
        JSONObject d = apiPost("vodDetail", "{\"vod_id\":\"" + id + "\"}");
        Vod v = new Vod();
        v.setVodId(id);
        if (d != null) {
            v.setVodName(pickStr(d, new String[]{"title", "name", "vod_name"}));
            String pic = absUrl(pickStr(d, new String[]{"pic", "cover", "img", "vod_pic"}));
            if (!TextUtils.isEmpty(pic)) v.setVodPic(pic);
            v.setVodContent(pickStr(d, new String[]{"content", "description", "intro", "vod_content"}));
            v.setVodRemarks(pickStr(d, new String[]{"remarks", "vod_remarks", "note"}));
        }
        JSONArray eps = pickArray(d);
        if (eps != null && eps.length() > 0) {
            List<String> playUrls = new ArrayList<>();
            for (int i = 0; i < eps.length(); i++) {
                JSONObject e = eps.optJSONObject(i);
                if (e == null) continue;
                String title = pickStr(e, new String[]{"name", "title", "ep", "vod_name"});
                String url = absUrl(pickStr(e, new String[]{"url", "play_url", "vod_play_url"}));
                if (TextUtils.isEmpty(url)) continue;
                playUrls.add(title + "$" + url);
            }
            if (!playUrls.isEmpty()) {
                v.setVodPlayFrom("默认线路");
                v.setVodPlayUrl(TextUtils.join("#", playUrls));
            }
        }
        return Result.string(v);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (TextUtils.isEmpty(id)) return Result.error("播放地址为空");
        String playUrl = id;
        int d = id.indexOf('$');
        if (d >= 0 && d < id.length() - 1) playUrl = id.substring(d + 1);
        if (playUrl.startsWith("http://") || playUrl.startsWith("https://")) {
            String origin = playUrl;
            try {
                URL u = new URL(playUrl);
                origin = u.getProtocol() + "://" + u.getHost();
            } catch (Exception ignored) { }
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", UA);
            h.put("Referer", origin + "/");
            h.put("Accept", "*/*");
            return Result.get().parse(0).url(playUrl).header(h).string();
        }
        return Result.error("该集需 vodParse 二次解析（参数待实采）");
    }

    private List<Class> parseClasses(JSONObject cfg) {
        List<Class> cs = new ArrayList<>();
        if (cfg == null) return cs;
        JSONArray cats = cfg.optJSONArray("class");
        if (cats == null) cats = cfg.optJSONArray("categories");
        if (cats != null) {
            for (int i = 0; i < cats.length(); i++) {
                JSONObject c = cats.optJSONObject(i);
                if (c == null) continue;
                String cid = String.valueOf(c.opt("id"));
                String name = c.optString("name", cid);
                if (!TextUtils.isEmpty(cid) && !"null".equals(cid)) cs.add(new Class(cid, name));
            }
        }
        return cs;
    }

    private static List<Class> defaultClasses() {
        List<Class> cs = new ArrayList<>();
        cs.add(new Class("1", "电影"));
        cs.add(new Class("2", "连续剧"));
        cs.add(new Class("3", "综艺"));
        cs.add(new Class("4", "动漫"));
        return cs;
    }

    private List<Vod> parseList(JSONArray arr) {
        List<Vod> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = String.valueOf(o.opt("id"));
            if (TextUtils.isEmpty(id) || "null".equals(id)) continue;
            String name = pickStr(o, new String[]{"title", "name", "vod_name"});
            if (TextUtils.isEmpty(name)) continue;
            Vod v = new Vod();
            v.setVodId(id);
            v.setVodName(name);
            String pic = absUrl(pickStr(o, new String[]{"pic", "cover", "img", "vod_pic"}));
            if (!TextUtils.isEmpty(pic)) v.setVodPic(pic);
            v.setVodRemarks(pickStr(o, new String[]{"remarks", "note", "year"}));
            list.add(v);
        }
        return list;
    }
}
