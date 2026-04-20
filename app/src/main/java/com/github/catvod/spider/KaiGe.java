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
            // 支持遠程鏈接或直接傳入 JSON 字符串
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

    private String getHtml(String url) {
        try {
            Map<String, String> header = new HashMap<>();
            header.put("User-Agent", rule.optString("ua", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"));
            return OkHttp.string(url, header);
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            String url = rule.getString("cate_url").replace("{tid}", tid).replace("{pg}", pg);
            return parseList(getHtml(url), pg, false);
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String url = rule.getString("search_url").replace("{wd}", key);
            return parseList(getHtml(url), "1", true);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 統一解析列表方法
     * @param isSearch 是否為搜索請求，如果是，優先使用 sc_ 開頭的規則
     */
    private String parseList(String html, String pg, boolean isSearch) {
        try {
            Document doc = Jsoup.parse(html);
            JSONArray list = new JSONArray();
            
            // 優先邏輯：如果搜索時規則定義了 sc_item，則使用 sc_item，否則用 cate_item
            String itemRule = isSearch ? rule.optString("sc_item", rule.optString("cate_item")) : rule.optString("cate_item");
            Elements items = doc.select(itemRule);
            
            for (Element item : items) {
                JSONObject vod = new JSONObject();
                // 適配搜索與分類的字段提取
                String idRule = isSearch ? rule.optString("sc_id", rule.optString("cate_id")) : rule.optString("cate_id");
                String nameRule = isSearch ? rule.optString("sc_name", rule.optString("cate_name")) : rule.optString("cate_name");
                String picRule = isSearch ? rule.optString("sc_pic", rule.optString("cate_pic")) : rule.optString("cate_pic");
                String remarkRule = isSearch ? rule.optString("sc_remarks", rule.optString("cate_remarks")) : rule.optString("cate_remarks");

                vod.put("vod_id", extract(item, idRule));
                vod.put("vod_name", extract(item, nameRule));
                vod.put("vod_pic", extract(item, picRule));
                vod.put("vod_remarks", extract(item, remarkRule));
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

            // 線路名提取
            Elements froms = doc.select(rule.optString("dt_from"));
            List<String> fromList = new ArrayList<>();
            for (Element f : froms) {
                String name = f.text().trim();
                if (!name.isEmpty()) fromList.add(name);
            }
            if (fromList.isEmpty()) fromList.add("默認線路");
            vod.put("vod_play_from", join(fromList, "$$$"));

            // 播放地址提取
            Elements urlLists = doc.select(rule.optString("dt_list"));
            List<String> circuits = new ArrayList<>();
            for (Element list : urlLists) {
                List<String> urls = new ArrayList<>();
                Elements as = list.select("a");
                for (Element a : as) {
                    String epName = a.text().trim();
                    String epLink = a.attr("href");
                    if (!epLink.isEmpty()) {
                        urls.add(epName + "$" + epLink);
                    }
                }
                if (!urls.isEmpty()) circuits.add(join(urls, "#"));
            }
            vod.put("vod_play_url", join(circuits, "$$$"));

            JSONArray resList = new JSONArray().put(vod);
            return new JSONObject().put("list", resList).toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String extract(Element root, String ruleStr) {
        try {
            if (ruleStr == null || ruleStr.isEmpty() || root == null) return "";
            
            // 屬性提取語法: selector[attr]
            if (ruleStr.contains("[") && ruleStr.endsWith("]")) {
                int index = ruleStr.lastIndexOf("[");
                String selector = ruleStr.substring(0, index);
                String attr = ruleStr.substring(index + 1, ruleStr.length() - 1);
                Element el = selector.isEmpty() ? root : root.selectFirst(selector);
                if (el != null) {
                    return attr.equalsIgnoreCase("text") ? el.text().trim() : el.attr(attr).trim();
                }
            } else {
                // 純文本提取及備選方案
                Element el = root.selectFirst(ruleStr);
                if (el != null) {
                    String res = el.text().trim();
                    if (res.isEmpty()) res = el.attr("title").trim();
                    if (res.isEmpty()) res = el.attr("alt").trim();
                    return res;
                }
            }
        } catch (Exception e) {
            // 靜默處理
        }
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
