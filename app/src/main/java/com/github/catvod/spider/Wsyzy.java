package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class Wsyzy extends Spider {

    // ========================================================
    // ==================== 配置区 开始 =======================
    // ========================================================

    // 主接口：负责分类 + 列表 + 详情主线路
    private static final String MAIN_API = "https://api.wsyzy.net/api.php/provide/vod";

    // 主接口 suggest 搜索（优先使用）
    private static final String SEARCH_API = "https://api.wsyzy.net/index.php/ajax/suggest.html?mid=1";

    // 副接口列表：要加新源只需在这里追加一行
    // SearchMode.PROVIDE_VOD → ?ac=videolist&wd=  （标准苹果CMS采集接口）
    // SearchMode.SUGGEST     → /index.php/ajax/suggest.html?mid=1&wd=  （suggest接口，baseUrl填根域名）
    private static final ExtraSource[] SOURCES = {
            new ExtraSource("备西瓜", "https://caiji.xgzyapi.com/api.php/provide/vod",                SearchMode.PROVIDE_VOD),
            new ExtraSource("备电影天堂", "http://caiji.dyttzyapi.com/api.php/provide/vod/from/dyttm3u8", SearchMode.PROVIDE_VOD),
            new ExtraSource("备悠悠资源", "https://uuzy.me/api.php/provide/vod",                    SearchMode.PROVIDE_VOD),
        
            // new ExtraSource("备3", "https://xxx.com/api.php/provide/vod",                       SearchMode.PROVIDE_VOD),
            // new ExtraSource("备4", "https://yyy.com",                                           SearchMode.SUGGEST),
    };

    // 主接口父分类（无数据），homeContent 里过滤掉
    private static final Set<String> HIDE_TYPE_IDS = new HashSet<>(Arrays.asList("1", "2", "3", "4"));

    // 成人内容关键词：分类名/视频名包含任意一个则屏蔽
    private static final String[] BLOCK_KEYWORDS = {
            "伦理", "三级", "色情", "写真", "热舞", "两性", "擦边",
            "成人", "情色", "福利", "AV", "av", "18+",
    };

    // 云播线路屏蔽开关：true = 只保留直链（m3u8/mp4），false = 全部保留
    private static final boolean BLOCK_CLOUD = true;

    // 副接口每个超时时间（秒）
    private static final int EXTRA_TIMEOUT = 3;

    // ========================================================
    // ==================== 配置区 结束 =======================
    // ========================================================

    private enum SearchMode {
        PROVIDE_VOD,
        SUGGEST
    }

    private static class ExtraSource {
        final String label;
        final String baseUrl;
        final SearchMode mode;

        ExtraSource(String label, String baseUrl, SearchMode mode) {
            this.label = label;
            this.baseUrl = baseUrl;
            this.mode = mode;
        }
    }

    // ========================================================
    // ==================== Spider 方法 =======================
    // ========================================================

    @Override
    public String homeContent(boolean filter) throws Exception {
        JSONObject root = new JSONObject(OkHttp.string(MAIN_API + "?ac=list"));
        JSONArray classArr = root.optJSONArray("class");

        List<Class> classes = new ArrayList<>();
        if (classArr != null) {
            for (int i = 0; i < classArr.length(); i++) {
                JSONObject c = classArr.getJSONObject(i);
                String tid = c.optString("type_id");
                String name = c.optString("type_name");
                if (HIDE_TYPE_IDS.contains(tid)) continue;
                if (isBlocked(name)) continue;
                classes.add(new Class(tid, name));
            }
        }

        return Result.get().classes(classes).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        JSONObject root = new JSONObject(OkHttp.string(MAIN_API + "?ac=videolist&t=" + tid + "&pg=" + pg));
        JSONArray list = root.optJSONArray("list");

        List<Vod> vods = new ArrayList<>();
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.getJSONObject(i);
                if (isBlocked(item.optString("vod_name"))) continue;
                vods.add(toVod(item));
            }
        }

        return Result.get()
                .page(root.optInt("page", 1), root.optInt("pagecount", 1),
                        root.optInt("limit", 20), root.optInt("total", vods.size()))
                .vod(vods)
                .string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        JSONObject root = new JSONObject(OkHttp.string(MAIN_API + "?ac=detail&ids=" + ids.get(0)));
        JSONArray list = root.optJSONArray("list");
        if (list == null || list.length() == 0) return Result.get().string();

        JSONObject item = list.getJSONObject(0);
        String title = item.optString("vod_name");

        Vod vod = new Vod();
        vod.setVodId(item.optString("vod_id"));
        vod.setVodName(title);
        vod.setVodPic(item.optString("vod_pic"));
        vod.setTypeName(item.optString("type_name"));
        vod.setVodYear(item.optString("vod_year"));
        vod.setVodArea(item.optString("vod_area"));
        vod.setVodDirector(item.optString("vod_director"));
        vod.setVodActor(item.optString("vod_actor"));
        vod.setVodRemarks(item.optString("vod_remarks"));
        vod.setVodContent(item.optString("vod_content"));

        // 主线路
        List<String> fromList = new ArrayList<>();
        List<String> urlList = new ArrayList<>();
        splitPlay(item.optString("vod_play_from"), item.optString("vod_play_url"), fromList, urlList, null);

        // 并发查所有副接口
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, SOURCES.length));
        List<Future<List<String[]>>> futures = new ArrayList<>();
        for (int i = 0; i < SOURCES.length; i++) {
            final int idx = i;
            futures.add(pool.submit((Callable<List<String[]>>) () -> searchExtra(idx, title)));
        }
        for (Future<List<String[]>> f : futures) {
            try {
                for (String[] pair : f.get(EXTRA_TIMEOUT, TimeUnit.SECONDS)) {
                    fromList.add(pair[0]);
                    urlList.add(pair[1]);
                }
            } catch (Exception e) {
                System.out.println("⚠️ [多源] 副接口超时/失败: " + e.getMessage());
            }
        }
        pool.shutdownNow();

        vod.setVodPlayFrom(String.join("$$$", fromList));
        vod.setVodPlayUrl(String.join("$$$", urlList));

        List<Vod> resultVods = new ArrayList<>();
        resultVods.add(vod);
        return Result.get().vod(resultVods).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        String encodeKey = URLEncoder.encode(key, "UTF-8");
        List<Vod> vods = new ArrayList<>();

        // 优先 suggest 接口
        try {
            JSONObject root = new JSONObject(OkHttp.string(SEARCH_API + "&wd=" + encodeKey));
            JSONArray list = root.optJSONArray("list");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject it = list.getJSONObject(i);
                    if (isBlocked(it.optString("name"))) continue;
                    vods.add(searchToVod(it));
                }
            }
        } catch (Exception e) {
            System.out.println("⚠️ [主接口] suggest 搜索失败: " + e.getMessage());
        }

        // suggest 没结果则降级到主接口标准搜索
        if (vods.isEmpty()) {
            try {
                JSONObject root = new JSONObject(OkHttp.string(MAIN_API + "?ac=videolist&wd=" + encodeKey));
                JSONArray list = root.optJSONArray("list");
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject it = list.getJSONObject(i);
                        if (isBlocked(it.optString("vod_name"))) continue;
                        vods.add(toVod(it));
                    }
                }
            } catch (Exception e) {
                System.out.println("❌ [主接口] 标准搜索也失败: " + e.getMessage());
            }
        }

        return Result.get().vod(vods).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return Result.get().parse(0).url(id).string();
    }

    // ========================================================
    // ==================== 内部辅助方法 ======================
    // ========================================================

    private static boolean isBlocked(String name) {
        if (name == null || name.isEmpty()) return false;
        for (String kw : BLOCK_KEYWORDS) {
            if (name.contains(kw)) return true;
        }
        return false;
    }

    private static boolean isDirectLink(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();
        return lower.contains(".m3u8") || lower.contains(".mp4");
    }

    private static void splitPlay(String playFrom, String playUrl,
                                  List<String> fromOut, List<String> urlOut, String prefix) {
        if (playFrom == null || playUrl == null) return;
        String[] fromArr = playFrom.split("\\$\\$\\$");
        String[] urlArr = playUrl.split("\\$\\$\\$");
        int n = Math.min(fromArr.length, urlArr.length);

        for (int i = 0; i < n; i++) {
            String from = fromArr[i].trim();
            String episodes = urlArr[i].trim();
            if (from.isEmpty() || episodes.isEmpty()) continue;

            if (BLOCK_CLOUD) {
                String[] epArr = episodes.split("#");
                boolean hasDirectEp = false;
                for (String ep : epArr) {
                    String[] parts = ep.split("\\$");
                    String url = parts.length > 1 ? parts[parts.length - 1] : parts[0];
                    if (isDirectLink(url)) { hasDirectEp = true; break; }
                }
                if (!hasDirectEp) continue;
            }

            fromOut.add(prefix == null ? from : "[" + prefix + "]" + from);
            urlOut.add(episodes);
        }
    }

    private static Vod toVod(JSONObject item) {
        Vod vod = new Vod();
        vod.setVodId(item.optString("vod_id"));
        vod.setVodName(item.optString("vod_name"));
        vod.setVodPic(item.optString("vod_pic"));
        vod.setVodRemarks(item.optString("vod_remarks"));
        return vod;
    }

    private static Vod searchToVod(JSONObject item) {
        Vod vod = new Vod();
        vod.setVodId(item.optString("id"));
        vod.setVodName(item.optString("name"));
        vod.setVodPic(item.optString("pic"));
        vod.setVodRemarks("");
        return vod;
    }

    private static String normalize(String name) {
        if (name == null) return "";
        String s = name.trim().replaceAll("[\\s　]+", "");
        s = s.replaceAll("[（(][^（）()]*[）)]\\s*$", "");
        return s.toLowerCase();
    }

    private static List<String[]> searchExtra(int idx, String title) {
        List<String[]> out = new ArrayList<>();
        ExtraSource src = SOURCES[idx];
        try {
            String wd = URLEncoder.encode(title, "UTF-8");
            String target = normalize(title);
            String matchId = null;

            if (src.mode == SearchMode.SUGGEST) {
                String suggestUrl = src.baseUrl + "/index.php/ajax/suggest.html?mid=1&wd=" + wd;
                JSONObject obj = new JSONObject(OkHttp.string(suggestUrl));
                JSONArray list = obj.optJSONArray("list");
                if (list != null) {
                    for (int j = 0; j < list.length(); j++) {
                        JSONObject it = list.getJSONObject(j);
                        if (target.equals(normalize(it.optString("name")))) {
                            matchId = it.optString("id");
                            break;
                        }
                    }
                }
                if (matchId != null) {
                    String detailBase = src.baseUrl + "/api.php/provide/vod";
                    JSONObject dObj = new JSONObject(OkHttp.string(detailBase + "?ac=detail&ids=" + matchId));
                    JSONArray dList = dObj.optJSONArray("list");
                    if (dList != null && dList.length() > 0) {
                        JSONObject extraVod = dList.getJSONObject(0);
                        List<String> fromOut = new ArrayList<>();
                        List<String> urlOut = new ArrayList<>();
                        splitPlay(extraVod.optString("vod_play_from"), extraVod.optString("vod_play_url"), fromOut, urlOut, src.label);
                        for (int k = 0; k < fromOut.size(); k++) out.add(new String[]{fromOut.get(k), urlOut.get(k)});
                    }
                }

            } else {
                JSONObject obj = new JSONObject(OkHttp.string(src.baseUrl + "?ac=videolist&wd=" + wd));
                JSONArray list = obj.optJSONArray("list");
                if (list != null) {
                    for (int j = 0; j < list.length(); j++) {
                        JSONObject it = list.getJSONObject(j);
                        if (target.equals(normalize(it.optString("vod_name")))) {
                            matchId = it.optString("vod_id");
                            break;
                        }
                    }
                }
                if (matchId != null) {
                    JSONObject dObj = new JSONObject(OkHttp.string(src.baseUrl + "?ac=detail&ids=" + matchId));
                    JSONArray dList = dObj.optJSONArray("list");
                    if (dList != null && dList.length() > 0) {
                        JSONObject extraVod = dList.getJSONObject(0);
                        List<String> fromOut = new ArrayList<>();
                        List<String> urlOut = new ArrayList<>();
                        splitPlay(extraVod.optString("vod_play_from"), extraVod.optString("vod_play_url"), fromOut, urlOut, src.label);
                        for (int k = 0; k < fromOut.size(); k++) out.add(new String[]{fromOut.get(k), urlOut.get(k)});
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("❌ [多源] " + src.label + " 查询异常: " + e.getMessage());
        }
        return out;
    }
}
