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
import java.util.*;

public class KaiGe extends Spider {
    private JSONObject rule = new JSONObject();
    private Map<String, String> varPool = new HashMap<>();

    private void logger(String msg) {
        // 確保每一條日誌都帶着綠色的標識，實時推送到你的調試頁面
        Proxy.log("[KG] " + msg);
    }

    @Override
    public void init(Context context, String extend) {
        try {
            String json = extend.startsWith("http") ? OkHttp.string(extend, null) : extend;
            this.rule = new JSONObject(json);
            logger("🚀 [初始化] 站點引擎已就緒: " + rule.optString("site_name"));
        } catch (Exception e) {
            logger("🚨 [初始化失敗]: " + e.getMessage());
        }
    }

    @Override
    public String homeContent(boolean filter) {
        logger("🏠 [動作] 訪問首頁 (homeContent)");
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
            if (extend != null) for (String key : extend.keySet()) url = url.replace("{" + key + "}", extend.get(key));
            if (url.startsWith("/") && !url.startsWith("//")) url = rule.optString("host") + url;
            
            logger("📂 [動作] 分類請求: " + url);
            String html = OkHttp.string(url, getHeaders(null));
            return parseList(html, pg, false);
        } catch (Exception e) { return ""; }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String id = ids.get(0);
            String url = id.startsWith("http") ? id : rule.optString("host") + (id.startsWith("/") ? "" : "/") + id;
            logger("📝 [動作] 進入詳情頁: " + url);
            
            String html = OkHttp.string(url, getHeaders(null));
            Document doc = Jsoup.parse(html);
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            
            // --- 逐行提取並打印日誌，不精簡字段 ---
            String name = extract(doc, rule.optString("dt_name"));
            String actor = extract(doc, rule.optString("dt_actor"));
            String director = extract(doc, rule.optString("dt_director"));
            String content = extract(doc, rule.optString("dt_content"));
            
            logger("💎 標題: " + name);
            logger("🎭 演員: " + actor);
            logger("🎬 導演: " + director);
            logger("📄 簡介: " + (content.length() > 50 ? content.substring(0, 50) + "..." : content));

            vod.put("vod_name", name);
            vod.put("vod_pic", extract(doc, rule.optString("dt_pic")));
            vod.put("vod_actor", actor);
            vod.put("vod_director", director);
            vod.put("vod_content", content);
            vod.put("vod_remarks", extract(doc, rule.optString("dt_remarks")));
            vod.put("vod_area", extract(doc, rule.optString("dt_area")));
            vod.put("vod_year", extract(doc, rule.optString("dt_year")));
            
            Elements froms = doc.select(rule.optString("dt_from"));
            List<String> fromList = new ArrayList<>();
            for (Element f : froms) fromList.add(f.text().trim());
            vod.put("vod_play_from", TextUtils.join("$$$", fromList));

            Elements urlLists = doc.select(rule.optString("dt_list"));
            List<String> circuits = new ArrayList<>();
            for (Element list : urlLists) {
                List<String> urls = new ArrayList<>();
                for (Element a : list.select("a")) {
                    urls.add(a.text().trim() + "$" + a.attr("href"));
                }
                circuits.add(TextUtils.join("#", urls));
            }
            vod.put("vod_play_url", TextUtils.join("$$$", circuits));
            return new JSONObject().put("list", new JSONArray().put(vod)).toString();
        } catch (Exception e) { return ""; }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        // --- 🛡️ 凱哥，這是最關鍵的「死命令」攔截，防止日誌地址跳轉 ---
        if (id != null && id.contains("kaige_debug")) {
            logger("🛠️ [系統] 攔截日誌請求，強制靜態返回，已終結跳轉。");
            return "{\"parse\":0,\"url\":\"\",\"js\":\"\"}";
        }

        try {
            String url = id;
            if (url.startsWith("/") && !url.startsWith("//")) url = rule.optString("host") + url;
            logger("🎬 [動作] 播放解析: " + url);

            if (!rule.has("play") || !rule.getJSONObject("play").has("steps")) return quickResult(url, 0);

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

                logger("🚀 [Step " + (i+1) + "] " + method.toUpperCase() + ": " + stepUrl);
                logger("📑 [Headers]: " + new JSONObject(headers).toString());

                if (method.equals("extract")) {
                    currentHtml = OkHttp.string(stepUrl, headers);
                } else if (method.contains("post")) {
                    currentHtml = OkHttp.post(stepUrl, replaceStepVars(step.optString("body")), headers).getBody();
                } else {
                    currentHtml = OkHttp.string(stepUrl, headers);
                }

                String logResp = currentHtml.length() > 500 ? currentHtml.substring(0, 500) + "..." : currentHtml;
                logger("📥 [Step " + (i+1) + " Resp]: " + logResp);

                if (step.has("vars")) {
                    JSONObject vars = step.getJSONObject("vars");
                    Iterator<String> keys = vars.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String vRule = vars.getString(key);
                        String val = vRule.startsWith("json:") ? new JSONObject(currentHtml).optString(vRule.substring(5)) : extract(currentHtml, vRule);
                        varPool.put(key, val);
                        logger((TextUtils.isEmpty(val) ? "❌ [提取失敗]: " : "💎 [提取成功]: ") + key + " = " + val);
                    }
                }
            }

            String finalUrl = replaceStepVars(playConfig.optString("final_output", "{final_url}"));
            if (TextUtils.isEmpty(finalUrl) || finalUrl.contains("{")) return quickResult(url, 1);
            if (finalUrl.startsWith("/") && !finalUrl.startsWith("//")) finalUrl = rule.optString("host") + finalUrl;

            logger("🏁 [最終地址]: " + finalUrl);
            return new JSONObject().put("parse", 0).put("url", finalUrl).toString();
        } catch (Exception e) { return quickResult(id, 1); }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String url = rule.optString("search_url").replace("{wd}", key);
            if (url.startsWith("/") && !url.startsWith("//")) url = rule.optString("host") + url;
            logger("🔍 [動作] 搜索關鍵詞: " + key);
            String html = OkHttp.string(url, getHeaders(null));
            return parseList(html, "1", true);
        } catch (Exception e) { return "{\"list\":[]}"; }
    }

    // --- 核心提取算法，支持 @ 和 && ---
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
                vod.put("vod_id", extract(item, rule.optString(prefix + "id", rule.optString("cate_id"))));
                vod.put("vod_name", extract(item, rule.optString(prefix + "name", rule.optString("cate_name"))));
                vod.put("vod_pic", extract(item, rule.optString(prefix + "pic", rule.optString("cate_pic"))));
                list.put(vod);
            }
            return new JSONObject().put("list", list).put("page", pg).toString();
        } catch (Exception e) { return "{\"list\":[]}"; }
    }

    private String quickResult(String url, int p) {
        logger("⚠️ [結果] parse=" + p + " | URL=" + url);
        try { return new JSONObject().put("parse", p).put("url", url).toString(); } catch (Exception e) { return ""; }
    }

    private String replaceStepVars(String t) {
        if (t == null) return "";
        for (String k : varPool.keySet()) t = t.replace("{" + k + "}", varPool.get(k));
        return t.replace("{host}", rule.optString("host"));
    }

    private Map<String, String> getHeaders(JSONObject c) {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", rule.optString("ua", "Mozilla/5.0"));
        JSONObject hd = c != null ? c : rule.optJSONObject("headers");
        if (hd != null) {
            Iterator<String> ks = hd.keys();
            while (ks.hasNext()) {
                String k = ks.next();
                h.put(k, replaceStepVars(hd.optString(k)));
            }
        }
        return h;
    }
}
