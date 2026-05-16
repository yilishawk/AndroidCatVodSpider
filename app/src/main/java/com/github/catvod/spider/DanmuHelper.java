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
    Proxy.log("🎯 [弹幕] 开始请求 title=" + title + " ep=" + episodeNum);
    try {
        // 1. 搜索获取原始链接 (严格匹配 titleTxt)
        String rawUrl = search360kan(title, episodeNum);
        Proxy.log("🎯 [弹幕] rawUrl=" + rawUrl);
        if (rawUrl == null || rawUrl.isEmpty()) {
            Proxy.log("🎯 [弹幕] rawUrl为空，返回空弹幕");
            return generateEmpty(title, episodeNum);
        }

        // 2. MD5 加密清洗后的链接
        String md5Id = getMd5(rawUrl);
        Proxy.log("🎯 [弹幕] md5=" + md5Id);

        // 3. 调用指定接口获取 XML
        String apiUrl = "https://danmu.zxz.ee/?type=xml&id=" + md5Id;
        Proxy.log("🎯 [弹幕] 请求XML: " + apiUrl);
        String xml = OkHttp.string(apiUrl);
        Proxy.log("🎯 [弹幕] XML长度=" + (xml == null ? "null" : xml.length()));

        if (xml != null && xml.contains("<d")) return xml;
    } catch (Exception e) {
        Proxy.log("🎯 [弹幕] 异常: " + e.getMessage());
    }
    return generateEmpty(title, episodeNum);
}

    // 修改 search360kan 内部逻辑
    private static String search360kan(String title, int episodeNum) {
        try {
            String url = "https://api.so.360kan.com/index?force_v=1&kw=" + URLEncoder.encode(title, "UTF-8") + "&tab=all";
            String res = OkHttp.string(url);
            JsonObject root = Json.safeObject(res);

            // 🛡️ 防御 A: 检查 root 和 data 节点
            if (root == null || !root.has("data") || root.get("data").isJsonNull()) return null;
            JsonObject data = root.getAsJsonObject("data");

            // 🛡️ 防御 B: 检查 longData 是否搜到结果
            if (!data.has("longData") || data.get("longData").isJsonNull()) {
                Proxy.log("⚠️ [Helper] 360未搜到该剧: " + title);
                return null;
            }
            JsonObject longData = data.getAsJsonObject("longData");

            // 🛡️ 防御 C: 检查 rows 数组
            if (!longData.has("rows") || longData.get("rows").isJsonNull()) return null;
            JsonArray rows = longData.getAsJsonArray("rows");

            for (JsonElement el : rows) {
                if (!el.isJsonObject()) continue;
                JsonObject row = el.getAsJsonObject();
                
                // 严格匹配 titleTxt
                String titleTxt = row.has("titleTxt") ? row.get("titleTxt").getAsString().replace(" ", "") : "";
                if (!titleTxt.equalsIgnoreCase(title.replace(" ", ""))) continue;

                // 提取剧集链接
                if (row.has("seriesPlaylinks") && row.get("seriesPlaylinks").isJsonArray()) {
                    JsonArray series = row.getAsJsonArray("seriesPlaylinks");
                    if (series.size() >= episodeNum && episodeNum > 0) {
                        JsonElement target = series.get(episodeNum - 1);
                        String epUrl = target.isJsonObject() ? target.getAsJsonObject().get("url").getAsString() : target.getAsString();
                        
                        // 清洗 URL 参数对齐 MD5 逻辑
                        return epUrl.contains("?") ? epUrl.split("\\?")[0] : epUrl;
                    }
                }
            }
        } catch (Exception e) {
            Proxy.log("❌ [Helper] 解析崩溃: " + e.getMessage());
        }
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
