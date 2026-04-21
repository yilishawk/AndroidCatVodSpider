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

public class KG extends Spider {
    private JSONObject rule = new JSONObject();

    // 日志输出（兼容 TVBox 环境）
    private void log(String msg) {
        System.out.println("[KG] " + msg);
    }

    @Override
    public void init(Context context, String extend) {
        try {
            log("引擎初始化启动...");
            String json = extend.startsWith("http") ? OkHttp.string(extend, null) : extend;
            this.rule = new JSONObject(json);
            log("规则加载成功: " + rule.optString("site_name", "未知站点"));
        } catch (Exception e) {
            log("初始化失败: " + e.getMessage());
        }
    }

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

    private String decodeBase64(String str) {
        if (TextUtils.isEmpty(str)) return "";
        try {
            return new String(android.util.Base64.decode(str, android.util.Base64.DEFAULT), "UTF-8");
        } catch (Exception e) {
            return str;
        }
    }

    private String processBfjxStep(String ruleLine, String currentHtml, Map<String, String> vars) throws Exception {
        String line = ruleLine.trim();
        String processed = line;
        log("bfjx 处理规则: " + line);
        Pattern extractPattern = Pattern.compile("([^+&]*?[+&]?[a-zA-Z0-9_*]*?&&[^+&]*)");
        Matcher m = extractPattern.matcher(processed);
        while (m.find()) {
            String expr = m.group(1);
            String extracted = extract(currentHtml, expr);
            processed = processed.replace(expr, extracted);
        }

        processed = replaceVars(processed, vars);

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

        String method = "get";
        String url = processed;
        String body = "";
        if (processed.contains(";post;")) {
            method = "post";
            String[] parts = processed.split(";post;");
            url = parts[0].trim();
            body = parts.length > 1 ? parts[1].trim() : "";
        }

        String response = "";
        log("bfjx 请求: " + method.toUpperCase() + " " + url);
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

        if (ruleLine.contains("[base64]")) {
            response = decodeBase64(response);
            log("bfjx 响应已 base64 解码");
        }
        return response;
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            log("首页加载");
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
            log("分类请求: tid=" + tid + ", pg=" + pg);
            String cateUrl = pg.equals("1") && rule.has("cate_page_1") ? rule.optString("cate_page_1") : rule.optString("cate_url");
            String url = cateUrl.replace("{tid}", tid).replace("{pg}", pg);
            if (extend != null) {
                for (String key : extend.keySet()) {
                    url = url.replace("{" + key + "}", extend.get(key));
                }
            }
            url = url.replaceAll("\\{[^}]+\\}", "");
            log("分类URL: " + url);
            String html = getHtmlWithLog(url, null);
            if (TextUtils.isEmpty(html)) return "";
            return parseList(html, pg, false);
        } catch (Exception e) {
            log("分类异常: " + e.getMessage());
            return "";
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            log("搜索: " + key);
            Map<String, String> vars = new HashMap<>();
            vars.put("wd", key);
            vars.put("pg", "1");
            String url = buildUrl(rule.optString("search_url"), vars);
            String html = getHtmlWithLog(url, null);
            return parseList(html, "1", true);
        } catch (Exception e) {
            return "";
        }
    }

    private String parseList(String html, String pg, boolean isSearch) {
        try {
            Document doc = Jsoup.parse(html);
            String itemRule = isSearch ? rule.optString("sc_item", rule.optString("cate_item")) : rule.optString("cate_item");
            Elements items = doc.select(itemRule);
            log("列表解析: 找到 " + items.size() + " 项");
            JSONArray list = new JSONArray();
            for (Element item : items) {
                String idRule = isSearch ? rule.optString("sc_id", rule.optString("cate_id")) : rule.optString("cate_id");
                String nameRule = isSearch ? rule.optString("sc_name", rule.optString("cate_name")) : rule.optString("cate_name");
                String picRule = isSearch ? rule.optString("sc_pic", rule.optString("cate_pic")) : rule.optString("cate_pic");
                String remarkRule = isSearch ? rule.optString("sc_remarks", rule.optString("cate_remarks")) : rule.optString("cate_remarks");

                String id = extract(item, idRule);
                String name = extract(item, nameRule);
                String pic = extract(item, picRule);
                String remark = extract(item, remarkRule);

                if (!id.startsWith("http")) {
                    id = rule.optString("host") + (id.startsWith("/") ? "" : "/") + id;
                }

                JSONObject vod = new JSONObject();
                vod.put("vod_id", id);
                vod.put("vod_name", name);
                vod.put("vod_pic", getPicUrl(pic));
                vod.put("vod_remarks", remark);
                list.put(vod);
            }
            JSONObject result = new JSONObject();
            result.put("list", list);
            result.put("pagecount", pg);
            return result.toString();
        } catch (Exception e) {
            log("列表解析异常: " + e.getMessage());
            return "";
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String id = ids.get(0);
            String url = id.startsWith("http") ? id : rule.optString("host") + (id.startsWith("/") ? "" : "/") + id;
            log("详情请求: " + url);
            Document doc = Jsoup.parse(getHtmlWithLog(url, null));
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

            log("详情解析成功: " + vod.optString("vod_name"));
            JSONObject result = new JSONObject();
            result.put("list", new JSONArray().put(vod));
            return result.toString();
        } catch (Exception e) {
            log("详情异常: " + e.getMessage());
            return "";
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        Map<String, String> vars = new HashMap<>();
        vars.put("play_id", id);
        vars.put("flag", flag);
        log("播放解析开始: flag=" + flag + ", id=" + id);

        try {
            JSONObject playRule = rule.optJSONObject("play");
            if (playRule == null) {
                log("无播放规则，返回 parse=1 让壳子处理");
                return buildFallbackResponse(id);
            }

            // 尝试 bfjx 模式
            List<String> bfjxList = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                if (playRule.has("bfjx" + i)) bfjxList.add(playRule.getString("bfjx" + i));
            }
            if (!bfjxList.isEmpty()) {
                log("使用 bfjx 模式，步骤数: " + bfjxList.size());
                Map<String, String> headers = getHeaders(null, vars);
                headers.put("Referer", rule.optString("host"));
                String currentHtml = getHtmlWithLog(id, headers);
                if (TextUtils.isEmpty(currentHtml)) {
                    log("bfjx 第一步获取 HTML 为空，回退");
                    return buildFallbackResponse(id);
                }
                String lastResult = currentHtml;
                for (String bfjx : bfjxList) {
                    lastResult = processBfjxStep(bfjx, lastResult, vars);
                    vars.put("bfjx_tmp", lastResult);
                }
                String finalUrl = lastResult;
                if (!TextUtils.isEmpty(finalUrl) && finalUrl.startsWith("http")) {
                    log("bfjx 成功获取视频地址: " + finalUrl);
                    JSONObject result = new JSONObject();
                    result.put("parse", 0);
                    result.put("url", finalUrl);
                    if (playRule.has("play_headers"))
                        result.put("header", playRule.optJSONObject("play_headers").toString());
                    return result.toString();
                } else if (!TextUtils.isEmpty(finalUrl)) {
                    finalUrl = decodeBase64(finalUrl);
                    if (finalUrl.startsWith("http")) {
                        log("bfjx 解码后地址: " + finalUrl);
                        return new JSONObject().put("parse", 0).put("url", finalUrl).toString();
                    }
                }
                log("bfjx 未能提取有效地址，回退");
                return buildFallbackResponse(id);
            }

            // 尝试 steps 模式
            if (playRule.has("steps")) {
                log("使用 steps 模式");
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
                            currentHtml = getHtmlWithLog(stepUrl, headers);
                        } else {
                            String body = buildUrl(step.optString("body", ""), vars);
                            currentHtml = postHtmlWithLog(stepUrl, body, headers);
                        }
                        currentUrl = stepUrl;
                        vars.put("step" + (i + 1) + "_url", stepUrl);
                    } else if (method.equals("extract")) {
                        if (currentHtml == null && i == 0) {
                            Map<String, String> headers = getHeaders(null, vars);
                            headers.put("Referer", rule.optString("host"));
                            currentHtml = getHtmlWithLog(id, headers);
                        }
                        JSONObject extractVars = step.optJSONObject("vars");
                        if (extractVars != null) {
                            Iterator<String> keys = extractVars.keys();
                            while (keys.hasNext()) {
                                String key = keys.next();
                                String vRule = extractVars.getString(key);
                                String value;
                                if (vRule.startsWith("json:")) {
                                    value = new JSONObject(currentHtml).optString(vRule.substring(5));
                                } else if (vRule.startsWith("base64:")) {
                                    String inner = vRule.substring(7);
                                    for (Map.Entry<String, String> entry : vars.entrySet())
                                        inner = inner.replace("{" + entry.getKey() + "}", entry.getValue());
                                    value = decodeBase64(inner);
                                } else {
                                    value = extract(currentHtml, vRule);
                                }
                                vars.put(key, value);
                                log("提取变量 " + key + " = " + (value == null ? "null" : value.substring(0, Math.min(50, value.length()))));
                            }
                        }
                    }
                }
                String finalUrl = buildUrl(rule.optString("final_output", "{final_url}"), vars);
                if (!TextUtils.isEmpty(finalUrl) && finalUrl.startsWith("http")) {
                    log("steps 成功获取视频地址: " + finalUrl);
                    JSONObject result = new JSONObject();
                    result.put("parse", 0);
                    result.put("url", finalUrl);
                    if (playRule.has("play_headers"))
                        result.put("header", playRule.optJSONObject("play_headers").toString());
                    return result.toString();
                }
                log("steps 未能提取有效地址，回退");
                return buildFallbackResponse(id);
            }

            // 没有规则，直接回退
            log("无有效播放规则，回退");
            return buildFallbackResponse(id);
        } catch (Exception e) {
            log("播放解析异常: " + e.getMessage());
            return buildFallbackResponse(id);
        }
    }

    // 构建回退响应（parse=1，带上请求头）
    private String buildFallbackResponse(String url) {
        try {
            JSONObject result = new JSONObject();
            result.put("parse", 1);
            result.put("url", url);
            // 添加必要的请求头，帮助壳子解析
            JSONObject headers = new JSONObject();
            headers.put("User-Agent", rule.optString("ua", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"));
            headers.put("Referer", rule.optString("host"));
            // 如果有全局 headers，也合并进去
            JSONObject globalHeaders = rule.optJSONObject("headers");
            if (globalHeaders != null) {
                Iterator<String> it = globalHeaders.keys();
                while (it.hasNext()) {
                    String key = it.next();
                    headers.put(key, globalHeaders.optString(key));
                }
            }
            result.put("header", headers.toString());
            log("返回回退响应: parse=1, url=" + url);
            return result.toString();
        } catch (Exception e) {
            return "{\"parse\":1,\"url\":\"" + url + "\"}";
        }
    }

    // 带日志的 GET 请求
    private String getHtmlWithLog(String url, Map<String, String> headers) throws Exception {
        if (headers == null) headers = getHeaders(null, null);
        log("GET 请求: " + url);
        if (headers != null && !headers.isEmpty()) {
            log("请求头: " + headers);
        }
        String result = OkHttp.string(url, headers);
        if (result == null) {
            log("GET 响应: 空内容");
        } else {
            log("GET 响应长度: " + result.length());
            int previewLen = Math.min(300, result.length());
            log("响应预览: " + result.substring(0, previewLen).replace("\n", " "));
        }
        return result;
    }

    // 带日志的 POST 请求
    private String postHtmlWithLog(String url, String body, Map<String, String> headers) throws Exception {
        log("POST 请求: " + url);
        log("POST body: " + body);
        if (headers != null && !headers.isEmpty()) {
            log("请求头: " + headers);
        }
        Map<String, String> params = new HashMap<>();
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length > 1) params.put(kv[0], kv[1]);
            else if (kv.length == 1) params.put(kv[0], "");
        }
        String result = OkHttp.post(url, params, headers).getBody();
        if (result == null) {
            log("POST 响应: 空内容");
        } else {
            log("POST 响应长度: " + result.length());
            int previewLen = Math.min(300, result.length());
            log("响应预览: " + result.substring(0, previewLen).replace("\n", " "));
        }
        return result;
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
