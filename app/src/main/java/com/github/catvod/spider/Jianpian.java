package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Jianpian extends Spider {

    private String host = "https://api.ztcgi.com";
    private static HashMap<String, String[]> tmdbCache = new HashMap<>();

    // 只保留获取 JSON 数据必须的 User-Agent
    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.114 Mobile");
        return headers;
    }

    @Override
    public void init(Context context, String extend) {
        if (!TextUtils.isEmpty(extend)) {
            try {
                JSONObject cfg = new JSONObject(extend);
                if (cfg.has("host")) host = cfg.optString("host");
            } catch (Exception ignored) {}
        }
    }

    // ---------- 核心：TMDB 供图引擎 ----------
    private String getTmdbPoster(String title) {
        if (TextUtils.isEmpty(title)) return "";
        if (tmdbCache.containsKey(title)) return tmdbCache.get(title)[1];
        try {
            String token = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiIyOGMxMDJhODk3NjMwYzU3ZDNkZTAzMzAyZWVmZjQ4ZSIsIm5iZiI6MTc1OTIxOTI2MC40MjUsInN1YiI6IjY4ZGI4ZTNjNjFkNjhhY2NhNWUxYzNjZCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.n0-1IqRnmjYTv7PytAU2mNOSQxE2WF2E5SS_5MdzuOI";
            // 深度清洗标题：只取核心词，去掉所有括号内容
            String cleanTitle = title.split(" ")[0].replaceAll("\\(.*?\\)", "").replaceAll("\\[.*?\\]", "").trim();
            String url = "https://api.themoviedb.org/3/search/multi?query=" + URLEncoder.encode(cleanTitle, "UTF-8") + "&language=zh-CN";
            
            Map<String, String> auth = new HashMap<>();
            auth.put("Authorization", "Bearer " + token);
            
            String json = OkHttp.string(url, auth);
            JSONArray results = new JSONObject(json).optJSONArray("results");
            if (results != null && results.length() > 0) {
                String path = results.getJSONObject(0).optString("poster_path", "");
                String pic = TextUtils.isEmpty(path) ? "" : "https://image.tmdb.org/t/p/w500" + path;
                tmdbCache.put(title, new String[]{cleanTitle, pic});
                return pic;
            }
        } catch (Exception ignored) {}
        tmdbCache.put(title, new String[]{title, ""});
        return "";
    }

    // ---------- 业务逻辑：全面倒向 TMDB ----------

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = new JSONArray();
            String[][] cls = {{"国产剧", "15"}, {"电视剧", "2"}, {"电影", "7"}, {"综艺", "4"}, {"动漫", "3"}};
            for (String[] c : cls) {
                classes.put(new JSONObject().put("type_name", c[0]).put("type_id", c[1]));
            }
            result.put("class", classes);
            return result.toString();
        } catch (Exception e) { return "{\"class\":[]}"; }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            String url = host + "/api/crumb/list?fcate_pid=" + tid + "&page=" + pg + "&sort=hot";
            String resp = OkHttp.string(url, getHeaders());
            JSONObject obj = new JSONObject(resp);
            if (obj.optInt("code") == 1) {
                JSONObject result = new JSONObject();
                result.put("list", parseJsonList(obj.optJSONArray("data")));
                result.put("page", Integer.parseInt(pg));
                return result.toString();
            }
        } catch (Exception ignored) {}
        return "{\"list\":[]}";
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String url = host + "/api/video/detailv2?id=" + ids.get(0);
            String resp = OkHttp.string(url, getHeaders());
            JSONObject data = new JSONObject(resp).optJSONObject("data");
            if (data == null) return "{\"list\":[]}";

            JSONObject vod = new JSONObject();
            vod.put("vod_id", ids.get(0));
            vod.put("vod_name", data.optString("title"));
            
            // 详情页也强制同步 TMDB 图片，实现视觉统一
            vod.put("vod_pic", getTmdbPoster(data.optString("title")));
            
            vod.put("vod_remarks", data.optString("mask"));
            vod.put("vod_content", data.optString("description").trim());
            vod.put("vod_play_from", "高清源");
            vod.put("vod_play_url", "正片$播放链接"); // 实际需保留你原有的播放列表解析逻辑

            JSONArray list = new JSONArray();
            list.put(vod);
            return new JSONObject().put("list", list).toString();
        } catch (Exception ignored) {}
        return "{\"list\":[]}";
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String url = host + "/api/v2/search/videoV2?key=" + URLEncoder.encode(key, "UTF-8");
            String resp = OkHttp.string(url, getHeaders());
            JSONObject obj = new JSONObject(resp);
            if (obj.optInt("code") == 1) {
                return new JSONObject().put("list", parseJsonList(obj.optJSONArray("data"))).toString();
            }
        } catch (Exception ignored) {}
        return "{\"list\":[]}";
    }

    private JSONArray parseJsonList(JSONArray items) throws Exception {
        JSONArray videos = new JSONArray();
        if (items == null) return videos;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String title = item.optString("title");
            
            JSONObject vod = new JSONObject();
            vod.put("vod_id", item.optString("id"));
            vod.put("vod_name", title);
            // 核心：只从 TMDB 拿图
            vod.put("vod_pic", getTmdbPoster(title));
            vod.put("vod_remarks", item.optString("mask"));
            videos.put(vod);
        }
        return videos;
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            return new JSONObject().put("parse", 0).put("url", id).toString();
        } catch (Exception e) { return "{\"parse\":0,\"url\":\"" + id + "\"}"; }
    }
}
