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

public class KG extends Spider {
    private String siteUrl = "";
    private JSONObject rule = new JSONObject();
    private Map<String, String> varPool = new HashMap<>();

    private void logger(String msg) {
        try { Proxy.log(msg); } catch (Exception ignored) {}
    }

    @Override
    public void init(Context context, String extend) {
        try {
            logger("🚀 KG 全能独立引擎启动...");
            if (TextUtils.isEmpty(extend)) { logger("🚨 初始化失败: 配置为空"); return; }

            String json = extend.startsWith("http") ? OkHttp.string(extend) : extend;
            if (TextUtils.isEmpty(json) || !json.trim().startsWith("{")) { logger("🚨 初始化失败: JSON 格式不正确"); return; }

            this.rule = new JSONObject(json);
            this.siteUrl = rule.optString("site_url", rule.optString("host", ""));
            logger("✅ 配置加载完成: " + rule.optString("site_name"));
        } catch (Exception e) { logger("🚨 初始化异常: " + e.getMessage()); }
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONArray classes = rule.optJSONArray("classes");
            if (classes == null) return "";

            JSONArray resultClasses = new JSONArray();
            for (int i = 0; i < classes.length(); i++) {
                JSONObject oldCate = classes.getJSONObject(i);
                JSONObject newCate = new JSONObject();
                newCate.put("type_name", oldCate.optString("type_name", oldCate.optString("name")));
                newCate.put("type_id", oldCate.optString("type_id", oldCate.optString("id")));
                resultClasses.put(newCate);
            }

            JSONObject result = new JSONObject();
            result.put("class", resultClasses);
            if (rule.has("filters")) result.put("filters", rule.optJSONObject("filters"));
            return result.toString();
        } catch (Exception e) { logger("🚨 homeContent异常: " + e.getMessage()); return ""; }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean f, HashMap<String, String> e) {
        try {
            String method = rule.optString("cate_method", "get").toLowerCase();
            String url = (pg.equals("1") && rule.has("cate_page_1")) ? rule.optString("cate_page_1") : rule.optString("cate_url");
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

            // 基础门票
            if (pg.equals("1")) {
                KaiGeNet.smartRequest(this.siteUrl, "get", this.siteUrl, null, getHeaders(null));
                try { Thread.sleep(200); } catch (Exception ignored) {}
            }

            OkResult res = KaiGeNet.smartRequest(this.siteUrl, method, url, body, getHeaders(null));
            String html = res.getBody();
            if (res.getCode() != 200 || TextUtils.isEmpty(html) || html.length() < 300) {
                try { Thread.sleep(1000); } catch (Exception ignored) {}
                res = KaiGeNet.smartRequest(this.siteUrl, method, url, body, getHeaders(null));
                html = res.getBody();
            }
            return parseList(html, pg, false);
        } catch (Exception ex) { return "{\"list\":[]}"; }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String method = rule.optString("search_method", "get").toLowerCase();
            String url = rule.optString("search_url");
            String body = rule.optString("search_body", "");
            if (method.equals("post")) body = body.replace("{wd}", key);
            else url = url.replace("{wd}", URLEncoder.encode(key, "UTF-8"));

            if (url.contains("{host}")) url = url.replace("{host}", this.siteUrl);
            else if (url.startsWith("/") && !url.startsWith("//")) url = this.siteUrl + url;

            OkResult res = KaiGeNet.smartRequest(this.siteUrl, method, url, body, getHeaders(null));
            return parseList(res.getBody(), "1", true);
        } catch (Exception e) { return "{\"list\":[]}"; }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String id = ids.get(0);
            String method = rule.optString("detail_method", "get").toLowerCase();
            String url = id.startsWith("http") ? id : this.siteUrl + (id.startsWith("/") ? "" : "/") + id;
            String body = rule.optString("detail_body", "");
            if (method.equals("post")) body = body.replace("{id}", id);
            else if (url.contains("{id}")) url = url.replace("{id}", URLEncoder.encode(id, "UTF-8"));

            OkResult res = KaiGeNet.smartRequest(this.siteUrl, method, url, body, getHeaders(null));
            String html = res.getBody();
            Document doc = Jsoup.parse(html);
            JSONObject smartVod = KaiGeSmart.parseDetail(html);

            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            vod.put("vod_name", extract(doc, rule.optString("dt_name")));
            vod.put("vod_pic", extract(doc, rule.optString("dt_pic")));
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
        } catch (Exception e) { return ""; }
    }

    private void processOriginalDetail(Document doc, JSONObject vod) throws Exception {
        String fromRule = rule.optString("dt_from");
        String listRule = rule.optString("dt_list");
        String cssFrom = fromRule.contains("&&") ? fromRule.split("&&")[0] : fromRule;
        Elements fromElements = doc.select(cssFrom);
        List<String> fList = new ArrayList<>();
        List<String> pLists = new ArrayList<>();
        for (Element from : fromElements) {
            String sourceName = from.text().trim();
            if (TextUtils.isEmpty(sourceName)) sourceName = "播放线路 " + (fromElements.indexOf(from) + 1);
            Element nextList = null, p = from.parent();
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
            }
        }

        List<String> playList = new ArrayList<>();
        for (String html : pLists) {
            List<String> urls = new ArrayList<>();
            Document listDoc = Jsoup.parse(html);
            Elements aElements = listDoc.select("a");
            for (Element a : aElements) {
                String pName = extract(a, rule.optString("dt_list_name"));
                String pUrl = extract(a, rule.optString("dt_list_url"));
                if (!pName.isEmpty() && !pUrl.isEmpty()) urls.add(pName + "$" + pUrl);
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
            varPool.clear();
            varPool.put("play_id", originalUrl);
            varPool.put("final_url", originalUrl);

            // 解析标题和集数
            String vodName = varPool.getOrDefault("vod_name", "未知标题");
            String episode = id.contains("$") ? id.split("\\$")[0] : "1";
            varPool.put("ep", episode);

            JSONObject play = rule.has("play") ? rule.getJSONObject("play") : new JSONObject();
            JSONArray steps = play.optJSONArray("steps");
            int stepCount = steps != null ? steps.length() : 0;
            boolean finalStepSuccess = false;

            if (stepCount == 0) {
                boolean isStream = originalUrl.toLowerCase().contains(".m3u8") || originalUrl.toLowerCase().contains(".mp4");
                JSONObject res = new JSONObject();
                res.put("parse", isStream ? 0 : 1);
                res.put("url", originalUrl);
                res.put("header", getPlayHeaders(play));
                res.put("danmaku", buildDanmuUrl(vodName, episode));
                return res.toString();
            }

            for (int i = 0; i < stepCount; i++) {
                if (i >= 5) break;
                JSONObject step = steps.getJSONObject(i);
                String stepUrl = replaceStepVars(step.optString("url", varPool.get("final_url")));
                String method = step.optString("method", "get");
                Map<String, String> headers = getHeaders(step.optJSONObject("headers"));

                OkResult resStep = KaiGeNet.smartRequest(this.siteUrl, method, stepUrl,
                        replaceStepVars(step.optString("body")), headers);
                String html = resStep.getBody();

                JSONObject vars = step.optJSONObject("vars");
                if (vars != null) {
                    boolean stepAnyOk = false;
                    for (Iterator<String> it = vars.keys(); it.hasNext(); ) {
                        String k = it.next();
                        String vRule = vars.optString(k).trim();
                        String val = KaiGeEngine.doExtract(html, vRule, this.siteUrl).value;
                        if (!TextUtils.isEmpty(val)) {
                            val = val.replace("\\/", "/").trim();
                            varPool.put(k, val);
                            if (k.contains("url") || k.matches("p[1-4]")) {
                                varPool.put("final_url", val);
                                stepAnyOk = true;
                            }
                        }
                    }
                    if (i == stepCount - 1 && stepAnyOk) finalStepSuccess = true;
                }
            }

            String finalUrl = varPool.get("final_url").replace("\\/", "/");
            boolean finalHasStream = finalUrl.toLowerCase().contains(".m3u8") || finalUrl.toLowerCase().contains(".mp4");
            int pValue = (finalStepSuccess || finalHasStream) ? 0 : 1;

            JSONObject resJson = new JSONObject();
            resJson.put("parse", pValue);
            resJson.put("url", finalUrl);
            resJson.put("header", getPlayHeaders(play));
            resJson.put("danmaku", buildDanmuUrl(vodName, episode));

            return resJson.toString();
        } catch (Exception e) {
            return "{\"parse\":1,\"url\":\"" + originalUrl + "\",\"header\":{},\"danmaku\":\"\"}";
        }
    }

    private String buildDanmuUrl(String title, String episode) {
        try {
            return "http://127.0.0.1:9978/proxy?do=danmu"
                    + "&title=" + URLEncoder.encode(title, "UTF-8")
                    + "&episode=" + URLEncoder.encode(episode, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private JSONObject getPlayHeaders(JSONObject play) {
        JSONObject headJson = play.optJSONObject("play_headers");
        if (headJson == null) {
            headJson = new JSONObject();
            try {
                headJson.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36");
                headJson.put("Referer", this.siteUrl + "/");
            } catch (Exception ignored) {}
        }
        return headJson;
    }

    private String parseList(String html, String pg, boolean isSearch) {
        try {
            JSONArray list = new JSONArray();
            String prefix = isSearch ? "sc_" : "cate_";
            if (html.trim().startsWith("{") && html.contains("\"list\"")) {
                JSONObject json = new JSONObject(html);
                JSONArray array = json.optJSONArray("list");
                if (array != null) {
                    String detailTemplate = rule.optString("detail_url", "");
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject item = array.getJSONObject(i);
                        JSONObject vod = new JSONObject();
                        String vId = item.optString("id");
                        if (!TextUtils.isEmpty(vId)) {
                            if (!detailTemplate.isEmpty() && !vId.startsWith("http")) vod.put("vod_id", detailTemplate.replace("{id}", vId));
                            else vod.put("vod_id", vId.startsWith("http") ? vId : this.siteUrl + (vId.startsWith("/") ? "" : "/") + vId);
                        }
                        vod.put("vod_name", item.optString("name"));
                        String vPic = item.optString("pic");
                        if (!TextUtils.isEmpty(vPic) && vPic.startsWith("//")) vPic = "http:" + vPic;
                        vod.put("vod_pic", vPic);
                        vod.put("vod_remarks", item.optString("remarks"));
                        if (vod.has("vod_id")) list.put(vod);
                    }
                }
            }
            return new JSONObject().put("list", list).put("page", pg).toString();
        } catch (Exception e) { return "{\"list\":[]}"; }
    }

    private String extract(Object root, String ruleStr) {
        try {
            if (TextUtils.isEmpty(ruleStr) || root == null) return "";
            String realRule = ruleStr;
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
}