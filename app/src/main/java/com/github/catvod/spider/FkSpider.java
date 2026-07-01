package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

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

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 凡客影视 (fktv.me) Spider
 * 支持分类、详情、播放（第一集直接取线路地址，后续集数调用加密API）、搜索
 */
public class FkSpider extends Spider {

    private static final String HOST = "https://fktv.me";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36";
    private static final String API_DETAIL = HOST + "/ysapi/movie/detail";

    // 分类映射（名称 -> ID）
    private static final LinkedHashMap<String, String> CATEGORY_MAP = new LinkedHashMap<>();
    static {
        CATEGORY_MAP.put("连续剧", "5");
        CATEGORY_MAP.put("电影", "6");
        CATEGORY_MAP.put("综艺", "4");
        CATEGORY_MAP.put("短剧", "9");
    }

    // AES 密钥（hex 解码）
    private static final String AES_KEY_HEX = "39656431613636316136616237383761";

    // 第一集信息（用于 playerContent 特殊处理）
    private String firstLinkId = "";
    private String firstM3u8Url = "";

    private void logger(String msg) {
        System.out.println("[FkSpider] " + msg);
    }

    // ---------- 网络请求 ----------
    private String get(String url) {
        return get(url, null);
    }

    private String get(String url, String referer) {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA);
            if (!TextUtils.isEmpty(referer)) headers.put("Referer", referer);
            return OkHttp.string(url, headers);
        } catch (Exception e) {
            logger("请求失败: " + url + " → " + e.getMessage());
            return "";
        }
    }

    // ---------- 图片 URL 处理 ----------
    private String fixImageUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        // 替换域名
        if (!url.contains("cdn.g3ejjm8m.com")) {
            String path = url.replaceFirst("^https?://[^/]+", "");
            url = "https://cdn.g3ejjm8m.com" + path;
        }
        // 后缀 bnc -> jpg
        if (url.endsWith(".bnc")) {
            url = url.substring(0, url.length() - 4) + ".jpg";
        }
        return url;
    }

    // ---------- AES 加密/解密 ----------
    private static SecretKeySpec getKey() {
        byte[] keyBytes = hexStringToByteArray(AES_KEY_HEX);
        return new SecretKeySpec(keyBytes, "AES");
    }

    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    private String encryptAES(String plainText) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, getKey());
            byte[] encrypted = cipher.doFinal(plainText.getBytes("UTF-8"));
            return android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            logger("AES 加密失败: " + e.getMessage());
            return null;
        }
    }

    private String decryptAES(String cipherText) {
        try {
            byte[] encrypted = android.util.Base64.decode(cipherText, android.util.Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, getKey());
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, "UTF-8");
        } catch (Exception e) {
            logger("AES 解密失败: " + e.getMessage());
            return null;
        }
    }

    // ---------- 调用加密 API 获取单集播放地址 ----------
    private String fetchPlayUrlByLinkId(String vodId, String linkId) {
        try {
            // 构造请求 JSON
            Map<String, Object> dataMap = new HashMap<>();
            dataMap.put("id", vodId);
            dataMap.put("link_id", linkId);
            dataMap.put("is_simple", "y");

            Map<String, Object> root = new HashMap<>();
            root.put("deviceId", "ffFrmAfy2sx5C6mSrTwX08bpi2YWn48t");
            root.put("token", "");
            root.put("domain", "fktv.me");
            root.put("referer", "");
            root.put("user_agent", UA);
            root.put("shareCode", "");
            root.put("channel", "");
            root.put("ip", "");
            root.put("data", dataMap);

            String json = new com.google.gson.Gson().toJson(root);
            String encrypted = encryptAES(json);
            if (encrypted == null) return null;
            byte[] encryptedBytes = android.util.Base64.decode(encrypted, android.util.Base64.NO_WRAP);

            // 使用 OkHttp 发送二进制数据
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            RequestBody body = RequestBody.create(MediaType.parse("application/octet-stream"), encryptedBytes);
            Request request = new Request.Builder()
                    .url(API_DETAIL)
                    .post(body)
                    .addHeader("User-Agent", UA)
                    .addHeader("Referer", HOST + "/movie/" + vodId)
                    .addHeader("Content-Type", "application/octet-stream")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    byte[] responseBytes = response.body().bytes();
                    String responseJson = new String(responseBytes, "UTF-8");
                    String decrypted = decryptAES(responseJson);
                    if (decrypted == null) return null;
                    // 提取 m3u8_url
                    Pattern p = Pattern.compile("\"m3u8_url\"\\s*:\\s*\"([^\"]+)\"");
                    Matcher m = p.matcher(decrypted);
                    if (m.find()) {
                        return m.group(1);
                    }
                }
            }
            return null;
        } catch (Exception e) {
            logger("获取播放地址失败: " + e.getMessage());
            return null;
        }
    }

    // 辅助：从 JSON 字符串提取值
    private String extractJsonValue(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(json);
        if (m.find()) return m.group(1);
        return "";
    }

    private String extractJsonArray(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]+)\\]");
        Matcher m = p.matcher(json);
        if (m.find()) {
            String content = m.group(1);
            Pattern namePattern = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]*)\"");
            Matcher nameMatcher = namePattern.matcher(content);
            List<String> names = new ArrayList<>();
            while (nameMatcher.find()) {
                names.add(nameMatcher.group(1));
            }
            return TextUtils.join(", ", names);
        }
        return "";
    }

    // ---------- 首页分类 ----------
    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        for (Map.Entry<String, String> entry : CATEGORY_MAP.entrySet()) {
            classes.add(new Class(entry.getValue(), entry.getKey()));
        }
        return Result.string(classes, new ArrayList<>());
    }

    // ---------- 分类列表 ----------
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            int page = Integer.parseInt(pg);
            String categoryName = null;
            for (Map.Entry<String, String> entry : CATEGORY_MAP.entrySet()) {
                if (entry.getValue().equals(tid)) {
                    categoryName = entry.getKey();
                    break;
                }
            }
            if (categoryName == null) {
                return Result.string(new ArrayList<>());
            }
            String url = HOST + "/category/" + tid + "/" + URLEncoder.encode(categoryName, "UTF-8") + "/page/" + page;
            logger("分类: " + url);
            String html = get(url);
            if (TextUtils.isEmpty(html)) return Result.string(new ArrayList<>());

            Document doc = Jsoup.parse(html);
            Elements items = doc.select(".item-wrap");
            List<Vod> list = new ArrayList<>();
            for (Element item : items) {
                Element link = item.selectFirst("a[href]");
                if (link == null) continue;
                String href = link.attr("href");
                if (!href.startsWith("/movie/")) continue;
                String vodId = href.substring(7);
                String name = link.attr("title");
                Element img = item.selectFirst("img");
                String pic = "";
                if (img != null) {
                    pic = fixImageUrl(img.attr("src"));
                }
                Elements tags = item.select(".tag");
                StringBuilder remarks = new StringBuilder();
                for (Element tag : tags) {
                    if (remarks.length() > 0) remarks.append(" ");
                    remarks.append(tag.text());
                }
                Vod vod = new Vod();
                vod.setVodId(vodId);
                vod.setVodName(name);
                vod.setVodPic(pic);
                vod.setVodRemarks(remarks.toString());
                list.add(vod);
            }
            return Result.string(list);
        } catch (Exception e) {
            logger("分类异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    // ---------- 详情页 ----------
    @Override
    public String detailContent(List<String> ids) {
        try {
            String vodId = ids.get(0);
            String url = HOST + "/movie/" + vodId;
            logger("详情: " + url);
            String html = get(url);
            if (TextUtils.isEmpty(html)) return Result.string(new ArrayList<>());

            Document doc = Jsoup.parse(html);

            // 从 JSON-LD 提取信息
            String jsonLd = null;
            Elements scripts = doc.select("script[type=\"application/ld+json\"]");
            for (Element script : scripts) {
                String data = script.data();
                if (data.contains("\"@type\":\"VideoObject\"")) {
                    jsonLd = data;
                    break;
                }
            }

            Vod vod = new Vod();
            String year = "";

            if (jsonLd != null) {
                String name = extractJsonValue(jsonLd, "name");
                String description = extractJsonValue(jsonLd, "description");
                String thumbnail = extractJsonValue(jsonLd, "thumbnailUrl");
                if (!TextUtils.isEmpty(thumbnail)) {
                    thumbnail = thumbnail.replaceAll("[\\[\\]\"]", "");
                    String[] thumbs = thumbnail.split(",");
                    if (thumbs.length > 0) thumbnail = thumbs[0].trim();
                }
                String uploadDate = extractJsonValue(jsonLd, "uploadDate");
                String genre = extractJsonValue(jsonLd, "genre");
                String actors = extractJsonArray(jsonLd, "actor");
                String directors = extractJsonArray(jsonLd, "director");

                if (!TextUtils.isEmpty(uploadDate) && uploadDate.length() >= 4) {
                    year = uploadDate.substring(0, 4);
                }

                vod.setVodName(name);
                vod.setVodPic(fixImageUrl(thumbnail));
                vod.setVodContent(description);
                vod.setVodYear(year);
                vod.setVodArea(genre);
                vod.setVodActor(actors);
                vod.setVodDirector(directors);
                vod.setVodRemarks(year);
            }

            // 提取集数列表 links
            String pageScript = doc.select("script").html();
            List<Map<String, String>> linksList = new ArrayList<>();

            Pattern linksPattern = Pattern.compile("\"links\"\\s*:\\s*\\[([^\\]]+)\\]");
            Matcher linksMatcher = linksPattern.matcher(pageScript);
            if (linksMatcher.find()) {
                String linksJson = "[" + linksMatcher.group(1) + "]";
                Pattern itemPattern = Pattern.compile("\\{[^}]*\\}");
                Matcher itemMatcher = itemPattern.matcher(linksJson);
                while (itemMatcher.find()) {
                    String item = itemMatcher.group();
                    String id = extractJsonValue(item, "id");
                    String name = extractJsonValue(item, "name");
                    if (!TextUtils.isEmpty(id) && !TextUtils.isEmpty(name)) {
                        Map<String, String> map = new HashMap<>();
                        map.put("id", id);
                        map.put("name", name);
                        linksList.add(map);
                    }
                }
            }

            if (linksList.isEmpty()) {
                Elements playLinks = doc.select(".paly_list_btn a");
                for (Element a : playLinks) {
                    String href = a.attr("href");
                    String text = a.text();
                    String linkId = null;
                    if (href.contains("link_id=")) {
                        linkId = href.substring(href.indexOf("link_id=") + 8);
                        if (linkId.contains("&")) linkId = linkId.substring(0, linkId.indexOf("&"));
                    }
                    if (!TextUtils.isEmpty(linkId)) {
                        Map<String, String> map = new HashMap<>();
                        map.put("id", linkId);
                        map.put("name", text);
                        linksList.add(map);
                    }
                }
            }

            // 记录第一个 link_id
            if (!linksList.isEmpty()) {
                firstLinkId = linksList.get(0).get("id");
            }

            // 提取 play_links (线路)
            List<Map<String, String>> playLinksList = new ArrayList<>();
            Pattern playLinksPattern = Pattern.compile("\"play_links\"\\s*:\\s*\\[([^\\]]+)\\]");
            Matcher playLinksMatcher = playLinksPattern.matcher(pageScript);
            if (playLinksMatcher.find()) {
                String playLinksJson = "[" + playLinksMatcher.group(1) + "]";
                Pattern itemPattern = Pattern.compile("\\{[^}]*\\}");
                Matcher itemMatcher = itemPattern.matcher(playLinksJson);
                while (itemMatcher.find()) {
                    String item = itemMatcher.group();
                    String id = extractJsonValue(item, "id");
                    String m3u8_url = extractJsonValue(item, "m3u8_url");
                    String name = extractJsonValue(item, "name");
                    if (!TextUtils.isEmpty(id) && !TextUtils.isEmpty(m3u8_url)) {
                        Map<String, String> map = new HashMap<>();
                        map.put("id", id);
                        map.put("m3u8_url", m3u8_url);
                        map.put("name", name);
                        playLinksList.add(map);
                    }
                }
            }

            // 记录第一个线路的 m3u8_url
            if (!playLinksList.isEmpty()) {
                firstM3u8Url = playLinksList.get(0).get("m3u8_url");
            }

            // 构建播放地址
            if (!playLinksList.isEmpty() && !linksList.isEmpty()) {
                StringBuilder playFrom = new StringBuilder();
                StringBuilder playUrl = new StringBuilder();
                for (Map<String, String> line : playLinksList) {
                    String lineName = line.get("name");
                    if (TextUtils.isEmpty(lineName)) lineName = "线路" + (playLinksList.indexOf(line) + 1);
                    if (playFrom.length() > 0) playFrom.append("$$$");
                    playFrom.append(lineName);

                    StringBuilder lineUrls = new StringBuilder();
                    for (Map<String, String> link : linksList) {
                        if (lineUrls.length() > 0) lineUrls.append("#");
                        String episodeName = link.get("name");
                        String linkId = link.get("id");
                        // 格式：集数名$vodId|linkId
                        lineUrls.append(episodeName).append("$").append(vodId).append("|").append(linkId);
                    }
                    if (playUrl.length() > 0) playUrl.append("$$$");
                    playUrl.append(lineUrls.toString());
                }
                vod.setVodPlayFrom(playFrom.toString());
                vod.setVodPlayUrl(playUrl.toString());
            }

            // 如果没有提取到播放信息，尝试使用 embedUrl 作为单集
            if (TextUtils.isEmpty(vod.getVodPlayUrl())) {
                String embedUrl = extractJsonValue(jsonLd, "embedUrl");
                if (!TextUtils.isEmpty(embedUrl)) {
                    vod.setVodPlayFrom("线路1");
                    vod.setVodPlayUrl("1$" + vodId + "|" + embedUrl);
                }
            }

            return Result.string(vod);
        } catch (Exception e) {
            logger("详情异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    // ---------- 播放 ----------
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            // id 格式为 "vodId|linkId"
            String[] parts = id.split("\\|");
            if (parts.length == 2) {
                String vodId = parts[0];
                String linkId = parts[1];

                // 第一集特殊处理：使用详情页中保存的线路地址
                if (linkId.equals(firstLinkId) && !TextUtils.isEmpty(firstM3u8Url)) {
                    return Result.get().url(firstM3u8Url).string();
                }

                // 其他集数调用 API
                String playUrl = fetchPlayUrlByLinkId(vodId, linkId);
                if (!TextUtils.isEmpty(playUrl)) {
                    return Result.get().url(playUrl).string();
                }
            }

            // 若 id 本身就是 m3u8 链接（兼容）
            if (id.startsWith("http")) {
                return Result.get().url(id).string();
            }

            return Result.get().url("").string();
        } catch (Exception e) {
            logger("播放异常: " + e.getMessage());
            return Result.get().url("").string();
        }
    }

    // ---------- 搜索 ----------
    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) {
            return Result.string(new ArrayList<>());
        }
        try {
            String url = HOST + "/channel?keywords=" + URLEncoder.encode(key, "UTF-8");
            logger("搜索: " + url);
            String html = get(url);
            if (TextUtils.isEmpty(html)) return Result.string(new ArrayList<>());

            Document doc = Jsoup.parse(html);
            Elements items = doc.select(".item-wrap");
            List<Vod> list = new ArrayList<>();
            for (Element item : items) {
                Element link = item.selectFirst("a[href]");
                if (link == null) continue;
                String href = link.attr("href");
                if (!href.startsWith("/movie/")) continue;
                String vodId = href.substring(7);
                String name = link.attr("title");
                Element img = item.selectFirst("img");
                String pic = "";
                if (img != null) {
                    pic = fixImageUrl(img.attr("src"));
                }
                Vod vod = new Vod();
                vod.setVodId(vodId);
                vod.setVodName(name);
                vod.setVodPic(pic);
                list.add(vod);
            }
            return Result.string(list);
        } catch (Exception e) {
            logger("搜索异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }
}
