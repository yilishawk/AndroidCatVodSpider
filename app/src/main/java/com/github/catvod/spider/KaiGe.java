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

    // 🌟 凱哥，這裡絕對不省，每一條日誌都往你的 10086 端口送
    private void logger(String msg) {
        Proxy.log(msg);
    }

    @Override
    public void init(Context context, String extend) {
        try {
            logger("🛠️ [初始化] 正在加載擴展配置...");
            String json = "";
            if (extend.startsWith("http")) {
                json = OkHttp.string(extend, null);
            } else {
                json = extend;
            }
            this.rule = new JSONObject(json);
            logger("✅ [初始化成功] 當前站點: " + rule.optString("site_name"));
        } catch (Exception e) {
            logger("🚨 [初始化失敗]: " + e.getMessage());
        }
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            logger("🏠 [首頁] 獲取分類列表");
            JSONObject result = new JSONObject();
            JSONArray classes = rule.optJSONArray("classes");
            result.put("class", classes);
            if (rule.has("filter")) {
                result.put("filters", rule.optJSONObject("filter"));
            }
            return result.toString();
        } catch (Exception e) {
            logger("🚨 [首頁錯誤]: " + e.getMessage());
            return "";
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            String cateUrl = rule.optString("cate_url");
            if (pg.equals("1") && rule.has("cate_page_1")) {
                cateUrl = rule.optString("cate_page_1");
            }
            String url = cateUrl.replace("{tid}", tid).replace("{pg}", pg);
            if (url.startsWith("/") && !url.startsWith("//")) {
                url = rule.optString("host") + url;
            }

            logger("📂 [分類請求] tid: " + tid + " | 頁碼: " + pg + " | URL: " + url);
            String html = OkHttp.string(url, getHeaders(null));
            if (TextUtils.isEmpty(html)) {
                logger("❌ [分類響應] 為空，請檢查網絡或 UA");
                return "{\"list\":[]}";
            }
            return parseList(html, pg, false);
        } catch (Exception e) {
            logger("🚨 [分類異常]: " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            logger("🔍 [搜索開始] 關鍵字: " + key);
            String searchUrl = rule.optString("search_url");
            String url = searchUrl.replace("{wd}", URLEncoder.encode(key, "UTF-8"));
            if (url.startsWith("/") && !url.startsWith("//")) {
                url = rule.optString("host") + url;
            }

            String html = OkHttp.string(url, getHeaders(null));
            return parseList(html, "1", true);
        } catch (Exception e) {
            logger("🚨 [搜索異常]: " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String id = ids.get(0);
            String url = id;
            if (!id.startsWith("http")) {
                url = rule.optString("host") + (id.startsWith("/") ? "" : "/") + id;
            }
            logger("📝 [詳情請求] 正在解析: " + url);

            String html = OkHttp.string(url, getHeaders(null));
            Document doc = Jsoup.parse(html);
            
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            
            String name = extract(doc, rule.optString("dt_name"));
            logger("💎 [詳情提取] 標題: " + name);
            vod.put("vod_name", name);
            vod.put("vod_pic", extract(doc, rule.optString("dt_pic")));
            vod.put("vod_actor", extract(doc, rule.optString("dt_actor")));
            vod.put("vod_director", extract(doc, rule.optString("dt_director")));
            vod.put("vod_content", extract(doc, rule.optString("dt_content")));

            // 解析播放源列表
            Elements froms = doc.select(rule.optString("dt_from"));
            List<String> fromList = new ArrayList<>();
            for (Element f : froms) {
                fromList.add(f.text().trim());
            }
            vod.put("vod_play_from", TextUtils.join("$$$", fromList));

            // 解析播放地址列表
            Elements urlLists = doc.select(rule.optString("dt_list"));
            List<String> circuits = new ArrayList<>();
            for (Element list : urlLists) {
                List<String> urls = new ArrayList<>();
                Elements links = list.select("a");
                for (Element a : links) {
                    urls.add(a.text().trim() + "$" + a.attr("href"));
                }
                circuits.add(TextUtils.join("#", urls));
            }
            vod.put("vod_play_url", TextUtils.join("$$$", circuits));

            JSONArray list = new JSONArray();
            list.put(vod);
            return new JSONObject().put("list", list).toString();
        } catch (Exception e) {
            logger("🚨 [詳情異常]: " + e.getMessage());
            return "";
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String url = id;
            if (url.startsWith("/") && !url.startsWith("//")) {
                url = rule.optString("host") + url;
            }
            
            logger("<br>🚀 <b>[播放路徑解析]</b>");
            logger("📍 源地址: " + url);

            if (!rule.has("play")) {
                logger("⚠️ 無解析步驟，直接輸出原始地址");
                return "{\"parse\":0,\"url\":\"" + url + "\"}";
            }

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
                    String body = replaceStepVars(step.optString("body"));
                    logger("📤 POST Body: " + body);
                    result = OkHttp.post(stepUrl, body, headers);
                } else {
                    result = OkHttp.get(stepUrl, headers);
                }
                
                currentHtml = result.getBody();
                if (TextUtils.isEmpty(currentHtml)) {
                    logger("❌ <span style='color:red;'>響應為空，終止解析</span>");
                    return "{\"parse\":0,\"url\":\"\"}";
                }

                // 打印響應片段，方便分析 HTML
                String snap = currentHtml.length() > 300 ? currentHtml.substring(0, 300) : currentHtml;
                logger("📥 響應內容預覽: <small>" + snap.replace("<", "&lt;").replace(">", "&gt;") + "...</small>");

                // 變量提取
                if (step.has("vars")) {
                    JSONObject vars = step.getJSONObject("vars");
                    Iterator<String> keys = vars.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String vRule = vars.getString(key);
                        String val = "";
                        if (vRule.startsWith("json:")) {
                            val = new JSONObject(currentHtml).optString(vRule.substring(5));
                        } else {
                            val = extract(currentHtml, vRule);
                        }
                        varPool.put(key, val);
                        logger("💡 提取到 [<b>" + key + "</b>] = " + val);
                    }
                }
            }

            String finalUrl = replaceStepVars(playConfig.optString("final_output", "{final_url}"));
            logger("🏁 <b>[解析成功]</b> 輸出地址: <a href='" + finalUrl + "'>" + finalUrl + "</a>");
            
            JSONObject result = new JSONObject();
            result.put("parse", 0);
            result.put("url", finalUrl);
            return result.toString();

        } catch (Exception e) {
            logger("🚨 [解析異常]: " + e.getMessage());
            return "{\"parse\":0,\"url\":\"\"}";
        }
    }

    // --- 核心解析工具 (不精簡版) ---

    private String extract(Object root, String ruleStr) {
        try {
            if (TextUtils.isEmpty(ruleStr) || root == null) return "";
            
            // 如果是 Jsoup 的鏈式提取 @attr
            if (ruleStr.contains("@") && !ruleStr.contains("&&") && root instanceof Element) {
                String[] parts = ruleStr.split("@");
                String selector = parts[0].trim();
                String attr = parts.length > 1 ? parts[1].trim() : "";
                Element el = selector.isEmpty() ? (Element) root : ((Element) root).selectFirst(selector);
                if (el == null) return "";
                return attr.isEmpty() ? el.text().trim() : el.attr(attr).trim();
            }

            // 如果是字符串截取 &&
            String source = (root instanceof Element) ? ((Element) root).outerHtml() : root.toString();
            if (ruleStr.contains("&&")) {
                String[] parts = ruleStr.split("&&");
                String left = parts[0].trim();
                String right = parts.length > 1 ? parts[1].trim() : "";
                
                // 支持 * 號跳躍截取
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

            // 標準 CSS 選擇器
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
            
            String itemRule = rule.optString(prefix + "item");
            Elements items = doc.select(itemRule);
            
            for (Element item : items) {
                JSONObject vod = new JSONObject();
                String vId = extract(item, rule.optString(prefix + "id"));
                if (!vId.startsWith("http") && !vId.startsWith("//")) {
                    vId = rule.optString("host") + (vId.startsWith("/") ? "" : "/") + vId;
                }
                vod.put("vod_id", vId);
                vod.put("vod_name", extract(item, rule.optString(prefix + "name")));
                vod.put("vod_pic", extract(item, rule.optString(prefix + "pic")));
                list.put(vod);
            }
            
            JSONObject result = new JSONObject();
            result.put("list", list);
            result.put("page", pg);
            return result.toString();
        } catch (Exception e) {
            return "{\"list\":[]}";
        }
    }

    private String replaceStepVars(String text) {
        if (text == null) return "";
        String result = text;
        for (Map.Entry<String, String> entry : varPool.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        result = result.replace("{host}", rule.optString("host"));
        return result;
    }

    private Map<String, String> getHeaders(JSONObject customHd) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", rule.optString("ua", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"));
        
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
