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

public class KaiGe extends Spider {
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

    private Map<String, String> getHeaders() {
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", rule.optString("ua", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"));
        if (rule.has("headers")) {
            JSONObject hd = rule.optJSONObject("headers");
            Iterator<String> keys = hd.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                header.put(key, hd.optString(key));
            }
        }
        return header;
    }

    private String getPicUrl(String pic) {
        if (pic.isEmpty()) return "";
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

    private String getHtml(String url) {
        try {
            Map<String, String> header = getHeaders();
            if (url.contains(";method:post")) {
                String[] parts = url.split(";");
                String realUrl = parts[0];
                Map<String, String> params = new HashMap<>();
                for (String p : parts) {
                    if (p.startsWith("data:")) {
                        String dataStr = p.substring(5);
                        for (String kv : dataStr.split("&")) {
                            String[] sp = kv.split("=");
                            if (sp.length > 1) params.put(sp[0], sp[1]);
                        }
                    }
                }
                return OkHttp.post(realUrl, params, header).getBody();
            }
            return OkHttp.string(url, header);
        } catch (Exception e) {
            return "";
        }
    }

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
            
            // --- 優化篩選替換邏輯 ---
            if (extend != null && !extend.isEmpty()) {
                for (String key : extend.keySet()) {
                    url = url.replace("{" + key + "}", extend.get(key));
                }
            }
            // 兜底清理：如果 JSON 裡寫了 {area} 但篩選沒選，把剩下的佔位符清理掉
            url = url.replaceAll("\\{.*?\\}", ""); 
            
            return parseList(getHtml(url), pg, false);
        } catch (Exception e) { return ""; }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String url = rule.optString("search_url").replace("{wd}", key);
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
            vod.put("vod_play_from", join(fromList, "$$$"));

            Elements urlLists = doc.select(rule.optString("dt_list"));
            List<String> circuits = new ArrayList<>();
            for (Element list : urlLists) {
                List<String> urls = new ArrayList<>();
                for (Element a : list.select("a")) {
                    String name = a.text().trim();
                    String link = a.attr("href");
                    if (!link.isEmpty()) urls.add(name + "$" + link);
                }
                if (!urls.isEmpty()) circuits.add(join(urls, "#"));
            }
            vod.put("vod_play_url", join(circuits, "$$$"));

            return new JSONObject().put("list", new JSONArray().put(vod)).toString();
        } catch (Exception e) { return ""; }
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
                if (el != null) return el.text().trim().isEmpty() ? el.attr("title").trim() : el.text().trim();
            }
        } catch (Exception e) {}
        return "";
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            JSONObject result = new JSONObject().put("parse", rule.optInt("parse", 0)).put("url", id);
            if (rule.has("headers")) result.put("header", rule.optJSONObject("headers").toString());
            return result.toString();
        } catch (Exception e) { return ""; }
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
