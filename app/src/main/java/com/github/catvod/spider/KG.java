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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 凱哥全能解析引擎 - 支持 bfjx 简写格式
 */
public class KG extends Spider {
    private JSONObject rule = new JSONObject();

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
    // 核心提取逻辑 (CSS + 字符串截取)
    // ==========================================
    private String extract(Object root, String ruleStr) {
        if (TextUtils.isEmpty(ruleStr) || root == null) return "";
        try {
            if (ruleStr.contains("&&")) {
                return stringExtract(root.toString(), ruleStr);
            }
            if (root instanceof Element) {
                return cssExtract((Element) root, ruleStr);
            }
            return root.toString().trim();
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    private String cssExtract(Element element, String cssRule) {
        Element el = element.selectFirst(cssRule);
        if (el == null) return "";
        String tag = el.tagName().toLowerCase();
        if (tag.equals("img")) {
            String pic = el.attr("data-original");
            return pic.isEmpty() ? el.attr("src") : pic;
        }
        return el.text().trim();
    }

    private String stringExtract(String source, String ruleStr) {
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
            if (right.equals("[text]")) {
                return source.substring(pos).trim();
            }
            int end = source.indexOf(right, pos);
            return (end != -1) ? source.substring(pos, end).trim() : "";
        }
        int s = source.indexOf(left);
        if (s == -1) return "";
        s += left.length();
        int e = source.indexOf(right, s);
        return (e != -1) ? source.substring(s, e).trim() : "";
    }

    // ==========================================
    // 变量拼接
    // ==========================================
    private String buildUrl(String format, Map<String, String> vars) {
        if (TextUtils.isEmpty(format)) return "";
        if (!format.contains("+")) {
            return replaceVars(format, vars);
        }
        StringBuilder sb = new StringBuilder();
        String[] parts = format.split("\\+");
        for (String p : parts) {
            p = p.trim();
            if (p.startsWith("{") && p.endsWith("}")) {
                String key = p.substring(1, p.length() - 1);
                String val = vars != null ? vars.getOrDefault(key, "") : "";
                try {
                    sb.append(URLEncoder.encode(val, "UTF-8"));
                } catch (Exception e) {
                    sb.append(val);
                }
            } else {
                sb.append(p.replace("'", ""));
            }
        }
        return replaceVars(sb.toString(), vars);
    }

    private String replaceVars(String str, Map<String, String> vars) {
        if (str == null || vars == null) return str;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            str = str.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return str.replace("{host}", rule.optString("host"));
    }

    // ==========================================
    // Base64 解码
    // ==========================================
    private String decodeBase64(String str) {
        if (TextUtils.isEmpty(str)) return "";
        try {
            return new String(android.util.Base64.decode(str, android.util.Base64.DEFAULT), "UTF-8");
        } catch (Exception e) {
            return str;
        }
    }

    // ==========================================
    // 核心：处理 bfjx 简写规则
    // ==========================================
    private String processBfjxStep(String ruleLine, String currentHtml, Map<String, String> vars) throws Exception {
        String line = ruleLine.trim();
        // 1. 先执行所有嵌入的提取表达式 (xxx&&yyy 或 xxx*yyy)
        String processed = line;
        Pattern extractPattern = Pattern.compile("([^+&]*?[+&]?[a-zA-Z0-9_*]*?&&[^+&]*)");
        Matcher m = extractPattern.matcher(processed);
        while (m.find()) {
            String expr = m.group(1);
            String extracted = extract(currentHtml, expr);
            processed = processed.replace(expr, extracted);
        }

        // 2. 替换变量
        processed = replaceVars(processed, vars);

        // 3. 解析请求头 (如果有 [请求头:...])
        Map<String, String> headers = new HashMap<>();
        String headerPattern = "\\[请求头:(.*?)\\]";
        Matcher hm = Pattern.compile(headerPattern).matcher(processed);
        if (hm.find()) {
            String headerStr = hm.group(1);
            for (String pair : headerStr.split("#")) {
                String[] kv = pair.split("\\$");
                if (kv.length == 2) headers.put(kv[0], kv[1]);
            }
            processed = processed.replace(hm.group(0), "");
        }

        // 4. 判断请求方法
        String method = "get";
        String url = processed;
        String body = "";
        if (processed.contains(";post;")) {
            method = "post";
            String[] parts = processed.split(";post;");
            url = parts[0].trim();
            body = parts.length > 1 ? parts[1].trim() : "";
        }

        // 5. 发起请求
        String response = "";
        if (method.equals("get")) {
            response = OkHttp.string(url, headers.isEmpty() ? getHeaders(null, vars) : headers);
        } else {
            Map<String, String> params = new HashMap<>();
            for (String pair : body.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length > 1) params.put(kv[0], kv[1]);
                else if (kv.length == 1) params.put(kv[0], "");
            }
            response = OkHttp.post(url, params, headers).getBody();
        }

        // 6. 如果规则中包含 [base64]，则解码响应
        if (ruleLine.contains("[base64]")) {
            response = decodeBase64(response);
        }

        return response;
    }

    // ==========================================
    // CatVod 接口实现
    // ==========================================
    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            result.put("class", rule.optJSONArray("classes"));
            if (rule.has("filter")) result.put("filters", rule.optJSONObject("filter"));
            return result.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            String cateUrl = pg.equals("1") && rule.has("cate_page_1") ? rule.optString("cate_page_1") : rule.optString("cate_url");
            String url = cateUrl.replace("{tid}", tid).replace("{pg}", pg);
            if (extend != null) {
                for (String key : extend.keySet()) {
                    url = url.replace("{" + key + "}", extend.get(key));
                }
            }
            url = url.replaceAll("\\{[^}]+\\}", "");
            String html = getHtml(url, null);
            return parseList(html, pg, false);
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            Map<String, String> vars = new HashMap<>();
            vars.put("wd", key);
            vars.put("pg", "1");
            String url = buildUrl(rule.optString("search_url"), vars);
            String html = getHtml(url, null);
            return parseList(html, "1", true);
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
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
            JSONObject result = new JSONObject();
            result.put("list", list);
            result.put("page", pg);
            return result.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String url = ids.get(0);
            if (!url.startsWith("http")) url = rule.optString("host") + url;
            Document doc = Jsoup.parse(getHtml(url, null));
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

            int minSize = Math.min(fromList.size(), circuits.size());
            List<String> validFrom = fromList.subList(0, minSize);
            List<String> validUrl = circuits.subList(0, minSize);
            vod.put("vod_play_from", TextUtils.join("$$$", validFrom));
            vod.put("vod_play_url", TextUtils.join("$$$", validUrl));

            JSONObject result = new JSONObject();
            result.put("list", new JSONArray().put(vod));
            return result.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        Map<String, String> vars = new HashMap<>();
        vars.put("play_id", id);
        vars.put("flag", flag);
        try {
            JSONObject playRule = rule.optJSONObject("play");
            if (playRule == null) {
                return new JSONObject().put("parse", 0).put("url", id).toString();
            }

            // 优先使用 bfjx 简写格式
            List<String> bfjxList = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                if (playRule.has("bfjx" + i)) bfjxList.add(playRule.getString("bfjx" + i));
            }
            if (!bfjxList.isEmpty()) {
                String currentHtml = getHtml(id, getHeaders(null, vars));
                String lastResult = currentHtml;
                for (String bfjx : bfjxList) {
                    lastResult = processBfjxStep(bfjx, lastResult, vars);
                    vars.put("bfjx_tmp", lastResult);
                }
                // 最后一步的结果作为最终播放地址
                String finalUrl = lastResult;
                if (finalUrl.startsWith("http")) {
                    JSONObject result = new JSONObject();
                    result.put("parse", 0);
                    result.put("url", finalUrl);
                    if (playRule.has("play_headers")) {
                        result.put("header", playRule.optJSONObject("play_headers").toString());
                    }
                    return result.toString();
                } else {
                    // 可能是提取出来的字段，再尝试解码
                    finalUrl = decodeBase64(finalUrl);
                    return new JSONObject().put("parse", 0).put("url", finalUrl).toString();
                }
            }

            // 原有的 steps 逻辑（兼容）
            if (!playRule.has("steps")) {
                return new JSONObject().put("parse", 0).put("url", id).toString();
            }
            JSONArray steps = playRule.getJSONArray("steps");
            String currentHtml = null;
            String currentUrl = id;
            for (int i = 0; i < steps.length(); i++) {
                JSONObject step = steps.getJSONObject(i);
                String method = step.optString("method", "get").toLowerCase();
                if (method.equals("get") || method.equals("post")) {
                    String stepUrl = buildUrl(step.optString("url", currentUrl), vars);
                    Map<String, String> headers = getHeaders(step.optJSONObject("headers"), vars);
                    if (method.equals("get")) {
                        currentHtml = getHtml(stepUrl, headers);
                    } else {
                        String body = buildUrl(step.optString("body", ""), vars);
                        currentHtml = postHtml(stepUrl, body, headers);
                    }
                    currentUrl = stepUrl;
                    vars.put("step" + (i + 1) + "_url", stepUrl);
                } else if (method.equals("extract")) {
                    if (currentHtml == null && i == 0) {
                        currentHtml = getHtml(id, getHeaders(null, vars));
                    }
                    JSONObject extractVars = step.optJSONObject("vars");
                    if (extractVars != null) {
                        Iterator<String> keys = extractVars.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            String vRule = extractVars.getString(key);
                            String value;
                            if (vRule.startsWith("json:")) {
                                String jsonPath = vRule.substring(5);
                                value = new JSONObject(currentHtml).optString(jsonPath);
                            } else if (vRule.startsWith("base64:")) {
                                String inner = vRule.substring(7);
                                for (Map.Entry<String, String> entry : vars.entrySet()) {
                                    inner = inner.replace("{" + entry.getKey() + "}", entry.getValue());
                                }
                                value = decodeBase64(inner);
                            } else {
                                value = extract(currentHtml, vRule);
                            }
                            vars.put(key, value);
                        }
                    }
                }
            }
            String finalUrl = buildUrl(rule.optString("final_output", "{final_url}"), vars);
            JSONObject result = new JSONObject();
            result.put("parse", 0);
            result.put("url", finalUrl);
            if (playRule.has("play_headers")) {
                result.put("header", playRule.optJSONObject("play_headers").toString());
            }
            return result.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    private Map<String, String> getHeaders(JSONObject customHd, Map<String, String> vars) {
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", rule.optString("ua", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"));
        JSONObject hd = customHd != null ? customHd : rule.optJSONObject("headers");
        if (hd != null) {
            Iterator<String> keys = hd.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = buildUrl(hd.optString(key), vars);
                header.put(key, value);
            }
        }
        return header;
    }

    private String getHtml(String url, Map<String, String> headers) throws Exception {
        if (headers == null) headers = getHeaders(null, null);
        return OkHttp.string(url, headers);
    }

    private String postHtml(String url, String body, Map<String, String> headers) throws Exception {
        Map<String, String> params = new HashMap<>();
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length > 1) params.put(kv[0], kv[1]);
            else if (kv.length == 1) params.put(kv[0], "");
        }
        return OkHttp.post(url, params, headers).getBody();
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
            if (!sb.toString().contains("User-Agent")) {
                sb.append("@User-Agent=").append(rule.optString("ua"));
            }
            return sb.toString();
        }
        return pic;
    }
}
