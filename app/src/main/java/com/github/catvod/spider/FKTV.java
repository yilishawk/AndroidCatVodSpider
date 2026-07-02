package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.regex.*;

public class FanKeSpider extends com.github.catvod.crawler.Spider {

    private final String HOST = "https://fktv.me";

    // ================= HOME =================
    @Override
    public String homeContent(boolean filter) {

        List<Class> classes = new ArrayList<>();
        classes.add(new Class("5", "连续剧"));
        classes.add(new Class("6", "电影"));
        classes.add(new Class("4", "综艺"));
        classes.add(new Class("9", "短剧"));

        return Result.string(classes, new ArrayList<>());
    }

    // ================= CATEGORY =================
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {

        List<Vod> list = new ArrayList<>();

        try {
            String url = HOST + "/category/" + tid + "/page/" + pg;
            String html = OkHttp.string(url);

            String clean = html.replace("\\\"", "\"").replace("\\/", "/");

            Pattern p = Pattern.compile("\"item\":(\\{.+?\\})");
            Matcher m = p.matcher(clean);

            while (m.find()) {
                try {
                    String json = m.group(1);
                    if (!json.endsWith("}")) json += "}";

                    JSONObject obj = new JSONObject(json);

                    String path = obj.optString("canonical_path");
                    String id = path.contains("/movie/")
                            ? path.split("/movie/")[1]
                            : obj.optString("id");

                    id = id.replace("/", "___");

                    Vod vod = new Vod();
                    vod.setVodId(id);
                    vod.setVodName(obj.optString("name"));
                    vod.setVodPic(obj.optString("img_y_source"));
                    vod.setVodRemarks(obj.optString("release_at"));

                    list.add(vod);

                } catch (Exception ignored) {}
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return Result.string(list);
    }

    // ================= DETAIL（已修复核心） =================
    @Override
    public String detailContent(List<String> ids) {

        List<Vod> list = new ArrayList<>();

        try {
            String vodId = ids.get(0);
            String url = HOST + "/movie/" + vodId.replace("___", "/");

            String html = OkHttp.string(url);

            Vod vod = new Vod();

            // ===== 基础信息 =====
            vod.setVodName(extractTitle(html));
            vod.setVodPic(extractImg(html));

            // ===== 关键修复：NEXT_DATA =====
            JSONObject nextData = getNextData(html);

            JSONArray links = new JSONArray();
            JSONArray playLinks = new JSONArray();

            if (nextData != null) {
                links = findArray(nextData, "links");
                playLinks = findArray(nextData, "play_links");
            }

            // ===== fallback regex =====
            if (links.length() == 0) {
                links = extractJsonArray(html, "links");
            }
            if (playLinks.length() == 0) {
                playLinks = extractJsonArray(html, "play_links");
            }

            // ===== 组装播放 =====
            List<String> from = new ArrayList<>();
            List<String> urls = new ArrayList<>();

            String movieId = vodId.split("___")[0];

            if (playLinks.length() > 0 && links.length() > 0) {

                from.add("默认线路");

                StringBuilder sb = new StringBuilder();

                for (int i = 0; i < links.length(); i++) {

                    JSONObject ep = links.getJSONObject(i);

                    String name = ep.optString("name", String.valueOf(i + 1));
                    String epId = ep.optString("id");

                    String playUrl;

                    if (i == 0) {
                        playUrl = name + "$" + url;
                    } else {
                        playUrl = name + "$" + movieId + "@" + epId;
                    }

                    sb.append(playUrl);

                    if (i < links.length() - 1) sb.append("#");
                }

                urls.add(sb.toString());
            }

            vod.setVodPlayFrom(String.join("$$$", from));
            vod.setVodPlayUrl(String.join("$$$", urls));

            list.add(vod);

        } catch (Exception e) {
            e.printStackTrace();
        }

        return Result.string(list);
    }

    // ================= NEXT_DATA 提取（关键） =================
    private JSONObject getNextData(String html) {
        try {
            Pattern p = Pattern.compile(
                    "<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>",
                    Pattern.DOTALL
            );

            Matcher m = p.matcher(html);

            if (m.find()) {
                return new JSONObject(m.group(1));
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ================= 递归查找数组 =================
    private JSONArray findArray(Object obj, String key) {

        try {
            if (obj instanceof JSONObject) {
                JSONObject o = (JSONObject) obj;

                if (o.has(key)) return o.getJSONArray(key);

                Iterator<String> it = o.keys();

                while (it.hasNext()) {
                    JSONArray r = findArray(o.get(it.next()), key);
                    if (r != null && r.length() > 0) return r;
                }
            }

            if (obj instanceof JSONArray) {
                JSONArray arr = (JSONArray) obj;

                for (int i = 0; i < arr.length(); i++) {
                    JSONArray r = findArray(arr.get(i), key);
                    if (r != null && r.length() > 0) return r;
                }
            }

        } catch (Exception ignored) {}

        return new JSONArray();
    }

    // ================= fallback regex JSON =================
    private JSONArray extractJsonArray(String html, String key) {
        try {
            Pattern p = Pattern.compile("\"" + key + "\":(\\[.+?\\])");
            Matcher m = p.matcher(html.replace("\\/", "/"));
            if (m.find()) {
                return new JSONArray(m.group(1));
            }
        } catch (Exception ignored) {}
        return new JSONArray();
    }

    // ================= title =================
    private String extractTitle(String html) {
        try {
            Pattern p = Pattern.compile("<h1.*?>(.*?)</h1>");
            Matcher m = p.matcher(html);
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {}
        return "";
    }

    // ================= image =================
    private String extractImg(String html) {
        try {
            Pattern p = Pattern.compile("<img[^>]+src=\"(.*?)\"");
            Matcher m = p.matcher(html);
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {}
        return "";
    }

    // ================= PLAY =================
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {

        try {
            if (id.startsWith("http")) {
                return "{\"parse\":0,\"url\":\"" + id + "\"}";
            }

            String[] arr = id.split("@");
            String movieId = arr[0];
            String linkId = arr[1];

            // 这里简化（原站可能是AES，这里你可再扩展）
            String api = HOST + "/ysapi/movie/detail";

            Map<String, String> params = new HashMap<>();
            params.put("id", movieId);
            params.put("link_id", linkId);

            String res = OkHttp.post(api, params);

            return "{\"parse\":0,\"url\":\"" + res + "\"}";

        } catch (Exception e) {
            return "{\"parse\":0,\"url\":\"\"}";
        }
    }
}
