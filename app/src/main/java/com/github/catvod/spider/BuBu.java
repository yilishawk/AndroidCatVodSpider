package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BuBu extends Spider {

    private static final String CONFIG_URL = "https://bubuzhuiju.com/js/config.js";
    private String host = "https://asd123sx23xdacsx.top";

    private static final String[][] CHANNELS = {
            {"2", "电视剧", "剧集"},
            {"1", "电影",   "电影"},
            {"4", "综艺",   "综艺"},
    };

    // ------------------------------------------------------------------ init

    @Override
    public void init(Context context, String extend) {
        initHost();
    }

    private void initHost() {
        try {
            String js = OkHttp.string(CONFIG_URL, new HashMap<>());
            Matcher m = Pattern.compile("host:\\s*'([^']+)'").matcher(js);
            while (m.find()) {
                String candidate = "https://" + m.group(1);
                if (isAlive(candidate)) {
                    host = candidate;
                    SpiderDebug.log("[BuBu] host: " + host);
                    return;
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("[BuBu] config 获取失败，使用默认域名: " + e.getMessage());
        }
    }

    private boolean isAlive(String url) {
        try {
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(3, TimeUnit.SECONDS)
                    .build();
            okhttp3.Request req = new okhttp3.Request.Builder().url(url).head().build();
            try (okhttp3.Response resp = client.newCall(req).execute()) {
                return resp.code() < 500;
            }
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 请求头

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36");
        h.put("Accept",     "application/json");
        h.put("web-sign",   "f65f3a83d6d9ad6f");
        h.put("x-client",   "8f3d2a1c7b6e5d4c9a0b1f2e3d4c5b6a");
        h.put("Referer",    host + "/");
        return h;
    }

    // ------------------------------------------------------------------ 首页

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        for (String[] ch : CHANNELS) classes.add(new Class(ch[0], ch[1]));
        List<Vod> vods = fetchVodList("2", "剧集", "1");
        return Result.get().classes(classes).filters(new LinkedHashMap<>()).vod(vods).string();
    }

    @Override
    public String homeVideoContent() throws Exception {
        return Result.get().vod(fetchVodList("2", "剧集", "1")).string();
    }

    // ------------------------------------------------------------------ 分类

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) throws Exception {
        String typeName = "";
        for (String[] ch : CHANNELS) if (ch[0].equals(tid)) { typeName = ch[2]; break; }
        List<Vod> vods = fetchVodList(tid, typeName, pg);
        int page = pg == null || pg.isEmpty() ? 1 : Integer.parseInt(pg);
        return Result.get().page(page, 999, 20, 9999).vod(vods).string();
    }

    private List<Vod> fetchVodList(String typeId, String typeName, String pg) throws Exception {
        String url = host + "/api.php/web/filter/vod"
                + "?type_id=" + typeId
                + "&page="    + pg
                + "&sort=hits"
                + (typeName.isEmpty() ? "" : "&type_name=" + URLEncoder.encode(typeName, "UTF-8"));
        String resp = OkHttp.string(url, headers());
        JSONObject root = new JSONObject(resp);
        if (root.optInt("code") != 200) return new ArrayList<>();
        JSONArray arr = root.optJSONArray("data");
        if (arr == null) return new ArrayList<>();
        List<Vod> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Vod v = new Vod();
            v.setVodId(o.optString("vod_id"));
            v.setVodName(o.optString("vod_name"));
            v.setVodPic(o.optString("vod_pic"));
            v.setVodRemarks(o.optString("vod_remarks"));
            list.add(v);
        }
        return list;
    }

    // ------------------------------------------------------------------ 详情

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String vodId = ids.get(0);

        // 1. 主详情
        String detailUrl  = host + "/api.php/web/vod/get_detail?vod_id=" + vodId;
        String detailResp = OkHttp.string(detailUrl, headers());
        JSONObject root   = new JSONObject(detailResp);
        if (root.optInt("code") != 200 || root.optJSONArray("data") == null)
            return Result.get().vod(new ArrayList<>()).string();

        JSONObject d = root.getJSONArray("data").getJSONObject(0);
        String fromStr = d.optString("vod_play_from", "");
        String urlStr  = d.optString("vod_play_url",  "");

        List<String> fromList = new ArrayList<>();
        List<String> urlList  = new ArrayList<>();
        if (!fromStr.isEmpty()) for (String s : fromStr.split("\\$\\$\\$", -1)) fromList.add(s);
        if (!urlStr.isEmpty())  for (String s : urlStr.split("\\$\\$\\$", -1))  urlList.add(s);

        // 2. 外部聚合
        try {
            String aggUrl  = host + "/api.php/web/internal/search_aggregate?vod_id=" + vodId;
            String aggResp = OkHttp.string(aggUrl, headers());
            JSONObject aggRoot = new JSONObject(aggResp);
            if (aggRoot.optInt("code") == 200 && aggRoot.optJSONArray("data") != null) {
                JSONArray items = aggRoot.getJSONArray("data");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item    = items.getJSONObject(i);
                    String siteKey     = item.optString("site_key", "");
                    String playUrlRaw  = item.optString("vod_play_url", "");
                    if (siteKey.isEmpty() || playUrlRaw.isEmpty()) continue;
                    // 按集数索引（从1开始）构建内部链接
                    String[] eps = playUrlRaw.split("#", -1);
                    StringBuilder sb = new StringBuilder();
                    for (int epIdx = 1; epIdx <= eps.length; epIdx++) {
                        String ep = eps[epIdx - 1];
                        int dollar = ep.indexOf('$');
                        if (dollar < 0) continue;
                        String epName = ep.substring(0, dollar);
                        String link   = "/play/" + vodId + "%23sid=" + siteKey + "&nid=" + epIdx;
                        if (sb.length() > 0) sb.append("#");
                        sb.append(epName).append("$").append(link);
                    }
                    if (sb.length() > 0) {
                        fromList.add(siteKey);
                        urlList.add(sb.toString());
                    }
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("[BuBu] 聚合接口失败: " + e.getMessage());
        }

        // 3. 所有线路统一转换为内部链接，并补全域名
        List<String> fullUrlList = new ArrayList<>();
        for (int idx = 0; idx < urlList.size(); idx++) {
            String lineName = idx < fromList.size() ? fromList.get(idx) : ("线路" + (idx + 1));
            String[] eps    = urlList.get(idx).split("#", -1);
            StringBuilder sb = new StringBuilder();
            for (int epIdx = 1; epIdx <= eps.length; epIdx++) {
                String ep     = eps[epIdx - 1];
                int dollar    = ep.indexOf('$');
                if (dollar < 0) continue;
                String epName = ep.substring(0, dollar);
                String link   = ep.substring(dollar + 1);
                // 已是内部链接（聚合来的）直接补全；原始 API 来的重新构建
                String fullLink;
                if (link.startsWith("/play/")) {
                    fullLink = host + link;
                } else if (link.startsWith("http")) {
                    fullLink = host + "/play/" + vodId + "%23sid=" + lineName + "&nid=" + epIdx;
                } else {
                    fullLink = host + "/play/" + vodId + "%23sid=" + lineName + "&nid=" + epIdx;
                }
                if (sb.length() > 0) sb.append("#");
                sb.append(epName).append("$").append(fullLink);
            }
            fullUrlList.add(sb.toString());
        }

        Vod vod = new Vod();
        vod.setVodId(vodId);
        vod.setVodName(d.optString("vod_name"));
        vod.setVodPic(d.optString("vod_pic"));
        vod.setVodYear(d.optString("vod_year"));
        vod.setVodArea(d.optString("vod_area"));
        vod.setVodDirector(d.optString("vod_director"));
        vod.setVodActor(d.optString("vod_actor"));
        vod.setVodContent(d.optString("vod_content"));
        vod.setVodPlayFrom(join(fromList, "$$$"));
        vod.setVodPlayUrl(join(fullUrlList, "$$$"));

        return Result.get().vod(vod).string();
    }

    // ------------------------------------------------------------------ 搜索

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        int page = pg == null || pg.isEmpty() ? 1 : Integer.parseInt(pg);
        String url = host + "/api.php/web/search/index"
                + "?wd="    + URLEncoder.encode(key, "UTF-8")
                + "&page="  + page
                + "&limit=20";
        String resp = OkHttp.string(url, headers());
        JSONObject root = new JSONObject(resp);
        if (root.optInt("code") != 200) return Result.get().vod(new ArrayList<>()).string();
        JSONArray arr  = root.optJSONArray("data");
        if (arr == null) return Result.get().vod(new ArrayList<>()).string();
        List<Vod> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Vod v = new Vod();
            v.setVodId(o.optString("vod_id"));
            v.setVodName(o.optString("vod_name"));
            v.setVodPic(o.optString("vod_pic"));
            v.setVodRemarks(o.optString("vod_remarks"));
            list.add(v);
        }
        return Result.get().page(page, 1, list.size(), list.size()).vod(list).string();
    }

    // ------------------------------------------------------------------ 播放

    /**
     * id 格式：https://host/play/vod_id%23sid=xxx&nid=1
     * 将 %23 还原为 # 后 parse=1 推给壳子嗅探
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String url = id.startsWith("/play/") ? host + id : id;
        url = url.replace("%23", "#");
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", headers().get("User-Agent"));
        h.put("Referer",    host + "/");
        return Result.get().url(url).parse(1).header(h).string();
    }

    // ------------------------------------------------------------------ 工具

    private String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }
}
