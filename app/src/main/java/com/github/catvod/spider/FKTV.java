package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import com.github.catvod.utils.AESEncryption;   // 新增导入
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

public class FKTV extends Spider {
    private static final String HOST = "https://fktv.me";
    private static final String API_URL = HOST + "/ysapi/movie/detail";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36";
    private static final String DEVICE_ID = "ffFrmAfy2sx5C6mSrTwX08bpi2YWn48t";
    private static final String AES_KEY_HEX = "39656431613636316136616237383761";
    private static final String[][] CHANNELS = {
            {"5", "连续剧"},
            {"6", "电影"},
            {"4", "综艺"},
            {"9", "短剧"},
    };

    // ------------------------------------------------------------------ 请求头
    @Override
    public void init(Context context, String extend) throws Exception {}
    private Map<String, String> getHeader() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", HOST + "/");
        return h;
    }
    private Map<String, String> getApiHeader(String movieId) {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("content-type", "application/octet-stream");
        h.put("accept", "*/*");
        h.put("origin", HOST);
        h.put("referer", HOST + "/movie/" + movieId);
        h.put("devicetype", "pc");
        h.put("version", "1.0");
        h.put("ip", "");
        h.put("sharecode", "");
        h.put("channel", "");
        h.put("cookie", "_did=" + DEVICE_ID);
        return h;
    }

    // ------------------------------------------------------------------ AES（已修改为使用 AESEncryption）
    private String encryptAES(String plainText) throws Exception {
        return AESEncryption.encrypt(plainText, AES_KEY_HEX, "", AESEncryption.ECB_PKCS_7_PADDING);
    }

    private String decryptAES(String base64Text) throws Exception {
        return AESEncryption.decrypt(base64Text, AES_KEY_HEX, "", AESEncryption.ECB_PKCS_7_PADDING);
    }

    // ------------------------------------------------------------------ 图片修正
    /** .bnc 改 .jpg，域名改为 cdn.g3ejjm8m.com */
    private String fixImage(String url) {
        if (url == null || url.isEmpty() || url.startsWith("data:")) return "";
        if (url.endsWith(".bnc")) {
            int idx = url.indexOf("/kk-208/");
            if (idx >= 0) url = "https://cdn.g3ejjm8m.com" + url.substring(idx);
            url = url.replace(".bnc", ".jpg");
        }
        return url;
    }

    // ------------------------------------------------------------------ 列表解析
    private List<Vod> parseList(String html) {
        List<Vod> list = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        for (Element item : doc.select("div.item-wrap.vertical")) {
            Element a = item.selectFirst("a[href^=/movie/]");
            if (a == null) continue;
            String href = a.attr("href"); // /movie/57c94967580455a9/mianpintu
            String name = a.attr("title");
            if (name.isEmpty()) name = a.text().trim();
            // vodId = "57c94967580455a9___mianpintu"（'/' 换 '___'，保留标题段方便还原 URL）
            String vodId = "";
            int mi = href.indexOf("/movie/");
            if (mi >= 0) vodId = href.substring(mi + 7).replace("/", "___");
            if (vodId.isEmpty() || name.isEmpty()) continue;
            String pic = "";
            Element img = item.selectFirst("img[src]");
            if (img != null) pic = fixImage(img.attr("src"));
            String remark = "";
            Element cat = item.selectFirst(".category");
            if (cat != null) remark = cat.text().trim();
            Vod vod = new Vod();
            vod.setVodId(vodId);
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodRemarks(remark);
            list.add(vod);
        }
        return list;
    }

    // ------------------------------------------------------------------ JSON 工具
    /**
     * 从原始 HTML 正则提取 JSON 数组（先做 \/ 反转义再匹配）
     */
    private JSONArray extractJsonArray(String html, String key) {
        String cleaned = html.replace("\\/", "/");
        Matcher m = Pattern.compile("\"" + key + "\":(\\[.+?\\])(?=\\s*[,}])")
                            .matcher(cleaned);
        if (m.find()) {
            try { return new JSONArray(m.group(1)); } catch (Exception ignored) {}
        }
        return null;
    }
    /**
     * 从 __next_f.push([1,"..."]) 提取 data 对象
     */
    private JSONObject extractDataFromNextF(String html) {
        String marker = "__next_f.push([1,\"";
        int pos = 0;
        while ((pos = html.indexOf(marker, pos)) >= 0) {
            int i = pos + marker.length();
            StringBuilder sb = new StringBuilder();
            while (i < html.length()) {
                char c = html.charAt(i);
                if (c == '\\' && i + 1 < html.length()) {
                    char nx = html.charAt(i + 1);
                    switch (nx) {
                        case '"': sb.append('"'); i += 2; break;
                        case '\\': sb.append('\\'); i += 2; break;
                        case 'n': sb.append('\n'); i += 2; break;
                        case 't': sb.append('\t'); i += 2; break;
                        case 'r': sb.append('\r'); i += 2; break;
                        case '/': sb.append('/'); i += 2; break;
                        default: sb.append(c); i++; break;
                    }
                } else if (c == '"') { break; }
                else { sb.append(c); i++; }
            }
            String text = sb.toString();
            if (!text.contains("\"links\"")) { pos = i; continue; }
            int dp = text.indexOf(",{\"data\":{");
            if (dp < 0) dp = text.indexOf("{\"data\":{");
            if (dp < 0) { pos = i; continue; }
            if (text.charAt(dp) == ',') dp++;
            int brace = 0, end = dp;
            for (int j = dp; j < text.length(); j++) {
                char ch = text.charAt(j);
                if (ch == '{') brace++;
                else if (ch == '}') { brace--; if (brace == 0) { end = j + 1; break; } }
            }
            if (end > dp) {
                try {
                    JSONObject data = new JSONObject(text.substring(dp, end)).optJSONObject("data");
                    if (data != null) return data;
                } catch (Exception ignored) {}
            }
            pos = i;
        }
        return null;
    }
    /** 递归从 JSONObject 查找指定 key 的字符串值 */
    private String findJsonKey(JSONObject obj, String key) {
        if (obj.has(key)) return obj.optString(key, "");
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            Object v = obj.opt(keys.next());
            String r = "";
            if (v instanceof JSONObject) r = findJsonKey((JSONObject) v, key);
            else if (v instanceof JSONArray) r = findJsonKeyArr((JSONArray) v, key);
            if (!r.isEmpty()) return r;
        }
        return "";
    }
    private String findJsonKeyArr(JSONArray arr, String key) {
        for (int i = 0; i < arr.length(); i++) {
            Object v = arr.opt(i);
            String r = "";
            if (v instanceof JSONObject) r = findJsonKey((JSONObject) v, key);
            else if (v instanceof JSONArray) r = findJsonKeyArr((JSONArray) v, key);
            if (!r.isEmpty()) return r;
        }
        return "";
    }
    /** 递归从 JSONObject 找 play_links 数组 */
    private JSONArray findPlayLinks(JSONObject obj) {
        if (obj.has("play_links")) return obj.optJSONArray("play_links");
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            Object v = obj.opt(keys.next());
            if (v instanceof JSONObject) {
                JSONArray r = findPlayLinks((JSONObject) v);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ Spider
    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        for (String[] ch : CHANNELS) classes.add(new Class(ch[0], ch[1]));
        String html = OkHttp.string(HOST + "/category/5/page/1", getHeader());
        return Result.get().classes(classes).vod(parseList(html)).string();
    }
    @Override
    public String homeVideoContent() throws Exception {
        String html = OkHttp.string(HOST + "/category/5/page/1", getHeader());
        return Result.get().vod(parseList(html)).string();
    }
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) throws Exception {
        int page = pg == null || pg.isEmpty() ? 1 : Integer.parseInt(pg);
        String html = OkHttp.string(HOST + "/category/" + tid + "/page/" + page, getHeader());
        List<Vod> vods = parseList(html);
        return Result.get().page(page, page + 1, 20, Integer.MAX_VALUE).vod(vods).string();
    }
    @Override
    public String detailContent(List<String> ids) throws Exception {
        String vodId = ids.get(0);
        String rawPath = vodId.replace("___", "/"); // 57c94967580455a9/mianpintu
        String movieId = vodId.split("___")[0]; // 57c94967580455a9
        String detailUrl = HOST + "/movie/" + rawPath;
        String html = OkHttp.string(detailUrl, getHeader());
        Document doc = Jsoup.parse(html);
        Vod vod = new Vod();
        String embedUrl = "";
        vod.setVodId(vodId);
        // 1. ld+json → 基础信息
        for (Element script : doc.select("script[type=application/ld+json]")) {
            try {
                JSONObject ld = new JSONObject(script.html());
                if (!"VideoObject".equals(ld.optString("@type"))) continue;
                vod.setVodName(ld.optString("name"));
                vod.setVodContent(ld.optString("description"));
                embedUrl = ld.optString("embedUrl", "");
                JSONArray thumbs = ld.optJSONArray("thumbnailUrl");
                if (thumbs != null && thumbs.length() > 0)
                    vod.setVodPic(fixImage(thumbs.getString(0)));
                JSONArray actArr = ld.optJSONArray("actor");
                if (actArr != null) {
                    List<String> names = new ArrayList<>();
                    for (int i = 0; i < actArr.length(); i++)
                        names.add(actArr.getJSONObject(i).optString("name"));
                    vod.setVodActor(String.join(",", names));
                }
                JSONArray dirArr = ld.optJSONArray("director");
                if (dirArr != null && dirArr.length() > 0)
                    vod.setVodDirector(dirArr.getJSONObject(0).optString("name"));
                break;
            } catch (Exception ignored) {}
        }
        // 2. 提取 links（集数列表）和 play_links（线路列表）
        JSONArray linksArr = extractJsonArray(html, "links");
        JSONArray playLinksArr = extractJsonArray(html, "play_links");
        // 兜底：从 __next_f.push 提取
        if (linksArr == null || playLinksArr == null) {
            JSONObject data = extractDataFromNextF(html);
            if (data != null) {
                if (linksArr == null) linksArr = data.optJSONArray("links");
                if (playLinksArr == null) playLinksArr = data.optJSONArray("play_links");
                if (embedUrl.isEmpty()) embedUrl = data.optString("m3u8_url_source", "");
            }
        }
        if (linksArr == null || playLinksArr == null
                || linksArr.length() == 0 || playLinksArr.length() == 0)
            return Result.get().vod(vod).string();
        // 3. 组装线路 + 集数
        // 第1集（idx=0）：embed_url 直链，不需要调解析接口
        // 第2集起（idx>0）：id = movieId@linkId，playerContent 调接口解析
        List<String> fromList = new ArrayList<>();
        List<String> urlList = new ArrayList<>();
        for (int li = 0; li < playLinksArr.length(); li++) {
            fromList.add(playLinksArr.getJSONObject(li).optString("name", "线路" + (li + 1)));
            StringBuilder eps = new StringBuilder();
            for (int i = 0; i < linksArr.length(); i++) {
                JSONObject ep = linksArr.getJSONObject(i);
                String epName = ep.optString("name", String.valueOf(i + 1));
                String epLid = ep.optString("id", "");
                if (eps.length() > 0) eps.append("#");
                if (i == 0 && !embedUrl.isEmpty()) {
                    eps.append(epName).append("$").append(embedUrl);
                } else {
                    eps.append(epName).append("$").append(movieId).append("@").append(epLid);
                }
            }
            urlList.add(eps.toString());
        }
        vod.setVodRemarks("共 " + linksArr.length() + " 集");
        vod.setVodPlayFrom(String.join("$$$", fromList));
        vod.setVodPlayUrl(String.join("$$$", urlList));
        return Result.get().vod(vod).string();
    }
    /**
     * flag = 线路名（线路1/线路2），id 两种格式：
     * 1. https://... → 第1集直链，直接返回
     * 2. movieId@linkId → 调解析接口
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // 第1集直链
        if (id.startsWith("http")) {
            return Result.get().url(id).header(getHeader()).string();
        }
        if (!id.contains("@")) return Result.get().url(id).string();
        String[] parts = id.split("@", 2);
        String movieId = parts[0];
        String linkId = parts[1];
        // 构造加密请求体
        JSONObject inner = new JSONObject();
        inner.put("id", movieId);
        inner.put("link_id", linkId);
        inner.put("is_simple", "y");
        JSONObject payload = new JSONObject();
        payload.put("deviceId", DEVICE_ID);
        payload.put("token", "");
        payload.put("domain", "fktv.me");
        payload.put("referer", "");
        payload.put("user_agent", UA);
        payload.put("shareCode", "");
        payload.put("channel", "");
        payload.put("ip", "");
        payload.put("data", inner);
        String encrypted = encryptAES(payload.toString());
        OkResult res = OkHttp.post(API_URL, encrypted, getApiHeader(movieId));
        if (res == null || res.getCode() != 200) return Result.get().url("").string();
        String body = res.getBody();
        if (body == null || body.trim().isEmpty()) return Result.get().url("").string();
        String decrypted = decryptAES(body.trim());
        JSONObject resp = new JSONObject(decrypted);
        // 优先：按 flag 匹配 play_links 里对应线路的 m3u8_url
        String realUrl = "";
        JSONArray playLinks = findPlayLinks(resp);
        if (playLinks != null) {
            for (int i = 0; i < playLinks.length(); i++) {
                JSONObject pl = playLinks.getJSONObject(i);
                if (flag.equals(pl.optString("name"))) {
                    realUrl = pl.optString("m3u8_url", "");
                    break;
                }
            }
            // 按 flag 没匹配到，取第一条
            if (realUrl.isEmpty() && playLinks.length() > 0)
                realUrl = playLinks.getJSONObject(0).optString("m3u8_url", "");
        }
        // 兜底：m3u8_url_source 或递归找 m3u8_url
        if (realUrl.isEmpty()) realUrl = resp.optString("m3u8_url_source", "");
        if (realUrl.isEmpty()) realUrl = findJsonKey(resp, "m3u8_url");
        return Result.get().url(realUrl).header(getHeader()).string();
    }
    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        String url = HOST + "/channel?keywords=" + URLEncoder.encode(key, "UTF-8");
        String html = OkHttp.string(url, getHeader());
        return Result.get().vod(parseList(html)).string();
    }
    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return searchContent(key, quick);
    }
}
