package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FKTV extends Spider {

    private final String host = "https://fktv.me";
    private final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36";
    private final String AES_HEX_KEY = "39656431613636316136616237383761";

    private Map<String, String> getHeader() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", host);
        return headers;
    }

    private Map<String, String> getApiHeader() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", host);
        headers.put("Content-Type", "application/octet-stream");
        headers.put("devicetype", "pc");
        headers.put("version", "1.0");
        headers.put("origin", host);
        headers.put("channel", "");
        return headers;
    }

    @Override
    public void init(Context context, String extend) throws Exception {
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("5", "连续剧"));
        classes.add(new Class("6", "电影"));
        classes.add(new Class("4", "综艺"));
        classes.add(new Class("9", "短剧"));

        if (filter) {
            LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();
            filters.put("5", new ArrayList<>());
            filters.put("6", new ArrayList<>());
            filters.put("4", new ArrayList<>());
            filters.put("9", new ArrayList<>());
            return Result.string(classes, filters);
        }
        // 关键修复：使用兼容的重载
        return Result.string(classes, new LinkedHashMap<String, List<Filter>>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String url = host + "/category/" + tid + "/page/" + pg;
        String html = OkHttp.string(url, getHeader());
        if (TextUtils.isEmpty(html)) return Result.string(new ArrayList<>());

        Document doc = Jsoup.parse(html);
        Elements items = doc.select(".item-wrap");
        List<Vod> list = new ArrayList<>();

        for (Element item : items) {
            Element a = item.selectFirst("a[href^=/movie/]");
            if (a == null) continue;

            String href = a.attr("href");
            String vid = href.replace("/movie/", "").split("/")[0];
            String name = a.attr("title");

            String pic = getFixedPic(item.selectFirst("img"));
            String remark = item.selectFirst(".tag") != null ? item.selectFirst(".tag").text() : "";

            Vod vod = new Vod();
            vod.setVodId(vid);
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodRemarks(remark);
            list.add(vod);
        }
        return Result.string(list);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String vid = ids.get(0);
        String url = host + "/movie/" + vid;
        String html = OkHttp.string(url, getHeader());
        if (TextUtils.isEmpty(html)) return Result.string(new Vod());

        Document doc = Jsoup.parse(html);

        String name = "", pic = "", content = "";
        for (Element script : doc.select("script[type=application/ld+json]")) {
            if (script.html().contains("VideoObject")) {
                try {
                    JSONObject json = new JSONObject(script.html());
                    name = json.optString("name");
                    pic = json.optJSONArray("thumbnailUrl") != null ? json.optJSONArray("thumbnailUrl").optString(0) : "";
                    content = json.optString("description");
                } catch (Exception ignored) {}
                break;
            }
        }

        if (TextUtils.isEmpty(name)) {
            name = doc.selectFirst("h1") != null ? doc.selectFirst("h1").text().trim() : "未知";
        }
        pic = getFixedPicStr(pic);

        Map<String, List<String>> playMap = new LinkedHashMap<>();
        List<String> line1 = new ArrayList<>();
        List<String> line2 = new ArrayList<>();

        String linksJson = extractRegex(html, "\"links\"\\s*:\\s*(\\[.*?\\])", Pattern.DOTALL);
        if (!TextUtils.isEmpty(linksJson)) {
            try {
                JSONArray arr = new JSONArray(linksJson);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String epName = obj.optString("name", "第" + (i + 1) + "集");
                    String linkId = obj.optString("id");
                    line1.add(epName + "$" + vid + "|" + linkId + "|1");
                    line2.add(epName + "$" + vid + "|" + linkId + "|2");
                }
            } catch (Exception ignored) {}
        }

        if (!line1.isEmpty()) {
            playMap.put("线路1", line1);
            playMap.put("线路2", line2);
        }

        Vod vod = new Vod();
        vod.setVodId(vid);
        vod.setVodName(name);
        vod.setVodPic(pic);
        vod.setVodContent(content);
        vod.setVodPlayFrom(String.join("$$$", playMap.keySet()));
        vod.setVodPlayUrl(String.join("$$$", playMap.values().stream().map(v -> String.join("#", v)).toList()));

        return Result.string(vod);
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        String url = host + "/channel?keywords=" + URLEncoder.encode(key, "UTF-8");
        String html = OkHttp.string(url, getHeader());
        Document doc = Jsoup.parse(html);

        Elements items = doc.select(".item-wrap");
        List<Vod> list = new ArrayList<>();

        for (Element item : items) {
            Element a = item.selectFirst("a[href^=/movie/]");
            if (a == null) continue;

            String href = a.attr("href");
            String vid = href.replace("/movie/", "").split("/")[0];
            String name = a.attr("title");
            String pic = getFixedPic(item.selectFirst("img"));

            Vod vod = new Vod();
            vod.setVodId(vid);
            vod.setVodName(name);
            vod.setVodPic(pic);
            list.add(vod);
        }
        return Result.string(list);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String[] parts = id.split("\\|");
        String vid = parts[0];
        String linkId = parts.length > 1 ? parts[1] : "";

        if (TextUtils.isEmpty(linkId) || "1".equals(linkId)) {
            String html = OkHttp.string(host + "/movie/" + vid, getHeader());
            String m3u8 = extractRegex(html, "\"m3u8_url\"\\s*:\\s*\"([^\"]+)\"", 0);
            if (!TextUtils.isEmpty(m3u8)) {
                return Result.get().url(m3u8).string();
            }
        }

        try {
            JSONObject dataObj = new JSONObject();
            dataObj.put("deviceId", "ffFrmAfy2sx5C6mSrTwX08bpi2YWn48t");
            dataObj.put("token", "");
            dataObj.put("domain", "fktv.me");
            dataObj.put("referer", "");
            dataObj.put("user_agent", UA);
            dataObj.put("shareCode", "");
            dataObj.put("channel", "");
            dataObj.put("ip", "");

            JSONObject inner = new JSONObject();
            inner.put("id", vid);
            inner.put("link_id", linkId);
            inner.put("is_simple", "y");
            dataObj.put("data", inner);

            String plainText = dataObj.toString();
            String encrypted = encryptHex(plainText);

            if (!TextUtils.isEmpty(encrypted)) {
                OkResult okResult = OkHttp.post(host + "/ysapi/movie/detail", encrypted, getApiHeader());
                String resp = okResult.getBody();
                String m3u8 = extractRegex(resp, "\"m3u8_url\"\\s*:\\s*\"([^\"]+)\"", 0);
                if (!TextUtils.isEmpty(m3u8)) {
                    return Result.get().url(m3u8).string();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return Result.get().url(host + "/movie/" + vid).parse(1).string();
    }

    private String getFixedPic(Element img) {
        if (img == null) return "";
        return getFixedPicStr(img.attr("src"));
    }

    private String getFixedPicStr(String pic) {
        if (TextUtils.isEmpty(pic)) return "";
        if (pic.endsWith(".bnc")) pic = pic.replace(".bnc", ".jpg");
        if (!pic.startsWith("http")) {
            pic = "https://cdn.g3ejjm8m.com" + (pic.startsWith("/") ? pic : "/" + pic);
        }
        return pic;
    }

    private String encryptHex(String plain) {
        try {
            byte[] keyBytes = hexStringToByteArray(AES_HEX_KEY);
            String keyStr = new String(keyBytes, "ISO-8859-1");
            return AESEncryption.encrypt(plain, keyStr, "", AESEncryption.ECB_PKCS_7_PADDING);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    private String extractRegex(String text, String regex, int flags) {
        if (TextUtils.isEmpty(text)) return "";
        Matcher m = Pattern.compile(regex, flags).matcher(text);
        return m.find() ? m.group(1) : "";
    }

    private String extractRegex(String text, String regex) {
        return extractRegex(text, regex, 0);
    }
}
