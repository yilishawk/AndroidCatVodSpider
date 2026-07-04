package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 凡客影视 (fktv.me)
 * 由 Python 版 spider 按项目 Java 规范翻译，Result/Vod/Class/OkHttp 的调用方式已对照
 * 实际上传的 Result.java / Vod.java / Class.java / OkHttp.java 源码核对过，说明如下：
 *
 *   - Result 没有传统 setter，只能用链式 builder：Result.get().vod(list).page(...).string()；
 *     .page(page, count, limit, total) 里任何一个参数传 0 都会被规范化成 Integer.MAX_VALUE
 *     （库自身的"无限/未知"语义，不是 bug）。
 *   - Vod 用传统 setXxx()，但**没有 type_id 字段**，只有 setTypeName()，所以列表/详情里
 *     都不再设置 type_id。
 *   - Class 完全没有 setter，只能用构造函数 new Class(typeId, typeName)。
 *   - OkHttp 没有 newCall(url, headers, body) 这个三参数重载，POST 自定义 Content-Type
 *     的原始字节流必须自己拼 okhttp3.Request 再调用 OkHttp.newCall(Request)。
 *   - OkHttp.string() 内部吞掉了 IOException，失败时返回空字符串而不是抛异常，因此用
 *     TextUtils.isEmpty() 判断请求是否成功，而不是依赖 try/catch。
 */
public class FKTV extends Spider {

    private static final String HOST = "https://fktv.me";
    private static final byte[] AES_KEY = hexToBytes("39656431613636316136616237383761");

    private Map<String, String> header;
    private Map<String, String> cateMap;
    private Map<String, String> typeNameMap;

    @Override
    public void init(Context context, String extend) throws Exception {
        header = new HashMap<>();
        header.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36");
        header.put("Referer", HOST + "/");
        header.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
        header.put("Accept-Language", "zh-CN,zh;q=0.9");
        header.put("Accept-Encoding", "gzip, deflate");

        cateMap = new HashMap<>();
        cateMap.put("连续剧", "5");
        cateMap.put("电影", "6");
        cateMap.put("综艺", "4");
        cateMap.put("短剧", "9");

        typeNameMap = new HashMap<>();
        typeNameMap.put("5", "连续剧");
        typeNameMap.put("6", "电影");
        typeNameMap.put("4", "综艺");
        typeNameMap.put("9", "短剧");
    }

    public String getName() {
        return "凡客影视";
    }

    // ========================= 工具方法 =========================

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    /** 对应 Python 的 find_key_json：在嵌套 JSON 中递归查找第一个匹配的 key */
    private Object findKeyJson(Object obj, String key) {
        if (obj instanceof JSONObject) {
            JSONObject jo = (JSONObject) obj;
            if (jo.has(key)) return jo.opt(key);
            Iterator<String> keys = jo.keys();
            while (keys.hasNext()) {
                Object res = findKeyJson(jo.opt(keys.next()), key);
                if (res != null) return res;
            }
        } else if (obj instanceof JSONArray) {
            JSONArray arr = (JSONArray) obj;
            for (int i = 0; i < arr.length(); i++) {
                Object res = findKeyJson(arr.opt(i), key);
                if (res != null) return res;
            }
        }
        return null;
    }

    /** 对应 Python 的 _find_all_m3u8_url：递归收集所有键为 m3u8_url 的值 */
    private void findAllM3u8Url(Object obj, List<String> result) {
        if (obj instanceof JSONObject) {
            JSONObject jo = (JSONObject) obj;
            Iterator<String> keys = jo.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object v = jo.opt(k);
                if ("m3u8_url".equals(k)) {
                    if (v != null) result.add(String.valueOf(v));
                } else {
                    findAllM3u8Url(v, result);
                }
            }
        } else if (obj instanceof JSONArray) {
            JSONArray arr = (JSONArray) obj;
            for (int i = 0; i < arr.length(); i++) {
                findAllM3u8Url(arr.opt(i), result);
            }
        }
    }

    private String encryptAesEcb(String text, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] encrypted = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private String decryptAesEcb(String base64Str, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] decrypted = cipher.doFinal(Base64.decode(base64Str, Base64.NO_WRAP));
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    private String joinNames(Object obj) {
        if (obj instanceof JSONArray) {
            JSONArray arr = (JSONArray) obj;
            List<String> names = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                if (item instanceof JSONObject) {
                    names.add(((JSONObject) item).optString("name", item.toString()));
                } else if (item != null) {
                    names.add(String.valueOf(item));
                }
            }
            return TextUtils.join(",", names);
        } else if (obj instanceof String) {
            return (String) obj;
        }
        return "";
    }

    private String trimSlashes(String s) {
        if (s == null) return "";
        int start = 0, end = s.length();
        while (start < end && s.charAt(start) == '/') start++;
        while (end > start && s.charAt(end - 1) == '/') end--;
        return s.substring(start, end);
    }

    // ========================= 首页 =========================

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("5_tag_296", "国产剧"));
        classes.add(new Class("5", "连续剧"));
        classes.add(new Class("6", "电影"));
        classes.add(new Class("4", "综艺"));
        classes.add(new Class("9", "短剧"));

        return Result.get().classes(classes).string();
    }

    // ========================= 分类列表 =========================

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String url;
        if (tid.contains("_tag_")) {
            String[] parts = tid.split("_tag_");
            String baseTid = parts[0];
            String tagId = parts[1];
            String typeName = typeNameMap.containsKey(baseTid) ? typeNameMap.get(baseTid) : "";
            url = HOST + "/category/" + baseTid + "/" + urlEncode(typeName) + "/tag/" + tagId + "/" + tagId + "/position/tv/page/" + pg;
        } else {
            String typeName = typeNameMap.containsKey(tid) ? typeNameMap.get(tid) : "";
            url = HOST + "/category/" + tid + "/" + urlEncode(typeName) + "/page/" + pg;
        }

        String htmlText = OkHttp.string(url, header);
        if (TextUtils.isEmpty(htmlText)) {
            return Result.get().vod(new ArrayList<Vod>())
                    .page(Integer.parseInt(pg), 0, 0, 0)
                    .string();
        }

        List<Vod> videos = parseItemsFromHtml(htmlText);

        int totalPages = Integer.parseInt(pg) + 1;
        try {
            Document doc = Jsoup.parse(htmlText);
            Elements pageLinks = doc.select(".pagination a");
            if (pageLinks.isEmpty()) pageLinks = doc.select(".page a");
            int max = totalPages;
            for (Element a : pageLinks) {
                String t = a.text().trim();
                if (t.matches("\\d+")) {
                    int v = Integer.parseInt(t);
                    if (v > max) max = v;
                }
            }
            totalPages = max;
        } catch (Exception ignore) {
        }

        return Result.get().vod(videos)
                .page(Integer.parseInt(pg), totalPages, videos.size(), videos.size() * 10)
                .string();
    }

    /** categoryContent 与 searchContent 共用的列表解析逻辑（对应 Python 里重复的那段 item 提取代码） */
    private List<Vod> parseItemsFromHtml(String htmlText) {
        List<Vod> videos = new ArrayList<>();
        String cleanHtml = htmlText.replace("\\\"", "\"").replace("\\/", "/");

        Pattern itemPattern = Pattern.compile("\"item\":(\\{.+?\\})(?=\\}(?:,|\\]|\\}))");
        Matcher m = itemPattern.matcher(cleanHtml);

        while (m.find()) {
            String itemStr = m.group(1);
            if (!itemStr.endsWith("}")) itemStr = itemStr + "}";
            try {
                JSONObject itemObj = new JSONObject(itemStr);
                String path = itemObj.optString("canonical_path", "");
                String vodId;
                if (path.contains("/movie/")) {
                    vodId = path.substring(path.lastIndexOf("/movie/") + "/movie/".length());
                } else {
                    vodId = itemObj.optString("id", "");
                }
                vodId = trimSlashes(vodId).replace("/", "___");
                String vodName = itemObj.optString("name", "");
                String vodPic = itemObj.optString("img_y_source", "");
                String vodRemarks = itemObj.optString("release_at", "");
                if (TextUtils.isEmpty(vodRemarks)) vodRemarks = itemObj.optString("area", "");

                if (!TextUtils.isEmpty(vodId) && !TextUtils.isEmpty(vodName)) {
                    Vod vod = new Vod();
                    vod.setVodId(vodId);
                    vod.setVodName(vodName);
                    vod.setVodPic(vodPic);
                    vod.setVodRemarks(vodRemarks);
                    videos.add(vod);
                }
            } catch (Exception ignore) {
            }
        }
        return videos;
    }

    // ========================= 搜索 =========================

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    /** 重载：兼容部分壳子(如 FongMi)会额外传 pg 参数的调用方式 */
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        int pageNum = safeParseInt(pg, 1);

        if (TextUtils.isEmpty(key)) {
            return Result.get().vod(new ArrayList<Vod>()).page(pageNum, 1, 0, 0).string();
        }

        String url = HOST + "/channel?keywords=" + urlEncode(key);
        String htmlText = OkHttp.string(url, header);
        if (TextUtils.isEmpty(htmlText)) {
            return Result.get().vod(new ArrayList<Vod>()).page(pageNum, 1, 0, 0).string();
        }

        List<Vod> videos = parseItemsFromHtml(htmlText);
        if (quick && videos.size() > 10) {
            videos = new ArrayList<>(videos.subList(0, 10));
        }

        return Result.get().vod(videos).page(pageNum, 1, videos.size(), videos.size()).string();
    }

    private int safeParseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    // ========================= 详情 =========================

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String vodId = ids.get(0);
        String rawPath = vodId.replace("___", "/");
        String url = HOST + "/movie/" + rawPath;

        String htmlText = OkHttp.string(url, header);
        if (TextUtils.isEmpty(htmlText)) {
            return Result.get().vod(new ArrayList<Vod>()).string();
        }

        String vodName = "";
        String vodPic = "";
        String embedUrl = "";
        String typeName = "未知";
        String vodDirector = "";
        String vodActor = "";
        String vodArea = "";
        String vodYear = "";
        String vodContent = "";

        Document soup = Jsoup.parse(htmlText);

        // 解析 <script type="application/ld+json"> 里的 VideoObject
        try {
            Elements scripts = soup.select("script[type=application/ld+json]");
            for (Element script : scripts) {
                try {
                    JSONObject data = new JSONObject(script.data());
                    if ("VideoObject".equals(data.optString("@type"))) {
                        vodName = data.optString("name", "");
                        typeName = data.optString("genre", "未知");
                        embedUrl = data.optString("embedUrl", "");

                        JSONArray thumbs = data.optJSONArray("thumbnailUrl");
                        if (thumbs != null && thumbs.length() > 0) vodPic = thumbs.optString(0, "");

                        vodDirector = joinNames(data.opt("director"));
                        vodActor = joinNames(data.opt("actor"));

                        String pubDate = data.optString("datePublished", "");
                        if (!TextUtils.isEmpty(pubDate) && pubDate.contains("-")) {
                            vodYear = pubDate.split("-")[0];
                        }
                        vodContent = data.optString("description", "");
                    }
                } catch (Exception ignore) {
                }
            }
        } catch (Exception ignore) {
        }

        String cleanDetailHtml = htmlText.replace("\\\"", "\"").replace("\\/", "/");
        JSONArray linksData = null;
        JSONArray playLinesData = null;

        Matcher linksMatcher = Pattern.compile("\"links\":(\\[.+?\\])(?=\\s*(?:,|\\}))").matcher(cleanDetailHtml);
        if (linksMatcher.find()) {
            try {
                linksData = new JSONArray(linksMatcher.group(1));
            } catch (Exception ignore) {
            }
        }

        Matcher linesMatcher = Pattern.compile("\"play_links\":(\\[.+?\\])(?=\\s*(?:,|\\}))").matcher(cleanDetailHtml);
        if (linesMatcher.find()) {
            try {
                playLinesData = new JSONArray(linesMatcher.group(1));
            } catch (Exception ignore) {
            }
        }

        // __NEXT_DATA__ 兜底
        JSONObject rootData = null;
        Matcher nextDataMatcher = Pattern.compile(
                "<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", Pattern.DOTALL
        ).matcher(htmlText);
        if (nextDataMatcher.find()) {
            try {
                rootData = new JSONObject(nextDataMatcher.group(1));
            } catch (Exception ignore) {
            }
        }

        if (rootData != null) {
            if (linksData == null) {
                Object o = findKeyJson(rootData, "links");
                if (o instanceof JSONArray) linksData = (JSONArray) o;
            }
            if (playLinesData == null) {
                Object o = findKeyJson(rootData, "play_links");
                if (o instanceof JSONArray) playLinesData = (JSONArray) o;
            }
            if (TextUtils.isEmpty(vodName)) {
                Object o = findKeyJson(rootData, "name");
                if (o != null) vodName = String.valueOf(o);
            }
            if (TextUtils.isEmpty(vodPic)) {
                Object o = findKeyJson(rootData, "img_y_source");
                if (o == null) o = findKeyJson(rootData, "img_y");
                if (o != null) vodPic = String.valueOf(o);
            }
            if (TextUtils.isEmpty(vodDirector)) {
                vodDirector = joinNames(findKeyJson(rootData, "director"));
            }
            if (TextUtils.isEmpty(vodActor)) {
                vodActor = joinNames(findKeyJson(rootData, "actor"));
            }
            if (TextUtils.isEmpty(vodArea)) {
                Object o = findKeyJson(rootData, "area");
                if (o != null) vodArea = String.valueOf(o);
            }
            if (TextUtils.isEmpty(vodYear)) {
                Object o = findKeyJson(rootData, "year");
                if (o == null) o = findKeyJson(rootData, "release_at");
                if (o != null) {
                    String y = String.valueOf(o);
                    vodYear = y.length() > 4 ? y.substring(0, 4) : y;
                }
            }
            if (TextUtils.isEmpty(vodContent)) {
                Object o = findKeyJson(rootData, "description");
                if (o == null) o = findKeyJson(rootData, "intro");
                if (o != null) vodContent = String.valueOf(o);
            }
        }

        if (TextUtils.isEmpty(vodContent)) {
            Element descTag = soup.selectFirst(".vod_content");
            if (descTag == null) descTag = soup.selectFirst(".summary");
            if (descTag == null) descTag = soup.selectFirst("[class*=desc]");
            if (descTag != null) vodContent = descTag.text().trim();
        }

        if (TextUtils.isEmpty(vodName)) {
            Element titleTag = soup.selectFirst(".normal-title");
            if (titleTag == null) titleTag = soup.selectFirst("h1");
            vodName = titleTag != null ? titleTag.text().trim() : url;
        }

        if (TextUtils.isEmpty(vodPic)) {
            Element imgTag = soup.selectFirst(".normal-wrap img");
            if (imgTag == null) imgTag = soup.selectFirst(".relative img");
            vodPic = imgTag != null ? imgTag.attr("src") : "";
        }

        // ---------- 组装播放源 ----------
        List<String> playFromList = new ArrayList<>();
        List<String> playUrlList = new ArrayList<>();
        String rawMovieId = vodId.contains("___") ? vodId.substring(0, vodId.indexOf("___")) : vodId;

        if (playLinesData != null && playLinesData.length() > 0 && linksData != null && linksData.length() > 0) {
            for (int i = 0; i < playLinesData.length(); i++) {
                JSONObject line = playLinesData.optJSONObject(i);
                String lineName = line != null ? line.optString("name", "默认线路") : "默认线路";
                playFromList.add(lineName);

                List<String> episodeUrls = new ArrayList<>();
                for (int j = 0; j < linksData.length(); j++) {
                    JSONObject ep = linksData.optJSONObject(j);
                    String epName = ep != null ? ep.optString("name", "") : "";
                    Object epLinkId = ep != null ? ep.opt("id") : null;
                    episodeUrls.add(epName + "$" + rawMovieId + "@" + epLinkId);
                }
                playUrlList.add(TextUtils.join("#", episodeUrls));
            }
        } else {
            playFromList.add("默认线路");
            playUrlList.add(!TextUtils.isEmpty(embedUrl) ? "第一集$" + embedUrl : "");
        }

        String vodPlayFrom = TextUtils.join("$$$", playFromList);
        String vodPlayUrl = TextUtils.join("$$$", playUrlList);

        Vod vod = new Vod();
        vod.setVodId(vodId);
        vod.setVodName(vodName);
        vod.setVodPic(vodPic);
        vod.setTypeName(typeName);
        vod.setVodRemarks(linksData != null ? ("共 " + linksData.length() + " 集") : "");
        vod.setVodContent(vodContent);
        vod.setVodPlayFrom(vodPlayFrom);
        vod.setVodPlayUrl(vodPlayUrl);
        vod.setVodDirector(vodDirector);
        vod.setVodActor(vodActor);
        vod.setVodArea(vodArea);
        vod.setVodYear(vodYear);

        return Result.get().vod(vod).string();
    }

    // ========================= 播放解析 =========================

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (id.startsWith("http")) {
            return okPlay(id);
        }

        try {
            if (!id.contains("@")) {
                return failPlay();
            }

            String[] split = id.split("@", 2);
            String movieId = split[0];
            String linkId = split[1];

            JSONObject data = new JSONObject();
            data.put("id", movieId);
            data.put("link_id", linkId);
            data.put("is_simple", "y");

            String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36";

            JSONObject payload = new JSONObject();
            payload.put("deviceId", "ffFrmAfy2sx5C6mSrTwX08bpi2YWn48t");
            payload.put("token", "");
            payload.put("domain", "fktv.me");
            payload.put("referer", "");
            payload.put("user_agent", ua);
            payload.put("shareCode", "");
            payload.put("channel", "");
            payload.put("ip", "");
            payload.put("data", data);

            String jsonStr = payload.toString();
            String encryptedBody = encryptAesEcb(jsonStr, AES_KEY);

            Map<String, String> apiHeaders = new HashMap<>();
            apiHeaders.put("pragma", "no-cache");
            apiHeaders.put("cache-control", "no-cache");
            apiHeaders.put("ip", "");
            apiHeaders.put("sharecode", "");
            apiHeaders.put("sec-ch-ua-platform", "\"Windows\"");
            apiHeaders.put("sec-ch-ua", "\"Google Chrome\";v=\"149\", \"Chromium\";v=\"149\", \"Not)A;Brand\";v=\"24\"");
            apiHeaders.put("sec-ch-ua-mobile", "?0");
            apiHeaders.put("devicetype", "pc");
            apiHeaders.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()));
            apiHeaders.put("user-agent", ua);
            apiHeaders.put("channel", "");
            apiHeaders.put("version", "1.0");
            apiHeaders.put("accept", "*/*");
            apiHeaders.put("origin", "https://fktv.me");
            apiHeaders.put("sec-fetch-site", "same-origin");
            apiHeaders.put("sec-fetch-mode", "cors");
            apiHeaders.put("sec-fetch-dest", "empty");
            apiHeaders.put("referer", "https://fktv.me/movie/" + movieId + "/mianpintu");
            apiHeaders.put("accept-language", "zh-CN,zh;q=0.9");
            apiHeaders.put("cookie", "_did=ffFrmAfy2sx5C6mSrTwX08bpi2YWn48t");
            // 注意：authority / content-type 不放进 addHeader，交给 okhttp3 请求本身处理，
            // 避免和 OkHttpClient 自动生成的同名请求头冲突（见下方 RequestBody 的 MediaType）

            RequestBody body = RequestBody.create(MediaType.parse("application/octet-stream"), encryptedBody);
            Request.Builder builder = new Request.Builder().url("https://fktv.me/ysapi/movie/detail").post(body);
            builder.headers(Headers.of(apiHeaders));
            Request request = builder.build();

            Response res = OkHttp.newCall(request);
            if (res == null || !res.isSuccessful() || res.body() == null) {
                return failPlay();
            }

            String serverText = res.body().string().trim();
            if (serverText.startsWith("{") && serverText.contains("\"status\":\"n\"")) {
                return failPlay();
            }

            String decrypted;
            try {
                decrypted = decryptAesEcb(serverText, AES_KEY);
            } catch (Exception e) {
                return failPlay();
            }

            JSONObject resJson;
            try {
                resJson = new JSONObject(decrypted);
            } catch (Exception e) {
                return failPlay();
            }

            String realUrl = null;

            // 1. 优先从 play_links 匹配 flag
            JSONArray playLinks = resJson.optJSONArray("play_links");
            if (playLinks != null && playLinks.length() > 0) {
                JSONObject matchedLink = null;
                if (!TextUtils.isEmpty(flag)) {
                    for (int i = 0; i < playLinks.length(); i++) {
                        JSONObject link = playLinks.optJSONObject(i);
                        if (link == null) continue;
                        String linkIdStr = link.opt("id") == null ? null : String.valueOf(link.opt("id"));
                        if (flag.equals(link.optString("name")) || flag.equals(linkIdStr)) {
                            matchedLink = link;
                            break;
                        }
                    }
                }
                if (matchedLink != null) {
                    realUrl = matchedLink.optString("m3u8_url", null);
                } else {
                    JSONObject first = playLinks.optJSONObject(0);
                    realUrl = first != null ? first.optString("m3u8_url", null) : null;
                }
            }

            // 2. 降级：递归查找所有 m3u8_url
            if (TextUtils.isEmpty(realUrl)) {
                List<String> m3u8Urls = new ArrayList<>();
                findAllM3u8Url(resJson, m3u8Urls);
                for (String u : m3u8Urls) {
                    if (u != null && u.startsWith("http")) {
                        realUrl = u;
                        break;
                    }
                }
            }

            // 3. 最终降级（注意：与 Python 版一致，故意不 fallback 到 m3u8_url_source）
            if (TextUtils.isEmpty(realUrl)) {
                realUrl = resJson.optString("m3u8_url", resJson.optString("url", null));
            }

            if (!TextUtils.isEmpty(realUrl) && realUrl.startsWith("http")) {
                return okPlay(realUrl);
            }
            return failPlay();

        } catch (Exception e) {
            return failPlay();
        }
    }

    private String okPlay(String url) {
        return Result.get().url(url).string();
    }

    private String failPlay() {
        return Result.get().url("").string();
    }
}
