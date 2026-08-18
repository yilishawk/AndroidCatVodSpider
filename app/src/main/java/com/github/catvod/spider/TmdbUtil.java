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
}
