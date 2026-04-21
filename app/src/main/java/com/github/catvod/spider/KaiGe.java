package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.net.URLEncoder;
import java.util.*;

public class KaiGe extends Spider {
    private JSONObject rule = new JSONObject();
    private Map<String, String> varPool = new HashMap<>();

    @Override
    public void init(Context context, String extend) {
        try {
            String json = extend.startsWith("http") ? OkHttp.string(extend, null) : extend;
            this.rule = new JSONObject(json);
            Proxy.log("✅ [引擎初始化成功] 站點: " + rule.optString("site_name"));
        } catch (Exception e) {
            Proxy.log("🚨 [初始化失敗]: " + e.getMessage());
        }
    }

    private String extract(Object root, String ruleStr) {
        try {
            if (TextUtils.isEmpty(ruleStr) || root == null) return "";
            String source = (root instanceof Element) ? ((Element) root).outerHtml() : root.toString();
            if (ruleStr.contains("&&")) {
                String[] parts = ruleStr.split("&&");
                String left = parts[0].trim();
                String right = parts.length > 1 ? parts[1].trim() : "";
                if (left.contains("*")) {
                    String[] anchors = left.split("\\*");
                    int pos = 0;
                    for (String a : anchors) {
                        int i = source.indexOf(a.trim(), pos);
                        if (i == -1) return "";
                        pos = i + a.trim().length();
                    }
                    int end = source.indexOf(right.replace("[text]", "").trim(), pos);
                    return (end != -1) ? source.substring(pos, end).trim() : "";
                }
                if (root instanceof Element && !left.isEmpty()) {
                    Element el = ((Element) root).selectFirst(left);
                    if (el == null) return "";
                    return right.equals("text") ? el.text().trim() : (right.equals("html") ? el.html().trim() : el.attr(right).trim());
                }
                int s = source.indexOf(left);
                if (s == -1) return "";
                s += left.length();
                int e = source.indexOf(right, s);
                return (e != -1) ? source.substring(s, e).trim() : "";
            }
            if (root instanceof Element) {
                Element el = ((Element) root).selectFirst(ruleStr);
                return el == null ? "" : el.text().trim();
            }
        } catch (Exception e) {}
        return "";
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            result.put("class", rule.optJSONArray("classes"));
            if (rule.has("filter")) result.put("filters", rule.optJSONObject("filter"));
            Proxy.log("🏠 [首頁數據內容]:\n" + result.toString(4)); // JSON 格式化日誌
            return result.toString();
        } catch (Exception e) { return ""; }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            String cateUrl = pg.equals("1") && rule.has("cate_page_1") ? rule.optString("cate_page_1") : rule.optString("cate_url");
            String url = cateUrl.replace("{tid}", tid).replace("{pg}", pg);
            if (extend != null) for (String key : extend.keySet()) url = url.replace("{" + key + "}", extend.get(key));
            if (url.startsWith("/") && !url.startsWith("//")) url = rule.optString("host") + url;
            
            Proxy.log("📂 [分類請求] tid: " + tid + " | pg: " + pg + " | URL: " + url);
            String html = OkHttp.string(url, getHeaders(null));
            String result = parseList(html, pg, false);
            Proxy.log("📦 [分類解析結果]:\n" + new JSONObject(result).toString(4));
            return result;
        } catch (Exception e) { return ""; }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String id = ids.get(0);
            String url = id.startsWith("http") ? id : rule.optString("host") + id;
            Proxy.log("📝 [詳情請求] URL: " + url);
            Document doc = Jsoup.parse(OkHttp.string(url, getHeaders(null)));
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            vod.put("vod_name", extract(doc, rule.optString("dt_name")));
            vod.put("vod_actor", extract(doc, rule.optString("dt_actor")));
            vod.put("vod_director", extract(doc, rule.optString("dt_director")));
            vod.put("vod_content", extract(doc, rule.optString("dt_content")));
            
            Elements froms = doc.select(rule.optString("dt_from"));
            List<String> fromList = new ArrayList<>();
            for (Element f : froms) fromList.add(f.text().trim());
            vod.put("vod_play_from", TextUtils.join("$$$", fromList));

            Elements urlLists = doc.select(rule.optString("dt_list"));
            List<String> circuits = new ArrayList<>();
            for (Element list : urlLists) {
                List<String> urls = new ArrayList<>();
                for (Element a : list.select("a")) urls.add(a.text().trim() + "$" + a.attr("href"));
                circuits.add(TextUtils.join("#", urls));
            }
            vod.put("vod_play_url", TextUtils.join("$$$", circuits));
            
            String res = new JSONObject().put("list", new JSONArray().put(vod)).toString();
            Proxy.log("✅ [詳情解析內容]:\n" + new JSONObject(res).toString(4));
            return res;
        } catch (Exception e) { return ""; }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String url = id;
            if (url.startsWith("/") && !url.startsWith("//")) url = rule.optString("host") + url;
            Proxy.log("\n🚀 [播放解析啟動] 原始網址: " + url);

            if (!rule.has("play") || !rule.getJSONObject("play").has("steps")) {
                return quickResult(url);
            }

            JSONObject playConfig = rule.getJSONObject("play");
            JSONArray steps = playConfig.getJSONArray("steps");
            varPool.clear();
            varPool.put("play_id", url);

            String currentHtml = "";
            for (int i = 0; i < steps.length(); i++) {
                JSONObject step = steps.getJSONObject(i);
                String method = step.optString("method", "get").toLowerCase();
                
                if (method.equals("extract")) {
                    Proxy.log("🎬 [Step " + (i+1) + "] EXTRACT 提取源碼...");
                    if (TextUtils.isEmpty(currentHtml)) currentHtml = OkHttp.string(url, getHeaders(null));
                } else {
                    String stepUrl = replaceStepVars(step.optString("url", url));
                    Map<String, String> headers = getHeaders(step.optJSONObject("headers"));
                    
                    // --- 必須打印請求頭 ---
                    Proxy.log("🎬 [Step " + (i+1) + "] " + method.toUpperCase() + " URL: " + stepUrl);
                    Proxy.log("📑 [Headers]: " + new JSONObject(headers).toString());
                    
                    if (method.contains("post")) {
                        String body = replaceStepVars(step.optString("body"));
                        Proxy.log("📤 [POST Body]: " + body);
                        currentHtml = OkHttp.post(stepUrl, body, headers).getBody();
                    } else {
                        currentHtml = OkHttp.string(stepUrl, headers);
                    }
                }

                if (step.has("vars")) {
                    JSONObject vars = step.getJSONObject("vars");
                    Iterator<String> keys = vars.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String vRule = vars.getString(key);
                        String val = vRule.startsWith("json:") ? new JSONObject(currentHtml).optString(vRule.substring(5)) : extract(currentHtml, vRule);
                        varPool.put(key, val);
                        Proxy.log("💎 [變量提取] " + key + " = " + val);
                    }
                }
            }

            String finalUrl = replaceStepVars(playConfig.optString("final_output", "{final_url}"));
            if (finalUrl.startsWith("/") && !finalUrl.startsWith("//")) finalUrl = rule.optString("host") + finalUrl;
            
            JSONObject res = new JSONObject();
            res.put("parse", 0);
            res.put("url", finalUrl);
            if (playConfig.has("play_headers")) res.put("header", playConfig.optJSONObject("play_headers").toString());
            
            Proxy.log("🏁 [最終播放 JSON]:\n" + res.toString(4));
            return res.toString();
        } catch (Exception e) {
            Proxy.log("🚨 [播放解析異常]: " + e.getMessage());
            return quickResult(id);
        }
    }

    private String parseList(String html, String pg, boolean isSearch) {
        try {
            Document doc = Jsoup.parse(html);
            JSONArray list = new JSONArray();
            String prefix = isSearch ? "sc_" : "cate_";
            String itemRule = rule.optString(prefix + "item", rule.optString("cate_item"));
            Elements items = doc.select(itemRule);
            for (Element item : items) {
                JSONObject vod = new JSONObject();
                String vId = extract(item, rule.optString(prefix + "id", rule.optString("cate_id")));
                if (!vId.startsWith("http") && !vId.startsWith("//")) vId = rule.optString("host") + (vId.startsWith("/") ? "" : "/") + vId;
                vod.put("vod_id", vId);
                vod.put("vod_name", extract(item, rule.optString(prefix + "name", rule.optString("cate_name"))));
                vod.put("vod_pic", extract(item, rule.optString(prefix + "pic", rule.optString("cate_pic"))));
                vod.put("vod_remarks", extract(item, rule.optString(prefix + "remarks", rule.optString("cate_remarks"))));
                list.put(vod);
            }
            return new JSONObject().put("list", list).put("page", pg).toString();
        } catch (Exception e) { return "{\"list\":[]}"; }
    }

    private String quickResult(String url) {
        try {
            if (url.startsWith("/") && !url.startsWith("//")) url = rule.optString("host") + url;
            JSONObject res = new JSONObject();
            res.put("parse", rule.optInt("parse", 0));
            res.put("url", url);
            return res.toString();
        } catch (Exception e) { return ""; }
    }

    private String replaceStepVars(String text) {
        if (text == null) return "";
        for (String key : varPool.keySet()) text = text.replace("{" + key + "}", varPool.get(key));
        return text.replace("{host}", rule.optString("host"));
    }

    private Map<String, String> getHeaders(JSONObject customHd) {
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", rule.optString("ua", "Mozilla/5.0"));
        JSONObject hd = customHd != null ? customHd : rule.optJSONObject("headers");
        if (hd != null) {
            Iterator<String> keys = hd.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                header.put(key, replaceStepVars(hd.optString(key)));
            }
        }
        return header;
    }
}
