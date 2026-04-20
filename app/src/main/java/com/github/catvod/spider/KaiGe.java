package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.util.*;
import java.net.URLEncoder;

public class KaiGe extends Spider {
    private JSONObject rule = new JSONObject();
    // OK影視內置調試端口 9978
    private final String logServer = "http://127.0.0.1:9978/debug";

    @Override
    public void init(Context context, String extend) {
        try {
            String json = extend.startsWith("http") ? OkHttp.string(extend, null) : extend;
            this.rule = new JSONObject(json);
            debugLog("--- KaiGe 引擎初始化成功 ---");
        } catch (Exception e) {
            debugLog("初始化失敗: " + e.getMessage());
        }
    }

    /**
     * OK影視專用調試日誌
     */
    private void debugLog(String msg) {
        // 同步打一份到系統日誌，防止 9978 沒開啟
        android.util.Log.d("KaiGe_Spider", msg);
        
        if (rule.optInt("debug", 0) != 1) return;
        try {
            Map<String, String> params = new HashMap<>();
            params.put("data", msg); 
            // 發送到 OK影視 9978 端口
            OkHttp.post(logServer, params);
        } catch (Exception ignored) {}
    }

    private String getHtml(String url) {
        try {
            Map<String, String> header = new HashMap<>();
            header.put("User-Agent", rule.optString("ua", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"));
            return OkHttp.string(url, header);
        } catch (Exception e) {
            debugLog("網絡請求失敗: " + url);
            return "";
        }
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            result.put("class", rule.optJSONArray("classes"));
            if (rule.has("filter")) {
                result.put("filters", rule.optJSONObject("filter"));
            }
            return result.toString();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            String url = rule.getString("cate_url").replace("{tid}", tid).replace("{pg}", pg);
            debugLog("加載分類: " + url);
            return parseList(getHtml(url), pg, false);
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            // 解決抓不到包的核心：對關鍵詞進行網址編碼
            String encodedKey = URLEncoder.encode(key, "UTF-8");
            String url = rule.getString("search_url").replace("{wd}", encodedKey);
            
            debugLog("發起搜索: " + url + " (原關鍵詞: " + key + ")");
            
            String html = getHtml(url);
            if (html == null || html.isEmpty()) {
                debugLog("搜索返回 HTML 為空，請檢查網絡或 UA");
                return "";
            }
            return parseList(html, "1", true);
        } catch (Exception e) {
            debugLog("搜索執行異常: " + e.getMessage());
            return "";
        }
    }

    private String parseList(String html, String pg, boolean isSearch) {
        try {
            Document doc = Jsoup.parse(html);
            JSONArray list = new JSONArray();

            // 搜索與分類字段分離
            String itemRule = isSearch ? rule.optString("sc_item", rule.optString("cate_item")) : rule.optString("cate_item");
            Elements items = doc.select(itemRule);
            debugLog("列表解析: 模式=" + (isSearch ? "搜索" : "分類") + ", 數量=" + items.size());

            for (Element item : items) {
                JSONObject vod = new JSONObject();
                String idRule = isSearch ? rule.optString("sc_id", rule.optString("cate_id")) : rule.optString("cate_id");
                String nameRule = isSearch ? rule.optString("sc_name", rule.optString("cate_name")) : rule.optString("cate_name");
                String picRule = isSearch ? rule.optString("sc_pic", rule.optString("cate_pic")) : rule.optString("cate_pic");
                String remarkRule = isSearch ? rule.optString("sc_remarks", rule.optString("cate_remarks")) : rule.optString("cate_remarks");

                String name = extract(item, nameRule);
                String id = extract(item, idRule);
                
                // 只有抓到標題和ID才加入列表，並打日誌
                if (!name.isEmpty() && !id.isEmpty()) {
                    debugLog("-> 抓取 [ " + name + " ] ID: " + id);
                    vod.put("vod_id", id);
                    vod.put("vod_name", name);
                    vod.put("vod_pic", extract(item, picRule));
                    vod.put("vod_remarks", extract(item, remarkRule));
                    list.put(vod);
                }
            }
            JSONObject result = new JSONObject();
            result.put("list", list);
            result.put("page", pg);
            return result.toString();
        } catch (Exception e) {
            debugLog("列表解析崩潰: " + e.getMessage());
            return "";
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String url = ids.get(0);
            if (!url.startsWith("http")) url = rule.getString("host") + url;
            debugLog("解析詳情頁: " + url);
            
            Document doc = Jsoup.parse(getHtml(url));
            JSONObject vod = new JSONObject();
            vod.put("vod_id", ids.get(0));
            vod.put("vod_name", extract(doc, rule.optString("dt_name")));
            vod.put("vod_pic", extract(doc, rule.optString("dt_pic")));
            vod.put("type_name", extract(doc, rule.optString("dt_type")));
            vod.put("vod_year", extract(doc, rule.optString("dt_year")));
            vod.put("vod_area", extract(doc, rule.optString("dt_area")));
            vod.put("vod_actor", extract(doc, rule.optString("dt_actor")));
            vod.put("vod_director", extract(doc, rule.optString("dt_director")));
            vod.put("vod_content", extract(doc, rule.optString("dt_content")));

            // 播放線路
            Elements froms = doc.select(rule.optString("dt_from"));
            List<String> fromList = new ArrayList<>();
            for (Element f : froms) {
                String name = f.text().trim();
                if (!name.isEmpty()) fromList.add(name);
            }
            if (fromList.isEmpty()) fromList.add("默認線路");
            vod.put("vod_play_from", join(fromList, "$$$"));

            // 播放清單
            Elements urlLists = doc.select(rule.optString("dt_list"));
            List<String> circuits = new ArrayList<>();
            for (Element list : urlLists) {
                List<String> urls = new ArrayList<>();
                Elements as = list.select("a");
                for (Element a : as) {
                    String epName = a.text().trim();
                    String epLink = a.attr("href");
                    if (!epLink.isEmpty()) urls.add(epName + "$" + epLink);
                }
                if (!urls.isEmpty()) circuits.add(join(urls, "#"));
            }
            vod.put("vod_play_url", join(circuits, "$$$"));

            return new JSONObject().put("list", new JSONArray().put(vod)).toString();
        } catch (Exception e) {
            debugLog("詳情頁出錯: " + e.getMessage());
            return "";
        }
    }

    private String extract(Element root, String ruleStr) {
        try {
            if (ruleStr == null || ruleStr.isEmpty() || root == null) return "";
            if (ruleStr.contains("[") && ruleStr.endsWith("]")) {
                int index = ruleStr.lastIndexOf("[");
                String selector = ruleStr.substring(0, index);
                String attr = ruleStr.substring(index + 1, ruleStr.length() - 1);
                Element el = selector.isEmpty() ? root : root.selectFirst(selector);
                if (el != null) return attr.equalsIgnoreCase("text") ? el.text().trim() : el.attr(attr).trim();
            } else {
                Element el = root.selectFirst(ruleStr);
                if (el != null) {
                    String res = el.text().trim();
                    if (res.isEmpty()) res = el.attr("title").trim();
                    if (res.isEmpty()) res = el.attr("alt").trim();
                    return res;
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            JSONObject result = new JSONObject();
            result.put("parse", rule.optInt("parse", 0));
            result.put("url", id);
            return result.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String join(List<String> list, String del) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(del);
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
