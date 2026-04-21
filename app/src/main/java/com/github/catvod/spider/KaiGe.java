package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
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
            SpiderDebug.log("🛠️ KaiGe 引擎啟動...");
            // 支持傳入本地 JSON 配置和遠程 URL
            String json = extend.startsWith("http") ? OkHttp.string(extend, null) : extend;
            this.rule = new JSONObject(json);
            SpiderDebug.log("✅ 規則加載成功，站點: " + rule.optString("site_name", "通用引擎"));
        } catch (Exception e) {
            SpiderDebug.log("🚨 初始化異常: " + e.getMessage());
        }
    }

    /**
     * 核心解析算法：支持 CSS 選擇、&& 萬能截取、* 模糊匹配
     */
    private String extract(Object root, String ruleStr) {
        try {
            if (TextUtils.isEmpty(ruleStr) || root == null) return "";
            String source = (root instanceof Element) ? ((Element) root).outerHtml() : root.toString();
            
            if (ruleStr.contains("&&")) {
                String[] parts = ruleStr.split("&&");
                String left = parts[0].trim();
                String right = parts.length > 1 ? parts[1].trim() : "";

                // 模糊匹配 (支持 left*right 結構)
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

                // CSS 選擇器 + 屬性/文本提取
                if (!left.isEmpty() && root instanceof Element) {
                    Element el = ((Element) root).selectFirst(left);
                    if (el == null) return "";
                    if (right.equals("text")) return el.text().trim();
                    if (right.equals("html")) return el.html().trim();
                    return el.attr(right).trim();
                }
                
                // 純字符串前後截取
                int s = source.indexOf(left);
                if (s == -1) return "";
                s += left.length();
                int e = source.indexOf(right, s);
                return (e != -1) ? source.substring(s, e).trim() : "";
            }

            // 單一 CSS 選擇器
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
            SpiderDebug.log("⚠️ 解析片段出錯: " + ruleStr);
        }
        return "";
    }

    /**
     * 構建請求 URL，處理動態變量與 Host
     */
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
            SpiderDebug.log("📡 [分類請求]: " + url);
            return parseList(OkHttp.string(url, getHeaders(null)), pg, false);
        } catch (Exception e) {
            SpiderDebug.log("🚨 [分類出錯]: " + e.getMessage());
            return "";
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String searchUrl = rule.optString("search_url");
            if (TextUtils.isEmpty(searchUrl)) return "";
            String url = searchUrl.replace("{wd}", URLEncoder.encode(key, "UTF-8"));
            SpiderDebug.log("🔍 [搜索發起]: " + key + " | URL: " + url);
            return parseList(OkHttp.string(url, getHeaders(null)), "1", true);
        } catch (Exception e) {
            SpiderDebug.log("🚨 [搜索出錯]: " + e.getMessage());
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
            SpiderDebug.log("📦 [列表解析]: 發現數量 " + items.size());

            for (Element item : items) {
                JSONObject vod = new JSONObject();
                vod.put("vod_id", extract(item, rule.optString(prefix + "id", rule.optString("cate_id"))));
                vod.put("vod_name", extract(item, rule.optString(prefix + "name", rule.optString("cate_name"))));
                vod.put("vod_pic", getPicUrl(extract(item, rule.optString(prefix + "pic", rule.optString("cate_pic")))));
                vod.put("vod_remarks", extract(item, rule.optString(prefix + "remarks", rule.optString("cate_remarks"))));
                list.put(vod);
            }
            return new JSONObject().put("list", list).put("page", pg).toString();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String id = ids.get(0);
            String url = id.startsWith("http") ? id : rule.optString("host") + id;
            SpiderDebug.log("📝 [詳情請求]: " + url);
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

            // 解析播放線路
            Elements froms = doc.select(rule.optString("dt_from"));
            List<String> fromList = new ArrayList<>();
            for (Element f : froms) fromList.add(f.text().trim());
            vod.put("vod_play_from", TextUtils.join("$$$", fromList));

            // 解析各線路播放清單
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
            
            SpiderDebug.log("✅ [詳情解析完成]: " + vod.optString("vod_name"));
            return new JSONObject().put("list", new JSONArray().put(vod)).toString();
        } catch (Exception e) {
            SpiderDebug.log("🚨 [詳情出錯]: " + e.getMessage());
            return "";
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            SpiderDebug.log("🎬 [播放請求]: " + id);
            JSONObject result = new JSONObject();
            result.put("parse", rule.optInt("parse", 0));
            result.put("url", id);
            if (rule.has("play_headers")) {
                result.put("header", rule.optJSONObject("play_headers").toString());
            }
            return result.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private Map<String, String> getHeaders(JSONObject customHd) {
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", rule.optString("ua", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"));
        JSONObject hd = customHd != null ? customHd : rule.optJSONObject("headers");
        if (hd != null) {
            Iterator<String> keys = hd.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                header.put(key, buildUrl(hd.optString(key)));
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
