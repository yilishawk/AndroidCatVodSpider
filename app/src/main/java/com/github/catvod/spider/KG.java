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
import java.util.regex.Pattern;

public class KG extends Spider {
    private String siteUrl = "";
    private JSONObject rule = new JSONObject();
    private Map<String, String> varPool = new HashMap<>();

    // ================== 新增加：缓存影片标题供弹幕使用 ==================
    private String currentVodName = "";

    private void logger(String msg) {
        try {
            Proxy.log(msg);
        } catch (Exception ignored) {
        }
    }

    private void logCheck(String title, String html, boolean showSource) {
        if (TextUtils.isEmpty(html)) {
            logger("❌ [" + title + "] 請求失敗");
            return;
        }
        int len = html.length();
        logger("📥 [" + title + "] 成功 | " + len + " 字符");
        if (showSource) {
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
                try {
                    Thread.sleep(200);
                } catch (Exception ignored) {
                }
            }

            OkResult res = KaiGeNet.smartRequest(this.siteUrl, method, url, body, getHeaders(null));
            String html = res.getBody();

            Proxy.log("<b style='color:#3498db;'>📊 [數據返回]</b> 長度: " + (html != null ? html.length() : 0));

            if (res.getCode() != 200 || TextUtils.isEmpty(html) || html.length() < 300) {
                Proxy.log("<b style='color:#f1c40f;'>⚠️ 內容異常，嘗試二次刷新...</b>");
                try {
                    Thread.sleep(1000);
                } catch (Exception ignored) {
                }
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
            if (method.equals("post")) {
                Proxy.log("<span style='color:#f1c40f;'>[POST參數]</span> " + body);
            }

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
            } else {
                if (url.contains("{id}")) {
                    url = url.replace("{id}", URLEncoder.encode(id, "UTF-8"));
                }
            }

            Proxy.log("<b style='color:#f39c12;'>📋 [詳情啟動]</b> 方法: " + method.toUpperCase() + " | ID: " + id);
            if (method.equals("post")) {
                Proxy.log("<span style='color:#f1c40f;'>[POST參數]</span> " + body);
            }

            OkResult res = KaiGeNet.smartRequest(this.siteUrl, method, url, body, getHeaders(null));
            String html = res.getBody();
            logCheck("詳情", html, false);

            Document doc = Jsoup.parse(html);

            JSONObject smartVod = KaiGeSmart.parseDetail(html);

            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);

            String name = extract(doc, rule.optString("dt_name"));
            vod.put("vod_name", TextUtils.isEmpty(name) ? smartVod.optString("vod_name") : name);
            // ================== 缓存影片标题供弹幕使用 ==================
            currentVodName = vod.optString("vod_name", "");

            String pic = extract(doc, rule.optString("dt_pic"));
            vod.put("vod_pic", TextUtils.isEmpty(pic) ? smartVod.optString("vod_pic") : pic);

            vod.put("vod_remarks", extract(doc, rule.optString("dt_remarks")));

            String actor = extract(doc, rule.optString("dt_actor"));
            vod.put("vod_actor", TextUtils.isEmpty(actor) ? smartVod.optString("vod_actor") : actor);

            String director = extract(doc, rule.optString("dt_director"));
            vod.put("vod_director", TextUtils.isEmpty(director) ? smartVod.optString("vod_director") : director);

            String content = extract(doc, rule.optString("dt_content"));
            vod.put("vod_content", TextUtils.isEmpty(content) ? smartVod.optString("vod_content") : content);

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
            }
        }

        List<String> playList = new ArrayList<>();
        for (int i = 0; i < pLists.size(); i++) {
            List<String> urls = new ArrayList<>();
            Document listDoc = Jsoup.parse(pLists.get(i));
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
        // ================== 修改1：从 id 中分离集数名称和播放地址 ==================
        String epName = "";
        String playUrl = id;
        if (id != null && id.contains("$")) {
            int splitPos = id.indexOf("$");
            epName = id.substring(0, splitPos);          // “第01集”
            playUrl = id.substring(splitPos + 1);        // 真正的播放地址
        }
        String originalUrl = playUrl.startsWith("http") ? playUrl :
                (playUrl.startsWith("//") ? playUrl :
                        this.siteUrl + (playUrl.startsWith("/") ? "" : "/") + playUrl);

        try {
            Proxy.log("<b style='color:#e74c3c;'>🎬 [播放解析啟動]</b> 原始ID: " + originalUrl);

            varPool.clear();
            varPool.put("play_id", originalUrl);
            varPool.put("final_url", originalUrl);

            JSONObject play = rule.has("play") ? rule.getJSONObject("play") : new JSONObject();
            JSONArray steps = play.optJSONArray("steps");
            int stepCount = (steps != null ? steps.length() : 0);
            boolean finalStepSuccess = false;

            if (stepCount == 0) {
                boolean isStream = originalUrl.toLowerCase().contains(".m3u8") || originalUrl.toLowerCase().contains(".mp4");
                JSONObject resJson = new JSONObject();
                resJson.put("parse", isStream ? 0 : 1);
                resJson.put("url", originalUrl);
                resJson.put("header", getPlayHeaders(play));
                // ================== 修改2：传入集数名称 ==================
                safePutDanmaku(resJson, epName);
                Proxy.log("<b style='color:#2ecc71;'>🚀 [Direct] 無解析步驟，直接推送原始地址</b>");
                return resJson.toString();
            }

            for (int i = 0; i < stepCount; i++) {
                if (i >= 5) break;
                JSONObject step = steps.getJSONObject(i);
                String stepUrl = replaceStepVars(step.optString("url", varPool.get("final_url")));
                String method = step.optString("method", "get");

                Map<String, String> headers = getHeaders(step.optJSONObject("headers"));

                Proxy.log("<span style='color:#3498db;'>[Step " + (i + 1) + " 請求]</span> " + method.toUpperCase() + " -> " + stepUrl);
                Proxy.log("<span style='color:#9b59b6;'>[請求頭查看]</span> " + headers.toString());

                OkResult res = KaiGeNet.smartRequest(this.siteUrl, method, stepUrl,
                        replaceStepVars(step.optString("body")),
                        getHeaders(step.optJSONObject("headers")));
                String html = res.getBody();

                Proxy.log("<b style='color:#3498db;'>📥 [Step " + (i + 1) + " 返回監控]</b>");
                if (TextUtils.isEmpty(html)) {
                    Proxy.log("<b style='color:red;'>❌ [致命] 返回內容完全為空！</b> 請檢查 URL 格式或網絡連通性。");
                } else {
                    String preview = (html.length() > 500 ? html.substring(0, 500) : html)
                            .trim().replace("\n", " ").replace("\r", " ");
                    Proxy.log("<div style='background:#2c3e50; color:#ecf0f1; padding:5px; border-left:5px solid #e74c3c;'>源碼預覽: "
                            + preview.replace("<", "&lt;").replace(">", "&gt;") + "...</div>");
                }

                JSONObject vars = step.optJSONObject("vars");
                if (vars != null) {
                    boolean currentStepAnyOk = false;
                    for (Iterator<String> it = vars.keys(); it.hasNext(); ) {
                        String k = it.next();
                        String vRule = vars.optString(k).trim();
                        String val = "";

                        if (vRule.startsWith("json:")) {
                            try {
                                String keyName = vRule.substring(5).trim();
                                JSONObject jsonObj = new JSONObject(html.trim());
                                val = jsonObj.optString(keyName);
                            } catch (Exception e) {
                                Proxy.log("   └─ <b style='color:red;'>❌ JSON 解析失敗:</b> " + e.getMessage());
                                val = "";
                            }
                        } else {
                            val = KaiGeEngine.doExtract(html, vRule, this.siteUrl).value;
                        }

                        if (!TextUtils.isEmpty(val)) {
                            val = val.replace("\\/", "/").replace("\\", "").trim();
                            varPool.put(k, val);
                            Proxy.log("    └─ <span style='color:#f1c40f;'>[提取成功]</span> " + k + " = "
                                    + (val.length() > 80 ? val.substring(0, 80) + "..." : val));

                            if (k.contains("url") || k.matches("p[1-4]")) {
                                varPool.put("final_url", val);
                                currentStepAnyOk = true;
                            }
                        } else {
                            Proxy.log("    └─ <b style='color:#95a5a6;'>⚠️ [提取為空]</b> 鍵: " + k + " | 規則: " + vRule);
                        }
                    }
                    if (i == stepCount - 1 && currentStepAnyOk) finalStepSuccess = true;
                }
            }

            String finalUrl = varPool.get("final_url").replace("\\/", "/");
            boolean finalHasStream = finalUrl.toLowerCase().contains(".m3u8") || finalUrl.toLowerCase().contains(".mp4");
            int pValue = (finalStepSuccess || finalHasStream) ? 0 : 1;

            JSONObject resJson = new JSONObject();
            resJson.put("parse", pValue);
            resJson.put("url", (pValue == 0) ? finalUrl : originalUrl);
            resJson.put("header", getPlayHeaders(play));

            // ================== 修改3：传入集数名称 ==================
            safePutDanmaku(resJson, epName);

            String finalPush = resJson.toString();
            Proxy.log("<b style='color:#2ecc71;'>🚀 [Final:推送 JSON]</b>");
            Proxy.log("<div style='background:#1a1a1a; color:#00ff00; padding:8px; border:1px solid #2ecc71; font-family:monospace;'>"
                    + finalPush + "</div>");

            return finalPush;
        } catch (Exception e) {
            Proxy.log("<b style='color:red;'>❌ [播放解析崩潰]:</b> " + e.getMessage());
            JSONObject errJson = new JSONObject();
            try {
                errJson.put("parse", 1);
                errJson.put("url", originalUrl);
                errJson.put("header", new JSONObject());
                safePutDanmaku(errJson, epName);   // 异常时也尝试添加弹幕
            } catch (Exception ignored) {
            }
            return errJson.toString();
        }
    }

    /**
     * 安全添加弹幕字段，完全不影响主逻辑
     * @param result 播放结果 JSON
     * @param episodeName 从播放列表 ID 中提取的集数名称（例如 "第01集"、"01"）
     */
    private void safePutDanmaku(JSONObject result, String episodeName) {
        try {
            // 从集数名称中提取数字，若为空则默认为 1
            int episode = 1;
            if (!TextUtils.isEmpty(episodeName)) {
                String numStr = episodeName.replaceAll("\\D+", ""); // 去除非数字字符
                if (!TextUtils.isEmpty(numStr)) {
                    episode = Integer.parseInt(numStr);
                }
            }

            // 构造弹幕代理请求 URL
            String title = TextUtils.isEmpty(currentVodName) ? "未知影片" : currentVodName;
            String danmakuUrl = "http://127.0.0.1:9978/proxy?do=danmu&title="
                    + URLEncoder.encode(title, "UTF-8")
                    + "&episode=" + episode;
            result.put("danmaku", danmakuUrl);
        } catch (Exception e) {
            // 弹幕字段添加失败，静默处理，不影响播放
        }
    }

    private JSONObject getPlayHeaders(JSONObject play) {
        JSONObject headJson = play.optJSONObject("play_headers");
        if (headJson == null) {
            headJson = new JSONObject();
            try {
                headJson.put("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                headJson.put("Referer", this.siteUrl + "/");
            } catch (Exception ignored) {
            }
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
                            if (!detailTemplate.isEmpty() && !vId.startsWith("http")) {
                                vod.put("vod_id", detailTemplate.replace("{id}", vId));
                            } else {
                                vod.put("vod_id", vId.startsWith("http") ? vId : this.siteUrl + (vId.startsWith("/") ? "" : "/") + vId);
                            }
                        }

                        vod.put("vod_name", item.optString("name"));

                        String vPic = item.optString("pic");
                        if (!TextUtils.isEmpty(vPic) && vPic.startsWith("//")) vPic = "http:" + vPic;
                        vod.put("vod_pic", vPic);

                        vod.put("vod_remarks", item.optString("remarks"));

                        if (vod.has("vod_id")) list.put(vod);
                    }
                }
            } else {
                Document doc = Jsoup.parse(html);
                String itemRule = rule.optString(prefix + "item", rule.optString("cate_item"));
                Elements items = doc.select(itemRule);
                String detailTemplate = rule.optString("detail_url", "");

                for (Element item : items) {
                    JSONObject smartVod = KaiGeSmart.parseList(item);
                    JSONObject vod = new JSONObject();

                    String vId = extract(item, rule.optString(prefix + "id", rule.optString("cate_id")));
                    if (TextUtils.isEmpty(vId)) vId = smartVod.optString("vod_id");

                    if (!TextUtils.isEmpty(vId)) {
                        if (isSearch && !detailTemplate.isEmpty() && !vId.startsWith("http")) {
                            vod.put("vod_id", detailTemplate.replace("{id}", vId));
                        } else {
                            vod.put("vod_id", vId.startsWith("http") ? vId : this.siteUrl + (vId.startsWith("/") ? "" : "/") + vId);
                        }
                    }

                    String vName = extract(item, rule.optString(prefix + "name", rule.optString("cate_name")));
                    vod.put("vod_name", TextUtils.isEmpty(vName) ? smartVod.optString("vod_name") : vName);

                    String vPic = extract(item, rule.optString(prefix + "pic", rule.optString("cate_pic")));
                    if (TextUtils.isEmpty(vPic)) vPic = smartVod.optString("vod_pic");
                    if (!TextUtils.isEmpty(vPic) && vPic.startsWith("//")) vPic = "http:" + vPic;
                    vod.put("vod_pic", vPic);

                    String vRemarks = extract(item, rule.optString(prefix + "remarks", rule.optString("cate_remarks")));
                    vod.put("vod_remarks", TextUtils.isEmpty(vRemarks) ? smartVod.optString("vod_remarks") : vRemarks);

                    if (vod.has("vod_id")) list.put(vod);
                }
            }
            return new JSONObject().put("list", list).put("page", pg).toString();
        } catch (Exception e) {
            return "{\"list\":[]}";
        }
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
                String content = (root instanceof Document) ? ((Document) root).outerHtml()
                        : (root instanceof Element) ? ((Element) root).outerHtml() : root.toString();
                return KaiGeEngine.doExtract(content, realRule, this.siteUrl).value;
            }
            return "";
        } catch (Exception e) {
            return "";
        }
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
                logger("🚨 [主頁] 警告：JSON 規則中未定義 classes 或格式錯誤");
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

            logger("✅ [主頁] 分類加載成功，共 " + resultClasses.length() + " 個頻道");

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
}