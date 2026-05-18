package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 港台影视 - 基于 API 的爬虫
 * 站点: http://app.hktvyb.cc
 */
public class Tvbyun extends Spider {

    private String baseUrl = "http://app.hktvyb.cc";
    private String apiUrl = "http://app.hktvyb.cc/api.php/provide/vod/";
    private OkHttpClient client;
    private Map<String, String> headers;
    private static final int MAX_RETRIES = 3;

    // 播放器线路映射（playerCode -> 显示名称）
    private Map<String, String> playerNameMap = new LinkedHashMap<>();
    // 解析接口映射（playerCode -> 解析URL）
    private Map<String, String> jiexiUrlMap = new HashMap<>();

    public Tvbyun() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();

        this.headers = new HashMap<>();
        this.headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        this.headers.put("Accept", "application/json, text/plain, */*");
        this.headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        this.headers.put("Content-Type", "application/json");
    }

    @Override
    public void init(Context context, String extend) {
        SpiderDebug.log("[Tvbyun] init called");
        try {
            fetchConfig();
        } catch (Exception e) {
            SpiderDebug.log("[Tvbyun] 获取配置失败: " + e.getMessage());
            initDefaultConfig();
        }
    }

    /**
     * 获取配置信息
     */
    private void fetchConfig() throws Exception {
        String configUrl = baseUrl + "/api.php/Appfox/config";
        String response = get(configUrl);
        if (response != null && !response.isEmpty()) {
            JSONObject json = new JSONObject(response);
            if (json.optInt("code") == 200) {
                JSONObject data = json.optJSONObject("data");
                if (data != null) {
                    // 更新 API 地址
                    String newApiUrl = data.optString("globalVideoDataUrl");
                    if (newApiUrl != null && !newApiUrl.isEmpty()) {
                        this.apiUrl = newApiUrl;
                    }
                    SpiderDebug.log("[Tvbyun] API地址: " + apiUrl);

                    // 1. 获取播放器线路映射
                    JSONArray playerList = data.optJSONArray("playerList");
                    if (playerList != null && playerList.length() > 0) {
                        playerNameMap.clear();
                        for (int i = 0; i < playerList.length(); i++) {
                            JSONObject playerItem = playerList.getJSONObject(i);
                            String playerCode = playerItem.optString("playerCode");
                            String playerName = playerItem.optString("playerName");
                            if (playerCode != null && !playerCode.isEmpty()) {
                                playerNameMap.put(playerCode, playerName);
                                SpiderDebug.log("[Tvbyun] 播放器: " + playerCode + " -> " + playerName);
                            }
                        }
                    }

                    // 2. 获取解析接口映射（只有配置了解析接口的线路才走解析）
                    JSONArray jiexiDataList = data.optJSONArray("jiexiDataList");
                    if (jiexiDataList != null && jiexiDataList.length() > 0) {
                        jiexiUrlMap.clear();
                        for (int i = 0; i < jiexiDataList.length(); i++) {
                            JSONObject jiexiItem = jiexiDataList.getJSONObject(i);
                            String url = jiexiItem.optString("url");
                            String playerCode = jiexiItem.optString("playerCode");
                            String name = jiexiItem.optString("name");
                            if (url != null && !url.isEmpty() && playerCode != null && !playerCode.isEmpty()) {
                                jiexiUrlMap.put(playerCode, url);
                                SpiderDebug.log("[Tvbyun] 解析接口: " + playerCode + " -> " + url);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 初始化默认配置（当获取配置失败时使用）
     */
    private void initDefaultConfig() {
        // 默认播放器线路（按优先级排序）
        playerNameMap.clear();
        playerNameMap.put("mp4", "极速备用");
        playerNameMap.put("hkm3u8", "极速主力");
        playerNameMap.put("YYNB", "国内高速");
        playerNameMap.put("1080zyk", "海外资源");
        playerNameMap.put("bfzym3u8", "海外线路①");
        playerNameMap.put("lzm3u8", "海外线路④");
        playerNameMap.put("mytv", "极速专线");
        playerNameMap.put("mytvb", "TVB专线");
        playerNameMap.put("ffm3u8", "非凡线路");
        playerNameMap.put("dbm3u8", "国内线路");
        playerNameMap.put("dyttm3u8", "海外线路");
        playerNameMap.put("4ko2r", "国内高清");
        playerNameMap.put("libvio", "外剧专线");
        playerNameMap.put("saohuo", "国内剧集");
        playerNameMap.put("link", "外链数据");

        // 默认解析接口
        jiexiUrlMap.clear();
        jiexiUrlMap.put("lzm3u8", "http://111.229.219.148:808/xun3.php?url=");
        jiexiUrlMap.put("bfzym3u8", "http://111.229.219.148:808/xun3.php?url=");
        jiexiUrlMap.put("mytvb", "http://111.229.219.148:808/index.php?url=");
        jiexiUrlMap.put("YYNB", "http://111.229.219.148:808/index.php?url=");
        jiexiUrlMap.put("ffm3u8", "http://111.229.219.148:808/xun3.php?url=");
        jiexiUrlMap.put("1080zyk", "http://111.229.219.148:808/xun3.php?url=");
        jiexiUrlMap.put("mytv", "http://111.229.219.148:808/index.php?url=");
    }

    private String get(String url) throws IOException {
        return getWithRetry(url, 0);
    }

    private String getWithRetry(String url, int retryCount) throws IOException {
        SpiderDebug.log("[Tvbyun] 请求 URL: " + url + " (尝试 " + (retryCount + 1) + "/" + MAX_RETRIES + ")");

        Request request = new Request.Builder()
                .url(url)
                .headers(Headers.of(headers))
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                if (retryCount < MAX_RETRIES - 1) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    return getWithRetry(url, retryCount + 1);
                }
                return null;
            }

            String body = response.body().string();

            if (isHtmlResponse(body)) {
                SpiderDebug.log("[Tvbyun] 检测到 HTML 响应，重试...");
                if (retryCount < MAX_RETRIES - 1) {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    return getWithRetry(url, retryCount + 1);
                }
                return null;
            }

            if (isValidJson(body)) {
                return body;
            }

            if (retryCount < MAX_RETRIES - 1) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                return getWithRetry(url, retryCount + 1);
            }
            return null;

        } catch (Exception e) {
            SpiderDebug.log("[Tvbyun] 请求异常: " + e.getMessage());
            if (retryCount < MAX_RETRIES - 1) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                return getWithRetry(url, retryCount + 1);
            }
            return null;
        }
    }

    private boolean isHtmlResponse(String body) {
        if (body == null) return true;
        String trimmed = body.trim().toLowerCase();
        return trimmed.startsWith("<html") || trimmed.startsWith("<!doctype") ||
                trimmed.contains("<script") || trimmed.contains("document.location");
    }

    private boolean isValidJson(String body) {
        if (body == null) return false;
        String trimmed = body.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = new JSONArray();

            String[][] mainClasses = {
                    {"13", "国产剧"},
                    {"1", "电影"},
                    {"21", "大陆综艺"},
                    {"5", "短剧"}
            };

            for (String[] c : mainClasses) {
                JSONObject cls = new JSONObject();
                cls.put("type_id", c[0]);
                cls.put("type_name", c[1]);
                classes.put(cls);
            }
            result.put("class", classes);

            if (filter) {
                JSONObject filters = new JSONObject();

                // 电影筛选器
                JSONArray movieFilters = new JSONArray();
                JSONObject movieTypeFilter = new JSONObject();
                movieTypeFilter.put("key", "type_id");
                movieTypeFilter.put("name", "类型");
                JSONArray movieOptions = new JSONArray();

                String[][] movieTypes = {
                        {"全部", "1"}, {"动作片", "6"}, {"喜剧片", "7"},
                        {"爱情片", "8"}, {"科幻片", "9"}, {"剧情片", "10"},
                        {"恐怖片", "11"}, {"战争片", "12"}
                };
                for (String[] opt : movieTypes) {
                    JSONObject option = new JSONObject();
                    option.put("n", opt[0]);
                    option.put("v", opt[1]);
                    movieOptions.put(option);
                }
                movieTypeFilter.put("value", movieOptions);
                movieFilters.put(movieTypeFilter);
                filters.put("1", movieFilters);

                // 电视剧筛选器
                JSONArray tvFilters = new JSONArray();
                JSONObject tvTypeFilter = new JSONObject();
                tvTypeFilter.put("key", "type_id");
                tvTypeFilter.put("name", "类型");
                JSONArray tvOptions = new JSONArray();

                String[][] tvTypes = {
                        {"全部", "13"}, {"港台剧", "14"}, {"日韩剧", "15"},
                        {"欧美剧", "16"}, {"海外剧", "20"}
                };
                for (String[] opt : tvTypes) {
                    JSONObject option = new JSONObject();
                    option.put("n", opt[0]);
                    option.put("v", opt[1]);
                    tvOptions.put(option);
                }
                tvTypeFilter.put("value", tvOptions);
                tvFilters.put(tvTypeFilter);
                filters.put("13", tvFilters);

                // 综艺筛选器
                JSONArray varietyFilters = new JSONArray();
                JSONObject varietyTypeFilter = new JSONObject();
                varietyTypeFilter.put("key", "type_id");
                varietyTypeFilter.put("name", "类型");
                JSONArray varietyOptions = new JSONArray();

                String[][] varietyTypes = {
                        {"全部", "21"}, {"香港综艺", "22"}, {"日韩综艺", "23"}, {"欧美综艺", "24"}
                };
                for (String[] opt : varietyTypes) {
                    JSONObject option = new JSONObject();
                    option.put("n", opt[0]);
                    option.put("v", opt[1]);
                    varietyOptions.put(option);
                }
                varietyTypeFilter.put("value", varietyOptions);
                varietyFilters.put(varietyTypeFilter);
                filters.put("21", varietyFilters);

                result.put("filters", filters);
            }

            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log("[Tvbyun] homeContent error: " + e.getMessage());
            return "{\"class\":[], \"filters\":{}}";
        }
    }

    @Override
    public String homeVideoContent() {
        return categoryContent("1", "1", false, null);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            String typeId = tid;
            if (extend != null && extend.containsKey("type_id") && !extend.get("type_id").isEmpty()) {
                String filterTypeId = extend.get("type_id");
                if (!filterTypeId.equals(tid)) {
                    typeId = filterTypeId;
                }
            }

            String url = apiUrl + "?ac=list&ac=detail&t=" + typeId + "&pg=" + pg;
            SpiderDebug.log("[Tvbyun] category URL: " + url);

            String response = get(url);
            if (response == null) {
                return "{\"list\":[], \"page\":" + pg + "}";
            }

            JSONObject json = new JSONObject(response);
            int code = json.optInt("code");
            if (code != 1) {
                return "{\"list\":[], \"page\":" + pg + "}";
            }

            JSONArray list = json.optJSONArray("list");
            JSONArray videos = new JSONArray();

            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.getJSONObject(i);
                    JSONObject vod = new JSONObject();
                    vod.put("vod_id", item.optString("vod_id"));
                    vod.put("vod_name", item.optString("vod_name"));
                    vod.put("vod_pic", item.optString("vod_pic"));
                    vod.put("vod_remarks", item.optString("vod_remarks"));
                    videos.put(vod);
                }
            }

            JSONObject result = new JSONObject();
            result.put("list", videos);
            result.put("page", Integer.parseInt(pg));
            result.put("pagecount", json.optInt("pagecount", 1));
            return result.toString();

        } catch (Exception e) {
            SpiderDebug.log("[Tvbyun] categoryContent error: " + e.getMessage());
            return "{\"list\":[], \"page\":" + pg + "}";
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            if (ids == null || ids.isEmpty()) {
                return "{\"list\":[]}";
            }

            String vodId = ids.get(0);
            String url = apiUrl + "?ac=detail&ids=" + vodId;
            SpiderDebug.log("[Tvbyun] detail URL: " + url);

            String response = get(url);
            if (response == null) {
                return "{\"list\":[]}";
            }

            JSONObject json = new JSONObject(response);
            int code = json.optInt("code");
            if (code != 1) {
                return "{\"list\":[]}";
            }

            JSONArray list = json.optJSONArray("list");
            if (list == null || list.length() == 0) {
                return "{\"list\":[]}";
            }

            JSONObject item = list.getJSONObject(0);

            // 获取原始播放线路（用 $$$ 分隔）
            String playFrom = item.optString("vod_play_from");
            String[] players = playFrom.split("\\$\\$\\$");

            // 按优先级排序线路：mp4 -> hkm3u8 -> YYNB -> 其他
            List<String> sortedPlayers = sortPlayers(players);

            // 构建显示名称和播放地址
            List<String> displayNames = new ArrayList<>();
            List<String> playUrls = new ArrayList<>();

            String playUrlAll = item.optString("vod_play_url");
            String[] urlGroups = playUrlAll.split("\\$\\$\\$");

            // 建立线路索引映射
            Map<String, String> playerUrlMap = new HashMap<>();
            for (int i = 0; i < players.length && i < urlGroups.length; i++) {
                playerUrlMap.put(players[i], urlGroups[i]);
            }

            for (String playerCode : sortedPlayers) {
                String playerName = playerNameMap.getOrDefault(playerCode, playerCode);
                String playerUrl = playerUrlMap.get(playerCode);
                if (playerUrl != null && !playerUrl.isEmpty()) {
                    displayNames.add(playerName);
                    playUrls.add(playerUrl);
                    SpiderDebug.log("[Tvbyun] 添加线路: " + playerCode + " -> " + playerName);
                }
            }

            JSONObject vod = new JSONObject();
            vod.put("vod_id", item.optString("vod_id"));
            vod.put("vod_name", item.optString("vod_name"));
            vod.put("vod_pic", item.optString("vod_pic"));
            vod.put("vod_content", item.optString("vod_content"));
            vod.put("vod_year", item.optString("vod_year"));
            vod.put("vod_actor", item.optString("vod_actor"));
            vod.put("vod_director", item.optString("vod_director"));
            vod.put("type_name", item.optString("type_name"));
            vod.put("vod_remarks", item.optString("vod_remarks"));
            vod.put("vod_play_from", String.join("$$$", displayNames));
            vod.put("vod_play_url", String.join("$$$", playUrls));

            JSONArray resultList = new JSONArray();
            resultList.put(vod);
            JSONObject result = new JSONObject();
            result.put("list", resultList);
            return result.toString();

        } catch (Exception e) {
            SpiderDebug.log("[Tvbyun] detailContent error: " + e.getMessage());
            e.printStackTrace();
            return "{\"list\":[]}";
        }
    }

    /**
     * 线路排序：mp4 -> hkm3u8 -> YYNB -> 其他
     */
    private List<String> sortPlayers(String[] players) {
        List<String> result = new ArrayList<>();
        List<String> others = new ArrayList<>();

        // 优先级顺序
        String[] priority = {"mp4", "hkm3u8", "YYNB"};

        // 先添加优先级的线路
        for (String p : priority) {
            for (String player : players) {
                if (player.equals(p)) {
                    result.add(player);
                    break;
                }
            }
        }

        // 添加其他线路
        for (String player : players) {
            boolean isPriority = false;
            for (String p : priority) {
                if (player.equals(p)) {
                    isPriority = true;
                    break;
                }
            }
            if (!isPriority) {
                others.add(player);
            }
        }

        // 其他线路按原顺序添加
        result.addAll(others);

        return result;
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String searchUrl = baseUrl + "/index.php/ajax/suggest.html?mid=1&wd=" + URLEncoder.encode(key, "UTF-8");
            SpiderDebug.log("[Tvbyun] search URL: " + searchUrl);

            String response = get(searchUrl);
            if (response == null) {
                return "{\"list\":[]}";
            }

            JSONArray list = null;
            if (response.trim().startsWith("[")) {
                list = new JSONArray(response);
            } else {
                try {
                    JSONObject respObj = new JSONObject(response);
                    if (respObj.has("list")) {
                        list = respObj.optJSONArray("list");
                    } else if (respObj.has("data")) {
                        list = respObj.optJSONArray("data");
                    }
                } catch (Exception e) {
                    SpiderDebug.log("[Tvbyun] searchContent 解析失败: " + e.getMessage());
                }
            }

            JSONArray videos = new JSONArray();
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.getJSONObject(i);
                    JSONObject vod = new JSONObject();
                    vod.put("vod_id", item.optString("id"));
                    vod.put("vod_name", item.optString("title"));
                    vod.put("vod_pic", item.optString("pic", ""));
                    vod.put("vod_remarks", item.optString("type_name", ""));
                    videos.put(vod);
                }
            }

            JSONObject result = new JSONObject();
            result.put("list", videos);
            return result.toString();

        } catch (Exception e) {
            SpiderDebug.log("[Tvbyun] searchContent error: " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    /**
     * 播放解析 - 只有配置了解析接口的线路才走解析
     * 无解析接口的线路（如 mp4、hkm3u8）直接播放
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        SpiderDebug.log("[Tvbyun] ========== 开始播放解析 ==========");
        SpiderDebug.log("[Tvbyun] flag: " + flag);
        SpiderDebug.log("[Tvbyun] id: " + id);

        try {
            // 检查该线路是否有解析接口
            String jiexiUrl = jiexiUrlMap.get(flag);
            boolean needParse = (jiexiUrl != null && !jiexiUrl.isEmpty());

            SpiderDebug.log("[Tvbyun] 线路 " + flag + " 是否有解析接口: " + needParse);

            if (needParse) {
                // 有解析接口，走解析
                String parsedUrl = tryParseWithJiexi(jiexiUrl, id);
                if (parsedUrl != null && parsedUrl.startsWith("http")) {
                    SpiderDebug.log("[Tvbyun] 解析成功: " + parsedUrl);
                    JSONObject result = new JSONObject();
                    result.put("parse", 0);
                    result.put("url", parsedUrl);
                    JSONObject header = new JSONObject();
                    header.put("User-Agent", headers.get("User-Agent"));
                    header.put("Referer", baseUrl + "/");
                    result.put("header", header);
                    return result.toString();
                } else {
                    SpiderDebug.log("[Tvbyun] 解析失败，尝试直接播放");
                }
            }

            // 无解析接口，直接播放
            String playUrl = id;
            if (!playUrl.startsWith("http")) {
                // 如果不是完整URL，尝试拼接
                if (playUrl.contains(".m3u8") || playUrl.contains(".mp4")) {
                    playUrl = "http://" + playUrl;
                }
            }

            if (playUrl.startsWith("http")) {
                SpiderDebug.log("[Tvbyun] 直接播放: " + playUrl);
                JSONObject result = new JSONObject();
                result.put("parse", 0);
                result.put("url", playUrl);
                JSONObject header = new JSONObject();
                header.put("User-Agent", headers.get("User-Agent"));
                header.put("Referer", baseUrl + "/");
                result.put("header", header);
                return result.toString();
            }

        } catch (Exception e) {
            SpiderDebug.log("[Tvbyun] playerContent error: " + e.getMessage());
            e.printStackTrace();
        }

        // 全部失败，返回 parse=1 让壳子嗅探
        SpiderDebug.log("[Tvbyun] 解析失败，返回嗅探模式");
        return "{\"parse\":1,\"url\":\"" + id + "\"}";
    }

    /**
     * 使用解析接口获取真实播放地址
     * @param jiexiUrl 解析接口URL
     * @param url 原始播放地址
     * @return 解析后的真实地址
     */
    private String tryParseWithJiexi(String jiexiUrl, String url) {
        try {
            String fullUrl = jiexiUrl + URLEncoder.encode(url, "UTF-8");
            SpiderDebug.log("[Tvbyun] 请求解析接口: " + fullUrl);

            String response = getWithRetry(fullUrl, 0);
            if (response != null && !response.isEmpty()) {
                // 尝试解析 JSON 响应
                try {
                    JSONObject json = new JSONObject(response);
                    if (json.optInt("code") == 200) {
                        String videoUrl = json.optString("url");
                        if (videoUrl != null && !videoUrl.isEmpty() && videoUrl.startsWith("http")) {
                            return videoUrl;
                        }
                    }
                } catch (Exception e) {
                    // 如果不是 JSON，可能是直接返回的 URL
                    if (response.startsWith("http")) {
                        return response;
                    }
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("[Tvbyun] 解析请求失败: " + e.getMessage());
        }
        return null;
    }
}
