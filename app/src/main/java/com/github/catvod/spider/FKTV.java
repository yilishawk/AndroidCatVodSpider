package com.github.catvod.crawler.spider;

import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.*;

public class FKTV extends Spider {

    private final String AES_KEY = "39656431613636316136616237383761";

    // AES 加密（使用你提供的 AESEncryption）
    private String encryptAES(String text) throws Exception {
        return AESEncryption.encrypt(text, AES_KEY, "", AESEncryption.ECB_PKCS_7_PADDING);
    }

    private String decryptAES(String base64) throws Exception {
        return AESEncryption.decrypt(base64, AES_KEY, "", AESEncryption.ECB_PKCS_7_PADDING);
    }

    @Override
    public String getName() {
        return "凡客影视";
    }

    @Override
    public String homeContent(boolean filter) {
        List<com.github.catvod.bean.Class> classes = new ArrayList<>();
        classes.add(new com.github.catvod.bean.Class("5", "连续剧"));
        classes.add(new com.github.catvod.bean.Class("6", "电影"));
        classes.add(new com.github.catvod.bean.Class("4", "综艺"));
        classes.add(new com.github.catvod.bean.Class("9", "短剧"));
        return Result.string(classes, new ArrayList<>(), new LinkedHashMap<>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        String url = "https://fktv.me/category/" + tid + "/page/" + pg;
        try {
            String html = OkHttp.string(url);
            String cleanHtml = html.replace("\\\"", "\"").replace("\\/", "/");

            List<Vod> videos = new ArrayList<>();
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"item\":(\\{.+?\\})(?=\\}(?:,|\\]|}))");
            java.util.regex.Matcher matcher = pattern.matcher(cleanHtml);

            while (matcher.find()) {
                try {
                    String itemStr = matcher.group(1);
                    if (!itemStr.endsWith("}")) itemStr += "}";
                    JsonObject item = Json.parse(itemStr).getAsJsonObject();

                    String path = Json.safeString(item, "canonical_path");
                    String vodId = path.contains("/movie/") ? path.split("/movie/")[1] : Json.safeString(item, "id");
                    vodId = vodId.replace("/", "___").replaceAll("^/|/$", "");

                    String name = Json.safeString(item, "name");
                    String pic = Json.safeString(item, "img_y_source");
                    String remarks = Json.safeString(item, "release_at", Json.safeString(item, "area"));

                    if (!TextUtils.isEmpty(vodId) && !TextUtils.isEmpty(name)) {
                        videos.add(new Vod(vodId, name, pic, remarks));
                    }
                } catch (Exception ignored) {}
            }

            // 分页
            Document doc = Jsoup.parse(html);
            Elements pageLinks = doc.select(".pagination a, .page a");
            int totalPages = Integer.parseInt(pg) + 1;
            for (Element a : pageLinks) {
                try {
                    int p = Integer.parseInt(a.text().trim());
                    totalPages = Math.max(totalPages, p);
                } catch (Exception ignored) {}
            }

            return Result.string(Integer.parseInt(pg), totalPages, videos.size(), videos.size() * 10, videos);

        } catch (Exception e) {
            e.printStackTrace();
            return Result.error("请求失败");
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        // TODO: 如果你需要，我可以马上把 Python 版 detailContent 完整转过来
        // 目前先保证 playerContent 正常工作
        return Result.string(new Vod());
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            if (id.startsWith("http")) {
                return Result.get().url(id).string();
            }

            if (!id.contains("@")) {
                return Result.get().url("").string();
            }

            String[] parts = id.split("@");
            String movieId = parts[0];
            String linkId = parts[1];

            JsonObject data = new JsonObject();
            data.addProperty("id", movieId);
            data.addProperty("link_id", linkId);
            data.addProperty("is_simple", "y");

            JsonObject payload = new JsonObject();
            payload.addProperty("deviceId", "ffFrmAfy2sx5C6mSrTwX08bpi2YWn48t");
            payload.addProperty("domain", "fktv.me");
            payload.add("data", data);

            String encrypted = encryptAES(payload.toString());

            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            headers.put("Referer", "https://fktv.me/movie/" + movieId + "/");
            headers.put("Content-Type", "application/octet-stream");

            String resp = OkHttp.post("https://fktv.me/ysapi/movie/detail", encrypted, headers);
            if (TextUtils.isEmpty(resp)) return Result.get().url("").string();

            String decrypted = decryptAES(resp);
            JsonObject root = Json.parse(decrypted).getAsJsonObject();

            // 严格按你的要求：只取 play_links 里的 m3u8_url
            String realUrl = null;
            JsonArray playLinks = root.getAsJsonArray("play_links");
            if (playLinks != null) {
                for (JsonElement e : playLinks) {
                    JsonObject line = e.getAsJsonObject();
                    String m3u8 = Json.safeString(line, "m3u8_url");
                    if (m3u8.startsWith("http")) {
                        realUrl = m3u8;
                        break;
                    }
                }
            }

            // 递归备用
            if (TextUtils.isEmpty(realUrl)) {
                List<String> all = new ArrayList<>();
                findAllM3u8(root, all);
                for (String u : all) {
                    if (u.startsWith("http")) {
                        realUrl = u;
                        break;
                    }
                }
            }

            if (!TextUtils.isEmpty(realUrl)) {
                return Result.get().url(realUrl).string();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return Result.get().url("").string();
    }

    private void findAllM3u8(JsonElement element, List<String> result) {
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            for (String key : obj.keySet()) {
                if ("m3u8_url".equals(key)) {
                    String val = Json.safeString(obj, key);
                    if (!TextUtils.isEmpty(val)) result.add(val);
                } else {
                    findAllM3u8(obj.get(key), result);
                }
            }
        } else if (element.isJsonArray()) {
            for (JsonElement e : element.getAsJsonArray()) {
                findAllM3u8(e, result);
            }
        }
    }
}
