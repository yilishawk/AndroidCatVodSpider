package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
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
    private final String AES_KEY = "39656431613636316136616237383761"; // hex key

    private Map<String, String> getHeader() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", host);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
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
        headers.put("accept", "*/*");
        return headers;
    }

    @Override
    public void init(Context context, String extend) throws Exception {
    }

    // ==================== 首页 ====================
    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("5", "连续剧"));
        classes.add(new Class("6", "电影"));
        classes.add(new Class("4", "综艺"));
        classes.add(new Class("9", "短剧"));

        if (filter) {
            Map<String, List<Filter>> filters = new HashMap<>();
            filters.put("5", new ArrayList<>());
            filters.put("6", new ArrayList<>());
            filters.put("4", new ArrayList<>());
            filters.put("9", new ArrayList<>());
            return Result.string(classes, filters);
        }
        return Result.string(classes);
    }

    // ==================== 分类页 ====================
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

            String pic = "";
            Element img = item.selectFirst("img");
            if (img != null) {
                pic = img.attr("src");
                if (pic.endsWith(".bnc")) pic = pic.replace(".bnc", ".jpg");
                if (!pic.startsWith("http")) {
                    pic = "https://cdn.g3ejjm8m.com" + (pic.startsWith("/") ? pic : "/" + pic);
                }
            }

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

    // ==================== 详情页 ====================
    @Override
    public String detailContent(List<String> ids) throws Exception {
        String vid = ids.get(0);
        String url = host + "/movie/" + vid;
        String html = OkHttp.string(url, getHeader());
        if (TextUtils.isEmpty(html)) return Result.string(new Vod());

        Document doc = Jsoup.parse(html);

        String name = "", pic = "", content = "";
        Elements scripts = doc.select("script[type=application/ld+json]");
        for (Element s : scripts) {
            if (s.html().contains("VideoObject")) {
                try {
                    JSONObject json = new JSONObject(s.html());
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

        // 图片修复
        if (pic.endsWith(".bnc")) pic = pic.replace(".bnc", ".jpg");
        if (!pic.startsWith("http")) pic = "https://cdn.g3ejjm8m.com" + (pic.startsWith("/") ? pic : "/" + pic);

        // 提取剧集
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
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (!line1.isEmpty()) {
            playMap.put("线路1", line1);
            playMap.put("线路2", line2);
        } else {
            // fallback
            String m3u8 = extractRegex(html, "\"m3u8_url\"\\s*:\\s*\"([^\"]+)\"", 0);
            if (!TextUtils.isEmpty(m3u8)) {
                line1.add("播放$" + m3u8);
                line2.add("播放$" + m3u8);
                playMap.put("线路1", line1);
                playMap.put("线路2", line2);
            }
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

    // ==================== 搜索 ====================
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

            String pic = "";
            Element img = item.selectFirst("img");
            if (img != null) {
                pic = img.attr("src");
                if (pic.endsWith(".bnc")) pic = pic.replace(".bnc", ".jpg");
                if (!pic.startsWith("http")) pic = "https://cdn.g3ejjm8m.com" + (pic.startsWith("/") ? pic : "/" + pic);
            }

            Vod vod = new Vod();
            vod.setVodId(vid);
            vod.setVodName(name);
            vod.setVodPic(pic);
            list.add(vod);
        }
        return Result.string(list);
    }

    // ==================== 播放解析 ====================
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String[] parts = id.split("\\|");
        String vid = parts[0];
        String linkId = parts.length > 1 ? parts[1] : "";
        String lineNum = parts.length > 2 ? parts[2] : "1";

        // 第一集尝试直链
        if (TextUtils.isEmpty(linkId) || "1".equals(linkId)) {
            String html = OkHttp.string(host + "/movie/" + vid, getHeader());
            String directM3u8 = extractRegex(html, "\"m3u8_url\"\\s*:\\s*\"([^\"]+)\"", 0);
            if (!TextUtils.isEmpty(directM3u8)) {
                return Result.get().url(directM3u8).string();
            }
        }

        // 其他集数走加密接口
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

            // 使用 AESEncryption 进行 ECB 加密
            String encrypted = AESEncryption.encrypt(plainText, AES_KEY, "", AESEncryption.ECB_PKCS_7_PADDING);

            if (TextUtils.isEmpty(encrypted)) {
                throw new Exception("加密失败");
            }

            String apiUrl = host + "/ysapi/movie/detail";
            String resp = OkHttp.post(apiUrl, encrypted, getApiHeader());

            if (!TextUtils.isEmpty(resp)) {
                String m3u8 = extractRegex(resp, "\"m3u8_url\"\\s*:\\s*\"([^\"]+)\"", 0);
                if (!TextUtils.isEmpty(m3u8)) {
                    return Result.get().url(m3u8).string();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 兜底嗅探
        return Result.get().url(host + "/movie/" + vid).parse(1).string();
    }

    private String extractRegex(String text, String regex) {
        return extractRegex(text, regex, 0);
    }

    private String extractRegex(String text, String regex, int flags) {
        if (TextUtils.isEmpty(text)) return "";
        Matcher m = Pattern.compile(regex, flags).matcher(text);
        return m.find() ? m.group(1) : "";
    }
}
