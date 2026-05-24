import com.github.catvod.net.OkHttp;
import org.json.JSONArray;
import org.json.JSONObject;
import android.text.TextUtils;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

public class TmdbUtil {
    // 你的令牌
    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiIyOGMxMDJhODk3NjMwYzU3ZDNkZTAzMzAyZWVmZjQ4ZSIsIm5iZiI6MTc1OTIxOTI2MC40MjUsInN1YiI6IjY4ZGI4ZTNjNjFkNjhhY2NhNWUxYzNjZCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.n0-1IqRnmjYTv7PytAU2mNOSQxE2WF2E5SS_5MdzuOI";
    
    // 简单的内存缓存，防止重复搜索同一个词
    private static HashMap<String, String[]> cache = new HashMap<>();

    public static String[] getInfo(String title) {
        if (TextUtils.isEmpty(title)) return new String[]{title, ""};
        
        // 1. 检查缓存
        if (cache.containsKey(title)) return cache.get(title);

        try {
            String url = "https://api.themoviedb.org/3/search/multi?query=" 
                       + URLEncoder.encode(title, "UTF-8") 
                       + "&language=zh-CN&include_adult=false";

            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + TOKEN);
            headers.put("accept", "application/json");

            String json = OkHttp.string(url, headers);
            JSONObject response = new JSONObject(json);
            JSONArray results = response.optJSONArray("results");

            if (results != null && results.length() > 0) {
                JSONObject first = results.getJSONObject(0);
                
                // 自动识别电影标题或剧集名称
                String zhName = first.optString("title", first.optString("name", title));
                String posterPath = first.optString("poster_path", "");
                String fullPic = TextUtils.isEmpty(posterPath) ? "" : "https://image.tmdb.org/t/p/w500" + posterPath;

                String[] result = new String[]{zhName, fullPic};
                cache.put(title, result); // 存入缓存
                return result;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new String[]{title, ""};
    }
}
