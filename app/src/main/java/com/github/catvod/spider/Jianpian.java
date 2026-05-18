package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import com.github.catvod.utils.Json;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ZT-API 爬虫 (详情加速版)
 * 站点: https://api.ztcgi.com
 */
public class Jianpian extends Spider {

    private String host = "https://api.ztcgi.com";
    private String imgHost = "https://img.jgsfnl.com";
    private Map<String, String> headers;

    public Jianpian() {
        headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 9; V2196A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.114 Mobile Safari/537.36");
        headers.put("Referer", "https://api.ztcgi.com/");
        headers.put("Connection", "Keep-Alive");
    }

    @Override
    public void init(Context context, String extend) {
        // 可扩展配置
        if (!TextUtils.isEmpty(extend)) {
            try {
                JSONObject cfg = new JSONObject(extend);
                if (cfg.has("host")) host = cfg.optString("host");
                if (cfg.has("imgHost")) imgHost = cfg.optString("imgHost");
            } catch (Exception e) {
                SpiderDebug.log("[ZT-API] 解析扩展配置失败: " + e.getMessage());
            }
        }
        SpiderDebug.log("[ZT-API] 初始化完成，host: " + host);
    }

    public String getName() {
        return "ZT-API(详情加速版)";
    }

    // ---------- 工具方法 ----------
    private String fetch(String url) throws Exception {
        return OkHttp.string(url, headers);
    }

    private String getImgUrl(String path) {
        if (TextUtils.isEmpty(path)) return "";
        String p = path.trim();
        if (!p.startsWith("/")) p = "/" + p;
        return imgHost + p;
    }

    // ---------- 首页 ----------
    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = new JSONArray();

            String[][] cls = {
                    {"国产剧", "15"},
                    {"电视剧", "2"},
                    {"喜剧电影", "7"},
                    {"综艺", "4"},
                    {"动漫", "3"}
            };
            for (String[] c : cls) {
                JSONObject obj = new JSONObject();
                obj.put("type_name", c[0]);
                obj.put("type_id", c[1]);
                classes.put(obj);
            }
            result.put("class", classes);

            if (filter) {
                JSONObject filters = new JSONObject();

                // 年份选项
                JSONArray yearOptions = new JSONArray();
                String[][] years = {{"全部", ""}, {"2026", "2026"}, {"2025", "107"}, {"2024", "119"}, {"2023", "153"}, {"2022", "101"}};
                for (String[] y : years) {
                    JSONObject opt = new JSONObject();
                    opt.put("n", y[0]);
                    opt.put("v", y[1]);
                    yearOptions.put(opt);
                }

                // 排序选项
                JSONArray sortOptions = new JSONArray();
                String[][] sorts = {{"热门", "hot"}, {"评分", "rating"}, {"更新", "update"}};
                for (String[] s : sorts) {
                    JSONObject opt = new JSONObject();
                    opt.put("n", s[0]);
                    opt.put("v", s[1]);
                    sortOptions.put(opt);
                }

                // 电视剧类型选项
                JSONArray cateOptions = new JSONArray();
                String[][] cates = {{"全部", "2"}, {"国产", "15"}, {"港台", "16"}};
                for (String[] c : cates) {
                    JSONObject opt = new JSONObject();
                    opt.put("n", c[0]);
                    opt.put("v", c[1]);
                    cateOptions.put(opt);
                }

                // 国产剧筛选
                JSONArray filter15 = new JSONArray();
                filter15.put(createFilter("year", "年代", yearOptions));
                filter15.put(createFilter("sort", "排序", sortOptions));
                filters.put("15", filter15);

                // 电视剧筛选
                JSONArray filter2 = new JSONArray();
                filter2.put(createFilter("cateId", "类型", cateOptions));
                filter2.put(createFilter("year", "年代", yearOptions));
                filter2.put(createFilter("sort", "排序", sortOptions));
                filters.put("2", filter2);

                // 喜剧电影、综艺、动漫筛选
                JSONArray filterOther = new JSONArray();
                filterOther.put(createFilter("year", "年代", yearOptions));
                filterOther.put(createFilter("sort", "排序", sortOptions));
                filters.put("7", filterOther);
                filters.put("4", filterOther);
                filters.put("3", filterOther);

                result.put("filters", filters);
            }
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log("[ZT-API] homeContent 错误: " + e.getMessage());
            return "{\"class\":[], \"filters\":{}}";
        }
    }

    private JSONObject createFilter(String key, String name, JSONArray values) throws Exception {
        JSONObject f = new JSONObject();
        f.put("key", key);
        f.put("name", name);
        f.put("value", values);
        return f;
    }

    // ---------- 分类列表 ----------
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            String fcatePid = tid;
            if (extend != null && extend.containsKey("cateId")) {
                fcatePid = extend.get("cateId");
            }
            String year = (extend != null && extend.containsKey("year")) ? extend.get("year") : "";
            String sort = (extend != null && extend.containsKey("sort")) ? extend.get("sort") : "hot";

            StringBuilder url = new StringBuilder(host);
            url.append("/api/crumb/list?fcate_pid=").append(fcatePid)
               .append("&category_id=&area=&year=").append(year)
               .append("&type=&sort=").append(sort)
               .append("&page=").append(pg);

            String resp = fetch(url.toString());
            JSONObject obj = new JSONObject(resp);
            if (obj.optInt("code") == 1) {
                JSONArray data = obj.optJSONArray("data");
                if (data == null) data = new JSONArray();
                JSONArray list = parseJsonList(data);
                JSONObject result = new JSONObject();
                result.put("list", list);
                result.put("page", Integer.parseInt(pg));
                return result.toString();
            }
        } catch (Exception e) {
            SpiderDebug.log("[ZT-API] categoryContent 错误: " + e.getMessage());
        }
        return "{\"list\":[], \"page\":" + pg + "}";
    }

    // ---------- 详情页 ----------
    @Override
    public String detailContent(List<String> ids) {
        try {
            if (ids == null || ids.isEmpty()) return "{\"list\":[]}";
            String vodId = ids.get(0);
            String url = host + "/api/video/detailv2?id=" + vodId;
            String resp = fetch(url);
            JSONObject obj = new JSONObject(resp);
            if (obj.optInt("code") != 1) return "{\"list\":[]}";

            JSONObject data = obj.optJSONObject("data");
            if (data == null) return "{\"list\":[]}";

            String imgPath = data.optString("thumbnail");
            if (TextUtils.isEmpty(imgPath)) imgPath = data.optString("path");
            String pic = getImgUrl(imgPath);

            // 演员
            JSONArray actors = data.optJSONArray("actors");
            StringBuilder actorBuilder = new StringBuilder();
            if (actors != null) {
                for (int i = 0; i < actors.length(); i++) {
                    if (i > 0) actorBuilder.append(" / ");
                    JSONObject a = actors.getJSONObject(i);
                    actorBuilder.append(a.optString("name", ""));
                }
            }
            // 导演
            JSONArray directors = data.optJSONArray("directors");
            StringBuilder directorBuilder = new StringBuilder();
            if (directors != null) {
                for (int i = 0; i < directors.length(); i++) {
                    if (i > 0) directorBuilder.append(" / ");
                    JSONObject d = directors.getJSONObject(i);
                    directorBuilder.append(d.optString("name", ""));
                }
            }

            // 播放线路
            List<String> playFromList = new ArrayList<>();
            List<String> playUrlList = new ArrayList<>();
            JSONArray sources = data.optJSONArray("source_list_source");
            if (sources != null) {
                for (int i = 0; i < sources.length(); i++) {
                    JSONObject source = sources.getJSONObject(i);
                    JSONArray eps = source.optJSONArray("source_list");
                    if (eps == null || eps.length() == 0) continue;
                    String fromName = source.optString("name", "线路");
                    playFromList.add(fromName);
                    List<String> urls = new ArrayList<>();
                    for (int j = 0; j < eps.length(); j++) {
                        JSONObject ep = eps.getJSONObject(j);
                        String epName = ep.optString("source_name", "正片");
                        if (epName.matches("\\d+")) epName = "第" + epName + "集";
                        String epUrl = ep.optString("url", "");
                        urls.add(epName + "$" + epUrl);
                    }
                    playUrlList.add(String.join("#", urls));
                }
            }

            JSONObject vod = new JSONObject();
            vod.put("vod_id", vodId);
            vod.put("vod_name", data.optString("title", ""));
            vod.put("vod_pic", pic);
            vod.put("vod_remarks", data.optString("mask", ""));
            vod.put("vod_actor", actorBuilder.toString());
            vod.put("vod_director", directorBuilder.toString());
            vod.put("vod_content", data.optString("description", "").trim());
            vod.put("vod_play_from", String.join("$$$", playFromList));
            vod.put("vod_play_url", String.join("$$$", playUrlList));

            JSONArray list = new JSONArray();
            list.put(vod);
            JSONObject result = new JSONObject();
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log("[ZT-API] detailContent 错误: " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    // ---------- 搜索 ----------
    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    public String searchContent(String key, boolean quick, String pg) {
        try {
            String url = host + "/api/v2/search/videoV2?key=" + URLEncoder.encode(key, "UTF-8") + "&page=" + pg;
            String resp = fetch(url);
            JSONObject obj = new JSONObject(resp);
            if (obj.optInt("code") == 1) {
                JSONArray data = obj.optJSONArray("data");
                if (data != null && data.length() > 0) {
                    // 只取第一条数据（与 Python 一致）
                    JSONArray first = new JSONArray();
                    first.put(data.get(0));
                    JSONArray list = parseJsonList(first);
                    JSONObject result = new JSONObject();
                    result.put("list", list);
                    result.put("page", Integer.parseInt(pg));
                    return result.toString();
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("[ZT-API] searchContent 错误: " + e.getMessage());
        }
        return "{\"list\":[]}";
    }

    private JSONArray parseJsonList(JSONArray items) throws Exception {
        JSONArray videos = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String id = item.optString("id");
            if (TextUtils.isEmpty(id)) continue;
            String path = item.optString("path");
            if (TextUtils.isEmpty(path)) path = item.optString("thumbnail");
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            vod.put("vod_name", item.optString("title", ""));
            vod.put("vod_pic", getImgUrl(path));
            vod.put("vod_remarks", item.optString("mask", ""));
            videos.put(vod);
        }
        return videos;
    }

    // ---------- 播放解析 ----------
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        // 提取 vod_id 和 weight（集数名称）
        String vodId = "";
        String weight = "正片";
        Pattern p = Pattern.compile("id=(\\d+)");
        Matcher m = p.matcher(id);
        if (m.find()) vodId = m.group(1);
        if (id.contains("$")) {
            String[] parts = id.split("\\$", 2);
            weight = parts[0].trim();
        }

        // 构造弹幕 URL
        String danmuUrl = host + "/api/v2/comment/list?video_id=" + vodId + "&weight=" + weight + "&sort=changed&page=1&pageSize=200";
        // 编码 weight
        try {
            danmuUrl = danmuUrl.replace(weight, URLEncoder.encode(weight, "UTF-8"));
        } catch (Exception ignored) {}

        // 播放请求头（移除 Referer）
        Map<String, String> playHeader = new HashMap<>(headers);
        playHeader.remove("Referer");

        try {
            JSONObject result = new JSONObject();
            result.put("parse", 0);
            result.put("url", id);
            JSONObject headerObj = new JSONObject();
            for (Map.Entry<String, String> entry : playHeader.entrySet()) {
                headerObj.put(entry.getKey(), entry.getValue());
            }
            result.put("header", headerObj);
            result.put("danmuku", danmuUrl);
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log("[ZT-API] playerContent 错误: " + e.getMessage());
            return "{\"parse\":0,\"url\":\"" + id + "\"}";
        }
    }
}