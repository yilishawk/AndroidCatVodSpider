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
            Proxy.log("🎬 KaiGe 引擎啟動...");
            String json = extend.startsWith("http") ? OkHttp.string(extend, null) : extend;
            this.rule = new JSONObject(json);
            Proxy.log("✅ 規則加載成功，站點: " + rule.optString("site_name", "通用引擎"));
        } catch (Exception e) {
            Proxy.log("🚨 初始化異常: " + e.getMessage());
        }
    }

    // --- 繼承自老代碼的穩健解析器 ---
    private String extract(Object root, String ruleStr) {
        try {
            if (TextUtils.isEmpty(ruleStr) || root == null) return "";
            if (ruleStr.contains("@") && !ruleStr.contains("&&") && root instanceof Element) {
                String[] parts = ruleStr.split("@");
                String selector = parts[0].trim();
                String prop = parts.length > 1 ? parts[1].trim() : "";
                Element el = selector.isEmpty() ? (Element) root : ((Element) root).selectFirst(selector);
                if (el == null) return "";
                if (prop.isEmpty()) return el.text().trim();
                return el.attr(prop).trim();
            }
            String source = (root instanceof Element) ? ((Element) root).outerHtml() : root.toString();
            if (ruleStr.contains("&&")) {
                String[] parts = ruleStr.split("&&");
                String left = parts[0].trim();
                String right = parts.length > 1 ? parts[1].trim() : "";
                if (left.contains("*")) {
                    String[] anchors = left.split("\\*");
                    int pos = 0;
                    for (String a : anchors) {
                        if (a.isEmpty()) continue;
                        int i = source.indexOf(a.trim(), pos);
                        if (i == -1) return "";
                        pos = i + a.trim().length();
                    }
                    int end = source.indexOf(right.replace("[text]", "").trim(), pos);
                    return (end != -1) ? source.substring(pos, end).trim() : "";
                }
                if (!left.isEmpty() && root instanceof Element) {
                    Element el = ((Element) root).selectFirst(left);
                    if (el == null) return "";
                    if (right.equals("text")) return el.text().trim();
                    if (right.equals("html")) return el.html().trim();
                    return el.attr(right).trim();
                }
                int s = source.indexOf(left);
                if (s == -1) return "";
                s += left.length();
                int e = source.indexOf(right, s);
                return (e != -1) ? source.substring(s, e).trim() : "";
            }
            if (root instanceof Element) {
                Element el = ((Element) root).selectFirst(ruleStr);
                if (el == null) return "";
                if (el.tagName().toLowerCase().equals("img")) {
                    String pic = el.attr("data-original");
                    if (pic.isEmpty()) pic = el.attr("src");
                    return pic;
                }
                return el.text().trim();
            }
        } catch (Exception e) {}
        return "";
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String id = ids.get(0);
            // 🛡️ 關鍵：如果是調試請求，不報錯也不跳轉，返回一個「安全空殼」
            if (id.contains("kaige_debug")) return "";

            String url = id.startsWith("http") ? id : rule.optString("host") + (id.startsWith("/") ? "" : "/") + id;
            Proxy.log("📝 [詳情請求]: " + url);
            String html = OkHttp.string(url, getHeaders(null));
            Document doc = Jsoup.parse(html);
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            
            // 逐行噴發日誌
            String name = extract(doc, rule.optString("dt_name"));
            Proxy.log("💎 標題: " + name);
            vod.put("vod_name", name);
            vod.put("vod_pic", getPicUrl(extract(doc, rule.optString("dt_pic"))));
            
            String actor = extract(doc, rule.optString("dt_actor"));
            Proxy.log("🎭 演員: " + actor);
            vod.put("vod_actor", actor);

            String director = extract(doc, rule.optString("dt_director"));
            Proxy.log("🎬 導演: " + director);
            vod.put("vod_director", director);

            String content = extract(doc, rule.optString("dt_content"));
            Proxy.log("📄 簡介: " + (content.length() > 50 ? content.substring(0, 50) + "..." : content));
            vod.put("vod_content", content);

            // 播放列表解析
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
            return res;
        } catch (Exception e) { return ""; }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        // 🚨 這是最像老代碼的「攔截」：不報錯，返回一個空的 parse:0 🚨
        if (id != null && id.contains("kaige_debug")) {
            Proxy.log("🛠️ [系統攔截] 調試請求，強制停止跳轉。");
            return "{\"parse\":0,\"url\":\"\"}";
        }

        try {
            String url = id;
            if (url.startsWith("/") && !url.startsWith("//")) url = rule.optString("host") + url;
            Proxy.log("\n🚀 [播放解析啟動] URL: " + url);

            if (!rule.has("play") || !rule.getJSONObject("play").has("steps")) return quickResult(url);

            JSONObject playConfig = rule.getJSONObject("play");
            JSONArray steps = playConfig.getJSONArray("steps");
            varPool.clear();
            varPool.put("play_id", url);

            String currentHtml = "";
            for (int i = 0; i < steps.length(); i++) {
                JSONObject step = steps.getJSONObject(i);
                String method = step.optString("method", "get").toLowerCase();
                String stepUrl = replaceStepVars(step.optString("url", url));
                Map<String, String> headers = getHeaders(step.optJSONObject("headers"));

                Proxy.log("🎬 [Step " + (i + 1) + "] " + method.toUpperCase() + " URL: " + stepUrl);

                if (method.contains("post")) {
                    currentHtml = OkHttp.post(stepUrl, replaceStepVars(step.optString("body")), headers).getBody();
                } else {
                    currentHtml = OkHttp.string(stepUrl, headers);
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
            Proxy.log("🏁 [最終地址]: " + finalUrl);
            return new JSONObject().put("parse", 0).put("url", finalUrl).toString();
        } catch (Exception e) { return quickResult(id); }
    }

    // --- 其他功能保持老代碼的穩健輸出 ---
    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            result.put("class", rule.optJSONArray("classes"));
            if (rule.has("filter")) result.put("filters", rule.optJSONObject("filter"));
            return result.toString();
        } catch (Exception e) { return ""; }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            String cateUrl = pg.equals("1") && rule.has("cate_page_1") ? rule.optString("cate_page_1") : rule.optString("cate_url");
            String url = cateUrl.replace("{tid}", tid).replace("{pg}", pg);
            if (url.startsWith("/") && !url.startsWith("//")) url = rule.optString("host") + url;
            String html = OkHttp.string(url, getHeaders(null));
            return parseList(html, pg, false);
        } catch (Exception e) { return ""; }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String url = rule.optString("search_url").replace("{wd}", URLEncoder.encode(key, "UTF-8"));
            if (url.startsWith("/") && !url.startsWith("//")) url = rule.optString("host") + url;
            return parseList(OkHttp.string(url, getHeaders(null)), "1", true);
        } catch (Exception e) { return ""; }
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
                vod.put("vod_pic", getPicUrl(extract(item, rule.optString(prefix + "pic", rule.optString("cate_pic")))));
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

    private String getPicUrl(String pic) {
        if (TextUtils.isEmpty(pic)) return "";
        return pic;
    }
}
