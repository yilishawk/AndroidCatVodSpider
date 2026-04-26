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
import com.github.catvod.utils.KaiGeEngine;

public class KaiGe extends Spider {
    private String siteUrl = ""; // 🚀 全局域名變量
    private JSONObject rule = new JSONObject();
    private Map<String, String> varPool = new HashMap<>();
    // 🚀 1. 刪除 ExecutorService 隊列，直接實時輸出
    private void logger(String msg) {
        try {
            // 不再排隊，操作到哪裡日誌就出到哪裡
            Proxy.log(msg);
        } catch (Exception e) {
            // 避免日誌報錯導致主程序卡死
        }
    }

    // 🚀 2. 暴力縮減預覽長度，解決緩衝區堵塞
    private void logCheck(String title, String html, boolean showSource) {
        if (TextUtils.isEmpty(html)) {
            logger("❌ [" + title + "] 請求失敗");
            return;
        }
        int len = html.length();
        logger("📥 [" + title + "] 成功 | " + len + " 字符");
        
        if (showSource) {
            // 以前抓 7000 字太長了，現在縮到 500 字，反應速度提升 10 倍
            String preview = (len > 500 ? html.substring(0, 500) : html)
                .trim().replace("\n", " ").replace("\r", " ");
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
            
            // --- 🚀 凱哥全能修復：【第一部分】線路與列表精準配對 ---
            String fromRule = rule.optString("dt_from");
            String listRule = rule.optString("dt_list");
            logger("🔍 [詳情診斷] 標題規則: " + fromRule + " | 列表規則: " + listRule);

            // 1. 兼容 [包含] 語法，提取真正的 CSS 標籤部分
            String cssFrom = fromRule;
            if (fromRule.contains("&&")) {
                String[] parts = fromRule.split("&&");
                // 💡 如果第一段是 [包含]，我們就取第二段作為 CSS 選擇器，否則取第一段
                cssFrom = parts[0].contains("[包含:") ? (parts.length > 1 ? parts[1] : "h3") : parts[0];
            }

            
            Elements fromElements = doc.select(cssFrom);
            logger("🔍 [詳情診斷] 找到標題數量: " + fromElements.size());

            List<String> fList = new ArrayList<>();
            List<String> pLists = new ArrayList<>();

            for (Element from : fromElements) {
                String sourceName = from.text().trim();
                if (TextUtils.isEmpty(sourceName)) sourceName = "播放線路 " + (fromElements.indexOf(from) + 1);

                // 💡 凱哥雷達：精準定位標題附近的列表
                Element nextList = null;
                Element p = from.parent(); 
                while (p != null && nextList == null) {
                    Element sibling = p.nextElementSibling();
                    while (sibling != null) {
                        nextList = sibling.selectFirst(listRule);
                        if (nextList != null) break;
                        sibling = sibling.nextElementSibling();
                    }
                    if (nextList != null) break;
                    p = p.parent();
                    if (p != null && p.tagName().equals("body")) break;
                }

                if (nextList == null) {
                    Elements allLists = doc.select(listRule);
                    int idx = fromElements.indexOf(from);
                    if (idx < allLists.size()) nextList = allLists.get(idx);
                }

                if (nextList != null) {
                    fList.add(sourceName);
                    pLists.add(nextList.outerHtml()); 
                    logger("✅ [成功] 匹配到線路: [" + sourceName + "]");
                } else {
                    logger("❌ [失敗] 標題 [" + sourceName + "] 附近找不到符合規則的列表");
                }
            }

            // --- 🚀 凱哥全能修復：【第二部分】選集解析與日誌監控 ---
            List<String> playList = new ArrayList<>();
            logger("🔍 [詳情診斷] 準備解析播放列表，總線路數: " + pLists.size());

            for (int i = 0; i < pLists.size(); i++) {
                List<String> urls = new ArrayList<>();
                Document listDoc = Jsoup.parse(pLists.get(i));
                
                String nameRule = rule.optString("dt_list_name");
                String urlRule = rule.optString("dt_list_url");
                
                Elements aElements = listDoc.select("a");
                // 💡 這裡是關鍵日誌點
                logger("   📂 線路 " + (i+1) + " [" + fList.get(i) + "] 發現 <a> 標籤數量: " + aElements.size());

                for (Element a : aElements) {
                    String pName = extract(a, nameRule); 
                    String pUrl = extract(a, urlRule);
                    
                    if (!pName.isEmpty() && !pUrl.isEmpty()) {
                        urls.add(pName + "$" + pUrl);
                    }
                }
                
                if (urls.size() > 0) {
                    logger("   🎉 [成功] 提取到有效選集: " + urls.size() + " 個 (首集: " + urls.get(0).split("\\$")[0] + ")");
                } else {
                    logger("   ⚠️ [警告] 線路 " + (i+1) + " 沒能提取出有效選集，請檢查 dt_list_name/url 規則");
                }
                playList.add(TextUtils.join("#", urls));
            }

            // 最後存入對象
            vod.put("vod_play_from", TextUtils.join("$$$", fList));
            vod.put("vod_play_url", TextUtils.join("$$$", playList));

            // --- 🚀 替換結束 ---

            return new JSONObject().put("list", new JSONArray().put(vod)).toString();
        } catch (Exception e) { 
            logger("🚨 [詳情崩潰]: " + e.getMessage());
            return ""; 
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            // ✅ 從這裡開始粘貼
            String url = id;
            if (url.startsWith("/") && !url.startsWith("//")) {
                url = this.siteUrl + url;
            } else if (!url.startsWith("http")) {
                url = this.siteUrl + (this.siteUrl.endsWith("/") ? "" : "/") + id;
            }

            logger("<br>🎬 <b>[播放解析啟動]</b>: " + url);

            // 🚀 1. 初始化絕對保底
            varPool.clear();
            varPool.put("play_id", url);
            varPool.put("final_url", url);
            int pValue = 1; 

            JSONObject play = rule.optJSONObject("play");
            if (play == null) play = new JSONObject();
            JSONArray steps = play.optJSONArray("steps");
            int actualSteps = (steps != null) ? steps.length() : 0;

            logger("🔍 [核心診斷] Step 總數: " + actualSteps + " | 引擎預設坑位: 4");

            // 🚀 2. 獨立 Step 執行序列 (每個 Step 都是全透明模塊)
            for (int i = 0; i < 4; i++) {
                if (i >= actualSteps) break;

                // 🛡️ 模塊防火牆：單個 Step 失敗不影響整體回傳
                try {
                    JSONObject step = steps.getJSONObject(i);
                    String method = step.optString("method", "get").toLowerCase();
                    String stepUrl = replaceStepVars(step.optString("url", url));
                    Map<String, String> headers = getHeaders(step.optJSONObject("headers"));

                    // --- 📢 核心日誌 1：請求頭監控 ---
                    logger("<br><b>[Step " + (i + 1) + " 請求中]</b>");
                    logger("🔗 網址: " + stepUrl);
                    logger("📤 請求頭: " + headers.toString());

                    OkResult res = method.equals("post") 
                        ? OkHttp.post(stepUrl, replaceStepVars(step.optString("body")), headers)
                        : OkHttp.get(stepUrl, null, headers);

                    String currentHtml = res.getBody();
                    
                    // --- 📢 核心日誌 2：返回數據監控 ---
                    if (TextUtils.isEmpty(currentHtml)) {
                        logger("❌ [Step " + (i + 1) + "] 返回源碼為空，請檢查網絡或請求頭！");
                    } else {
                        int len = currentHtml.length();
                        String preview = (len > 500 ? currentHtml.substring(0, 500) : currentHtml)
                                        .replace("<", "&lt;").replace(">", "&gt;").replace("\n", " ");
                        logger("📥 [返回源碼] 長度: " + len + " | 預覽: " + preview + "...");
                    }

                    // --- 📢 核心日誌 3：提取結果監控 ---
                    if (step.has("vars")) {
                        JSONObject vars = step.getJSONObject("vars");
                        Iterator<String> keys = vars.keys();
                        while (keys.hasNext()) {
                            String k = keys.next();
                            String vRule = vars.getString(k);
                            try {
                                String val = vRule.startsWith("json:") 
                                    ? new JSONObject(currentHtml).optString(vRule.substring(5)) 
                                    : extract(currentHtml, vRule);

                                if (val != null && !val.isEmpty()) {
                                    varPool.put(k, val);
                                    // 強行噴出提取到的內容，不管長短
                                    logger("  └ ✅ 提取變量 [<b>" + k + "</b>] -> 內容: " + val);
                                    if (k.equals("final_url") || k.equals("url")) varPool.put("final_url", val);
                                } else {
                                    logger("  └ ⚠️ 提取變量 [<b>" + k + "</b>] 失敗 | 規則: " + vRule);
                                }
                            } catch (Exception eVar) {
                                logger("  └ ❌ 提取變量 [<b>" + k + "</b>] 崩潰: " + eVar.getMessage());
                            }
                        }
                    } else {
                        logger("  ⚠️ Step " + (i + 1) + " 未配置 vars 提取規則");
                    }

                } catch (Exception eStep) {
                    logger("🚨 <b>[Step " + (i + 1) + " 模塊崩潰]</b>: " + eStep.getMessage());
                }
            }

            // 🚀 3. 最終判定：只要有 Step 且地址變了，就嘗試直連 (0)
            String finalUrl = varPool.get("final_url");
            if (finalUrl == null) finalUrl = url;

            boolean hasChanged = !finalUrl.equals(url);
            boolean isStream = finalUrl.toLowerCase().contains(".m3u8") || finalUrl.toLowerCase().contains(".mp4") || finalUrl.toLowerCase().contains(".flv");

            if (actualSteps > 0 && (hasChanged || isStream)) {
                pValue = 0;
            } else {
                pValue = 1; // 無論如何，最後一定會給出 pValue
            }

            JSONObject resJson = new JSONObject();
            resJson.put("parse", pValue);
            resJson.put("url", finalUrl);
            resJson.put("header", getPlayHeaders(play));
            
            String result = resJson.toString();
            logger("<br>🏁 <b>[解析結果]</b>: " + (pValue == 0 ? "直連" : "嗅探") + "<br>傳回數據: <code>" + result + "</code>");
            
            return result;


        } catch (Exception e) { 
            String finalId = id;
            if (id != null && !id.startsWith("http")) {
                String baseUrl = this.siteUrl;
                if (baseUrl != null && !baseUrl.isEmpty()) {
                    if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                    finalId = id.startsWith("/") ? (baseUrl + id) : (baseUrl + "/" + id);
                }
            }
            String errorResult = "{\"parse\":1,\"url\":\"" + finalId + "\",\"header\":{\"User-Agent\":\"Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36\",\"Referer\":\"" + this.siteUrl + "/\",\"Origin\":\"" + this.siteUrl + "\"}}";
            logger("<br><span style='color:#e74c3c;'>🚨 <b>[解析異常拋給殼子]</b></span><br>原因: " + e.getMessage() + "<br>返回: <code>" + errorResult + "</code>");
            return errorResult;
        }
    }

    // 🚀 在類末尾補上這個提取播放頭的輔助方法，保證代碼簡潔
    private JSONObject getPlayHeaders(JSONObject play) {
        JSONObject headJson = play.optJSONObject("play_headers");
        if (headJson == null) {
            headJson = new JSONObject();
            try {
                headJson.put("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36");
                headJson.put("Referer", this.siteUrl + "/");
                headJson.put("Origin", this.siteUrl);
            } catch (Exception e) {}
        }
        return headJson;
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
            
            String finalResult = "";

            // 🚀 凱哥判定法：如果規則裡「不包含」&&，則認定為標準 CSS 規則，交給 Jsoup 處理
            if (!ruleStr.contains("&&")) {
                if (root instanceof Element) {
                    Element el = (Element) root;
                    
                    // A1. 處理帶 @ 的屬性提取 (如 a@href)
                    if (ruleStr.contains("@")) {
                        String[] parts = ruleStr.split("@");
                        String selector = parts[0].trim();
                        String attr = parts[1].trim();
                        Element target = selector.isEmpty() ? el : el.selectFirst(selector);
                        finalResult = (target != null) ? target.attr(attr) : "";
                    } 
                    // A2. 處理不帶 @ 的純定位取文本 (如 span.absolute)
                    else {
                        Element target = el.selectFirst(ruleStr);
                        finalResult = (target != null) ? target.text() : "";
                    }
                }
                // logger("📡 [Jsoup 原生模式] 規則: " + ruleStr + " | 結果: " + finalResult);
            } 
            
            // 🚀 凱哥判定法：如果規則「包含」&&，則啟動全能工具 Java 進行切割
            else {
                String content = (root instanceof Document) ? ((Document) root).outerHtml() 
                               : (root instanceof Element) ? ((Element) root).outerHtml() 
                               : root.toString();

                com.github.catvod.utils.KaiGeEngine.ExtractionResult res = 
                    com.github.catvod.utils.KaiGeEngine.doExtract(content, ruleStr, this.siteUrl);
                
                finalResult = (res.value == null) ? "" : res.value;
                // logger("🔪 [工具 Java 模式] 規則: " + ruleStr + " | 結果: " + finalResult);
            }

            return finalResult;

        } catch (Exception e) {
            return "";
        }
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
