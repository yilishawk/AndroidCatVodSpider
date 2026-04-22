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

    private String extract(Object root, String ruleStr) {
        try {
            if (TextUtils.isEmpty(ruleStr) || root == null) return "";
            
            // 支持 @ 屬性提取
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

                // 核心：支持 * 星號模糊匹配 (var player_aaaa=*url\":\"&&\")
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
        } catch (Exception e) {
            Proxy.log("⚠️ 解析片段出錯: " + ruleStr);
        }
        return "";
    }

    private String buildUrl(String format) {
        if (format == null) return "";
        if (!format.contains("+") && !format.contains("{")) {
            return format.replace("{host}", rule.optString("host"));
        }
        try {
            StringBuilder sb = new StringBuilder();
            String[] parts = format.split("\\+");
            for (String p : parts) {
                p = p.trim();
                if (p.startsWith("{") && p.endsWith("}")) {
                    String key = p.substring(1, p.length() - 1);
                    String val = varPool.getOrDefault(key, "");
                    sb.append(URLEncoder.encode(val, "UTF-8"));
                } else {
                    sb.append(p.replace("'", "").replace("{host}", rule.optString("host")));
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return format;
        }
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            result.put("class", rule.optJSONArray("classes"));
            if (rule.has("filter")) result.put("filters", rule.optJSONObject("filter"));
            // 實時 JSON 日誌
            Proxy.log("🏠 [首頁加載數據]:\n" + result.toString(4));
            return result.toString();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            String cateUrl = pg.equals("1") && rule.has("cate_page_1") ? rule.optString("cate_page_1") : rule.optString("cate_url");
            String url = cateUrl.replace("{tid}", tid).replace("{pg}", pg);
            if (extend != null) {
                for (String key : extend.keySet()) url = url.replace("{" + key + "}", extend.get(key));
            }
            url = url.replaceAll("\\{.*?\\}", ""); 
            if (url.startsWith("/") && !url.startsWith("//")) url = rule.optString("host") + url;
            
            Proxy.log("📂 [分類請求] tid=" + tid + ", pg=" + pg + " | URL: " + url);
            String html = OkHttp.string(url, getHeaders(null));
            String result = parseList(html, pg, false);
            // 實時 JSON 日誌
            Proxy.log("📦 [分類解析 JSON]:\n" + new JSONObject(result).toString(4));
            return result;
        } catch (Exception e) {
            Proxy.log("🚨 [分類出錯]: " + e.getMessage());
            return "";
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String searchUrl = rule.optString("search_url");
            if (TextUtils.isEmpty(searchUrl)) return "";
            String url = searchUrl.replace("{wd}", URLEncoder.encode(key, "UTF-8"));
            if (url.startsWith("/") && !url.startsWith("//")) url = rule.optString("host") + url;
            Proxy.log("🔍 [搜索發起]: " + key + " | URL: " + url);
            String result = parseList(OkHttp.string(url, getHeaders(null)), "1", true);
            Proxy.log("🔍 [搜索結果 JSON]:\n" + new JSONObject(result).toString(4));
            return result;
        } catch (Exception e) {
            Proxy.log("🚨 [搜索出錯]: " + e.getMessage());
            return "";
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
                vod.put("vod_pic", getPicUrl(extract(item, rule.optString(prefix + "pic", rule.optString("cate_pic")))));
                vod.put("vod_remarks", extract(item, rule.optString(prefix + "remarks", rule.optString("cate_remarks"))));
                list.put(vod);
            }
            return new JSONObject().put("list", list).put("page", pg).toString();
        } catch (Exception e) {
            return "{\"list\":[]}";
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String id = ids.get(0);
            String url = id.startsWith("http") ? id : rule.optString("host") + (id.startsWith("/") ? "" : "/") + id;
            Proxy.log("📝 [詳情請求]: " + url);
            String html = OkHttp.string(url, getHeaders(null));
            Document doc = Jsoup.parse(html);
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            vod.put("vod_name", extract(doc, rule.optString("dt_name")));
            vod.put("vod_pic", getPicUrl(extract(doc, rule.optString("dt_pic"))));
            vod.put("type_name", extract(doc, rule.optString("dt_type")));
            vod.put("vod_year", extract(doc, rule.optString("dt_year")));
            vod.put("vod_area", extract(doc, rule.optString("dt_area")));
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
            Proxy.log("✅ [詳情解析 JSON]:\n" + new JSONObject(res).toString(4));
            return res;
        } catch (Exception e) {
            Proxy.log("🚨 [詳情出錯]: " + e.getMessage());
            return "";
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String url = id;
            if (url.startsWith("/") && !url.startsWith("//")) url = rule.optString("host") + url;
            Proxy.log("\n🚀 [播放解析啟動] URL: " + url);

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
                    Proxy.log("🎬 [Step " + (i + 1) + "] EXTRACT 提取源碼...");
                    if (TextUtils.isEmpty(currentHtml)) currentHtml = OkHttp.string(url, getHeaders(null));
                } else {
                    String stepUrl = replaceStepVars(step.optString("url", url));
                    Map<String, String> headers = getHeaders(step.optJSONObject("headers"));
                    
                    // 強制顯示請求頭日誌
                    Proxy.log("🎬 [Step " + (i + 1) + "] " + method.toUpperCase() + " URL: " + stepUrl);
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
            
            // 域名補全邏輯
            if (finalUrl.startsWith("/") && !finalUrl.startsWith("//")) {
                finalUrl = rule.optString("host") + finalUrl;
            } else if (!finalUrl.startsWith("http")) {
                if (TextUtils.isEmpty(finalUrl) || finalUrl.contains("{")) finalUrl = url;
            }

            Proxy.log("🏁 [最終播放 JSON]:\n" + new JSONObject().put("parse", 0).put("url", finalUrl).toString(4));
            JSONObject res = new JSONObject();
            res.put("parse", 0);
            res.put("url", finalUrl);
            if (playConfig.has("play_headers")) res.put("header", playConfig.optJSONObject("play_headers").toString());
            return res.toString();
        } catch (Exception e) {
            Proxy.log("🚨 [播放出錯]: " + e.getMessage());
            return quickResult(id);
        }
    }

    private String quickResult(String url) {
        try {
            if (url.startsWith("/") && !url.startsWith("//")) url = rule.optString("host") + url;
            JSONObject res = new JSONObject();
            res.put("parse", rule.optInt("parse", 0));
            res.put("url", url);
            if (rule.has("play_headers")) res.put("header", rule.optJSONObject("play_headers").toString());
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
        if (rule.optInt("pic_proxy", 0) == 1) {
            StringBuilder sb = new StringBuilder(pic);
            JSONObject picHd = rule.has("pic_headers") ? rule.optJSONObject("pic_headers") : rule.optJSONObject("headers");
            if (picHd != null) {
                Iterator<String> keys = picHd.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    sb.append("@").append(key).append("=").append(picHd.optString(key));
                }
            }
            return sb.toString();
        }
        return pic;
    }
}
