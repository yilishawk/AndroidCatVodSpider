package com.github.catvod.spider;

import android.text.TextUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.util.ArrayList;
import java.util.List;

public class KaiGeSmart {

    // ================== 安全写入 JSON ==================
    private static void safePut(JSONObject obj, String key, Object value) {
        try {
            if (obj != null && !TextUtils.isEmpty(key) && value != null) {
                obj.put(key, value);
            }
        } catch (Exception ignored) {}
    }

    // ================== 列表智能识别 ==================
    public static JSONObject parseList(Element item) {
        JSONObject vod = new JSONObject();
        try {
            if (item == null) return vod;

            // 标题
            String name = item.select("a[title]").attr("title");
            if (TextUtils.isEmpty(name)) name = item.select("img[alt]").attr("alt");
            if (TextUtils.isEmpty(name)) name = item.text();
            safePut(vod, "vod_name", name.trim());

            // 图片
            String pic = item.select("img").attr("data-src");
            if (TextUtils.isEmpty(pic)) pic = item.select("img").attr("src");
            safePut(vod, "vod_pic", pic);

            // ID / URL
            String id = item.select("a").attr("href");
            safePut(vod, "vod_id", id);

            // 备注
            String remark = item.select(".remarks,.note,.tag").text();
            safePut(vod, "vod_remarks", remark);

        } catch (Exception ignored) {}

        return vod;
    }

    // ================== 详情智能识别 ==================
    public static JSONObject parseDetail(String html) {
        JSONObject vod = new JSONObject();
        try {
            if (TextUtils.isEmpty(html)) return vod;

            Document doc = Jsoup.parse(html);

            // 标题
            String title = doc.select("h1").text();
            if (TextUtils.isEmpty(title)) title = doc.title();
            safePut(vod, "vod_name", title);

            // 图片
            String pic = doc.select("img").attr("src");
            safePut(vod, "vod_pic", pic);

            // 简介
            String content = doc.select(".content,.desc,.detail").text();
            safePut(vod, "vod_content", content);

            // 演员 / 导演 / 年份等
            Elements infos = doc.select("p,li,span");
            for (Element el : infos) {
                String t = el.text();
                if (TextUtils.isEmpty(t)) continue;

                try {
                    if (t.contains("主演")) safePut(vod, "vod_actor", t);
                    if (t.contains("导演")) safePut(vod, "vod_director", t);
                    if (t.contains("地区")) safePut(vod, "vod_area", t);
                    if (t.contains("年份")) safePut(vod, "vod_year", t);
                } catch (Exception ignored) {}
            }

            // ================== 播放列表识别 ==================
            Elements links = doc.select("a[href]");
            List<String> playUrls = new ArrayList<>();
            for (Element a : links) {
                String href = a.attr("href");
                String text = a.text();

                if (TextUtils.isEmpty(href) || TextUtils.isEmpty(text)) continue;

                // 过滤非播放链接
                if (href.contains("javascript")) continue;

                // 识别视频链接
                if (href.contains(".m3u8") || href.contains(".mp4") || href.contains("play")) {
                    playUrls.add(text + "$" + href);
                }
            }

            if (!playUrls.isEmpty()) {
                String playUrl = TextUtils.join("#", playUrls);
                safePut(vod, "vod_play_from", "智能线路");
                safePut(vod, "vod_play_url", playUrl);
            }

        } catch (Exception ignored) {}

        return vod;
    }

    // ================== JSON列表解析（备用） ==================
    public static String parseJsonList(JSONArray list) {
        try {
            JSONArray result = new JSONArray();
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.getJSONObject(i);
                JSONObject vod = new JSONObject();

                safePut(vod, "vod_id", item.optString("id"));
                safePut(vod, "vod_name", item.optString("name"));
                safePut(vod, "vod_pic", item.optString("pic"));
                safePut(vod, "vod_remarks", item.optString("remarks"));

                result.put(vod);
            }

            JSONObject res = new JSONObject();
            safePut(res, "list", result);
            return res.toString();

        } catch (Exception e) {
            return "{\"list\":[]}";
        }
    }
}