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
        Proxy.log(msg); // 對接 10086 端口，確保 Proxy 內部有 flush 邏輯
    }

    @Override
    public void init(Context context, String extend) {
        try {
            logger("------------------------------------------");
            logger("⚙️ [系統] 啟動凱哥萬能引擎 (Full Mode)...");
            String json = extend.startsWith("http") ? OkHttp.string(extend, null) : extend;
            this.rule = new JSONObject(json);
            logger("✅ [系統] 站點配置加載成功: " + rule.optString("site_name"));
        } catch (Exception e) {
            logger("🚨 [系統] 初始化失敗: " + e.getMessage());
        }
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = rule.optJSONArray("classes");
            result.put("class", classes);
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
            
            logger("📂 [分類] 正在請求: " + url);
            String html = OkHttp.string(url, getHeaders(null));
            if (TextUtils.isEmpty(html)) logger("🚨 [分類] 警告：響應 HTML 為空！");
            
            return parseList(html, pg, false);
        } catch (Exception e) {
            logger("🚨 [分類] 異常: " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            logger("🔍 [搜索] 關鍵字: " + key);
            String searchUrl = rule.optString("search_url");
            String url = searchUrl.replace("{wd}", URLEncoder.encode(key, "UTF-8"));
            if (url.startsWith("/") && !url.startsWith("//")) url = rule.optString("host") + url;
            
            logger("🔍 [搜索] 請求 URL: " + url);
            String html = OkHttp.string(url, getHeaders(null));
            return parseList(html, "1", true);
        } catch (Exception e) {
            logger("🚨 [搜索] 異常: " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String id = ids.get(0);
            String url = id.startsWith("http") ? id : rule.optString("host") + (id.startsWith("/") ? "" : "/") + id;
            logger("📝 [詳情] 正在解析內容: " + url);

            String html = OkHttp.string(url, getHeaders(null));
            Document doc = Jsoup.parse(html);
            
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            vod.put("vod_name", extract(doc, rule.optString("dt_name")));
            vod.put("vod_pic", extract(doc, rule.optString("dt_pic")));
            vod.put("vod_remarks", extract(doc, rule.optString("dt_remarks")));
            vod.put("vod_actor", extract(doc, rule.optString("dt_actor")));
            vod.put("vod_director", extract(doc, rule.optString("dt_director")));
            vod.put("vod_content", extract(doc, rule.optString("dt_content")));

            // 播放源 (From)
            Elements froms = doc.select(rule.optString("dt_from"));
            List<String> fList = new ArrayList<>();
            for (Element f : froms) fList.add(f.text().trim());
            vod.put("vod_play_from", TextUtils.join("$$$", fList));

            // 播放列表 (List)
            Elements urlGroups = doc.select(rule.optString("dt_list"));
            List<String> pLists = new ArrayList<>();
            for (Element g : urlGroups) {
                List<String> urls = new ArrayList<>();
                Elements links = g.select("a");
                for (Element a : links) {
                    urls.add(a.text().trim() + "$" + a.attr("href"));
                }
                pLists.add(TextUtils.join("#", urls));
            }
            vod.put("vod_play_url", TextUtils.join("$$$", pLists));
            
            logger("✅ [詳情] 內容提取完成: " + vod.optString("vod_name"));
            return new JSONObject().put("list", new JSONArray().put(vod)).toString();
        } catch (Exception e) {
            logger("🚨 [詳情] 異常: " + e.getMessage());
            return "";
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String url = id.startsWith("/") && !id.startsWith("//") ? rule.optString("host") + id : id;
            logger("<br>🚀 <b>[播放解析]</b>啟動路徑: " + url);
            
            if (!rule.has("play")) return "{\"parse\":0,\"url\":\"" + url + "\"}";

            JSONObject play = rule.getJSONObject("play");
            JSONArray steps = play.optJSONArray("steps");
            varPool.clear();
            varPool.put("play_id", url);
            
            String currentHtml = "";
            for (int i = 0; i < (steps != null ? steps.length() : 0); i++) {
                JSONObject step = steps.getJSONObject(i);
                String method = step.optString("method", "get");
                String stepUrl = replaceStepVars(step.optString("url", url));
                Map<String, String> headers = getHeaders(step.optJSONObject("headers"));
                
                logger("<b>Step " + (i+1) + "</b> (" + method.toUpperCase() + "): " + stepUrl);

                OkResult res;
                if (method.equalsIgnoreCase("post")) {
                    res = OkHttp.post(stepUrl, replaceStepVars(step.optString("body")), headers);
                } else {
                    res = OkHttp.get(stepUrl, null, headers); // 補齊三參數
                }
                currentHtml = res.getBody();
                
                if (step.has("vars")) {
                    JSONObject vars = step.getJSONObject("vars");
                    Iterator<String> keys = vars.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        String vRule = vars.getString(k);
                        String val = vRule.startsWith("json:") ? new JSONObject(currentHtml).optString(vRule.substring(5)) : extract(currentHtml, vRule);
                        varPool.put(k, val);
                        logger("  └ 💡 提取變量 [<b>" + k + "</b>] = " + val);
                    }
                }
            }

            String finalUrl = replaceStepVars(play.optString("final_output", "{final_url}"));
            
            // --- 🤖 智能判定返回格式 ---
            JSONObject result = new JSONObject();
            boolean isDirect = finalUrl.contains(".m3u8") || finalUrl.contains(".mp4") || finalUrl.contains(".flv") || finalUrl.contains("video_download");

            if (!TextUtils.isEmpty(finalUrl) && isDirect) {
                logger("🏁 <b>[成功]</b> 取得直連，內部播放。");
                result.put("parse", 0); 
            } else {
                logger("⚠️ <b>[跳轉]</b> 未取得直連地址，交由殼子嗅探。");
                result.put("parse", 1); 
            }

            result.put("url", finalUrl);
            
            // 補齊 Headers (UA, Referer, Origin)
            JSONObject playerHeaders = new JSONObject();
            playerHeaders.put("User-Agent", rule.optString("ua", "Mozilla/5.0"));
            playerHeaders.put("Referer", url); 
            playerHeaders.put("Origin", rule.optString("host"));
            result.put("header", playerHeaders);

            return result.toString();
        } catch (Exception e) {
            logger("🚨 [解析] 出錯: " + e.getMessage());
            return "{\"parse\":1,\"url\":\"" + id + "\"}";
        }
    }

    // --- 凱哥萬能提取引擎：支持 @, &&, *, CSS 多種樣式 ---
    private String extract(Object root, String ruleStr) {
        try {
            if (TextUtils.isEmpty(ruleStr) || root == null) return "";
            
            // 兼容 a.lazyload@data-original 寫法
            String workRule = (ruleStr.contains("@") && !ruleStr.contains("&&")) ? ruleStr.replace("@", "&&") : ruleStr;

            if (workRule.contains("&&")) {
                String[] parts = workRule.split("&&");
                String first = parts[0].trim();
                String second = parts[1].trim();

                Element target = null;
                if (root instanceof Document) target = ((Document) root).selectFirst(first);
                else if (root instanceof Element) target = ((Element) root).selectFirst(first);

                if (target != null) {
                    if (isAttr(second)) return target.attr(second).trim();
                    if (second.equals("text") || second.isEmpty()) return target.text().trim();
                    return extractString(target.outerHtml(), second);
                } else {
                    // CSS 定位不到，嘗試全源碼字符串截取
                    return extractString(root.toString(), workRule);
                }
            }

            // 純 CSS 定位提取 Text
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

    private String parseList(String html, String pg, boolean isSearch) {
        try {
            Document doc = Jsoup.parse(html);
            JSONArray list = new JSONArray();
            String prefix = isSearch ? "sc_" : "cate_";
            
            String itemSelector = rule.optString(prefix + "item", rule.optString("cate_item"));
            Elements items = doc.select(itemSelector);
            
            for (Element item : items) {
                JSONObject vod = new JSONObject();
                String vId = extract(item, rule.optString(prefix + "id", rule.optString("cate_id")));
                if (!vId.startsWith("http") && !vId.startsWith("//")) {
                    vId = rule.optString("host") + (vId.startsWith("/") ? "" : "/") + vId;
                }
                vod.put("vod_id", vId);
                vod.put("vod_name", extract(item, rule.optString(prefix + "name", rule.optString("cate_name"))));
                vod.put("vod_pic", extract(item, rule.optString(prefix + "pic", rule.optString("cate_pic"))));
                vod.put("vod_remarks", extract(item, rule.optString(prefix + "remarks", rule.optString("cate_remarks"))));
                list.put(vod);
            }
            return new JSONObject().put("list", list).put("page", pg).toString();
        } catch (Exception e) { return "{\"list\":[]}"; }
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
}
