package com.github.catvod.utils;

import android.text.TextUtils;
import com.github.catvod.net.OkHttp;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TmdbUtil {
    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiIyOGMxMDJhODk3NjMwYzU3ZDNkZTAzMzAyZWVmZjQ4ZSIsIm5iZiI6MTc1OTIxOTI2MC40MjUsInN1YiI6IjY4ZGI4ZTNjNjFkNjhhY2NhNWUxYzNjZCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.n0-1IqRnmjYTv7PytAU2mNOSQxE2WF2E5SS_5MdzuOI";
    
    // 线程安全的内存缓存
    private static final ConcurrentHashMap<String, String[]> cache = new ConcurrentHashMap<>();

    // 中文标题 -> 英文标题 缓存 (英文站搜索用)
    private static final ConcurrentHashMap<String, String> enCache = new ConcurrentHashMap<>();

    /**
     * 规范化搜索关键词（清理季数、语言版本标示，提升 TMDB 命中率）
     */
    public static String normalizeSearchTitle(String title) {
        if (TextUtils.isEmpty(title)) return "";
        String s = title.trim();
        // 清理括号及后缀
        s = s.replaceAll("[(（](粤|国|英|日|韩)语?版?[)）]", "");
        s = s.replaceAll("(?i)(4k|1080p|hd|bd|web-dl|x264|x255)", "");
        // 将 第X季 规范化
        Pattern seasonPattern = Pattern.compile("第([0-9]+)季");
        Matcher seasonMatcher = seasonPattern.matcher(s);
        StringBuffer seasonBuf = new StringBuffer();
        while (seasonMatcher.find()) {
            int num = Integer.parseInt(seasonMatcher.group(1));
            seasonMatcher.appendReplacement(seasonBuf, "Season " + num);
        }
        seasonMatcher.appendTail(seasonBuf);
        return seasonBuf.toString().trim();
    }

    /**
     * 获取多语言元数据 [中文标题, 海报全路径]
     */
    public static String[] getInfo(String title) {
        if (TextUtils.isEmpty(title)) return new String[]{title, ""};
        
        String cleanTitle = normalizeSearchTitle(title);
        if (cache.containsKey(cleanTitle)) return cache.get(cleanTitle);

        try {
            String url = "https://api.themoviedb.org/3/search/multi?query=" 
                       + URLEncoder.encode(cleanTitle, "UTF-8") 
                       + "&language=zh-CN&include_adult=false";

            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + TOKEN);
            headers.put("accept", "application/json");

            String json = OkHttp.string(url, headers);
            if (TextUtils.isEmpty(json)) return new String[]{title, ""};

            JSONObject response = new JSONObject(json);
            JSONArray results = response.optJSONArray("results");

            if (results != null && results.length() > 0) {
                JSONObject first = results.getJSONObject(0);
                
                // 自动提取电影 title 或剧集 name
                String zhName = first.optString("title", first.optString("name", title));
                String posterPath = first.optString("poster_path", "");
                String fullPic = TextUtils.isEmpty(posterPath) ? "" : "https://image.tmdb.org/t/p/w500" + posterPath;

                String[] result = new String[]{zhName, fullPic};
                cache.put(cleanTitle, result);
                return result;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new String[]{title, ""};
    }

    /**
     * 快捷方法：仅获取海报地址
     */
    public static String getPosterUrl(String title) {
        return getInfo(title)[1];
    }

    /**
     * 快捷方法：仅获取中文标题
     */
    public static String getZhTitle(String title) {
        return getInfo(title)[0];
    }

    // ==================== 中文标题 -> 英文标题 (供英文站 searchContent 使用) ====================

    /**
     * 拿条目的英文标题. 两级检索:
     *   ① search/multi?language=en-US —— 条目在 TMDB 英文索引里时直接拿到英文 title/name;
     *   ② ① 拿不到拉丁字母标题时, 用 zh-CN 检索拿 id + media_type, 再取
     *      /3/{movie|tv}/{id}?language=en-US 的 title/name / original_title.
     * 两级都失败(或 TMDB 不通)返回原样入参, 由调用方决定回退策略 —— 不抛异常.
     */
    public static String getEnTitle(String title) {
        if (TextUtils.isEmpty(title)) return title;
        String clean = normalizeSearchTitle(title);
        if (enCache.containsKey(clean)) return enCache.get(clean);
        String en = "";
        try {
            JSONObject first = searchFirst(clean, "en-US");
            if (first != null) en = pickEn(first);
            if (TextUtils.isEmpty(en) || !isLatin(en)) {
                JSONObject zh = searchFirst(clean, "zh-CN");
                if (zh != null) {
                    String type = zh.optString("media_type", "");
                    String id = zh.optString("id", "");
                    if (!TextUtils.isEmpty(id) && ("movie".equals(type) || "tv".equals(type))) {
                        JSONObject detail = tmdb("/3/" + type + "/" + id + "?language=en-US");
                        if (detail != null) en = pickEn(detail);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (TextUtils.isEmpty(en)) en = clean;
        enCache.put(clean, en);
        return en;
    }

    /** search/multi 的首条结果, 无结果/请求失败返回 null */
    private static JSONObject searchFirst(String query, String lang) {
        try {
            String url = "https://api.themoviedb.org/3/search/multi?query="
                    + URLEncoder.encode(query, "UTF-8")
                    + "&language=" + lang + "&include_adult=false";
            String json = OkHttp.string(url, authHeader());
            if (TextUtils.isEmpty(json)) return null;
            JSONArray results = new JSONObject(json).optJSONArray("results");
            if (results == null || results.length() == 0) return null;
            return results.getJSONObject(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** 取 TMDB 详情, 失败返回 null */
    private static JSONObject tmdb(String path) {
        try {
            String json = OkHttp.string("https://api.themoviedb.org" + path, authHeader());
            return TextUtils.isEmpty(json) ? null : new JSONObject(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, String> authHeader() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + TOKEN);
        headers.put("accept", "application/json");
        return headers;
    }

    /** 优先拉丁字母标题: title/name -> original_title/original_name */
    private static String pickEn(JSONObject obj) {
        String en = obj.optString("title", obj.optString("name", ""));
        String origin = obj.optString("original_title", obj.optString("original_name", ""));
        if (isLatin(en)) return en;
        if (isLatin(origin)) return origin;
        return TextUtils.isEmpty(en) ? origin : en;
    }

    /** 含拉丁字母且不含中日韩汉字 —— 用来判断拿到的到底是不是英文标题 */
    private static boolean isLatin(String s) {
        if (TextUtils.isEmpty(s)) return false;
        return s.matches(".*[A-Za-z].*") && !s.matches(".*[\\u4e00-\\u9fa5].*");
    }
}
