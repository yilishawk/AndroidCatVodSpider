package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Wooyun extends Spider {

    private static final String HOST = "https://wooyun.tv";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private void logger(String msg) {
        try { Proxy.log("[Wooyun] " + msg); } catch (Exception ignored) {}
    }

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Content-Type", "application/json");
        headers.put("Referer", HOST + "/");
        return headers;
    }

    private String buildListBody(String topCode, int page, int pageSize, String searchKey) {
        try {
            JSONObject body = new JSONObject();
            body.put("menuCodeList", new JSONArray());
            body.put("pageIndex", String.valueOf(page));
            body.put("pageSize", pageSize);
            body.put("searchKey", searchKey == null ? "" : searchKey);
            body.put("sortCode", "");
            body.put("topCode", topCode == null ? "" : topCode);
            return body.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @Override
    public void init(Context context, String extend) {
        logger("初始化完成");
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            List<Class> classes = new ArrayList<>();
            classes.add(new Class("tv_series",    "电视剧"));
            classes.add(new Class("movie",         "电影"));
            classes.add(new Class("korean_drama",  "韩剧"));
            classes.add(new Class("short_drama",   "短剧"));
            classes.add(new Class("variety",       "综艺"));
            return Result.string(classes, new ArrayList<>());
        } catch (Exception e) {
            logger("homeContent 异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            int page = Integer.parseInt(pg);
            String body = buildListBody(tid, page, 24, "");
            String res = OkHttp.post(HOST + "/api/proxy?url=/movie/media/search", body, getHeaders()).getBody();
            JSONObject js = new JSONObject(res);
            JSONArray records = js.getJSONObject("data").getJSONArray("records");

            List<Vod> list = new ArrayList<>();
            for (int i = 0; i < records.length(); i++) {
                JSONObject item = records.getJSONObject(i);
                String pic = item.optString("posterUrlS3", "");
                if (TextUtils.isEmpty(pic)) pic = item.optString("posterUrl", "");
                Vod vod = new Vod();
                vod.setVodId(String.valueOf(item.optInt("id")));
                vod.setVodName(item.optString("title", ""));
                vod.setVodPic(pic);
                vod.setVodRemarks(item.optString("episodeStatus", ""));
                list.add(vod);
            }
            return Result.string(list);
        } catch (Exception e) {
            logger("categoryContent 异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String mediaId = ids.get(0);

            // 基本信息
            String detailRes = OkHttp.string(
                    HOST + "/api/proxy?url=/movie/media/base/detail?mediaId=" + mediaId,
                    getHeaders()
            );
            JSONObject info = new JSONObject(detailRes).getJSONObject("data");

            // 集数列表
            String epRes = OkHttp.string(
                    HOST + "/api/proxy?url=/movie/media/video/list?mediaId=" + mediaId + "&lineName=&resolutionCode=",
                    getHeaders()
            );
            JSONArray seasons = new JSONObject(epRes).getJSONArray("data");

            // 组装播放列表
            List<String> fromList = new ArrayList<>();
            List<String> urlList = new ArrayList<>();
            for (int s = 0; s < seasons.length(); s++) {
                JSONObject season = seasons.getJSONObject(s);
                int seasonNo = season.optInt("seasonNo", 1);
                JSONArray epList = season.optJSONArray("videoList");
                if (epList == null || epList.length() == 0) continue;
                fromList.add("第" + seasonNo + "季");
                List<String> urls = new ArrayList<>();
                for (int e = 0; e < epList.length(); e++) {
                    JSONObject ep = epList.getJSONObject(e);
                    String name = ep.optString("remark", "第" + ep.optInt("epNo") + "集");
                    String url = ep.optString("playUrl", "");
                    if (!TextUtils.isEmpty(url)) urls.add(name + "$" + url);
                }
                urlList.add(TextUtils.join("#", urls));
            }

            // 演员/导演
            JSONArray actorsArr = info.optJSONArray("actors");
            JSONArray dirsArr = info.optJSONArray("directors");
            List<String> actors = new ArrayList<>();
            List<String> directors = new ArrayList<>();
            if (actorsArr != null) for (int i = 0; i < actorsArr.length(); i++) actors.add(actorsArr.getString(i));
            if (dirsArr != null) for (int i = 0; i < dirsArr.length(); i++) directors.add(dirsArr.getString(i));

            String pic = info.optString("posterUrlS3", "");
            if (TextUtils.isEmpty(pic)) pic = info.optString("posterUrl", "");

            Vod vod = new Vod();
            vod.setVodId(mediaId);
            vod.setVodName(info.optString("title", ""));
            vod.setVodPic(pic);
            vod.setVodYear(String.valueOf(info.optInt("releaseYear")));
            vod.setVodArea(info.optString("region", ""));
            vod.setVodActor(TextUtils.join(", ", actors));
            vod.setVodDirector(TextUtils.join(", ", directors));
            vod.setVodContent(info.optString("overview", ""));
            vod.setVodRemarks(info.optString("episodeStatus", ""));
            vod.setVodPlayFrom(TextUtils.join("$$$", fromList));
            vod.setVodPlayUrl(TextUtils.join("$$$", urlList));

            return Result.string(vod);
        } catch (Exception e) {
            logger("detailContent 异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String body = buildListBody("", 1, 10, key);
            String res = OkHttp.post(HOST + "/api/proxy?url=/movie/media/search", body, getHeaders()).getBody();
            JSONArray records = new JSONObject(res).getJSONObject("data").getJSONArray("records");

            List<Vod> list = new ArrayList<>();
            for (int i = 0; i < records.length(); i++) {
                JSONObject item = records.getJSONObject(i);
                // 只保留完全匹配
                if (!item.optString("title", "").trim().equals(key.trim())) continue;
                String pic = item.optString("posterUrlS3", "");
                if (TextUtils.isEmpty(pic)) pic = item.optString("posterUrl", "");
                Vod vod = new Vod();
                vod.setVodId(String.valueOf(item.optInt("id")));
                vod.setVodName(item.optString("title", ""));
                vod.setVodPic(pic);
                vod.setVodRemarks(item.optString("episodeStatus", ""));
                list.add(vod);
            }
            return Result.string(list);
        } catch (Exception e) {
            logger("searchContent 异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA);
            return Result.get().url(id).header(headers).string();
        } catch (Exception e) {
            logger("playerContent 异常: " + e.getMessage());
            return Result.get().parse(1).url(id).string();
        }
    }
}
