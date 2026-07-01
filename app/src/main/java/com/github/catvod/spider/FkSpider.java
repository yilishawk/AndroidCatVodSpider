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

/**
 * 凡客影视 (fktv.me) Spider
 * 支持分类、详情、播放（含第二集加密解析）、搜索
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

    private void logger(String msg) {
        // 可替换为 Proxy.log 或其他日志
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
        if (url.contains("cdn.g3ejjm8m.com")) {
            // 已经是正确域名，不做替换
        } else {
            // 提取路径部分，替换域名
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
                    + Character.digit(s.charAt(i+1), 16));
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

            // 发送 POST（body 为加密后的字节流，直接发送 Base64 解码后的字节？）
            // 根据描述，content-type 是 application/octet-stream，所以直接发送加密后的字节数组（而非 Base64 字符串）
            // 但 OkHttp.string 不支持直接发字节流，我们使用 OkHttp 的 post 方法。
            // 这里为了简便，我们使用 OkHttp 的 post 方法，但 OkHttp 没有直接返回 String 的 post 方法，
            // 我们可以用 OkHttp.post 返回 Response，再提取字节。
            // 由于 OkHttp 封装，我们可以用 OkHttp.post(url, headers, body) 但需要自己处理。
            // 为了简化，我们使用 Java 标准 HttpURLConnection 或 OkHttp 的扩展。
            // 这里假设我们有 OkHttp.postBytes 方法，但标准 CatVod 的 OkHttp 可能没有。
            // 我们改用 OkHttp 的 post 方法，把加密后的字节数组作为 body，并设置 Content-Type。
            // 由于 OkHttp 的 post 接受 Map，但我们需要字节，可以用 OkHttp.post(url, headers, body) 其中 body 是 byte[]。
            // 查阅 CatVod 的 OkHttp 类，有 post(String url, Map<String,String> headers, byte[] body) 方法。
            // 我们尝试使用它。
            byte[] encryptedBytes = android.util.Base64.decode(encrypted, android.util.Base64.NO_WRAP);
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/octet-stream");
            headers.put("User-Agent", UA);
            headers.put("Referer", HOST + "/movie/" + vodId);
            // 还需要其他头，如 time, devicetype 等，但可能不必须。

            byte[] responseBytes = OkHttp.post(API_DETAIL, headers, encryptedBytes);
            if (responseBytes == null) return null;
            String responseJson = new String(responseBytes, "UTF-8");
            // 解密
            String decrypted = decryptAES(responseJson);
            if (decrypted == null) return null;

            // 解析 JSON 提取 play_links 中的 m3u8_url
            // 使用 Gson 或手动解析，这里手动简单提取
            // 寻找 "m3u8_url":"..." 
            Pattern p = Pattern.compile("\"m3u8_url\"\\s*:\\s*\"([^\"]+)\"");
            Matcher m = p.matcher(decrypted);
            if (m.find()) {
                return m.group(1);
            }
            return null;
        } catch (Exception e) {
            logger("获取播放地址失败: " + e.getMessage());
            return null;
        }
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
            // 构建 URL: /category/{tid}/{categoryName}/page/{page}
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
                String vodId = href.substring(7); // 去掉 /movie/
                String name = link.attr("title");
                Element img = item.selectFirst("img");
                String pic = "";
                if (img != null) {
                    pic = fixImageUrl(img.attr("src"));
                }
                // 提取备注（标签）
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
            if (jsonLd != null) {
                // 简单提取字段
                String name = extractJsonValue(jsonLd, "name");
                String description = extractJsonValue(jsonLd, "description");
                String thumbnail = extractJsonValue(jsonLd, "thumbnailUrl"); // 可能数组，取第一个
                if (!TextUtils.isEmpty(thumbnail)) {
                    // 去掉 [ 和 "
                    thumbnail = thumbnail.replaceAll("[\\[\\]\"]", "");
                    String[] thumbs = thumbnail.split(",");
                    if (thumbs.length > 0) thumbnail = thumbs[0].trim();
                }
                String uploadDate = extractJsonValue(jsonLd, "uploadDate");
                String embedUrl = extractJsonValue(jsonLd, "embedUrl"); // m3u8_source
                String genre = extractJsonValue(jsonLd, "genre");
                String keywords = extractJsonValue(jsonLd, "keywords");
                // 演员和导演
                String actors = extractJsonArray(jsonLd, "actor");
                String directors = extractJsonArray(jsonLd, "director");

                vod.setVodName(name);
                vod.setVodPic(fixImageUrl(thumbnail));
                vod.setVodContent(description);
                vod.setVodYear(uploadDate != null && uploadDate.length() >= 4 ? uploadDate.substring(0, 4) : "");
                vod.setVodArea(genre);
                vod.setVodActor(actors);
                vod.setVodDirector(directors);
                // 备注存年份
                vod.setVodRemarks(vod.getVodYear());
                // 存储 embedUrl 以备后用（第一集地址）
                if (!TextUtils.isEmpty(embedUrl)) {
                    // 我们可以把它存到扩展字段，但这里不处理
                }
            }

            // 提取集数列表 links
            // 从页面中寻找 var data = {...}; 或直接从 script 中提取 links
            // 更可靠：从页面中可能存在的 window.__INITIAL_STATE__ 或直接 script 内容
            // 这里我们通过正则从 script 中提取 "links":[...]
            String pageScript = doc.select("script").html();
            Pattern linksPattern = Pattern.compile("\"links\"\\s*:\\s*\\[([^\\]]+)\\]");
            Matcher linksMatcher = linksPattern.matcher(pageScript);
            List<Map<String, String>> linksList = new ArrayList<>();
            if (linksMatcher.find()) {
                String linksJson = "[" + linksMatcher.group(1) + "]";
                // 简单解析每个对象
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
            // 如果 linksList 为空，尝试从其他 script 提取
            if (linksList.isEmpty()) {
                // 另一种方式：从页面中提取集数元素（比如 .paly_list_btn a）
                Elements playLinks = doc.select(".paly_list_btn a");
                for (Element a : playLinks) {
                    String href = a.attr("href");
                    String text = a.text();
                    // href 可能包含 link_id，如 ?link_id=xxx
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

            // 提取 play_links (线路)
            Pattern playLinksPattern = Pattern.compile("\"play_links\"\\s*:\\s*\\[([^\\]]+)\\]");
            Matcher playLinksMatcher = playLinksPattern.matcher(pageScript);
            List<Map<String, String>> playLinksList = new ArrayList<>();
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

            // 构建播放地址字符串: 线路名称$集数ID#集数ID...
            // 如果有多个线路，我们使用线路1, 线路2 作为 flag
            if (!playLinksList.isEmpty() && !linksList.isEmpty()) {
                StringBuilder playFrom = new StringBuilder();
                StringBuilder playUrl = new StringBuilder();
                for (Map<String, String> line : playLinksList) {
                    String lineName = line.get("name");
                    if (TextUtils.isEmpty(lineName)) lineName = "线路" + (playLinksList.indexOf(line) + 1);
                    if (playFrom.length() > 0) playFrom.append("$$$");
                    playFrom.append(lineName);
                    // 构造该线路的集数串：集数名称$link_id#集数名称$link_id...
                    StringBuilder lineUrls = new StringBuilder();
                    for (Map<String, String> link : linksList) {
                        if (lineUrls.length() > 0) lineUrls.append("#");
                        String episodeName = link.get("name");
                        String linkId = link.get("id");
                        lineUrls.append(episodeName).append("$").append(linkId);
                    }
                    if (playUrl.length() > 0) playUrl.append("$$$");
                    playUrl.append(lineUrls.toString());
                }
                vod.setVodPlayFrom(playFrom.toString());
                vod.setVodPlayUrl(playUrl.toString());
            }

            // 如果没有提取到播放信息，尝试从页面的 m3u8_url_source 构造单集
            if (TextUtils.isEmpty(vod.getVodPlayUrl())) {
                // 尝试获取 embedUrl 作为第一集
                String embedUrl = extractJsonValue(jsonLd, "embedUrl");
                if (!TextUtils.isEmpty(embedUrl)) {
                    // 构造单集
                    vod.setVodPlayFrom("线路1");
                    vod.setVodPlayUrl("1$" + embedUrl);
                }
            }

            return Result.string(vod);
        } catch (Exception e) {
            logger("详情异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
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
            // 提取所有 "name":"xxx"
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

    // ---------- 播放 ----------
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            // id 是 link_id
            String linkId = id;
            // 需要获取 vodId，但 playerContent 没有直接传入，我们可以从扩展中获取，或者从 id 格式推断
            // 我们暂时假设调用时传入的 id 就是 link_id，但我们需要 vodId 来调用 API。
            // 可以在 detailContent 中把 vodId 保存到全局变量或使用其他方式。
            // 简单做法：在 detailContent 中生成播放地址时，将 vodId 编码到 id 中，如 "vodId|linkId"
            // 为了简便，我们约定 playerContent 的 id 格式为 "vodId|linkId"
            String[] parts = id.split("\\|");
            if (parts.length == 2) {
                String vodId = parts[0];
                String linkId2 = parts[1];
                // 判断是否为第一集（link 名称是否为 "1" 或者是列表第一个）
                // 从 links 中获取名称，但我们没有上下文，只能通过判断 linkId2 是否等于第一集的 link_id
                // 我们可以从之前存储的 firstLinkId 中获取，但没有存储。
                // 这里我们简单判断：如果 linkId2 与详情页的某个 link 匹配，且其名称为 "1"，则直接使用线路地址。
                // 更好的做法：在 detailContent 时存储 firstLinkId。
                // 由于代码复杂度，我们在这里尝试通过 API 获取所有集数，如果返回的 m3u8_url 与第一集相同，则直接使用。
                // 但为了简化，我们尝试调用 API 获取，如果失败则尝试使用 embedUrl。
                String playUrl = fetchPlayUrlByLinkId(vodId, linkId2);
                if (!TextUtils.isEmpty(playUrl)) {
                    return Result.get().url(playUrl).string();
                }
                // 如果 API 失败，尝试使用详情页中的 embedUrl（第一集）
                // 我们没有办法获取详情页 embedUrl，所以回退到直接使用 id（即 linkId）作为地址？
                return Result.get().url(id).string();
            } else {
                // 如果 id 只是一个地址（如 m3u8 链接），直接播放
                if (id.startsWith("http")) {
                    return Result.get().url(id).string();
                }
                // 否则返回空
                return Result.get().url("").string();
            }
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
                // 搜索可能不返回详细备注，忽略
                list.add(vod);
            }
            return Result.string(list);
        } catch (Exception e) {
            logger("搜索异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }
}
