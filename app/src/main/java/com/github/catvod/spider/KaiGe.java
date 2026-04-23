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
    private String siteUrl = ""; // 🚀 全局域名變量
    private JSONObject rule = new JSONObject();
    private Map<String, String> varPool = new HashMap<>();
    private final ExecutorService logExecutor = Executors.newSingleThreadExecutor();

    private void logger(String msg) {
        logExecutor.execute(() -> Proxy.log(msg));
    }

    private void logCheck(String title, String html, boolean showSource) {
        if (TextUtils.isEmpty(html)) {
            logger("❌ [" + title + "] 請求失敗：HTML 為空");
            return;
        }
        int len = html.length();
        logger("📥 [" + title + "] 成功 | 長度: " + len + " 字節");
        if (showSource) {
            String preview = (len > 1000 ? html.substring(0, 1000) : html).trim().replace("\n", " ");
            logger("📄 [源碼預覽]: " + preview.replace("<", "&lt;").replace(">", "&gt;") + "...");
        }
    }

@Override
    public void init(Context context, String extend) {
        try {
            logger("------------------------------------------");
            logger("🚀❤️ <b>凱哥全能獨立引擎啟動 (Full Power)...</b>");
            String json = extend.startsWith("http") ? OkHttp.string(extend, null) : extend;
            this.rule = new JSONObject(json);
            
            // 🚀 從配置中自動提取域名，適配所有網站
            this.siteUrl = rule.optString("site_url", rule.optString("host", ""));
            
            logger("✅ [系統] 站點配置加載完成: " + rule.optString("site_name"));
            logger("🌐 [系統] 域名自動綁定: " + this.siteUrl);
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
            logCheck("分類", res.getBody(), false);
            return parseList(res.getBody(), pg, false);
        } catch (Exception ex) { return "{\"list\":[]}"; }
    }

@Override
    public String searchContent(String key, boolean quick) {
        try {
            String url = rule.optString("search_url").replace("{wd}", URLEncoder.encode(key, "UTF-8"));
            
            if (url.contains("{host}")) {
                url = url.replace("{host}", this.siteUrl);
            } 
            else if (url.startsWith("/") && !url.startsWith("//") && !url.contains("http")) {
                String baseUrl = this.siteUrl;
                if (baseUrl.endsWith("/")) {
                    baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                }
                url = baseUrl + url;
            }

            logger("🔍 [搜索] 關鍵字: " + key + " | 網址: " + url);
            
            OkResult res = OkHttp.get(url, null, getHeaders(null));
            logCheck("搜索", res.getBody(), false);
            
            return parseList(res.getBody(), "1", true);
        } catch (Exception e) { 
            logger("🚨 [搜索異常]: " + e.getMessage());
            return "{\"list\":[]}"; 
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String id = ids.get(0);
            String url = id.startsWith("http") ? id : rule.optString("host") + (id.startsWith("/") ? "" : "/") + id;
            logger("📝 [詳情] 正在解析內容: " + url);
            OkResult res = OkHttp.get(url, null, getHeaders(null));
            logCheck("詳情", res.getBody(), false);
            
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
            // 精確子定位規則
            String listNameRule = rule.optString("dt_list_name", "a");
            String listUrlRule = rule.optString("dt_list_url", "a@href");

            for (int i = 0; i < lists.size(); i++) {
                Element group = lists.get(i);
                List<String> urls = new ArrayList<>();
                Elements items = group.select("a"); 
                for (Element item : items) {
                    String name = extract(item, listNameRule);
                    String link = extract(item, listUrlRule);
                    if (!TextUtils.isEmpty(name) && !TextUtils.isEmpty(link)) {
                        urls.add(name + "$" + link);
                    }
                }
                String source = i < fList.size() ? fList.get(i) : "線路" + (i + 1);
                logger("✅ [詳情] 線路 [" + source + "] 成功提取選集: " + urls.size() + " 個");
                pLists.add(TextUtils.join("#", urls));
            }
            vod.put("vod_play_url", TextUtils.join("$$$", pLists));
            return new JSONObject().put("list", new JSONArray().put(vod)).toString();
        } catch (Exception e) { return ""; }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String url = id.startsWith("/") && !id.startsWith("//") ? rule.optString("host") + id : id;
            logger("<br>🎬 <b>[播放解析啟動]</b>: " + url);
            if (!rule.has("play")) return "{\"parse\":0,\"url\":\"" + url + "\"}";
            
            JSONObject play = rule.getJSONObject("play");
            JSONArray steps = play.optJSONArray("steps");
            varPool.clear();
            varPool.put("play_id", url);
            varPool.put("final_url", url); // 默認初始值

            // --- 🚀 步驟解析循環 ---
            int stepCount = (steps != null ? steps.length() : 0);
            String currentHtml = "";

            for (int i = 0; i < stepCount; i++) {
                JSONObject step = steps.getJSONObject(i);
                String method = step.optString("method", "get").toLowerCase();
                String stepUrl = replaceStepVars(step.optString("url", url));
                Map<String, String> headers = getHeaders(step.optJSONObject("headers"));

                logger("<b>Step " + (i+1) + "</b> (" + method.toUpperCase() + "): " + stepUrl);

                // 打印請求頭摺疊日誌
                StringBuilder hdLog = new StringBuilder("<details style='margin:5px 0;'><summary style='color:#0077ff;font-size:11px;cursor:pointer;'>📤 點擊查看請求頭 (Headers)</summary><div style='color:#666;font-size:10px;padding:5px;background:#f9f9f9;border-left:2px solid #0077ff;margin-top:5px;'>");
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    hdLog.append("<b>").append(entry.getKey()).append(":</b> ").append(entry.getValue()).append("<br>");
                }
                hdLog.append("</div></details>");
                logger(hdLog.toString());
                
                OkResult res = method.equals("post") 
                    ? OkHttp.post(stepUrl, replaceStepVars(step.optString("body")), headers)
                    : OkHttp.get(stepUrl, null, headers);
                
                currentHtml = res.getBody();
                logCheck("解析 Step " + (i+1), currentHtml, true);

                if (step.has("vars")) {
                    JSONObject vars = step.getJSONObject("vars");
                    Iterator<String> keys = vars.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        String vRule = vars.getString(k);
                        String val = vRule.startsWith("json:") ? new JSONObject(currentHtml).optString(vRule.substring(5)) : extract(currentHtml, vRule);
                        varPool.put(k, val);
                        // 🚀 關鍵：如果是最終地址，自動更新
                        if (k.equals("final_url") || k.equals("url")) varPool.put("final_url", val);
                        logger("  └ 💡 提取變量 [<b>" + k + "</b>] = " + val);
                    }
                }
            }

             // --- 🚀 最終返回邏輯（凱哥嚴格精準版） ---
            String finalUrl = varPool.get("final_url");
            
            // 1. 檢測是否為直連格式 (明顯後綴)
            boolean hasStreamExt = finalUrl.toLowerCase().contains(".m3u8") || 
                                   finalUrl.toLowerCase().contains(".mp4") || 
                                   finalUrl.toLowerCase().contains(".flv");

            // 2. 檢測地址是否被解析「有效變更」
            // 只有 finalUrl 不等於原始 id，說明 steps 真的提取到了新東西
            boolean isUrlChanged = !finalUrl.equals(url) && !finalUrl.equals(id);

            String resultTemplate = play.optString("final_output", "");
            String result;

            if (!resultTemplate.isEmpty()) {
                result = replaceStepVars(resultTemplate);
            } else {
                int pValue;
                
                // 🚀 凱哥邏輯核心判斷
                if (hasStreamExt) {
                    // 如果地址長得像流（有 m3u8），直接播
                    pValue = 0;
                } else if (stepCount > 0 && isUrlChanged) {
                    // 如果有步驟且地址變了（解析成功），哪怕沒後綴也直接播
                    pValue = 0;
                } else {
                    // 沒寫步驟、解析沒動、且沒後綴（即原始網頁），強制嗅探！
                    pValue = 1;
                }

                JSONObject resJson = new JSONObject();
                resJson.put("parse", pValue);
                resJson.put("url", finalUrl);
                
                // 確保嗅探時帶上 Headers
                JSONObject headJson = play.optJSONObject("play_headers");
                if (headJson == null) {
                    headJson = new JSONObject();
                    headJson.put("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36");
                    headJson.put("Referer", this.siteUrl + "/");
                    headJson.put("Origin", this.siteUrl);
                }
                resJson.put("header", headJson);
                result = resJson.toString();
            }

            // 🏁 成功日誌（0 還是 1 一目了然）
            logger("<br><span style='color:#16a085;'>🏁 <b>[解析成功返回殼子]</b></span><br><code style='color:#2980b9;'>" + result + "</code>");
            return result;

    } catch (Exception e) { // 🚀 這個 } 閉合的是 try，必須緊貼在 catch 前面

            // 🚀 1. 智能補全域名：如果 id 不帶 http，自動利用 siteUrl 補全
            String finalId = id;
            if (id != null && !id.startsWith("http")) {
                String baseUrl = this.siteUrl;
                if (baseUrl != null && !baseUrl.isEmpty()) {
                    // 去掉 baseUrl 末尾的斜槓
                    if (baseUrl.endsWith("/")) {
                        baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                    }
                    // 確保 id 開頭有斜槓，然後拼接
                    finalId = id.startsWith("/") ? (baseUrl + id) : (baseUrl + "/" + id);
                }
            }

            // 🚀 2. 封裝標準的失敗返回格式（parse: 1）              
            // 🚀 失敗保底返回：加入了 Referer 和 Origin
            String errorResult = "{\"parse\":1,\"url\":\"" + finalId + "\",\"header\":{\"User-Agent\":\"Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36\",\"Referer\":\"" + this.siteUrl + "/\",\"Origin\":\"" + this.siteUrl + "\"}}";

            // 🚀 3. 輸出強化日誌
            logger("<br><span style='color:#e74c3c;'>🚨 <b>[解析異常/失敗兜底]</b></span><br>原因: " + e.getMessage() + "<br>返回: <code>" + errorResult + "</code>");
            
            return errorResult;
        }
    }

    private String parseList(String html, String pg, boolean isSearch) {
        try {
            Document doc = Jsoup.parse(html);
            JSONArray list = new JSONArray();
            String prefix = isSearch ? "sc_" : "cate_";
            Elements items = doc.select(rule.optString(prefix + "item", rule.optString("cate_item")));
            logger("📊 [列表] 成功提取項目數量: " + items.size());
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
            String workRule = ruleStr.replace("@", "&&");
            if (workRule.contains("&&")) {
                String[] parts = workRule.split("&&");
                Element target = (root instanceof Document) ? ((Document) root).selectFirst(parts[0].trim()) : ((Element) root).selectFirst(parts[0].trim());
                if (target != null) {
                    String second = parts[1].trim();
                    if (isAttr(second)) return target.attr(second).trim();
                    if (second.equals("text") || second.isEmpty()) return target.text().trim();
                    return extractString(target.outerHtml(), second);
                }
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
        return t.equals("href") || t.equals("title") || t.equals("src") || t.startsWith("data-") || t.equals("value");
    }

    private String extractString(String content, String ruleStr) {
        try {
            if (!ruleStr.contains("&&")) return content;
            String[] p = ruleStr.split("&&");
            int s = content.indexOf(p[0].trim());
            if (s == -1) return "";
            s += p[0].trim().length();
            int e = content.indexOf(p[1].trim(), s);
            return (e != -1) ? content.substring(s, e).trim() : "";
        } catch (Exception e) { return ""; }
    }

    private String replaceStepVars(String text) {
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
