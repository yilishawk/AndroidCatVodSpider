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

/**
 * 凱哥萬能規則引擎 - 穩健兼容版 (org.json)
 */
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
            if (filter && extend != null) {
                for (String key : extend.keySet()) {
                    String val = extend.get(key);
                    if (url.contains("{" + key + "}")) {
                        url = url.replace("{" + key + "}", val);
                    } else {
                        url += (url.contains("?") ? "&" : "?") + key + "=" + val;
                    }
                }
            }
            url = url.replaceAll("\\{.*?\\}", "");
            return parseList(OkHttp.string(url, null), pg);
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String url = rule.getString("search_url").replace("{wd}", key);
            return parseList(OkHttp.string(url, null), "1");
        } catch (Exception e) {
            return "";
        }
    }

    private String parseList(String html, String pg) {
        try {
            Document doc = Jsoup.parse(html);
            JSONArray list = new JSONArray();
            Elements items = doc.select(rule.getString("cate_item"));
            for (Element item : items) {
                JSONObject vod = new JSONObject();
                vod.put("vod_id", extract(item, rule.getString("cate_id")));
                vod.put("vod_name", extract(item, rule.getString("cate_name")));
                vod.put("vod_pic", extract(item, rule.getString("cate_pic")));
                vod.put("vod_remarks", extract(item, rule.getString("cate_remarks")));
                list.put(vod);
            }
            JSONObject result = new JSONObject();
            result.put("list", list);
            result.put("page", pg);
            return result.toString();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String url = ids.get(0);
            if (!url.startsWith("http")) url = rule.getString("host") + url;
            Document doc = Jsoup.parse(OkHttp.string(url, null));
            JSONObject vod = new JSONObject();
            vod.put("vod_id", ids.get(0));
            vod.put("vod_name", extract(doc, rule.getString("dt_name")));
            vod.put("vod_pic", extract(doc, rule.getString("dt_pic")));
            vod.put("type_name", extract(doc, rule.getString("dt_type")));
            vod.put("vod_year", extract(doc, rule.getString("dt_year")));
            vod.put("vod_area", extract(doc, rule.getString("dt_area")));
            vod.put("vod_actor", extract(doc, rule.getString("dt_actor")));
            vod.put("vod_director", extract(doc, rule.getString("dt_director")));
            vod.put("vod_content", extract(doc, rule.getString("dt_content")));

            Elements froms = doc.select(rule.getString("dt_from"));
            List<String> fromList = new ArrayList<>();
            for (Element f : froms) fromList.add(f.text());
            vod.put("vod_play_from", join(fromList, "$$$"));

            Elements urlLists = doc.select(rule.getString("dt_list"));
            List<String> circuits = new ArrayList<>();
            for (Element list : urlLists) {
                List<String> urls = new ArrayList<>();
                for (Element a : list.select("a")) {
                    urls.add(a.text() + "$" + a.attr("href"));
                }
                circuits.add(join(urls, "#"));
            }
            vod.put("vod_play_url", join(circuits, "$$$"));

            JSONObject result = new JSONObject();
            JSONArray list = new JSONArray();
            list.put(vod);
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String extract(Element root, String ruleStr) {
        try {
            if (ruleStr == null || ruleStr.isEmpty()) return "";
            if (ruleStr.contains("[") && ruleStr.endsWith("]")) {
                String attr = ruleStr.substring(ruleStr.lastIndexOf("[") + 1, ruleStr.length() - 1);
                String selector = ruleStr.substring(0, ruleStr.lastIndexOf("["));
                Element el = selector.isEmpty() ? root : root.selectFirst(selector);
                return el != null ? el.attr(attr) : "";
            }
            Element el = root.selectFirst(ruleStr);
            return el != null ? el.text() : "";
        } catch (Exception e) {
            return "";
        }
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

    // 兼容低版本 Android 的 join 方法
    private String join(List<String> list, String del) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(del);
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
