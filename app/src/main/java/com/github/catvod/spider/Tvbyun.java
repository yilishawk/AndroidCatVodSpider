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
    private static final int MAX_RETRIES = 3;  // 最大重试次数

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
        }
    }
    
    private void fetchConfig() throws Exception {
        String configUrl = baseUrl + "/api.php/Appfox/config";
        String response = get(configUrl);
        if (response != null && !response.isEmpty()) {
            JSONObject json = new JSONObject(response);
            if (json.optInt("code") == 200) {
                JSONObject data = json.optJSONObject("data");
                if (data != null) {
                    String newApiUrl = data.optString("globalVideoDataUrl");
                    if (newApiUrl != null && !newApiUrl.isEmpty()) {
                        this.apiUrl = newApiUrl;
                    }
                    SpiderDebug.log("[Tvbyun] 配置获取成功, apiUrl: " + apiUrl);
                }
            }
        }
    }
    
    /**
     * GET 请求 - 当返回 HTML 时自动重试（使用相同 URL）
     */
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
                SpiderDebug.log("[Tvbyun] 请求失败，状态码: " + response.code());
                // 非成功状态码，重试
                if (retryCount < MAX_RETRIES - 1) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    return getWithRetry(url, retryCount + 1);
                }
                return null;
            }
            
            String body = response.body().string();
            
            // 检查返回的是否是 HTML（重定向页面）
            if (isHtmlResponse(body)) {
                SpiderDebug.log("[Tvbyun] 检测到 HTML 响应（非 JSON），使用相同 URL 重新请求...");
                if (retryCount < MAX_RETRIES - 1) {
                    // 等待后重试，使用相同的 URL
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    return getWithRetry(url, retryCount + 1);
                } else {
                    SpiderDebug.log("[Tvbyun] 已达最大重试次数，仍返回 HTML");
                    return null;
                }
            }
            
            // 检查是否是有效的 JSON
            if (isValidJson(body)) {
                SpiderDebug.log("[Tvbyun] 请求成功，返回 JSON 数据");
                return body;
            } else {
                SpiderDebug.log("[Tvbyun] 返回的不是有效 JSON，重试...");
                if (retryCount < MAX_RETRIES - 1) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    return getWithRetry(url, retryCount + 1);
                }
                return null;
            }
        } catch (Exception e) {
            SpiderDebug.log("[Tvbyun] 请求异常: " + e.getMessage());
            if (retryCount < MAX_RETRIES - 1) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                return getWithRetry(url, retryCount + 1);
            }
            return null;
        }
    }
    
    /**
     * 判断响应是否是 HTML
     */
    private boolean isHtmlResponse(String body) {
        if (body == null) return true;
        String trimmed = body.trim().toLowerCase();
        return trimmed.startsWith("<html") || trimmed.startsWith("<!doctype") || 
               trimmed.contains("<script") || trimmed.contains("document.location");
    }
    
    /**
     * 判断是否是有效的 JSON
     */
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

            // 只放4个主要分类
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
            // 如果有筛选条件，使用筛选的 type_id
            if (extend != null && extend.containsKey("type_id") && !extend.get("type_id").isEmpty()) {
                String filterTypeId = extend.get("type_id");
                // 验证筛选的 type_id 是否有效
                if (!filterTypeId.equals(tid)) {
                    typeId = filterTypeId;
                }
            }
            
            // 构建 URL：ac=list&t={tid}&pg={pg}
            String url = apiUrl + "?ac=list&t=" + typeId + "&pg=" + pg;
            SpiderDebug.log("[Tvbyun] category URL: " + url);
            
            String response = get(url);
            if (response == null) {
                SpiderDebug.log("[Tvbyun] categoryContent: 响应为空");
                return "{\"list\":[], \"page\":" + pg + "}";
            }
            
            JSONObject json = new JSONObject(response);
            int code = json.optInt("code");
            if (code != 1) {
                SpiderDebug.log("[Tvbyun] categoryContent: API 返回错误 code=" + code);
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
            e.printStackTrace();
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
                SpiderDebug.log("[Tvbyun] detailContent: 响应为空");
                return "{\"list\":[]}";
            }
            
            JSONObject json = new JSONObject(response);
            int code = json.optInt("code");
            if (code != 1) {
                SpiderDebug.log("[Tvbyun] detailContent: API 返回错误 code=" + code);
                return "{\"list\":[]}";
            }
            
            JSONArray list = json.optJSONArray("list");
            if (list == null || list.length() == 0) {
                return "{\"list\":[]}";
            }
            
            JSONObject item = list.getJSONObject(0);
            
            // 解析播放地址
            List<String> playFromList = new ArrayList<>();
            List<String> playUrlList = new ArrayList<>();
            
            // 获取播放来源列表
            String playFromStr = item.optString("vod_play_from");
            SpiderDebug.log("[Tvbyun] vod_play_from: " + playFromStr);
            
            if (playFromStr != null && !playFromStr.isEmpty()) {
                String[] players = playFromStr.split(",");
                for (String playerCode : players) {
                    playerCode = playerCode.trim();
                    // 尝试获取该线路的播放地址
                    String playUrl = item.optString("vod_play_url_" + playerCode);
                    if (playUrl == null || playUrl.isEmpty()) {
                        playUrl = item.optString("vod_url_" + playerCode);
                    }
                    if (playUrl == null || playUrl.isEmpty()) {
                        playUrl = item.optString(playerCode);
                    }
                    
                    if (playUrl != null && !playUrl.isEmpty()) {
                        // 格式化播放地址：集数$地址
                        String formattedUrl = formatPlayUrl(playUrl);
                        if (formattedUrl != null && !formattedUrl.isEmpty()) {
                            playFromList.add(playerCode);
                            playUrlList.add(formattedUrl);
                            SpiderDebug.log("[Tvbyun] 添加线路: " + playerCode);
                        }
                    }
                }
            }
            
            // 如果没有解析到任何线路，尝试默认字段
            if (playFromList.isEmpty()) {
                String defaultPlayUrl = item.optString("vod_play_url");
                if (defaultPlayUrl == null || defaultPlayUrl.isEmpty()) {
                    defaultPlayUrl = item.optString("vod_url");
                }
                if (defaultPlayUrl != null && !defaultPlayUrl.isEmpty()) {
                    String formattedUrl = formatPlayUrl(defaultPlayUrl);
                    if (formattedUrl != null && !formattedUrl.isEmpty()) {
                        playFromList.add("默认线路");
                        playUrlList.add(formattedUrl);
                    }
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
            vod.put("vod_play_from", String.join("$$$", playFromList));
            vod.put("vod_play_url", String.join("$$$", playUrlList));
            
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
     * 格式化播放地址
     * 输入: "第1集$http://xxx.m3u8#第2集$http://yyy.m3u8" 或 "http://xxx.m3u8"
     * 输出: 保持原格式
     */
    private String formatPlayUrl(String playUrl) {
        if (playUrl == null || playUrl.isEmpty()) {
            return null;
        }
        // 如果已经包含 $ 分隔符，直接返回
        if (playUrl.contains("$")) {
            return playUrl;
        }
        // 如果是单个 URL，添加默认集数名称
        if (playUrl.startsWith("http")) {
            return "播放$" + playUrl;
        }
        return playUrl;
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

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        SpiderDebug.log("[Tvbyun] playerContent flag=" + flag + ", id=" + id);
        
        try {
            // 如果 id 已经是完整的 URL，直接返回
            if (id.startsWith("http://") || id.startsWith("https://")) {
                JSONObject result = new JSONObject();
                result.put("parse", 0);
                result.put("url", id);
                // 添加请求头
                JSONObject header = new JSONObject();
                header.put("User-Agent", headers.get("User-Agent"));
                header.put("Referer", baseUrl + "/");
                result.put("header", header);
                return result.toString();
            }
            
            // 默认返回 parse=1，让壳子处理
            return "{\"parse\":1,\"url\":\"" + id + "\"}";
            
        } catch (Exception e) {
            SpiderDebug.log("[Tvbyun] playerContent error: " + e.getMessage());
            return "{\"parse\":1,\"url\":\"" + id + "\"}";
        }
    }
}
