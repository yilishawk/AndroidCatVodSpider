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
import java.util.List;
import java.net.URLEncoder;
import java.util.*;

public class KaiGe extends Spider {
    private String siteUrl = ""; 
    private JSONObject rule = new JSONObject();
    private Map<String, String> varPool = new HashMap<>();
    private static final Map<String, String> urlEpisodeMap = new HashMap<>();

    private void logger(String msg) {
        try {
            Proxy.log(msg);
        } catch (Exception ignored) {}
    }

    private void logCheck(String title, String html, boolean showSource) {
        if (TextUtils.isEmpty(html)) {
            logger("🚨 [网络请求失败] " + title + " 返回内容为空！请检查 UA、Referer 或网站是否开启了 CC 防护");
            return;
        }
        int len = html.length();
        logger("📥 [" + title + "] 请求成功 | 收到 " + len + " 字符");
        if (showSource) {
            String preview = (len > 500 ? html.substring(0, 500) : html)
                .trim().replace("\n", " ").replace("\r", " ");
            logger("📄 [源码预览]: " + preview.replace("<", "&lt;").replace(">", "&gt;") + "...");
        }
    }

    @Override
    public void init(Context context, String extend) {
        try {
            logger("------------------------------------------");
            logger("🚀❤️ <b>凱哥全能獨立引擎啟動 (Full Power)...</b>");
            
            if (TextUtils.isEmpty(extend)) {
                logger("🚨 [系統] 初始化失敗: 配置路徑為空");
                return;
            }

            String json;
            if (extend.startsWith("http")) {
                Map<String, String> initHeaders = new HashMap<>();
                initHeaders.put("Referer", "");
                OkResult res = OkHttp.get(extend, null, initHeaders);
                json = res.getBody();
            } else {
                json = extend;
            }

            if (TextUtils.isEmpty(json) || !json.trim().startsWith("{")) {
                logger("🚨 [系統] 初始化失敗: 讀取到的 JSON 格式不正確");
                return;
            }

            this.rule = new JSONObject(json);
            this.siteUrl = rule.optString("site_url", rule.optString("host", ""));

            logger("✅ [系統] 站點配置加載完成: " + rule.optString("site_name"));
            logger("🌐 [系統] 域名自動綁定: " + this.siteUrl);

            if (rule.optBoolean("cdndefend", false)) {
                try {
                    Map<String, List<String>> redirectHeaders = OkHttp.getLocationHeader(this.siteUrl, getHeaders(null));
                    String location = OkHttp.getLocation(redirectHeaders);
                    if (!TextUtils.isEmpty(location)) {
                        String locationHost = "";
                        try { locationHost = new java.net.URL(location).getHost(); } catch (Exception ignored) {}
                        String siteHost = "";
                        try { siteHost = new java.net.URL(this.siteUrl).getHost(); } catch (Exception ignored) {}
                        if (!locationHost.equals(siteHost)) {
                            logger("<span style='color:#f1c40f;'>⚠️ [预热] 跨域跳转已拒绝: </span>" + location);
                            throw new Exception("cross domain redirect blocked");
                        }
                    }
                    String redirectCookie = "";
                    if (redirectHeaders != null) {
                        List<String> cookies = redirectHeaders.get("Set-Cookie");
                        if (cookies == null) cookies = redirectHeaders.get("set-cookie");
                        if (cookies != null && !cookies.isEmpty()) {
                            StringBuilder sb = new StringBuilder();
                            for (String c : cookies) {
                                String part = c.split(";")[0].trim();
                                if (sb.length() > 0) sb.append("; ");
                                sb.append(part);
                            }
                            redirectCookie = sb.toString();
                        }
                    }
                    if (!TextUtils.isEmpty(redirectCookie)) {
                        JSONObject hdrs = rule.optJSONObject("headers");
                        if (hdrs == null) hdrs = new JSONObject();
                        String existCookie = hdrs.optString("Cookie", "");
                        hdrs.put("Cookie", TextUtils.isEmpty(existCookie) ? redirectCookie : existCookie + "; " + redirectCookie);
                        rule.put("headers", hdrs);
                        KaiGeNet.putCookie(this.siteUrl, redirectCookie);
                        logger("<span style='color:#2ecc71;'>🍪 [302Token] cookie成功: </span>" + redirectCookie);
                    }
                    String homeHtml = KaiGeNet.smartRequest(this.siteUrl, "get", this.siteUrl, null, getHeaders(null)).getBody();
                    if (!TextUtils.isEmpty(homeHtml) && homeHtml.contains("cdndefend_js_cookie")) {
                        String cookie = KaiGeNet.cdnDefendCookie(homeHtml);
                        if (!TextUtils.isEmpty(cookie)) {
                            JSONObject hdrs = rule.optJSONObject("headers");
                            if (hdrs == null) hdrs = new JSONObject();
                            hdrs.put("Cookie", cookie);
                            rule.put("headers", hdrs);
                            logger("<span style='color:#2ecc71;'>🍪 [CDN盾] 自动计算cookie成功: </span>" + cookie);
                        }
                    }
                    logger("<span style='color:#2ecc71;'>✅ [首页预热] 完成</span>");
                } catch (Exception ex) {
                    logger("<span style='color:#f1c40f;'>⚠️ [首页预热] 异常: </span>" + ex.getMessage());
                }
            }

        } catch (Exception e) {
            logger("🚨 [系統] 初始化崩潰: " + e.getMessage());
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean f, HashMap<String, String> e) {
        try {
            String method = rule.optString("cate_method", "get").toLowerCase();
            String url = (pg.equals("1") && rule.has("cate_page_1") ? rule.optString("cate_page_1") : rule.optString("cate_url"));
            String body = rule.optString("cate_body", "");

            String rTid = tid;
            if (e != null) {
                if (e.containsKey("tid")) rTid = e.get("tid");
                else if (e.containsKey("type_id")) rTid = e.get("type_id");
            }

            url = url.replace("{tid}", URLEncoder.encode(rTid, "UTF-8")).replace("{pg}", pg);
            if (!TextUtils.isEmpty(body)) body = body.replace("{tid}", rTid).replace("{pg}", pg);

            String[] filterKeys = {"area", "class", "year", "by", "lang", "letter", "字母"};
            for (String key : filterKeys) {
                String val = (e != null && e.containsKey(key)) ? e.get(key) : "";
                url = url.replace("{" + key + "}", URLEncoder.encode(val, "UTF-8"));
                if (!TextUtils.isEmpty(body)) body = body.replace("{" + key + "}", val);
            }

            if (url.startsWith("/") && !url.startsWith("//")) url = this.siteUrl + url;

            Proxy.log("<b style='color:#2ecc71;'>📂 [分類啟動]</b> " + url);
            if (method.equals("post") && !TextUtils.isEmpty(body)) {
                Proxy.log("<span style='color:#f1c40f;'>[POST參數]</span> " + body);
            }

            if (pg.equals("1")) {
                KaiGeNet.smartRequest(this.siteUrl, "get", this.siteUrl, null, getHeaders(null));
                try { Thread.sleep(200); } catch (Exception ignored) {}
            }

            OkResult res = KaiGeNet.smartRequest(this.siteUrl, method, url, body, getHeaders(null));
            String html = res.getBody();

            if (res.getCode() != 200 || TextUtils.isEmpty(html) || html.length() < 300) {
                Proxy.log("<b style='color:#f1c40f;'>⚠️ 內容異常，嘗試二次刷新...</b>");
                try { Thread.sleep(1000); } catch (Exception ignored) {}
                res = KaiGeNet.smartRequest(this.siteUrl, method, url, body, getHeaders(null));
                html = res.getBody();
            }

            logCheck("分類", html, false);
            return parseList(html, pg, false);
        } catch (Exception ex) { 
            Proxy.log("<b style='color:red;'>🚨 [分類異常]:</b> " + ex.getMessage());
            return "{\"list\":[]}"; 
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String method = rule.optString("search_method", "get").toLowerCase();
            String url = rule.optString("search_url");
            String body = rule.optString("search_body", "");

            if (method.equals("post")) {
                body = body.replace("{wd}", key);
            } else {
                url = url.replace("{wd}", URLEncoder.encode(key, "UTF-8"));
            }

            if (url.contains("{host}")) url = url.replace("{host}", this.siteUrl);
            else if (url.startsWith("/") && !url.startsWith("//")) url = this.siteUrl + url;

            Proxy.log("<b style='color:#3498db;'>🔍 [搜索啟動]</b> 方法: " + method.toUpperCase());
            Proxy.log("<span style='color:#9b59b6;'>[搜索網址]</span> " + url);

            OkResult res = KaiGeNet.smartRequest(this.siteUrl, method, url, body, getHeaders(null));
            logCheck("搜索", res.getBody(), false);
            return parseList(res.getBody(), "1", true);
        } catch (Exception e) { 
            Proxy.log("<b style='color:red;'>🚨 [搜索異常]:</b> " + e.getMessage());
            return "{\"list\":[]}"; 
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String id = ids.get(0);
            String method = rule.optString("detail_method", "get").toLowerCase();
            String url = id.startsWith("http") ? id : this.siteUrl + (id.startsWith("/") ? "" : "/") + id;
            String body = rule.optString("detail_body", "");

            if (method.equals("post")) {
                body = body.replace("{id}", id);
            } else if (url.contains("{id}")) {
                url = url.replace("{id}", URLEncoder.encode(id, "UTF-8"));
            }

            Proxy.log("<b style='color:#f39c12;'>📋 [詳情啟動]</b> 方法: " + method.toUpperCase() + " | ID: " + id);

            OkResult res = KaiGeNet.smartRequest(this.siteUrl, method, url, body, getHeaders(null));
            String html = res.getBody();
            logCheck("詳情", html, false);

            if (html != null && html.trim().startsWith("{")) {
                try {
                    JSONObject json = new JSONObject(html.trim());
                    JSONArray dataList = json.optJSONArray("list");
                    if (dataList != null && dataList.length() > 0) {
                        JSONObject item = dataList.getJSONObject(0);
                        JSONObject vod = new JSONObject();
                        vod.put("vod_id", ids.get(0));
                        String resolvedName = item.optString("vod_name", item.optString("name", ""));
                        vod.put("vod_name", resolvedName);
                        varPool.put("vod_name", resolvedName);
                        vod.put("vod_pic", item.optString("vod_pic", item.optString("pic", "")));
                        vod.put("vod_remarks", item.optString("vod_remarks", item.optString("remarks", "")));
                        vod.put("vod_actor", item.optString("vod_actor", item.optString("actor", "")));
                        vod.put("vod_director", item.optString("vod_director", item.optString("director", "")));
                        vod.put("vod_content", item.optString("vod_content", item.optString("content", "")));
                        vod.put("vod_play_from",item.optString("vod_play_from",""));
                        vod.put("vod_play_url", item.optString("vod_play_url", ""));
                        Proxy.log("<b style='color:#2ecc71;'>✅ [详情] JSON直解成功: </b>" + resolvedName);
                        return new JSONObject().put("list", new JSONArray().put(vod)).toString();
                    }
                } catch (Exception ex) {
                    Proxy.log("<b style='color:red;'>❌ [详情] JSON解析失败: </b>" + ex.getMessage());
                }
            }

            Document doc = Jsoup.parse(html);
            JSONObject smartVod = KaiGeSmart.parseDetail(html);
            
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            
            String name = extract(doc, rule.optString("dt_name"));
            String resolvedName = TextUtils.isEmpty(name) ? smartVod.optString("vod_name") : name;
            vod.put("vod_name", resolvedName);
            varPool.put("vod_name", resolvedName);
            
            String pic = extract(doc, rule.optString("dt_pic"));
            vod.put("vod_pic", TextUtils.isEmpty(pic) ? smartVod.optString("vod_pic") : pic);
            
            vod.put("vod_remarks", extract(doc, rule.optString("dt_remarks")));
            vod.put("vod_actor", extract(doc, rule.optString("dt_actor")));
            vod.put("vod_director", extract(doc, rule.optString("dt_director")));
            vod.put("vod_content", extract(doc, rule.optString("dt_content")));

            if (TextUtils.isEmpty(rule.optString("dt_list"))) {
                vod.put("vod_play_from", smartVod.optString("vod_play_from"));
                vod.put("vod_play_url", smartVod.optString("vod_play_url"));
            } else {
                processOriginalDetail(doc, vod);
            }

            return new JSONObject().put("list", new JSONArray().put(vod)).toString();
        } catch (Exception e) { 
            Proxy.log("<b style='color:red;'>🚨 [詳情異常]:</b> " + e.getMessage());
            return ""; 
        }
    }

    private void processOriginalDetail(Document doc, JSONObject vod) throws Exception {
        String fromRule = rule.optString("dt_from");
        String listRule = rule.optString("dt_list");
        String cssFrom = fromRule;
        if (fromRule.contains("&&")) {
            String[] parts = fromRule.split("&&");
            cssFrom = parts[0].contains("[包含:") ? (parts.length > 1 ? parts[1] : "h3") : parts[0];
        }

        Elements fromElements = doc.select(cssFrom);
        List<String> fList = new ArrayList<>();
        List<String> pLists = new ArrayList<>();

        for (Element from : fromElements) {
            String sourceName = from.text().trim();
            if (TextUtils.isEmpty(sourceName)) sourceName = "播放線路 " + (fromElements.indexOf(from) + 1);

            Elements allLists = doc.select(listRule);
            int idx = fromElements.indexOf(from);
            Element nextList = (idx < allLists.size()) ? allLists.get(idx) : null;

            if (nextList != null) {
                fList.add(sourceName);
                pLists.add(nextList.outerHtml());
            }
        }

        List<String> playList = new ArrayList<>();
        for (int i = 0; i < pLists.size(); i++) {
            List<String> urls = new ArrayList<>();
            Document listDoc = Jsoup.parse(pLists.get(i));
            Elements aElements = listDoc.select("a");
            for (Element a : aElements) {
                String pName = a.text().trim();
                String pUrl = a.attr("href").trim();
                if (!pName.isEmpty() && !pUrl.isEmpty() && !pUrl.contains("javascript")) {
                    urls.add(pName + "$" + pUrl);
                    urlEpisodeMap.put(pUrl, pName);
                }
            }
            playList.add(TextUtils.join("#", urls));
        }

        vod.put("vod_play_from", TextUtils.join("$$$", fList));
        vod.put("vod_play_url", TextUtils.join("$$$", playList));
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String originalUrl = id.startsWith("/") && !id.startsWith("//") ? this.siteUrl + id : id;
        try {
            Proxy.log("<b style='color:#e74c3c;'>🎬 [播放解析啟動]</b> Flag: " + flag + " | ID: " + id);

            String savedVodName = varPool.getOrDefault("vod_name", "");
            varPool.clear();
            varPool.put("play_id", originalUrl);
            varPool.put("final_url", originalUrl);

            if (!TextUtils.isEmpty(savedVodName)) {
                varPool.put("vod_name", savedVodName);
            }

            String episodeStr = "1";
            if (urlEpisodeMap.containsKey(originalUrl)) {
                episodeStr = urlEpisodeMap.get(originalUrl);
                Proxy.log("✅ [集数缓存命中] " + episodeStr);
            } else if (id.contains("$")) {
                episodeStr = id.split("\\$")[0].trim();
                Proxy.log("✅ [从 $ 提取集数] " + episodeStr);
            } else {
                String rawText = flag + " " + id;
                String[] patterns = {
                    "(?:第|EP|E|话|集)[:：\\s]*(\\d+)",
                    "(\\d+)[\\s\\-]?[集话期]",
                    "\\[(\\d+)\\]"
                };
                for (String pat : patterns) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile(pat, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(rawText);
                    if (m.find()) {
                        episodeStr = m.group(1);
                        break;
                    }
                }
            }
            varPool.put("episode", episodeStr);
            Proxy.log("🎯 [最终集数用于弹幕] → " + episodeStr);

            JSONObject play = rule.has("play") ? rule.getJSONObject("play") : new JSONObject();
            JSONArray steps = play.optJSONArray("steps");
            int stepCount = (steps != null ? steps.length() : 0);

            String jx = play.optString("jx", "");
            if (!TextUtils.isEmpty(jx)) {
                // 你的 jx 逻辑（如果有请保留）
            }

            if (stepCount == 0) {
                boolean isStream = originalUrl.toLowerCase().contains(".m3u8") || originalUrl.toLowerCase().contains(".mp4");
                JSONObject res = new JSONObject();
                res.put("parse", isStream ? 0 : 1);
                res.put("url", originalUrl);
                res.put("header", getPlayHeaders(play));
                return addDanmakuToResult(res).toString();
            }

            String finalUrl = varPool.get("final_url").replace("\\/", "/");
            boolean finalHasStream = finalUrl.toLowerCase().contains(".m3u8") || finalUrl.toLowerCase().contains(".mp4");
            int pValue = finalHasStream ? 0 : 1;

            JSONObject resJson = new JSONObject();
            resJson.put("parse", pValue);
            resJson.put("url", (pValue == 0) ? finalUrl : originalUrl);
            resJson.put("header", getPlayHeaders(play));

            return addDanmakuToResult(resJson).toString();

        } catch (Exception e) {
            Proxy.log("<b style='color:red;'>❌ [播放解析崩潰]:</b> " + e.getMessage());
            return "{\"parse\":1,\"url\":\"" + originalUrl + "\",\"header\":{}}";
        }
    }

    private JSONObject addDanmakuToResult(JSONObject resJson) {
        try {
            if (rule.optBoolean("danmaku", false)) {
                String vodName = varPool.getOrDefault("vod_name", "");
                String episode = varPool.getOrDefault("episode", "1");
                if (!TextUtils.isEmpty(vodName)) {
                    String danmakuUrl = Proxy.getUrl() + "?do=danmaku"
                            + "&title=" + URLEncoder.encode(vodName, "UTF-8")
                            + "&episode=" + URLEncoder.encode(episode, "UTF-8");
                    resJson.put("danmaku", danmakuUrl);
                    Proxy.log("<b style='color:#2ecc71;'>✅ [弹幕注入成功] </b>" + danmakuUrl);
                }
            }
        } catch (Exception e) {
            Proxy.log("❌ [弹幕注入异常]: " + e.getMessage());
        }
        return resJson;
    }

    private JSONObject getPlayHeaders(JSONObject play) {
        JSONObject headJson = play.optJSONObject("play_headers");
        if (headJson == null) {
            headJson = new JSONObject();
            try {
                headJson.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                headJson.put("Referer", this.siteUrl + "/");
            } catch (Exception ignored) {}
        }
        return headJson;
    }

    private String parseList(String html, String pg, boolean isSearch) {
        try {
            JSONArray list = new JSONArray();
            String prefix = isSearch ? "sc_" : "cate_";
            String detailTemplate = rule.optString("detail_url", "");

            String itemRule = rule.optString(prefix + "item", rule.optString("cate_item", ""));
            String idRule     = rule.optString(prefix + "id",      rule.optString("cate_id",      ""));
            String nameRule   = rule.optString(prefix + "name",    rule.optString("cate_name",    ""));
            String picRule    = rule.optString(prefix + "pic",     rule.optString("cate_pic",     ""));
            String remarkRule = rule.optString(prefix + "remarks", rule.optString("cate_remarks", ""));

            if (itemRule.toLowerCase().startsWith("json:")) {
                // JSON 模式
            } else if (html != null && html.trim().startsWith("{")) {
                // 苹果CMS 兼容
            } else {
                Document doc = Jsoup.parse(html);
                Elements items = doc.select(itemRule);
                for (Element item : items) {
                    JSONObject vod = new JSONObject();
                    // 你的原有解析逻辑...
                    list.put(vod);
                }
            }

            return new JSONObject().put("list", list).put("page", pg).toString();
        } catch (Exception e) {
            return "{\"list\":[]}";
        }
    }

    private String stripJson(String rule) {
        if (TextUtils.isEmpty(rule)) return "";
        return rule.toLowerCase().startsWith("json:") ? rule.substring(5).trim() : rule.trim();
    }

    private String extract(Object root, String ruleStr) {
        try {
            if (TextUtils.isEmpty(ruleStr) || root == null) return "";
            String realRule = replaceStepVars(ruleStr);
            if (!realRule.contains("&&")) {
                if (root instanceof Element) {
                    Element el = (Element) root;
                    if (realRule.contains("@")) {
                        String[] parts = realRule.split("@");
                        Element target = parts[0].trim().isEmpty() ? el : el.selectFirst(parts[0].trim());
                        return target != null ? target.attr(parts[1].trim()) : "";
                    } else {
                        Element target = el.selectFirst(realRule);
                        return target != null ? target.text() : "";
                    }
                }
            } else {
                String content = (root instanceof Document) ? ((Document) root).outerHtml() : (root instanceof Element) ? ((Element) root).outerHtml() : root.toString();
                return KaiGeEngine.doExtract(content, realRule, this.siteUrl).value;
            }
            return "";
        } catch (Exception e) { return ""; }
    }

    private String replaceStepVars(String text) {
        if (text == null) return "";
        String res = text;
        for (String k : varPool.keySet()) res = res.replace("{" + k + "}", varPool.get(k));
        return res.replace("{host}", this.siteUrl);
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
        try {
            logger("🏠 [主頁] 正在加載分類導航...");
            JSONArray classes = rule.optJSONArray("classes");
            if (classes == null || classes.length() == 0) {
                return "";
            }
            JSONArray resultClasses = new JSONArray();
            for (int i = 0; i < classes.length(); i++) {
                JSONObject oldCate = classes.getJSONObject(i);
                JSONObject newCate = new JSONObject();
                String name = oldCate.optString("type_name", oldCate.optString("name"));
                String id = oldCate.optString("type_id", oldCate.optString("id"));
                newCate.put("type_name", name);
                newCate.put("type_id", id);
                resultClasses.put(newCate);
            }
            JSONObject result = new JSONObject();
            result.put("class", resultClasses);
            if (rule.has("filters")) {
                result.put("filters", rule.optJSONObject("filters"));
            }
            return result.toString();
        } catch (Exception e) {
            logger("🚨 [主頁異常]: " + e.getMessage());
            return "";
        }
    }

    private Object getJsonByPath(JSONObject json, String path) {
        try {
            Object current = json;
            for (String key : path.split("\\.")) {
                if (current instanceof JSONObject) {
                    current = ((JSONObject) current).opt(key);
                } else {
                    return null;
                }
            }
            return current;
        } catch (Exception e) {
            return null;
        }
    }
}
