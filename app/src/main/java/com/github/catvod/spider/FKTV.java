package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Misc;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.json.JSONArray;
import org.json.JSONObject;

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

import okhttp3.Response;

/**
 * 凡客影视 (fktv.me)
 * 由 Python 版 spider 按项目 Java 规范翻译而来。
 *
 * ⚠️ 以下几处依赖你们实际 base 工程中的 API，编译前请对照确认（这也是历史上
 * "Result API 方法名 / OkHttp 导入" 反复出错的地方）：
 *   1) com.github.catvod.bean.Result / Vod / Class 的具体 setter 名称
 *      （不同 fork 可能是 setTypeId / setType_id，或用构造器传参）；
 *   2) OkHttp 工具类里 GET/POST 的具体方法签名（这里假设有
 *      OkHttp.string(url, headers) 和 OkHttp.newCall(url, headers, body)，
 *      如果你们的封装叫法不同，只需替换这两处调用）；
 *   3) Misc.encode 是否存在于你们的 utils 包里，没有的话用
 *      java.net.URLEncoder.encode(str, "UTF-8") 替代即可。
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
        classes.add(buildClass("5_tag_296", "国产剧"));
        classes.add(buildClass("5", "连续剧"));
        classes.add(buildClass("6", "电影"));
        classes.add(buildClass("4", "综艺"));
        classes.add(buildClass("9", "短剧"));

        Result result = new Result();
        result.setClasses(classes);
        return result.string();
    }

    private Class buildClass(String id, String name) {
        Class c = new Class();
        c.setTypeId(id);
        c.setTypeName(name);
        return c;
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
            String encodedName = Misc.encode(typeName);
            url = HOST + "/category/" + baseTid + "/" + encodedName + "/tag/" + tagId + "/" + tagId + "/position/tv/page/" + pg;
        } else {
            String typeName = typeNameMap.containsKey(tid) ? typeNameMap.get(tid) : "";
            String encodedName = Misc.encode(typeName);
            url = HOST + "/category/" + tid + "/" + encodedName + "/page/" + pg;
        }

        String htmlText;
        try {
            htmlText = OkHttp.string(url, header);
        } catch (Exception e) {
            Result result = new Result();
            result.setList(new ArrayList<Vod>());
            result.setPage(Integer.parseInt(pg));
            result.setPagecount(0);
            result.setLimit(0);
            result.setTotal(0);
            return result.string();
        }

        List<Vod> videos = parseItemsFromHtml(htmlText, tid);

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

        Result result = new Result();
        result.setList(videos);
        result.setPage(Integer.parseInt(pg));
        result.setPagecount(totalPages);
        result.setLimit(videos.size());
        result.setTotal(videos.size() * 10);
        return result.string();
    }

    /** categoryContent 与 searchContent 共用的列表解析逻辑（对应 Python 里重复的那段 item 提取代码） */
    private List<Vod> parseItemsFromHtml(String htmlText, String tid) {
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
                    if (tid != null) vod.setTypeId(tid);
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

    /** 兼容部分壳子(如 FongMi)会额外传 pg 参数的调用方式 */
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        if (TextUtils.isEmpty(key)) {
            return emptySearchResult(pg);
        }

        String url = HOST + "/channel?keywords=" + Misc.encode(key);
        String htmlText;
        try {
            htmlText = OkHttp.string(url, header);
        } catch (Exception e) {
            return emptySearchResult(pg);
        }

        List<Vod> videos = parseItemsFromHtml(htmlText, null);
        if (quick && videos.size() > 10) {
            videos = new ArrayList<>(videos.subList(0, 10));
        }

        Result result = new Result();
        result.setList(videos);
        result.setPage(safeParseInt(pg, 1));
        result.setPagecount(1);
        result.setTotal(videos.size());
        return result.string();
    }

    private String emptySearchResult(String pg) {
        Result result = new Result();
        result.setList(new ArrayList<Vod>());
        result.setPage(safeParseInt(pg, 1));
        result.setPagecount(1);
        result.setTotal(0);
        return result.string();
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

        String htmlText;
        try {
            htmlText = OkHttp.string(url, header);
        } catch (Exception e) {
            Result result = new Result();
            result.setList(new ArrayList<Vod>());
            return result.string();
        }

        String vodName = "";
        String vodPic = "";
        String embedUrl = "";
        String typeName = "未知";
        String typeId = "";
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
                        typeId = cateMap.containsKey(typeName) ? cateMap.get(typeName) : "";
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
        vod.setTypeId(typeId);
        vod.setVodRemarks(linksData != null ? ("共 " + linksData.length() + " 集") : "");
        vod.setVodContent(vodContent);
        vod.setVodPlayFrom(vodPlayFrom);
        vod.setVodPlayUrl(vodPlayUrl);
        vod.setVodDirector(vodDirector);
        vod.setVodActor(vodActor);
        vod.setVodArea(vodArea);
        vod.setVodYear(vodYear);

        List<Vod> list = new ArrayList<>();
        list.add(vod);

        Result result = new Result();
        result.setList(list);
        return result.string();
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
            apiHeaders.put("authority", "fktv.me");
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
            apiHeaders.put("content-type", "application/octet-stream");
            apiHeaders.put("version", "1.0");
            apiHeaders.put("accept", "*/*");
            apiHeaders.put("origin", "https://fktv.me");
            apiHeaders.put("sec-fetch-site", "same-origin");
            apiHeaders.put("sec-fetch-mode", "cors");
            apiHeaders.put("sec-fetch-dest", "empty");
            apiHeaders.put("referer", "https://fktv.me/movie/" + movieId + "/mianpintu");
            apiHeaders.put("accept-encoding", "gzip, deflate, br, zstd");
            apiHeaders.put("accept-language", "zh-CN,zh;q=0.9");
            apiHeaders.put("cookie", "_did=ffFrmAfy2sx5C6mSrTwX08bpi2YWn48t");

            String apiUrl = "https://fktv.me/ysapi/movie/detail";
            Response res = OkHttp.newCall(apiUrl, apiHeaders, encryptedBody);
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

    private String okPlay(String url) throws Exception {
        Result result = new Result();
        result.setParse(0);
        result.setUrl(url);
        result.setHeader(new HashMap<String, String>());
        return result.string();
    }

    private String failPlay() throws Exception {
        Result result = new Result();
        result.setParse(0);
        result.setUrl("");
        result.setHeader(new HashMap<String, String>());
        return result.string();
    }
}
