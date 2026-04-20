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

/**
 * 凱哥全能解析引擎 - CatVod 終極適配版
 * 功能：CSS定位、&&截取、+拼接、*模糊匹配、多級跳轉、佔位符自動清理
 */
public class KaiGe extends Spider {
    private JSONObject rule = new JSONObject();
    private Map<String, String> varPool = new HashMap<>();

    @Override
    public void init(Context context, String extend) {
        try {
            String json = extend.startsWith("http") ? OkHttp.string(extend, null) : extend;
            this.rule = new JSONObject(json);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ==========================================
    // 1. 核心解析邏輯 (萬能截取 + CSS + 模糊匹配)
    // ==========================================
    private String extract(Object root, String ruleStr) {
        try {
            if (TextUtils.isEmpty(ruleStr) || root == null) return "";
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
                String tag = el.tagName().toLowerCase();
                if (tag.equals("img")) {
                    String pic = el.attr("data-original");
                    return pic.isEmpty() ? el.attr("src") : pic;
                }
                return el.text().trim();
            }
        } catch (Exception e) {}
        return "";
    }

    // ==========================================
    // 2. 核心拼接邏輯 (支持 '常量' + {變量})
    // ==========================================
    private String buildUrl(String format) {
        if (format == null || !format.contains("+")) {
            return format != null ? format.replace("{host}", rule.optString("host")) : "";
        }
        StringBuilder sb = new StringBuilder();
        String[] parts = format.split("\\+");
        for (String p : parts) {
            p = p.trim();
            if (p.startsWith("{") && p.endsWith("}")) {
                String key = p.substring(1, p.length() - 1);
                String val = varPool.getOrDefault(key, "");
                try {
                    sb.append(URLEncoder.encode(val, "UTF-8"));
                } catch (Exception e) { sb.append(val); }
            } else {
                sb.append(p.replace("'", "").replace("{host}", rule.optString("host")));
            }
        }
        return sb.toString();
    }

    // ==========================================
    // 3. CatVod 爬蟲接口實現
    // ==========================================
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
            String cateUrl = pg.equals("1") && rule.has("cate_page_1") ? rule.optString("cate_page_1") : rule.optString("cate_url");
            String url = cateUrl.replace("{tid}", tid).replace("{pg}", pg);
            
            // 執行篩選替換
            if (extend != null) {
                for (String key : extend.keySet()) {
                    url = url.replace("{" + key + "}", extend.get(key));
                }
            }

            // 【核心修正】：清理鏈接中未被點擊/賦值的殘留佔位符 (如 {area}, {year})
            url = url.replaceAll("\\{.*?\\}", ""); 

            return parseList(OkHttp.string(url, getHeaders(null)), pg, false);
        } catch (Exception e) { return ""; }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String url = buildUrl(rule.optString("search_url")).replace("{wd}", key);
            return parseList(getHtml(url), "1", true);
        } catch (Exception e) { return ""; }
    }

    private String parseList(String html, String pg, boolean isSearch) {
        try {
            Document doc = Jsoup.parse(html);
            JSONArray list = new JSONArray();
            String itemRule = isSearch ? rule.optString("sc_item", rule.optString("cate_item")) : rule.optString("cate_item");
            for (Element item : doc.select(itemRule)) {
                JSONObject vod = new JSONObject();
                String idRule = isSearch ? rule.optString("sc_id", rule.optString("cate_id")) : rule.optString("cate_id");
                String nameRule = isSearch ? rule.optString("sc_name", rule.optString("cate_name")) : rule.optString("cate_name");
                String picRule = isSearch ? rule.optString("sc_pic", rule.optString("cate_pic")) : rule.optString("cate_pic");
                String remarkRule = isSearch ? rule.optString("sc_remarks", rule.optString("cate_remarks")) : rule.optString("cate_remarks");

                vod.put("vod_id", extract(item, idRule));
                vod.put("vod_name", extract(item, nameRule));
                vod.put("vod_pic", getPicUrl(extract(item, picRule)));
                vod.put("vod_remarks", extract(item, remarkRule));
                list.put(vod);
            }
            return new JSONObject().put("list", list).put("page", pg).toString();
        } catch (Exception e) { return ""; }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String url = ids.get(0);
            if (!url.startsWith("http")) url = rule.optString("host") + url;
            Document doc = Jsoup.parse(getHtml(url));
            JSONObject vod = new JSONObject();
            vod.put("vod_id", ids.get(0));
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
                for (Element a : list.select("a")) {
                    String name = a.text().trim();
                    String link = a.attr("href");
                    if (!link.isEmpty()) urls.add(name + "$" + link);
                }
                if (!urls.isEmpty()) circuits.add(TextUtils.join("#", urls));
            }
            vod.put("vod_play_url", TextUtils.join("$$$", circuits));

            return new JSONObject().put("list", new JSONArray().put(vod)).toString();
        } catch (Exception e) { return ""; }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            varPool.put("play_id", id);
            JSONObject playRule = rule.optJSONObject("play");
            
            if (playRule == null || !playRule.has("steps")) {
                JSONObject result = new JSONObject().put("parse", rule.optInt("parse", 0)).put("url", id);
                if (rule.has("play_headers")) result.put("header", rule.optJSONObject("play_headers").toString());
                return result.toString();
            }

            JSONArray steps = playRule.getJSONArray("steps");
            String lastHtml = "";
            String lastUrl = id;

            for (int i = 0; i < steps.length(); i++) {
                JSONObject step = steps.getJSONObject(i);
                String method = step.optString("method", "get");
                String stepUrl = buildUrl(step.optString("url", lastUrl));

                if (method.equalsIgnoreCase("extract")) {
                    lastHtml = (i == 0) ? getHtml(id) : lastHtml; 
                } else if (method.equalsIgnoreCase("http_post")) {
                    lastHtml = postHtml(stepUrl, step.optString("body"), getHeaders(step.optJSONObject("headers")));
                } else {
                    lastHtml = getHtml(stepUrl);
                    lastUrl = stepUrl;
                }
                varPool.put("step" + (i + 1) + "_url", stepUrl);

                JSONObject vars = step.optJSONObject("vars");
                if (vars != null) {
                    Iterator<String> keys = vars.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String vRule = vars.getString(key);
                        String v = vRule.startsWith("json:") ? new JSONObject(lastHtml).optString(vRule.substring(5)) : extract(lastHtml, vRule);
                        varPool.put(key, v);
                    }
                }
            }

            String finalUrl = buildUrl(rule.optString("final_output", "{final_url}"));
            JSONObject result = new JSONObject().put("parse", 0).put("url", finalUrl);
            if (rule.has("play_headers")) result.put("header", rule.optJSONObject("play_headers").toString());
            return result.toString();
        } catch (Exception e) { return ""; }
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

    private String getHtml(String url) throws Exception { return OkHttp.string(url, getHeaders(null)); }

    private String postHtml(String url, String body, Map<String, String> hd) throws Exception {
        Map<String, String> params = new HashMap<>();
        String finalBody = buildUrl(body);
        for (String pair : finalBody.split("&")) {
            String[] kv = pair.split("=");
            if (kv.length > 1) params.put(kv[0], kv[1]);
        }
        return OkHttp.post(url, params, hd).getBody();
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
            if (!sb.toString().contains("User-Agent")) sb.append("@User-Agent=").append(rule.optString("ua"));
            return sb.toString();
        }
        return pic;
    }
}
