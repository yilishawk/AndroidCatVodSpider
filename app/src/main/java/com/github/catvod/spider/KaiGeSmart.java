package com.github.catvod.spider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import android.text.TextUtils;

import java.util.*;

public class KaiGeSmart {

    // 🚀 ================== 列表 ==================
    public static String buildResult(String data, String key) {
        try {
            if (TextUtils.isEmpty(data)) return empty();

            String trim = data.trim();

            // ✅ JSON 模式
            if (trim.startsWith("{") || trim.startsWith("[")) {
                return parseJsonList(trim, key);
            }

            // ✅ HTML 模式
            return parseHtmlList(trim, key);

        } catch (Exception e) {
            return empty();
        }
    }

    private static String parseJsonList(String data, String key) {
        try {
            JSONObject json = new JSONObject(data);

            JSONArray arr = json.optJSONArray("list");
            if (arr == null) arr = json.optJSONArray("data");

            if (arr == null) return data;

            JSONArray list = new JSONArray();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.getJSONObject(i);

                String id = it.optString("vod_id", it.optString("id"));
                String name = it.optString("vod_name", it.optString("name"));
                String pic = it.optString("vod_pic", it.optString("pic"));
                String remarks = it.optString("vod_remarks", it.optString("remarks", ""));

                if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name)) continue;
                if (!TextUtils.isEmpty(key) && !name.contains(key)) continue;

                JSONObject vod = new JSONObject();
                vod.put("vod_id", id);
                vod.put("vod_name", name);
                vod.put("vod_pic", fixUrl(pic));
                vod.put("vod_remarks", remarks);

                list.put(vod);
            }

            return new JSONObject().put("list", list).toString();

        } catch (Exception e) {
            return empty();
        }
    }

    private static String parseHtmlList(String html, String key) {
        Document doc = Jsoup.parse(html);
        JSONArray list = new JSONArray();

        Elements items = doc.select(
            ".module-item, .vodlist_item, .myui-vodlist__item, .stui-vodlist__item, li"
        );

        if (items.isEmpty()) {
            items = doc.select("a[href*='vod'], a[href*='detail']");
        }

        for (Element el : items) {
            JSONObject vod = parseList(el);

            if (vod.has("vod_name")) {
                if (!TextUtils.isEmpty(key) && !vod.optString("vod_name").contains(key)) continue;
                list.put(vod);
            }
        }

        return new JSONObject().put("list", list).toString();
    }

    // 🚀 ================== 列表单项 ==================
    public static JSONObject parseList(Element el) {
        JSONObject vod = new JSONObject();

        try {
            String id = findUrl(el);
            String name = findTitle(el);
            String pic = findPic(el);

            if (TextUtils.isEmpty(name) || TextUtils.isEmpty(id)) return vod;

            vod.put("vod_id", id);
            vod.put("vod_name", name);
            vod.put("vod_pic", pic);

            String remarks = findRemarks(el);
            vod.put("vod_remarks", remarks);

        } catch (Exception ignored) {}

        return vod;
    }

    // 🚀 ================== 详情 ==================
    public static String buildDetail(String html) {
        try {
            JSONObject result = new JSONObject();
            JSONArray list = new JSONArray();

            list.put(parseDetail(html));
            result.put("list", list);

            return result.toString();
        } catch (Exception e) {
            return empty();
        }
    }

    public static JSONObject parseDetail(String html) {
        JSONObject vod = new JSONObject();

        try {
            Document doc = Jsoup.parse(html);

            vod.put("vod_name", findTitle(doc));
            vod.put("vod_pic", findPic(doc));
            vod.put("vod_content", findContent(doc));

            parseMeta(doc, vod);
            parsePlaylist(doc, vod);

        } catch (Exception ignored) {}

        return vod;
    }

    // 🚀 ================== 播放列表 ==================
    private static void parsePlaylist(Document doc, JSONObject vod) {
        try {
            List<String> from = new ArrayList<>();
            List<String> urls = new ArrayList<>();

            Elements blocks = doc.select(
                ".playlist, .play-list, .module-play-list, .content_playlist"
            );

            if (blocks.isEmpty()) {
                String all = findAllLinks(doc);
                if (!TextUtils.isEmpty(all)) {
                    from.add("默认线路");
                    urls.add(all);
                }
            } else {
                for (int i = 0; i < blocks.size(); i++) {
                    String links = findAllLinks(blocks.get(i));

                    if (!TextUtils.isEmpty(links)) {
                        from.add("线路" + (i + 1));
                        urls.add(links);
                    }
                }
            }

            vod.put("vod_play_from", TextUtils.join("$$$", from));
            vod.put("vod_play_url", TextUtils.join("$$$", urls));

        } catch (Exception ignored) {}
    }

    private static String findAllLinks(Element root) {
        StringBuilder sb = new StringBuilder();

        for (Element a : root.select("a")) {
            String name = a.text().trim();
            String href = a.attr("href");

            if (TextUtils.isEmpty(href)) continue;

            // 🚀 过滤垃圾
            if (href.contains("javascript")) continue;
            if (name.length() > 20) continue;

            if (!href.contains("play") && !href.contains("vod")) continue;

            if (sb.length() > 0) sb.append("#");
            sb.append(name).append("$").append(href);
        }

        return sb.toString();
    }

    // 🚀 ================== 辅助 ==================

    private static String findTitle(Element el) {
        String t = el.attr("title");

        if (TextUtils.isEmpty(t)) t = el.select("img").attr("alt");
        if (TextUtils.isEmpty(t)) t = el.text();

        return t.trim();
    }

    private static String findUrl(Element el) {
        Element a = el.selectFirst("a[href]");
        return a != null ? a.attr("href") : "";
    }

    private static String findPic(Element el) {
        Elements imgs = el.select("img");

        for (Element img : imgs) {
            String src = img.attr("data-src");
            if (TextUtils.isEmpty(src)) src = img.attr("src");

            if (isValidPic(src)) return fixUrl(src);
        }

        return "";
    }

    private static boolean isValidPic(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String u = url.toLowerCase();
        return !u.contains("gif") && !u.contains("base64");
    }

    private static String findRemarks(Element el) {
        Element r = el.selectFirst(".remarks, .tag, .label, .state");
        return r != null ? r.text().trim() : "";
    }

    private static String findContent(Document doc) {
        Elements els = doc.select(
            ".content, .vod_content, .detail-content, #desc"
        );

        String best = "";
        for (Element e : els) {
            if (e.text().length() > best.length()) {
                best = e.text();
            }
        }

        return best;
    }

    private static void parseMeta(Document doc, JSONObject vod) {
        Elements nodes = doc.select("p, li");

        for (Element n : nodes) {
            String t = n.text();

            if (t.contains("主演")) vod.put("vod_actor", t);
            if (t.contains("导演")) vod.put("vod_director", t);
            if (t.contains("地区")) vod.put("vod_area", t);
            if (t.contains("年份")) vod.put("vod_year", t);
        }
    }

    private static String fixUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        if (url.startsWith("//")) return "http:" + url;
        return url;
    }

    private static String empty() {
        return "{\"list\":[]}";
    }
}