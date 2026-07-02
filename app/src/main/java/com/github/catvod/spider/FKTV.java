package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

public class FKTV extends Spider {

    private final String siteUrl = "https://fktv.me";
    private final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36";
    private final String COOKIE = "_did=wEdXiQxa07zJ15hm0AsNjxsc4rZRSKzb; _device=pc";
    private final String API_URL = "https://fktv.me/ysapi/movie/detail";
    private final String AES_KEY_HEX = "39656431613636316136616237383761"; // 16字节密钥十六进制

    private Map<String, String> getHeader() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", siteUrl + "/");
        headers.put("Accept", "application/json, text/javascript, */*; q=0.01");
        headers.put("Cookie", COOKIE);
        return headers;
    }

    @Override
    public void init(Context context, String extend) throws Exception {
    }

    // ==================== 分类部分（保持不变） ====================
    private String getCategoryName(String tid) {
        switch (tid) {
            case "6": return "电影";
            case "5": return "电视剧";
            case "4": return "综艺";
            case "9": return "短剧";
            default: return "";
        }
    }

    private List<Vod> parseList(String html) {
        List<Vod> list = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        Elements items = doc.select("div.item-wrap.vertical");
        for (Element item : items) {
            // 每个卡片里有多个 <a href=/movie/...>，取第一个不含 opacity-0 的或直接用第一个
            Element a = item.selectFirst("a[href^=/movie/]");
            if (a == null) continue;
            String href = siteUrl + a.attr("href");
            String name = a.attr("title");
            String remark = item.selectFirst(".category") != null
                    ? item.selectFirst(".category").text() : "";
            // 图片：Next.js 直接把 URL 写在 src 里，非 base64 占位图
            String pic = "";
            Element img = item.selectFirst("img[src]");
            if (img != null) {
                String src = img.attr("src");
                pic = fixImage(src); // 空字符串若是 data: URI
            }
            Vod vod = new Vod();
            vod.setVodId(href);
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodRemarks(remark);
            list.add(vod);
        }
        return list;
    }

    private String encodeUrl(String str) {
        try {
            return URLEncoder.encode(str, "UTF-8");
        } catch (Exception e) {
            return str.replace(" ", "%20");
        }
    }

    /**
     * 修正图片 URL：
     *  - 后缀 .bnc → .jpg
     *  - 同时把域名替换为 https://cdn.g3ejjm8m.com（保留 /kk-208/ 后的路径）
     */
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

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("6", "电影"));
        classes.add(new Class("5", "电视剧"));
        classes.add(new Class("4", "综艺"));
        classes.add(new Class("9", "短剧"));
        String url = siteUrl + "/category/6/" + encodeUrl("电影") + "/page/1";
        String html = OkHttp.string(url, getHeader());
        List<Vod> list = parseList(html);
        return Result.string(classes, list);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String catName = getCategoryName(tid);
        if (catName.isEmpty()) {
            String url = siteUrl + "/channel?page=" + pg + "&cat_id=" + tid + "&tag_id=&order=new&page_size=32";
            String html = OkHttp.string(url, getHeader());
            return Result.string(parseList(html));
        }
        String url = siteUrl + "/category/" + tid + "/" + encodeUrl(catName) + "/page/" + pg;
        String html = OkHttp.string(url, getHeader());
        return Result.string(parseList(html));
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        try {
            String url = siteUrl + "/channel?keywords=" + encodeUrl(key);
            String html = OkHttp.string(url, getHeader());
            return Result.string(parseList(html));
        } catch (Exception e) {
            return Result.string(new ArrayList<>());
        }
    }

    // ==================== 详情和播放（新加密 API） ====================
    private JSONObject extractDataJson(String html) {
        Document doc = Jsoup.parse(html);
        Elements scripts = doc.select("script");
        String scriptContent = "";
        for (Element s : scripts) {
            String content = s.html();
            if (content.contains("\"links\"") || content.contains("links")) {
                scriptContent = content;
                break;
            }
        }
        if (scriptContent.isEmpty()) return null;

        int dataIdx = scriptContent.indexOf("\"data\"");
        if (dataIdx == -1) dataIdx = scriptContent.indexOf("data");
        if (dataIdx == -1) return null;
        int start = scriptContent.lastIndexOf("{", dataIdx);
        if (start == -1) return null;

        int braceCount = 0;
        int end = start;
        for (int i = start; i < scriptContent.length(); i++) {
            char c = scriptContent.charAt(i);
            if (c == '{') braceCount++;
            else if (c == '}') {
                braceCount--;
                if (braceCount == 0) {
                    end = i + 1;
                    break;
                }
            }
        }
        if (end > start) {
            String jsonStr = scriptContent.substring(start, end);
            try {
                return new JSONObject(jsonStr);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    private List<String> extractLinkIdsFromJson(JSONObject dataJson) {
        List<String> ids = new ArrayList<>();
        try {
            JSONObject data = dataJson.optJSONObject("data");
            if (data == null) return ids;
            JSONArray links = data.optJSONArray("links");
            if (links != null) {
                for (int i = 0; i < links.length(); i++) {
                    JSONObject link = links.getJSONObject(i);
                    String id = link.optString("id");
                    if (!id.isEmpty()) ids.add(id);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return ids;
    }

    private String getStringFromJson(JSONObject dataJson, String key) {
        try {
            JSONObject data = dataJson.optJSONObject("data");
            if (data == null) return "";
            return data.optString(key, "");
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- AES 加解密（使用 Java 标准库） ----------
    private byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }

    private String encryptAES(String plainText, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        SecretKeySpec secretKey = new SecretKeySpec(key, "AES");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] encrypted = cipher.doFinal(plainText.getBytes("UTF-8"));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    private String decryptAES(String cipherTextBase64, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        SecretKeySpec secretKey = new SecretKeySpec(key, "AES");
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        byte[] decoded = Base64.getDecoder().decode(cipherTextBase64);
        byte[] decrypted = cipher.doFinal(decoded);
        return new String(decrypted, "UTF-8");
    }

    private String buildEncryptedRequest(String movieId, String linkId, String deviceId) throws Exception {
        JSONObject req = new JSONObject();
        req.put("deviceId", deviceId);
        req.put("token", "");
        req.put("domain", "fktv.me");
        req.put("referer", "");
        req.put("user_agent", UA);
        req.put("shareCode", "");
        req.put("channel", "");
        req.put("ip", "");
        JSONObject data = new JSONObject();
        data.put("id", movieId);
        data.put("link_id", linkId);
        data.put("is_simple", "y");
        req.put("data", data);

        byte[] key = hexToBytes(AES_KEY_HEX);
        return encryptAES(req.toString(), key);
    }

    private List<String> parsePlayLinksFromResponse(String encryptedResponse) throws Exception {
        byte[] key = hexToBytes(AES_KEY_HEX);
        String jsonStr = decryptAES(encryptedResponse, key);
        JSONObject resp = new JSONObject(jsonStr);
        JSONArray playLinks = resp.optJSONArray("play_links");
        List<String> lines = new ArrayList<>();
        if (playLinks != null) {
            for (int i = 0; i < playLinks.length(); i++) {
                JSONObject item = playLinks.getJSONObject(i);
                String name = item.optString("name", "线路" + (i+1));
                String m3u8 = item.optString("m3u8_url");
                if (!m3u8.isEmpty()) {
                    lines.add(name + "$" + m3u8);
                }
            }
        }
        return lines;
    }

    private List<String> getPlayUrls(String detailUrl, String linkId) {
        List<String> lines = new ArrayList<>();
        try {
            String movieId = "";
            Pattern p = Pattern.compile("/movie/([^/]+)");
            Matcher m = p.matcher(detailUrl);
            if (m.find()) {
                movieId = m.group(1);
            } else {
                String[] parts = detailUrl.split("/");
                if (parts.length >= 5) movieId = parts[4];
            }
            if (movieId.isEmpty()) return lines;

            String deviceId = "wEdXiQxa07zJ15hm0AsNjxsc4rZRSKzb";
            String encryptedReq = buildEncryptedRequest(movieId, linkId, deviceId);

            Map<String, String> headers = new HashMap<>(getHeader());
            headers.put("Content-Type", "application/octet-stream");
            OkResult res = OkHttp.post(API_URL, encryptedReq, headers);
            if (res.getCode() == 200) {
                String body = res.getBody();
                if (body != null && !body.isEmpty()) {
                    lines = parsePlayLinksFromResponse(body);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return lines;
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String detailUrl = ids.get(0).startsWith("http") ? ids.get(0) : siteUrl + ids.get(0);
        String   html    = OkHttp.string(detailUrl, getHeader());
        Document doc     = Jsoup.parse(html);

        Vod vod = new Vod();
        vod.setVodId(detailUrl);

        // 1. ld+json 解析基础信息
        for (Element script : doc.select("script[type=application/ld+json]")) {
            try {
                JSONObject ld = new JSONObject(script.html());
                if (!"VideoObject".equals(ld.optString("@type"))) continue;
                vod.setVodName(ld.optString("name"));
                vod.setVodContent(ld.optString("description"));
                JSONArray thumbs = ld.optJSONArray("thumbnailUrl");
                if (thumbs != null && thumbs.length() > 0)
                    vod.setVodPic(fixImage(thumbs.getString(0)));
                JSONArray actorArr = ld.optJSONArray("actor");
                if (actorArr != null) {
                    List<String> actNames = new ArrayList<>();
                    for (int i = 0; i < actorArr.length(); i++)
                        actNames.add(actorArr.getJSONObject(i).optString("name"));
                    vod.setVodActor(String.join(",", actNames));
                }
                JSONArray dirArr = ld.optJSONArray("director");
                if (dirArr != null && dirArr.length() > 0)
                    vod.setVodDirector(dirArr.getJSONObject(0).optString("name"));
                break;
            } catch (Exception ignored) {}
        }

        // 2. __next_f.push 解析 links + play_links
        JSONObject data = extractDataJson(html);
        if (data == null) return Result.get().vod(vod).string();

        List<String> linkIds = new ArrayList<>();
        JSONArray links = data.optJSONArray("links");
        if (links != null) {
            for (int i = 0; i < links.length(); i++) {
                String lid = links.getJSONObject(i).optString("id", "");
                if (!lid.isEmpty()) linkIds.add(lid);
            }
        }
        if (linkIds.isEmpty()) {
            String lid = data.optString("link_id", "");
            if (!lid.isEmpty()) linkIds.add(lid);
        }
        if (linkIds.isEmpty()) return Result.get().vod(vod).string();

        JSONArray playLinks = data.optJSONArray("play_links");
        if (playLinks == null || playLinks.length() == 0)
            return Result.get().vod(vod).string();

        // 3. 组装线路
        // 第1集：play_links 里已有直链，直接用 m3u8_url
        // 第2集起：id = detailUrl|linkId|lineIdx，playerContent 再调接口
        List<String> fromList = new ArrayList<>();
        List<String> urlList  = new ArrayList<>();

        for (int lineIdx = 0; lineIdx < playLinks.length(); lineIdx++) {
            JSONObject pl       = playLinks.getJSONObject(lineIdx);
            String     lineName = pl.optString("name", "线路" + (lineIdx + 1));
            String     ep1Url   = pl.optString("m3u8_url", "");
            fromList.add(lineName);

            StringBuilder eps = new StringBuilder();
            if (!ep1Url.isEmpty())
                eps.append("第1集$").append(ep1Url);

            for (int i = 1; i < linkIds.size(); i++) {
                if (eps.length() > 0) eps.append("#");
                eps.append("第").append(i + 1).append("集$")
                   .append(detailUrl).append("|").append(linkIds.get(i))
                   .append("|").append(lineIdx);
            }
            urlList.add(eps.toString());
        }

        vod.setVodPlayFrom(String.join("$$$", fromList));
        vod.setVodPlayUrl(String.join("$$$", urlList));
        return Result.get().vod(vod).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // 第1集：id 直接是 m3u8 地址，带上 Referer 直接推给播放器
        if (id.startsWith("http")) {
            return Result.get().url(id).header(getHeader()).string();
        }
        // 第2集起：id = detailUrl|linkId|lineIdx，调解析接口
        if (id.contains("|")) {
            String[] parts = id.split("\\|", 3);
            if (parts.length >= 3) {
                String detailUrl = parts[0];
                String linkId    = parts[1];
                int lineIndex    = 0;
                try { lineIndex = Integer.parseInt(parts[2]); } catch (Exception ignored) {}
                List<String> playList = getPlayUrls(detailUrl, linkId);
                if (lineIndex < playList.size()) {
                    String[] arr = playList.get(lineIndex).split("\\$", 2);
                    if (arr.length == 2) {
                        return Result.get().url(arr[1]).header(getHeader()).string();
                    }
                }
            }
        }
        return Result.get().url(id).string();
    }
}
