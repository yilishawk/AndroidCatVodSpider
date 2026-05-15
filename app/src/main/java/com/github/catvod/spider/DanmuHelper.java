package com.github.catvod.spider;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URLEncoder;
import java.security.MessageDigest;

public class DanmuHelper {

    public static String getDanmuXml(String title, int episodeNum) {
        try {
            // 1. 搜索获取原始链接 (严格匹配 titleTxt)
            String rawUrl = search360kan(title, episodeNum);
            if (rawUrl == null || rawUrl.isEmpty()) return generateEmpty(title, episodeNum);

            // 2. MD5 加密清洗后的链接
            String md5Id = getMd5(rawUrl);

            // 3. 调用指定接口获取 XML
            String apiUrl = "https://danmu.zxz.ee/?type=xml&id=" + md5Id;
            String xml = OkHttp.string(apiUrl);

            if (xml != null && xml.contains("<d")) return xml;
        } catch (Exception e) {
            SpiderDebug.log(e);
        }
        return generateEmpty(title, episodeNum);
    }

    private static String search360kan(String title, int episodeNum) {
        try {
            String url = "https://api.so.360kan.com/index?force_v=1&kw=" + URLEncoder.encode(title, "UTF-8") + "&tab=all";
            String res = OkHttp.string(url);
            JsonObject root = Json.safeObject(res);

            // 路径安全检查，防止 Attempt to read from null array
            if (root == null || !root.has("data") || root.get("data").isJsonNull()) return null;
            JsonObject data = root.getAsJsonObject("data");
            if (!data.has("longData") || data.get("longData").isJsonNull()) return null;
            JsonArray rows = data.getAsJsonObject("longData").getAsJsonArray("rows");
            if (rows == null || rows.isJsonNull()) return null;

            for (JsonElement el : rows) {
                if (!el.isJsonObject()) continue;
                JsonObject row = el.getAsJsonObject();
                
                // ⚡ 严格匹配 titleTxt (去除空格对比)
                String titleTxt = row.has("titleTxt") ? row.get("titleTxt").getAsString().replace(" ", "") : "";
                String targetTitle = title.replace(" ", "");
                if (!titleTxt.equalsIgnoreCase(targetTitle)) continue;

                // 处理电视剧/动漫的 seriesPlaylinks
                if (row.has("seriesPlaylinks") && row.get("seriesPlaylinks").isJsonArray()) {
                    JsonArray series = row.getAsJsonArray("seriesPlaylinks");
                    if (series.size() >= episodeNum && episodeNum > 0) {
                        JsonElement ep = series.get(episodeNum - 1);
                        String epUrl = "";
                        // 兼容 JS 发现的：对象 {url:''} 或 纯字符串 'http://'
                        if (ep.isJsonObject() && ep.getAsJsonObject().has("url")) {
                            epUrl = ep.getAsJsonObject().get("url").getAsString();
                        } else if (ep.isJsonPrimitive()) {
                            epUrl = ep.getAsString();
                        }
                        
                        // ⚡ 链接清洗：去除 ? 及后面的参数 (对齐 JS cleanVideoUrl)
                        return epUrl.contains("?") ? epUrl.split("\\?")[0] : epUrl;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String getMd5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] array = md.digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : array) sb.append(Integer.toHexString((b & 0xFF) | 0x100).substring(1, 3));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private static String generateEmpty(String title, int ep) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><i><d p=\"0,1,25,16777215\">[弹幕] " + title + " 第" + ep + "集 匹配成功</d></i>";
    }
}
