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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class KaiGe extends Spider {
    
    private JSONObject rule = new JSONObject();
    private Map<String, String> varPool = new HashMap<>();
    private final ExecutorService logExecutor = Executors.newSingleThreadExecutor();

    private void logger(String msg) {
        logExecutor.execute(() -> Proxy.log(msg));
    }

    /**
     * 分級日誌：只有播放解析才打印源碼，其他只看狀態
     */
    private void logCheck(String title, String html, boolean showSource) {
        if (TextUtils.isEmpty(html)) {
            logger("❌ [" + title + "] 請求失敗：HTML 為空");
            return;
        }
        int len = html.length();
        logger("📥 [" + title + "] 成功 | 長度: " + len + " 字節");
        if (showSource) {
            String preview = (len > 300 ? html.substring(0, 300) : html).trim().replace("\n", " ");
            logger("📄 [解析源碼預覽]: " + preview.replace("<", "&lt;").replace(">", "&gt;") + "...");
        }
    }

    @Override
    public void init(Context context, String extend) {
        try {
            logger("------------------------------------------");
            logger("🚀 <b>凱哥全能引擎啟動 (核心日誌版)</b>");
            String json = extend.startsWith("http") ? OkHttp.string(extend, null) : extend;
            this.rule = new JSONObject(json);
            logger("✅ [系統] 站點配置: " + rule.optString("site_name"));
        } catch (Exception e) {
            logger("🚨 [系統] 初始化失敗: " + e.getMessage());
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean f, HashMap<String, String> e) {
        try {
            String url = (pg.equals("1") && rule.has("cate_page_1") ? rule.optString("cate_page_1") : rule.optString("cate_url"))
                    .replace("{tid}", tid).replace("{pg}", pg);
            if (url.startsWith("/") && !url.startsWith("//")) url = rule.optString("host") + url;
            
            logger("📂 [分類] 請求網址: " + url);
            OkResult res = OkHttp.get(url, null, getHeaders(null));
            logCheck("分類", res.getBody(), false); // 不看源碼
            return parseList(res.getBody(), pg, false);
        } catch (Exception ex) { return "{\"list\":[]}"; }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String url = rule.optString("search_url").replace("{wd}", URLEncoder.encode(key, "UTF-8"));
            if (url.startsWith("/") && !url.startsWith("//")) url = rule.optString("host") + url;
            logger("🔍 [搜索] 關鍵字: " + key + " | 網址: " + url);
            OkResult res = OkHttp.get(url, null, getHeaders(null));
            logCheck("搜索", res.getBody(), false); // 不看源碼
            return parseList(res.getBody(), "1", true);
        } catch (Exception e) { return "{\"list\":[]}"; }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String id = ids.get(0);
            String url = id.startsWith("http") ? id : rule.optString("host") + (id.startsWith("/") ? "" : "/") + id;
            logger("📝 [詳情] 正在請求: " + url);
            OkResult res = OkHttp.get(url, null, getHeaders(null));
            logCheck("詳情", res.getBody(), false); // 不看源碼
            
            Document doc = Jsoup.parse(res.getBody());
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            vod.put("vod_name", extract(doc, rule.optString("dt_name")));
            vod.put("vod_pic", extract(doc, rule.optString("dt_pic")));
            vod.put("vod_remarks", extract(doc, rule.optString("dt_remarks")));
            vod.put("vod_actor", extract(doc, rule.optString("dt_actor")));
            vod.put("vod_director", extract(doc, rule.optString("dt_director")));
            vod.put("vod_content", extract(doc, rule.optString("dt_content")));
            
            Elements froms = doc.select(rule.optString("dt_from"));
            List<String> fList = new ArrayList<>();
            for (Element f : froms) fList.add(f.text().trim());
            vod.put("vod_play_from", TextUtils.join("$$$", fList));

            Elements lists = doc.select(rule.optString("dt_list"));
            List<String> pLists = new ArrayList<>();
            for (Element g : lists) {
                List<String> urls = new ArrayList<>();
                for (Element a : g.select("a")) urls.add(a.text().trim() + "$" + a.attr("href"));
                pLists.add(TextUtils.join("#", urls));
            }
            vod.put("vod_play_url", TextUtils.join("$$$", pLists));
            logger("✅ [詳情] 數據提取完畢 | 源數量: " + fList.size());
            return new JSONObject().put("list", new JSONArray().put(vod)).toString();
        } catch (Exception e) { return ""; }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String url = id.startsWith("/") && !id.startsWith("//") ? rule.optString("host") + id : id;
            logger("<br>🎬 <b>[播放解析啟動]</b>");
            
            if (!rule.has("play")) return "{\"parse\":0,\"url\":\"" + url + "\"}";

            JSONObject play = rule.getJSONObject("play");
            JSONArray steps = play.optJSONArray("steps");
            varPool.clear();
            varPool.put("play_id", url);
            
            String currentHtml = "";
            for (int i = 0; i < (steps != null ? steps.length() : 0); i++) {
                JSONObject step = steps.getJSONObject(i);
                String method = step.optString("method", "get").toLowerCase();
                String stepUrl = replaceStepVars(step.optString("url", url));
                Map<String, String> headers = getHeaders(step.optJSONObject("headers"));
                
                logger("<b>Step " + (i+1) + "</b> (" + method.toUpperCase() + "): " + stepUrl);
                logger("📤 [請求頭]: <small>" + headers.toString() + "</small>");

                OkResult res = method.equals("post") 
                    ? OkHttp.post(stepUrl, replaceStepVars(step.optString("body")), headers)
                    : OkHttp.get(stepUrl, null, headers);
                
                currentHtml = res.getBody();
                logCheck("解析 Step " + (i+1), currentHtml, true); // 解析必須看源碼

                if (step.has("vars")) {
                    JSONObject vars = step.getJSONObject("vars");
                    Iterator<String> keys = vars.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        String vRule = vars.getString(k);
                        String val = vRule.startsWith("json:") ? new JSONObject(currentHtml).optString(vRule.substring(5)) : extract(currentHtml, vRule);
                        varPool.put(k, val);
                        logger("  └ 💡 變量 [<b>" + k + "</b>] = " + val);
                    }
                }
            }

            String finalUrl = replaceStepVars(play.optString("final_output", "{final_url}"));
            JSONObject result = new JSONObject();
            boolean isMedia = finalUrl.contains(".m3u8") || finalUrl.contains(".mp4") || finalUrl.contains(".flv");

            result.put("parse", isMedia ? 0 : 1);
            result.put("url", finalUrl);
            
            JSONObject hd = new JSONObject();
            hd.put("User-Agent", rule.optString("ua", "Mozilla/5.0"));
            hd.put("Referer", url);
            hd.put("Origin", rule.optString("host"));
            result.put("header", hd);

            logger("🏁 <b>[解析完成]</b> 返回: " + finalUrl + " | Parse: " + result.optInt("parse"));
            return result.toString();
        } catch (Exception e) { return "{\"parse\":1,\"url\":\"" + id + "\"}"; }
    }

    private String parseList(String html, String pg, boolean isSearch) {
        try {
            Document doc = Jsoup.parse(html);
            JSONArray list = new JSONArray();
            String prefix = isSearch ? "sc_" : "cate_";
            Elements items = doc.select(rule.optString(prefix + "item", rule.optString("cate_item")));
            logger("📊 [列表] 提取項目數量: " + items.size());
            for (Element item : items) {
                JSONObject vod = new JSONObject();
                String vId = extract(item, rule.optString(prefix + "id", rule.optString("cate_id")));
                vod.put("vod_id", vId.startsWith("http") ? vId : rule.optString("host") + (vId.startsWith("/") ? "" : "/") + vId);
                vod.put("vod_name", extract(item, rule.optString(prefix + "name", rule.optString("cate_name"))));
                vod.put("vod_pic", extract(item, rule.optString(prefix + "pic", rule.optString("cate_pic"))));
                vod.put("vod_remarks", extract(item, rule.optString(prefix + "remarks", rule.optString("cate_remarks"))));
                list.put(vod);
            }
            return new JSONObject().put("list", list).put("page", pg).toString();
        } catch (Exception e) { return "{\"list\":[]}"; }
    }

    private String extract(Object root, String ruleStr) {
        try {
            if (TextUtils.isEmpty(ruleStr) || root == null) return "";
            String workRule = (ruleStr.contains("@") && !ruleStr.contains("&&")) ? ruleStr.replace("@", "&&") : ruleStr;
            if (workRule.contains("&&")) {
                String[] parts = workRule.split("&&");
                Element target = null;
                if (root instanceof Document) target = ((Document) root).selectFirst(parts[0].trim());
                else if (root instanceof Element) target = ((Element) root).selectFirst(parts[0].trim());
                if (target != null) {
                    String second = parts[1].trim();
                    if (isAttr(second)) return target.attr(second).trim();
                    if (second.equals("text") || second.isEmpty()) return target.text().trim();
                    return extractString(target.outerHtml(), second);
                }
                return extractString(root.toString(), workRule);
            }
            if (root instanceof Element) {
                Element el = ((Element) root).selectFirst(workRule);
                return el != null ? el.text().trim() : "";
            }
        } catch (Exception e) {}
        return "";
    }

    private boolean isAttr(String s) {
        String t = s.toLowerCase();
        return t.equals("href") || t.equals("title") || t.equals("src") || t.startsWith("data-") || t.equals("value") || t.equals("alt");
    }

    private String extractString(String content, String ruleStr) {
        try {
            if (!ruleStr.contains("&&")) return content;
            String[] p = ruleStr.split("&&");
            String left = p[0].trim();
            String right = p.length > 1 ? p[1].trim() : "";
            if (left.contains("*")) {
                String[] anchors = left.split("\\*");
                int pos = 0;
                for (String a : anchors) {
                    int idx = content.indexOf(a.trim(), pos);
                    if (idx == -1) return "";
                    pos = idx + a.trim().length();
                }
                int end = content.indexOf(right.replace("[text]", "").trim(), pos);
                return (end != -1) ? content.substring(pos, end).trim() : "";
            }
            int s = content.indexOf(left);
            if (s == -1) return "";
            s += left.length();
            int e = content.indexOf(right, s);
            return (e != -1) ? content.substring(s, e).trim() : "";
        } catch (Exception e) { return ""; }
    }

    private String replaceStepVars(String text) {
        if (text == null) return "";
        String res = text;
        for (String k : varPool.keySet()) res = res.replace("{" + k + "}", varPool.get(k));
        return res.replace("{host}", rule.optString("host"));
    }

    private Map<String, String> getHeaders(JSONObject custom) {
        Map<String, String> hb = new HashMap<>();
        hb.put("User-Agent", rule.optString("ua", "Mozilla/5.0"));
        JSONObject hd = custom != null ? custom : rule.optJSONObject("headers");
        if (hd != null) {
            Iterator<String> keys = hd.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                hb.put(k, replaceStepVars(hd.optString(k)));
            }
        }
        return hb;
    }

    @Override
    public String homeContent(boolean filter) {
        try { return new JSONObject().put("class", rule.optJSONArray("classes")).toString(); } catch (Exception e) { return ""; }
    }
}
