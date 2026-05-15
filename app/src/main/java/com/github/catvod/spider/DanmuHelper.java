package com.github.catvod.spider;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * DanmuHelper - 针对 TVSpider 项目优化的弹幕助手
 * 功能：多源搜索弹幕、JSON转XML、广告过滤、本地代理响应
 */
public class DanmuHelper {

    // 弹幕源 API (可扩展)
    private static final String[] DANMU_SOURCES = {
            "https://api.danmu.icu/?ac=dm&url={url}",
            "https://dmku.hls.one/?ac=dm&url={url}"
    };

    // 弹幕内容指纹过滤正则（去除采集站广告）- 预编译提升性能
    private static final Pattern AD_PATTERN = Pattern.compile(
            ".*(请遵守弹幕礼仪|官方弹幕库|微信公众号|云烟小助手|未传入链接|弹幕列队|火花剧场|加群|防走失|备用|联系|侵权).*"
    );

    /**
     * 响应 Spider 类的 proxy 调用
     * params 必须包含：
     * - title: 视频标题
     * - episode: 集数
     */
    public static String getDanmuXml(String title, int episodeNum) {
    try {
        String videoUrl = searchVideoUrl(title, episodeNum);
        String xmlContent = "";
        if (!videoUrl.isEmpty()) {
            xmlContent = fetchAndConvert(videoUrl);
        }
        if (xmlContent.isEmpty()) {
            xmlContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><i>"
                    + "<d p=\"0,1,25,16777215\">[弹幕] " + title + " 加载完成</d>"
                    + "</i>";
        }
        return xmlContent;
    } catch (Exception e) {
        SpiderDebug.log(e);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><i></i>";
    }
}

    /**
     * 视频 URL 搜索逻辑
     */
    private static String searchVideoUrl(String title, int episode) {
        try {
            String searchUrl = "https://api.so.360kan.com/index?force_v=1&kw="
                    + URLEncoder.encode(title, "UTF-8") + "&tab=all";
            String json = OkHttp.string(searchUrl);

            JsonObject root = Json.safeObject(json);
            if (root == null || !root.has("data")) return "";

            JsonObject data = root.getAsJsonObject("data");
            if (data == null || !data.has("longData")) return "";

            JsonArray rows = data.getAsJsonObject("longData").getAsJsonArray("rows");
            if (rows == null) return "";

            for (JsonElement el : rows) {
                JsonObject row = el.getAsJsonObject();
                if (!row.has("titleTxt")) continue;

                String rowTitle = row.get("titleTxt").getAsString();
                if (!rowTitle.contains(title) && !title.contains(rowTitle)) continue;

                if (row.has("cat_name") && "电影".equals(row.get("cat_name").getAsString())) {
                    if (!row.has("playlinks")) continue;
                    JsonObject playlinks = row.getAsJsonObject("playlinks");
                    if (playlinks.has("qq")) return cleanUrl(playlinks.get("qq").getAsString());
                    if (playlinks.has("qiyi")) return cleanUrl(playlinks.get("qiyi").getAsString());
                } else {
                    if (!row.has("seriesPlaylinks")) continue;
                    JsonArray series = row.getAsJsonArray("seriesPlaylinks");
                    if (series.size() >= episode) {
                        JsonObject ep = series.get(episode - 1).getAsJsonObject();
                        if (ep.has("url")) return cleanUrl(ep.get("url").getAsString());
                    }
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * 去掉 URL 参数
     */
    private static String cleanUrl(String url) {
        return url.contains("?") ? url.split("\\?")[0] : url;
    }

    /**
     * 抓取弹幕并转换为 XML
     */
    private static String fetchAndConvert(String videoUrl) {
        for (String source : DANMU_SOURCES) {
            try {
                String api = source.replace("{url}", URLEncoder.encode(videoUrl, "UTF-8"));
                String res = OkHttp.string(api);

                // 如果已经是 XML 格式
                if (res.contains("<d")) return res;

                // JSON 格式弹幕
                JsonObject json = Json.safeObject(res);
                if (json == null) continue;

                JsonArray danmuku = null;
                if (json.has("danmuku")) {
                    danmuku = json.getAsJsonArray("danmuku");
                } else if (json.has("data") && json.getAsJsonObject("data").has("danmuku")) {
                    danmuku = json.getAsJsonObject("data").getAsJsonArray("danmuku");
                }

                if (danmuku == null) continue;

                StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><i>\n");
                for (JsonElement d : danmuku) {
                    JsonArray item = d.getAsJsonArray();
                    if (item.size() < 5) continue;
                    String content = item.get(4).getAsString();
                    if (AD_PATTERN.matcher(content).matches()) continue;

                    String time = item.get(0).getAsString();
                    String color = item.get(3).getAsString();
                    xml.append(String.format("<d p=\"%s,1,25,%s\">%s</d>\n",
                            time, color, escape(content)));
                }
                xml.append("</i>");
                return xml.toString();

            } catch (Exception ignored) {}
        }
        return "";
    }

    /**
     * 转义 XML 特殊字符
     */
    private static String escape(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;");
    }
}
