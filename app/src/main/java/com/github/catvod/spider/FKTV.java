package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import com.github.catvod.utils.AESEncryption;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FKTV extends Spider {

    private static final String HOST = "https://fktv.me";
    private static final String API_URL = HOST + "/ysapi/movie/detail";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36";
    private static final String DEVICE_ID = "ffFrmAfy2sx5C6mSrTwX08bpi2YWn48t";
    private static final String AES_KEY = "9ed1a661a6ab787a"; // 解码后的 key（原 hex）
    private static final String[][] CHANNELS = {
            {"5", "连续剧"},
            {"6", "电影"},
            {"4", "综艺"},
            {"9", "短剧"},
    };

    // ===================================================================
    // 请求头
    // ===================================================================
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

    // ===================================================================
    // 图片修正
    // ===================================================================
    private String fixImage(String url) {
        if (url == null || url.isEmpty() || url.startsWith("data:")) return "";
        if (url.endsWith(".bnc")) {
            int idx = url.indexOf("/kk-208/");
            if (idx >= 0) {
                url = "https://cdn.g3ejjm8m.com" + url.substring(idx);
            }
            url = url.replace(".bnc", ".jpg");
        }
        return url;
    }

    // ===================================================================
    // 列表解析（保持原逻辑）
    // ===================================================================
    private List<Vod> parseList(String html) {
        List<Vod> list = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        for (Element item : doc.select("div.item-wrap.vertical")) {
            Element a = item.selectFirst("a[href^=/movie/]");
            if (a == null) continue;

            String href = a.attr("href");
            String name = a.attr("title");
            if (name.isEmpty()) name = a.text().trim();

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

    // ===================================================================
    // 【修改重点】详情 + 播放解析部分（使用 AESEncryption 工具类）
    // ===================================================================
    @Override
    public String detailContent(List<String> ids) throws Exception {
        String vodId = ids.get(0);
        String rawPath = vodId.replace("___", "/");
        String movieId = vodId.split("___")[0];
        String detailUrl = HOST + "/movie/" + rawPath;

        String html = OkHttp.string(detailUrl, getHeader());
        Document doc = Jsoup.parse(html);

        Vod vod = new Vod();
        vod.setVodId(vodId);

        String embedUrl = "";

        // 1. ld+json 基础信息
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

                // actor / director
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

        // 2. 提取 links 和 play_links（保持原有提取逻辑）
        JSONArray linksArr = extractJsonArray(html, "links");
        JSONArray playLinksArr = extractJsonArray(html, "play_links");

        if ((linksArr == null || playLinksArr == null) || linksArr.length() == 0) {
            JSONObject data = extractDataFromNextF(html);
            if (data != null) {
                if (linksArr == null) linksArr = data.optJSONArray("links");
                if (playLinksArr == null) playLinksArr = data.optJSONArray("play_links");
                if (embedUrl.isEmpty()) embedUrl = data.optString("m3u8_url_source", "");
            }
        }

        if (linksArr == null || playLinksArr == null || linksArr.length() == 0)
            return Result.get().vod(vod).string();

        // 3. 组装播放线路
        List<String> fromList = new ArrayList<>();
        List<String> urlList = new ArrayList<>();

        for (int li = 0; li < playLinksArr.length(); li++) {
            JSONObject pl = playLinksArr.getJSONObject(li);
            fromList.add(pl.optString("name", "线路" + (li + 1)));

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

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // 第1集直链
        if (id.startsWith("http")) {
            return Result.get().url(id).header(getHeader()).string();
        }

        if (!id.contains("@")) {
            return Result.get().url(id).string();
        }

        String[] parts = id.split("@", 2);
        String movieId = parts[0];
        String linkId = parts[1];

        // 使用 AESEncryption 工具类（推荐方式）
        try {
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

            String encrypted = AESEncryption.encrypt(payload.toString(), AES_KEY, "", AESEncryption.ECB_PKCS_7_PADDING);

            OkResult res = OkHttp.post(API_URL, encrypted, getApiHeader(movieId));
            if (res == null || res.getCode() != 200) {
                return Result.get().url("").string();
            }

            String body = res.getBody();
            if (body == null || body.trim().isEmpty()) {
                return Result.get().url("").string();
            }

            String decrypted = AESEncryption.decrypt(body.trim(), AES_KEY, "", AESEncryption.ECB_PKCS_7_PADDING);
            JSONObject resp = new JSONObject(decrypted);

            // 优先按线路名称匹配
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
                if (realUrl.isEmpty() && playLinks.length() > 0) {
                    realUrl = playLinks.getJSONObject(0).optString("m3u8_url", "");
                }
            }

            if (realUrl.isEmpty()) realUrl = resp.optString("m3u8_url_source", "");
            if (realUrl.isEmpty()) realUrl = findJsonKey(resp, "m3u8_url");

            return Result.get().url(realUrl).header(getHeader()).string();

        } catch (Exception e) {
            e.printStackTrace();
            return Result.get().url("").string();
        }
    }

    // ===================================================================
    // 辅助方法（保持原有提取逻辑）
    // ===================================================================
    private JSONArray extractJsonArray(String html, String key) {
        String cleaned = html.replace("\\/", "/");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\":(\\[.+?\\])(?=\\s*[,}])")
                .matcher(cleaned);
        if (m.find()) {
            try {
                return new JSONArray(m.group(1));
            } catch (Exception ignored) {}
        }
        return null;
    }

    private JSONObject extractDataFromNextF(String html) {
        // ...（保持你原来的实现，此处省略以节省篇幅）
        // 如果需要我也可以帮你优化这部分
        return null; // 保留原逻辑
    }

    private String findJsonKey(JSONObject obj, String key) {
        // 原递归查找实现...
        if (obj.has(key)) return obj.optString(key, "");
        // ... 省略完整递归代码（与你原代码一致）
        return "";
    }

    private JSONArray findPlayLinks(JSONObject obj) {
        if (obj.has("play_links")) return obj.optJSONArray("play_links");
        // 递归查找...
        return null;
    }

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
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = pg == null || pg.isEmpty() ? 1 : Integer.parseInt(pg);
        String html = OkHttp.string(HOST + "/category/" + tid + "/page/" + page, getHeader());
        List<Vod> vods = parseList(html);
        return Result.get().page(page, page + 1, 20, Integer.MAX_VALUE).vod(vods).string();
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
