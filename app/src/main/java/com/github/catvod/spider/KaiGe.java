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

    private void logger(String msg) {
        Proxy.log("[KG] " + msg);
    }

    @Override
    public void init(Context context, String extend) {
        try {
            String json = extend.startsWith("http") ? OkHttp.string(extend, null) : extend;
            this.rule = new JSONObject(json);
            logger("🚀 引擎啟動: " + rule.optString("site_name"));
        } catch (Exception e) {
            logger("🚨 初始化失敗: " + e.getMessage());
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            String cateUrl = pg.equals("1") && rule.has("cate_page_1") ? rule.optString("cate_page_1") : rule.optString("cate_url");
            String url = cateUrl.replace("{tid}", tid).replace("{pg}", pg);
            if (url.startsWith("/") && !url.startsWith("//")) url = rule.optString("host") + url;
            
            logger("📂 [分類動作] URL: " + url);
            logger("📑 [請求頭]: " + new JSONObject(getHeaders(null)).toString());

            String html = OkHttp.string(url, getHeaders(null));
            return parseList(html, pg, false);
        } catch (Exception e) { return ""; }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String url = rule.optString("search_url").replace("{wd}", URLEncoder.encode(key, "UTF-8"));
            if (url.startsWith("/") && !url.startsWith("//")) url = rule.optString("host") + url;
            
            logger("🔍 [搜索動作] 關鍵詞: " + key + " | URL: " + url);
            logger("📑 [請求頭]: " + new JSONObject(getHeaders(null)).toString());

            String html = OkHttp.string(url, getHeaders(null));
            return parseList(html, "1", true);
        } catch (Exception e) { return ""; }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String id = ids.get(0);
            if (id.contains("kaige_debug")) return "";

            String url = id.startsWith("http") ? id : rule.optString("host") + (id.startsWith("/") ? "" : "/") + id;
            logger("📝 [詳情動作] URL: " + url);
            logger("📑 [請求頭]: " + new JSONObject(getHeaders(null)).toString());

            String html = OkHttp.string(url, getHeaders(null));
            Document doc = Jsoup.parse(html);
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            
            // 詳情只打印關鍵字段提取結果，不打印 HTML
            String name = extract(doc, rule.optString("dt_name"));
            logger("💎 標題: " + name);
            vod.put("vod_name", name);
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

            return new JSONObject().put("list", new JSONArray().put(vod)).toString();
        } catch (Exception e) { return ""; }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (id != null && id.contains("kaige_debug")) {
            logger("🛠️ [日誌請求] 攔截成功，已切斷 WebView 重定向。");
            return "{\"parse\":0,\"url\":\"\"}";
        }

        try {
            String url = id;
            if (url.startsWith("/") && !url.startsWith("//")) url = rule.optString("host") + url;
            logger("\n🚀 [播放解析啟動] 目標: " + url);

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

                logger("🎬 [Step " + (i + 1) + "] " + method.toUpperCase() + " URL: " + stepUrl);
                logger("📑 [請求頭]: " + new JSONObject(headers).toString());

                if (method.contains("post")) {
                    String body = replaceStepVars(step.optString("body"));
                    logger("📤 [POST Body]: " + body);
                    currentHtml = OkHttp.post(stepUrl, body, headers).getBody();
                } else {
                    currentHtml = OkHttp.string(stepUrl, headers);
                }

                // 🌟 播放解析這裏保留 300 字響應，因為這裏最需要看源碼調試參數
                String preview = currentHtml.length() > 300 ? currentHtml.substring(0, 300) : currentHtml;
                logger("📥 [Step響應]: " + preview.replace("\n", " "));

                if (step.has("vars")) {
                    JSONObject vars = step.getJSONObject("vars");
                    Iterator<String> keys = vars.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String vRule = vars.getString(key);
                        String val = vRule.startsWith("json:") ? new JSONObject(currentHtml).optString(vRule.substring(5)) : extract(currentHtml, vRule);
                        varPool.put(key, val);
                        logger("💎 [提取] " + key + " = " + val);
                    }
                }
            }

            String finalUrl = replaceStepVars(playConfig.optString("final_output", "{final_url}"));
            logger("🏁 [最終播放地址]: " + finalUrl);
            return new JSONObject().put("parse", 0).put("url", finalUrl).toString();
        } catch (Exception e) { return quickResult(id); }
    }

    // --- 輔助解析邏輯 ---
    private String extract(Object root, String ruleStr) {
        try {
            if (TextUtils.isEmpty(ruleStr) || root == null) return "";
            if (ruleStr.contains("@") && !ruleStr.contains("&&") && root instanceof Element) {
                String[] parts = ruleStr.split("@");
                Element el = parts[0].trim().isEmpty() ? (Element) root : ((Element) root).selectFirst(parts[0].trim());
                return el == null ? "" : (parts.length > 1 ? el.attr(parts[1].trim()) : el.text()).trim();
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
                if (el == null) return "";
                return el.tagName().equalsIgnoreCase("img") ? (el.hasAttr("data-original") ? el.attr("data-original") : el.attr("src")) : el.text().trim();
            }
        } catch (Exception e) {}
        return "";
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
                list.put(vod);
            }
            return new JSONObject().put("list", list).put("page", pg).toString();
        } catch (Exception e) { return "{\"list\":[]}"; }
    }

    private String quickResult(String url) {
        try {
            if (url.startsWith("/") && !url.startsWith("//")) url = rule.optString("host") + url;
            return new JSONObject().put("parse", rule.optInt("parse", 0)).put("url", url).toString();
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
                String k = keys.next();
                header.put(k, replaceStepVars(hd.optString(k)));
            }
        }
        return header;
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            result.put("class", rule.optJSONArray("classes"));
            if (rule.has("filter")) result.put("filters", rule.optJSONObject("filter"));
            return result.toString();
        } catch (Exception e) { return ""; }
    }
}
