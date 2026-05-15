package com.github.catvod.spider;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URLEncoder;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DanmuHelper {

    private static final String[] DANMU_SOURCES = {
            "https://api.danmu.icu/?ac=dm&url={url}",
            "https://dmku.hls.one/?ac=dm&url={url}"
    };

    private static final String[] COLORS = {"16711680", "16776960", "65280", "255", "16711935", "65535", "16777215", "8388736", "16753920"};

    /**
     * 对应 JS 的 handleRequest 入口
     */
    public static String getDanmuXml(String title, int episodeNum) {
        try {
            // JS 逻辑: await searchVideoUrl(title, episodeNum)
            String videoUrl = searchVideoUrl(title, episodeNum);

            if (videoUrl == null || videoUrl.isEmpty()) {
                return generateEmptyDanmu(title, episodeNum);
            }

            // JS 逻辑: await fetchDanmu(videoUrl)
            String danmuXml = fetchDanmu(videoUrl);
            return (danmuXml != null && !danmuXml.isEmpty()) ? danmuXml : generateEmptyDanmu(title, episodeNum);

        } catch (Exception e) {
            SpiderDebug.log(e);
            return generateEmptyDanmu(title, episodeNum);
        }
    }

    /**
     * 对应 JS 的 searchVideoUrl
     */
    private static String searchVideoUrl(String title, int episodeNum) {
        // 方法1: 360kan
        String url = search360kan(title, episodeNum);
        if (url != null) return url;

        // 方法2: 金蝉 (JS 逻辑备选)
        url = searchJinchan(title, episodeNum);
        return url;
    }

    /**
     * 对应 JS 的 search360kan
     */
    private static String search360kan(String title, int episodeNum) {
        try {
            String apiUrl = "https://api.so.360kan.com/index?force_v=1&kw=" + URLEncoder.encode(title, "UTF-8") + "&tab=all";
            String res = OkHttp.string(apiUrl);
            JsonObject root = Json.safeObject(res);

            // 严格检查 data.longData.rows 路径
            if (root == null || !root.has("data") || root.get("data").isJsonNull()) return null;
            JsonObject data = root.getAsJsonObject("data");
            if (!data.has("longData") || data.get("longData").isJsonNull()) return null;
            JsonObject longData = data.getAsJsonObject("longData");
            if (!longData.has("rows") || longData.get("rows").isJsonNull()) return null;
            
            JsonArray rows = longData.getAsJsonArray("rows");

            for (JsonElement el : rows) {
                if (!el.isJsonObject()) continue;
                JsonObject row = el.getAsJsonObject();

                // JS 逻辑: rowTitle !== searchTitle && !rowTitle.includes(searchTitle)...
                String rowTitle = row.has("titleTxt") ? row.get("titleTxt").getAsString().toLowerCase().replaceAll("\\s", "") : "";
                String searchTitle = title.toLowerCase().replaceAll("\\s", "");
                if (!rowTitle.equals(searchTitle) && !rowTitle.contains(searchTitle) && !searchTitle.contains(rowTitle)) continue;

                String catName = row.has("cat_name") ? row.get("cat_name").getAsString() : "";
                if ("电影".equals(catName)) {
                    JsonObject pl = row.getAsJsonObject("playlinks");
                    if (pl != null) {
                        // JS: pl.qq || pl.qiyi...
                        String rawUrl = pl.has("qq") ? pl.get("qq").getAsString() : 
                                        pl.has("qiyi") ? pl.get("qiyi").getAsString() : "";
                        if (!rawUrl.isEmpty()) return cleanUrl(rawUrl);
                    }
                } else if (row.has("seriesPlaylinks") && row.get("seriesPlaylinks").isJsonArray()) {
                    JsonArray series = row.getAsJsonArray("seriesPlaylinks");
                    if (series.size() >= episodeNum && episodeNum > 0) {
                        JsonElement target = series.get(episodeNum - 1);
                        // 处理 JS 发现的 Object 或 String 混合
                        if (target.isJsonObject() && target.getAsJsonObject().has("url")) {
                            return cleanUrl(target.getAsJsonObject().get("url").getAsString());
                        } else if (target.isJsonPrimitive()) {
                            return cleanUrl(target.getAsString());
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 对应 JS 的 searchJinchan
     */
    private static String searchJinchan(String title, int episodeNum) {
        try {
            String apiUrl = "https://zy.jinchancaiji.com/api.php/provide/vod/?ac=detail&wd=" + URLEncoder.encode(title, "UTF-8");
            String res = OkHttp.string(apiUrl);
            JsonObject data = Json.safeObject(res);

            if (data != null && data.has("list") && data.getAsJsonArray("list").size() > 0) {
                String playUrl = data.getAsJsonArray("list").get(0).getAsJsonObject().get("vod_play_url").getAsString();
                String[] episodes = playUrl.split("#");
                for (String ep : episodes) {
                    String[] parts = ep.split("\\$");
                    if (parts.length >= 2) {
                        Matcher m = Pattern.compile("\\d+").matcher(parts[0]);
                        if (m.find() && Integer.parseInt(m.group()) == episodeNum) {
                            return cleanUrl(parts[1]);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 对应 JS 的 fetchDanmu
     */
    private static String fetchDanmu(String videoUrl) {
        for (String source : DANMU_SOURCES) {
            try {
                String api = source.replace("{url}", URLEncoder.encode(videoUrl, "UTF-8"));
                String res = OkHttp.string(api);

                if (res.contains("<d") && res.contains("</d>")) return res;

                if (res.startsWith("{")) {
                    JsonObject json = Json.safeObject(res);
                    JsonArray danmuku = null;
                    if (json.has("danmuku") && json.get("danmuku").isJsonArray()) {
                        danmuku = json.getAsJsonArray("danmuku");
                    } else if (json.has("data") && json.getAsJsonObject("data").has("danmuku")) {
                        danmuku = json.getAsJsonObject("data").getAsJsonArray("danmuku");
                    }

                    if (danmuku != null) return convertJsonToXml(danmuku);
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String convertJsonToXml(JsonArray danmuku) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><i>\n");
        Random rand = new Random();
        for (JsonElement d : danmuku) {
            try {
                String time = "0", content = "", color = COLORS[rand.nextInt(COLORS.length)];
                if (d.isJsonArray()) {
                    JsonArray item = d.getAsJsonArray();
                    time = item.get(0).getAsString();
                    content = item.get(4).getAsString();
                    color = item.get(3).getAsString();
                } else if (d.isJsonObject()) {
                    JsonObject obj = d.getAsJsonObject();
                    time = obj.has("time") ? obj.get("time").getAsString() : "0";
                    content = obj.has("content") ? obj.get("content").getAsString() : "";
                }

                // JS 逻辑: 过滤广告
                if (!content.matches(".*(请遵守弹幕礼仪|官方弹幕库|微信公众号|云烟小助手|未传入链接|弹幕列队|火花剧场).*")) {
                    xml.append(String.format("<d p=\"%s,1,25,%s\">%s</d>\n", time, color, escapeXml(content)));
                }
            } catch (Exception ignored) {}
        }
        xml.append("</i>");
        return xml.toString();
    }

    private static String generateEmptyDanmu(String title, int episode) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><i>"
                + "<d p=\"0,1,25,16777215\">暂无弹幕: " + escapeXml(title) + " 第" + episode + "集</d></i>";
    }

    private static String cleanUrl(String url) {
        return url != null && url.contains("?") ? url.split("\\?")[0] : url;
    }

    private static String escapeXml(String str) {
        return str == null ? "" : str.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
