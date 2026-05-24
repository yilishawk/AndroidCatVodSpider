package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

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
 * ZT-API 爬虫 (采集站高清图 + 详情加速版)
 * 站点: https://api.ztcgi.com
 * 图片: https://hongniuzy.tv 采集接口
 */
public class Jianpian extends Spider {

    private String host = "https://api.ztcgi.com";
    private String cjHost = "https://hongniuzy.tv";
    private Map<String, String> headers;

    public Jianpian() {
        headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 9; V2196A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.114 Mobile Safari/537.36");
        headers.put("Referer", "https://api.ztcgi.com/");
        headers.put("Connection", "Keep-Alive");
    }

    @Override
    public void init(Context context, String extend) {
        if (!TextUtils.isEmpty(extend)) {
            try {
                JSONObject cfg = new JSONObject(extend);
                if (cfg.has("host")) host = cfg.optString("host");
                if (cfg.has("cjHost")) cjHost = cfg.optString("cjHost");
            } catch (Exception e) {
                SpiderDebug.log("[ZT-API] 解析扩展配置失败: " + e.getMessage());
            }
        }
        SpiderDebug.log("[ZT-API] 初始化完成，host: " + host + ", cjHost: " + cjHost);
    }

    public String getName() {
        return "ZT-API(采集站高清图)";
    }

    // ---------- 工具方法 ----------
    private String fetch(String url) throws Exception {
        return OkHttp.string(url, headers);
    }

    // 从采集站获取图片，完全匹配标题
    private String getCjPic(String title) {
        if (TextUtils.isEmpty(title)) return "";
        try {
            String url = cjHost + "/index.php/ajax/suggest.html?mid=1&wd=" + URLEncoder.encode(title, "UTF-8");
            String resp = OkHttp.string(url, headers);
            JSONObject obj = new JSONObject(resp);
            if (obj.optInt("code") == 1) {
                JSONArray list = obj.optJSONArray("list");
                if (list != null && list.length() > 0) {
                    // 1. 先找完全匹配的
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject item = list.getJSONObject(i);
                        String name = item.optString("name", "");
                        if (title.equals(name)) {
                            String pic = item.optString("pic", "");
                            if (!TextUtils.isEmpty(pic)) {
                                SpiderDebug.log("[CJ] 完全匹配: " + title + " -> " + pic);
                                return pic;
                            }
                        }
                    }
                    // 2. 没找到完全匹配的，用第一个结果兜底
                    String pic = list.getJSONObject(0).optString("pic", "");
                    if (!TextUtils.isEmpty(pic)) {
                        SpiderDebug.log("[CJ] 模糊匹配: " + title + " -> " + pic);
                        return pic;
                    }
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("[CJ] 采集图片失败: " + e.getMessage());
        }
        return "";
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

            // 获取首页推荐列表
            try {
                String homeUrl = host + "/api/crumb/list?page=1&sort=hot";
                SpiderDebug.log("[ZT-API] 请求首页: " + homeUrl);
                JSONObject homeObj = new JSONObject(fetch(homeUrl));
                SpiderDebug.log("[ZT-API] 首页code: " + homeObj.optInt("code"));
                if (homeObj.optInt("code") == 1) {
                    JSONArray data = homeObj.optJSONArray("data");
                    if (data != null && data.length() > 0) {
                        JSONArray list = parseJsonList(data);
                        SpiderDebug.log("[ZT-API] 首页list长度: " + list.length());
                        result.put("list", list);
                    } else {
                        SpiderDebug.log("[ZT-API] 首页data为空");
                        result.put("list", new JSONArray());
                    }
                } else {
                    SpiderDebug.log("[ZT-API] 首页code≠1");
                    result.put("list", new JSONArray());
                }
            } catch (Exception e) {
                SpiderDebug.log("[ZT-API] 首页列表异常: " + e.getMessage());
                result.put("list", new JSONArray());
            }

            if (filter) {
                JSONObject filters = new JSONObject();

                JSONArray yearOptions = new JSONArray();
                String[][] years = {{"全部", ""}, {"2026", "2026"}, {"2025", "107"}, {"2024", "119"}, {"2023", "153"}, {"2022", "101"}};
                for (String[] y : years) {
                    JSONObject opt = new JSONObject();
                    opt.put("n", y[0]);
                    opt.put("v", y[1]);
                    yearOptions.put(opt);
                }

                JSONArray sortOptions = new JSONArray();
                String[][] sorts = {{"热门", "hot"}, {"评分", "rating"}, {"更新", "update"}};
                for (String[] s : sorts) {
                    JSONObject opt = new JSONObject();
                    opt.put("n", s[0]);
                    opt.put("v", s[1]);
                    sortOptions.put(opt);
                }

                JSONArray cateOptions = new JSONArray();
                String[][] cates = {{"全部", "2"}, {"国产", "15"}, {"港台", "16"}};
                for (String[] c : cates) {
                    JSONObject opt = new JSONObject();
                    opt.put("n", c[0]);
                    opt.put("v", c[1]);
                    cateOptions.put(opt);
                }

                JSONArray filter15 = new JSONArray();
                filter15.put(createFilter("year", "年代", yearOptions));
                filter15.put(createFilter("sort", "排序", sortOptions));
                filters.put("15", filter15);

                JSONArray filter2 = new JSONArray();
                filter2.put(createFilter("cateId", "类型", cateOptions));
                filter2.put(createFilter("year", "年代", yearOptions));
                filter2.put(createFilter("sort", "排序", sortOptions));
                filters.put("2", filter2);

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
            return "{\"class\":[],\"list\":[]}";
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

            String title = data.optString("title", "");
            String pic = getCjPic(title);

            JSONArray actors = data.optJSONArray("actors");
            StringBuilder actorBuilder = new StringBuilder();
            if (actors != null) {
                for (int i = 0; i < actors.length(); i++) {
                    if (i > 0) actorBuilder.append(" / ");
                    JSONObject a = actors.getJSONObject(i);
                    actorBuilder.append(a.optString("name", ""));
                }
            }
            JSONArray directors = data.optJSONArray("directors");
            StringBuilder directorBuilder = new StringBuilder();
            if (directors != null) {
                for (int i = 0; i < directors.length(); i++) {
                    if (i > 0) directorBuilder.append(" / ");
                    JSONObject d = directors.getJSONObject(i);
                    directorBuilder.append(d.optString("name", ""));
                }
            }

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
            vod.put("vod_name", title);
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
            String title = item.optString("title", "");
            String pic = getCjPic(title);
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            vod.put("vod_name", title);
            vod.put("vod_pic", pic);
            vod.put("vod_remarks", item.optString("mask", ""));
            videos.put(vod);
        }
        return videos;
    }

    // ---------- 播放解析 ----------
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String vodId = "";
        String weight = "正片";
        Pattern p = Pattern.compile("id=(\\d+)");
        Matcher m = p.matcher(id);
        if (m.find()) vodId = m.group(1);
        if (id.contains("$")) {
            String[] parts = id.split("\\$", 2);
            weight = parts[0].trim();
        }

        String danmuUrl = host + "/api/v2/comment/list?video_id=" + vodId + "&weight=" + weight + "&sort=changed&page=1&pageSize=200";
        try {
            danmuUrl = danmuUrl.replace(weight, URLEncoder.encode(weight, "UTF-8"));
        } catch (Exception ignored) {}

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