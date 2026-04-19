package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.crawler.Spider;
import com.github.catvod.utils.okhttp.OkHttp;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONArray;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.util.*;

/**
 * 凱哥萬能規則引擎 - 自成一派版
 * 核心邏輯：底層 JAR 固化，業務邏輯由外部 JSON 驅動
 */
public class KaiGe extends Spider {
    private JSONObject rule = new JSONObject();

    @Override
    public void init(Context context, String extend) {
        try {
            // 支持從 URL 或 直接字符串加載 JSON 規則
            String json = extend.startsWith("http") ? OkHttp.string(extend, null) : extend;
            this.rule = JSON.parseObject(json);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public String homeContent(boolean filter) {
        JSONObject result = new JSONObject();
        result.put("class", rule.getJSONArray("classes"));
        if (rule.containsKey("filter")) {
            result.put("filters", rule.getJSONObject("filter"));
        }
        return result.toJSONString();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
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
        url = url.replaceAll("\\{.*?\\}", ""); // 清理未匹配的佔位符
        return parseList(OkHttp.string(url, null), pg);
    }

    @Override
    public String searchContent(String key, boolean quick) {
        String url = rule.getString("search_url").replace("{wd}", key);
        return parseList(OkHttp.string(url, null), "1");
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
                list.add(vod);
            }
            JSONObject result = new JSONObject();
            result.put("list", list);
            result.put("page", pg);
            return result.toJSONString();
        } catch (Exception e) { return ""; }
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

            // 解析播放源與集數
            Elements froms = doc.select(rule.getString("dt_from"));
            List<String> fromList = new ArrayList<>();
            for (Element f : froms) fromList.add(f.text());
            vod.put("vod_play_from", String.join("$$$", fromList));

            Elements urlLists = doc.select(rule.getString("dt_list"));
            List<String> circuits = new ArrayList<>();
            for (Element list : urlLists) {
                List<String> urls = new ArrayList<>();
                for (Element a : list.select("a")) {
                    urls.add(a.text() + "$" + a.attr("href"));
                }
                circuits.add(String.join("#", urls));
            }
            vod.put("vod_play_url", String.join("$$$", circuits));

            JSONObject result = new JSONObject();
            result.put("list", new JSONArray(Collections.singletonList(vod)));
            return result.toJSONString();
        } catch (Exception e) { return ""; }
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
        } catch (Exception e) { return ""; }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        JSONObject result = new JSONObject();
        result.put("parse", rule.getIntValue("parse"));
        result.put("url", id);
        return result.toJSONString();
    }
}
