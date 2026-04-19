package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.AESEncryption;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WK 核心引擎 - 自成一派
 * 功能：指令化解析、CSS選擇器、動態變量繼承、AES解密、動態嗅探
 */
public class WK extends Spider {

    private JsonObject ruleConfig;
    private final Map<String, String> vars = new HashMap<>();
    private final List<String> sniffWords = new ArrayList<>();

    @Override
    public void init(Context context, String extend) {
        try {
            // 1. 加載配置 (支持 URL 遠程加載或本地 JSON 字符串)
            String json = extend.startsWith("http") ? OkHttp.string(extend) : extend;
            this.ruleConfig = Json.safeObject(json);

            // 2. 初始化全局 UA
            vars.put("ua", ruleConfig.has("ua") ? ruleConfig.get("ua").getAsString() : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

            // 3. 預加載嗅探詞
            if (ruleConfig.has("sniff_words")) {
                JsonArray arr = ruleConfig.getAsJsonArray("sniff_words");
                for (JsonElement el : arr) {
                    sniffWords.add(el.getAsString());
                }
            }

            // 4. 執行初始化步驟 (如動態域名獲取)
            if (ruleConfig.has("init")) {
                executeSteps(ruleConfig.getAsJsonObject("init").getAsJsonArray("steps"), "");
            }
            SpiderDebug.log("WK引擎：初始化完成");
        } catch (Exception e) {
            SpiderDebug.log("WK初始化失敗: " + e.getMessage());
        }
    }

    /**
     * 核心執行引擎：指令流水線
     */
    private String executeSteps(JsonArray steps, String input) {
        String current = input;
        if (steps == null) return current;

        for (JsonElement element : steps) {
            JsonObject step = element.getAsJsonObject();
            String action = step.get("action").getAsString();

            try {
                switch (action) {
                    case "get": // 發起網絡請求
                        current = OkHttp.string(replaceVars(step.get("url").getAsString(), current), getHeaders(step));
                        break;

                    case "css": // CSS 選擇器解析 (格式: "selector--attr")
                        String selector = step.get("selector").getAsString();
                        current = parseCss(current, selector);
                        if (step.has("save_as")) vars.put(step.get("save_as").getAsString(), current);
                        break;

                    case "extract": // 正則提取 (兼容 XBPQ && 邏輯)
                        String regex = step.get("regex").getAsString();
                        Matcher m = Pattern.compile(regex).matcher(current);
                        if (m.find()) {
                            current = m.group(step.has("group") ? step.get("group").getAsInt() : 1);
                            if (step.has("save_as")) vars.put(step.get("save_as").getAsString(), current);
                        }
                        break;

                    case "aes_decrypt": // AES 解密 (支持動態 Key/IV)
                        String key = replaceVars(step.get("key").getAsString(), "");
                        String iv = step.has("iv") ? replaceVars(step.get("iv").getAsString(), "") : "";
                        String trans = step.has("trans") ? step.get("trans").getAsString() : AESEncryption.CBC_PKCS_7_PADDING;
                        current = AESEncryption.decrypt(current, key, iv, trans);
                        break;

                    case "set_var": // 手動設置變量
                        String name = step.get("name").getAsString();
                        String val = replaceVars(step.get("value").getAsString(), current);
                        vars.put(name, val);
                        break;
                }
            } catch (Exception e) {
                SpiderDebug.log("Action [" + action + "] 失敗: " + e.getMessage());
            }
        }
        return current;
    }

    private String parseCss(String html, String rule) {
        try {
            String[] parts = rule.split("--");
            Document doc = Jsoup.parse(html);
            Element el = doc.selectFirst(parts[0]);
            if (el == null) return "";
            return (parts.length < 2 || parts[1].equals("text")) ? el.text() : el.attr(parts[1]);
        } catch (Exception e) { return ""; }
    }

    private String replaceVars(String text, String input) {
        if (text == null) return "";
        String res = text.replace("{input}", input);
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            res = res.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return res;
    }

    private Map<String, String> getHeaders(JsonObject obj) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", vars.get("ua"));
        if (obj.has("headers")) {
            JsonObject hd = obj.getAsJsonObject("headers");
            for (String key : hd.keySet()) headers.put(key, replaceVars(hd.get(key).getAsString(), ""));
        }
        return headers;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        // 分類數據直接從 ruleConfig 讀取返回，不走網絡
        return ""; // 具體實現略，可根據 ruleConfig.get("class") 生成
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (!ruleConfig.has("detail")) return "";
        return executeSteps(ruleConfig.getAsJsonObject("detail").getAsJsonArray("steps"), ids.get(0));
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        JsonObject result = new JsonObject();
        if (ruleConfig.has("player")) {
            String playUrl = executeSteps(ruleConfig.getAsJsonObject("player").getAsJsonArray("steps"), id);
            result.addProperty("parse", 0);
            result.addProperty("url", playUrl);
            result.addProperty("header", Json.toJson(getHeaders(ruleConfig.getAsJsonObject("player"))));
        }
        return result.toString();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        if (!ruleConfig.has("search")) return "";
        vars.put("wd", key);
        return executeSteps(ruleConfig.getAsJsonObject("search").getAsJsonArray("steps"), "");
    }

    @Override
    public boolean isVideoFormat(String url) throws Exception {
        for (String word : sniffWords) if (url.toLowerCase().contains(word.toLowerCase())) return true;
        String path = url.toLowerCase();
        return path.contains(".m3u8") || path.contains(".mp4") || path.contains(".flv");
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception { return ""; }
}
