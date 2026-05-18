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
import java.util.Iterator;
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
    
    // 播放器线路映射
    private Map<String, String> playerMap = new HashMap<>();
    // 解析线路列表
    private List<Map<String, String>> jiexiList = new ArrayList<>();

    public HkTvYb() {
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
        SpiderDebug.log("[HkTvYb] init called");
        
        // 初始化播放器映射
        initPlayerMap();
        
        // 初始化解析线路
        initJiexiList();
        
        // 尝试获取配置
        try {
            fetchConfig();
        } catch (Exception e) {
            SpiderDebug.log("[HkTvYb] 获取配置失败: " + e.getMessage());
        }
    }
    
    /**
     * 初始化播放器线路映射
     */
    private void initPlayerMap() {
        // 按优先级排序：mp4, hkm3u8, YYNB
        playerMap.put("mp4", "极速备用");
        playerMap.put("hkm3u8", "极速主力");
        playerMap.put("YYNB", "国内高速");
        playerMap.put("mytv", "极速专线");
        playerMap.put("mytvb", "TVB专线");
        playerMap.put("ffm3u8", "非凡线路");
        playerMap.put("1080zyk", "海外资源");
        playerMap.put("dbm3u8", "国内线路");
        playerMap.put("dyttm3u8", "海外线路");
        playerMap.put("4ko2r", "国内高清");
        playerMap.put("libvio", "外剧专线");
        playerMap.put("saohuo", "国内剧集");
        playerMap.put("link", "外链数据");
    }
    
    /**
     * 初始化解析线路
     */
    private void initJiexiList() {
        // 添加解析线路（按优先级）
        addJiexi("http://111.229.219.148:808/index.php?url=", "极速专线", "mytv");
        addJiexi("http://111.229.219.148:808/xun3.php?url=", "海外线路①", "bfzym3u8");
        addJiexi("http://111.229.219.148:808/xun3.php?url=", "海外线路②", "1080zyk");
        addJiexi("http://111.229.219.148:808/xun3.php?url=", "海外线路③", "ffm3u8");
        addJiexi("http://111.229.219.148:808/xun3.php?url=", "海外线路④", "lzm3u8");
        addJiexi("http://111.229.219.148:808/index.php?url=", "TVB专线", "mytvb");
        addJiexi("http://111.229.219.148:808/index.php?url=", "国内高速", "YYNB");
    }
    
    private void addJiexi(String url, String name, String playerCode) {
        Map<String, String> item = new HashMap<>();
        item.put("url", url);
        item.put("name", name);
        item.put("playerCode", playerCode);
        jiexiList.add(item);
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
                    SpiderDebug.log("[HkTvYb] 配置获取成功, apiUrl: " + apiUrl);
                }
            }
        }
    }
    
    private String get(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .headers(Headers.of(headers))
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return response.body().string();
            }
        }
        return null;
    }
    
    private String post(String url, String body) throws IOException {
        okhttp3.RequestBody requestBody = okhttp3.RequestBody.create(
                okhttp3.MediaType.parse("application/json; charset=utf-8"),
                body
        );
        Request request = new Request.Builder()
                .url(url)
                .headers(Headers.of(headers))
                .post(requestBody)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return response.body().string();
            }
        }
        return null;
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
                
                // 电视剧筛选器（国产剧）
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
            SpiderDebug.log("[HkTvYb] homeContent error: " + e.getMessage());
            return "{\"class\":[], \"filters\":{}}";
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            String typeId = tid;
            // 如果有筛选条件，使用筛选的 type_id
            if (extend != null && extend.containsKey("type_id") && !extend.get("type_id").isEmpty()) {
                typeId = extend.get("type_id");
            }
            
            String url = apiUrl + "?ac=list&t=" + typeId + "&pg=" + pg;
            SpiderDebug.log("[HkTvYb] category URL: " + url);
            
            String response = get(url);
            if (response == null) {
                return "{\"list\":[], \"page\":" + pg + "}";
            }
            
            JSONObject json = new JSONObject(response);
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
            SpiderDebug.log("[HkTvYb] categoryContent error: " + e.getMessage());
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
            SpiderDebug.log("[HkTvYb] detail URL: " + url);
            
            String response = get(url);
            if (response == null) {
                return "{\"list\":[]}";
            }
            
            JSONObject json = new JSONObject(response);
            JSONArray list = json.optJSONArray("list");
            if (list == null || list.length() == 0) {
                return "{\"list\":[]}";
            }
            
            JSONObject item = list.getJSONObject(0);
            
            // 构建播放地址
            List<String> playFromList = new ArrayList<>();
            List<String> playUrlList = new ArrayList<>();
            
            // 按优先级添加线路：mp4, hkm3u8, YYNB
            String[] priorityPlayers = {"mp4", "hkm3u8", "YYNB"};
            
            for (String playerCode : priorityPlayers) {
                String playUrl = buildPlayUrl(item, playerCode);
                if (playUrl != null && !playUrl.isEmpty()) {
                    String playerName = playerMap.getOrDefault(playerCode, playerCode);
                    playFromList.add(playerName);
                    playUrlList.add(playUrl);
                }
            }
            
            // 添加其他可用线路
            for (Map.Entry<String, String> entry : playerMap.entrySet()) {
                String playerCode = entry.getKey();
                // 跳过已添加的
                boolean alreadyAdded = false;
                for (String added : priorityPlayers) {
                    if (added.equals(playerCode)) {
                        alreadyAdded = true;
                        break;
                    }
                }
                if (alreadyAdded) continue;
                
                String playUrl = buildPlayUrl(item, playerCode);
                if (playUrl != null && !playUrl.isEmpty()) {
                    playFromList.add(entry.getValue());
                    playUrlList.add(playUrl);
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
            SpiderDebug.log("[HkTvYb] detailContent error: " + e.getMessage());
            e.printStackTrace();
            return "{\"list\":[]}";
        }
    }
    
    /**
     * 构建播放 URL
     */
    private String buildPlayUrl(JSONObject item, String playerCode) {
        try {
            // 尝试获取对应线路的播放地址
            String playUrl = item.optString("vod_play_url_" + playerCode);
            if (playUrl == null || playUrl.isEmpty()) {
                // 尝试其他可能的字段名
                playUrl = item.optString(playerCode);
            }
            if (playUrl == null || playUrl.isEmpty()) {
                return null;
            }
            
            // 格式：集数名称$播放地址
            JSONArray episodes = new JSONArray();
            String[] urlParts = playUrl.split("#");
            for (String part : urlParts) {
                if (part.contains("$")) {
                    episodes.put(part);
                } else if (!part.isEmpty()) {
                    episodes.put("第" + (episodes.length() + 1) + "集$" + part);
                }
            }
            
            if (episodes.length() == 0) {
                return null;
            }
            
            return episodes.toString().replace("[", "").replace("]", "").replace("\"", "");
            
        } catch (Exception e) {
            SpiderDebug.log("[HkTvYb] buildPlayUrl error for " + playerCode + ": " + e.getMessage());
            return null;
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String searchUrl = baseUrl + "/index.php/ajax/suggest.html?mid=1&wd=" + URLEncoder.encode(key, "UTF-8");
            SpiderDebug.log("[HkTvYb] search URL: " + searchUrl);
            
            String response = get(searchUrl);
            if (response == null) {
                return "{\"list\":[]}";
            }
            
            JSONArray list = new JSONArray(response);
            JSONArray videos = new JSONArray();
            
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.getJSONObject(i);
                JSONObject vod = new JSONObject();
                vod.put("vod_id", item.optString("id"));
                vod.put("vod_name", item.optString("title"));
                vod.put("vod_pic", "");
                vod.put("vod_remarks", "");
                videos.put(vod);
            }
            
            JSONObject result = new JSONObject();
            result.put("list", videos);
            return result.toString();
            
        } catch (Exception e) {
            SpiderDebug.log("[HkTvYb] searchContent error: " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        SpiderDebug.log("[HkTvYb] playerContent flag=" + flag + ", id=" + id);
        
        try {
            // 如果 id 已经是完整的 URL，直接返回
            if (id.startsWith("http://") || id.startsWith("https://")) {
                // 检查是否是 m3u8 链接
                if (id.contains(".m3u8")) {
                    JSONObject result = new JSONObject();
                    result.put("parse", 0);
                    result.put("url", id);
                    return result.toString();
                }
                
                // 尝试使用解析线路
                String parsedUrl = tryParseWithJiexi(id);
                if (parsedUrl != null) {
                    JSONObject result = new JSONObject();
                    result.put("parse", 0);
                    result.put("url", parsedUrl);
                    return result.toString();
                }
            }
            
            // 如果 id 是解析 URL + 加密串的格式
            if (id.contains("|")) {
                String[] parts = id.split("\\|");
                if (parts.length >= 2) {
                    String parseUrl = parts[0];
                    String encrypted = parts[1];
                    String fullUrl = parseUrl + encrypted;
                    
                    String parsedUrl = tryParseWithJiexi(fullUrl);
                    if (parsedUrl != null) {
                        JSONObject result = new JSONObject();
                        result.put("parse", 0);
                        result.put("url", parsedUrl);
                        return result.toString();
                    }
                }
            }
            
            // 默认返回 parse=1，让壳子处理
            return "{\"parse\":1,\"url\":\"" + id + "\"}";
            
        } catch (Exception e) {
            SpiderDebug.log("[HkTvYb] playerContent error: " + e.getMessage());
            return "{\"parse\":1,\"url\":\"" + id + "\"}";
        }
    }
    
    /**
     * 尝试使用解析线路
     */
    private String tryParseWithJiexi(String url) {
        for (Map<String, String> jiexi : jiexiList) {
            String jiexiUrl = jiexi.get("url");
            if (jiexiUrl == null || jiexiUrl.isEmpty()) {
                continue;
            }
            try {
                String fullUrl = jiexiUrl + URLEncoder.encode(url, "UTF-8");
                SpiderDebug.log("[HkTvYb] 尝试解析: " + fullUrl);
                
                String response = get(fullUrl);
                if (response != null && !response.isEmpty()) {
                    JSONObject json = new JSONObject(response);
                    if (json.optInt("code") == 200) {
                        String videoUrl = json.optString("url");
                        if (videoUrl != null && !videoUrl.isEmpty() && videoUrl.startsWith("http")) {
                            SpiderDebug.log("[HkTvYb] 解析成功: " + videoUrl);
                            return videoUrl;
                        }
                    }
                }
            } catch (Exception e) {
                SpiderDebug.log("[HkTvYb] 解析失败: " + e.getMessage());
            }
        }
        return null;
    }
}
