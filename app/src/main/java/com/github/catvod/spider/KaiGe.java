package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
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
        Proxy.log(msg);
    }

    @Override
    public void init(Context context, String extend) {
        try {
            logger("🛠️ [初始化] 正在讀取配置...");
            String json = extend.startsWith("http") ? OkHttp.string(extend, null) : extend;
            this.rule = new JSONObject(json);
            logger("✅ [初始化成功] 站點: " + rule.optString("site_name"));
        } catch (Exception e) {
            logger("🚨 [初始化失敗]: " + e.getMessage());
        }
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

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            String cateUrl = (pg.equals("1") && rule.has("cate_page_1")) ? rule.optString("cate_page_1") : rule.optString("cate_url");
            String url = cateUrl.replace("{tid}", tid).replace("{pg}", pg);
            if (url.startsWith("/") && !url.startsWith("//")) url = rule.optString("host") + url;
            logger("📂 [分類] URL: " + url);
            String html = OkHttp.string(url, getHeaders(null));
            return parseList(html, pg, false);
        } catch (Exception e) { return "{\"list\":[]}"; }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String url = rule.optString("search_url").replace("{wd}", URLEncoder.encode(key, "UTF-8"));
            if (url.startsWith("/") && !url.startsWith("//")) url = rule.optString("host") + url;
            String html = OkHttp.string(url, getHeaders(null));
            return parseList(html, "1", true);
        } catch (Exception e) { return "{\"list\":[]}"; }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String id = ids.get(0);
            String url = id.startsWith("http") ? id : rule.optString("host") + (id.startsWith("/") ? "" : "/") + id;
            String html = OkHttp.string(url, getHeaders(null));
            Document doc = Jsoup.parse(html);
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            vod.put("vod_name", extract(doc, rule.optString("dt_name")));
            vod.put("vod_pic", extract(doc, rule.optString("dt_pic")));
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
        try {
            String url = id.startsWith("/") && !id.startsWith("//") ? rule.optString("host") + id : id;
            logger("<br>🚀 <b>[播放解析啟動]</b>: " + url);
            if (!rule.has("play")) return "{\"parse\":0,\"url\":\"" + url + "\"}";
            JSONObject playConfig = rule.getJSONObject("play");
            JSONArray steps = playConfig.optJSONArray("steps");
            varPool.clear();
            varPool.put("play_id", url);
            String currentHtml = "";
            for (int i = 0; i < (steps != null ? steps.length() : 0); i++) {
                JSONObject step = steps.getJSONObject(i);
                String method = step.optString("method", "get").toLowerCase();
                String stepUrl = replaceStepVars(step.optString("url", url));
                Map<String, String> headers = getHeaders(step.optJSONObject("headers"));
                logger("<b>Step " + (i + 1) + "</b> (" + method.toUpperCase() + "): " + stepUrl);
                OkResult result;
                if (method.equals("post")) {
                    result = OkHttp.post(stepUrl, replaceStepVars(step.optString("body")), headers);
                } else {
                    result = OkHttp.get(stepUrl, null, headers); 
                }
                currentHtml = result.getBody();
                if (TextUtils.isEmpty(currentHtml)) return "{\"parse\":0,\"url\":\"\"}";
                logger("📥 響應預覽: <small>" + (currentHtml.length() > 200 ? currentHtml.substring(0, 200) : currentHtml).replace("<", "&lt;") + "...</small>");
                if (step.has("vars")) {
                    JSONObject vars = step.getJSONObject("vars");
                    Iterator<String> keys = vars.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String vRule = vars.getString(key);
                        String val = vRule.startsWith("json:") ? new JSONObject(currentHtml).optString(vRule.substring(5)) : extract(currentHtml, vRule);
                        varPool.put(key, val);
                        logger("💡 變量 [<b>" + key + "</b>] = " + val);
                    }
                }
            }
            String finalUrl = replaceStepVars(playConfig.optString("final_output", "{final_url}"));
            logger("🏁 <b>[解析完成]</b>: " + finalUrl);
            return new JSONObject().put("parse", 0).put("url", finalUrl).toString();
        } catch (Exception e) { return "{\"parse\":0,\"url\":\"\"}"; }
    }

    // --- 凱哥專用：絕對不改的元素定位邏輯 ---
    private String extract(Object root, String ruleStr) {
        try {
            if (TextUtils.isEmpty(ruleStr) || root == null) return "";
            
            // 1. 處理 Jsoup 屬性提取 (例如: .title@text 或 a@href)
            if (ruleStr.contains("@") && !ruleStr.contains("&&") && root instanceof Element) {
                String[] parts = ruleStr.split("@");
                String selector = parts[0].trim();
                String attr = parts.length > 1 ? parts[1].trim() : "";
                Element el = selector.isEmpty() ? (Element) root : ((Element) root).selectFirst(selector);
                if (el == null) return "";
                if (attr.equals("text")) return el.text().trim();
                if (attr.isEmpty()) return el.text().trim();
                return el.attr(attr).trim();
            }

            // 2. 處理字符串截取 (&& 模式)
            String source = (root instanceof Element) ? ((Element) root).outerHtml() : root.toString();
            if (ruleStr.contains("&&")) {
                String[] parts = ruleStr.split("&&");
                String left = parts[0].trim();
                String right = parts.length > 1 ? parts[1].trim() : "";
                
                // 凱哥，這裡是你最核心的 * 號多級跳躍邏輯
                if (left.contains("*")) {
                    String[] anchors = left.split("\\*");
                    int pos = 0;
                    for (String anchor : anchors) {
                        int idx = source.indexOf(anchor.trim(), pos);
                        if (idx == -1) return "";
                        pos = idx + anchor.trim().length();
                    }
                    int end = source.indexOf(right.replace("[text]", "").trim(), pos);
                    return (end != -1) ? source.substring(pos, end).trim() : "";
                }
                
                // 標準前後截取
                int start = source.indexOf(left);
                if (start == -1) return "";
                start += left.length();
                int end = source.indexOf(right, start);
                return (end != -1) ? source.substring(start, end).trim() : "";
            }

            // 3. 標準 CSS 選擇器提取 Text
            if (root instanceof Element) {
                Element el = ((Element) root).selectFirst(ruleStr);
                return el != null ? el.text().trim() : "";
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

    private String replaceStepVars(String text) {
        if (text == null) return "";
        for (Map.Entry<String, String> entry : varPool.entrySet()) text = text.replace("{" + entry.getKey() + "}", entry.getValue());
        return text.replace("{host}", rule.optString("host"));
    }

    private Map<String, String> getHeaders(JSONObject customHd) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", rule.optString("ua", "Mozilla/5.0"));
        JSONObject hd = (customHd != null) ? customHd : rule.optJSONObject("headers");
        if (hd != null) {
            Iterator<String> keys = hd.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                headers.put(key, replaceStepVars(hd.optString(key)));
            }
        }
        return headers;
    }
}
