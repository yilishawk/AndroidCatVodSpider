package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.net.OkHttp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.*;
import java.util.regex.*;

public class FKTV extends com.github.catvod.crawler.Spider {

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

                    org.json.JSONObject obj = new org.json.JSONObject(json);

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

    // ================= DETAIL =================
    @Override
    public String detailContent(List<String> ids) {

        List<Vod> list = new ArrayList<>();

        try {
            String id = ids.get(0);
            String url = HOST + "/movie/" + id.replace("___", "/");

            String html = OkHttp.string(url);
            Document doc = Jsoup.parse(html);

            Vod vod = new Vod();

            vod.setVodName(doc.select("h1").text());
            vod.setVodPic(doc.select("img").attr("src"));

            Pattern p = Pattern.compile("\"links\":(\\[.+?\\])");
            Matcher m = p.matcher(html.replace("\\/", "/"));

            List<String> playFrom = new ArrayList<>();
            List<String> playUrl = new ArrayList<>();

            if (m.find()) {
                String arr = m.group(1);
                org.json.JSONArray links = new org.json.JSONArray(arr);

                playFrom.add("默认线路");

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < links.length(); i++) {
                    org.json.JSONObject e = links.getJSONObject(i);

                    String name = e.optString("name", String.valueOf(i + 1));
                    String epId = e.optString("id");

                    sb.append(name).append("$").append(id).append("@").append(epId);

                    if (i < links.length() - 1) sb.append("#");
                }

                playUrl.add(sb.toString());
            }

            vod.setVodPlayFrom(String.join("$$$", playFrom));
            vod.setVodPlayUrl(String.join("$$$", playUrl));

            list.add(vod);

        } catch (Exception e) {
            e.printStackTrace();
        }

        return Result.string(list);
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
