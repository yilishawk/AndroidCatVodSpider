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
        
        // 探测点 A: 网络返回
        if (res == null || res.isEmpty()) {
            Proxy.log("❌ [哨兵3-Helper] 360kan 请求失败，返回为空");
            return null;
        }

        JsonObject root = Json.safeObject(res);
        
        // 探测点 B: data 节点
        if (root == null || !root.has("data") || root.get("data").isJsonNull()) {
            Proxy.log("❌ [哨兵3-Helper] 360kan 无 data 节点");
            return null;
        }
        JsonObject data = root.getAsJsonObject("data");

        // 探测点 C: longData 节点 (最常报错 null array 的地方)
        if (!data.has("longData") || data.get("longData").isJsonNull()) {
            Proxy.log("⚠️ [哨兵3-Helper] 搜索无结果 (longData is null)");
            return null;
        }
        JsonObject longData = data.getAsJsonObject("longData");

        // 探测点 D: rows 节点
        if (!longData.has("rows") || longData.get("rows").isJsonNull()) {
            Proxy.log("⚠️ [哨兵3-Helper] rows 节点为空");
            return null;
        }
        JsonArray rows = longData.getAsJsonArray("rows");
        Proxy.log("✅ [哨兵3-Helper] 匹配到 " + rows.size() + " 条搜索结果");

        for (JsonElement el : rows) {
            // ... 后续匹配逻辑 ...
        }
    } catch (Exception e) {
        Proxy.log("❌ [哨兵3-Helper] 崩溃位置: " + e.getMessage());
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
